package app.flowt;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles /team (aliases /t, /teams) and /teama (alias /teamadmin). */
final class TeamCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("create", "invite", "join", "leave", "kick", "promote", "demote",
            "setowner", "disband", "info", "list", "top", "description", "open", "sethome", "home", "chat", "help");
    private static final List<String> ADMIN_SUBS = List.of("disband", "score", "info", "list");

    private final FlowtPlugin plugin;
    private final Map<UUID, Long> homeCooldown = new HashMap<>();
    private final Map<UUID, Long> disbandConfirm = new HashMap<>();

    TeamCommand(FlowtPlugin plugin) {
        this.plugin = plugin;
    }

    private TeamManager tm() { return plugin.teams(); }

    // ------------------------------------------------------------ entry
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!plugin.getConfig().getBoolean("teams.enabled", true)) {
            Msg.err(sender, "Teams are disabled on this server.");
            return true;
        }
        if (cmd.getName().equalsIgnoreCase("teama")) {
            admin(sender, args);
            return true;
        }
        if (!sender.hasPermission("flowt.team")) {
            Msg.err(sender, "You don't have permission.");
            return true;
        }
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create" -> create(sender, args);
            case "invite" -> invite(sender, args);
            case "join" -> join(sender, args);
            case "leave" -> leave(sender);
            case "kick" -> kick(sender, args);
            case "promote" -> promote(sender, args, true);
            case "demote" -> promote(sender, args, false);
            case "setowner" -> setOwner(sender, args);
            case "disband" -> disband(sender, args);
            case "info" -> info(sender, args);
            case "list" -> list(sender);
            case "top" -> top(sender);
            case "description", "desc" -> description(sender, args);
            case "open" -> toggleOpen(sender);
            case "sethome" -> setHome(sender);
            case "home" -> home(sender);
            case "chat", "c" -> chat(sender);
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        Msg.info(s, "Team commands:");
        String[] lines = {
                "create <name>", "invite <player>", "join <team>", "leave", "kick <player>",
                "promote|demote <player>", "setowner <player>", "disband", "info [team]", "list", "top",
                "description <text>", "open", "sethome", "home", "chat"
        };
        for (String l : lines) Msg.plain(s, ChatColor.GRAY + " /" + label + " " + l);
    }

    // ------------------------------------------------------------ helpers
    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        Msg.err(s, "This command can only be used in-game.");
        return null;
    }

    private Team myTeam(Player p) {
        Team t = tm().of(p.getUniqueId());
        if (t == null) Msg.err(p, "You are not in a team. Use /team create <name>.");
        return t;
    }

    private static boolean canManage(Team t, Player p) {
        Team.Role r = t.role(p.getUniqueId());
        return r == Team.Role.OWNER || r == Team.Role.ADMIN;
    }

    private static boolean isOwner(Team t, Player p) {
        return t.role(p.getUniqueId()) == Team.Role.OWNER;
    }

    private static boolean isAdmin(CommandSender s) {
        return s.hasPermission("flowt.admin") || s.hasPermission("betterteams.admin");
    }

    // ------------------------------------------------------------ subcommands
    private void create(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 2) { Msg.err(p, "Usage: /team create <name>"); return; }
        if (tm().of(p.getUniqueId()) != null) { Msg.err(p, "You are already in a team. Leave it first."); return; }
        String name = a[1];
        if (!TeamManager.NAME.matcher(name).matches()) {
            Msg.err(p, "Team names must be 2-16 characters: letters, numbers and _ only.");
            return;
        }
        if (tm().find(name) != null) { Msg.err(p, "That team name is already taken."); return; }
        Team t = tm().create(p, name);
        Msg.ok(p, "Team " + ChatColor.AQUA + t.name + ChatColor.GREEN + " created!");
    }

    private void invite(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!canManage(t, p)) { Msg.err(p, "Only the team owner or admins can invite."); return; }
        if (a.length < 2) { Msg.err(p, "Usage: /team invite <player>"); return; }
        Player target = Bukkit.getPlayerExact(a[1]);
        if (target == null) { Msg.err(p, "That player is not online."); return; }
        if (tm().of(target.getUniqueId()) != null) { Msg.err(p, target.getName() + " is already in a team."); return; }
        if (t.members.size() >= plugin.getConfig().getInt("teams.max-members", 10)) { Msg.err(p, "Your team is full."); return; }
        tm().invite(t, target.getUniqueId());
        Msg.ok(p, "Invited " + target.getName() + " (valid for 60 seconds).");
        Msg.ok(target, p.getName() + " invited you to team " + ChatColor.AQUA + t.name + ChatColor.GREEN + ". Type /team join " + t.name);
    }

    private void join(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 2) { Msg.err(p, "Usage: /team join <team>"); return; }
        if (tm().of(p.getUniqueId()) != null) { Msg.err(p, "You are already in a team."); return; }
        Team t = tm().find(a[1]);
        if (t == null) { Msg.err(p, "No team with that name."); return; }
        if (!t.open && !tm().hasInvite(p.getUniqueId(), t)) { Msg.err(p, "That team is invite-only and you have no invite."); return; }
        if (t.members.size() >= plugin.getConfig().getInt("teams.max-members", 10)) { Msg.err(p, "That team is full."); return; }
        tm().addMember(t, p);
        tm().broadcast(t, Msg.PREFIX + ChatColor.GREEN + p.getName() + " joined the team!");
    }

    private void leave(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (isOwner(t, p)) {
            Msg.err(p, "The owner can't leave. Use /team setowner <player> first, or /team disband.");
            return;
        }
        tm().removeMember(t, p.getUniqueId());
        Msg.ok(p, "You left " + t.name + ".");
        tm().broadcast(t, Msg.PREFIX + ChatColor.YELLOW + p.getName() + " left the team.");
    }

    private void kick(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!canManage(t, p)) { Msg.err(p, "Only the team owner or admins can kick."); return; }
        if (a.length < 2) { Msg.err(p, "Usage: /team kick <player>"); return; }
        Team.Member m = t.findMember(a[1]);
        if (m == null) { Msg.err(p, "That player is not in your team."); return; }
        if (m.uuid.equals(p.getUniqueId())) { Msg.err(p, "You can't kick yourself. Use /team leave."); return; }
        if (m.role == Team.Role.OWNER || (m.role == Team.Role.ADMIN && !isOwner(t, p))) {
            Msg.err(p, "You can't kick that member.");
            return;
        }
        tm().removeMember(t, m.uuid);
        tm().broadcast(t, Msg.PREFIX + ChatColor.YELLOW + m.name + " was kicked from the team.");
        Player kicked = Bukkit.getPlayer(m.uuid);
        if (kicked != null) Msg.err(kicked, "You were kicked from " + t.name + ".");
    }

    private void promote(CommandSender s, String[] a, boolean up) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!isOwner(t, p)) { Msg.err(p, "Only the team owner can do that."); return; }
        if (a.length < 2) { Msg.err(p, "Usage: /team " + (up ? "promote" : "demote") + " <player>"); return; }
        Team.Member m = t.findMember(a[1]);
        if (m == null) { Msg.err(p, "That player is not in your team."); return; }
        if (up && m.role == Team.Role.MEMBER) {
            m.role = Team.Role.ADMIN;
        } else if (!up && m.role == Team.Role.ADMIN) {
            m.role = Team.Role.MEMBER;
        } else {
            Msg.err(p, up ? "That player can't be promoted further." : "That player can't be demoted.");
            return;
        }
        tm().changed();
        tm().broadcast(t, Msg.PREFIX + ChatColor.GREEN + m.name + " is now " + (up ? "an admin." : "a member."));
    }

    private void setOwner(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!isOwner(t, p)) { Msg.err(p, "Only the team owner can do that."); return; }
        if (a.length < 2) { Msg.err(p, "Usage: /team setowner <player>"); return; }
        Team.Member m = t.findMember(a[1]);
        if (m == null || m.uuid.equals(p.getUniqueId())) { Msg.err(p, "Pick another member of your team."); return; }
        m.role = Team.Role.OWNER;
        t.members.get(p.getUniqueId()).role = Team.Role.ADMIN;
        tm().changed();
        tm().broadcast(t, Msg.PREFIX + ChatColor.GREEN + m.name + " is the new team owner.");
    }

    private void disband(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!isOwner(t, p)) { Msg.err(p, "Only the team owner can disband the team."); return; }
        long now = System.currentTimeMillis();
        boolean confirmed = a.length >= 2 && a[1].equalsIgnoreCase("confirm")
                && disbandConfirm.getOrDefault(p.getUniqueId(), 0L) > now;
        if (!confirmed) {
            disbandConfirm.put(p.getUniqueId(), now + 30_000L);
            Msg.err(p, "This deletes the team for good. Type /team disband confirm within 30 seconds.");
            return;
        }
        disbandConfirm.remove(p.getUniqueId());
        tm().broadcast(t, Msg.PREFIX + ChatColor.RED + "Your team " + t.name + " was disbanded.");
        tm().disband(t);
    }

    private void info(CommandSender s, String[] a) {
        Team t;
        if (a.length >= 2) {
            t = tm().find(a[1]);
            if (t == null) { Msg.err(s, "No team with that name."); return; }
        } else if (s instanceof Player p) {
            t = myTeam(p);
            if (t == null) return;
        } else {
            Msg.err(s, "Usage: /team info <team>");
            return;
        }
        showInfo(s, t);
    }

    private void showInfo(CommandSender s, Team t) {
        Team.Member owner = t.owner();
        Msg.plain(s, ChatColor.DARK_GRAY + "---- " + ChatColor.AQUA + t.name + ChatColor.DARK_GRAY + " ----");
        if (t.description != null && !t.description.isEmpty()) Msg.plain(s, ChatColor.GRAY + t.description);
        Msg.plain(s, ChatColor.GRAY + "Owner: " + ChatColor.WHITE + (owner == null ? "?" : owner.name)
                + ChatColor.GRAY + "   Score: " + ChatColor.WHITE + t.score
                + ChatColor.GRAY + "   Join: " + ChatColor.WHITE + (t.open ? "open" : "invite only"));
        StringBuilder sb = new StringBuilder();
        for (Team.Member m : t.members.values()) {
            if (sb.length() > 0) sb.append(ChatColor.GRAY).append(", ");
            boolean online = Bukkit.getPlayer(m.uuid) != null;
            String star = m.role == Team.Role.OWNER ? "* " : (m.role == Team.Role.ADMIN ? "+ " : "");
            sb.append(online ? ChatColor.GREEN : ChatColor.GRAY).append(star).append(m.name);
        }
        Msg.plain(s, ChatColor.GRAY + "Members (" + t.members.size() + "): " + sb);
    }

    private void list(CommandSender s) {
        List<Team> teams = new ArrayList<>(tm().all());
        if (teams.isEmpty()) { Msg.info(s, "There are no teams yet. Create one with /team create <name>."); return; }
        teams.sort(Comparator.comparingInt((Team t) -> t.members.size()).reversed());
        Msg.info(s, "Teams (" + teams.size() + "):");
        int shown = 0;
        for (Team t : teams) {
            if (shown++ >= 20) break;
            Msg.plain(s, ChatColor.GRAY + " - " + ChatColor.AQUA + t.name + ChatColor.GRAY + " (" + t.members.size() + " members)");
        }
    }

    private void top(CommandSender s) {
        List<Team> teams = new ArrayList<>(tm().all());
        if (teams.isEmpty()) { Msg.info(s, "There are no teams yet."); return; }
        teams.sort(Comparator.comparingInt((Team t) -> t.score).reversed());
        Msg.info(s, "Top teams on this server:");
        int rank = 1;
        for (Team t : teams) {
            if (rank > 10) break;
            Msg.plain(s, ChatColor.GRAY + " " + rank + ". " + ChatColor.AQUA + t.name + ChatColor.GRAY + " - " + t.score + " pts");
            rank++;
        }
        Msg.info(s, "Global ranking: use /teams top in the Flowt Discord bot.");
    }

    private void description(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!canManage(t, p)) { Msg.err(p, "Only the team owner or admins can do that."); return; }
        if (a.length < 2) { Msg.err(p, "Usage: /team description <text>"); return; }
        String text = Msg.limit(Msg.clean(String.join(" ", Arrays.copyOfRange(a, 1, a.length))), 100);
        t.description = text;
        tm().changed();
        Msg.ok(p, "Description updated.");
    }

    private void toggleOpen(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!canManage(t, p)) { Msg.err(p, "Only the team owner or admins can do that."); return; }
        t.open = !t.open;
        tm().changed();
        Msg.ok(p, t.open ? "Your team is now open: anyone can join." : "Your team is now invite-only.");
    }

    private void setHome(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!canManage(t, p)) { Msg.err(p, "Only the team owner or admins can do that."); return; }
        Location l = p.getLocation();
        if (l.getWorld() == null) { Msg.err(p, "Can't set a home here."); return; }
        t.hasHome = true;
        t.homeWorld = l.getWorld().getName();
        t.hx = l.getX();
        t.hy = l.getY();
        t.hz = l.getZ();
        t.hyaw = l.getYaw();
        t.hpitch = l.getPitch();
        tm().changed();
        Msg.ok(p, "Team home set.");
    }

    private void home(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        Team t = myTeam(p);
        if (t == null) return;
        if (!t.hasHome) { Msg.err(p, "Your team has no home yet. An admin can use /team sethome."); return; }
        World w = Bukkit.getWorld(t.homeWorld);
        if (w == null) { Msg.err(p, "The home world is not loaded."); return; }
        long now = System.currentTimeMillis();
        long wait = homeCooldown.getOrDefault(p.getUniqueId(), 0L) - now;
        if (wait > 0) { Msg.err(p, "Please wait " + (wait / 1000 + 1) + "s before using /team home again."); return; }
        homeCooldown.put(p.getUniqueId(), now + plugin.getConfig().getInt("teams.home-cooldown-seconds", 10) * 1000L);
        p.teleport(new Location(w, t.hx, t.hy, t.hz, t.hyaw, t.hpitch));
        Msg.ok(p, "Teleported to the team home.");
    }

    private void chat(CommandSender s) {
        Player p = player(s);
        if (p == null) return;
        if (myTeam(p) == null) return;
        if (tm().teamChat.remove(p.getUniqueId())) {
            Msg.ok(p, "Team chat off. You are talking to everyone again.");
        } else {
            tm().teamChat.add(p.getUniqueId());
            Msg.ok(p, "Team chat on. Only your team sees your messages.");
        }
    }

    // ------------------------------------------------------------ /teama
    private void admin(CommandSender s, String[] a) {
        if (!isAdmin(s)) { Msg.err(s, "You don't have permission."); return; }
        if (a.length == 0) {
            Msg.info(s, "Admin: /teama disband <team> | score <team> <number> | info <team> | list");
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(s);
            case "info" -> {
                Team t = a.length >= 2 ? tm().find(a[1]) : null;
                if (t == null) Msg.err(s, "Usage: /teama info <team>  (team not found?)");
                else showInfo(s, t);
            }
            case "disband" -> {
                Team t = a.length >= 2 ? tm().find(a[1]) : null;
                if (t == null) { Msg.err(s, "Usage: /teama disband <team>  (team not found?)"); return; }
                tm().broadcast(t, Msg.PREFIX + ChatColor.RED + "Your team " + t.name + " was disbanded by an admin.");
                tm().disband(t);
                Msg.ok(s, "Team " + t.name + " disbanded.");
            }
            case "score" -> {
                Team t = a.length >= 2 ? tm().find(a[1]) : null;
                if (t == null || a.length < 3) { Msg.err(s, "Usage: /teama score <team> <number>"); return; }
                try {
                    t.score = Integer.parseInt(a[2]);
                } catch (NumberFormatException e) {
                    Msg.err(s, "That's not a number.");
                    return;
                }
                tm().changed();
                Msg.ok(s, "Score of " + t.name + " is now " + t.score + ".");
            }
            default -> Msg.err(s, "Unknown admin subcommand.");
        }
    }

    // ------------------------------------------------------------ tab completion
    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    private List<String> teamNames() {
        List<String> names = new ArrayList<>();
        for (Team t : tm().all()) names.add(t.name);
        return names;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        boolean admin = cmd.getName().equalsIgnoreCase("teama");
        if (args.length == 1) return filter(admin ? ADMIN_SUBS : SUBS, args[0]);
        if (args.length != 2) return new ArrayList<>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (admin) {
            return (sub.equals("disband") || sub.equals("score") || sub.equals("info"))
                    ? filter(teamNames(), args[1]) : new ArrayList<>();
        }
        switch (sub) {
            case "invite": {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
                return filter(names, args[1]);
            }
            case "join":
            case "info":
                return filter(teamNames(), args[1]);
            case "kick":
            case "promote":
            case "demote":
            case "setowner": {
                List<String> names = new ArrayList<>();
                if (sender instanceof Player p) {
                    Team t = tm().of(p.getUniqueId());
                    if (t != null) for (Team.Member m : t.members.values()) names.add(m.name);
                }
                return filter(names, args[1]);
            }
            case "disband":
                return filter(List.of("confirm"), args[1]);
            default:
                return new ArrayList<>();
        }
    }
}
