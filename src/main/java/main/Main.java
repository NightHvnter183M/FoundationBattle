package main;

import arc.Core;
import arc.Events;
import arc.util.CommandHandler;
import arc.util.Strings;
import arc.util.Time;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.mod.Plugin;
import mindustry.net.Administration;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static main.Resources.*;

public class Main extends Plugin {
    private final MenuManager menus = new MenuManager();
    private final SettingsManager settings = new SettingsManager();
    private Timer.Task roundTask;
    private int roundLeft;

    @Override
    public void init() {
        Administration.Config.serverName.set("[#5F9EA0]Foundation Battle");
        settings.init();
        Borders.init();

        // reconnecting players land straight in their team, everyone else spectates
        Vars.netServer.assigner = (player, players) -> assign(player);
        Vars.netServer.admins.addActionFilter(this::allowAction);

        Events.on(EventType.PlayerJoin.class, e -> onJoin(e.player));
        Events.on(EventType.PlayerLeave.class, e -> {
            Resources.onLeave(e.player);
            SnapshotMenu.forget(e.player.uuid());
            RestartManager.onLeave();
        });
        Events.on(EventType.TapEvent.class, e -> onTap(e.player, e.tile));
        Events.on(EventType.BlockBuildEndEvent.class, this::onBuildEnd);
        Events.on(EventType.PayloadDropEvent.class, e -> {
            // drops without a player (logic, RTS orders) skip the action filter: the block is already placed, take it back
            if (e.build != null && !insideTerritory(e.build.team, e.build.tile)) {
                Core.app.post(() -> {
                    if (e.build.isValid()) e.build.tile.removeNet();
                });
            }
        });
        Events.on(EventType.CoreChangeEvent.class, e -> Core.app.post(() -> checkEliminations(e.core)));
        Events.on(EventType.WorldLoadEndEvent.class, e -> startRound());
        Events.on(EventType.PlayEvent.class, e -> settings.setup());
    }

    private boolean allowAction(Administration.PlayerAction action) {
        Player p = action.player;
        if (p == null) return true;
        Team team = p.team();

        if (action.type == Administration.ActionType.placeBlock) {
            if (action.block == Blocks.air || insideTerritory(team, action.tile)) return true;
            if (team.cores().isEmpty()) return false;
            if (action.block == Blocks.vault) {
                CoreBuild enemy = nearestCore(action.tile, team);
                float dist = enemy == null ? Float.MAX_VALUE : tilesBetween(action.tile, enemy.tile);
                return dist >= VAULT_MIN_ENEMY_DISTANCE;
            }
            return false;
        }

        if (action.type == Administration.ActionType.dropPayload && action.payload instanceof BuildPayload) {
            if (insideTerritory(team, action.tile)) return true;
            hint(p, "payload", "[scarlet]You can drop blocks only inside your territory.");
            return false;
        }
        return true;
    }

    private void onJoin(Player p) {
        Resources.onJoin(p);
        if (welcomed.add(p.uuid())) menus.showWelcome(p);
        else if (isSpectator(p)) p.sendMessage("[lightgray]Tap free ground to found a team, or use /join.");
        RestartManager.onJoin(p); // shown last, so the map vote ends up on top
    }

    private void onTap(Player p, Tile tile) {
        if (p == null || tile == null || !isSpectator(p) || RestartManager.isEnding()) return;
        if (!ready(p, "tap", 1000)) return;
        String blocker = foundBlocker(tile);
        if (blocker != null) {
            hint(p, "found", "[scarlet]" + blocker);
            return;
        }
        menus.showFound(p, tile);
    }

    private void onBuildEnd(EventType.BlockBuildEndEvent e) {
        if (e.breaking || e.tile.block() != Blocks.vault) return;
        Tile tile = e.tile;
        Team team = e.team;
        Player builder = e.unit == null ? null : e.unit.getPlayer();
        CoreBuild enemy = nearestCore(tile, team);
        if (enemy != null && tilesBetween(tile, enemy.tile) < VAULT_MIN_ENEMY_DISTANCE) {
            if (builder != null) hint(builder, "vault", "[lightgray]This vault is too close to an enemy core to become a core.");
            return;
        }
        Time.run(1f, () -> {
            // it may have been destroyed or replaced in the meantime
            if (tile.block() != Blocks.vault || tile.team() != team) return;
            tile.setNet(Blocks.coreShard, team, 0);
        });
    }

