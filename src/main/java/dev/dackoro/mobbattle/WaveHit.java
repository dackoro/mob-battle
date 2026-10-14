package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * The waves' hits: wand waves (Morph, Cleanse, Effect, Heal: waveTravel / waveGroundOnly) and battle waves (Snap
 * without a battle, royale, all vs one: battleWaveTravel / battleWaveGroundOnly, both off by default) (wand waves 2026-10-13, user: vanilla's Gobliterator slam is a growing
 * Donut selector that hits each entity when its ring gets there, ours hit everyone at once).
 * <ul>
 *   <li>waveTravel on (default): each target is hit when the visible ring (FightFx.shockwave) reaches its horizontal
 *   distance, so fliers above and mobs in caves below are still hit, just when the ring passes under/over them.</li>
 *   <li>waveGroundOnly on: only targets within {@link #GROUND_REACH} blocks above or below the wave's center count,
 *   as the Gobliterator's 2-high ring (off by default).</li>
 * </ul>
 * Targets are grouped per FightFx wave step (50 ms), one scheduled task per group on the world thread, so a big wave
 * is at most ~44 tasks, not one per mob.
 */
final class WaveHit {

    /** waveGroundOnly: the most a target's feet may be above or below the wave's center (blocks). */
    static final double GROUND_REACH = 3;
    private static final long STEP_MS = 50;

    private WaveHit() {
    }

    /** Drops the targets waveGroundOnly leaves out (too high above or below {@code center}). Returns the list. */
    static List<Ref<EntityStore>> filter(Vector3d center, List<Ref<EntityStore>> targets, ComponentAccessor<EntityStore> acc) {
        return filter(center, targets, FxSettings.waveGroundOnly, acc);
    }

    /** Same with an explicit ground-only flag (the battle waves use battleWaveGroundOnly). */
    static List<Ref<EntityStore>> filter(Vector3d center, List<Ref<EntityStore>> targets, boolean groundOnly,
                                         ComponentAccessor<EntityStore> acc) {
        if (!groundOnly) return targets;
        List<Ref<EntityStore>> out = new ArrayList<>();
        for (Ref<EntityStore> r : targets) {
            Vector3d p = r.isValid() ? ChaosWand.positionOf(r, acc) : null;
            if (p != null && Math.abs(p.y - center.y) <= GROUND_REACH) out.add(r);
        }
        return out;
    }

    /**
     * Runs {@code hit} on {@code targets} as the wave out to {@code radius} from {@code center} reaches them: the ones
     * reached at once (or all, with waveTravel off) right now with {@code now}, the others later in groups on the world
     * thread with the store as accessor. {@code hit} gets the group and an accessor; invalid refs are left out.
     */
    static void hit(Vector3d center, double radius, List<Ref<EntityStore>> targets, ComponentAccessor<EntityStore> now,
                    BiConsumer<List<Ref<EntityStore>>, ComponentAccessor<EntityStore>> hit) {
        hit(center, radius, targets, FxSettings.waveTravel, now, hit);
    }

    /** Same with an explicit travel flag (the battle waves use battleWaveTravel). */
    static void hit(Vector3d center, double radius, List<Ref<EntityStore>> targets, boolean travel,
                    ComponentAccessor<EntityStore> now, BiConsumer<List<Ref<EntityStore>>, ComponentAccessor<EntityStore>> hit) {
        if (targets.isEmpty()) return;
        if (!travel) {
            hit.accept(targets, now);
            return;
        }
        TreeMap<Long, List<Ref<EntityStore>>> groups = new TreeMap<>();
        for (Ref<EntityStore> r : targets) {
            Vector3d p = r.isValid() ? ChaosWand.positionOf(r, now) : null;
            if (p == null) continue;
            long at = FightFx.waveReachMs(Math.hypot(p.x - center.x, p.z - center.z), radius) / STEP_MS * STEP_MS;
            groups.computeIfAbsent(at, k -> new ArrayList<>()).add(r);
        }
        List<Ref<EntityStore>> first = groups.remove(0L);
        if (first != null) hit.accept(first, now);
        if (groups.isEmpty()) return;
        var store = targets.get(0).getStore();
        var world = store.getExternalData().getWorld();
        groups.forEach((at, group) -> com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown()) return;
            List<Ref<EntityStore>> live = new ArrayList<>();
            for (Ref<EntityStore> r : group) if (r.isValid()) live.add(r);
            if (!live.isEmpty()) hit.accept(live, store);
        }), at, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
