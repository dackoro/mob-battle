package dev.dackoro.mobbattle;

import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle music: battle music from the countdown until the battle ends, for the same players who see the
 * titles (/mobbattle titlescope).
 * <p>
 * Same as the game's "/audio music force|clear" (AudioMusicForceCommand / AudioMusicClearCommand): set the
 * player's ForcedMusicTracker container index (0 = no forced music); the audio module sends UpdateForcedMusic.
 */
final class BattleMusic {

    /**
     * Mob Battle's own music container (Server/Audio/MusicContainers/MobBattle/MobBattle_Battle_Music.json, 2026-10-16,
     * user: no battle music, or very quiet): the game's goblin boss battle song, +6 volume and looping until the battle
     * ends. The game's Track_Z1D_Goblin_Boss_Battle has no volume boost and plays once (LoopCount 1, 0.7.2), so a
     * setting still naming it plays this one (OLD_DEFAULT_TRACK).
     */
    public static final String DEFAULT_TRACK = "MobBattle_Battle_Music";
    static final String OLD_DEFAULT_TRACK = "Track_Z1D_Goblin_Boss_Battle";
    /** Battle music tracks shipped with the game (Assets.zip, Server/Audio/MusicContainers). */
    public static final List<String> SUGGESTED = List.of(DEFAULT_TRACK, OLD_DEFAULT_TRACK, "Track_Void_Escalation", "Track_Void_Tension");
    /** Time for a countdown's battle to register before the music is stopped for having no battle. */
    private static final long GRACE_MS = 2000;

    /** Player owner UUID or ownerless event UUID -> the players hearing that music. */
    private static final Map<UUID, Set<UUID>> LISTENERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STARTED = new ConcurrentHashMap<>();
    private static final Set<UUID> EVENT_SESSIONS = ConcurrentHashMap.newKeySet();
    /** Session -> battle kind of its music (stopSilenced: only the kinds whose music is now off). */
    private static final Map<UUID, BattleManager.Kind> KINDS = new ConcurrentHashMap<>();

    private BattleMusic() {
    }

    /** Music for {@code owner}'s fight and its title viewers. From a world thread (the wand or tick buffer). */
    static void start(BattleManager.Kind kind, PlayerRef owner, ComponentAccessor<EntityStore> buffer) {
        start(kind, owner, null, buffer);
    }

    static void start(BattleManager.Kind kind, PlayerRef owner, BattleManager.Battle.Anchor anchor,
                      ComponentAccessor<EntityStore> buffer) {
        String track = KindFx.musicTrack(kind);
        if (OLD_DEFAULT_TRACK.equals(track)) track = DEFAULT_TRACK;
        if (track == null || owner != null && !owner.isValid()
                || owner == null && (anchor == null || anchor.world() == null)) {
            return;
        }
        int index = MusicContainer.getAssetMap().getIndex(track);
        if (index <= 0) {
            MobBattlePlugin.log("Battle music: no music track named " + track);
            return;
        }
        UUID session = owner != null ? owner.getUuid() : anchor.eventId();
        if (owner == null) EVENT_SESSIONS.add(session);
        Set<UUID> listeners = LISTENERS.computeIfAbsent(session, k -> ConcurrentHashMap.newKeySet());
        List<PlayerRef> viewers = owner != null ? FightFx.titleViewers(owner)
                : FightFx.eventViewers(anchor, FxSettings.titleScope);
        for (PlayerRef viewer : viewers) {
            if (set(viewer, index, buffer)) listeners.add(viewer.getUuid());
        }
        if (kind != null) KINDS.put(session, kind);
        STARTED.put(session, System.currentTimeMillis());
        // 2026-10-16, user: no music heard on the test server; this says whether it started and for whom.
        if (MobBattlePlugin.debugging()) {
            MobBattlePlugin.debug("music: " + track + " (index " + index + ") for " + listeners.size() + " of " + viewers.size() + " viewer(s)");
        }
    }

    /**
     * Once per second (BattleManager.maintain): stops music when its battle/event and countdown have ended.
     * Polling instead of hooking every way a battle ends (winner, stop, new order, unloaded world).
     */
    static void maintain() {
        if (LISTENERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID session : LISTENERS.keySet()) {
            if (now - STARTED.getOrDefault(session, 0L) < GRACE_MS) continue;
            boolean busy = EVENT_SESSIONS.contains(session) ? Countdown.hasPendingEvent(session)
                    || BattleManager.battles().stream().anyMatch(b -> !b.over && b.anchor != null
                    && session.equals(b.anchor.eventId()))
                    : Countdown.hasPendingFor(session)
                    || BattleManager.battles().stream().anyMatch(b -> !b.over && b.owner() != null
                    && session.equals(b.owner().getUuid()));
            if (busy) continue;
            Set<UUID> listeners = LISTENERS.remove(session);
            STARTED.remove(session);
            EVENT_SESSIONS.remove(session);
            KINDS.remove(session);
            if (listeners != null) listeners.forEach(BattleMusic::clear);
        }
    }

    /**
     * A music setting changed (global, per kind, preset, panel): stops only the music whose kind now has no
     * track, so a kind with its own override keeps playing when the global music is turned off.
     */
    static void stopSilenced() {
        Set<UUID> silenced = new java.util.HashSet<>();
        for (UUID session : LISTENERS.keySet()) {
            if (KindFx.musicTrack(KINDS.get(session)) != null) continue;
            Set<UUID> listeners = LISTENERS.remove(session);
            STARTED.remove(session);
            EVENT_SESSIONS.remove(session);
            KINDS.remove(session);
            if (listeners != null) silenced.addAll(listeners);
        }
        // After every removal: clear() keeps the music of anyone still listed in another session.
        silenced.forEach(BattleMusic::clear);
    }

    /** Stops every battle music (/mobbattle music off). */
    static void stopAll() {
        // Empty the map first: clear() keeps the music of anyone still listed as a listener.
        Set<UUID> all = new java.util.HashSet<>();
        LISTENERS.values().forEach(all::addAll);
        LISTENERS.clear();
        STARTED.clear();
        EVENT_SESSIONS.clear();
        KINDS.clear();
        all.forEach(BattleMusic::clear);
    }

    /** Back to the world's own music, unless another battle's music still plays for that player. */
    private static void clear(UUID player) {
        if (LISTENERS.values().stream().anyMatch(s -> s.contains(player))) return;
        PlayerRef p = Universe.get().getPlayer(player);
        Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
        if (ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (ref.isValid()) set(p, 0, store);
        });
    }

    private static boolean set(PlayerRef player, int index, ComponentAccessor<EntityStore> accessor) {
        Ref<EntityStore> ref = player.getReference();
        // Only players in the accessor's world: another world's components must be read on its own thread.
        if (ref == null || !ref.isValid() || ref.getStore().getExternalData() != accessor.getExternalData()) return false;
        ForcedMusicTracker tracker = accessor.getComponent(ref, ForcedMusicTracker.getComponentType());
        if (tracker == null) return false;
        tracker.setCurrentContainerIndex(index);
        return true;
    }
}
