import type { FastifyInstance } from "fastify";
import { db } from "./db.js";
import { generateApiKey } from "./keys.js";
import { isBotAuthorized } from "./botRoutes.js";

// TEMPORARY: lets you create a server + API key from the browser (no Node needed).
// Delete this file (and its 2 lines in index.ts) once you have your key.
const PAGE = `<!doctype html><meta charset="utf-8"><title>Flowt key</title>
<body style="font-family:sans-serif;max-width:460px;margin:40px auto">
<h3>Flowt - create server key</h3>
<input id="s" type="password" placeholder="BOT_SECRET" style="width:100%;padding:8px;margin:4px 0"><br>
<input id="n" placeholder="Server name" value="My Server" style="width:100%;padding:8px;margin:4px 0"><br>
<label><input id="p" type="checkbox" checked> public (teams global list mein dikhein)</label><br><br>
<button onclick="go()" style="padding:8px 16px">Create key</button>
<pre id="o" style="white-space:pre-wrap;word-break:break-all"></pre>
<script>
async function go(){
  const r = await fetch("/v1/admin/create-server",{method:"POST",
    headers:{"Content-Type":"application/json","Authorization":"Bearer "+document.getElementById("s").value.trim()},
    body:JSON.stringify({name:document.getElementById("n").value,isPublic:document.getElementById("p").checked})});
  document.getElementById("o").textContent = JSON.stringify(await r.json(),null,2);
}
</script>`;

export function registerAdminRoutes(app: FastifyInstance) {
  app.get("/v1/admin/create-server", async (_req, reply) => reply.type("text/html").send(PAGE));

  app.post<{ Body: { name?: string; isPublic?: boolean } }>("/v1/admin/create-server", async (req, reply) => {
    if (!isBotAuthorized(req.headers.authorization)) return reply.code(401).send({ error: "unauthorized" });

    const name = (req.body?.name ?? "My Server").toString().trim().slice(0, 64) || "My Server";
    const email = "dev-owner@flowt.local";
    const { data: list, error: listErr } = await db.auth.admin.listUsers({ page: 1, perPage: 200 });
    if (listErr) return reply.code(500).send({ error: "list_users_failed", detail: listErr.message });

    let ownerId = list.users.find((u) => u.email === email)?.id;
    if (!ownerId) {
      const { data, error } = await db.auth.admin.createUser({ email, email_confirm: true });
      if (error) return reply.code(500).send({ error: "create_user_failed", detail: error.message });
      ownerId = data.user.id;
    }

    const { data: server, error: sErr } = await db
      .from("servers")
      .insert({ owner_id: ownerId, name, is_public: !!req.body?.isPublic })
      .select("id")
      .single();
    if (sErr) return reply.code(500).send({ error: "server_insert_failed", detail: sErr.message });

    const { key, hash, prefix } = generateApiKey();
    const { error: kErr } = await db.from("server_keys").insert({ server_id: server.id, key_hash: hash, key_prefix: prefix });
    if (kErr) return reply.code(500).send({ error: "key_insert_failed", detail: kErr.message });

    return { apiKey: key, serverId: server.id, note: "Key sirf ek baar dikhegi. Copy karke config.yml mein daalo." };
  });
}
