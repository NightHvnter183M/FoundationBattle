package main;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.world.Tile;


public class MenuManager {
    public enum RequestMode {ACCEPT, DENY, ASK}
    private final SnapshotMenu welcome = new SnapshotMenu();
    private final SnapshotMenu info = new SnapshotMenu();
    private final SnapshotMenu language = new SnapshotMenu();
    private final SnapshotMenu team = new SnapshotMenu();
    private final SnapshotMenu join = new SnapshotMenu();
    private final SnapshotMenu requests = new SnapshotMenu();
    private final SnapshotMenu decision = new SnapshotMenu();
    private final SnapshotMenu members = new SnapshotMenu();
    private final SnapshotMenu confirm = new SnapshotMenu();
    private final SnapshotMenu found = new SnapshotMenu();

    private static final Runnable close = () -> {};

    private static String t(Player p, String key, Object... args) {
        return Bundle.get(p, key, args);
    }

    public void showWelcome(Player p) {
        welcome.show(p, t(p, "welcome.title"), t(p, "welcome.text"), new SnapshotMenu.Options()
            .row(t(p, "menu.how-to-play"), () -> showInfo(p), t(p, "menu.language"), () -> showLanguage(p))
            .row(t(p, "menu.discord"), () -> Call.openURI(p.con, Resources.DISCORD_URL), t(p, "menu.close"), close));
    }

    public void showInfo(Player p) {
        info.show(p, t(p, "info.title"),
            t(p, "info.text", Resources.tiles(Resources.START_MIN_DISTANCE), Resources.tiles(Resources.BUILD_RADIUS),
                Resources.tiles(Resources.VAULT_MIN_ENEMY_DISTANCE), Bundle.count("count.hours", Resources.ROUND_SECONDS / 3600)),
            new SnapshotMenu.Options().add(t(p, "menu.close"), close));
    }

    public void showLanguage(Player p) {
        String picked = Bundle.picked(p);
        String game = Bundle.match(p.locale());
        String gameName = Bundle.languageName(game != null ? game : Bundle.serverDefault());
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        options.add(mark(picked == null, t(p, "lang.auto", gameName)), () -> {
            Bundle.pick(p, null);
            Bundle.send(p, "lang.auto-set", Bundle.languageName(Bundle.lang(p)));
        });
        for (String lang : Bundle.languages()) {
            options.add(mark(lang.equals(picked), Bundle.languageName(lang)), () -> {
                Bundle.pick(p, lang);
                Bundle.send(p, "lang.changed", Bundle.languageName(lang));
            });
        }
        options.add(t(p, "menu.close"), close);
        language.show(p, t(p, "lang.title"), t(p, "lang.text", Bundle.languageName(Bundle.lang(p))), options);
    }

    private static String mark(boolean current, String label) {
        return current ? "[accent]> " + label + " <" : label;
    }

    public void showTeam(Player p) {
        Team t = p.team();
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        if (!Resources.isTracked(t)) {
            options.add(t(p, "team.menu.join"), () -> showJoin(p))
                .add(t(p, "menu.how-to-play"), () -> showInfo(p))
                .add(t(p, "menu.close"), close);
            team.show(p, t(p, "team.menu.title"), t(p, "team.menu.spectator"), options);
            return;
        }

        String text = t(p, "team.menu.info", Resources.teamName(t), Resources.nameOf(Resources.teamLeaders.get(t)),
            Resources.onlineCount(t), Resources.coreSummary(t));
        if (Resources.isLeader(p)) {
            options.add(t(p, "team.menu.requests", Resources.requesters(t).size), () -> showRequests(p, RequestMode.ASK))
                .row(t(p, "team.menu.kick"), () -> showMembers(p, true), t(p, "team.menu.make-leader"), () -> showMembers(p, false))
                .add(t(p, "team.menu.leave"), () -> confirmLeave(p))
                .add(t(p, "menu.close"), close);
        } else {
            options.add(t(p, "team.menu.join-other"), () -> showJoin(p))
                .add(t(p, "team.menu.leave"), () -> confirmLeave(p))
                .add(t(p, "menu.close"), close);
        }
        team.show(p, t(p, "team.menu.title"), text, options);
    }

