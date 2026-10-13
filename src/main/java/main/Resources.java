package main;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.net.Administration;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public class Resources {
    public static final String PLUGIN_ID = "foundation-battle";
    public static final float BUILD_RADIUS = 35f;
    public static final float START_MIN_DISTANCE = 125f;
    public static final float VAULT_MIN_ENEMY_DISTANCE = 93.75f;
    public static final int ROUND_SECONDS = 3 * 60 * 60;
    public static final int JOIN_REQUEST_COOLDOWN = 10;

    public static final String DISCORD_URL = "https://discord.gg/xxxxxxx";

    public static String custommotd = """
           [#008B8B]Foundation Battle - [white]Mixtech pvp server
           [white]Time until round end :""";

    public static final ObjectMap<Team, String> teamLeaders = new ObjectMap<>();
    public static final ObjectMap<String, Team> teamMembers = new ObjectMap<>();
    public static final ObjectMap<Team, String> teamNames = new ObjectMap<>();
    public static final ObjectMap<String, Team> joinRequests = new ObjectMap<>();
    public static final ObjectSet<String> welcomed = new ObjectSet<>();
    private static final ObjectIntMap<Team> knownMainCores = new ObjectIntMap<>();
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

    public static boolean isMainCore(CoreBuild core) {
        return core.block != Blocks.coreShard;
    }

    public static int mainCores(Team team) {
        return team.cores().count(Resources::isMainCore);
    }

    public static boolean isAlive(Team team) {
        return mainCores(team) > 0;
    }

    public static String coreSummary(Team team) {
        int nucleus = 0, foundation = 0, shard = 0;
        for (CoreBuild core : team.cores()) {
            if (core.block == Blocks.coreNucleus) nucleus++;
            else if (core.block == Blocks.coreFoundation) foundation++;
            else if (core.block == Blocks.coreShard) shard++;
        }
        StringBuilder out = new StringBuilder("[white]");
        if (nucleus > 0) out.append(Iconc.blockCoreNucleus).append(' ').append(nucleus).append("  ");
        if (foundation > 0) out.append(Iconc.blockCoreFoundation).append(' ').append(foundation).append("  ");
        if (shard > 0) out.append(Iconc.blockCoreShard).append(' ').append(shard);
        return out.toString().trim();
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
        return info == null ? "?" : info.lastName;
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

    public static Bundle.Text tiles(float amount) {
        return Bundle.count("count.tiles", (int) amount);
    }

    public static Bundle.Text foundBlocker(Tile tile) {
        CoreBuild near = nearestCore(tile, null);
        if (near != null) {
            float dist = tilesBetween(tile, near.tile);
            if (dist < START_MIN_DISTANCE) {
                return Bundle.text("found.too-close", teamName(near.team), tiles(dist), tiles(START_MIN_DISTANCE));
            }
        }
        int size = Blocks.coreNucleus.size;
        tile.getLinkedTilesAs(Blocks.coreNucleus, tmpTiles);
        if (tmpTiles.size < size * size) return Bundle.text("found.map-edge");
        for (Tile t : tmpTiles) {
            if (t.block() != Blocks.air || t.floor().isDeep() || t.floor().solid) return Bundle.text("found.no-space", size);
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
            Bundle.send(p, "found.already-in-team");
            return;
        }
        Bundle.Text blocker = foundBlocker(tile);
        if (blocker != null) {
            Bundle.send(p, blocker);
            return;
        }
        Team team = freeTeam();
        if (team == null) {
            Bundle.send(p, "found.no-slots");
            return;
        }
        tile.setNet(Blocks.coreNucleus, team, 0);
        teamLeaders.put(team, p.uuid());
        teamNames.put(team, displayName(p));
        addMember(p, team);
        Bundle.sendAll("team.founded", teamName(team));
        Bundle.send(p, "team.founded.you", teamName(team), tiles(BUILD_RADIUS));
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
        Bundle.sendTeam(team, "team.new-leader", nameOf(uuid));
    }

    public static void requestJoin(Player p, Team team) {
        if (isLeader(p)) {
            Bundle.send(p, "join.leader-cant");
            return;
        }
        if (p.team() == team) {
            Bundle.send(p, "join.same-team");
            return;
        }
        Player leader = onlineLeader(team);
        if (!isTracked(team) || leader == null) {
            Bundle.send(p, "join.unavailable");
            return;
        }
        if (joinRequests.get(p.uuid()) == team) {
            Bundle.send(p, "join.already-requested");
            return;
        }
        if (!ready(p, "join", JOIN_REQUEST_COOLDOWN * 1000L)) {
            Bundle.send(p, "join.cooldown");
            return;
        }
        joinRequests.put(p.uuid(), team);
        Bundle.send(p, "join.sent", teamName(team));
        Bundle.send(leader, "join.incoming", p.name);
    }

    public static void acceptRequest(Player leader, Player requester) {
        Team team = leader.team();
        if (!isLeader(leader) || !requester.isAdded() || joinRequests.get(requester.uuid()) != team || isLeader(requester)) {
            joinRequests.remove(requester.uuid());
            Bundle.send(leader, "request.invalid");
            return;
        }
        addMember(requester, team);
        Bundle.send(requester, "request.accepted", teamName(team));
        Bundle.sendTeam(team, "team.joined", requester.name);
    }

    public static void denyRequest(Player leader, Player requester) {
        Team team = leader.team();
        if (!isLeader(leader) || joinRequests.get(requester.uuid()) != team) {
            Bundle.send(leader, "request.invalid");
            return;
        }
        joinRequests.remove(requester.uuid());
        Bundle.send(requester, "request.declined", teamName(team));
        Bundle.send(leader, "request.declined.leader", requester.name);
    }

    public static void kick(Player leader, String uuid) {
        Team team = leader.team();
        if (!isLeader(leader) || uuid.equals(leader.uuid()) || teamMembers.get(uuid) != team) {
            Bundle.send(leader, "kick.not-member");
            return;
        }
        String name = nameOf(uuid);
        teamMembers.remove(uuid);
        Player target = findOnline(uuid);
        if (target != null && target.team() == team) {
            moveToTeam(target, Team.derelict);
            Bundle.send(target, "kick.you", teamName(team));
        }
        Bundle.sendTeam(team, "kick.team", name);
    }

    public static void transferLeadership(Player leader, Player target) {
        if (!isLeader(leader) || target == null || !target.isAdded() || target.team() != leader.team() || target == leader) {
            Bundle.send(leader, "leader.unavailable");
            return;
        }
        setLeader(leader.team(), target.uuid());
    }

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
                disband(team, "leave.disbanded");
                return;
            }
            setLeader(team, next);
        }
        moveToTeam(p, Team.derelict);
        Bundle.sendTeam(team, "leave.team", p.name);
        Bundle.send(p, "leave.you", teamName(team));
    }

    private static String nextLeader(Team team, String except) {
        Player online = Groups.player.find(o -> o.team() == team && !o.uuid().equals(except));
        if (online != null) return online.uuid();
        for (String uuid : memberUuids(team)) {
            if (!uuid.equals(except)) return uuid;
        }
        return null;
    }

    public static void disband(Team team, String messageKey) {
        forget(team);
        Seq<Tile> coreTiles = team.cores().map(c -> c.tile);
        coreTiles.each(Tile::removeNet);
        for (Player p : online()) {
            if (p.team() != team) continue;
            moveToTeam(p, Team.derelict);
            Bundle.send(p, messageKey);
        }
        Borders.clear(team);
        team.data().destroyToDerelict();
    }

    public static void eliminate(Team team, Team by) {
        String name = teamName(team);
        boolean byEnemy = by != null && by != team && by != Team.derelict;
        String attacker = byEnemy ? teamName(by) : null;
        forget(team);
        if (byEnemy) Bundle.sendAll("team.destroyed-by", name, attacker);
        else Bundle.sendAll("team.destroyed", name);
        for (Player p : online()) {
            if (p.team() != team) continue;
            moveToTeam(p, Team.derelict);
            Bundle.send(p, "team.destroyed.you");
        }
        Borders.clear(team);
        for (CoreBuild core : team.cores().copy()) {
            Call.effectReliable(Fx.massiveExplosion, core.x, core.y, 0f, team.color);
            core.tile.removeNet();
        }
        team.data().destroyToDerelict(); // no cores left now, so nothing can be captured
    }

    public static void checkEliminations(CoreBuild changed) {
        for (Team team : teamLeaders.keys().toSeq()) {
            int main = mainCores(team);
            int before = knownMainCores.get(team, main);
            knownMainCores.put(team, main);
            if (main == 0) {
                eliminate(team, changed != null && changed.team == team ? changed.lastDamage : null);
            } else if (main < before) {
                if (main == 1) Bundle.sendTeam(team, "team.last-main-core");
                else Bundle.sendTeam(team, "team.main-core-lost", main);
            }
        }
    }

    private static void forget(Team team) {
        teamLeaders.remove(team);
        teamNames.remove(team);
        knownMainCores.remove(team, 0);
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
        if (team != null && isTracked(team) && isAlive(team)) return team;
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
        knownMainCores.clear();
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


    public static void hint(Player p, String kind, Bundle.Text text) {
        if (ready(p, "hint/" + kind, 3000)) Bundle.send(p, text);
    }

    public static Bundle.Text standings() {
        Seq<Team> teams = teamLeaders.keys().toSeq().select(t -> !t.cores().isEmpty());
        teams.sort(t -> -t.cores().size);
        teams.truncate(3);
        Seq<String> names = teams.map(Resources::teamName);
        int[] cores = new int[teams.size];
        for (int i = 0; i < teams.size; i++) cores[i] = teams.get(i).cores().size;
        return lang -> {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < cores.length; i++) {
                out.append(Bundle.get(lang, "standings.line", i + 1, names.get(i), Bundle.count("count.cores", cores[i]))).append('\n');
            }
            return out.toString();
        };
    }

}
