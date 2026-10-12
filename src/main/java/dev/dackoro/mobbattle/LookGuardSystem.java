package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;

/**
 * Runs the look guardian ({@link LookKeeper#tick}) every tick, in the tracker's queue-update group right BEFORE vanilla
 * {@code EntityTrackerSystems.EffectControllerSystem} consumes the effect changes. A hit (Red_Flash, Hit_Interrupt_*)
 * drops the look's ModelVFX on the client; re-sent here, the twin swap travels in the same EntityEffectsUpdate as the
 * change that dropped it, after it, so the normal skin never shows. A change made later in the tick is caught on the
 * next one, still before its own send. Cost with no look and no light: one check per world tick.
 */
public final class LookGuardSystem extends TickingSystem<EntityStore> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.BEFORE, EntityTrackerSystems.EffectControllerSystem.class));

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.QUEUE_UPDATE_GROUP;
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void tick(float delta, int index, Store<EntityStore> store) {
        if (LookKeeper.any() || LookLight.any()) LookKeeper.tick(store);
    }
}
