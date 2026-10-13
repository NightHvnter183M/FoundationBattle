package main;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
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
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;


public class Main extends Plugin {
    private final MenuManager menus = new MenuManager();
    private final SettingsManager settings = new SettingsManager();
    private Timer.Task roundTask;
    private int roundLeft;

    @Override
    public void init() {
        Bundle.init(Resources.PLUGIN_ID);
        Administration.Config.serverName.set("[#5F9EA0]Foundation PvP");
        settings.init();
        Borders.init();

        // reconnecting players land straight in their team, everyone else spectates
        Vars.netServer.assigner = (player, players) -> Resources.assign(player);
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
            if (e.build != null && !Resources.insideTerritory(e.build.team, e.build.tile)) {
                Core.app.post(() -> {
                    if (e.build.isValid()) e.build.tile.removeNet();
                });
            }
        });
        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if (e.tile.build instanceof CoreBuild core) onCoreDestroyed(core);
        });
        // a team that lost its last core is out
        Events.on(EventType.CoreChangeEvent.class, e -> Core.app.post(() -> Resources.checkEliminations(e.core)));
        Events.on(EventType.WorldLoadEndEvent.class, e -> startRound());
        Events.on(EventType.PlayEvent.class, e -> settings.setup());
    }

    private boolean allowAction(Administration.PlayerAction action) {
        Player p = action.player;
        if (p == null) return true;
        Team team = p.team();

        if (action.type == Administration.ActionType.placeBlock) {
            if (action.block == Blocks.air || Resources.insideTerritory(team, action.tile)) return true;
            if (team.cores().isEmpty()) return false;
            if (action.block == Blocks.vault) {
                // outside the territory a vault is allowed only where it will turn into a core
                CoreBuild enemy = Resources.nearestCore(action.tile, team);
                float dist = enemy == null ? Float.MAX_VALUE : Resources.tilesBetween(action.tile, enemy.tile);
                if (dist >= Resources.VAULT_MIN_ENEMY_DISTANCE) return true;
                Resources.hint(p, "vault", Bundle.text("build.vault-too-close", Resources.teamName(enemy.team),
                        Resources.tiles(dist), Resources.tiles(Resources.VAULT_MIN_ENEMY_DISTANCE)));
                return false;
            }
            Resources.hint(p, "radius", Bundle.text("build.outside", Resources.tiles(Resources.BUILD_RADIUS)));
            return false;
        }

        if (action.type == Administration.ActionType.dropPayload && action.payload instanceof BuildPayload) {
            if (Resources.insideTerritory(team, action.tile)) return true;
            Resources.hint(p, "payload", Bundle.text("build.payload-outside"));
            return false;
        }
        return true;
    }

    private void onJoin(Player p) {
        Resources.onJoin(p);
        if (Resources.welcomed.add(p.uuid())) menus.showWelcome(p);
        else if (Resources.isSpectator(p)) Bundle.send(p, "join.hint");
        RestartManager.onJoin(p); // shown last, so the map vote ends up on top
    }

    private void onTap(Player p, Tile tile) {
        if (p == null || tile == null || !Resources.isSpectator(p) || RestartManager.isEnding()) return;
        if (!Resources.ready(p, "tap", 1000)) return;
        Bundle.Text blocker = Resources.foundBlocker(tile);
        if (blocker != null) {
            Resources.hint(p, "found", blocker);
            return;
        }
        menus.showFound(p, tile);
    }

    private void onCoreDestroyed(CoreBuild core) {
        if (!Vars.state.rules.coreCapture) return;
        Team attacker = core.lastDamage;
        if (attacker != null && attacker != Team.derelict && attacker != core.team) return; // a real capture
        Team team = core.team;
        Tile tile = core.tile;
        Block block = core.block;
        core.lastDamage = team;
        Time.run(3f, () -> {
            if (tile.build instanceof CoreBuild back && back.team == team && tile.block() == block) tile.removeNet();
            Borders.queue(team);
            Resources.checkEliminations(null);
        });
    }

    private void onBuildEnd(EventType.BlockBuildEndEvent e) {
        if (e.breaking || e.tile.block() != Blocks.vault) return;
        Tile tile = e.tile;
        Team team = e.team;
        Player builder = e.unit == null ? null : e.unit.getPlayer();
        CoreBuild enemy = Resources.nearestCore(tile, team);
        if (enemy != null && Resources.tilesBetween(tile, enemy.tile) < Resources.VAULT_MIN_ENEMY_DISTANCE) {
            if (builder != null) Resources.hint(builder, "vault", Bundle.text("vault.too-close"));
            return;
        }
        Time.run(1f, () -> {
            // it may have been destroyed or replaced in the meantime
            if (tile.block() != Blocks.vault || tile.team() != team) return;
            tile.setNet(Blocks.coreShard, team, 0);
            if (builder == null) Bundle.sendTeam(team, "core.new");
            else Bundle.sendTeam(team, "core.new-by", builder.name);
        });
    }

    private void startRound() {
        Resources.resetRound();
        RestartManager.reset();
        SnapshotMenu.forgetAll();
        roundLeft = Resources.ROUND_SECONDS;
        updateDescription();
        if (roundTask != null) roundTask.cancel();
        roundTask = Timer.schedule(this::tick, 1f, 1f);
    }

    private void tick() {
        if (roundLeft > 0) roundLeft--;
        // does nothing once the round is already ending
        if (roundLeft == 0) RestartManager.startMapVote("round.time-up");
        if (roundLeft % 60 == 0) updateDescription();

        String clock = String.format("%02d:%02d:%02d", roundLeft / 3600, roundLeft % 3600 / 60, roundLeft % 60);
        for (Player p : Resources.online()) Call.setHudText(p.con, hud(p, clock));
    }

    private void updateDescription() {
        Administration.Config.desc.set(Resources.custommotd + String.format("%02d:%02d", roundLeft / 3600, roundLeft % 3600 / 60));
    }

    private String hud(Player p, String clock) {
        String lang = Bundle.lang(p);
        StringBuilder out = new StringBuilder(Vars.state.gameOver ? Bundle.get(lang, "hud.round-over") : Bundle.get(lang, "hud.timer", clock));
        Team team = p.team();
        if (Resources.isTracked(team)) {
            out.append('\n').append(Bundle.get(lang, "hud.team",
                    Resources.teamName(team), Resources.onlineCount(team), Resources.coreSummary(team)));
            if (Resources.isLeader(p)) {
                int requests = Resources.requesters(team).size;
                if (requests > 0) out.append('\n').append(Bundle.count("hud.requests", requests).in(lang));
            }
        } else if (Resources.isSpectator(p) && !RestartManager.isEnding()) {
            out.append('\n').append(Bundle.get(lang, "hud.spectator"));
        }
        return out.toString();
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        Bundle.init(Resources.PLUGIN_ID);
        Commands.init(handler);
        Commands.add("team", "", (p, args) -> menus.showTeam(p), "teams", "tm");
        Commands.add("join", "", (p, args) -> menus.showJoin(p), "j");
        Commands.add("accept", "", (p, args) -> menus.showRequests(p, MenuManager.RequestMode.ACCEPT), "acc");
        Commands.add("deny", "", (p, args) -> menus.showRequests(p, MenuManager.RequestMode.DENY), "decline", "reject");
        Commands.add("spectate", "", (p, args) -> {
            if (Resources.isSpectator(p)) Bundle.send(p, "spectate.already");
            else if (Resources.isLeader(p)) menus.confirmLeave(p);
            else Resources.leaveTeam(p);
        }, "spec", "leave");
        Commands.add("restart", "", (p, args) -> RestartManager.vote(p), "rtv");
        Commands.add("info", "", (p, args) -> menus.showInfo(p), "rules", "howto", "guide");
        Commands.add("lang", "[language]", this::language, "language", "locale");

        // admin only, for the alpha test
        Commands.admin("changeteam", "<team_id>", (p, args) -> {
            int id = Strings.parseInt(args[0], -1);
            if (id < 0 || id >= Team.all.length) {
                Bundle.send(p, "changeteam.bad-id", Team.all.length - 1);
                return;
            }
            if (Resources.isLeader(p)) {
                Bundle.send(p, "changeteam.leader");
                return;
            }
            Team team = Team.all[id];
            Resources.teamMembers.remove(p.uuid());
            if (Resources.isTracked(team)) Resources.teamMembers.put(p.uuid(), team);
            Resources.moveToTeam(p, team);
        }, "ct");
    }

    private void language(Player p, String[] args) {
        if (args.length == 0) {
            menus.showLanguage(p);
            return;
        }
        String input = args[0];
        if (input.equalsIgnoreCase("auto") || input.equalsIgnoreCase(Commands.toRussianLayout("auto"))) {
            Bundle.pick(p, null);
            Bundle.send(p, "lang.auto-set", Bundle.languageName(Bundle.lang(p)));
            return;
        }
        String lang = Bundle.findLanguage(input);
        if (lang == null) lang = Bundle.findLanguage(Commands.fromRussianLayout(input)); // "кг" typed for "ru"
        if (lang == null) {
            Bundle.send(p, "lang.unknown", input, Bundle.languages().toString(", ") + ", auto");
            return;
        }
        Bundle.pick(p, lang);
        Bundle.send(p, "lang.changed", Bundle.languageName(lang));
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        handler.register("restart", "Force the round to end now", args -> Events.fire(new EventType.GameOverEvent(Team.derelict)));
        handler.register("bundles", "[reload|check|export|default] [language]",
                "Plugin texts: list languages, reload them from disk, check translations, export a template, set the default", this::bundles);
    }

    private void bundles(String[] args) {
        Bundle.init(Resources.PLUGIN_ID);
        String action = args.length > 0 ? args[0] : "";
        switch (action) {
            case "" -> Bundle.summary().each(line -> Log.info(line));
            case "reload" -> {
                Bundle.reload();
                Commands.refreshAliases();
                Bundle.summary().each(line -> Log.info(line));
            }
            case "check" -> {
                Seq<String> problems = Bundle.check().addAll(Commands.problems());
                if (problems.isEmpty()) Log.info("Translations: no problems found.");
                else problems.each(line -> Log.warn(line));
            }
            case "export" -> {
                if (args.length < 2) {
                    Log.err("Usage: bundles export <language>, e.g. bundles export uk");
                    return;
                }
                Fi file = Bundle.export(args[1]);
                Log.info("Template written to @", file.absolutePath());
            }
            case "default" -> {
                String lang = args.length < 2 ? null : Bundle.findLanguage(args[1]);
                if (lang == null) {
                    Log.err("Usage: bundles default <language>. Languages: @", Bundle.languages().toString(", "));
                    return;
                }
                Bundle.setServerDefault(lang);
                Log.info("Players whose game language has no translation now read @.", Bundle.languageName(lang));
            }
            default -> Log.err("Unknown action. Use: bundles, bundles reload, bundles check, bundles export <lang>, bundles default <lang>");
        }
    }

}