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
    private final MobBattleApi api = new MobBattleApi();

    public MobBattlePlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    /** Public API for trusted plugins declaring a dependency on Dackoro:Mob Battle. */
    public static MobBattleApi getApi() {
        return instance != null ? instance.api : null;
    }

    /** Asset pack key of this mod ("Dackoro:Mob Battle"), used for runtime team effects. */
    public static String packKey() {
        return instance != null ? instance.getIdentifier().toString() : "Dackoro:Mob Battle";
    }

    /** Writes to the server log (Saves/&lt;world&gt;/logs), and to the debug recorder when it is on. */
    public static void log(String message) {
        if (instance != null) {
            instance.getLogger().at(Level.INFO).log(message);
        }
        DebugRecorder.add(message);
    }

    /**
     * Battle decisions, prefixed "[debug]": in the server log with debugLog on, in the debug recorder with
     * debugRecord on. Builds nothing when both are off.
     */
    public static void debug(String message) {
        if (FxSettings.debug) log("[debug] " + message);
        else DebugRecorder.add("[debug] " + message);
    }

    /** True when [debug] lines go anywhere (debugLog or debugRecord): guard costly messages in hot paths with it. */
    public static boolean debugging() {
        return FxSettings.debug || FxSettings.debugRecord;
    }

    @Override
    protected void setup() {
        Teams.count(); // fills the default team slots
        Perms.register();
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
                .register(MorphBurstInteraction.TYPE, MorphBurstInteraction.class, MorphBurstInteraction.CODEC)
                .register(MorphShuffleInteraction.TYPE, MorphShuffleInteraction.class, MorphShuffleInteraction.CODEC)
                .register(CleanseAreaInteraction.TYPE, CleanseAreaInteraction.class, CleanseAreaInteraction.CODEC)
                .register(EffectAreaInteraction.TYPE, EffectAreaInteraction.class, EffectAreaInteraction.CODEC)
                .register(SkipCameraInteraction.TYPE, SkipCameraInteraction.class, SkipCameraInteraction.CODEC)
                .register(HealAreaInteraction.TYPE, HealAreaInteraction.class, HealAreaInteraction.CODEC)
                .register(ArmyToggleInteraction.TYPE, ArmyToggleInteraction.class, ArmyToggleInteraction.CODEC)
                .register(ArmyDisbandInteraction.TYPE, ArmyDisbandInteraction.class, ArmyDisbandInteraction.CODEC)
                .register(WatchInteraction.Prev.TYPE, WatchInteraction.Prev.class, WatchInteraction.Prev.CODEC)
                .register(WatchInteraction.Next.TYPE, WatchInteraction.Next.class, WatchInteraction.Next.CODEC)
                .register(WatchInteraction.Exit.TYPE, WatchInteraction.Exit.class, WatchInteraction.Exit.CODEC)
                .register(DebugInspectInteraction.TYPE, DebugInspectInteraction.class, DebugInspectInteraction.CODEC)
                .register(DebugTrackInteraction.TYPE, DebugTrackInteraction.class, DebugTrackInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new FightDamageSystem());
        getEntityStoreRegistry().registerSystem(new ArmyPlayerDamageSystem());
        getEntityStoreRegistry().registerSystem(new PlayerBattleDamageSystem());
        getEntityStoreRegistry().registerSystem(new BuffDamageSystem());
        getEntityStoreRegistry().registerSystem(new DebugHitSystem());
        getEntityStoreRegistry().registerSystem(new PlayerDeathSystem());
        getEntityStoreRegistry().registerSystem(new KillTracker());
        getEntityStoreRegistry().registerSystem(new BattleUnloadSystem());
        getEntityStoreRegistry().registerSystem(new FightTickSystem());
        getEntityStoreRegistry().registerSystem(new BattleLifecycleSystem());
        getEntityStoreRegistry().registerSystem(new WandEquipSoundSystem());
        getEntityStoreRegistry().registerSystem(new LookLoadSystem());
        getEntityStoreRegistry().registerSystem(new LookDeathSystem());
        getEntityStoreRegistry().registerSystem(new LookGuardSystem());
        getEntityStoreRegistry().registerSystem(new SkinRespawnSystem());
        getEntityStoreRegistry().registerSystem(new BuffJump());
        FxSettings.load(getDataDirectory());
        getCommandRegistry().registerCommand(new MobBattleCommand());
        listenAssetReloads();
        // One chat hint per player and server session, only in Creative (the mod is a creative tool).
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                event -> {
                    // Left while watching a battle: back from our saved spectator game mode.
                    BattleWatch.onReady(event.getPlayerRef());
                    // Came back with a size buff's leftovers (scale and health modifier are saved, our state isn't).
                    Buffs.onPlayerReady(event.getPlayerRef());
                    var player = event.getPlayer();
                    if (player == null || player.getGameMode() != com.hypixel.hytale.protocol.GameMode.Creative) {
                        return;
                    }
                    var entity = event.getPlayerRef();
                    var ref = entity != null && entity.isValid()
                            ? entity.getStore().getComponent(entity, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType())
                            : null;
                    if (ref != null && WELCOMED.add(ref.getUuid())) {
                        ref.sendMessage(I18n.tr("chat.plugin.welcome")
                                .color("#ffd23e"));
                    }
                });

        getLogger().at(Level.INFO).log("Mob Battle loaded (interactions: "
                + SelectFighterInteraction.TYPE + ", " + ClearSelectionInteraction.TYPE + ")");
    }

    /** Models or effects (re)loaded or removed: the settings panel's lists of them are built again (PickLists). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void listenAssetReloads() {
        getEventRegistry().registerGlobal((Class) com.hypixel.hytale.assetstore.event.LoadedAssetsEvent.class,
                (java.util.function.Consumer) e -> PickLists.assetsChanged(((com.hypixel.hytale.assetstore.event.LoadedAssetsEvent) e).getAssetClass()));
        getEventRegistry().registerGlobal((Class) com.hypixel.hytale.assetstore.event.RemovedAssetsEvent.class,
                (java.util.function.Consumer) e -> PickLists.assetsChanged(((com.hypixel.hytale.assetstore.event.RemovedAssetsEvent) e).getAssetClass()));
    }

    @Override
    protected void shutdown() {
        // Player-side state first (spectator mode, cameras, forced music, HUDs); onReady covers anyone missed.
        BattleWatch.exitAll();
        MeasureTool.stopAll();
        TestTools.stopAll();
        FightCamera.stopAll();
        BattleMusic.stopAll();
        DuelCard.clearAll();
        FocusCard.clearAll();
        MobBattleApi.shutdown();
        super.shutdown();
        WELCOMED.clear();
        instance = null;
    }
}
