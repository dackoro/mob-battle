package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * A player who fights in a battle dies: they are out of it (BattleManager.fall). The game respawns a player as the
 * same entity (DeathComponent.respawn teleports it and removes DeathComponent from it), so without this they would
 * come back into the fight.
 */
public class PlayerDeathSystem extends RefChangeSystem<EntityStore, DeathComponent> {

    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> buffer) {
        if (!BattleManager.isPlayerFighter(ref)) {
            return;
        }
        // Before fall(): it takes the player out of the battle, and then the kill could not be told apart.
        KillTracker.onDeath(ref, death, buffer);
        BattleManager.Battle battle = BattleManager.fall(ref);
        if (battle == null) {
            // Died during the countdown, before the battle exists: they won't be in it (PlayerFighters.canStart).
            if (Countdown.involves(ref)) BattleManager.unmarkPlayer(ref);
            return;
        }
        PlayerRef player = buffer.getComponent(ref, PlayerRef.getComponentType());
        String name = player != null ? player.getUsername() : "A player";
        MobBattlePlugin.log("Battle: " + name + " fell and is out of the " + battle.kind.key + " battle");
        if (player != null) {
            player.sendMessage(Message.raw("You fell: you are out of the battle.").color("#ff6b5e"));
        }
        if (battle.owner() != null && battle.owner().isValid() && battle.owner() != player) {
            battle.owner().sendMessage(Message.raw(name + " fell and is out of the battle.").color("#aaaaaa"));
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
