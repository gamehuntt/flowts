import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { db } from "./db.js";
import { env } from "./env.js";
import { hub } from "./hub.js";
import { safeEqual, generateCode } from "./keys.js";
import { checkLimits } from "./provision.js";
import { bindServerToChannel, CHANNEL_KINDS, isBlocked as blocked, recordFailure } from "./bind.js";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const one = (x: any) => (Array.isArray(x) ? x[0] : x);

export function isBotAuthorized(header: string | undefined): boolean {
  const key = header?.startsWith("Bearer ") ? header.slice(7).trim() : "";
  return key.length > 0 && safeEqual(key, env.BOT_SECRET);
}

const SNOWFLAKE = /^\d{5,25}$/;
const connectBody = z.object({
  code: z.string().min(4).max(16),
  guildId: z.string().regex(SNOWFLAKE),
  channelId: z.string().regex(SNOWFLAKE),
  userId: z.string().regex(SNOWFLAKE),
});
const linkBody = z.object({
  code: z.string().min(4).max(16),
  discordId: z.string().regex(SNOWFLAKE),
});


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
    // /connect <code>: a code made in Minecraft (by an already-connected server, or by a brand-new plugin)
    bot.post("/v1/bot/connect", async (req, reply) => {
      const body = connectBody.safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { code, guildId, channelId, userId } = body.data;
      if (blocked(userId)) return reply.code(429).send({ error: "too_many_attempts" });
      const upper = code.trim().toUpperCase();
      const now = new Date();

      // 1) code from a server that already has a key (it just wants a different channel / Discord)
      const { data: sc, error: scErr } = await db
        .from("setup_codes")
        .select("code, server_id, expires_at, servers(name)")
        .eq("code", upper)
        .maybeSingle();
      if (scErr) throw scErr;
      if (sc && new Date(sc.expires_at) >= now) {
        await bindServerToChannel(sc.server_id, guildId, channelId);
        await db.from("setup_codes").delete().eq("code", sc.code);
        hub.send(sc.server_id, { type: "notice", text: "Discord channel linked to Flowt." });
        return { status: "connected", serverName: one(sc.servers)?.name ?? "Unknown" };
      }

      // 2) code from a brand-new plugin: remember who entered it, the plugin picks it up and gets its key
      const { data: pc, error: pcErr } = await db.from("pair_codes").select("*").eq("code", upper).eq("kind", "minecraft").maybeSingle();
      if (pcErr) throw pcErr;
      if (pc && !pc.guild_id && new Date(pc.expires_at) >= now) {
        const limit = await checkLimits(guildId, userId);
        if (limit) return reply.code(409).send({ error: limit });
        await db.from("pair_codes").update({ guild_id: guildId, channel_id: channelId, discord_user_id: userId }).eq("code", upper);
        return { status: "pending", serverName: pc.server_name ?? "Minecraft Server" };
      }

      recordFailure(userId);
      return reply.code(404).send({ error: "invalid_or_expired_code" });
    });

    // /connect (no code): make a code, then run /connect <code> in Minecraft (plugin with or without a key)
    bot.post("/v1/bot/connect/start", async (req, reply) => {
      const body = z
        .object({ guildId: z.string().regex(SNOWFLAKE), channelId: z.string().regex(SNOWFLAKE), userId: z.string().regex(SNOWFLAKE) })
        .safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { guildId, channelId, userId } = body.data;
      await db.from("pair_codes").delete().lt("expires_at", new Date().toISOString());
      await db.from("pair_codes").delete().eq("kind", "discord").eq("guild_id", guildId).eq("discord_user_id", userId);
      const code = generateCode(8);
      const { error } = await db.from("pair_codes").insert({
        code,
        kind: "discord",
        guild_id: guildId,
        channel_id: channelId,
        discord_user_id: userId,
        expires_at: new Date(Date.now() + 15 * 60_000).toISOString(),
      });
      if (error) throw error;
      return { code, expiresInMinutes: 15 };
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

    // ---- Discord-initiated account link ----

    // /link (no code): bot makes a code, player runs "/discord link <code>" in Minecraft
    bot.post("/v1/bot/link/start", async (req, reply) => {
      const body = z.object({ discordId: z.string().regex(SNOWFLAKE) }).safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { discordId } = body.data;
      const { data: existing } = await db.from("players").select("username").eq("discord_id", discordId).maybeSingle();
      if (existing) return reply.code(409).send({ error: "already_linked", username: existing.username });
      await db.from("discord_link_codes").delete().eq("discord_id", discordId);
      const code = generateCode(6);
      const { error } = await db.from("discord_link_codes").insert({
        code,
        discord_id: discordId,
        expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
      });
      if (error) throw error;
      return { code, expiresInMinutes: 10 };
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
