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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.entity.knockback.KnockbackComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.Set;

/**
 * Hits on players who fight in a battle (/mobbattle playerFights):
 * <ul>
 *   <li>allies of the same battle (their teammate mobs) can't hurt them, unless friendlyFire is on for that kind;</li>
 *   <li>enemy players of the same battle follow the world's PvP rule, unless /mobbattle battlePvp is on: then
 *       only their hits are let through where PvP is off (spawn protection and invulnerability still apply);</li>
 *   <li>with battleIsolation on, mobs from outside the battle can't hurt them.</li>
 * </ul>
 * Creative players never fight (PlayerFighters.eligible), so the game's Creative invulnerability is untouched.
 * Runs after the vanilla player filters, so it knows what they decided.
 */
public class PlayerBattleDamageSystem extends DamageEventSystem {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, DamageSystems.PlayerDamageFilterSystem.class),
            new SystemDependency<>(Order.AFTER, DamageSystems.FilterPlayerWorldConfig.class),
            new SystemDependency<>(Order.AFTER, DamageSystems.FilterUnkillable.class));

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return DamageModule.get().getFilterDamageGroup();
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, Damage damage) {
        if (!BattleManager.hasPlayerFighters() || !(damage.getSource() instanceof Damage.EntitySource source)) {
            return;
        }
        Ref<EntityStore> victim = chunk.getReferenceTo(index);
        if (!BattleManager.isPlayerFighter(victim)) {
            return;
        }
        BattleManager.Battle battle = BattleManager.battleOf(victim);
        Ref<EntityStore> attacker = source.getRef();
        if (battle == null || attacker == null || !attacker.isValid() || attacker == victim) {
            return; // still waiting in a team draft, or not an entity hit: normal rules
        }
        boolean attackerIsPlayer = buffer.getComponent(attacker, PlayerRef.getComponentType()) != null;
        if (BattleManager.battleOf(attacker) == battle) {
            if (battle.team(attacker) == battle.team(victim)) {
                if (!FxSettings.friendlyFire(battle.kind)) damage.setCancelled(true);
                return;
            }
            if (damage.isCancelled() && attackerIsPlayer && FxSettings.battlePvp && onlyPvpBlocks(index, chunk, store)) {
                damage.setCancelled(false);
                // Since 0.7.0 (release) the PvP rule also takes the hit's knockback off the victim
                // (PlayerDamageFilterSystem.cancelKnockback): it comes back with the hit.
                if (damage.getIfPresentMetaObject(Damage.KNOCKBACK_COMPONENT) instanceof KnockbackComponent knockback) {
                    buffer.putComponent(victim, KnockbackComponent.getComponentType(), knockback);
                }
            }
            if (!damage.isCancelled()) {
                BattleManager.recordDamage(attacker, victim, damage.getAmount());
            }
            return;
        }
        if (!attackerIsPlayer && FxSettings.isolated(battle.kind)
                && buffer.getComponent(attacker, NPCEntity.getComponentType()) != null) {
            damage.setCancelled(true);
        }
    }

    /**
     * True if the hit was cancelled only by the world's PvP rule: PvP is off and none of the other vanilla reasons
     * (spawn protection, invulnerable, intangible, invulnerability effect, "player incoming damage disabled") apply.
     */
    private static boolean onlyPvpBlocks(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        if (world.getWorldConfig().isPvpEnabled()) {
            return false;
        }
        var archetype = chunk.getArchetype();
        if (archetype.contains(Invulnerable.getComponentType()) || archetype.contains(Intangible.getComponentType())) {
            return false;
        }
        Player player = chunk.getComponent(index, Player.getComponentType());
        if (player == null || player.hasSpawnProtection()) {
            return false;
        }
        EffectControllerComponent effects = chunk.getComponent(index, EffectControllerComponent.getComponentType());
        if (effects != null && effects.isInvulnerable()) {
            return false;
        }
        return !world.getGameplayConfig().getCombatConfig().isPlayerIncomingDamageDisabled();
    }
}
