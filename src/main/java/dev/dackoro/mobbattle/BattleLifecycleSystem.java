package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Checks victory and timeout even when no surviving NPC is running its combat tick. */
public final class BattleLifecycleSystem extends TickingSystem<EntityStore> {
    private final Map<EntityStore, Long> lastCheck = new ConcurrentHashMap<>();

    @Override
    public void tick(float delta, int index, Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        EntityStore world = store.getExternalData();
        if (now - lastCheck.getOrDefault(world, 0L) < 1000) return;
        lastCheck.put(world, now);
        BattleManager.checkAll(store);
    }
}
