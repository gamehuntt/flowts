package app.flowt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** First-time connection to Discord without an API key (the key is received and saved automatically). */
final class Pairing {
    private record Reply(int status, JsonObject json) { }

    private final FlowtPlugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile BukkitTask pollTask;

    Pairing(FlowtPlugin plugin) {
        this.plugin = plugin;
    }

    /** Address of the Flowt service, written into the jar when it was built (null for an unofficial build). */
    private String host() {
        try (InputStream in = plugin.getResource("flowt.properties")) {
            if (in == null) return null;
            Properties p = new Properties();
            p.load(in);
            String h = p.getProperty("api.host", "").trim();
            return h.matches("[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?") ? h : null;
        } catch (IOException e) {
            return null;
        }
    }

    private String serverName() {
        String name = plugin.getConfig().getString("server-name", "");
        if (name == null) name = "";
        name = name.trim();
        if (name.isEmpty()) {
            String motd = Bukkit.getMotd();
            if (motd != null) name = ChatColor.stripColor(motd.split("\\R")[0]).trim();
        }
        if (name == null || name.isEmpty()) name = "Minecraft Server";
        return Msg.limit(name, 48);
    }

    private static String str(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private CompletableFuture<Reply> post(String host, String path, JsonObject body) {
        String scheme = (host.startsWith("localhost") || host.startsWith("127.0.0.1")) ? "http" : "https";
        HttpRequest req = HttpRequest.newBuilder(URI.create(scheme + "://" + host + path))
                .timeout(Duration.ofSeconds(70)) // a sleeping free host can need ~50 seconds to wake up
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject json;
            try {
                json = JsonParser.parseString(r.body()).getAsJsonObject();
            } catch (Exception e) {
                json = new JsonObject();
            }
            return new Reply(r.statusCode(), json);
        });
    }

    void cancelPoll() {
        BukkitTask t = pollTask;
        pollTask = null;
        if (t != null) t.cancel();
    }

    private void failure(CommandSender who, Reply res, Throwable err) {
        if (err != null || res == null) {
            Msg.err(who, "Could not reach the Flowt service. Try again in a minute.");
            return;
        }
        String e = str(res.json(), "error");
        if (e.equals("invalid_or_expired_code")) Msg.err(who, "That code is wrong or expired. Run /connect in Discord to get a new one.");
        else if (e.equals("guild_limit")) Msg.err(who, "This Discord server already has the maximum number of connected Minecraft servers.");
        else if (e.equals("user_limit")) Msg.err(who, "That Discord user has reached the maximum number of servers.");
        else if (res.status() == 429) Msg.err(who, "Too many attempts. Try again in a while.");
        else Msg.err(who, "Could not connect (error " + res.status() + "). Try again in a minute.");
    }

    private void finish(CommandSender who, JsonObject json) {
        String key = str(json, "apiKey");
        if (key.isEmpty()) {
            Msg.err(who, "Flowt did not send a key. Try /connect again.");
            return;
        }
        plugin.setApiKey(key);
        plugin.client().restart();
        Msg.ok(who, "Connected to Discord as \"" + str(json, "serverName") + "\"! Chat will now appear in the channel you chose.");
    }

    /** /connect <code> - the code was made in Discord with /connect. */
    void claim(CommandSender who, String code) {
        String host = host();
        if (host == null) {
            Msg.err(who, "This copy of Flowt has no service address built in. Download the official jar.");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            Msg.err(who, "Already connecting, please wait...");
            return;
        }
        Msg.info(who, "Connecting...");
        JsonObject body = new JsonObject();
        body.addProperty("code", Msg.limit(code, 16));
        body.addProperty("name", serverName());
        post(host, "/v1/pair/claim", body).whenComplete((res, err) -> {
            busy.set(false);
            sync(() -> {
                if (err == null && res != null && res.status() == 200 && res.json().has("apiKey")) finish(who, res.json());
                else failure(who, res, err);
            });
        });
    }

    /** /connect - make a code here, then run /connect <code> in Discord. */
    void start(CommandSender who) {
        String host = host();
        if (host == null) {
            Msg.err(who, "This copy of Flowt has no service address built in. Download the official jar.");
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            Msg.err(who, "Already connecting, please wait...");
            return;
        }
        Msg.info(who, "Asking Flowt for a connect code...");
        JsonObject body = new JsonObject();
        body.addProperty("name", serverName());
        post(host, "/v1/pair/start", body).whenComplete((res, err) -> {
            busy.set(false);
            sync(() -> {
                if (err != null || res == null || res.status() != 200 || !res.json().has("code")) {
                    failure(who, res, err);
                    return;
                }
                String code = str(res.json(), "code");
                int mins = res.json().has("expiresInMinutes") ? res.json().get("expiresInMinutes").getAsInt() : 15;
                Msg.ok(who, "Connect code: " + ChatColor.YELLOW + code + ChatColor.GREEN + "  (valid for " + mins + " minutes)");
                Msg.info(who, "In your Discord server (Manage Server permission) run:");
                Msg.plain(who, ChatColor.WHITE + "/connect code:" + code);
                beginPolling(who, host, code, str(res.json(), "pollToken"), mins);
            });
        });
    }

    private void beginPolling(CommandSender who, String host, String code, String token, int mins) {
        cancelPoll();
        long deadline = System.currentTimeMillis() + mins * 60_000L;
        AtomicBoolean inFlight = new AtomicBoolean(false);
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (System.currentTimeMillis() > deadline) {
                cancelPoll();
                sync(() -> Msg.err(who, "The connect code expired. Run /connect again."));
                return;
            }
            if (!inFlight.compareAndSet(false, true)) return;
            JsonObject body = new JsonObject();
            body.addProperty("code", code);
            body.addProperty("pollToken", token);
            post(host, "/v1/pair/poll", body).whenComplete((res, err) -> {
                inFlight.set(false);
                if (err != null || res == null || res.status() != 200) return; // try again on the next tick
                String status = str(res.json(), "status");
                if (status.equals("claimed")) {
                    cancelPoll();
                    sync(() -> finish(who, res.json()));
                } else if (status.equals("expired")) {
                    cancelPoll();
                    sync(() -> Msg.err(who, "The connect code expired. Run /connect again."));
                } else if (status.equals("error")) {
                    cancelPoll();
                    sync(() -> failure(who, res, null));
                }
            });
        }, 60L, 60L);
    }
}
