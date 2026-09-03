package main;

import arc.Events;
import arc.util.CommandHandler;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.net.Administration;


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
            if (Resources.team_info.containsKey(event.player.uuid())){
                Team prTeam = Resources.team_info.get(event.player.uuid());
                if (prTeam != null && prTeam != Team.all[0]){
                    event.player.team(prTeam);
                }
                else{
                    event.player.team(Team.all[0]);
                }
            }
            else{
                event.player.team(Team.all[0]);
            }
        });

        Events.on(EventType.PlayerLeave.class, event -> {
           Resources.team_info.put(event.player.uuid(), event.player.team());
        });

        /// Duplicating here some stuff because it has to be done every restart(can be done in worldLoadEvent)
        Events.on(EventType.GameOverEvent.class, event -> {
           Groups.player.each(p -> p.team(Team.all[0]));
           Resources.team_info.clear();
        });
    }

    public void registerClientCommands(CommandHandler handler){
        /// Player commands
        handler.<Player>register("start", "Create a team and ", (args,  player) -> {

        });

        /// Team manage commands
        handler.<Player>register("team", "Manage team", (args, player) -> {
            menuManager.callTeamMenu(player);
        });

        /// Admin commands!!!
        handler.<Player>register("cteam","<Team_Id>", "ADMIN ONLY/Change your team", (args,  player) -> {
            if (player.admin()){
                try {
                    int id = Integer.parseInt(args[0]);
                    player.team(Team.all[id]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[red]No valid team id");
                }
            } else{
                player.sendMessage("[red]Not enough permissions");
            }
        });
    }

    public void registerServerCommands(CommandHandler handler){
        handler.register("restart", "force to restart the game",  (args) -> {
            Events.fire(new EventType.GameOverEvent(Team.all[0]));
        });
    }
}
