package app.flowt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Outbound WebSocket connection to the Flowt API. */
final class FlowtClient {
    private final FlowtPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    private volatile WebSocket socket;
    private volatile boolean running;
    private volatile CommandSender setupRequester;
    private volatile CommandSender confirmRequester;
    private volatile CommandSender disconnectRequester;
    private int attempt;
    private CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);

    FlowtClient(FlowtPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ lifecycle
    void start() {
        running = true;
        connect();
    }

    void stop() {
        running = false;
        WebSocket w = socket;
        socket = null;
        if (w != null) w.abort();
    }

    /** Used by /flowt reload: drop the connection and connect again with the (new) config. */
    void restart() {
        WebSocket w = socket;
        socket = null;
        if (w != null) w.abort();
        attempt = 0;
        reconnectPending.set(false);
        connect();
    }

    boolean isConnected() {
        WebSocket w = socket;
        return w != null && !w.isOutputClosed() && !w.isInputClosed();
    }

    private void connect() {
        if (!running) return;
        String key = plugin.apiKey();
        if (key.isEmpty()) {
            plugin.getLogger().info("Not connected to Discord yet. Run /connect in Discord (Manage Server permission), then /connect <code> here. Teams work without it.");
            return;
        }
        String host = hostFromKey(key);
        if (host == null) {
            plugin.getLogger().warning("The saved connection is not valid. Run /flowt disconnect and then /connect again.");
            return;
        }
        String scheme = (host.startsWith("localhost") || host.startsWith("127.0.0.1")) ? "ws" : "wss";
        String url = scheme + "://" + host + "/v1/plugin";
        try {
            http.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + key)
                    .connectTimeout(Duration.ofSeconds(60))
                    .buildAsync(URI.create(url), new Handler())
                    .whenComplete((ws, err) -> {
                        if (err == null) return;
                        Throwable c = (err instanceof CompletionException && err.getCause() != null) ? err.getCause() : err;
                        if (c instanceof WebSocketHandshakeException whe && whe.getResponse() != null) {
                            int code = whe.getResponse().statusCode();
                            if (code == 401) {
                                // the server was removed on the Flowt side (for example after a data reset): forget the old key
                                plugin.setApiKey("");
                                plugin.getLogger().warning("Flowt no longer knows this server (HTTP 401). The old connection was removed - run /connect to connect again.");
                                return;
                            } else {
                                plugin.getLogger().warning("Flowt API refused the connection (HTTP " + code + ").");
                            }
                        } else {
                            plugin.getLogger().warning("Could not reach the Flowt API: " + c.getClass().getSimpleName()
                                    + (c.getMessage() == null ? "" : " - " + c.getMessage()));
                        }
                        scheduleReconnect();
                    });
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("The saved connection contains an invalid server address. Run /flowt disconnect.");
        }
    }

    /** The API address is stored inside the key: flowt_<base64url(host)>.<secret> */
    private static String hostFromKey(String key) {
        if (!key.startsWith("flowt_")) return null;
        int dot = key.indexOf('.');
        if (dot <= 6) return null;
        try {
            String host = new String(Base64.getUrlDecoder().decode(key.substring(6, dot)), StandardCharsets.UTF_8);
            return host.matches("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?") ? host : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void scheduleReconnect() {
        if (!running || !plugin.isEnabled()) return;
        if (!reconnectPending.compareAndSet(false, true)) return;
        long delaySec = Math.min(60L, 2L << Math.min(attempt++, 5)); // 2,4,8,16,32,60 seconds
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            reconnectPending.set(false);
            connect();
        }, delaySec * 20L);
    }

    private void dropped(WebSocket ws) {
        if (socket != ws) return; // an old connection, ignore
        socket = null;
        scheduleReconnect();
    }

    // ------------------------------------------------------------ sending
    private void send(JsonObject obj) {
        WebSocket w = socket;
        if (w == null || w.isOutputClosed()) return;
        String json = obj.toString();
        synchronized (this) {
            // WebSocket.sendText must not run twice at the same time, so sends are chained
            sendChain = sendChain
                    .exceptionally(e -> null)
                    .thenCompose(v -> w.sendText(json, true))
                    .thenApply(x -> null);
        }
    }

    /** Must be called on the main thread. */
    void sendHeartbeat() {
        if (!isConnected()) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "heartbeat");
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        o.addProperty("online", online.size());
        o.addProperty("max", Bukkit.getMaxPlayers());
        JsonArray names = new JsonArray();
        int n = 0;
        for (Player p : online) {
            if (n++ >= 500) break;
            names.add(Msg.limit(p.getName(), 32));
        }
        o.add("players", names);
        send(o);
    }

    void sendChat(Player p, String message) {
        String text = Msg.limit(Msg.clean(message).trim(), 256);
        if (text.isEmpty()) return;
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", Msg.limit(p.getName(), 32));
        o.addProperty("uuid", p.getUniqueId().toString());
        o.addProperty("message", text);
        send(o);
    }

    void sendEvent(String kind, String player, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "event");
        o.addProperty("kind", kind);
        o.addProperty("player", Msg.limit(player, 32));
        if (text != null && !text.isBlank()) o.addProperty("text", Msg.limit(Msg.clean(text), 256));
        send(o);
    }

    void sendTeamSnapshot(JsonObject snapshot) {
        if (isConnected()) send(snapshot);
    }

    boolean requestLink(Player p) {
        if (!isConnected()) return false;
        JsonObject o = new JsonObject();
        o.addProperty("type", "link.request");
        o.addProperty("uuid", p.getUniqueId().toString());
        o.addProperty("username", Msg.limit(p.getName(), 32));
        send(o);
        return true;
    }

    /** /discord link <code>: finish a link that was started in Discord with /link. */
    boolean confirmLink(Player p, String code) {
        if (!isConnected()) return false;
        JsonObject o = new JsonObject();
        o.addProperty("type", "link.confirm");
        o.addProperty("uuid", p.getUniqueId().toString());
        o.addProperty("username", Msg.limit(p.getName(), 32));
        o.addProperty("code", Msg.limit(code, 16));
        send(o);
        return true;
    }

    /** /flowt setup <code>: finish a setup that was started in Discord with /flowt setup. */
    boolean confirmSetup(CommandSender who, String code) {
        if (!isConnected()) return false;
        confirmRequester = who;
        JsonObject o = new JsonObject();
        o.addProperty("type", "setup.confirm");
        o.addProperty("code", Msg.limit(code, 16));
        send(o);
        return true;
    }

    /** /disconnect: ask Flowt to remove this server's Discord connection and key. */
    boolean requestDisconnect(CommandSender who) {
        if (!isConnected()) return false;
        disconnectRequester = who;
        JsonObject o = new JsonObject();
        o.addProperty("type", "disconnect");
        send(o);
        return true;
    }

    boolean requestSetup(CommandSender who) {
        if (!isConnected()) return false;
        setupRequester = who;
        JsonObject o = new JsonObject();
        o.addProperty("type", "setup.request");
        send(o);
        return true;
    }

    // ------------------------------------------------------------ receiving
    private final class Handler implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            if (!running) {
                ws.abort();
                return;
            }
            socket = ws;
            attempt = 0;
            synchronized (FlowtClient.this) {
                sendChain = CompletableFuture.completedFuture(null);
            }
            plugin.getLogger().info("Connected to the Flowt API.");
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handle(msg);
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not read a message from the Flowt API: " + e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            plugin.getLogger().info("Flowt API connection closed (" + statusCode
                    + (reason == null || reason.isEmpty() ? "" : ": " + reason) + ")");
            dropped(ws);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            plugin.getLogger().warning("Flowt API connection error: " + error.getMessage());
            dropped(ws);
        }
    }

    private static String str(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private void handle(String raw) {
        JsonObject o = JsonParser.parseString(raw).getAsJsonObject();
        String type = str(o, "type");
        switch (type) {
            case "welcome" -> {
                plugin.getLogger().info("Authenticated as server \"" + str(o, "name") + "\".");
                sync(() -> {
                    sendHeartbeat();
                    plugin.teams().syncNow();
                });
            }
            case "error" -> {
                String e = str(o, "error");
                if (!e.equals("snapshot_too_fast") && !e.equals("too_fast")) {
                    plugin.getLogger().warning("Flowt API reported an error: " + e);
                }
            }
            case "link.code" -> sync(() -> {
                Player p = playerOf(str(o, "uuid"));
                if (p == null) return;
                String code = str(o, "code");
                int mins = o.has("expiresInMinutes") ? o.get("expiresInMinutes").getAsInt() : 10;
                Msg.ok(p, "Your link code is " + ChatColor.YELLOW + code + ChatColor.GREEN + ".");
                Msg.info(p, "In Discord run: /account connect code:" + code + "  (valid for " + mins + " minutes)");
            });
            case "link.error" -> sync(() -> {
                Player p = playerOf(str(o, "uuid"));
                if (p == null) return;
                String e = str(o, "error");
                if (e.equals("already_linked")) Msg.err(p, "Your account is already linked to Discord.");
                else if (e.equals("too_fast")) Msg.err(p, "Please wait a few seconds before asking for a new code.");
                else if (e.equals("invalid_code")) Msg.err(p, "That code is wrong or expired. Run /account connect in Discord to get a new one.");
                else if (e.equals("discord_already_linked")) Msg.err(p, "That Discord account is already linked to another Minecraft account.");
                else if (e.equals("too_many_attempts")) Msg.err(p, "Too many wrong codes. Try again in an hour.");
                else Msg.err(p, "Could not create a link code. Try again later.");
            });
            case "link.success" -> sync(() -> {
                Player p = playerOf(str(o, "uuid"));
                if (p != null) Msg.ok(p, "Your Minecraft account is now linked to your Discord account!");
            });
            case "setup.code" -> sync(() -> {
                CommandSender s = setupRequester != null ? setupRequester : Bukkit.getConsoleSender();
                setupRequester = null;
                String code = str(o, "code");
                int mins = o.has("expiresInMinutes") ? o.get("expiresInMinutes").getAsInt() : 30;
                Msg.ok(s, "Setup code: " + ChatColor.YELLOW + code + ChatColor.GREEN + "  (valid for " + mins + " minutes)");
                Msg.info(s, "In your Discord server (you need the Manage Server permission) run:");
                Msg.plain(s, ChatColor.WHITE + "/connect code:" + code + ChatColor.GRAY + "  [channel: optional]");
            });
            case "disconnect.done" -> {
                plugin.setApiKey("");
                sync(() -> {
                    CommandSender s = disconnectRequester != null ? disconnectRequester : Bukkit.getConsoleSender();
                    disconnectRequester = null;
                    Msg.ok(s, "Disconnected from Discord. Run /connect to connect again.");
                });
            }
            case "setup.done" -> sync(() -> {
                CommandSender s = confirmRequester != null ? confirmRequester : Bukkit.getConsoleSender();
                confirmRequester = null;
                Msg.ok(s, "Connected to Discord! Chat will now appear in the channel you chose.");
            });
            case "setup.error" -> sync(() -> {
                CommandSender s = confirmRequester != null ? confirmRequester : Bukkit.getConsoleSender();
                confirmRequester = null;
                String e = str(o, "error");
                if (e.equals("invalid_or_expired_code")) Msg.err(s, "That code is wrong or expired. Run /connect in Discord to get a new one.");
                else if (e.equals("too_many_attempts")) Msg.err(s, "Too many wrong codes. Try again in an hour.");
                else Msg.err(s, "Could not connect to Discord. Try again in a few seconds.");
            });
            case "discord.chat" -> {
                if (!plugin.getConfig().getBoolean("discord-to-minecraft", true)) return;
                String format = plugin.getConfig().getString("discord-chat-format", "&9[Discord] &f{author}&7: &f{message}");
                String text = ChatColor.translateAlternateColorCodes('&', format)
                        .replace("{author}", Msg.clean(str(o, "author")))
                        .replace("{message}", Msg.clean(str(o, "message")));
                sync(() -> Bukkit.broadcastMessage(text));
            }
            case "notice" -> sync(() -> {
                String t = str(o, "text");
                plugin.getLogger().info(t);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("flowt.admin")) Msg.ok(p, t);
                }
            });
            default -> { /* welcome-like or unknown messages are ignored */ }
        }
    }

    private static Player playerOf(String uuid) {
        try {
            return Bukkit.getPlayer(UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
