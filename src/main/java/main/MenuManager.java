package main;

import arc.struct.Seq;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.world.Tile;

import static main.Resources.*;

public class MenuManager {
    public enum RequestMode {ACCEPT, DENY, ASK}
    private final SnapshotMenu welcome = new SnapshotMenu();
    private final SnapshotMenu info = new SnapshotMenu();
    private final SnapshotMenu team = new SnapshotMenu();
    private final SnapshotMenu join = new SnapshotMenu();
    private final SnapshotMenu requests = new SnapshotMenu();
    private final SnapshotMenu decision = new SnapshotMenu();
    private final SnapshotMenu members = new SnapshotMenu();
    private final SnapshotMenu confirm = new SnapshotMenu();
    private final SnapshotMenu found = new SnapshotMenu();

    private static final Runnable close = () -> {};

    public void showWelcome(Player p) {
        welcome.show(p, TITLE, """
            Welcome to the open test of Foundation Battle!

            [lightgray]Tap free ground to found your own team, or use [accent]/join[] to join one.""",
            new SnapshotMenu.Options()
                .add("How to play", () -> showInfo(p))
                .row("Discord", () -> Call.openURI(p.con, DISCORD_URL), "Close", close));
    }

    public void showInfo(Player p) {
        info.show(p, "How to play", Strings.format("""
            [accent]Teams[]
            Tap free ground at least @ tiles away from other cores to found a team and lead it.
            [accent]/join[] - ask to join a team, [accent]/team[] - team menu, [accent]/spectate[] - leave your team.

            [accent]Territory[]
            You can build only within @ tiles of your cores - the circle around them.
            A [accent]vault[] built at least @ tiles away from enemy cores turns into a new core.

            [accent]Round[]
            A round lasts @ hours. [accent]/restart[] starts a vote to end it early.""",
            (int) START_MIN_DISTANCE, (int) BUILD_RADIUS, (int) VAULT_MIN_ENEMY_DISTANCE, ROUND_SECONDS / 3600),
            new SnapshotMenu.Options().add("Close", close));
    }

    public void showTeam(Player p) {
        Team t = p.team();
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        if (!isTracked(t)) {
            options.add("Join a team", () -> showJoin(p))
                .add("How to play", () -> showInfo(p))
                .add("Close", close);
            team.show(p, "Team", "[lightgray]You are a spectator.\nTap free ground to found a team or join an existing one.", options);
            return;
        }

        String text = Strings.format("@\n[lightgray]Leader: @[lightgray]  |  online: @  |  cores: @",
            teamName(t), nameOf(teamLeaders.get(t)), onlineCount(t), t.cores().size);
        if (isLeader(p)) {
            options.add("Join requests (" + requesters(t).size + ")", () -> showRequests(p, RequestMode.ASK))
                .row("Kick member", () -> showMembers(p, true), "Make leader", () -> showMembers(p, false))
                .add("Leave team", () -> confirmLeave(p))
                .add("Close", close);
        } else {
            options.add("Join another team", () -> showJoin(p))
                .add("Leave team", () -> confirmLeave(p))
                .add("Close", close);
        }
        team.show(p, "Team", text, options);
    }

    public void showJoin(Player p) {
        if (isLeader(p)) {
            p.sendMessage("[scarlet]A leader can't join another team. Leave yours first (/spectate).");
            return;
        }
        Seq<Team> teams = teamLeaders.keys().toSeq().select(t -> t != p.team() && onlineLeader(t) != null);
        if (teams.isEmpty()) {
            p.sendMessage("[lightgray]There are no teams to join right now. Tap free ground to found your own!");
            return;
        }
        teams.sort(t -> -onlineCount(t));
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (Team t : teams) {
            options.add(teamName(t) + " [lightgray](" + onlineCount(t) + " online)", () -> requestJoin(p, t));
        }
        options.add("Close", close);
        join.show(p, "Join a team", "Pick a team. Its leader will get your request.", options);
    }

    public void showRequests(Player p, RequestMode mode) {
        if (!isLeader(p)) {
            p.sendMessage("[scarlet]Only the team leader can answer join requests.");
            return;
        }
        Seq<Player> list = requesters(p.team());
        if (list.isEmpty()) {
            p.sendMessage("[lightgray]No pending join requests.");
            return;
        }
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (Player r : list) {
            options.add(r.name, () -> {
                switch (mode) {
                    case ACCEPT -> acceptRequest(p, r);
                    case DENY -> denyRequest(p, r);
                    default -> showDecision(p, r);
                }
            });
        }
        options.add("Close", close);
        String title = switch (mode) {
            case ACCEPT -> "Accept a request";
            case DENY -> "Deny a request";
            default -> "Join requests";
        };
        requests.show(p, title, "Players who want to join your team:", options);
    }

    private void showDecision(Player leader, Player r) {
        decision.show(leader, "Join request", r.name + "[white] wants to join your team.",
            new SnapshotMenu.Options()
                .row("[green]Accept", () -> acceptRequest(leader, r), "[scarlet]Deny", () -> denyRequest(leader, r))
                .add("Back", () -> showRequests(leader, RequestMode.ASK)));
    }

    private void showMembers(Player p, boolean kick) {
        if (!isLeader(p)) return;
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (String uuid : memberUuids(p.team())) {
            if (uuid.equals(p.uuid())) continue;
            Player online = findOnline(uuid);
            if (!kick && online == null) continue;
            String name = nameOf(uuid);
            if (kick) {
                options.add(name + (online == null ? " [lightgray](offline)" : ""), () ->
                    ask(p, "Kick member?", name + "[white] will be removed from the team.", "Kick", () -> Resources.kick(p, uuid)));
            } else {
                options.add(name, () ->
                    ask(p, "Make leader?", name + "[white] will lead the team instead of you.", "Confirm", () -> transferLeadership(p, online)));
            }
        }
        if (options.isEmpty()) {
            p.sendMessage(kick ? "[lightgray]There is nobody else in your team." : "[lightgray]No other members are online.");
            return;
        }
        options.add("Back", () -> showTeam(p));
        members.show(p, kick ? "Kick member" : "Make leader", kick ? "Choose a member to remove:" : "Choose the new leader:", options);
    }

    public void confirmLeave(Player p) {
        String text;
        if (!isLeader(p)) {
            text = "You will become a spectator.";
        } else if (memberUuids(p.team()).size <= 1) {
            text = "[scarlet]You are the last member: your team will be destroyed.[]\nIts cores disappear and its buildings become derelict.";
        } else {
            text = "Another member will lead the team.";
        }
        ask(p, "Leave the team?", text, "Leave", () -> leaveTeam(p));
    }

    public void showFound(Player p, Tile tile) {
        int x = tile.x, y = tile.y;
        found.show(p, "Found a team?", Strings.format("Place your core at ([accent]@[], [accent]@[])?\n[lightgray]You'll lead the new team; others join with /join.", x, y),
            new SnapshotMenu.Options().row("[accent]Found team", () -> {
                Tile now = Vars.world.tile(x, y); // the tile of the current map, not a stale one
                if (now != null) foundTeam(p, now);
            }, "Cancel", () -> block(p, "tap", 3000)));
    }

    private void ask(Player p, String title, String text, String yes, Runnable action) {
        confirm.show(p, title, text, new SnapshotMenu.Options().row(yes, action, "Cancel", close));
    }
}
