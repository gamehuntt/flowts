package app.flowt;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

final class Msg {
    static final String PREFIX = ChatColor.DARK_GRAY + "[" + ChatColor.AQUA + "Flowt" + ChatColor.DARK_GRAY + "] " + ChatColor.RESET;

    private Msg() {}

    static void ok(CommandSender s, String m) { s.sendMessage(PREFIX + ChatColor.GREEN + m); }
    static void err(CommandSender s, String m) { s.sendMessage(PREFIX + ChatColor.RED + m); }
    static void info(CommandSender s, String m) { s.sendMessage(PREFIX + ChatColor.GRAY + m); }
    static void plain(CommandSender s, String m) { s.sendMessage(m); }

    /** Removes colour codes and the section sign so user text can't inject formatting. */
    static String clean(String s) {
        if (s == null) return "";
        return ChatColor.stripColor(s).replace('\u00A7', ' ');
    }

    static String limit(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
