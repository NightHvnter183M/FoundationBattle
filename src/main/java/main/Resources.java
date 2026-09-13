package main;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.world.Tile;

import java.util.Iterator;


public class Resources {
    public static int welcomeMenuId, teamMenuId, joinMenuId, AcceptMenuId, DenyMenuId, mapVoteMenuId;
    public static ObjectMap<Team, String> team_leaders = new ObjectMap<>();
    public static ObjectMap<String, Team> team_members = new ObjectMap<>();
    public static ObjectMap<String, String> join_requests = new ObjectMap<>();
    public static ObjectIntMap<Integer> votes = new ObjectIntMap<>();
    public static Boolean isRestartVoting = false;
    public static ObjectSet<String> restartVotes = new ObjectSet<>();
    public static float buildRadius = 35f;


    public static String custommotd = """
           [#008B8B]Foundation Battle - [white]Mixtech pvp server
           [white]Time until round end :""";

    /// Doing here some static methods for easy use in different places
    public static boolean isLeader(Player p) {
        String teamLeaderUuid = team_leaders.get(p.team());
        if(teamLeaderUuid == null){
            Log.err("Couldn't find team leader for " + p.team());
            return false;
        }
        return teamLeaderUuid.equals(p.uuid());
    }

    public static void destroyTeam(Player p) {
        Team team = p.team();
        if (team == null) return;
        clearTeamBorders(team);
        team_leaders.remove(team);
        //Using iterator not to get ConcurrentModificationException
        Iterator<ObjectMap.Entry<String, Team>> iterator = team_members.iterator();
        while (iterator.hasNext()) {
            ObjectMap.Entry<String, Team> entry = iterator.next();
            if (entry.value == team) iterator.remove();
        }
        Seq<Tile> coreTiles = new Seq<>();
        for (var core : team.cores()) {
            coreTiles.add(core.tile);
        }
        for (Tile tile : coreTiles) {
            if (tile.build != null) {
                tile.build.remove();
            }
            tile.setNet(Blocks.air);
        }
        updateAllTeamBorders();
        Groups.player.each(player -> {
            if (player.team().equals(team)) {
                player.team(Team.all[0]);
                if (player.unit() != null) player.unit().kill();
            }
        });
        team.data().destroyToDerelict();
    }

    public static Seq<Player> getLeaders(Player p) {
        Seq<Player> list = new Seq<>();
        Groups.player.each(player -> {
           if (player == p) return;
           if (team_leaders.containsKey(player.team())) list.add(player);
        });
        return list;
    }

    public static Seq<Player> getRequesters(Player p) {
        Seq<Player> list = new Seq<>();
        for (var entry : join_requests.entries()){
            if (entry.value.equals(p.uuid())){
                Player req = Groups.player.find(found -> found.uuid().equals(entry.key));
                if (req != null) list.add(req);
            }
        }
        return list;
    }

    public static void updateTeamBorders(Team team) {
        var cores = team.cores();
        if (cores.isEmpty()) return;
        int radius = (int) buildRadius;
        int minX = Vars.world.width(), maxX = 0;
        int minY = Vars.world.height(), maxY = 0;
        for (var core : cores) {
            minX = Math.min(minX, core.tile.x - radius - 1);
            maxX = Math.max(maxX, core.tile.x + radius + 1);
            minY = Math.min(minY, core.tile.y - radius - 1);
            maxY = Math.max(maxY, core.tile.y + radius + 1);
        }
        minX = Math.max(0, minX);
        maxX = Math.min(Vars.world.width() - 1, maxX);
        minY = Math.max(0, minY);
        maxY = Math.min(Vars.world.height() - 1, maxY);
        for (int x = minX; x <= maxX; x+=2) {
            for (int y = minY; y <= maxY; y++) {
                Tile tile = Vars.world.tile(x, y);
                if (tile == null) continue;
                boolean inside = isInsideAnyCore(x, y, cores, radius);
                if (inside) {
                    boolean isEdge = !isInsideAnyCore(x + 1, y, cores, radius) ||
                            !isInsideAnyCore(x - 1, y, cores, radius) ||
                            !isInsideAnyCore(x, y + 1, cores, radius) ||
                            !isInsideAnyCore(x, y - 1, cores, radius);
                    if (isEdge) {
                        if ((x + y) % 2 == 0) {
                            if (tile.block() == Blocks.air) {
                                tile.setNet(Blocks.illuminator, team, 0);
                            }
                        }
                    }
                }
            }
        }
    }
    private static boolean isInsideAnyCore(int x, int y, arc.struct.Seq<mindustry.world.blocks.storage.CoreBlock.CoreBuild> cores, int radius) {
        for (var core : cores) {
            if (Math.hypot(x - core.tile.x, y - core.tile.y) <= radius) {
                return true;
            }
        }
        return false;
    }

    public static void clearTeamBorders(Team team) {
        Groups.build.each(b -> b.team == team && b.block == Blocks.illuminator, b -> b.tile.removeNet());
    }

    public static void updateAllTeamBorders() {
        for (Team team : Team.all) {
            if (team.active() && !team.cores().isEmpty()) {
                clearTeamBorders(team);
                updateTeamBorders(team);
            } else {
                clearTeamBorders(team);
            }
        }
    }
}