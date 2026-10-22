package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * readyTime (user, 2026-10-20): seconds to get in position before a battle's VS screen and countdown. Every battle is
 * started with a click on a mob, so whoever starts one stands next to it when the VS shows; with a ready time they can
 * first walk or fly to their seat (an arena's stands). Meanwhile the player who started it sees a big number with
 * "get in position, crouch to start" and hears a soft tick each second, and the fighters keep the countdown's truce.
 * Crouching starts it at once (a crouch held at the click has to be let go first). Fewer than two standing calls it
 * off. A player who fights in the battle never waits: they already are where it happens. Per battle kind (KindFx
 * READY_TIME), default 0 = no wait; the cinematic preset sets 8.
 * Countdown's own lookups (involves, waitingTogether, cancelFor, cancelAll, prune, a new order for the same mobs)
 * cover the waits too, so players marked for the battle and the truce hold as in a countdown.
 */
final class ReadyWait {

    private static final class Wait {
        final BattleManager.Kind kind;
        final UUID owner;
        final Store<EntityStore> store;
        final Countdown.Label label;
        final Set<Ref<EntityStore>> mobs;
        final Consumer<ComponentAccessor<EntityStore>> go;
        final long endsAt;
        final AtomicBoolean done = new AtomicBoolean();
        /** World thread only: the crouch was let go once (a crouch held at the click doesn't count). */
        boolean armed;
        /** World thread only: the whole second last shown, so the title, tick and truce go once a second. */
        int shown = -1;

        Wait(BattleManager.Kind kind, UUID owner, Store<EntityStore> store, Countdown.Label label,
             Set<Ref<EntityStore>> mobs, Consumer<ComponentAccessor<EntityStore>> go, long endsAt) {
            this.kind = kind;
            this.owner = owner;
            this.store = store;
            this.label = label;
            this.mobs = mobs;
            this.go = go;
            this.endsAt = endsAt;
        }
    }

    private static final List<Wait> WAITS = new CopyOnWriteArrayList<>();
    /** How often a wait reads the owner's crouch (as FightCamera.watchSkip). */
    private static final long POLL_MS = 100;
    /** The soft tick each second, the VS screen's click. */
    private static final String TICK_SOUND = "SFX_Creative_Play_Selection_Place";
    /** The last seconds tick higher. */
    private static final int HIGH_TICKS = 3;

    private ReadyWait() {
    }

    /**
     * Seconds {@code player} waits before a battle of {@code kind} with {@code mobs}: readyTime, or 0 for a battle
     * nobody starts (API events), or one the player fights in.
     */
    static int secondsFor(BattleManager.Kind kind, PlayerRef player, Collection<Ref<EntityStore>> mobs) {
        int seconds = KindFx.readyTime(kind);
        if (seconds <= 0 || player == null || !player.isValid()) return 0;
        Ref<EntityStore> me = player.getReference();
        return me == null || !me.isValid() || mobs.contains(me) ? 0 : seconds;
    }

    /**
     * Runs {@code go} (the VS, intro camera and countdown of a battle) after the ready time, on the mobs' world thread
     * with its Store, or right away with {@code buffer} when there is no wait.
     */
    static void then(BattleManager.Kind kind, PlayerRef player, Collection<Ref<EntityStore>> mobs, Countdown.Label label,
                     ComponentAccessor<EntityStore> buffer, Consumer<ComponentAccessor<EntityStore>> go) {
        int seconds = secondsFor(kind, player, mobs);
        if (seconds <= 0) {
            go.accept(buffer);
            return;
        }
        Set<Ref<EntityStore>> set = ConcurrentHashMap.newKeySet();
        set.addAll(mobs);
        // A new order for these mobs: older countdowns and waits that share one are cancelled, as a countdown does.
        Countdown.cancelSharing(set);
        Store<EntityStore> store = buffer.getExternalData().getWorld().getEntityStore().getStore();
        Wait w = new Wait(kind, player.getUuid(), store, label, set, go, System.currentTimeMillis() + seconds * 1000L);
        WAITS.add(w);
        player.sendMessage(I18n.tr("battle.ready.start").param("label", label.text()).param("seconds", seconds)
                .color("#ffd23e"));
        MobBattlePlugin.debug("ready time " + seconds + " s for " + label.id() + " (" + player.getUsername() + ")");
        // the first pass on the next tick: the click's own interaction is still running on this thread
        later(w);
    }

    private static void later(Wait w) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (w.done.get()) return;
            if (w.store.isShutdown()) {
                drop(w);
                return;
            }
            try {
                w.store.getExternalData().getWorld().execute(() -> pass(w));
            } catch (RuntimeException e) {
                drop(w);   // the world is closing
            }
        }, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** One look, on the mobs' world thread: crouch, time up, and once a second the fighters, truce, title and tick. */
    private static void pass(Wait w) {
        if (w.done.get()) return;
        PlayerRef player = Universe.get().getPlayer(w.owner);
        Ref<EntityStore> me = player != null && player.isValid() ? player.getReference() : null;
        if (me == null || !me.isValid() || w.store.isShutdown()) {
            drop(w);
            return;
        }
        // Their crouch can only be read here when they are in the battle's world (another world's store belongs to
        // another thread); in another world the time runs out by itself.
        boolean crouch = me.getStore() == w.store && PlayerFighters.crouching(me, w.store);
        if (!crouch) w.armed = true;
        long now = System.currentTimeMillis();
        int left = (int) Math.ceil((w.endsAt - now) / 1000.0);
        if (crouch && w.armed || left <= 0) {
            if (crouch && w.armed) MobBattlePlugin.debug("ready time cut short by " + player.getUsername() + " crouching");
            start(w);
            return;
        }
        if (left != w.shown) {
            w.shown = left;
            List<Ref<EntityStore>> alive = new ArrayList<>();
            for (Ref<EntityStore> r : w.mobs) {
                if (!BattleManager.isAlive(r, w.store)) continue;
                alive.add(r);
                MajorDungeonsCompat.keepBoss(r, w.store);   // a boss waiting for its fight is not reset either
            }
            if (alive.size() < 2) {
                fell(w, player);
                return;
            }
            Countdown.truce(alive, w.store);
            FightFx.readyTitle(player, left, I18n.tr("battle.ready.title"));
            tick(player, left <= HIGH_TICKS ? 1.25f : 1f);
        }
        later(w);
    }

    private static void start(Wait w) {
        if (!w.done.compareAndSet(false, true)) return;
        WAITS.remove(w);
        try {
            w.go.accept(w.store);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Ready time start failed: " + e);
            e.printStackTrace();
        }
    }

    /** Fewer than two fighters standing: no battle. */
    private static void fell(Wait w, PlayerRef player) {
        if (!w.done.compareAndSet(false, true)) return;
        WAITS.remove(w);
        MobBattlePlugin.debug("ready time of " + w.label.id() + " called off: a fighter fell before the start");
        if (player != null && player.isValid()) {
            player.sendMessage(I18n.tr("battle.countdown.fell").param("label", w.label.text()).color("#aaaaaa"));
        }
    }

    /** Gone without a word (its owner left, its world closed). */
    private static void drop(Wait w) {
        if (w.done.compareAndSet(false, true)) WAITS.remove(w);
    }

    /** Cancelled by a new order, a right click or /mobbattle stop; tells the owner when {@code tell}. */
    private static boolean cancel(Wait w, boolean tell) {
        if (!w.done.compareAndSet(false, true)) return false;
        WAITS.remove(w);
        PlayerRef player = tell ? Universe.get().getPlayer(w.owner) : null;
        if (player != null && player.isValid()) {
            player.sendMessage(I18n.tr("battle.countdown.cancelled").param("label", w.label.text()).color("#aaaaaa"));
        }
        return true;
    }

    private static void tick(PlayerRef player, float pitch) {
        try {
            int index = com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getIndex(TICK_SOUND);
            if (index < 0) return;
            com.hypixel.hytale.server.core.universe.world.SoundUtil.playSoundEvent2dToPlayer(player, index,
                    com.hypixel.hytale.protocol.SoundCategory.UI, 0.6f, pitch);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Ready time tick failed: " + e);
        }
    }

    // ---- lookups for Countdown (which answers for both) ----

    static boolean any() {
        return !WAITS.isEmpty();
    }

    static boolean involves(Ref<EntityStore> ref) {
        for (Wait w : WAITS) {
            if (!w.done.get() && w.mobs.contains(ref)) return true;
        }
        return false;
    }

    static boolean together(Ref<EntityStore> a, Ref<EntityStore> b) {
        for (Wait w : WAITS) {
            if (!w.done.get() && w.mobs.contains(a) && w.mobs.contains(b)) return true;
        }
        return false;
    }

    /** A new order for these mobs: waits that share one are cancelled. */
    static void cancelSharing(Set<Ref<EntityStore>> mobs) {
        for (Wait w : WAITS) {
            if (!w.done.get() && w.mobs.stream().anyMatch(mobs::contains)) cancel(w, true);
        }
    }

    /** The owner's waits whose label starts with {@code prefix}; returns their mobs. */
    static Set<Ref<EntityStore>> cancelFor(UUID player, String prefix) {
        Set<Ref<EntityStore>> mobs = new HashSet<>();
        for (Wait w : WAITS) {
            if (!w.done.get() && player.equals(w.owner) && w.label.id().startsWith(prefix) && cancel(w, false)) {
                mobs.addAll(w.mobs);
            }
        }
        return mobs;
    }

    static int cancelAll() {
        int n = 0;
        for (Wait w : WAITS) {
            if (cancel(w, false)) n++;
        }
        return n;
    }

    /** BattleManager.maintain (through Countdown.prune): a wait keeps its Store reachable. */
    static void prune() {
        for (Wait w : WAITS) {
            PlayerRef player = Universe.get().getPlayer(w.owner);
            if (w.store.isShutdown() || player == null || !player.isValid()) drop(w);
        }
    }
}
