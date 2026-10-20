import { createHash, randomBytes } from "node:crypto";

export function hashKey(key: string): string {
  return createHash("sha256").update(key).digest("hex");
}

/** Creates a new server API key. Show `key` to the owner ONCE; store only `hash`. */
export function generateApiKey() {
  const key = "flowt_" + randomBytes(24).toString("hex");
  return { key, hash: hashKey(key), prefix: key.slice(0, 12) };
}
