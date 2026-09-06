package main;

import arc.Events;
import arc.struct.Seq;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;

public class RestartManager {
    private static Seq<Map> mapOptions = new Seq<>();
    private static Timer.Task voteTask;
    public static void restart(){
        Resources.votes.clear();
        mapOptions.clear();
        Seq<Map> allMaps = Vars.maps.customMaps().shuffle();
        for (int i = 0; i < Math.min(6, allMaps.size); i++){
            mapOptions.add(allMaps.get(i));
        }
        String[][] buttons = new String[mapOptions.size][1];
        for (int i = 0; i < mapOptions.size; i++){
            buttons[i][0] = mapOptions.get(i).name();
        }
        Call.menu(Resources.mapVoteMenuId, "Round is over!", "Choose a next map:", buttons);
        Timer.schedule(RestartManager::finish, 20);
    }

    private static void finish(){
        int winnerindex = 0;
        int maxvotes = -1;
        for (int i = 0; i < mapOptions.size; i++){
            int count = Resources.votes.get(i, 0);
            if (count > maxvotes){
                maxvotes = count;
                winnerindex = i;
            }
        }

        Map winner = mapOptions.get(winnerindex);
        Timer.schedule(() -> {
            Vars.maps.setNextMapOverride(winner);
            Events.fire(new EventType.GameOverEvent(Team.all[0]));
            Groups.player.each(p -> p.team(Team.all[0]));
        }, 3);
    }

    public static void startRestartVoting(Player player){
        if(Resources.isRestartVoting) return;
        Resources.isRestartVoting = true;
        Call.sendMessage(player.name + " started a voting of restart. Voting will last five minutes");
        Resources.restartVotes.clear();
        Resources.restartVotes.add(player.uuid());
        voteTask = Timer.schedule(() -> {
            if (Resources.isRestartVoting) {
                Resources.isRestartVoting = false;
                Call.sendMessage("[red]Not enough votes for restart. Voting is over");
                Resources.restartVotes.clear();
            }
        }, 300);
    }

    public static void AddVotes(Player player) {
        if (!Resources.isRestartVoting){
            startRestartVoting(player);
            return;
        }
        if(Resources.restartVotes.contains(player.uuid())){
            player.sendMessage("You have already voted for restart.");
            return;
        }
        Resources.restartVotes.add(player.uuid());
        int votesNeeded = (int) (Groups.player.size() * 0.6) + 1;
        int currentVotes = Resources.restartVotes.size;
        Call.sendMessage("[accent]" + player.name + "[white] Voted for restart. ([green]" + currentVotes + "[white]/[orange]" + votesNeeded + "[white])");
        if(currentVotes >= votesNeeded){
            Resources.isRestartVoting = false;
            if (voteTask != null) voteTask.cancel();
            restart();
        }

    }
}
