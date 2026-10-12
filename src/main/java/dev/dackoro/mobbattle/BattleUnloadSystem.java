package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * A battle mob unloaded with its chunk (no player near it any more) is not dead: without this, checkWinner would
 * count it as fallen and declare the other side the winner. Its battle is flagged and checkWinner cancels it with
 * no winner. Kill/Snap Wand removals are RemoveReason.REMOVE, so they still count as deaths. A role change is also
 * an UNLOAD + LOAD of the same mob: RoleChange carries it over instead.
 */
public class BattleUnloadSystem extends RefSystem<EntityStore> {

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void onEntityAdded(Ref<EntityStore> ref, AddReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        // The second half of a role change (RoleChange): the same mob is back under a new Ref.
        if (reason == AddReason.LOAD) RoleChange.arrived(ref, store);
    }

    @Override
    public void onEntityRemove(Ref<EntityStore> ref, RemoveReason reason, Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        if (reason != RemoveReason.UNLOAD) return;
        // A role change (0.7.0 boss stages, ...) re-creates the mob at once: carried over, not abandoned.
        if (RoleChange.leaving(ref, store)) return;
        if (!BattleManager.hasBattles()) return;
        BattleManager.Battle battle = BattleManager.battleOf(ref);
        if (battle == null || battle.over) return;
        // Already dying (its death pose can outlast the player walking away): that one did fall.
        if (store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType()) != null) return;
        battle.abandoned = true;
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": a living fighter was unloaded, cancelling it");
    }
}
