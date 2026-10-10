package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Summoned mobs that leave on their own (/mobbattle summonDespawn, summonLifetime, summonExit).
 *
 * Summons are spawned outside battles, so these are global settings (FxSettings), not per battle kind. battleEnd
 * applies to whatever kind the summon fought in. {@link SummonList#spawn} records every mob it spawns here with its
 * spawn time (always, so switching the mode later also covers summons spawned before). The map is keyed by Ref and
 * pruned once a second from {@link BattleManager#maintain} (a Ref keeps its whole world alive). Exits run on the
 * mob's world thread (world.execute), never inside a system iteration.
 *
 * Limit: the tracking is in memory and by Ref. A summon that is unloaded and loaded again (a new Ref) or a server
 * restart is no longer tracked, so it stays as before.
 */
final class SummonDespawn {

    static final String NEVER = "never";
    static final String BATTLE_END = "battleEnd";
    static final String TIMER = "timer";

    static final String DISINTEGRATE = "disintegrate";
    static final String VANISH = "vanish";

    static final int MIN_LIFETIME = 5;
    static final int MAX_LIFETIME = 3600;

    /** Tracked summons -> spawn time (ms). */
    private static final Map<Ref<EntityStore>, Long> SPAWNED = new ConcurrentHashMap<>();

    private SummonDespawn() {
    }

    /** Called by SummonList.spawn for every mob it spawns (world thread). */
    static void track(Ref<EntityStore> mob) {
        if (mob != null) {
            SPAWNED.put(mob, System.currentTimeMillis());
        }
    }

    /** Canonical mode ("never", "battleEnd" or "timer") in any case, or null if it is none of them. */
    static String parseMode(String value) {
        if (value == null) return null;
        String v = value.trim();
        for (String mode : new String[] {NEVER, BATTLE_END, TIMER}) {
            if (mode.equalsIgnoreCase(v)) return mode;
        }
        return null;
    }

    /** Canonical exit ("disintegrate" or "vanish") in any case, or null if it is neither. */
    static String parseExit(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (DISINTEGRATE.equalsIgnoreCase(v)) return DISINTEGRATE;
        if (VANISH.equalsIgnoreCase(v)) return VANISH;
        return null;
    }

    /** Whole seconds within MIN_LIFETIME..MAX_LIFETIME, or -1 if the value is not one. */
    static int parseLifetime(String value) {
        try {
            int s = Integer.parseInt(value == null ? "" : value.trim());
            return s < MIN_LIFETIME || s > MAX_LIFETIME ? -1 : s;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int clampLifetime(int seconds) {
        return Math.max(MIN_LIFETIME, Math.min(MAX_LIFETIME, seconds));
    }

    /**
     * From BattleManager.maintain (once a second, from any world thread): drops dead summons, then, in timer mode,
     * sends every summon whose lifetime is over to its exit. O(tracked summons); nothing when none or the mode is never.
     */
    static void maintain() {
        if (SPAWNED.isEmpty()) return;
        SPAWNED.keySet().removeIf(mob -> !mob.isValid());
        if (!TIMER.equals(FxSettings.summonDespawn)) return;
        long now = System.currentTimeMillis();
        long lifetimeMs = FxSettings.summonLifetime * 1000L;
        for (Map.Entry<Ref<EntityStore>, Long> e : SPAWNED.entrySet()) {
            if (now - e.getValue() >= lifetimeMs) {
                leave(e.getKey());
            }
        }
    }

    /** From BattleManager.cleanUp (any thread): in battleEnd mode the surviving summons among a battle's members leave. */
    static void battleEnded(List<Ref<EntityStore>> members) {
        if (SPAWNED.isEmpty() || members == null || !BATTLE_END.equals(FxSettings.summonDespawn)) return;
        for (Ref<EntityStore> mob : members) {
            if (SPAWNED.containsKey(mob)) {
                leave(mob);
            }
        }
    }

    /** Claims a summon (only the first caller gets it) and runs its exit on its world thread. */
    private static void leave(Ref<EntityStore> mob) {
        if (SPAWNED.remove(mob) == null || !mob.isValid()) return;
        Store<EntityStore> store = mob.getStore();
        store.getExternalData().getWorld().execute(() -> exit(mob, store));
    }

    /** World thread. Living summons only: a dead one is already on its way out and keeps its death. */
    private static void exit(Ref<EntityStore> mob, Store<EntityStore> store) {
        if (store.isShutdown() || !mob.isValid() || !BattleManager.isAlive(mob, store)) return;
        if (VANISH.equals(FxSettings.summonExit)) {
            store.removeEntity(mob, RemoveReason.REMOVE);
            return;
        }
        // Disintegrate like the Kill Wand (soul rip, then removal), but without loot: the role counts its death
        // items as already dropped. WandFx's loot drop and vanilla's DropDeathItems both check that flag first.
        NPCEntity npc = store.getComponent(mob, NPCEntity.getComponentType());
        if (npc != null && npc.getRole() != null) {
            npc.getRole().setDeathItemsDropped();
        }
        WandFx.kill(mob, null, true, store);
    }
}
