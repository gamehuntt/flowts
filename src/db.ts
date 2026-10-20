import { createClient } from "@supabase/supabase-js";
import { env } from "./env.js";

// Server-side only client (service_role bypasses RLS). Never expose this key.
export const db = createClient(env.SUPABASE_URL, env.SUPABASE_SERVICE_ROLE_KEY, {
  auth: { persistSession: false, autoRefreshToken: false },
});
