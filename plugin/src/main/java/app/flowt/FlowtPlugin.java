package app.flowt;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public final class FlowtPlugin extends JavaPlugin {
    private FlowtClient client;
    private TeamManager teams;
    private Pairing pairing;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        teams = new TeamManager(this);
        teams.load();
        client = new FlowtClient(this);
        pairing = new Pairing(this);

        getServer().getPluginManager().registerEvents(new GameListener(this), this);

        TeamCommand teamCommand = new TeamCommand(this);
        bind("team", teamCommand);
        bind("teama", teamCommand);
        FlowtCommand flowtCommand = new FlowtCommand(this);
        bind("discord", flowtCommand);
        bind("flowt", flowtCommand);
        bind("connect", flowtCommand);
        bind("disconnect", flowtCommand);
        bind("account", flowtCommand);

        client.start();

        // heartbeat every 30 seconds (online count + player names; also keeps a free API host awake)
        Bukkit.getScheduler().runTaskTimer(this, () -> client.sendHeartbeat(), 20L * 10, 20L * 30);
        // full team sync every 5 minutes as a safety net
        Bukkit.getScheduler().runTaskTimer(this, () -> teams.syncNow(), 20L * 60 * 5, 20L * 60 * 5);
    }

    @Override
    public void onDisable() {
        if (teams != null) teams.saveNow();
        if (pairing != null) pairing.cancelPoll();
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

    /** The key Flowt gave this server when it was connected. Stored in plugins/Flowt/connection.yml (not config.yml). */
    String apiKey() {
        File f = new File(getDataFolder(), "connection.yml");
        if (!f.exists()) return "";
        return YamlConfiguration.loadConfiguration(f).getString("api-key", "").trim();
    }

    void setApiKey(String key) {
        YamlConfiguration y = new YamlConfiguration();
        if (key != null && !key.isEmpty()) y.set("api-key", key);
        try {
            getDataFolder().mkdirs();
            y.save(new File(getDataFolder(), "connection.yml"));
        } catch (IOException e) {
            getLogger().warning("Could not save connection.yml: " + e.getMessage());
        }
    }

    FlowtClient client() { return client; }
    Pairing pairing() { return pairing; }
    TeamManager teams() { return teams; }
}
