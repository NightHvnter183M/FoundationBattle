package main;

import arc.Events;
import arc.util.CommandHandler;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.world.Tile;


public class Main extends Plugin{
    private MenuManager menuManager;
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

        /// Duplicating here some stuff because it has to be done every restart also (done in worldLoadEvent)
        Events.on(EventType.GameOverEvent.class, event -> {
           Groups.player.each(p -> p.team(Team.all[0]));
           Resources.team_leaders.clear();
           Resources.team_members.clear();
        });

        Events.on(EventType.WorldLoadEndEvent.class, event -> {
            Groups.player.each(p -> p.team(Team.all[0]));
            Resources.team_leaders.clear();
            Resources.team_members.clear();
        });
    }

    public void registerClientCommands(CommandHandler handler){
        /// Player commands
        handler.<Player>register("start", "Create a team and ", (args,  player) -> {
            if (player.team() == Team.all[0]) teamStart(player);
        });

        handler.<Player>register("spectate", "sss", (args, player) -> {
            if (Resources.isLeader(player)) Resources.destroyTeam(player);
            else {
                player.team(Team.all[0]);
                Resources.team_members.remove(player.uuid());
            }
        });

        /// Team manage commands
        handler.<Player>register("join", "join a team", (args, player) -> menuManager.callJoinMenu(player));

        handler.<Player>register("accept", "accept a player", (args, player) -> menuManager.callAcceptMenu(player));

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

    private Team takeNewTeam(){
        for (Team team : Team.all){
            if (!team.active() && team.id > 6) {
                Tile tile = new Tile(100, 100);
                tile.setNet(Blocks.coreNucleus, team, 0);
                return team;
            }
        }
        return Team.all[0];
    }

    private void teamStart(Player p){
        Team team =  takeNewTeam();
        p.team(team);
        Resources.team_leaders.put(team, p.uuid());
        Resources.team_members.put(p.uuid(), team);
    }
}
