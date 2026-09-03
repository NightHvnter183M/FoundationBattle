package main;

import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public class MenuManager {

    public void init(){
        Resources.welcomeMenuId = Menus.registerMenu((player, selection) -> {
           if (selection == 0) {
               return;
           }
           if (selection == 1) {
               Call.openURI(player.con, "https://discord.gg/GMQRKUn8W8");
           }
        });
    }

    public void callWelcomeMenu(Player p){
        Call.menu(p.con, Resources.welcomeMenuId, "FoundationBattle", "Welcome to the open test of Foundation Battle!", new String[][]{
                {"Close"},
                {"Discord"}
        });
    }

    public void callTeamMenu(Player p){

    }
}
