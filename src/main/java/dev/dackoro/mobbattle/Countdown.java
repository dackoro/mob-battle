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
        final BattleManager.Kind kind;
        final long dueAt;
        final Store<EntityStore> store;
        /** Owner identity only; resolve the current PlayerRef when sending feedback. Null for console events. */
        final UUID owner;
        final BattleManager.Battle.Anchor anchor;
        final String label;
        final Set<Ref<EntityStore>> mobs;
        final Consumer<ComponentAccessor<EntityStore>> start;
        final Consumer<MobBattleApi.EndReason> onEnd;
        volatile boolean cancelled;

        Pending(BattleManager.Kind kind, long dueAt, Store<EntityStore> store, UUID owner,
                BattleManager.Battle.Anchor anchor, String label, Set<Ref<EntityStore>> mobs,
                Consumer<ComponentAccessor<EntityStore>> start, Consumer<MobBattleApi.EndReason> onEnd) {
            this.kind = kind;
            this.dueAt = dueAt;
            this.store = store;
            this.owner = owner;
            this.anchor = anchor;
            this.label = label;
            this.mobs = mobs;
            this.start = start;
            this.onEnd = onEnd;
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

    static void run(BattleManager.Kind kind, PlayerRef player, ComponentAccessor<EntityStore> buffer, String label,
                    Collection<Ref<EntityStore>> mobs, Consumer<ComponentAccessor<EntityStore>> start) {
        run(kind, player, null, buffer, label, mobs, start, null);
    }

    /** Player countdown that also tells when it is cancelled (never when it starts). */
    static void run(BattleManager.Kind kind, PlayerRef player, ComponentAccessor<EntityStore> buffer, String label,
                    Collection<Ref<EntityStore>> mobs, Consumer<ComponentAccessor<EntityStore>> start,
                    Consumer<MobBattleApi.EndReason> onEnd) {
        run(kind, player, null, buffer, label, mobs, start, onEnd);
    }

    /** Server-launched event countdown with no retained player owner. */
    static void run(BattleManager.Kind kind, BattleManager.Battle.Anchor anchor,
                    ComponentAccessor<EntityStore> buffer, String label, Collection<Ref<EntityStore>> mobs,
                    Consumer<ComponentAccessor<EntityStore>> start) {
        run(kind, null, anchor, buffer, label, mobs, start, null);
    }

    static void run(BattleManager.Kind kind, BattleManager.Battle.Anchor anchor,
                    ComponentAccessor<EntityStore> buffer, String label, Collection<Ref<EntityStore>> mobs,
                    Consumer<ComponentAccessor<EntityStore>> start,
                    Consumer<MobBattleApi.EndReason> onEnd) {
        run(kind, null, anchor, buffer, label, mobs, start, onEnd);
    }

    private static void run(BattleManager.Kind kind, PlayerRef player, BattleManager.Battle.Anchor anchor,
                            ComponentAccessor<EntityStore> buffer, String label, Collection<Ref<EntityStore>> mobs,
                            Consumer<ComponentAccessor<EntityStore>> start,
                            Consumer<MobBattleApi.EndReason> onEnd) {
        Set<Ref<EntityStore>> set = ConcurrentHashMap.newKeySet();
        set.addAll(mobs);
        cancelSharing(set);
        BattleMusic.start(kind, player, anchor, buffer);
        int seconds = KindFx.countdown(kind);
        if (seconds <= 0) {
            start.accept(buffer);
            return;
        }
        Store<EntityStore> store = buffer.getExternalData().getWorld().getEntityStore().getStore();
        Pending p = new Pending(kind, System.currentTimeMillis() + seconds * 1000L, store,
                player != null ? player.getUuid() : null, anchor, label, set, start, onEnd);
        synchronized (Countdown.class) {
            // With recomputeNextDue: an add on one world thread is never lost by a recompute on another.
            // TITLE_OWNER first: a finish from another thread can't run in between and leave it pointing here.
            if (player != null) TITLE_OWNER.put(player.getUuid(), p);
            PENDING.add(p);
            nextDue = Math.min(nextDue, p.dueAt);
        }
        for (int i = 0; i < seconds; i++) {
            int left = seconds - i;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> tick(p, left), i * 1000L, TimeUnit.MILLISECONDS);
        }
    }

    private static void tick(Pending p, int left) {
        PlayerRef player = p.owner != null ? com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(p.owner) : null;
        if (p.cancelled || p.owner != null && (player == null || !player.isValid())) {
            return;
        }
        if (player != null) {
            player.sendMessage(Message.raw("[" + p.label + "] " + left).color("#ffd23e"));
            if (TITLE_OWNER.get(p.owner) == p) FightFx.countdownTitle(player, left, p.label);
        } else {
            FightFx.countdownTitle(p.anchor, left, p.label);
        }
    }

    /** A new order for these mobs: pending countdowns that include any of them are cancelled. */
    private static void cancelSharing(Set<Ref<EntityStore>> mobs) {
        for (Pending old : PENDING) {
            if (old.mobs.stream().anyMatch(mobs::contains)) {
                finish(old, MobBattleApi.EndReason.REPLACED);
                PlayerRef player = old.owner != null ? com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(old.owner) : null;
                if (player != null && player.isValid()) {
                    player.sendMessage(Message.raw("[" + old.label + "] cancelled: its mobs got a new order.")
                            .color("#aaaaaa"));
                }
            }
        }
    }

    private static void finish(Pending p) {
        finish(p, MobBattleApi.EndReason.CANCELLED_COUNTDOWN);
    }

    private static void finish(Pending p, MobBattleApi.EndReason reason) {
        if (p.cancelled) return;
        p.cancelled = true;
        PENDING.remove(p);
        if (p.owner != null) TITLE_OWNER.remove(p.owner, p);
        if (p.onEnd != null) p.onEnd.accept(reason);
    }

    private static void finishStarted(Pending p) {
        if (p.cancelled) return;
        p.cancelled = true;
        PENDING.remove(p);
        if (p.owner != null) TITLE_OWNER.remove(p.owner, p);
    }

    /**
     * Battle Wand right click: cancels this player's pending countdowns whose label starts with {@code prefix}
     * (e.g. "DUEL"). Returns their mobs (empty if none).
     */
    static java.util.Set<Ref<EntityStore>> cancelFor(UUID player, String prefix) {
        java.util.Set<Ref<EntityStore>> mobs = new java.util.HashSet<>();
        for (Pending p : PENDING) {
            if (!p.cancelled && player.equals(p.owner) && p.label.startsWith(prefix)) {
                finish(p);
                mobs.addAll(p.mobs);
            }
        }
        return mobs;
    }

    /** Cancels every pending countdown. Returns how many. */
    static int cancelAll() {
        return cancelAll(MobBattleApi.EndReason.CANCELLED_COUNTDOWN);
    }

    static int cancelAll(MobBattleApi.EndReason reason) {
        int n = 0;
        for (Pending p : PENDING) {
            finish(p, reason);
            n++;
        }
        return n;
    }

    /** Cancels the event countdown correlated with an API event ID. */
    static boolean cancelEvent(UUID eventId) {
        return cancelEvent(eventId, MobBattleApi.EndReason.CANCELLED_COUNTDOWN);
    }

    static boolean cancelEvent(UUID eventId, MobBattleApi.EndReason reason) {
        boolean cancelled = false;
        for (Pending p : PENDING) {
            if (!p.cancelled && p.anchor != null && eventId.equals(p.anchor.eventId())) {
                finish(p, reason);
                cancelled = true;
            }
        }
        return cancelled;
    }

    /** True if {@code ref} is one of the fighters of a countdown still running (a player waiting for their fight). */
    static boolean involves(Ref<EntityStore> ref) {
        for (Pending p : PENDING) {
            if (!p.cancelled && p.mobs.contains(ref)) return true;
        }
        return false;
    }

    /** True if {@code player} has a countdown still running (BattleMusic keeps playing meanwhile). */
    static boolean hasPendingFor(java.util.UUID player) {
        for (Pending p : PENDING) {
            if (!p.cancelled && player.equals(p.owner)) return true;
        }
        return false;
    }

    static boolean hasPendingEvent(UUID eventId) {
        if (eventId == null) return false;
        for (Pending p : PENDING) {
            if (!p.cancelled && p.anchor != null && eventId.equals(p.anchor.eventId())) return true;
        }
        return false;
    }

    /** An owned duel's countdown still running; ownerless server events do not appear in the player's DuelCard. */
    record PendingDuel(PlayerRef player, Store<EntityStore> store, List<Ref<EntityStore>> mobs) {
    }

    static List<PendingDuel> pendingDuels() {
        List<PendingDuel> out = new java.util.ArrayList<>();
        for (Pending p : PENDING) {
            PlayerRef owner = p.owner != null
                    ? com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(p.owner) : null;
            if (!p.cancelled && owner != null && owner.isValid() && p.kind == BattleManager.Kind.DUEL && p.mobs.size() == 2) {
                out.add(new PendingDuel(owner, p.store, new java.util.ArrayList<>(p.mobs)));
            }
        }
        return out;
    }

    static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    static long nextDue() {
        return nextDue;
    }

    /**
     * From FightTickSystem: starts every due countdown of this world with the tick's buffer. Also from
     * BattleLifecycleSystem (world thread, the Store as buffer) once one is overdue: a world with no NPC ticking
     * (player-only duel, every mob gone meanwhile) would otherwise never start or drop it.
     */
    static void runDue(Store<EntityStore> store, long now, ComponentAccessor<EntityStore> buffer) {
        if (now < nextDue) {
            return;
        }
        boolean removed = false;
        for (Pending p : PENDING) {
            // Other worlds' countdowns are left to their own tick: no player lookups for them on every NPC tick.
            if (now < p.dueAt || p.store != store && !p.store.isShutdown()) {
                continue;
            }
            removed = true;
            PlayerRef player = p.owner != null ? com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(p.owner) : null;
            if (p.store.isShutdown() || p.owner != null && (player == null || !player.isValid())) {
                finish(p, p.store.isShutdown() ? MobBattleApi.EndReason.UNLOAD : MobBattleApi.EndReason.CANCELLED_COUNTDOWN);
            } else if (PENDING.remove(p)) {
                finishStarted(p);
                try {
                    p.start.accept(buffer);
                } catch (RuntimeException e) {
                    MobBattlePlugin.log("Countdown start failed: " + e);
                    e.printStackTrace();
                    if (p.anchor != null) BattleManager.stopEvent(p.anchor.eventId());
                    if (p.onEnd != null) p.onEnd.accept(MobBattleApi.EndReason.CANCELLED_COUNTDOWN);
                }
            }
        }
        // Due ones of other worlds stay: their own tick starts them (the min keeps them due).
        if (removed) recomputeNextDue();
    }

    private static synchronized void recomputeNextDue() {
        nextDue = PENDING.stream().mapToLong(p -> p.dueAt).min().orElse(Long.MAX_VALUE);
    }

    /** BattleManager.maintain: a countdown keeps its Store reachable; drop it if its world or player is gone. */
    static void prune() {
        for (Pending p : PENDING) {
            PlayerRef player = p.owner != null ? com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(p.owner) : null;
            if (p.store.isShutdown() || p.owner != null && (player == null || !player.isValid())) {
                finish(p, MobBattleApi.EndReason.UNLOAD);
            }
        }
        recomputeNextDue();
    }
}
