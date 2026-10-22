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
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.IgnoreDisableNPCIncomingDamage;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.systems.NPCDamageSystems;

import java.util.Set;

/**
 * Lets wand-paired NPCs hurt each other even if they are the same species.
 * <p>
 * Vanilla NPCDamageSystems.FilterDamageSystem cancels a hit on an NPC when the VICTIM's role lists
 * the attacker's group in its disabled damage groups, or the attacker is in the victim's flock
 * (CombatSupport.getCanCauseDamage), which is how wolves of the same pack don't bite each other.
 * This runs right after it, in the same filter group, and un-cancels the damage only for an active
 * wand fight pair or battle enemies, and never when vanilla protects the victim (vanillaProtects).
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
        // Mob leaders (2026-10-14): the army key a mob leads, if any.
        java.util.UUID attackerLeads = player == null ? Army.leaderKey(attacker) : null;
        java.util.UUID victimLeads = Army.leaderKey(victim);
        // Same army: one chain of command (mates, a leader and its mobs, an owner and every army under it). Unless the
        // battle put them on different sides (army summons in a royale): they target each other, so a cancelled hit
        // left them hitting for nothing and the battle could never end (user, 2026-10-12).
        boolean sameArmy = Army.sameChain(attacker, player != null ? player.getUuid() : attackerLeads, victim, victimLeads);
        if (sameArmy && !BattleManager.areEnemies(attacker, victim)) {
            // armyFriendlyFire: the hit lands, but nobody fights back or calls the others (they stay allies).
            if (!FxSettings.armyFriendlyFire) damage.setCancelled(true);
            return true;
        }
        if (!damage.isCancelled()) {
            // Owner's order: a player or a leader mob hit it, so their army goes for it.
            if (player != null) Army.order(player.getUuid(), victim, buffer);
            else if (attackerLeads != null) Army.order(attackerLeads, victim, buffer);
            // Bodyguard: a leader mob was hit, so its army goes for the attacker.
            if (victimLeads != null) Army.order(victimLeads, attacker, buffer);
        }
        if (victimOwner != null && !damage.isCancelled()) {
            Army.defend(victim, attacker, buffer);
        }
        return false;
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, Damage damage) {
        // Medium: a projectile hit tells which roles fight at range (they can reach fliers and fish at the surface).
        if (damage.getSource() instanceof Damage.ProjectileSource shot) Medium.sawProjectile(shot.getRef(), buffer);
        // Countdown truce: two fighters waiting for the same start don't hurt each other yet (Countdown.truce). First,
        // so a cancelled hit sends no army after the victim and wakes no calm summon; a player's hits too (the mob
        // ignores them meanwhile, so they were free hits; audit 2026-10-17).
        if (Countdown.anyWaiting() && !damage.isCancelled() && damage.getSource() instanceof Damage.EntitySource waiting
                && Countdown.waitingTogether(waiting.getRef(), chunk.getReferenceTo(index))) {
            damage.setCancelled(true);
            return;
        }
        if (Army.any() && damage.getSource() instanceof Damage.EntitySource armySource && armyHit(armySource.getRef(),
                chunk.getReferenceTo(index), damage, buffer)) {
            return;
        }
        if (SummonCalm.any() && !damage.isCancelled() && damage.getSource() instanceof Damage.EntitySource calmSource) {
            // A calm summon that is hit may fight back that attacker (/mobbattle summonCalm).
            SummonCalm.hurt(chunk.getReferenceTo(index), calmSource.getRef(), buffer);
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
            // Only what the NPC/flock filter cancelled comes back: a victim vanilla protects stays unhurt (audit 0.7.2).
            if (vanillaProtects(index, chunk, store)) return;
            damage.setCancelled(false);
        }
        BattleManager.recordDamage(source.getRef(), victim, damage.getAmount());
    }

    /**
     * True if vanilla would cancel this hit for the victim itself, whatever the attacker: dead, Invulnerable or
     * Intangible, an invulnerability effect (boss interludes, dodges), or a world with NPC incoming damage off
     * (DamageSystems.FilterUnkillable / FilterNPCWorldConfig, which have no order against this system, decompiled
     * 0.7.2). Read only for a cancelled hit between enemies.
     */
    private static boolean vanillaProtects(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store) {
        var archetype = chunk.getArchetype();
        if (archetype.contains(DeathComponent.getComponentType()) || archetype.contains(Invulnerable.getComponentType())
                || archetype.contains(Intangible.getComponentType())) {
            return true;
        }
        EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (effects != null && effects.isInvulnerable()) return true;
        return store.getExternalData().getWorld().getGameplayConfig().getCombatConfig().isNpcIncomingDamageDisabled()
                && !archetype.contains(IgnoreDisableNPCIncomingDamage.getComponentType());
    }
}
