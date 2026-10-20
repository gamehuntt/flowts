import Fastify from "fastify";
import websocket from "@fastify/websocket";
import rateLimit from "@fastify/rate-limit";
import { env } from "./env.js";
import { db } from "./db.js";
import { hashKey, generateCode } from "./keys.js";
import { pluginMessage, botMessage, type TeamSnapshotTeam } from "./protocol.js";
import { hub, botHub } from "./hub.js";
import { bus } from "./bus.js";
import { registerPublicRoutes } from "./routes.js";
import { registerBotRoutes, isBotAuthorized } from "./botRoutes.js";

declare module "fastify" {
  interface FastifyRequest {
    flowtServer?: { id: string; name: string };
  }
}

const app = Fastify({ logger: true });

await app.register(rateLimit, { max: 120, timeWindow: "1 minute" });
await app.register(websocket, { options: { maxPayload: 1_048_576 } }); // 1 MB per message

registerPublicRoutes(app);
registerBotRoutes(app);

// Minecraft -> Discord: forward chat and game events to the bot (dropped if the bot is offline)
bus.on("chat", (e) => botHub.send(e));
bus.on("event", (e) => botHub.send(e));

/** Removes duplicate team names / players that would break the database constraints. */
function sanitizeSnapshot(teams: TeamSnapshotTeam[]): TeamSnapshotTeam[] {
  const seenTeams = new Set<string>();
  const seenPlayers = new Set<string>();
  const out: TeamSnapshotTeam[] = [];
  for (const t of teams) {
    const key = t.name.toLowerCase();
    if (seenTeams.has(key)) continue;
    seenTeams.add(key);
    const members = t.members.filter((m) => {
      const id = m.uuid.toLowerCase();
      if (seenPlayers.has(id)) return false;
      seenPlayers.add(id);
      return true;
    });
    out.push({ ...t, members });
  }
  return out;
}

// ---- Discord bot connection: wss://HOST/v1/bot   (header: Authorization: Bearer <BOT_SECRET>) ----
app.get(
  "/v1/bot",
  {
    websocket: true,
    preValidation: async (req, reply) => {
      if (!isBotAuthorized(req.headers.authorization)) {
        return reply.code(401).send({ error: "unauthorized" });
      }
    },
  },
  (socket, req) => {
    botHub.register(socket);
    req.log.info("discord bot connected");
    socket.send(JSON.stringify({ type: "welcome" }));

    let alive = true;
    socket.on("pong", () => (alive = true));
    const pinger = setInterval(() => {
      if (!alive) return socket.terminate();
      alive = false;
      socket.ping();
    }, 30_000);

    socket.on("message", (raw) => {
      let msg;
      try {
        msg = botMessage.parse(JSON.parse(raw.toString()));
      } catch {
        return;
      }
      if (msg.type === "ping") {
        socket.send(JSON.stringify({ type: "pong" }));
      } else if (msg.type === "discord.chat") {
        // Discord -> Minecraft
        hub.send(msg.serverId, { type: "discord.chat", author: msg.author, message: msg.message });
      }
    });

    socket.on("close", () => {
      clearInterval(pinger);
      botHub.unregister(socket);
      req.log.info("discord bot disconnected");
    });
  },
);

