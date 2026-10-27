import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { db } from "./db.js";
import { env } from "./env.js";
import { hub } from "./hub.js";
import { safeEqual, generateApiKey, generateCode } from "./keys.js";
import { bindServerToChannel, CHANNEL_KINDS, isBlocked as blocked, recordFailure } from "./bind.js";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const one = (x: any) => (Array.isArray(x) ? x[0] : x);

export function isBotAuthorized(header: string | undefined): boolean {
  const key = header?.startsWith("Bearer ") ? header.slice(7).trim() : "";
  return key.length > 0 && safeEqual(key, env.BOT_SECRET);
}

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


// Until the website dashboard exists, every server belongs to one system owner account.
let cachedOwnerId: string | undefined;
async function getOwnerId(): Promise<string> {
  if (cachedOwnerId) return cachedOwnerId;
  const email = "dev-owner@flowt.local";
  const { data: list, error: listErr } = await db.auth.admin.listUsers({ page: 1, perPage: 200 });
  if (listErr) throw listErr;
  let id = list.users.find((u) => u.email === email)?.id;
  if (!id) {
    const { data, error } = await db.auth.admin.createUser({ email, email_confirm: true });
    if (error) throw error;
    id = data.user.id;
  }
  cachedOwnerId = id;
  return id;
}

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

      await bindServerToChannel(row.server_id, guildId, channelId);
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

    // ---- Discord-initiated flows (mirror of the in-game commands) ----

    // /flowt setup (no code): bot makes a code, admin runs "/flowt setup <code>" in Minecraft
    bot.post("/v1/bot/setup/start", async (req, reply) => {
      const body = z
        .object({ guildId: z.string().regex(SNOWFLAKE), channelId: z.string().regex(SNOWFLAKE), userId: z.string().regex(SNOWFLAKE) })
        .safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { guildId, channelId, userId } = body.data;
      await db.from("discord_setup_codes").delete().eq("guild_id", guildId);
      const code = generateCode(8);
      const { error } = await db.from("discord_setup_codes").insert({
        code,
        guild_id: guildId,
        channel_id: channelId,
        created_by: userId,
        expires_at: new Date(Date.now() + 30 * 60_000).toISOString(),
      });
      if (error) throw error;
      return { code, expiresInMinutes: 30 };
    });

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

    // /flowt create: make a new server + API key straight from Discord (no Node / scripts needed)
    bot.post("/v1/bot/server/create", async (req, reply) => {
      const body = z
        .object({
          guildId: z.string().regex(SNOWFLAKE),
          channelId: z.string().regex(SNOWFLAKE),
          userId: z.string().regex(SNOWFLAKE),
          name: z.string().trim().min(2).max(48),
          isPublic: z.boolean().default(false),
        })
        .safeParse(req.body);
      if (!body.success) return reply.code(400).send({ error: "invalid_request" });
      const { guildId, channelId, userId, name, isPublic } = body.data;

      const perGuild = await db.from("servers").select("id", { count: "exact", head: true }).eq("discord_guild_id", guildId);
      if ((perGuild.count ?? 0) >= 3) return reply.code(409).send({ error: "guild_limit" });
      const perUser = await db.from("servers").select("id", { count: "exact", head: true }).eq("created_by_discord_id", userId);
      if ((perUser.count ?? 0) >= 5) return reply.code(409).send({ error: "user_limit" });

      const ownerId = await getOwnerId();
      const { data: server, error: sErr } = await db
        .from("servers")
        .insert({ owner_id: ownerId, name, is_public: isPublic, created_by_discord_id: userId })
        .select("id")
        .single();
      if (sErr) throw sErr;

      const host = env.PUBLIC_HOST ?? process.env.RENDER_EXTERNAL_HOSTNAME ?? req.headers.host ?? "";
      if (!host) throw new Error("API host unknown: set PUBLIC_HOST");
      const { key, hash, prefix } = generateApiKey(host);
      const { error: kErr } = await db.from("server_keys").insert({ server_id: server.id, key_hash: hash, key_prefix: prefix });
      if (kErr) throw kErr;

      await bindServerToChannel(server.id, guildId, channelId);
      return { apiKey: key, serverId: server.id, name };
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
