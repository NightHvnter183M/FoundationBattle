package main;

import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.net.Administration;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class Resources {
    public static final float BUILD_RADIUS = 35f;
    public static final float START_MIN_DISTANCE = 125f;
    public static final float VAULT_MIN_ENEMY_DISTANCE = 93.75f;
    public static final int ROUND_SECONDS = 3 * 60 * 60;
    public static final int JOIN_REQUEST_COOLDOWN = 10;
    public static final String TITLE = "Foundation Battle";
    public static final String DISCORD_URL = "https://discord.gg/xxxxxxx";

    public static String custommotd = """
           [#008B8B]Foundation Battle - [white]Mixtech pvp server
           [white]Time until round end :""";

    public static final ObjectMap<Team, String> teamLeaders = new ObjectMap<>();
    public static final ObjectMap<String, Team> teamMembers = new ObjectMap<>();
    public static final ObjectMap<Team, String> teamNames = new ObjectMap<>();
    public static final ObjectMap<String, Team> joinRequests = new ObjectMap<>();
    public static final ObjectSet<String> welcomed = new ObjectSet<>();
    private static final ObjectMap<String, Long> cooldowns = new ObjectMap<>();
    private static final Seq<Tile> tmpTiles = new Seq<>();


    public static boolean isTracked(Team team) {
        return teamLeaders.containsKey(team);
    }

    public static boolean isLeader(Player p) {
        return p.uuid().equals(teamLeaders.get(p.team()));
    }

    public static boolean isSpectator(Player p) {
        return p.team() == Team.derelict;
    }

    public static String teamName(Team team) {
        String name = teamNames.get(team);
        return "[#" + team.color + "]" + (name == null ? team.name : name) + "[]";
    }

    public static Seq<Player> online() {
        return Groups.player.copy();
    }

    public static Player findOnline(String uuid) {
        return uuid == null ? null : Groups.player.find(p -> p.uuid().equals(uuid));
    }

    public static Player onlineLeader(Team team) {
        return findOnline(teamLeaders.get(team));
    }

    public static int onlineCount(Team team) {
        return Groups.player.count(p -> p.team() == team);
    }

    public static String nameOf(String uuid) {
        Player online = findOnline(uuid);
        if (online != null) return online.name;
        Administration.PlayerInfo info = Vars.netServer.admins.getInfoOptional(uuid);
        return info == null ? "unknown" : info.lastName;
    }

    public static Seq<String> memberUuids(Team team) {
        Seq<String> out = new Seq<>();
        teamMembers.each((uuid, t) -> {
            if (t == team) out.add(uuid);
        });
        return out;
    }

    public static Seq<Player> requesters(Team team) {
        return online().select(p -> joinRequests.get(p.uuid()) == team);
    }

    public static boolean insideTerritory(Team team, Tile tile) {
        if (tile == null) return false;
        float range = BUILD_RADIUS * Vars.tilesize;
        for (CoreBuild core : team.cores()) {
            if (core.tile.within(tile, range)) return true;
        }
        return false;
    }

    public static CoreBuild nearestCore(Tile tile, Team except) {
        CoreBuild best = null;
        float bestDst = Float.MAX_VALUE;
        for (TeamData data : Vars.state.teams.getActive()) {
            if (data.team == except) continue;
            for (CoreBuild core : data.cores) {
                float dst = core.tile.dst2(tile);
                if (dst < bestDst) {
                    bestDst = dst;
                    best = core;
                }
            }
        }
        return best;
    }

    public static float tilesBetween(Tile a, Tile b) {
        return a.dst(b) / Vars.tilesize;
    }

    public static String foundBlocker(Tile tile) {
        CoreBuild near = nearestCore(tile, null);
        if (near != null) {
            float dist = tilesBetween(tile, near.tile);
            if (dist < START_MIN_DISTANCE) {
                return Strings.format("Too close to the core of @[scarlet]: @ tiles, need at least @.",
                    teamName(near.team), (int) dist, (int) START_MIN_DISTANCE);
            }
        }
        int size = Blocks.coreNucleus.size;
        tile.getLinkedTilesAs(Blocks.coreNucleus, tmpTiles);
        if (tmpTiles.size < size * size) return "Too close to the map edge.";
        for (Tile t : tmpTiles) {
            if (t.block() != Blocks.air || t.floor().isDeep() || t.floor().solid) {
                return Strings.format("The core needs a clear @x@ area of dry ground.", size, size);
            }
        }
        return null;
    }

    public static void moveToTeam(Player p, Team team) {
        p.clearUnit();
        p.team(team);
    }

    public static void addMember(Player p, Team team) {
        joinRequests.remove(p.uuid());
        teamMembers.put(p.uuid(), team);
        moveToTeam(p, team);
    }

    public static void foundTeam(Player p, Tile tile) {
        if (!isSpectator(p)) {
            p.sendMessage("[scarlet]You are already in a team.");
            return;
        }
        String blocker = foundBlocker(tile);
        if (blocker != null) {
            p.sendMessage("[scarlet]" + blocker);
            return;
        }
        Team team = freeTeam();
        if (team == null) {
            p.sendMessage("[scarlet]No free team slots left.");
            return;
        }
        tile.setNet(Blocks.coreNucleus, team, 0);
        teamLeaders.put(team, p.uuid());
        teamNames.put(team, displayName(p));
        addMember(p, team);
        Call.sendMessage(Strings.format("@[accent] founded the team @[accent].", p.name, teamName(team)));
        p.sendMessage(Strings.format("[accent]You lead @[accent] now.[] Build within @ tiles of your cores; a vault far from enemies becomes a new core. [accent]/team[] opens the team menu.",
            teamName(team), (int) BUILD_RADIUS));
    }

    private static Team freeTeam() {
        for (Team team : Team.all) {
            if (team.id > 6 && !team.active() && !isTracked(team) && !teamMembers.containsValue(team, true)) return team;
        }
        return null;
    }

    private static String displayName(Player p) {
        String name = Strings.stripColors(p.name).trim();
        if (name.isEmpty()) name = "Team";
        if (name.length() > 16) name = name.substring(0, 16);
        return name.replace("[", "[[");
    }

    public static void setLeader(Team team, String uuid) {
        teamLeaders.put(team, uuid);
        teamMessage(team, "[accent]" + nameOf(uuid) + "[accent] is the team leader now.");
    }

    public static void requestJoin(Player p, Team team) {
        if (isLeader(p)) {
            p.sendMessage("[scarlet]A leader can't join another team. Leave yours first (/spectate).");
            return;
        }
        if (p.team() == team) {
            p.sendMessage("[lightgray]You are already in this team.");
            return;
        }
        Player leader = onlineLeader(team);
        if (!isTracked(team) || leader == null) {
            p.sendMessage("[scarlet]This team can't take requests right now.");
            return;
        }
        if (joinRequests.get(p.uuid()) == team) {
            p.sendMessage("[lightgray]Your request is already waiting for the leader.");
            return;
        }
        if (!ready(p, "join", JOIN_REQUEST_COOLDOWN * 1000L)) {
            p.sendMessage("[scarlet]Wait a few seconds before sending another request.");
            return;
        }
        joinRequests.put(p.uuid(), team);
        p.sendMessage("[accent]Request sent to " + teamName(team) + "[accent]. Wait for the leader's answer.");
        leader.sendMessage("[accent]" + p.name + "[accent] wants to join your team. [white]/accept[] or [white]/team");
    }

    public static void acceptRequest(Player leader, Player requester) {
        Team team = leader.team();
        if (!isLeader(leader) || !requester.isAdded() || joinRequests.get(requester.uuid()) != team || isLeader(requester)) {
            joinRequests.remove(requester.uuid());
            leader.sendMessage("[lightgray]This request is no longer valid.");
            return;
        }
        addMember(requester, team);
        requester.sendMessage("[accent]You joined " + teamName(team) + "[accent]!");
        teamMessage(team, "[accent]" + requester.name + "[accent] joined the team.");
    }

    public static void denyRequest(Player leader, Player requester) {
        Team team = leader.team();
        if (!isLeader(leader) || joinRequests.get(requester.uuid()) != team) {
            leader.sendMessage("[lightgray]This request is no longer valid.");
            return;
        }
        joinRequests.remove(requester.uuid());
        requester.sendMessage(teamName(team) + "[scarlet] declined your request.");
        leader.sendMessage("[lightgray]Declined " + requester.name + "[lightgray].");
    }

    public static void kick(Player leader, String uuid) {
        Team team = leader.team();
        if (!isLeader(leader) || uuid.equals(leader.uuid()) || teamMembers.get(uuid) != team) {
            leader.sendMessage("[lightgray]This player is not in your team anymore.");
            return;
        }
        String name = nameOf(uuid);
        teamMembers.remove(uuid);
        Player target = findOnline(uuid);
        if (target != null && target.team() == team) {
            moveToTeam(target, Team.derelict);
            target.sendMessage("[scarlet]You were removed from " + teamName(team) + "[scarlet].");
        }
        teamMessage(team, "[lightgray]" + name + "[lightgray] was removed from the team.");
    }

    public static void transferLeadership(Player leader, Player target) {
        if (!isLeader(leader) || target == null || !target.isAdded() || target.team() != leader.team() || target == leader) {
            leader.sendMessage("[lightgray]This player can't lead your team right now.");
            return;
        }
        setLeader(leader.team(), target.uuid());
    }

    /** The player becomes a spectator. A leader hands the team to someone else; the last member disbands it. */
    public static void leaveTeam(Player p) {
        Team team = p.team();
        if (!isTracked(team)) {
            teamMembers.remove(p.uuid());
            if (team != Team.derelict) moveToTeam(p, Team.derelict);
            return;
        }
        boolean leader = isLeader(p);
        teamMembers.remove(p.uuid());
        if (leader) {
            String next = nextLeader(team, p.uuid());
            if (next == null) {
                disband(team, "[lightgray]You left and your team was disbanded.");
                return;
            }
            setLeader(team, next);
        }
        moveToTeam(p, Team.derelict);
        teamMessage(team, "[lightgray]" + p.name + "[lightgray] left the team.");
        p.sendMessage("[lightgray]You left " + teamName(team) + "[lightgray]. Tap free ground to found a team or use /join.");
    }

    private static String nextLeader(Team team, String except) {
        Player online = Groups.player.find(o -> o.team() == team && !o.uuid().equals(except));
        if (online != null) return online.uuid();
        for (String uuid : memberUuids(team)) {
            if (!uuid.equals(except)) return uuid;
        }
        return null;
    }

    public static void disband(Team team, String messageToMembers) {
        forget(team);
        Seq<Tile> coreTiles = team.cores().map(c -> c.tile);
        coreTiles.each(Tile::removeNet);
        for (Player p : online()) {
            if (p.team() != team) continue;
            moveToTeam(p, Team.derelict);
            p.sendMessage(messageToMembers);
        }
        Borders.clear(team);
        team.data().destroyToDerelict();
    }

    public static void eliminate(Team team, Team by) {
        String name = teamName(team);
        String killer = by != null && by != team && by != Team.derelict ? "[scarlet] by " + teamName(by) : "";
        forget(team);
        Call.sendMessage("[scarlet]Team " + name + "[scarlet] was destroyed" + killer + "[scarlet].");
        for (Player p : online()) {
            if (p.team() != team) continue;
            moveToTeam(p, Team.derelict);
            p.sendMessage("[scarlet]Your team was destroyed.[] Tap free ground to found a new one or use /join.");
        }
        Borders.clear(team);
        team.data().destroyToDerelict(); // no cores left, so nothing can be captured
    }

    public static void checkEliminations(CoreBuild changed) {
        for (Team team : teamLeaders.keys().toSeq()) {
            if (!team.cores().isEmpty()) continue;
            eliminate(team, changed != null && changed.team == team ? changed.lastDamage : null);
        }
    }

    private static void forget(Team team) {
        teamLeaders.remove(team);
        teamNames.remove(team);
        Seq<String> gone = memberUuids(team);
        gone.each(teamMembers::remove);
        gone.clear();
        joinRequests.each((uuid, t) -> {
            if (t == team) gone.add(uuid);
        });
        gone.each(joinRequests::remove);
    }

    public static Team assign(Player p) {
        Team team = teamMembers.get(p.uuid());
        if (team != null && isTracked(team) && !team.cores().isEmpty()) return team;
        teamMembers.remove(p.uuid());
        return Team.derelict;
    }

    public static void onJoin(Player p) {
        Team team = p.team();
        if (isTracked(team) && !isLeader(p) && onlineLeader(team) == null) setLeader(team, p.uuid());
    }

    public static void onLeave(Player p) {
        joinRequests.remove(p.uuid());
        Team team = p.team();
        if (isTracked(team) && isLeader(p)) {
            Player next = Groups.player.find(o -> o != p && o.team() == team);
            if (next != null) setLeader(team, next.uuid());
        }
    }

    public static void resetRound() {
        teamLeaders.clear();
        teamMembers.clear();
        teamNames.clear();
        joinRequests.clear();
        cooldowns.clear();
    }


    public static void teamMessage(Team team, String message) {
        for (Player p : online()) {
            if (p.team() == team) p.sendMessage(message);
        }
    }

    public static boolean ready(Player p, String key, long millis) {
        String k = p.uuid() + "/" + key;
        long now = Time.millis();
        Long until = cooldowns.get(k);
        if (until != null && until > now) return false;
        cooldowns.put(k, now + millis);
        return true;
    }

    public static void block(Player p, String key, long millis) {
        cooldowns.put(p.uuid() + "/" + key, Time.millis() + millis);
    }

    public static void hint(Player p, String key, String message) {
        if (ready(p, "hint/" + key, 3000)) p.sendMessage(message);
    }

    public static String standings() {
        Seq<Team> teams = teamLeaders.keys().toSeq().select(t -> !t.cores().isEmpty());
        teams.sort(t -> -t.cores().size);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(3, teams.size); i++) {
            Team t = teams.get(i);
            int cores = t.cores().size;
            out.append(i + 1).append(". ").append(teamName(t)).append(" [lightgray]- ")
                .append(cores).append(cores == 1 ? " core" : " cores").append("[]\n");
        }
        return out.toString();
    }

}
