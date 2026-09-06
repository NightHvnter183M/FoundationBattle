package main;

import arc.struct.Seq;
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
               Call.openURI(player.con, "https://discord.gg/xxxxxxx");
           }
        });

        Resources.teamMenuId = Menus.registerMenu((player, selection) -> {
           if (selection == 0) callJoinMenu(player);
           if (selection == 1) callAcceptMenu(player);
           if (selection == 2) callDenyMenu(player);
        });

        Resources.joinMenuId = Menus.registerMenu((player, selection) -> {
            Seq<Player> leaders = Resources.getLeaders(player);
            Player target = leaders.get(selection);
            Resources.join_requests.put(player.uuid(), target.uuid());
            player.sendMessage("You sent a request to" + target.name());
            target.sendMessage(player.name() + " sent a request to join your team");
        });

        Resources.AcceptMenuId = Menus.registerMenu((player, selection) -> {
            Seq<Player> requesters = Resources.getRequesters(player);
            Player found = requesters.get(selection);
            if (found.unit() != null) found.unit().kill();
            Resources.join_requests.remove(found.uuid());
            if (Resources.team_members.containsKey(found.uuid())) Resources.team_members.remove(found.uuid());
            player.sendMessage("You accepted player " + found.name());
            found.sendMessage(player.name() + " accepted your join request");
            found.team(player.team());
            Resources.team_members.put(found.uuid(), found.team());
        });

        Resources.DenyMenuId = Menus.registerMenu((player, selection) -> {
            Seq<Player> requesters = Resources.getRequesters(player);
            Player found = requesters.get(selection);
            Resources.join_requests.remove(found.uuid());
            player.sendMessage("You denied player " + found.name());
            found.sendMessage(player.name() + " denied your join request");
        });

        Resources.mapVoteMenuId = Menus.registerMenu((player, selection) -> Resources.votes.put(selection, Resources.votes.get(selection, 0) + 1));
    }

    public void callWelcomeMenu(Player p){
        Call.menu(p.con, Resources.welcomeMenuId, "FoundationBattle", "Welcome to the open test of Foundation Battle!", new String[][]{
                {"Close"},
                {"Discord"}
        });
    }

    public void callTeamMenu(Player p){
        Call.menu(p.con, Resources.teamMenuId, "FoundationBattle", "Team Menu", new String[][]{
                {"Join"},
                {"Accept"},
                {"Deny"},
                {"Close"}
        });
    }

    public void callJoinMenu(Player p){
        Seq<Player> leaders = Resources.getLeaders(p);
        String[][] buttons = new String[leaders.size][1];
        for (int i = 0; i < leaders.size; i++) buttons[i][0] = leaders.get(i).name;
        Call.menu(p.con, Resources.joinMenuId, "FoundationBattle", "Join Team", buttons);
    }

    public void callAcceptMenu(Player p){
        Seq<Player> requesters = Resources.getRequesters(p);
        if (requesters.isEmpty()) return;
        String[][] buttons = new String[requesters.size][1];
        for (int i = 0; i < requesters.size; i++) buttons[i][0] = requesters.get(i).name;
        Call.menu(p.con, Resources.AcceptMenuId, "FoundationBattle", "Accept Request", buttons);
    }

    public void callDenyMenu(Player p) {
        Seq<Player> requesters = Resources.getRequesters(p);
        if (requesters.isEmpty()) return;
        String[][] buttons = new String[requesters.size][1];
        for (int i = 0; i < requesters.size; i++) buttons[i][0] = requesters.get(i).name;
        Call.menu(p.con, Resources.AcceptMenuId, "FoundationBattle", "Deny Request", buttons);
    }
}