    private void startRound() {
        resetRound();
        RestartManager.reset();
        SnapshotMenu.forgetAll();
        roundLeft = ROUND_SECONDS;
        updateDescription();
        if (roundTask != null) roundTask.cancel();
        roundTask = Timer.schedule(this::tick, 1f, 1f);
    }

    private void tick() {
        if (roundLeft > 0) roundLeft--;
        // does nothing once the round is already ending
        if (roundLeft == 0) RestartManager.startMapVote("[accent]Time is up!");
        if (roundLeft % 60 == 0) updateDescription();

        String clock = Vars.state.gameOver ? "[accent]Round over - loading the next map"
            : String.format("Round timer: %02d:%02d:%02d", roundLeft / 3600, roundLeft % 3600 / 60, roundLeft % 60);
        for (Player p : online()) Call.setHudText(p.con, hud(p, clock));
    }

    private void updateDescription() {
        Administration.Config.desc.set(custommotd + String.format("%02d:%02d", roundLeft / 3600, roundLeft % 3600 / 60));
    }

    private String hud(Player p, String clock) {
        StringBuilder out = new StringBuilder(clock);
        Team team = p.team();
        if (isTracked(team)) {
            out.append('\n').append(teamName(team))
                .append("[lightgray]  |  online ").append(onlineCount(team))
                .append("  |  cores ").append(team.cores().size);
            if (isLeader(p)) {
                int requests = requesters(team).size;
                if (requests > 0) {
                    out.append("\n[accent]").append(requests).append(requests == 1 ? " join request" : " join requests").append(" - /accept");
                }
            }
        } else if (isSpectator(p) && !RestartManager.isEnding()) {
            out.append("\n[lightgray]Spectating - tap free ground to found a team, or /join");
        }
        return out.toString();
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        handler.<Player>register("team", "Open the team menu", (args, p) -> menus.showTeam(p));
        handler.<Player>register("join", "Ask to join a team", (args, p) -> menus.showJoin(p));
        handler.<Player>register("accept", "Accept a join request", (args, p) -> menus.showRequests(p, MenuManager.RequestMode.ACCEPT));
        handler.<Player>register("deny", "Deny a join request", (args, p) -> menus.showRequests(p, MenuManager.RequestMode.DENY));
        handler.<Player>register("spectate", "Leave your team and spectate", (args, p) -> {
            if (isSpectator(p)) p.sendMessage("[lightgray]You are already spectating.");
            else if (isLeader(p)) menus.confirmLeave(p);
            else leaveTeam(p);
        });
        handler.<Player>register("restart", "Vote to end the round early", (args, p) -> RestartManager.vote(p));
        handler.<Player>register("info", "How to play on this server", (args, p) -> menus.showInfo(p));

        // admin only, for the alpha test
        handler.<Player>register("changeTeam", "<team_id>", "ADMIN ONLY: change your team", (args, p) -> {
            if (!p.admin()) {
                p.sendMessage("[red]Not enough permissions");
                return;
            }
            int id = Strings.parseInt(args[0], -1);
            if (id < 0 || id >= Team.all.length) {
                p.sendMessage("[red]Team id must be between 0 and " + (Team.all.length - 1));
                return;
            }
            if (isLeader(p)) {
                p.sendMessage("[red]Hand over or leave your team first.");
                return;
            }
            Team team = Team.all[id];
            teamMembers.remove(p.uuid());
            if (isTracked(team)) teamMembers.put(p.uuid(), team);
            moveToTeam(p, team);
        });
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("restart", "Force the round to end now", args -> Events.fire(new EventType.GameOverEvent(Team.derelict)));
    }

}
