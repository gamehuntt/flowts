import type { FastifyInstance } from "fastify";
import { db } from "./db.js";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const one = (x: any) => (Array.isArray(x) ? x[0] : x);

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function shape(row: any) {
  return {
    name: row.name,
    description: row.description ?? null,
    color: row.color ?? null,
    score: row.score,
    members: row.member_count,
    server: one(row.servers)?.name ?? null,
  };
}

const COLUMNS = "name, description, color, score, member_count, servers(name)";

export function registerPublicRoutes(app: FastifyInstance) {
  app.get("/health", async () => ({ ok: true }));

  // Global leaderboard: only teams from servers that opted in (is_public)
  app.get<{ Querystring: { limit?: string } }>("/v1/teams/top", async (req, reply) => {
    const limit = Math.min(Math.max(parseInt(req.query.limit ?? "20", 10) || 20, 1), 100);
    const { data, error } = await db
      .from("teams")
      .select(COLUMNS)
      .eq("is_public", true)
      .order("score", { ascending: false })
      .order("id")
      .limit(limit);
    if (error) {
      req.log.error(error);
      return reply.code(500).send({ error: "db_error" });
    }
    return { teams: (data ?? []).map(shape) };
  });

  // Global team search by name
  app.get<{ Querystring: { q?: string } }>("/v1/teams/search", async (req, reply) => {
    const q = (req.query.q ?? "").toLowerCase().replace(/[%_\\,()]/g, " ").trim().slice(0, 32);
    if (q.length < 2) return reply.code(400).send({ error: "query_too_short" });
    const { data, error } = await db
      .from("teams")
      .select(COLUMNS)
      .eq("is_public", true)
      .ilike("name_lower", `%${q}%`)
      .order("score", { ascending: false })
      .limit(20);
    if (error) {
      req.log.error(error);
      return reply.code(500).send({ error: "db_error" });
    }
    return { teams: (data ?? []).map(shape) };
  });

  // Team details (exact name; the same name can exist on different servers)
  app.get<{ Querystring: { name?: string } }>("/v1/teams/info", async (req, reply) => {
    const name = (req.query.name ?? "").trim().toLowerCase().slice(0, 32);
    if (name.length < 2) return reply.code(400).send({ error: "query_too_short" });
    const { data, error } = await db
      .from("teams")
      .select(`${COLUMNS}, is_open, team_members(role, players(username))`)
      .eq("is_public", true)
      .eq("name_lower", name)
      .order("score", { ascending: false })
      .limit(5);
    if (error) {
      req.log.error(error);
      return reply.code(500).send({ error: "db_error" });
    }
    return {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      teams: (data ?? []).map((row: any) => ({
        ...shape(row),
        open: !!row.is_open,
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        memberList: (row.team_members ?? []).map((m: any) => ({
          username: one(m.players)?.username ?? "?",
          role: m.role,
        })),
      })),
    };
  });
}
