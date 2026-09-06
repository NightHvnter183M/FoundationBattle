package main;

import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import java.util.Iterator;


public class Resources {
    public static int welcomeMenuId, teamMenuId, joinMenuId, AcceptMenuId, DenyMenuId, mapVoteMenuId;
    public static ObjectMap<Team, String> team_leaders = new ObjectMap<>();
    public static ObjectMap<String, Team> team_members = new ObjectMap<>();
    public static ObjectMap<String, String> join_requests = new ObjectMap<>();
    public static ObjectIntMap<Integer> votes = new ObjectIntMap<>();
    public static Boolean isRestartVoting = false;
    public static ObjectSet<String> restartVotes = new ObjectSet<>();


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
        team_leaders.remove(team);
        //Using iterator not to get ConcurrentModificationException
        Iterator<ObjectMap.Entry<String, Team>> iterator = team_members.iterator();
        while (iterator.hasNext()) {
            ObjectMap.Entry<String, Team> entry = iterator.next();
            if (entry.value == team) iterator.remove();
        }
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
}