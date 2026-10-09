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

    public static final String DEFAULT_TRACK = "Track_Z1D_Goblin_Boss_Battle";
    /** Battle music tracks shipped with the game (Assets.zip, Server/Audio/MusicContainers). */
    public static final List<String> SUGGESTED = List.of(DEFAULT_TRACK, "Track_Void_Escalation", "Track_Void_Tension");
    /** Time for a countdown's battle to register before the music is stopped for having no battle. */
    private static final long GRACE_MS = 2000;

    /** Battle owner -> the players hearing their music. */
    private static final Map<UUID, Set<UUID>> LISTENERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STARTED = new ConcurrentHashMap<>();

    private BattleMusic() {
    }

    /** Music for {@code owner}'s fight and its title viewers. From a world thread (the wand or tick buffer). */
    static void start(PlayerRef owner, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.battleMusic || owner == null || !owner.isValid()) {
            return;
        }
        int index = MusicContainer.getAssetMap().getIndex(FxSettings.musicTrack);
        if (index <= 0) {
            return;
        }
        Set<UUID> listeners = LISTENERS.computeIfAbsent(owner.getUuid(), k -> ConcurrentHashMap.newKeySet());
        for (PlayerRef viewer : FightFx.titleViewers(owner)) {
            if (set(viewer, index, buffer)) listeners.add(viewer.getUuid());
        }
        STARTED.put(owner.getUuid(), System.currentTimeMillis());
    }

    /**
     * Once per second (BattleManager.maintain): stops the music of owners with no battle and no countdown left.
     * Polling instead of hooking every way a battle ends (winner, stop, new order, unloaded world).
     */
    static void maintain() {
        if (LISTENERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID owner : LISTENERS.keySet()) {
            if (now - STARTED.getOrDefault(owner, 0L) < GRACE_MS) continue;
            boolean busy = Countdown.hasPendingFor(owner)
                    || BattleManager.battles().stream().anyMatch(b -> !b.over && b.owner() != null
                    && owner.equals(b.owner().getUuid()));
            if (busy) continue;
            Set<UUID> listeners = LISTENERS.remove(owner);
            STARTED.remove(owner);
            if (listeners != null) listeners.forEach(BattleMusic::clear);
        }
    }

    /** Stops every battle music (/mobbattle music off). */
    static void stopAll() {
        LISTENERS.values().forEach(set -> set.forEach(BattleMusic::clear));
        LISTENERS.clear();
        STARTED.clear();
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
