import WebSocket from "ws";

// serverId -> its live plugin connection
const sockets = new Map<string, WebSocket>();
// serverId -> names of online players (kept in memory only, never in the database)
const players = new Map<string, string[]>();

export const hub = {
  register(serverId: string, socket: WebSocket) {
    const old = sockets.get(serverId);
    if (old && old !== socket) old.close(4000, "replaced_by_new_connection");
    sockets.set(serverId, socket);
  },
  unregister(serverId: string, socket: WebSocket) {
    if (sockets.get(serverId) === socket) {
      sockets.delete(serverId);
      players.delete(serverId);
    }
  },
  isOnline(serverId: string): boolean {
    const s = sockets.get(serverId);
    return !!s && s.readyState === WebSocket.OPEN;
  },
  send(serverId: string, payload: unknown): boolean {
    const s = sockets.get(serverId);
    if (!s || s.readyState !== WebSocket.OPEN) return false;
    s.send(JSON.stringify(payload));
    return true;
  },
  setPlayers(serverId: string, names: string[]) {
    players.set(serverId, names);
  },
  getPlayers(serverId: string): string[] | undefined {
    return players.get(serverId);
  },
};

// The (single) Discord bot connection
let botSocket: WebSocket | null = null;

export const botHub = {
  register(socket: WebSocket) {
    if (botSocket && botSocket !== socket) botSocket.close(4000, "replaced_by_new_connection");
    botSocket = socket;
  },
  unregister(socket: WebSocket) {
    if (botSocket === socket) botSocket = null;
  },
  send(payload: unknown): boolean {
    if (!botSocket || botSocket.readyState !== WebSocket.OPEN) return false;
    botSocket.send(JSON.stringify(payload));
    return true;
  },
};
