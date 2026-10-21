package app.flowt;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

public final class FlowtPlugin extends JavaPlugin {
    private FlowtClient client;
    private TeamManager teams;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        teams = new TeamManager(this);
        teams.load();
        client = new FlowtClient(this);

        getServer().getPluginManager().registerEvents(new GameListener(this), this);

        TeamCommand teamCommand = new TeamCommand(this);
        bind("team", teamCommand);
        bind("teama", teamCommand);
        FlowtCommand flowtCommand = new FlowtCommand(this);
        bind("discord", flowtCommand);
        bind("flowt", flowtCommand);

        client.start();

        // heartbeat every 30 seconds (online count + player names; also keeps a free API host awake)
        Bukkit.getScheduler().runTaskTimer(this, () -> client.sendHeartbeat(), 20L * 10, 20L * 30);
        // full team sync every 5 minutes as a safety net
        Bukkit.getScheduler().runTaskTimer(this, () -> teams.syncNow(), 20L * 60 * 5, 20L * 60 * 5);
    }

    @Override
    public void onDisable() {
        if (teams != null) teams.saveNow();
        if (client != null) client.stop();
    }

    private void bind(String name, TabExecutor handler) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml");
            return;
        }
        c.setExecutor(handler);
        c.setTabCompleter(handler);
    }

    FlowtClient client() { return client; }
    TeamManager teams() { return teams; }
}
