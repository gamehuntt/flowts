import { z } from "zod";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const memberSchema = z.object({
  uuid: z.string().regex(UUID),
  username: z.string().min(1).max(32),
  role: z.enum(["owner", "admin", "member"]),
});

export const teamSchema = z.object({
  name: z.string().min(2).max(32),
  description: z.string().max(256).nullish(),
  color: z.string().max(16).nullish(),
  open: z.boolean().default(false),
  score: z.number().int().default(0),
  balance: z.number().default(0),
  members: z.array(memberSchema).max(200),
});

/** Messages the Minecraft plugin may send to the API. */
export const pluginMessage = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("heartbeat"),
    online: z.number().int().min(0).max(100000),
    max: z.number().int().min(0).max(100000).optional(),
  }),
  z.object({
    type: z.literal("teams.snapshot"),
    teams: z.array(teamSchema).max(2000),
  }),
  z.object({
    type: z.literal("chat"),
    player: z.string().min(1).max(32),
    uuid: z.string().regex(UUID).optional(),
    message: z.string().min(1).max(256),
  }),
  z.object({
    type: z.literal("event"),
    kind: z.enum(["join", "quit", "death", "advancement"]),
    player: z.string().min(1).max(32),
    text: z.string().max(256).optional(),
  }),
]);

export type PluginMessage = z.infer<typeof pluginMessage>;
export type TeamSnapshotTeam = z.infer<typeof teamSchema>;
