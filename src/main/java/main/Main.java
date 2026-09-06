package main;

import arc.Events;
import arc.util.CommandHandler;
import arc.util.Timer;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

public class Main extends Plugin{
    private MenuManager menuManager;
    private Timer.Task roundTimerTask;
    private Timer.Task serverInfoTimerTask;
    @Override
    public void init(){
        ///Setting up server
        Administration.Config.serverName.set("[#5F9EA0]Foundation PvP");
        this.menuManager = new MenuManager();
        menuManager.init();
        /// Some kind of player's cache manipulations to properly transfer them to their teams after reconnect
        Events.on(EventType.PlayerJoin.class, event -> {
            menuManager.callWelcomeMenu(event.player);
            if (Resources.team_members.containsKey(event.player.uuid())) event.player.team(Resources.team_members.get(event.player.uuid()));
            else event.player.team(Team.all[0]);
        });

        /// When a spectator taps on free ground creating a new team
        Events.on(EventType.TapEvent.class, event -> {
            Tile tile = event.tile;
            Player p = event.player;
            if (p.team() != Team.all[0]) return;
            if (tile.solid()) return;
            for (var build : Groups.build){
                if (build instanceof CoreBlock.CoreBuild){
                    if (tile.dst(build.tile) < 1000f) {
                        p.sendMessage("Too close to other cores!");
                        return;
                    }
                }
            }
            teamStart(p, tile);
        });

        /// Duplicating here some stuff because it has to be done every restart also (done in worldLoadEvent)
        Events.on(EventType.GameOverEvent.class, event -> {
           Groups.player.each(p -> p.team(Team.all[0]));
           Resources.team_leaders.clear();
           Resources.team_members.clear();
        });

        Events.on(EventType.WorldLoadEndEvent.class, event -> {
            final int[] roundTime = {10800};
            Groups.player.each(p -> p.team(Team.all[0]));
            Resources.team_leaders.clear();
            Resources.team_members.clear();
            if (roundTimerTask != null) {
                roundTimerTask.cancel();
                roundTimerTask = null;
            }
            if (serverInfoTimerTask != null) {
                serverInfoTimerTask.cancel();
                serverInfoTimerTask = null;
            }
            roundTimerTask = Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    if (roundTime[0] > 0) {
                        roundTime[0]--;
                        int hours = roundTime[0] / 3600;
                        int minutes = (roundTime[0] % 3600) / 60;
                        int seconds = roundTime[0] % 60;
                        String timeDisplay = """
                                Round timer: %02d:%02d:%02d
                                """.formatted(hours, minutes, seconds);
                        Groups.player.each(player -> {
                            if (player.team() == Team.all[1]) player.team(Team.all[0]);
                            Call.setHudText(player.con, timeDisplay);
                        });
                    } else {
                        cancel();
                        roundTimerTask = null;
                        RestartManager.restart();
                    }
                }
            }, 0, 1f);

            serverInfoTimerTask = Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    int hours = roundTime[0] / 3600;
                    int minutes = (roundTime[0] % 3600) / 60;
                    String timeString = String.format("%02d:%02d", hours, minutes);
                    Administration.Config.desc.set(Resources.custommotd + timeString);
                }
            }, 0f, 60f);
        });
    }

    public void registerClientCommands(CommandHandler handler){
        /// Player commands
        handler.<Player>register("spectate", "sss", (args, player) -> {
            if (Resources.isLeader(player)) Resources.destroyTeam(player);
            else {
                player.team(Team.all[0]);
                if (player.unit() != null) player.unit().kill();
                Resources.team_members.remove(player.uuid());
            }
        });

        handler.<Player>register("restart", "start a voting for restart ot vote for restart", (args, player) -> {
            if(Groups.player.size() == 1) RestartManager.restart();
            else RestartManager.AddVotes(player);
        });

        /// Team manage commands

        handler.<Player>register("team", "open a team manage menu", (args, player) -> menuManager.callTeamMenu(player));
        handler.<Player>register("join", "join a team", (args, player) -> {
            if (!Resources.isLeader(player) || player.team() == Team.all[0]) menuManager.callJoinMenu(player);
            else player.sendMessage("You can't join other teams if you are a leader");
        });

        handler.<Player>register("accept", "accept a player", (args, player) -> {
            if (Resources.isLeader(player)) menuManager.callAcceptMenu(player);
        });

        handler.<Player>register("deny", "deny a player", (args, player) -> {
            if (Resources.isLeader(player)) menuManager.callDenyMenu(player);
        });

        /// Admin commands!!!
        /// changeTeam is only for alpha-test
        handler.<Player>register("changeTeam","<Team_Id>", "ADMIN ONLY/Change your team", (args,  player) -> {
            if (player.admin()){
                try {
                    int id = Integer.parseInt(args[0]);
                    player.team(Team.all[id]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[red]No valid team id");
                }
            } else player.sendMessage("[red]Not enough permissions");
        });
    }

    public void registerServerCommands(CommandHandler handler){
        handler.register("restart", "force to restart the game",  (args) -> Events.fire(new EventType.GameOverEvent(Team.all[0])));
    }

    private Team takeNewTeam(Tile tile){
        for (Team team : Team.all){
            if (!team.active() && team.id > 6) {
                tile.setNet(Blocks.coreNucleus, team, 0);
                return team;
            }
        }
        return Team.all[0];
    }

    private void teamStart(Player p, Tile tile){
        Team team =  takeNewTeam(tile);
        p.team(team);
        Resources.team_leaders.put(team, p.uuid());
        Resources.team_members.put(p.uuid(), team);
    }
}