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
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;

/**
 * Debug Wand follow and /mobbattle test duel: every hit given or taken by a followed mob, as our filters leave it
 * (after BuffDamageSystem: final amount and whether it is cancelled). Every hit in every world passes here, so it
 * returns at once while nothing is followed.
 */
public class DebugHitSystem extends DamageEventSystem {

    private final Set<Dependency<EntityStore>> dependencies = Set.of(
            new SystemDependency<>(Order.AFTER, BuffDamageSystem.class));

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
        boolean wand = DebugWand.any(), test = TestTools.watching();
        if (!wand && !test) return;
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        Ref<EntityStore> attacker = damage.getSource() instanceof Damage.EntitySource source ? source.getRef() : null;
        DamageCause cause = DamageCause.getAssetMap().getAsset(damage.getDamageCauseIndex());
        String causeId = cause != null ? cause.getId() : "?";
        if (wand) DebugWand.onHit(attacker, victim, damage.getAmount(), causeId, damage.isCancelled(), buffer);
        // test verify isolation: a checked mob takes damage only from its own copy (area attacks of bosses hit the
        // neighbouring pairs, map-spawned mobs got in; user, 2026-10-13).
        if (test && TestTools.verifyBlocks(attacker, victim)) damage.setCancelled(true);
        // /mobbattle test duel: every hit of its two fighters.
        if (test) TestTools.onHit(attacker, victim, damage.getAmount(), causeId, damage.isCancelled(), buffer);
    }
}