// ---- Plugin connection: wss://HOST/v1/plugin   (header: Authorization: Bearer <server key>) ----
app.get(
  "/v1/plugin",
  {
    websocket: true,
    preValidation: async (req, reply) => {
      const header = req.headers.authorization ?? "";
      const key = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
      if (!key) return reply.code(401).send({ error: "missing_key" });

      const { data, error } = await db
        .from("server_keys")
        .select("server_id, servers(name)")
        .eq("key_hash", hashKey(key))
        .maybeSingle();

      if (error) {
        req.log.error(error);
        return reply.code(503).send({ error: "db_unavailable" });
      }
      if (!data) return reply.code(401).send({ error: "invalid_key" });

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const row = data as any;
      const s = Array.isArray(row.servers) ? row.servers[0] : row.servers;
      req.flowtServer = { id: row.server_id, name: s?.name ?? "Unknown" };
    },
  },
  (socket, req) => {
    const server = req.flowtServer!;
    const send = (obj: unknown) => socket.send(JSON.stringify(obj));

    hub.register(server.id, socket);
    req.log.info({ server: server.name }, "plugin connected");
    send({ type: "welcome", serverId: server.id, name: server.name });
    void db.from("servers").update({ last_seen_at: new Date().toISOString() }).eq("id", server.id);

    // keep-alive: drop dead connections
    let alive = true;
    socket.on("pong", () => (alive = true));
    const pinger = setInterval(() => {
      if (!alive) return socket.terminate();
      alive = false;
      socket.ping();
    }, 30_000);

    // simple flood protection + write throttles
    let windowStart = Date.now();
    let count = 0;
    let lastBeatWrite = 0;
    let lastSnapshot = 0;
    let lastSetup = 0;
    const lastLink = new Map<string, number>();

    socket.on("message", async (raw) => {
      const now = Date.now();
      if (now - windowStart > 10_000) {
        windowStart = now;
        count = 0;
      }
      if (++count > 200) return socket.close(1008, "rate_limited");

      let msg;
      try {
        msg = pluginMessage.parse(JSON.parse(raw.toString()));
      } catch {
        return send({ type: "error", error: "invalid_message" });
      }

      try {
        switch (msg.type) {
          case "heartbeat": {
            if (msg.players) hub.setPlayers(server.id, msg.players);
            // write to DB at most once per minute (saves the free tier)
            if (now - lastBeatWrite > 60_000) {
              lastBeatWrite = now;
              await db
                .from("servers")
                .update({
                  online_players: msg.online,
                  max_players: msg.max ?? null,
                  last_seen_at: new Date().toISOString(),
                })
                .eq("id", server.id);
            }
            break;
          }
          case "teams.snapshot": {
            if (now - lastSnapshot < 5_000) return send({ type: "error", error: "snapshot_too_fast" });
            lastSnapshot = now;
            const teams = sanitizeSnapshot(msg.teams);
            const { error } = await db.rpc("apply_team_snapshot", {
              p_server_id: server.id,
              p_teams: teams,
            });
            if (error) {
              req.log.error(error);
              return send({ type: "error", error: "snapshot_failed" });
            }
            send({ type: "ack", for: "teams.snapshot", teams: teams.length });
            break;
          }
          case "chat":
            bus.emit("chat", { serverId: server.id, serverName: server.name, ...msg });
            break;
          case "event":
            bus.emit("event", { serverId: server.id, serverName: server.name, ...msg });
            break;

          case "link.request": {
            const uuid = msg.uuid.toLowerCase();
            if (now - (lastLink.get(uuid) ?? 0) < 10_000) {
              return send({ type: "link.error", uuid, error: "too_fast" });
            }
            lastLink.set(uuid, now);

            const { error: pErr } = await db
              .from("players")
              .upsert(
                { mc_uuid: uuid, username: msg.username, last_seen_at: new Date().toISOString() },
                { onConflict: "mc_uuid" },
              );
            if (pErr) throw pErr;

            const { data: p } = await db.from("players").select("discord_id").eq("mc_uuid", uuid).maybeSingle();
            if (p?.discord_id) return send({ type: "link.error", uuid, error: "already_linked" });

            await db.from("link_codes").delete().eq("mc_uuid", uuid);
            const code = generateCode(6);
            const { error: cErr } = await db.from("link_codes").insert({
              code,
              mc_uuid: uuid,
              server_id: server.id,
              expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
            });
            if (cErr) throw cErr;
            send({ type: "link.code", uuid, code, expiresInMinutes: 10 });
            break;
          }

          case "setup.request": {
            if (now - lastSetup < 10_000) return send({ type: "error", error: "too_fast" });
            lastSetup = now;
            await db.from("setup_codes").delete().eq("server_id", server.id);
            const code = generateCode(8);
            const { error: sErr } = await db.from("setup_codes").insert({
              code,
              server_id: server.id,
              expires_at: new Date(Date.now() + 30 * 60_000).toISOString(),
            });
            if (sErr) throw sErr;
            send({ type: "setup.code", code, expiresInMinutes: 30 });
            break;
          }
        }
      } catch (err) {
        req.log.error(err);
        send({ type: "error", error: "internal_error" });
      }
    });

    socket.on("close", () => {
      clearInterval(pinger);
      hub.unregister(server.id, socket);
      req.log.info({ server: server.name }, "plugin disconnected");
    });
  },
);

await app.listen({ port: env.PORT, host: "0.0.0.0" });
