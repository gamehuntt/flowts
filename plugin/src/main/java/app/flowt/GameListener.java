package app.flowt;

import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

final class GameListener implements Listener {
    private final FlowtPlugin plugin;

    GameListener(FlowtPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean event(String name) {
        return plugin.getConfig().getBoolean("events." + name, true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        plugin.teams().touchName(p);
        if (event("join")) plugin.client().sendEvent("join", p.getName(), null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.client().sendHeartbeat(), 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.teams().teamChat.remove(p.getUniqueId());
        if (event("quit")) plugin.client().sendEvent("quit", p.getName(), null);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.client().sendHeartbeat(), 10L);
    }

    /** Team chat mode: message goes to the team only (cancelled early so it never reaches Discord). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTeamChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.teams().teamChat.contains(p.getUniqueId())) return;
        Team t = plugin.teams().of(p.getUniqueId());
        if (t == null) {
            plugin.teams().teamChat.remove(p.getUniqueId());
            return;
        }
        e.setCancelled(true);
        String line = ChatColor.AQUA + "[Team] " + ChatColor.WHITE + p.getName() + ChatColor.GRAY + ": " + ChatColor.WHITE + Msg.clean(e.getMessage());
        plugin.teams().broadcast(t, line);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!plugin.getConfig().getBoolean("chat-to-discord", true)) return;
        plugin.client().sendChat(e.getPlayer(), e.getMessage());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        if (event("death")) {
            String msg = e.getDeathMessage();
            plugin.client().sendEvent("death", dead.getName(), msg == null ? dead.getName() + " died" : msg);
        }
        Player killer = dead.getKiller();
        if (killer == null || !plugin.getConfig().getBoolean("teams.enabled", true)) return;
        Team kt = plugin.teams().of(killer.getUniqueId());
        Team dt = plugin.teams().of(dead.getUniqueId());
        if (kt != null && kt != dt) {
            int points = plugin.getConfig().getInt("teams.score-per-kill", 1);
            if (points != 0) plugin.teams().addScore(kt, points);
        }
    }

    @EventHandler
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (!event("advancement")) return;
        NamespacedKey key = e.getAdvancement().getKey();
        String path = key.getKey();
        if (path.startsWith("recipes/") || path.endsWith("/root")) return;
        String last = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder pretty = new StringBuilder();
        for (String w : last.split(" ")) {
            if (w.isEmpty()) continue;
            if (pretty.length() > 0) pretty.append(' ');
            pretty.append(w.substring(0, 1).toUpperCase(Locale.ROOT)).append(w.substring(1));
        }
        plugin.client().sendEvent("advancement", e.getPlayer().getName(), pretty.toString());
    }

    /** No damage between team members unless friendly-fire is enabled. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (plugin.getConfig().getBoolean("teams.friendly-fire", false)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Entity d = e.getDamager();
        Player attacker = null;
        if (d instanceof Player p) {
            attacker = p;
        } else if (d instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null || attacker.equals(victim)) return;
        Team a = plugin.teams().of(attacker.getUniqueId());
        if (a != null && a == plugin.teams().of(victim.getUniqueId())) {
            e.setCancelled(true);
            Msg.err(attacker, "You can't hurt your teammate.");
        }
    }
}
