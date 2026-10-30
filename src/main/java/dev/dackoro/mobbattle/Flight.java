package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.TimeUnit;

/**
 * Whether a player was flying before the watch or the film put them in a flying game mode, given back when they leave
 * it (2026-10-29, playtest: after the watch, the film or an intro they were always left flying, even in survival). Leaving
 * the mode brings back its movement rules, but the client kept flying.
 */
final class Flight {
    /** After the game mode change: its own movement reset lands first. */
    private static final long RESTORE_DELAY_MS = 150;

    private Flight() {
    }

    /** Is the player flying now (world thread). */
    static boolean of(Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        MovementStatesComponent m = acc.getComponent(me, MovementStatesComponent.getComponentType());
        return m != null && m.getMovementStates() != null && m.getMovementStates().flying;
    }

    /** A moment after leaving the mode: flying again as before, or walking (vanilla Player.setFlying, as /fly). */
    static void restoreLater(Ref<EntityStore> me, Store<EntityStore> store, boolean flying) {
        store.getExternalData().getWorld().scheduleAfter(() -> {
            if (!me.isValid() || me.getStore() != store) return;
            Player player = store.getComponent(me, Player.getComponentType());
            if (player == null || of(me, store) == flying) return;
            try {
                player.setFlying(me, flying, store);
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Could not give back the flight state: " + e);
            }
        }, RESTORE_DELAY_MS, TimeUnit.MILLISECONDS);
    }
}
