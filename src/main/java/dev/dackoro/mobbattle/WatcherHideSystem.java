package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Set;

/**
 * Players watching a battle (/mobbattle watch) are seen by nobody else. The game hides spectators only from players
 * who are not spectating, so two players watching saw each other's body standing among the fighters (playtest,
 * 2026-10-29). Same place as vanilla {@code EntityTrackerSystems.HideFromPlayer} and SkinRespawnSystem: after
 * CollectVisible each watcher is taken out of every other viewer's visible set. Cost when nobody watches: one map check
 * per viewer per tick.
 */
public final class WatcherHideSystem extends EntityTickingSystem<EntityStore> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return EntityTrackerSystems.EntityViewer.getComponentType();
    }

    @Override
    public void tick(float delta, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> buffer) {
        if (!BattleWatch.any()) return;
        List<Ref<EntityStore>> bodies = BattleWatch.watcherBodies(store);
        if (bodies.isEmpty()) return;
        EntityTrackerSystems.EntityViewer viewer = chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
        if (viewer == null || viewer.visible == null) return;
        Ref<EntityStore> self = chunk.getReferenceTo(index);
        // never a player's own entity: their client would lose itself
        for (Ref<EntityStore> body : bodies) if (!body.equals(self)) viewer.visible.remove(body);
    }
}
