package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * Battle kill count: when a battle NPC dies, the entity behind its killing hit (DeathComponent.getDeathInfo, the
 * Damage that killed it) gets the kill, if it is an enemy in the same battle. Player deaths come through
 * PlayerDeathSystem. Disintegrations (Kill Wand, Snap, timeUp kill) remove the mob without a death: no kill.
 * Shown as the leader in the timer box, the top 3 in chat at the end, and used by /mobbattle tieBreak kills.
 */
public class KillTracker extends RefChangeSystem<EntityStore, DeathComponent> {

    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> buffer) {
        onDeath(ref, death, buffer);
    }

    /** Credits the kill of {@code victim} to its killer, if both fight on different sides of the same battle. */
    static void onDeath(Ref<EntityStore> victim, DeathComponent death, ComponentAccessor<EntityStore> buffer) {
        if (!BattleManager.hasBattles() || !BattleManager.isManaged(victim) && !BattleManager.isPlayerFighter(victim)) {
            return;
        }
        BattleManager.Battle battle = BattleManager.battleOf(victim);
        Damage info = death.getDeathInfo();
        if (battle == null || info == null || !(info.getSource() instanceof Damage.EntitySource source)) {
            return;
        }
        Ref<EntityStore> killer = source.getRef();
        if (killer == null || killer == victim || !battle.has(killer)) {
            return;
        }
        int team = battle.team(killer);
        if (team == battle.team(victim)) {
            return;
        }
        battle.recordKill(killer, ChaosWand.nameOf(killer, buffer), team);
        // /mobbattle watch: whoever watched the victim now follows its killer.
        BattleWatch.onKill(victim, killer, buffer);
        // /mobbattle buff streak: % per kill in this battle, up to maxKills.
        BattleManager.Kills k = battle.kills.get(killer);
        if (k != null && Buffs.enabled(Buffs.Source.STREAK)) {
            Buffs.set(killer, Buffs.Source.STREAK, Math.min(k.count(), Buffs.streakMax));
        }
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
