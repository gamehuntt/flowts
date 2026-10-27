package app.flowt;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Handles /connect, /account, /discord (alias /discordsrv, /dsrv) and /flowt. */
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
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        // /connect [code]  - connect this server to a Discord channel
        if (name.equals("connect")) {
            connect(sender, args);
            return true;
        }

        // /account connect [code]  - link your Minecraft account to your Discord account
        if (name.equals("account")) {
            if (sub.equals("connect")) link(sender, args);
            else Msg.info(sender, "Usage: /" + label + " connect [code]");
            return true;
        }

        // /discord and its DiscordSRV-style subcommands
        if (name.equals("discord")) {
            switch (sub) {
                case "link" -> link(sender, args);
                case "reload", "status", "disconnect" -> admin(sender, sub);
                default -> {
                    String invite = plugin.getConfig().getString("discord-invite", "");
                    if (invite != null && !invite.isBlank()) Msg.ok(sender, "Join our Discord: " + ChatColor.AQUA + invite);
                    else Msg.info(sender, "Link your account with /account connect");
                }
            }
            return true;
        }

        // /flowt
        switch (sub) {
            case "connect", "setup" -> connect(sender, Arrays.copyOfRange(args, 1, args.length));
            case "status", "reload", "disconnect" -> admin(sender, sub);
            default -> {
                Msg.info(sender, "Flowt commands:");
                Msg.plain(sender, ChatColor.GRAY + " /connect [code] - connect this server to Discord");
                Msg.plain(sender, ChatColor.GRAY + " /account connect [code] - link your Discord account");
                Msg.plain(sender, ChatColor.GRAY + " /flowt status - connection status");
                Msg.plain(sender, ChatColor.GRAY + " /flowt reload - reload config + reconnect");
                Msg.plain(sender, ChatColor.GRAY + " /flowt disconnect - forget the Discord connection");
            }
        }
        return true;
    }

    /** /connect [code] - args holds only what comes after the command name. */
    private void connect(CommandSender sender, String[] args) {
        if (!isAdmin(sender)) {
            Msg.err(sender, "You don't have permission.");
            return;
        }
        boolean hasKey = !plugin.apiKey().isEmpty();
        if (args.length >= 1) {
            // a code made in Discord with /connect
            if (hasKey) {
                if (!plugin.client().confirmSetup(sender, args[0])) {
                    Msg.err(sender, "Not connected to the Flowt service right now. Wait a few seconds and try again (/flowt status).");
                } else {
                    Msg.info(sender, "Checking your code...");
                }
            } else {
                plugin.pairing().claim(sender, args[0]);
            }
            return;
        }
        // no code: make one here, then enter it in Discord with /connect code:<code>
        if (hasKey) {
            if (!plugin.client().requestSetup(sender)) {
                Msg.err(sender, "Not connected to the Flowt service right now. Wait a few seconds and try again (/flowt status).");
            } else {
                Msg.info(sender, "Asking Flowt for a code...");
            }
        } else {
            plugin.pairing().start(sender);
        }
    }

    private void link(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.err(sender, "Only players can link an account.");
            return;
        }
        if (!p.hasPermission("flowt.link")) {
            Msg.err(p, "You don't have permission.");
            return;
        }
        if (args.length >= 2) {
            // a code made in Discord with /account connect
            if (!plugin.client().confirmLink(p, args[1])) {
                Msg.err(p, "The Discord bridge is offline right now. Try again in a minute.");
                return;
            }
            Msg.info(p, "Checking your code...");
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
            case "disconnect" -> {
                plugin.setApiKey("");
                plugin.client().restart();
                Msg.ok(sender, "Disconnected from Discord. Run /connect to connect again.");
            }
            default -> { }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> options = switch (name) {
            case "account" -> List.of("connect");
            case "discord" -> List.of("link", "status", "reload");
            case "flowt" -> List.of("connect", "status", "reload", "disconnect");
            default -> List.of();
        };
        for (String o : options) {
            if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(o);
        }
        return out;
    }
}
