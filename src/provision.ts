import type { FastifyRequest } from "fastify";
import { db } from "./db.js";
import { env } from "./env.js";
import { generateApiKey } from "./keys.js";
import { bindServerToChannel } from "./bind.js";

export class ProvisionError extends Error {
  constructor(public code: "guild_limit" | "user_limit") {
    super(code);
  }
}

/** Public host of this API (goes inside every API key). */
export function apiHost(req: FastifyRequest): string {
  const host = env.PUBLIC_HOST ?? process.env.RENDER_EXTERNAL_HOSTNAME ?? req.headers.host ?? "";
  if (!host) throw new Error("API host unknown: set PUBLIC_HOST");
  return host;
}

/** Limits so one person cannot create unlimited servers. Returns an error code or null. */
export async function checkLimits(guildId: string, userId: string): Promise<"guild_limit" | "user_limit" | null> {
  const perGuild = await db.from("servers").select("id", { count: "exact", head: true }).eq("discord_guild_id", guildId);
  if ((perGuild.count ?? 0) >= 3) return "guild_limit";
  const perUser = await db.from("servers").select("id", { count: "exact", head: true }).eq("created_by_discord_id", userId);
  if ((perUser.count ?? 0) >= 5) return "user_limit";
  return null;
}

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

/** Creates a server + API key and connects it to a Discord channel. The key is returned once. */
export async function provisionServer(opts: {
  name: string;
  guildId: string;
  channelId: string;
  userId: string;
  host: string;
}) {
  const limit = await checkLimits(opts.guildId, opts.userId);
  if (limit) throw new ProvisionError(limit);

  const { data: server, error: sErr } = await db
    .from("servers")
    .insert({ owner_id: await getOwnerId(), name: opts.name, is_public: false, created_by_discord_id: opts.userId })
    .select("id")
    .single();
  if (sErr) throw sErr;

  const { key, hash, prefix } = generateApiKey(opts.host);
  const { error: kErr } = await db.from("server_keys").insert({ server_id: server.id, key_hash: hash, key_prefix: prefix });
  if (kErr) throw kErr;

  await bindServerToChannel(server.id, opts.guildId, opts.channelId);
  return { serverId: server.id as string, apiKey: key, name: opts.name };
}
