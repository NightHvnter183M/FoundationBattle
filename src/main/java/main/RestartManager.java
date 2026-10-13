package main;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.game.Team;
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
    private static Bundle.Text standings = lang -> "";

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
            Bundle.send(p, "restart.ending");
            return;
        }
        if (!restartVoting) {
            restartVoting = true;
            restartVotes.clear();
            restartVotes.add(p.uuid());
            restartVoteTask = Timer.schedule(() -> {
                if (!restartVoting) return;
                stopRestartVote();
                Bundle.sendAll("restart.failed");
            }, RESTART_VOTE_SECONDS);
            Bundle.sendAll("restart.started", p.name, votes(), needed(), Bundle.count("count.minutes", RESTART_VOTE_SECONDS / 60));
        } else if (!restartVotes.add(p.uuid())) {
            Bundle.send(p, "restart.already-voted");
            return;
        } else {
            Bundle.sendAll("restart.voted", p.name, votes(), needed());
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
        if (restartVoting && votes() >= needed()) startMapVote("round.vote-passed");
    }

    private static void stopRestartVote() {
        restartVoting = false;
        restartVotes.clear();
        if (restartVoteTask != null) restartVoteTask.cancel();
        restartVoteTask = null;
    }

    public static void startMapVote(String reasonKey) {
        if (isEnding()) return;
        stopRestartVote();
        standings = Resources.standings();

        Seq<Map> pool = Vars.maps.customMaps();
        if (pool.isEmpty()) pool = Vars.maps.all().copy();
        if (pool.size > 1) pool.remove(Vars.state.map, true);
        if (pool.isEmpty()) {
            Bundle.sendAll(reasonKey);
            Events.fire(new EventType.GameOverEvent(Team.derelict));
            return;
        }
        pool.shuffle();
        pool.truncate(6);
        mapOptions.set(pool);
        mapVotes.clear();
        mapVoting = true;

        Bundle.sendAll("mapvote.start", Bundle.text(reasonKey), Bundle.count("count.seconds", MAP_VOTE_SECONDS));
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
                Bundle.send(p, "mapvote.your-vote", mapOptions.get(index).name());
            });
        }
        String top = Bundle.get(p, standings);
        String text = (top.isEmpty() ? "" : Bundle.get(p, "mapvote.top") + "\n" + top + "\n") + Bundle.get(p, "mapvote.choose");
        mapMenu.show(p, Bundle.get(p, "mapvote.title"), text, options);
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
        Bundle.sendAll("mapvote.result", next.name(), Bundle.count("count.votes", counts[best]));
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
        standings = lang -> "";
    }
}
