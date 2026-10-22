package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
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

    /** Called from checkWinner when a battle ended with a winner. */
    static void afterLastHit(BattleManager.Battle battle) {
        int pct = FxSettings.endSlowMo;
        if (pct <= 0 || pct >= 100 || battle.store == null || battle.store.isShutdown()) return;
        for (BattleManager.Battle other : BattleManager.battles()) {
            if (other != battle && other.store == battle.store) return;
        }
        World world = battle.store.getExternalData().getWorld();
        int mine;
        synchronized (SlowMo.class) { mine = ++gen; }
        ACTIVE.put(world.getName(), mine);
        float factor = Math.max(0.05f, pct / 100f);
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
