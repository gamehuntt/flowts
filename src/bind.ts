import { db } from "./db.js";

export const CHANNEL_KINDS = ["chat", "join_leave", "death", "advancement"] as const;

/** Connects a Minecraft server to a Discord guild + channel (everything is stored in Supabase). */
export async function bindServerToChannel(serverId: string, guildId: string, channelId: string) {
  const { error: e1 } = await db.from("servers").update({ discord_guild_id: guildId }).eq("id", serverId);
  if (e1) throw e1;
  const { error: e2 } = await db
    .from("server_channels")
    .delete()
    .eq("server_id", serverId)
    .in("kind", [...CHANNEL_KINDS]);
  if (e2) throw e2;
  const { error: e3 } = await db
    .from("server_channels")
    .insert(CHANNEL_KINDS.map((kind) => ({ server_id: serverId, kind, discord_channel_id: channelId })));
  if (e3) throw e3;
}

// Brute-force protection for one-time codes: max 10 wrong codes per hour per id
const failures = new Map<string, { count: number; reset: number }>();
export const isBlocked = (id: string) => {
  const f = failures.get(id);
  return !!f && f.reset > Date.now() && f.count >= 10;
};
export const recordFailure = (id: string) => {
  const now = Date.now();
  const f = failures.get(id);
  if (!f || f.reset <= now) failures.set(id, { count: 1, reset: now + 3_600_000 });
  else f.count++;
};
