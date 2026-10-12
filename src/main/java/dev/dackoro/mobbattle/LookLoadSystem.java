package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * A Jade/Shimmer look is an infinite effect, so it is saved with the mob, but LookKeeper only keeps its list in
 * memory: a mob loaded again (world left and entered, chunk reloaded, server restart) wore an unguarded look. Mobs
 * that load with one are handed back to LookKeeper. Cost for any other mob: a look over its active effects.
 */
public class LookLoadSystem extends com.hypixel.hytale.component.system.RefSystem<EntityStore> {

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        if (reason != AddReason.LOAD) return;
        EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (!LookKeeper.hasLookEffect(effects)) return;
        // Components change outside the system callback, on the world thread.
        store.getExternalData().getWorld().execute(() -> LookKeeper.adopt(ref, store));
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
    }
}
