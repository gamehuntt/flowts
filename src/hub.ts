import WebSocket from "ws";

// serverId -> its live plugin connection. The Discord bot will use hub.send() to push
// Discord chat into Minecraft.
const sockets = new Map<string, WebSocket>();

export const hub = {
  register(serverId: string, socket: WebSocket) {
    const old = sockets.get(serverId);
    if (old && old !== socket) old.close(4000, "replaced_by_new_connection");
    sockets.set(serverId, socket);
  },
  unregister(serverId: string, socket: WebSocket) {
    if (sockets.get(serverId) === socket) sockets.delete(serverId);
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
};
