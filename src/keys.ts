import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export function hashKey(key: string): string {
  return createHash("sha256").update(key).digest("hex");
}

/** Creates a new server API key. Show `key` to the owner ONCE; store only `hash`. */
export function generateApiKey() {
  const key = "flowt_" + randomBytes(24).toString("hex");
  return { key, hash: hashKey(key), prefix: key.slice(0, 12) };
}

// No 0/O/1/I so codes are easy to read and type
const ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** Short one-time code (link codes, setup codes). */
export function generateCode(length: number): string {
  const bytes = randomBytes(length);
  let out = "";
  for (const b of bytes) out += ALPHABET[b % ALPHABET.length]; // 256 % 32 === 0 -> unbiased
  return out;
}

/** Constant-time string comparison (for secrets). */
export function safeEqual(a: string, b: string): boolean {
  const ha = createHash("sha256").update(a).digest();
  const hb = createHash("sha256").update(b).digest();
  return timingSafeEqual(ha, hb);
}
