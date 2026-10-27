// Creates a server + API key (until the website dashboard exists).
// Usage:  npm run create-server -- "My Server Name" --public
import { db } from "../src/db.js";
import { generateApiKey } from "../src/keys.js";

const args = process.argv.slice(2);
const isPublic = args.includes("--public");
const name = args.find((a) => !a.startsWith("--")) ?? "My Test Server";

// Until the website exists, all servers belong to one development owner account.
const email = "dev-owner@flowt.local";
const { data: list, error: listErr } = await db.auth.admin.listUsers({ page: 1, perPage: 200 });
if (listErr) throw listErr;

let ownerId = list.users.find((u) => u.email === email)?.id;
if (!ownerId) {
  const { data, error } = await db.auth.admin.createUser({ email, email_confirm: true });
  if (error) throw error;
  ownerId = data.user.id; // the database trigger creates the matching profile automatically
}

const { data: server, error: serverErr } = await db
  .from("servers")
  .insert({ owner_id: ownerId, name, is_public: isPublic })
  .select("id")
  .single();
if (serverErr) throw serverErr;

const host = process.env.PUBLIC_HOST ?? "localhost:3000";
const { key, hash, prefix } = generateApiKey(host);
const { error: keyErr } = await db
  .from("server_keys")
  .insert({ server_id: server.id, key_hash: hash, key_prefix: prefix });
if (keyErr) throw keyErr;

console.log("\n=========================================");
console.log(" Server created:", name, isPublic ? "(public)" : "(private)");
console.log(" Server ID     :", server.id);
console.log(" API KEY       :", key);
console.log(" ^ Isko abhi copy karke safe jagah rakh lo. Ye dobara nahi dikhega.");
console.log("=========================================\n");
