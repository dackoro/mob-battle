package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Mob Battle: a wand that makes two selected NPCs fight each other.
 * Left click selects A, left click on B starts the fight, right click clears the selection.
 */
public class MobBattlePlugin extends JavaPlugin {

    private static MobBattlePlugin instance;

    public MobBattlePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    /** Asset pack key of this mod ("Dackoro:Mob Battle"), used for runtime team effects. */
    public static String packKey() {
        return instance != null ? instance.getIdentifier().toString() : "Dackoro:Mob Battle";
    }

    /** Writes to the server log (Saves/&lt;world&gt;/logs). */
    public static void log(String message) {
        if (instance != null) {
            instance.getLogger().at(Level.INFO).log(message);
        }
    }

    @Override
    protected void setup() {
        Teams.count(); // fills the default team slots
        getCodecRegistry(Interaction.CODEC)
                .register(SelectFighterInteraction.TYPE, SelectFighterInteraction.class, SelectFighterInteraction.CODEC)
                .register(ClearSelectionInteraction.TYPE, ClearSelectionInteraction.class, ClearSelectionInteraction.CODEC)
                .register(HealInteraction.TYPE, HealInteraction.class, HealInteraction.CODEC)
                .register(KillInteraction.TYPE, KillInteraction.class, KillInteraction.CODEC)
                .register(ChaosFocusInteraction.TYPE, ChaosFocusInteraction.class, ChaosFocusInteraction.CODEC)
                .register(ChaosBrawlInteraction.TYPE, ChaosBrawlInteraction.class, ChaosBrawlInteraction.CODEC)
                .register(TeamSelectInteraction.TYPE, TeamSelectInteraction.class, TeamSelectInteraction.CODEC)
                .register(TeamActionInteraction.TYPE, TeamActionInteraction.class, TeamActionInteraction.CODEC)
                .register(CleanseInteraction.TYPE, CleanseInteraction.class, CleanseInteraction.CODEC)
                .register(EffectGiveInteraction.TYPE, EffectGiveInteraction.class, EffectGiveInteraction.CODEC)
                .register(EffectCycleInteraction.TYPE, EffectCycleInteraction.class, EffectCycleInteraction.CODEC)
                .register(SummonToggleInteraction.TYPE, SummonToggleInteraction.class, SummonToggleInteraction.CODEC)
                .register(SummonSpawnInteraction.TYPE, SummonSpawnInteraction.class, SummonSpawnInteraction.CODEC)
                .register(MorphCopyInteraction.TYPE, MorphCopyInteraction.class, MorphCopyInteraction.CODEC)
                .register(MorphApplyInteraction.TYPE, MorphApplyInteraction.class, MorphApplyInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new FightDamageSystem());
        getEntityStoreRegistry().registerSystem(new FightTickSystem());
        getEntityStoreRegistry().registerSystem(new BattleLifecycleSystem());
        FxSettings.load(getDataDirectory());
        getCommandRegistry().registerCommand(new MobBattleCommand());

        getLogger().at(Level.INFO).log("Mob Battle loaded (interactions: "
                + SelectFighterInteraction.TYPE + ", " + ClearSelectionInteraction.TYPE + ")");
    }
}
