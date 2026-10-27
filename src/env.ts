import { z } from "zod";

const schema = z.object({
  SUPABASE_URL: z.string().url(),
  SUPABASE_SERVICE_ROLE_KEY: z.string().min(20),
  BOT_SECRET: z.string().min(16),
  // Public host of this API (e.g. my-api.onrender.com). On Render it is detected automatically.
  PUBLIC_HOST: z.string().optional(),
  PORT: z.coerce.number().int().default(3000),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  console.error("\n.env / Environment Variables mein ye values galat ya missing hain:");
  console.error(parsed.error.flatten().fieldErrors);
  process.exit(1);
}

export const env = parsed.data;
