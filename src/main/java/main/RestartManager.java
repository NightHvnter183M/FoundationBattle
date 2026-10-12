package main;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Strings;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.maps.Map;

public class RestartManager {
    private static final int MAP_VOTE_SECONDS = 20;
    private static final int RESTART_VOTE_SECONDS = 300;
    private static final float RESTART_VOTE_SHARE = 0.6f;

    private static final SnapshotMenu mapMenu = new SnapshotMenu();
    private static final Seq<Map> mapOptions = new Seq<>();
    private static final ObjectIntMap<String> mapVotes = new ObjectIntMap<>();
    private static final ObjectSet<String> restartVotes = new ObjectSet<>();
    private static boolean restartVoting, mapVoting;
    private static Timer.Task restartVoteTask, mapVoteTask;
    private static String standings = "";

    public static void onJoin(Player p) {
        if (mapVoting) showMapVote(p);
    }

    public static void onLeave() {
        Core.app.post(RestartManager::checkRestartVote);
    }

    public static boolean isEnding() {
        return mapVoting || Vars.state.gameOver;
    }

    public static void vote(Player p) {
        if (isEnding()) {
            p.sendMessage("[lightgray]The round is already ending.");
            return;
        }
        if (!restartVoting) {
            restartVoting = true;
            restartVotes.clear();
            restartVotes.add(p.uuid());
            restartVoteTask = Timer.schedule(() -> {
                if (!restartVoting) return;
                stopRestartVote();
                Call.sendMessage("[scarlet]Not enough votes to end the round.");
            }, RESTART_VOTE_SECONDS);
            Call.sendMessage(Strings.format("@[accent] wants to end the round. Type [white]/restart[accent] to agree. ([green]@[accent]/[orange]@[accent], @ min)",
                p.name, votes(), needed(), RESTART_VOTE_SECONDS / 60));
        } else if (!restartVotes.add(p.uuid())) {
            p.sendMessage("[lightgray]You have already voted.");
            return;
        } else {
            Call.sendMessage(Strings.format("@[accent] voted to end the round. ([green]@[accent]/[orange]@[accent])", p.name, votes(), needed()));
        }
        checkRestartVote();
    }

    private static int needed() {
        return Math.max(1, Mathf.ceil(Groups.player.size() * RESTART_VOTE_SHARE));
    }

    private static int votes() {
        return Groups.player.count(p -> restartVotes.contains(p.uuid()));
    }

    private static void checkRestartVote() {
        if (restartVoting && votes() >= needed()) startMapVote("[accent]The vote passed!");
    }

    private static void stopRestartVote() {
        restartVoting = false;
        restartVotes.clear();
        if (restartVoteTask != null) restartVoteTask.cancel();
        restartVoteTask = null;
    }

    public static void startMapVote(String reason) {
        if (isEnding()) return;
        stopRestartVote();
        standings = Resources.standings();

        Seq<Map> pool = Vars.maps.customMaps();
        if (pool.isEmpty()) pool = Vars.maps.all().copy();
        if (pool.size > 1) pool.remove(Vars.state.map, true);
        if (pool.isEmpty()) {
            Call.sendMessage(reason);
            Events.fire(new EventType.GameOverEvent(Team.derelict));
            return;
        }
        pool.shuffle();
        pool.truncate(6);
        mapOptions.set(pool);
        mapVotes.clear();
        mapVoting = true;

        Call.sendMessage(reason + "[lightgray] Vote for the next map - " + MAP_VOTE_SECONDS + " seconds.");
        Resources.online().each(RestartManager::showMapVote);
        mapVoteTask = Timer.schedule(RestartManager::finishMapVote, MAP_VOTE_SECONDS);
    }

    private static void showMapVote(Player p) {
        SnapshotMenu.Options options = new SnapshotMenu.Options();
        for (int i = 0; i < mapOptions.size; i++) {
            int index = i;
            options.add(mapOptions.get(i).name(), () -> {
                if (!mapVoting) return;
                mapVotes.put(p.uuid(), index);
                p.sendMessage("[lightgray]Your vote: []" + mapOptions.get(index).name());
            });
        }
        String text = (standings.isEmpty() ? "" : "[accent]Top teams[]\n" + standings + "\n") + "Choose the next map:";
        mapMenu.show(p, "Round is over!", text, options);
    }

    private static void finishMapVote() {
        mapVoteTask = null;
        if (!mapVoting || Vars.state.gameOver) return;
        int[] counts = new int[mapOptions.size];
        for (ObjectIntMap.Entry<String> e : mapVotes) {
            if (e.value >= 0 && e.value < counts.length) counts[e.value]++;
        }
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        Map next = mapOptions.get(best);
        Call.sendMessage(Strings.format("[accent]Next map: [white]@[lightgray] (@ @)", next.name(), counts[best], counts[best] == 1 ? "vote" : "votes"));
        Vars.maps.setNextMapOverride(next);
        Events.fire(new EventType.GameOverEvent(Team.derelict));
    }

    public static void reset() {
        stopRestartVote();
        mapVoting = false;
        mapVotes.clear();
        mapOptions.clear();
        if (mapVoteTask != null) mapVoteTask.cancel();
        mapVoteTask = null;
        standings = "";
    }
}
