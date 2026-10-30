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

    /**
     * Hands a repeating task to the plugin's task registry, so a /plugin unload cancels it instead of leaving it to run
     * against classes of an unloaded plugin.
     */
    @SuppressWarnings("unchecked")
    static <T extends java.util.concurrent.ScheduledFuture<?>> T track(T task) {
        if (instance != null && task != null) {
            try {
                TRACKED.put(task, instance.getTaskRegistry().registerTask((java.util.concurrent.ScheduledFuture<Void>) task));
            } catch (Exception e) {
                log("Task not registered: " + e);
            }
        }
        return task;
    }

    /**
     * Cancels a {@link #track}ed task and takes it out of the registry (audit 2026-10-29: the registry keeps every
     * registration until unregister, so a measure's lambda, holding its world's Store and Refs, stayed for good).
     */
    static void untrack(java.util.concurrent.Future<?> task) {
        if (task == null) return;
        task.cancel(false);
        var reg = TRACKED.remove(task);
        if (reg != null) {
            try {
                reg.unregister();
            } catch (Exception ignored) {
                // already gone with the plugin
            }
        }
    }

    private static final java.util.Map<java.util.concurrent.Future<?>, com.hypixel.hytale.server.core.task.TaskRegistration> TRACKED =
            new java.util.concurrent.ConcurrentHashMap<>();

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

    /** Time since {@code t0} (System.nanoTime) for log lines: "850 ms", or seconds past 1000 ms ("34.0 s"). */
    public static String took(long t0) {
        return millis((System.nanoTime() - t0) / 1_000_000);
    }

    /** A duration for log lines: "850 ms", or seconds past 1000 ms ("34.0 s"). */
    public static String millis(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    /** A byte count for log lines: "640 B", "12.4 KB", "15.2 MB". */
    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Total size of the files under a folder (0 when it is missing or unreadable). */
    public static long sizeOf(java.nio.file.Path dir) {
        return folderStats(dir)[0];
    }

    /**
     * A folder for log lines: "12.3 MB on disk, last drawn 2026-10-17 01:26" (size of every file under it and the
     * newest file's time, i.e. when anything in it was last generated); "empty" when there is nothing.
     */
    public static String onDisk(java.nio.file.Path dir) {
        long[] st = folderStats(dir);
        if (st[1] == 0) return "empty";
        return size(st[0]) + " on disk, last drawn " + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(st[1]), java.time.ZoneId.systemDefault()));
    }

    /** Time (ms) of the newest file under a folder, 0 when there is none. */
    public static long newestFile(java.nio.file.Path dir) {
        return folderStats(dir)[1];
    }

    /** {total bytes, newest file time in ms (0 = no file)} of the files under a folder. */
    private static long[] folderStats(java.nio.file.Path dir) {
        long[] st = new long[2];
        if (dir == null || !java.nio.file.Files.isDirectory(dir)) return st;
        try (var files = java.nio.file.Files.walk(dir)) {
            files.filter(java.nio.file.Files::isRegularFile).forEach(f -> {
                java.io.File file = f.toFile();
                st[0] += file.length();
                st[1] = Math.max(st[1], file.lastModified());
            });
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return new long[2];
        }
        return st;
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
                .register(EquipWand.EditInteraction.TYPE, EquipWand.EditInteraction.class, EquipWand.EditInteraction.CODEC)
                .register(EquipWand.PasteInteraction.TYPE, EquipWand.PasteInteraction.class, EquipWand.PasteInteraction.CODEC)
                .register(EquipWand.AreaInteraction.TYPE, EquipWand.AreaInteraction.class, EquipWand.AreaInteraction.CODEC)
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
                .register(KillAreaInteraction.TYPE, KillAreaInteraction.class, KillAreaInteraction.CODEC)
                .register(ArmyToggleInteraction.TYPE, ArmyToggleInteraction.class, ArmyToggleInteraction.CODEC)
                .register(ArmyDisbandInteraction.TYPE, ArmyDisbandInteraction.class, ArmyDisbandInteraction.CODEC)
                .register(WatchInteraction.Prev.TYPE, WatchInteraction.Prev.class, WatchInteraction.Prev.CODEC)
                .register(WatchInteraction.Next.TYPE, WatchInteraction.Next.class, WatchInteraction.Next.CODEC)
                .register(WatchInteraction.Exit.TYPE, WatchInteraction.Exit.class, WatchInteraction.Exit.CODEC)
                .register(WatchInteraction.Auto.TYPE, WatchInteraction.Auto.class, WatchInteraction.Auto.CODEC)
                .register(FilmMode.Exit.TYPE, FilmMode.Exit.class, FilmMode.Exit.CODEC)
                .register(DebugInspectInteraction.TYPE, DebugInspectInteraction.class, DebugInspectInteraction.CODEC)
                .register(DebugTrackInteraction.TYPE, DebugTrackInteraction.class, DebugTrackInteraction.CODEC);
        getEntityStoreRegistry().registerSystem(new FightDamageSystem());
        getEntityStoreRegistry().registerSystem(new ArmyPlayerDamageSystem());
        getEntityStoreRegistry().registerSystem(new PlayerBattleDamageSystem());
        getEntityStoreRegistry().registerSystem(new BuffDamageSystem());
        getEntityStoreRegistry().registerSystem(new WeaponDamageSystem());
        getEntityStoreRegistry().registerSystem(new DebugHitSystem());
        getEntityStoreRegistry().registerSystem(new ArrowDebugSystem());
        getEntityStoreRegistry().registerSystem(new PlayerDeathSystem());
        getEntityStoreRegistry().registerSystem(new KillTracker());
        getEntityStoreRegistry().registerSystem(new BattleUnloadSystem());
        getEntityStoreRegistry().registerSystem(new FightTickSystem());
        getEntityStoreRegistry().registerSystem(new BattleLifecycleSystem());
        getEntityStoreRegistry().registerSystem(new WandEquipSoundSystem());
        getEntityStoreRegistry().registerSystem(new LookLoadSystem());
        getEntityStoreRegistry().registerSystem(new LookDeathSystem());
        getEntityStoreRegistry().registerSystem(new WatchDetachSystem());
        getEntityStoreRegistry().registerSystem(new LookGuardSystem());
        getEntityStoreRegistry().registerSystem(new SkinRespawnSystem());
        getEntityStoreRegistry().registerSystem(new WatcherHideSystem());
        getEntityStoreRegistry().registerSystem(new BuffJump());
        FxSettings.load(getDataDirectory());
        // Armies saved before the last stop: each mob rejoins as its chunk loads.
        Army.loadSaved(getDataDirectory());
        // Mob runes and kit stats saved before the last stop (or a crashed world): back as each mob loads.
        MobExtras.loadSaved(getDataDirectory());
        getCommandRegistry().registerCommand(new MobBattleCommand());
        listenAssetReloads();
        // Model thumbnails (2026-10-16): once the server has booted, before players join; missing or outdated ones
        // only, on ThumbnailCache's own background thread.
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.BootEvent.class,
                event -> {
                    try {
                        FxSettings.reloadKits();
                    } catch (RuntimeException e) {   // never stops the pictures below (audit 2026-10-29)
                        log("Kits not reloaded at boot: " + e);
                    }
                    ThumbnailCache.startup();
                });
        // from the first client that starts connecting, no more runtime assets this session (ThumbnailCache.mayRegister)
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerSetupConnectEvent.class,
                event -> ThumbnailCache.clientConnecting());
        // One chat hint per player and server session, only in Creative (the mod is a creative tool).
        // the watch's glide and fall hold turn with the mouse (BattleWatch.onMouse)
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent.class,
                BattleWatch::onMouse);
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class,
                event -> {
                    // Left while watching a battle: back from our saved spectator game mode.
                    BattleWatch.onReady(event.getPlayerRef());
                    // The same for a battle film (its own game mode, so Q leaves it).
                    FilmMode.onReady(event.getPlayerRef());
                    // Came back with a size buff's leftovers (scale and health modifier are saved, our state isn't).
                    Buffs.onPlayerReady(event.getPlayerRef());
                    // Their own skin in the VS screen: saved, drawn and registered in the background when new.
                    VsScreen.onPlayerReady(event.getPlayerRef());
                    // Welcome once per server run, in any game mode (a new world starts in Adventure).
                    var entity = event.getPlayerRef();
                    var ref = entity != null && entity.isValid()
                            ? entity.getStore().getComponent(entity, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType())
                            : null;
                    if (ref != null && WELCOMED.add(ref.getUuid())) {
                        // Two lines (2026-10-30, playtest): config and help first, then the film and watch tip.
                        ref.sendMessage(I18n.tr("chat.plugin.welcome")
                                .color("#ffd23e"));
                        ref.sendMessage(I18n.tr("chat.plugin.welcome.watch")
                                .color("#c8d4e0"));
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
        FilmMode.exitAll();
        MeasureTool.stopAll();
        TestTools.stopAll();
        FightCamera.stopAll();
        BattleMusic.stopAll();
        SlowMo.stopAll();
        DuelCard.clearAll();
        FocusCard.clearAll();
        MobBattleApi.shutdown();
        ThumbnailCache.stop();
        MorphHologram.clear();
        super.shutdown();
        WELCOMED.clear();
        instance = null;
    }
}
