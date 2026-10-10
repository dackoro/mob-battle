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
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;

/**
 * /mobbattle buff damage and defense, on every hit to anything with health (mobs and players). Runs after our
 * filters, so it sees each hit's final cancel state. Battle buffs count between battle enemies only; the Buff
 * Wand's everywhere (Buffs.scale).
 */
public class BuffDamageSystem extends DamageEventSystem {

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, FightDamageSystem.class),
            new SystemDependency<>(Order.AFTER, PlayerBattleDamageSystem.class),
            new SystemDependency<>(Order.AFTER, ArmyPlayerDamageSystem.class));

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
        // Every hit in every world passes here: nothing to do while nobody is buffed.
        if (!Buffs.any()) return;
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        Ref<EntityStore> attacker = damage.getSource() instanceof Damage.EntitySource source ? source.getRef() : null;
        // Neither side is buffed: Buffs.scale would return the damage unchanged, so skip the enemy checks.
        if (!Buffs.has(attacker) && !Buffs.has(victim)) return;
        boolean enemies = attacker != null && (BattleManager.areEnemies(attacker, victim) || FightManager.isFighting(attacker, victim));
        float scaled = Buffs.scale(attacker, victim, damage.getAmount(), enemies);
        if (scaled != damage.getAmount()) damage.setAmount(scaled);
    }
}