    public void showJoin(Player p) {
        if (Resources.isLeader(p)) {
            Bundle.send(p, "join.leader-cant");
            return;
        }
        Seq<Team> teams = Resources.teamLeaders.keys().toSeq().select(t -> t != p.team() && Resources.onlineLeader(t) != null);
        if (teams.isEmpty()) {
            Bundle.send(p, "join.none");
            return;
        }
        teams.sort(t -> -Resources.onlineCount(t));
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (Team t : teams) {
            options.add(t(p, "join.option", Resources.teamName(t), Resources.onlineCount(t)), () -> Resources.requestJoin(p, t));
        }
        options.add(t(p, "menu.close"), close);
        join.show(p, t(p, "join.title"), t(p, "join.text"), options);
    }

    public void showRequests(Player p, RequestMode mode) {
        if (!Resources.isLeader(p)) {
            Bundle.send(p, "requests.leader-only");
            return;
        }
        Seq<Player> list = Resources.requesters(p.team());
        if (list.isEmpty()) {
            Bundle.send(p, "requests.none");
            return;
        }
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (Player r : list) {
            options.add(r.name, () -> {
                switch (mode) {
                    case ACCEPT -> Resources.acceptRequest(p, r);
                    case DENY -> Resources.denyRequest(p, r);
                    default -> showDecision(p, r);
                }
            });
        }
        options.add(t(p, "menu.close"), close);
        String title = switch (mode) {
            case ACCEPT -> t(p, "requests.title.accept");
            case DENY -> t(p, "requests.title.deny");
            default -> t(p, "requests.title");
        };
        requests.show(p, title, t(p, "requests.text"), options);
    }

    private void showDecision(Player leader, Player r) {
        decision.show(leader, t(leader, "decision.title"), t(leader, "decision.text", r.name), new SnapshotMenu.Options()
            .row(t(leader, "decision.accept"), () -> Resources.acceptRequest(leader, r),
                t(leader, "decision.deny"), () -> Resources.denyRequest(leader, r))
            .add(t(leader, "menu.back"), () -> showRequests(leader, RequestMode.ASK)));
    }

    private void showMembers(Player p, boolean kick) {
        if (!Resources.isLeader(p)) return;
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (String uuid : Resources.memberUuids(p.team())) {
            if (uuid.equals(p.uuid())) continue;
            Player online = Resources.findOnline(uuid);
            if (!kick && online == null) continue;
            String name = Resources.nameOf(uuid);
            if (kick) {
                options.add(online == null ? t(p, "members.offline", name) : name, () -> ask(p, t(p, "members.kick.confirm.title"),
                    t(p, "members.kick.confirm.text", name), t(p, "members.kick.confirm.yes"), () -> Resources.kick(p, uuid)));
            } else {
                options.add(name, () -> ask(p, t(p, "members.leader.confirm.title"),
                    t(p, "members.leader.confirm.text", name), t(p, "members.leader.confirm.yes"),
                    () -> Resources.transferLeadership(p, online)));
            }
        }
        if (options.isEmpty()) {
            Bundle.send(p, kick ? "members.nobody" : "members.nobody-online");
            return;
        }
        options.add(t(p, "menu.back"), () -> showTeam(p));
        members.show(p, t(p, kick ? "members.kick.title" : "members.leader.title"),
            t(p, kick ? "members.kick.text" : "members.leader.text"), options);
    }

    public void confirmLeave(Player p) {
        String text;
        if (!Resources.isLeader(p)) text = t(p, "leave.confirm.member");
        else if (Resources.memberUuids(p.team()).size <= 1) text = t(p, "leave.confirm.last");
        else text = t(p, "leave.confirm.leader");
        ask(p, t(p, "leave.confirm.title"), text, t(p, "leave.confirm.yes"), () -> Resources.leaveTeam(p));
    }

    public void showFound(Player p, Tile tile) {
        int x = tile.x, y = tile.y;
        found.show(p, t(p, "found.title"), t(p, "found.text", x, y), new SnapshotMenu.Options()
            .row(t(p, "found.yes"), () -> {
                Tile now = Vars.world.tile(x, y); // the tile of the current map, not a stale one
                if (now != null) Resources.foundTeam(p, now);
            }, t(p, "menu.cancel"), () -> Resources.block(p, "tap", 3000)));
    }

    private void ask(Player p, String title, String text, String yes, Runnable action) {
        confirm.show(p, title, text, new SnapshotMenu.Options().row(yes, action, t(p, "menu.cancel"), close));
    }
}
