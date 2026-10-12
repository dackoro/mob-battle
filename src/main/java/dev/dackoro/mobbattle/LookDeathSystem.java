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
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.Set;

/**
 * A Jade or Shimmer mob dying a normal death (killed by another mob, a fall, lava...) shatters or melts as with the
 * Kill Wand. Vanilla clears every effect on death (DeathSystems.ClearEntityEffects), so the look just went and the
 * normal death played (user, 2026-10-12: a bear killed a jade goblin). Runs after that clear, else the death look
 * would be cleared too. Cost for any other dying NPC: one LookKeeper lookup.
 */
public class LookDeathSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, DeathSystems.ClearEntityEffects.class));

    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> buffer) {
        if (MobBattlePlugin.debugging() && (Buffs.has(ref) || LookKeeper.styleOf(ref) != null)) {
            MobBattlePlugin.debug("death of " + ChaosWand.nameOf(ref, buffer) + ": look " + LookKeeper.styleOf(ref)
                    + ", buffed " + Buffs.has(ref) + ", dying " + WandFx.isDying(ref));
        }
        WandFx.lookDeathNatural(ref, death, buffer);
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, DeathComponent before, DeathComponent after, Store<EntityStore> store,
                               CommandBuffer<EntityStore> buffer) {
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> buffer) {
    }
}
