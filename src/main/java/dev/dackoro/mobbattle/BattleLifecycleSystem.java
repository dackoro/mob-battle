package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Once per second: global cleanup (any world thread, whichever ticks first), then victory and timeout of
 * the battles of the ticking world (on its own thread). Nothing is keyed by world here, so an unloaded
 * instance leaves nothing behind.
 */
public final class BattleLifecycleSystem extends TickingSystem<EntityStore> {
    private static final long INTERVAL_MS = 1000;
    private final AtomicLong nextMaintain = new AtomicLong();
    /** Per world: next held-area redraw (areaonlywand). Weak keys: an unloaded instance leaves nothing behind. */
    private final java.util.Map<Store<EntityStore>, Long> nextHeldArea =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private final java.util.Map<Store<EntityStore>, Long> nextFollow =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @Override
    public void tick(float delta, int index, Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        long next = nextMaintain.get();
        if (now >= next && nextMaintain.compareAndSet(next, now + INTERVAL_MS)) {
            BattleManager.maintain();
        }
        if (FxSettings.areaOnlyWand && now >= nextHeldArea.getOrDefault(store, 0L)) {
            nextHeldArea.put(store, now + FightFx.HELD_AREA_REFRESH_MS);
            FightFx.refreshHeldAreas(store);
        }
        if (Army.any() && now >= nextFollow.getOrDefault(store, 0L)) {
            nextFollow.put(store, now + INTERVAL_MS);
            // Teleports and leash changes outside the system iteration, on the world thread.
            store.getExternalData().getWorld().execute(() -> Army.follow(store));
        }
        if (BattleManager.hasBattles()) {
            BattleManager.checkAll(store, now);
        }
    }
}
