import Fastify from "fastify";
import websocket from "@fastify/websocket";
import rateLimit from "@fastify/rate-limit";
import { env } from "./env.js";
import { db } from "./db.js";
import { hashKey } from "./keys.js";
import { pluginMessage, type TeamSnapshotTeam } from "./protocol.js";
import { hub } from "./hub.js";
import { bus } from "./bus.js";
import { registerPublicRoutes } from "./routes.js";

declare module "fastify" {
  interface FastifyRequest {
    flowtServer?: { id: string; name: string };
  }
}

const app = Fastify({ logger: true });

await app.register(rateLimit, { max: 120, timeWindow: "1 minute" });
await app.register(websocket, { options: { maxPayload: 1_048_576 } }); // 1 MB per message

registerPublicRoutes(app);

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

// ---- Plugin connection: ws://HOST/v1/plugin   (header: Authorization: Bearer <server key>) ----
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
