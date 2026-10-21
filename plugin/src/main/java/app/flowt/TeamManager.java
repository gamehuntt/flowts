package app.flowt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Holds all teams of this server. Only touched from the main server thread (except teamChat). */
final class TeamManager {
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{2,16}$");

    private final FlowtPlugin plugin;
    private final File file;
    private final Map<String, Team> byKey = new LinkedHashMap<>();
    private final Map<UUID, Team> byPlayer = new HashMap<>();
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();
    final Set<UUID> teamChat = ConcurrentHashMap.newKeySet();
    private boolean savePending;
    private boolean syncPending;

    TeamManager(FlowtPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    private static String key(Team t) { return key(t.name); }

    // ------------------------------------------------------------ queries
    Collection<Team> all() { return Collections.unmodifiableCollection(byKey.values()); }
    Team find(String name) { return name == null ? null : byKey.get(key(name)); }
    Team of(UUID id) { return byPlayer.get(id); }

    // ------------------------------------------------------------ changes
    Team create(Player p, String name) {
        Team t = new Team(name);
        t.members.put(p.getUniqueId(), new Team.Member(p.getUniqueId(), p.getName(), Team.Role.OWNER));
        byKey.put(key(t), t);
        byPlayer.put(p.getUniqueId(), t);
        changed();
        return t;
    }

    void addMember(Team t, Player p) {
        t.members.put(p.getUniqueId(), new Team.Member(p.getUniqueId(), p.getName(), Team.Role.MEMBER));
        byPlayer.put(p.getUniqueId(), t);
        invites.remove(p.getUniqueId());
        changed();
    }

    void removeMember(Team t, UUID id) {
        t.members.remove(id);
        byPlayer.remove(id);
        teamChat.remove(id);
        changed();
    }

    void disband(Team t) {
        for (UUID id : new ArrayList<>(t.members.keySet())) {
            byPlayer.remove(id);
            teamChat.remove(id);
        }
        byKey.remove(key(t));
        changed();
    }

    void addScore(Team t, int n) {
        t.score += n;
        changed();
    }

    void touchName(Player p) {
        Team t = byPlayer.get(p.getUniqueId());
        if (t == null) return;
        Team.Member m = t.members.get(p.getUniqueId());
        if (m != null && !m.name.equals(p.getName())) {
            m.name = p.getName();
            changed();
        }
    }

    void invite(Team t, UUID invitee) {
        invites.computeIfAbsent(invitee, k -> new HashMap<>()).put(key(t), System.currentTimeMillis() + 60_000L);
    }

    boolean hasInvite(UUID invitee, Team t) {
        Map<String, Long> m = invites.get(invitee);
        if (m == null) return false;
        Long expires = m.get(key(t));
        if (expires == null) return false;
        if (expires < System.currentTimeMillis()) {
            m.remove(key(t));
            return false;
        }
        return true;
    }

    void broadcast(Team t, String message) {
        for (UUID id : t.members.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(message);
        }
    }

    /** Call after any change: saves to disk and tells the Flowt API (both delayed + merged). */
    void changed() {
        saveSoon();
        requestSync();
    }

    // ------------------------------------------------------------ sync with Flowt API
    void requestSync() {
        if (syncPending || !plugin.isEnabled()) return;
        syncPending = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            syncPending = false;
            syncNow();
        }, 120L);
    }

    void syncNow() {
        if (!plugin.getConfig().getBoolean("teams.enabled", true)) return;
        plugin.client().sendTeamSnapshot(toSnapshot());
    }

    JsonObject toSnapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "teams.snapshot");
        JsonArray arr = new JsonArray();
        for (Team t : byKey.values()) {
            JsonObject jt = new JsonObject();
            jt.addProperty("name", t.name);
            if (t.description != null && !t.description.isEmpty()) jt.addProperty("description", t.description);
            jt.addProperty("open", t.open);
            jt.addProperty("score", t.score);
            jt.addProperty("balance", 0);
            JsonArray ms = new JsonArray();
            for (Team.Member m : t.members.values()) {
                JsonObject jm = new JsonObject();
                jm.addProperty("uuid", m.uuid.toString());
                jm.addProperty("username", m.name);
                jm.addProperty("role", m.role.name().toLowerCase(Locale.ROOT));
                ms.add(jm);
            }
            jt.add("members", ms);
            arr.add(jt);
        }
        o.add("teams", arr);
        return o;
    }

    // ------------------------------------------------------------ storage (teams.yml)
    void load() {
        byKey.clear();
        byPlayer.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("teams");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            try {
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s == null) continue;
                Team t = new Team(s.getString("name", k));
                t.description = s.getString("description", "");
                t.open = s.getBoolean("open", false);
                t.score = s.getInt("score", 0);
                ConfigurationSection h = s.getConfigurationSection("home");
                if (h != null && h.getString("world") != null) {
                    t.hasHome = true;
                    t.homeWorld = h.getString("world");
                    t.hx = h.getDouble("x");
                    t.hy = h.getDouble("y");
                    t.hz = h.getDouble("z");
                    t.hyaw = (float) h.getDouble("yaw");
                    t.hpitch = (float) h.getDouble("pitch");
                }
                ConfigurationSection ms = s.getConfigurationSection("members");
                if (ms != null) {
                    for (String u : ms.getKeys(false)) {
                        ConfigurationSection m = ms.getConfigurationSection(u);
                        if (m == null) continue;
                        UUID id = UUID.fromString(u);
                        Team.Role role = Team.Role.valueOf(m.getString("role", "MEMBER"));
                        t.members.put(id, new Team.Member(id, m.getString("name", "?"), role));
                    }
                }
                if (t.members.isEmpty()) continue;
                if (t.owner() == null) t.members.values().iterator().next().role = Team.Role.OWNER;
                byKey.put(key(t), t);
                for (UUID id : t.members.keySet()) byPlayer.put(id, t);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipping broken team '" + k + "': " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + byKey.size() + " team(s)");
    }

    private String serialize() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Team t : byKey.values()) {
            String base = "teams." + key(t);
            cfg.set(base + ".name", t.name);
            cfg.set(base + ".description", t.description);
            cfg.set(base + ".open", t.open);
            cfg.set(base + ".score", t.score);
            if (t.hasHome) {
                cfg.set(base + ".home.world", t.homeWorld);
                cfg.set(base + ".home.x", t.hx);
                cfg.set(base + ".home.y", t.hy);
                cfg.set(base + ".home.z", t.hz);
                cfg.set(base + ".home.yaw", (double) t.hyaw);
                cfg.set(base + ".home.pitch", (double) t.hpitch);
            }
            for (Team.Member m : t.members.values()) {
                String mb = base + ".members." + m.uuid;
                cfg.set(mb + ".name", m.name);
                cfg.set(mb + ".role", m.role.name());
            }
        }
        return cfg.saveToString();
    }

    private void saveSoon() {
        if (savePending || !plugin.isEnabled()) return;
        savePending = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePending = false;
            String data = serialize();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data));
        }, 40L);
    }

    void saveNow() {
        write(serialize());
    }

    private void write(String data) {
        try {
            plugin.getDataFolder().mkdirs();
            Path target = file.toPath();
            Path tmp = target.resolveSibling("teams.yml.tmp");
            Files.write(tmp, data.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save teams.yml: " + e.getMessage());
        }
    }
}
