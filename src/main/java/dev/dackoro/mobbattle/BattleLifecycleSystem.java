package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Once per second: global cleanup (any world thread, whichever ticks first), then victory and timeout of
 * the battles of the ticking world (on its own thread). Nothing is keyed by world here, so an unloaded
 * instance leaves nothing behind. The look guardian (LookKeeper) has its own system, LookGuardSystem.
 */
public final class BattleLifecycleSystem extends TickingSystem<EntityStore> {
    private static final long INTERVAL_MS = 1000;
    /** A countdown this late was not started by an NPC tick (no NPC ticking in its world): start it from here. */
    private static final long COUNTDOWN_LATE_MS = 250;
    private final AtomicLong nextMaintain = new AtomicLong();
    private final java.util.Map<Store<EntityStore>, Long> nextCountdown =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** Per world: next held-area redraw (areaonlywand). Weak keys: an unloaded instance leaves nothing behind. */
    private final java.util.Map<Store<EntityStore>, Long> nextHeldArea =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private final java.util.Map<Store<EntityStore>, Long> nextArmyRing =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextFollow =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextArena =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final long ARENA_MS = 250;
    private final java.util.Map<Store<EntityStore>, Long> nextUnstick =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextScore =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextCard =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextFocusCard =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextPlayers =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextWatch =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextCalm =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextDebugWand =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextExtras =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<Store<EntityStore>, Long> nextRunes =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    @Override
    public void tick(float delta, int index, Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        WandFx.applyPlayerKills(store, now);
        long next = nextMaintain.get();
        if (now >= next && nextMaintain.compareAndSet(next, now + INTERVAL_MS)) {
            BattleManager.maintain();
        }
        if (Countdown.hasPending() && now >= Countdown.nextDue() + COUNTDOWN_LATE_MS
                && now >= nextCountdown.getOrDefault(store, 0L)) {
            nextCountdown.put(store, now + COUNTDOWN_LATE_MS);
            // Starting a battle changes components: on the world thread, outside the system iteration.
            store.getExternalData().getWorld().execute(() -> {
                if (!store.isShutdown()) Countdown.runDue(store, System.currentTimeMillis(), store);
            });
        }
        if (FxSettings.areaOnlyWand && now >= nextHeldArea.getOrDefault(store, 0L)) {
            nextHeldArea.put(store, now + FightFx.HELD_AREA_REFRESH_MS);
            FightFx.refreshHeldAreas(store);
        }
        if (Army.any() && now >= nextArmyRing.getOrDefault(store, 0L)) {
            nextArmyRing.put(store, now + FightFx.TEAM_RING_REFRESH_MS);
            Army.drawRings(store);
        }
        if (Army.any() && now >= nextFollow.getOrDefault(store, 0L)) {
            nextFollow.put(store, now + INTERVAL_MS);
            // Teleports and leash changes outside the system iteration, on the world thread.
            store.getExternalData().getWorld().execute(() -> Army.follow(store));
        }
        if ((BattleManager.hasPlayerFighters() || TeamFx.hasPending() || Snap.hasPlayers())
                && now >= nextPlayers.getOrDefault(store, 0L)) {
            nextPlayers.put(store, now + FightFx.TEAM_RING_REFRESH_MS);
            // Player fighters' team look and ring (FightTickSystem only ticks NPCs), on the world thread.
            store.getExternalData().getWorld().execute(() -> PlayerFighters.tick(store));
        }
        if (Scoreboard.any() && now >= nextScore.getOrDefault(store, 0L)) {
            nextScore.put(store, now + INTERVAL_MS);
            // HUD changes on the world thread, outside the system iteration.
            store.getExternalData().getWorld().execute(() -> Scoreboard.tick(store));
        }
        if (DuelCard.any() && now >= nextCard.getOrDefault(store, 0L)) {
            nextCard.put(store, now + INTERVAL_MS);
            store.getExternalData().getWorld().execute(() -> DuelCard.tick(store));
        }
        if (FocusCard.any() && now >= nextFocusCard.getOrDefault(store, 0L)) {
            nextFocusCard.put(store, now + INTERVAL_MS);
            store.getExternalData().getWorld().execute(() -> FocusCard.tick(store));
        }
        if (DebugWand.any() && now >= nextDebugWand.getOrDefault(store, 0L)) {
            nextDebugWand.put(store, now + INTERVAL_MS);
            // Debug Wand tracks (effects and health, 1/s) read components: on the world thread, outside the iteration.
            store.getExternalData().getWorld().execute(() -> DebugWand.tick(store));
        }
        if (BattleWatch.any() && now >= nextWatch.getOrDefault(store, 0L)) {
            nextWatch.put(store, now + FightFx.TEAM_RING_REFRESH_MS);
            // Camera targets and the watch card outside the system iteration, on the world thread.
            store.getExternalData().getWorld().execute(() -> BattleWatch.tick(store));
        }
        if (SummonCalm.any() && now >= nextCalm.getOrDefault(store, 0L)) {
            nextCalm.put(store, now + SummonCalm.REFRESH_MS);
            // Attitude overrides and a sphere query per calm summon: on the world thread, outside the iteration.
            store.getExternalData().getWorld().execute(() -> SummonCalm.tick(store));
        }
        if ((RuneCaster.any() || MobExtras.any()) && now >= nextExtras.getOrDefault(store, 0L)) {
            nextExtras.put(store, now + MobExtras.PASS_MS);
            // Runes and kit stats saved per mob UUID (MobExtras): components read on the world thread.
            store.getExternalData().getWorld().execute(() -> MobExtras.pass(store));
        }
        if (RuneCaster.any() && now >= nextRunes.getOrDefault(store, 0L)) {
            nextRunes.put(store, now + RuneCaster.TICK_MS);
            // Rune casts start interaction chains (a plain list in the InteractionManager): on the world thread.
            store.getExternalData().getWorld().execute(() -> RuneCaster.tick(store));
        }
        if (Arena.any() && now >= nextArena.getOrDefault(store, 0L)) {
            nextArena.put(store, now + ARENA_MS);
            // Knockback, teleports and the timer's end outside the system iteration, on the world thread.
            store.getExternalData().getWorld().execute(() -> Arena.tick(store));
        }
        if (BattleManager.hasBattles()) {
            BattleManager.checkAll(store, now);
            if (Unstick.active() && now >= nextUnstick.getOrDefault(store, 0L)) {
                nextUnstick.put(store, now + INTERVAL_MS);
                // Teleports outside the system iteration, on the world thread.
                store.getExternalData().getWorld().execute(() -> Unstick.tick(store));
            }
        }
    }
}
