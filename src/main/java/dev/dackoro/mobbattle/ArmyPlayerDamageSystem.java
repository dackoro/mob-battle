package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Army Wand, hits on players: your own army mobs can't hurt you, and any other mob or player that hits
 * you gets attacked by your army; a player you hit gets attacked by your army (PvP). (Hits on NPCs are handled in FightDamageSystem.)
 */
public class ArmyPlayerDamageSystem extends DamageEventSystem {

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    /**
     * After the vanilla player filters, so damage.isCancelled() already includes the world's PvP rule
     * (PlayerDamageFilterSystem cancels PvP when WorldConfig.isPvpEnabled() is false). Without this the order
     * inside the filter group is undefined and a hit the PvP rule cancels could still trigger the army.
     */
    private static final java.util.Set<com.hypixel.hytale.component.dependency.Dependency<EntityStore>> DEPENDENCIES =
            java.util.Set.of(
                    new com.hypixel.hytale.component.dependency.SystemDependency<>(
                            com.hypixel.hytale.component.dependency.Order.AFTER,
                            com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.PlayerDamageFilterSystem.class),
                    new com.hypixel.hytale.component.dependency.SystemDependency<>(
                            com.hypixel.hytale.component.dependency.Order.AFTER,
                            com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems.FilterPlayerWorldConfig.class));

    @Override
    public java.util.Set<com.hypixel.hytale.component.dependency.Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, Damage damage) {
        if (!Army.any() || !(damage.getSource() instanceof Damage.EntitySource source)) {
            return;
        }
        Ref<EntityStore> attacker = source.getRef();
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        if (attacker == null || !attacker.isValid() || attacker == victim) {
            return;
        }
        PlayerRef player = buffer.getComponent(victim, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }
        if (buffer.getComponent(attacker, NPCEntity.getComponentType()) != null) {
            // Its own army mob (or a mob of a leader serving in its army) may hurt a player fighter only when the
            // battle made them enemies.
            if (Army.sameChain(victim, player.getUuid(), attacker, Army.leaderKey(attacker))
                    && !BattleManager.areEnemies(attacker, victim)) {
                // armyFriendlyFire: its own army mob's hit lands, without turning anyone against it.
                if (!FxSettings.armyFriendlyFire) damage.setCancelled(true);
            } else if (!damage.isCancelled()) {
                Army.order(player.getUuid(), attacker, buffer);
                // A leader mob hit a player: its army goes for that player too (owner's order).
                java.util.UUID leads = Army.leaderKey(attacker);
                if (leads != null) Army.order(leads, victim, buffer);
            }
            return;
        }
        // PvP: a player hit another player. Only real hits count (not ones PvP rules already cancelled).
        PlayerRef hitter = buffer.getComponent(attacker, PlayerRef.getComponentType());
        if (hitter == null || damage.isCancelled()) {
            return;
        }
        // Bodyguard: the victim's army goes for the hitter; owner's order: the hitter's army goes for the victim.
        Army.order(player.getUuid(), attacker, buffer);
        Army.order(hitter.getUuid(), victim, buffer);
    }
}
