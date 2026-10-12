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
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import java.util.Set;

/**
 * Lets wand-paired NPCs hurt each other even if they are the same species.
 * <p>
 * Vanilla NPCDamageSystems.FilterDamageSystem cancels NPC-to-NPC damage when the attacker's
 * role lists the victim's group in its disabled damage groups (CombatSupport.getCanCauseDamage),
 * which is how wolves of the same pack don't bite each other. This runs right after it, in the
 * same filter group, and un-cancels the damage only for an active wand fight pair.
 */
public class FightDamageSystem extends DamageEventSystem {

    private final Set<Dependency<EntityStore>> dependencies =
            Set.of(new SystemDependency<>(Order.AFTER, NPCDamageSystems.FilterDamageSystem.class));

    /**
     * EventSystem.shouldProcessEvent skips cancelled events by default, which would hide exactly
     * the hits we need to restore (same-species damage cancelled by the NPC filter).
     */
    @Override
    protected boolean shouldProcessEvent(Damage damage) {
        return true;
    }

    private static String roleOf(Ref<EntityStore> ref, CommandBuffer<EntityStore> buffer) {
        NPCEntity npc = ref.isValid() ? buffer.getComponent(ref, NPCEntity.getComponentType()) : null;
        return npc != null ? npc.getRoleName() : "?";
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    /**
     * Army Wand, hits on NPCs: the owner and army mobs never hurt their own army (cancelled, returns true);
     * when the owner hits another mob, the whole army attacks it.
     */
    private static boolean armyHit(Ref<EntityStore> attacker, Ref<EntityStore> victim, Damage damage,
                                   CommandBuffer<EntityStore> buffer) {
        if (attacker == null || !attacker.isValid()) {
            return false;
        }
        java.util.UUID victimOwner = Army.ownerOf(victim);
        var player = buffer.getComponent(attacker, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        java.util.UUID attackerOwner = player != null ? player.getUuid() : Army.ownerOf(attacker);
        // Unless the battle put them on different sides (army summons in a royale): they target each other, so
        // a cancelled hit left them hitting for nothing and the battle could never end (user, 2026-10-12).
        if (victimOwner != null && victimOwner.equals(attackerOwner) && !BattleManager.areEnemies(attacker, victim)) {
            damage.setCancelled(true);
            return true;
        }
        if (player != null && !damage.isCancelled()) {
            Army.order(player.getUuid(), victim, buffer);
        }
        if (victimOwner != null && !damage.isCancelled()) {
            Army.defend(victim, attacker, buffer);
        }
        return false;
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, Damage damage) {
        if (Army.any() && damage.getSource() instanceof Damage.EntitySource armySource && armyHit(armySource.getRef(),
                chunk.getReferenceTo(index), damage, buffer)) {
            return;
        }
        // Every NPC hit in every world passes here: nothing to do while no battle or wand fight exists.
        if (!BattleManager.hasBattles() && !FightManager.hasFights()
                || !(damage.getSource() instanceof Damage.EntitySource source)) {
            return;
        }
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        Ref<EntityStore> attackerRef = source.getRef();
        // No attacker entity: nothing battle-related below (and the concurrent maps reject a null key).
        if (attackerRef == null) return;
        if (attackerRef.isValid() && buffer.getComponent(attackerRef, NPCEntity.getComponentType()) != null
                && BattleManager.blocksDamage(attackerRef, victim)) {
            damage.setCancelled(true);
            return;
        }
        // A player fighter (/mobbattle playerFights) never hurts its own side's mobs, unless friendlyFire is on.
        if (BattleManager.isPlayerFighter(source.getRef())) {
            BattleManager.Battle mine = BattleManager.battleOf(source.getRef());
            if (mine != null && mine.has(victim) && mine.team(victim) == mine.team(source.getRef())
                    && !FxSettings.friendlyFire(mine.kind)) {
                damage.setCancelled(true);
                return;
            }
        }
        BattleManager.allyHit(attackerRef, victim);
        if (!FightManager.isFighting(source.getRef(), victim) && !BattleManager.areEnemies(source.getRef(), victim)) {
            // Unstick: a battle mob hit by an outsider (a player, a wild mob with isolation off) is in a fight too.
            if (Unstick.active() && !damage.isCancelled() && BattleManager.isManaged(victim)) {
                Unstick.hurt(victim, source.getRef());
            }
            return;
        }
        if (BattleDebug.tracing(source.getRef(), victim)) {
            // Debug: shows whether fighter hits reach the damage pipeline and if vanilla cancelled them.
            MobBattlePlugin.log("Fight hit " + roleOf(source.getRef(), buffer) + " -> " + roleOf(victim, buffer) + " "
                    + damage.getAmount() + " (cancelled by vanilla: " + damage.isCancelled() + ")");
        }
        if (damage.isCancelled()) {
            damage.setCancelled(false);
        }
        BattleManager.recordDamage(source.getRef(), victim, damage.getAmount());
    }
}
