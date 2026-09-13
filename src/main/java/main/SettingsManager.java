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
    public void init(){
        Blocks.coreShard.unitCapModifier = 1;
        Blocks.coreFoundation.unitCapModifier = 2;
        Blocks.coreNucleus.unitCapModifier = 4;
        Blocks.illuminator.consPower.capacity = 0f;
    }

    public void setup(){
        Vars.state.rules.waves = false;
        Vars.state.rules.waveTimer = false;
        Vars.state.rules.pvp = false;
        Vars.state.rules.pvpAutoPause = false;
        Vars.state.rules.canGameOver = false;
        Vars.state.rules.logicUnitBuild = true;
        Vars.state.rules.reactorExplosions = true;
        Vars.state.rules.coreCapture = true;
        Vars.state.rules.defaultTeam = Team.all[0];
        Vars.state.rules.unitCap = 8;
        Vars.state.rules.planet = Planets.sun;
        Vars.state.rules.unitBuildSpeedMultiplier = 0.33f;
        Vars.state.rules.infiniteResources = true;
        Call.setRules(Vars.state.rules);
        Time.run(2f, () -> Groups.build.each(b -> b instanceof CoreBlock.CoreBuild, b -> b.tile.removeNet()));
    }
}
