import type { FastifyInstance } from "fastify";
import { db } from "./db.js";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function shape(row: any) {
  const server = Array.isArray(row.servers) ? row.servers[0] : row.servers;
  return {
    name: row.name,
    description: row.description ?? null,
    color: row.color ?? null,
    score: row.score,
    members: row.member_count,
    server: server?.name ?? null,
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
}
