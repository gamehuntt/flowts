import { EventEmitter } from "node:events";

// Internal event bus. The Discord bot (next step) listens here:
//   bus.on("chat",  (e) => ...)   bus.on("event", (e) => ...)
export const bus = new EventEmitter();

export interface ChatEvent {
  serverId: string;
  serverName: string;
  player: string;
  uuid?: string;
  message: string;
}

export interface GameEvent {
  serverId: string;
  serverName: string;
  kind: "join" | "quit" | "death" | "advancement";
  player: string;
  text?: string;
}
