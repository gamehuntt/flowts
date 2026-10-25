import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { db } from "./db.js";
import { env } from "./env.js";
import { hub } from "./hub.js";
import { safeEqual } from "./keys.js";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const one = (x: any) => (Array.isArray(x) ? x[0] : x);

export function isBotAuthorized(header: string | undefined): boolean {
  const key = header?.startsWith("Bearer ") ? header.slice(7).trim() : "";
  return key.length > 0 && safeEqual(key, env.BOT_SECRET);
}

// Brute-force protection for one-time codes: max 10 wrong codes per hour per Discord guild/user
const failures = new Map<string, { count: number; reset: number }>();
const blocked = (id: string) => {
  const f = failures.get(id);
  return !!f && f.reset > Date.now() && f.count >= 10;
};
const recordFailure = (id: string) => {
  const now = Date.now();
  const f = failures.get(id);
  if (!f || f.reset <= now) failures.set(id, { count: 1, reset: now + 3_600_000 });
  else f.count++;
};

const SNOWFLAKE = /^\d{5,25}$/;
const setupBody = z.object({
  code: z.string().min(4).max(16),
  guildId: z.string().regex(SNOWFLAKE),
  channelId: z.string().regex(SNOWFLAKE),
});
const linkBody = z.object({
  code: z.string().min(4).max(16),
  discordId: z.string().regex(SNOWFLAKE),
});

const CHANNEL_KINDS = ["chat", "join_leave", "death", "advancement"] as const;

/** All /v1/bot/* REST routes. Every route here requires the BOT_SECRET. */
export function registerBotRoutes(app: FastifyInstance) {
  app.register(async (bot) => {
    bot.addHook("preHandler", async (req, reply) => {
      if (!isBotAuthorized(req.headers.authorization)) {
        return reply.code(401).send({ error: "unauthorized" });
      }
    });

    // Which Discord channel belongs to which Minecraft server (the bot caches this)
    bot.get("/v1/bot/routes", async () => {
      const routes: { serverId: string; serverName: string; kind: string; channelId: string }[] = [];
      for (let from = 0; ; from += 1000) {
        const { data, error } = await db
          .from("server_channels")
          .select("server_id, kind, discord_channel_id, servers(name)")
          .order("id")
          .range(from, from + 999);
        if (error) throw error;
        for (const r of data ?? []) {
          routes.push({
            serverId: r.server_id,
            serverName: one(r.servers)?.name ?? "Unknown",
            kind: r.kind,
            channelId: r.discord_channel_id,
          });
        }
        if (!data || data.length < 1000) break;
      }
      return { routes };
    });

    // /flowt setup <code>: connect a Discord channel to a Minecraft server
    bot.post("/v1/bot/setup", async (req, reply) => {
      const body = setupBody.safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { code, guildId, channelId } = body.data;
      if (blocked(guildId)) return reply.code(429).send({ error: "too_many_attempts" });

      const { data: row, error } = await db
        .from("setup_codes")
        .select("code, server_id, expires_at, servers(name)")
        .eq("code", code.toUpperCase())
        .maybeSingle();
      if (error) throw error;
      if (!row || new Date(row.expires_at) < new Date()) {
        recordFailure(guildId);
        return reply.code(404).send({ error: "invalid_or_expired_code" });
      }

      const { error: e1 } = await db.from("servers").update({ discord_guild_id: guildId }).eq("id", row.server_id);
      if (e1) throw e1;
      const { error: e2 } = await db
        .from("server_channels")
        .delete()
        .eq("server_id", row.server_id)
        .in("kind", [...CHANNEL_KINDS]);
      if (e2) throw e2;
      const { error: e3 } = await db
        .from("server_channels")
        .insert(CHANNEL_KINDS.map((kind) => ({ server_id: row.server_id, kind, discord_channel_id: channelId })));
      if (e3) throw e3;
      await db.from("setup_codes").delete().eq("code", row.code);

      hub.send(row.server_id, { type: "notice", text: "Discord channel linked to Flowt." });
      return { serverId: row.server_id, serverName: one(row.servers)?.name ?? "Unknown" };
    });

    // /link <code>: connect a Discord account to a Minecraft account
    bot.post("/v1/bot/link", async (req, reply) => {
      const body = linkBody.safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { code, discordId } = body.data;
      if (blocked(discordId)) return reply.code(429).send({ error: "too_many_attempts" });

      const { data: row, error } = await db
        .from("link_codes")
        .select("code, mc_uuid, server_id, expires_at, players(username), servers(name)")
        .eq("code", code.toUpperCase())
        .maybeSingle();
      if (error) throw error;
      if (!row || new Date(row.expires_at) < new Date()) {
        recordFailure(discordId);
        return reply.code(404).send({ error: "invalid_or_expired_code" });
      }

      const { error: upErr } = await db
        .from("players")
        .update({ discord_id: discordId, linked_at: new Date().toISOString() })
        .eq("mc_uuid", row.mc_uuid);
      if (upErr) {
        if (upErr.code === "23505") return reply.code(409).send({ error: "discord_already_linked" });
        throw upErr;
      }
      await db.from("link_codes").delete().eq("mc_uuid", row.mc_uuid);

      if (row.server_id) hub.send(row.server_id, { type: "link.success", uuid: row.mc_uuid, discordId });
      return { username: one(row.players)?.username ?? "?", serverName: one(row.servers)?.name ?? null };
    });

    // /list and /flowt status: Minecraft servers connected to this Discord server
    bot.get<{ Querystring: { guildId?: string } }>("/v1/bot/guild", async (req, reply) => {
      const guildId = req.query.guildId ?? "";
      if (!SNOWFLAKE.test(guildId)) return reply.code(400).send({ error: "invalid_request" });
      const { data, error } = await db
        .from("servers")
        .select("id, name, online_players, max_players")
        .eq("discord_guild_id", guildId)
        .limit(25);
      if (error) throw error;
      // Linked Discord accounts of the players that are online right now
      const allNames = [...new Set((data ?? []).flatMap((s) => hub.getPlayers(s.id) ?? []))];
      const linked: Record<string, string> = {};
      if (allNames.length > 0) {
        const { data: rows } = await db
          .from("players")
          .select("username, discord_id")
          .in("username", allNames)
          .not("discord_id", "is", null);
        for (const r of rows ?? []) if (r.discord_id) linked[String(r.username).toLowerCase()] = r.discord_id;
      }
      return {
        servers: (data ?? []).map((s) => {
          const names = hub.getPlayers(s.id);
          return {
            id: s.id,
            name: s.name,
            online: hub.isOnline(s.id),
            onlinePlayers: names ? names.length : s.online_players,
            maxPlayers: s.max_players,
            players: names ?? [],
            discordIds: Object.fromEntries(
              (names ?? []).filter((n) => linked[n.toLowerCase()]).map((n) => [n, linked[n.toLowerCase()]]),
            ),
          };
        }),
      };
    });
  });
}
