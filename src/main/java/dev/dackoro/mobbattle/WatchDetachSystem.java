package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.spectator.SpectatorSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;

/**
 * Vanilla empties a spectator's Spectating when its target dies and resets the camera to first person
 * (SpectatorSystems.FollowTarget / OnSpectatingChange). Right after that, a watcher of a battle gets the follow camera
 * back on the fallen body (BattleWatch.detached), so the reset never shows. Cost when nobody watches: one map check.
 */
public class WatchDetachSystem extends RefChangeSystem<EntityStore, Spectating> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, SpectatorSystems.OnSpectatingChange.class));

    @Override
    public ComponentType<EntityStore, Spectating> componentType() {
        return Spectating.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, Spectating spectating, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> buffer) {
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, Spectating before, Spectating after, Store<EntityStore> store,
                               CommandBuffer<EntityStore> buffer) {
        if (before == null || before.getTargetRef() == null || after != null && after.getTargetRef() != null) return;
        BattleWatch.detached(ref, before.getTargetRef(), buffer);
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, Spectating spectating, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> buffer) {
    }
}
