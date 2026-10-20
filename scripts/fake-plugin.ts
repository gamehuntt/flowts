// Pretends to be the Minecraft plugin, to test the API without Minecraft.
// Usage:  npm run fake-plugin -- <API_KEY>
import WebSocket from "ws";

const key = process.argv[2];
if (!key) {
  console.error("Usage: npm run fake-plugin -- <API_KEY>");
  process.exit(1);
}
const url = process.env.FLOWT_URL ?? "ws://localhost:3000/v1/plugin";
const ws = new WebSocket(url, { headers: { Authorization: `Bearer ${key}` } });

ws.on("open", () => {
  console.log("Connected!");
  ws.send(JSON.stringify({ type: "heartbeat", online: 3, max: 20 }));
  ws.send(
    JSON.stringify({
      type: "teams.snapshot",
      teams: [
        {
          name: "Dragons",
          description: "We breathe fire",
          color: "red",
          open: true,
          score: 120,
          balance: 500,
          members: [
            { uuid: "00000000-0000-4000-8000-000000000001", username: "Steve", role: "owner" },
            { uuid: "00000000-0000-4000-8000-000000000002", username: "Alex", role: "member" },
          ],
        },
        {
          name: "Wolves",
          score: 80,
          members: [{ uuid: "00000000-0000-4000-8000-000000000003", username: "Herobrine", role: "owner" }],
        },
      ],
    }),
  );
  ws.send(JSON.stringify({ type: "chat", player: "Steve", message: "Hello from Minecraft!" }));
});

ws.on("message", (d) => console.log("<-", d.toString()));
ws.on("unexpected-response", (_req, res) => {
  console.error("Rejected by server, HTTP status:", res.statusCode, "(API key galat hai?)");
  process.exit(1);
});
ws.on("error", (e) => {
  console.error("Error:", e.message, "(API chal rahi hai? npm run dev)");
  process.exit(1);
});
setTimeout(() => {
  ws.close();
  process.exit(0);
}, 4000);
