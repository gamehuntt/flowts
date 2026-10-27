import type { FastifyInstance } from "fastify";
import { z } from "zod";
import { createHash, randomBytes } from "node:crypto";
import { db } from "./db.js";
import { generateCode, safeEqual } from "./keys.js";
import { isBlocked, recordFailure } from "./bind.js";
import { provisionServer, ProvisionError, apiHost } from "./provision.js";
import { botHub } from "./hub.js";

const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const nameSchema = z.string().trim().min(1).max(48);
const PAIR_MINUTES = 15;

/**
 * Pairing for a plugin that has no API key yet. These are the only routes that work without a key:
 *  - /v1/pair/claim : the plugin has a code made in Discord (/connect)           -> gets its key right away
 *  - /v1/pair/start : the plugin makes a code, a Discord admin enters it         -> plugin polls for its key
 *  - /v1/pair/poll
 * Codes are one-time, short-lived, and wrong guesses are rate-limited per IP.
 */
export function registerPairRoutes(app: FastifyInstance) {
  app.post("/v1/pair/claim", { config: { rateLimit: { max: 10, timeWindow: "1 minute" } } }, async (req, reply) => {
    const body = z.object({ code: z.string().min(4).max(16), name: nameSchema }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "invalid_request" });
    const ipKey = "ip:" + req.ip;
    if (isBlocked(ipKey)) return reply.code(429).send({ error: "too_many_attempts" });

    // take the code atomically so it can only be used once
    const { data: row, error } = await db
      .from("pair_codes")
      .delete()
      .eq("code", body.data.code.trim().toUpperCase())
      .eq("kind", "discord")
      .gt("expires_at", new Date().toISOString())
      .select()
      .maybeSingle();
    if (error) throw error;
    if (!row) {
      recordFailure(ipKey);
      return reply.code(404).send({ error: "invalid_or_expired_code" });
    }
    try {
      const s = await provisionServer({
        name: body.data.name,
        guildId: row.guild_id,
        channelId: row.channel_id,
        userId: row.discord_user_id,
        host: apiHost(req),
      });
      botHub.send({ type: "setup.done", serverName: s.name, channelId: row.channel_id });
      return { apiKey: s.apiKey, serverName: s.name };
    } catch (e) {
      if (e instanceof ProvisionError) return reply.code(409).send({ error: e.code });
      throw e;
    }
  });

  app.post("/v1/pair/start", { config: { rateLimit: { max: 6, timeWindow: "1 minute" } } }, async (req, reply) => {
    const body = z.object({ name: nameSchema }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "invalid_request" });
    await db.from("pair_codes").delete().lt("expires_at", new Date().toISOString());

    const code = generateCode(8);
    const pollToken = randomBytes(24).toString("base64url");
    const { error } = await db.from("pair_codes").insert({
      code,
      kind: "minecraft",
      server_name: body.data.name,
      poll_hash: sha(pollToken),
      expires_at: new Date(Date.now() + PAIR_MINUTES * 60_000).toISOString(),
    });
    if (error) throw error;
    return { code, pollToken, expiresInMinutes: PAIR_MINUTES };
  });

  app.post("/v1/pair/poll", { config: { rateLimit: { max: 40, timeWindow: "1 minute" } } }, async (req, reply) => {
    const body = z.object({ code: z.string().min(4).max(16), pollToken: z.string().min(8).max(100) }).safeParse(req.body);
    if (!body.success) return reply.code(400).send({ error: "invalid_request" });
    const code = body.data.code.trim().toUpperCase();

    const { data: row, error } = await db.from("pair_codes").select("*").eq("code", code).eq("kind", "minecraft").maybeSingle();
    if (error) throw error;
    if (!row || !row.poll_hash || !safeEqual(row.poll_hash, sha(body.data.pollToken))) return { status: "expired" };
    if (new Date(row.expires_at) < new Date()) {
      await db.from("pair_codes").delete().eq("code", code);
      return { status: "expired" };
    }
    if (!row.guild_id) return { status: "pending" };

    // a Discord admin entered the code: take it atomically, then create the server + key
    const { data: taken } = await db.from("pair_codes").delete().eq("code", code).not("guild_id", "is", null).select().maybeSingle();
    if (!taken) return { status: "pending" };
    try {
      const s = await provisionServer({
        name: taken.server_name ?? "Minecraft Server",
        guildId: taken.guild_id,
        channelId: taken.channel_id,
        userId: taken.discord_user_id,
        host: apiHost(req),
      });
      botHub.send({ type: "setup.done", serverName: s.name, channelId: taken.channel_id });
      return { status: "claimed", apiKey: s.apiKey, serverName: s.name };
    } catch (e) {
      if (e instanceof ProvisionError) return { status: "error", error: e.code };
      throw e;
    }
  });
}
