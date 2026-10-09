package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * /mobbattle countdown: "3", "2", "1" before a fight starts (duel, everyone vs one, royale, teams).
 * 0 (default) starts right away. Like fights without a countdown, several can run at once; a new one
 * cancels only the pending ones that share a mob with it (that mob got a new order). The big 3-2-1 titles
 * follow each player's newest countdown; every countdown also ticks in chat as "[label] n".
 * The delayed start runs inside FightTickSystem (a real tick with its CommandBuffer, like every other NPC
 * change of the mod), so it must re-check that its mobs are still there.
 */
final class Countdown {

    private static final class Pending {
        final long dueAt;
        final Store<EntityStore> store;
        final PlayerRef player;
        final String label;
        final Set<Ref<EntityStore>> mobs;
        final Consumer<ComponentAccessor<EntityStore>> start;
        volatile boolean cancelled;

        Pending(long dueAt, Store<EntityStore> store, PlayerRef player, String label, Set<Ref<EntityStore>> mobs,
                Consumer<ComponentAccessor<EntityStore>> start) {
            this.dueAt = dueAt;
            this.store = store;
            this.player = player;
            this.label = label;
            this.mobs = mobs;
            this.start = start;
        }
    }

    private static final List<Pending> PENDING = new CopyOnWriteArrayList<>();
    /** Per player: the countdown that owns the big titles (the newest one). */
    private static final Map<UUID, Pending> TITLE_OWNER = new ConcurrentHashMap<>();
    /**
     * Earliest dueAt of PENDING (Long.MAX_VALUE if none). runDue is called for every NPC on every tick while a
     * countdown is pending, so it must not walk the list until something is actually due.
     */
    private static volatile long nextDue = Long.MAX_VALUE;

    private Countdown() {
    }

    static void run(PlayerRef player, ComponentAccessor<EntityStore> buffer, String label,
                    Collection<Ref<EntityStore>> mobs, Consumer<ComponentAccessor<EntityStore>> start) {
        Set<Ref<EntityStore>> set = ConcurrentHashMap.newKeySet();
        set.addAll(mobs);
        cancelSharing(set);
        int seconds = FxSettings.countdownSeconds;
        if (seconds <= 0) {
            start.accept(buffer);
            return;
        }
        Store<EntityStore> store = buffer.getExternalData().getWorld().getEntityStore().getStore();
        Pending p = new Pending(System.currentTimeMillis() + seconds * 1000L, store, player, label, set, start);
        synchronized (Countdown.class) {
            // With recomputeNextDue: an add on one world thread is never lost by a recompute on another.
            PENDING.add(p);
            nextDue = Math.min(nextDue, p.dueAt);
        }
        TITLE_OWNER.put(player.getUuid(), p);
        for (int i = 0; i < seconds; i++) {
            int left = seconds - i;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> tick(p, left), i * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    private static void tick(Pending p, int left) {
        if (p.cancelled || !p.player.isValid()) {
            return;
        }
        p.player.sendMessage(Message.raw("[" + p.label + "] " + left).color("#ffd23e"));
        if (TITLE_OWNER.get(p.player.getUuid()) == p) {
            FightFx.countdownTitle(p.player, left, p.label);
        }
    }

    /** A new order for these mobs: pending countdowns that include any of them are cancelled. */
    private static void cancelSharing(Set<Ref<EntityStore>> mobs) {
        for (Pending old : PENDING) {
            if (old.mobs.stream().anyMatch(mobs::contains)) {
                finish(old);
                if (old.player.isValid()) {
                    old.player.sendMessage(Message.raw("[" + old.label + "] cancelled: its mobs got a new order.")
                            .color("#aaaaaa"));
                }
            }
        }
    }

    private static void finish(Pending p) {
        p.cancelled = true;
        PENDING.remove(p);
        TITLE_OWNER.remove(p.player.getUuid(), p);
    }

    /** Cancels every pending countdown. Returns how many. */
    static int cancelAll() {
        int n = 0;
        for (Pending p : PENDING) {
            finish(p);
            n++;
        }
        return n;
    }

    static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** From FightTickSystem: starts every due countdown of this world with the tick's buffer. */
    static void runDue(Store<EntityStore> store, long now, ComponentAccessor<EntityStore> buffer) {
        if (now < nextDue) {
            return;
        }
        for (Pending p : PENDING) {
            if (now < p.dueAt) {
                continue;
            }
            if (p.store.isShutdown() || !p.player.isValid()) {
                finish(p);
            } else if (p.store == store && PENDING.remove(p)) {
                finish(p);
                p.start.accept(buffer);
            }
        }
        // Due ones of other worlds stay: their own tick starts them (the min keeps them due).
        recomputeNextDue();
    }

    private static synchronized void recomputeNextDue() {
        nextDue = PENDING.stream().mapToLong(p -> p.dueAt).min().orElse(Long.MAX_VALUE);
    }

    /** BattleManager.maintain: a countdown keeps its Store reachable; drop it if its world or player is gone. */
    static void prune() {
        for (Pending p : PENDING) {
            if (p.store.isShutdown() || !p.player.isValid()) {
                finish(p);
            }
        }
        recomputeNextDue();
    }
}
