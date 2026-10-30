package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.Set;

/**
 * Weapon damage bonus (equipWeaponDamage, 2026-10-16, playtest: a mob's weapon only changed its look): a mob's hits are
 * scaled by {@link EquipWand#weaponFactor} = the held weapon's power over its role's own weapon. Off by default; with
 * it off this returns at the first check. Provocations (FightManager.PROVOKE_DAMAGE) are never scaled, or they would
 * count as real hits.
 */
public class WeaponDamageSystem extends DamageEventSystem {

    private final Set<Dependency<EntityStore>> dependencies = Set.of(new SystemDependency<>(Order.AFTER, BuffDamageSystem.class));

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return EntityStatMap.getComponentType();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, Damage damage) {
        if (!FxSettings.equipWeaponDamage || damage.isCancelled()) return;
        if (damage.getAmount() <= FightManager.PROVOKE_DAMAGE * 2) return;
        Ref<EntityStore> attacker = damage.getSource() instanceof Damage.EntitySource source ? source.getRef() : null;
        if (attacker == null || !attacker.isValid() || attacker == chunk.getReferenceTo(index)) return;
        NPCEntity npc = buffer.getComponent(attacker, NPCEntity.getComponentType());
        if (npc == null) return;
        float f = EquipWand.weaponFactor(npc);
        if (f != 1f) damage.setAmount(damage.getAmount() * f);
    }
}
