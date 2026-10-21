package app.flowt;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Handles /discord (alias /discordsrv, /dsrv) and /flowt. */
final class FlowtCommand implements TabExecutor {
    private final FlowtPlugin plugin;

    FlowtCommand(FlowtPlugin plugin) {
        this.plugin = plugin;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.hasPermission("flowt.admin") || s.hasPermission("discordsrv.admin");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        // /discord and its DiscordSRV-style subcommands
        if (cmd.getName().equalsIgnoreCase("discord")) {
            switch (sub) {
                case "link" -> link(sender);
                case "reload", "status" -> admin(sender, sub);
                default -> {
                    String invite = plugin.getConfig().getString("discord-invite", "");
                    if (invite != null && !invite.isBlank()) Msg.ok(sender, "Join our Discord: " + ChatColor.AQUA + invite);
                    else Msg.info(sender, "Link your account with /" + label + " link");
                }
            }
            return true;
        }

        // /flowt
        switch (sub) {
            case "setup", "status", "reload" -> admin(sender, sub);
            default -> {
                Msg.info(sender, "Flowt commands:");
                Msg.plain(sender, ChatColor.GRAY + " /flowt setup  - connect a Discord channel");
                Msg.plain(sender, ChatColor.GRAY + " /flowt status - connection status");
                Msg.plain(sender, ChatColor.GRAY + " /flowt reload - reload config + reconnect");
                Msg.plain(sender, ChatColor.GRAY + " /discord link - link your Discord account");
            }
        }
        return true;
    }

    private void link(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Msg.err(sender, "Only players can link an account.");
            return;
        }
        if (!p.hasPermission("flowt.link")) {
            Msg.err(p, "You don't have permission.");
            return;
        }
        if (!plugin.client().requestLink(p)) {
            Msg.err(p, "The Discord bridge is offline right now. Try again in a minute.");
            return;
        }
        Msg.info(p, "Creating your link code...");
    }

    private void admin(CommandSender sender, String sub) {
        if (!isAdmin(sender)) {
            Msg.err(sender, "You don't have permission.");
            return;
        }
        switch (sub) {
            case "setup" -> {
                if (!plugin.client().requestSetup(sender)) {
                    Msg.err(sender, "Not connected to the Flowt API. Check api-key in config.yml, then /flowt reload.");
                } else {
                    Msg.info(sender, "Asking Flowt for a setup code...");
                }
            }
            case "status" -> {
                boolean on = plugin.client().isConnected();
                Msg.plain(sender, Msg.PREFIX + "Flowt API: " + (on ? ChatColor.GREEN + "connected" : ChatColor.RED + "not connected"));
                Msg.plain(sender, Msg.PREFIX + ChatColor.GRAY + "Teams on this server: " + plugin.teams().all().size());
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.client().restart();
                Msg.ok(sender, "Config reloaded, reconnecting to Flowt...");
            }
            default -> { }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        List<String> options = cmd.getName().equalsIgnoreCase("discord")
                ? List.of("link", "status", "reload")
                : List.of("setup", "status", "reload");
        for (String o : options) {
            if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
        }
        return out;
    }
}
