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

    /** Players who already got the /mobbattle help hint this server session. */
    private static final java.util.Set<java.util.UUID> WELCOMED = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /** /mobbattle debug on: detailed battle decisions in the server log, prefixed "[debug]". */
    public static void debug(String message) {
        if (FxSettings.debug) log("[debug] " + message);
    }

    @Override
    protected void setup() {
        Teams.count(); // fills the default team slots
        getCodecRegistry(Interaction.CODEC)
                .register(SelectFighterInteraction.TYPE, SelectFighterInteraction.class, SelectFighterInteraction.CODEC)
                .register(ClearSelectionInteraction.TYPE, ClearSelectionInteraction.class, ClearSelectionInteraction.CODEC)
                .register(HealInteraction.TYPE, HealInteraction.class, HealInteraction.CODEC)
                .register(SnapInteraction.TYPE, SnapInteraction.class, SnapInteraction.CODEC)
                .register(KillInteraction.TYPE, KillInteraction.class, KillInteraction.CODEC)
                .register(ChaosFocusInteraction.TYPE, ChaosFocusInteraction.class, ChaosFocusInteraction.CODEC)
                .register(ChaosBrawlInteraction.TYPE, ChaosBrawlInteraction.class, ChaosBrawlInteraction.CODEC)
                .register(TeamSelectInteraction.TYPE, TeamSelectInteraction.class, TeamSelectInteraction.CODEC)
                .register(TeamActionInteraction.TYPE, TeamActionInteraction.class, TeamActionInteraction.CODEC)
                .register(CleanseInteraction.TYPE, CleanseInteraction.class, CleanseInteraction.CODEC)
                .register(BuffGiveInteraction.TYPE, BuffGiveInteraction.class, BuffGiveInteraction.CODEC)
                .register(BuffTakeInteraction.TYPE, BuffTakeInteraction.class, BuffTakeInteraction.CODEC)
                .register(EffectGiveInteraction.TYPE, EffectGiveInteraction.class, EffectGiveInteraction.CODEC)
                .register(EffectCycleInteraction.TYPE, EffectCycleInteraction.class, EffectCycleInteraction.CODEC)
                .register(SummonToggleInteraction.TYPE, SummonToggleInteraction.class, SummonToggleInteraction.CODEC)
                .register(SummonSpawnInteraction.TYPE, SummonSpawnInteraction.class, SummonSpawnInteraction.CODEC)
                .register(MorphCopyInteraction.TYPE, MorphCopyInteraction.class, MorphCopyInteraction.CODEC)
                .register(MorphApplyInteraction.TYPE, MorphApplyInteraction.class, MorphApplyInteraction.CODEC)
                .register(ArmyToggleInteraction.TYPE, ArmyToggleInteraction.class, ArmyToggleInteraction.CODEC)
                .register(ArmyDisbandInteraction.TYPE, ArmyDisbandInteraction.class, ArmyDisbandInteraction.CODEC)
                .register(WatchInteraction.Prev.TYPE, WatchInteraction.Prev.class, WatchInteraction.Prev.CODEC)
                .register(WatchInteraction.Next.TYPE, WatchInteraction.Next.class, WatchInteraction.Next.CODEC)
                .register(WatchInteraction.Exit.TYPE, WatchInteraction.Exit.class, WatchInteraction.Exit.CODEC);
        getEntityStoreRegistry().registerSystem(new FightDamageSystem());
        getEntityStoreRegistry().registerSystem(new ArmyPlayerDamageSystem());
        getEntityStoreRegistry().registerSystem(new PlayerBattleDamageSystem());
        getEntityStoreRegistry().registerSystem(new BuffDamageSystem());
        getEntityStoreRegistry().registerSystem(new PlayerDeathSystem());
        getEntityStoreRegistry().registerSystem(new KillTracker());
        getEntityStoreRegistry().registerSystem(new BattleUnloadSystem());
        getEntityStoreRegistry().registerSystem(new FightTickSystem());
        getEntityStoreRegistry().registerSystem(new BattleLifecycleSystem());
        FxSettings.load(getDataDirectory());
        getCommandRegistry().registerCommand(new MobBattleCommand());
        // One chat hint per player and server session, only in Creative (the mod is a creative tool).
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                event -> {
                    var player = event.getPlayer();
                    if (player == null || player.getGameMode() != com.hypixel.hytale.protocol.GameMode.Creative) {
                        return;
                    }
                    var entity = event.getPlayerRef();
                    var ref = entity != null && entity.isValid()
                            ? entity.getStore().getComponent(entity, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType())
                            : null;
                    if (ref != null && WELCOMED.add(ref.getUuid())) {
                        ref.sendMessage(com.hypixel.hytale.server.core.Message.raw(
                                "Dackoro's Mob Battle: /mobbattle help explains the wands and commands. /mobbattle give gets all ten wands. "
                                + "Besides help, everything is configurable: effects, timers, results and more (see /mobbattle settings).")
                                .color("#ffd23e"));
                    }
                });

        getLogger().at(Level.INFO).log("Mob Battle loaded (interactions: "
                + SelectFighterInteraction.TYPE + ", " + ClearSelectionInteraction.TYPE + ")");
    }
}
