package main;

import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.world.blocks.storage.CoreBlock;

public class SettingsManager {
    public void init() {
        // Server-side only: clients compute the cap with vanilla values, so their UI shows a higher limit.
        Blocks.coreShard.unitCapModifier = 1;
        Blocks.coreFoundation.unitCapModifier = 2;
        Blocks.coreNucleus.unitCapModifier = 4;
    }

    public void setup() {
        Vars.state.rules.waves = false;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.pvp = false;
        Vars.state.rules.pvpAutoPause = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.logicUnitBuild = true;
        Vars.state.rules.reactorExplosions = true;
        Vars.state.rules.coreCapture = true;
        Vars.state.rules.defaultTeam = Team.derelict;
        Vars.state.rules.unitCap = 8;
        Vars.state.rules.planet = Planets.sun;
        Vars.state.rules.unitBuildSpeedMultiplier = 0.33f;
        Vars.state.rules.infiniteResources = true;
        Call.setRules(Vars.state.rules);
        // remove the map's own cores; players found their teams themselves
        Time.run(2f, () -> Groups.build.each(
            b -> b instanceof CoreBlock.CoreBuild && !Resources.isTracked(b.team),
            b -> {
                Borders.queue(b.team); // removing a tile fires no core event, so take its border down explicitly
                b.tile.removeNet();
            }));
    }
}
