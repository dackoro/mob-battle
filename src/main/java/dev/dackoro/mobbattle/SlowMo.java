package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Slow motion after a battle's final blow (endSlowMo). Vanilla time dilation ({@code /time dilation},
 * {@link World#setTimeDilation}) is per WORLD: everyone in it slows down, so it is skipped while another battle
 * runs there. Put back to 1 after endSlowMoTime real seconds, on /mobbattle stop and on shutdown.
 */
final class SlowMo {
    private SlowMo() {}

    /** World name -> generation of its running slow motion (a newer one cancels the older restore). */
    private static final Map<String, Integer> ACTIVE = new ConcurrentHashMap<>();
    private static int gen;

    /** Battles whose slow motion already started at the final blow (finalBlow), so checkWinner does not restart it. */
    private static final Set<BattleManager.Battle> STARTED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** Battles whose world really went slow (not skipped for another battle there): the film's winner shot asks. */
    private static final Set<BattleManager.Battle> SLOWED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Whether this battle's end really slowed its world (FilmCamera.winnerShot, audit 2026-10-29). */
    static boolean slowed(BattleManager.Battle battle) {
        return battle != null && SLOWED.contains(battle);
    }

    /**
     * A battle fighter died (KillTracker, PlayerDeathSystem, same tick as the killing hit): when only one side is left
     * standing, or none, slow motion starts now. checkWinner only looks once a second, so the slow motion came after the
     * death animation (playtest 2026-10-29). Cheap: one pass over the battle's members per death, only with endSlowMo.
     */
    static void finalBlow(BattleManager.Battle battle, Ref<EntityStore> victim, ComponentAccessor<EntityStore> buffer) {
        if (FxSettings.endSlowMo < 2 || battle == null || battle.over || STARTED.contains(battle)) return;
        int side = Integer.MIN_VALUE;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            if (e.getKey() == victim || !BattleManager.isAlive(e.getKey(), buffer)) continue;
            if (side == Integer.MIN_VALUE) side = e.getValue();
            else if (side != e.getValue()) return;
        }
        // one side left (a win) or none (the last ones fell together: a draw)
        afterLastHit(battle);
    }

    /**
     * Called when a battle ends with a winner or a fought draw (checkWinner, the time-up path); started already if
     * finalBlow saw the last death.
     */
    static void afterLastHit(BattleManager.Battle battle) {
        int times = FxSettings.endSlowMo;
        if (times < 2 || battle.store == null || battle.store.isShutdown()) return;
        if (!STARTED.add(battle)) return;
        for (BattleManager.Battle other : BattleManager.battles()) {
            if (other != battle && other.store == battle.store) return;
        }
        SLOWED.add(battle);
        World world = battle.store.getExternalData().getWorld();
        int mine;
        synchronized (SlowMo.class) { mine = ++gen; }
        ACTIVE.put(world.getName(), mine);
        float factor = Math.max(0.05f, 1f / times);
        world.execute(() -> set(battle.store, factor));
        world.scheduleAfter(() -> {
            if (ACTIVE.remove(world.getName(), mine)) set(battle.store, 1f);
        }, Math.max(1, FxSettings.endSlowMoSeconds), TimeUnit.SECONDS);
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("slow motion x" + factor + " in " + world.getName());
    }

    private static void set(Store<EntityStore> store, float factor) {
        try {
            if (!store.isShutdown()) World.setTimeDilation(factor, store);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("slow motion: " + e);
        }
    }

    /** Puts every slowed world back to normal speed (/mobbattle stop, shutdown). */
    static void stopAll() {
        for (String name : ACTIVE.keySet()) {
            ACTIVE.remove(name);
            World world = Universe.get().getWorld(name);
            if (world == null) continue;
            Store<EntityStore> store = world.getEntityStore().getStore();
            try {
                world.execute(() -> set(store, 1f));
            } catch (RuntimeException ignored) {
                // world already stopping
            }
        }
    }
}
