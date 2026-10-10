package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Public integration surface for starting and observing ownerless Mob Battle events. */
public final class MobBattleApi {
    public static final int API_VERSION = 1;
    public enum State { SCHEDULED, RUNNING, ENDED, UNKNOWN }
    public enum Mode { DUEL, ROYALE, FOCUS }
    public enum EndReason { WIN, DRAW, TIMEOUT, STOPPED, REPLACED, UNLOAD, SHUTDOWN, CANCELLED_COUNTDOWN }

    /** Immutable result containing stable IDs only; it never exposes ECS references or stores. */
    public record Snapshot(UUID eventId, UUID worldId, Mode mode, State state, List<UUID> winners,
                           Integer winnerSide, EndReason endReason, long updatedAtMillis) {
        public Snapshot {
            winners = winners == null ? List.of() : List.copyOf(winners);
        }
    }

    /** A rejected request has a null snapshot and a readable error. Accepted IDs are immediately queryable. */
    public record StartResult(Snapshot snapshot, String error) {
        public boolean accepted() { return snapshot != null; }
    }

    public record BattleEvent(Snapshot snapshot) { }

    private static final long RETAIN_MILLIS = TimeUnit.HOURS.toMillis(24);
    private static final int MAX_RECORDS = 4096;
    private static final ConcurrentHashMap<UUID, EventRecord> EVENTS = new ConcurrentHashMap<>();
    private static volatile long nextPrune;
    private static final CopyOnWriteArrayList<Consumer<BattleEvent>> LISTENERS = new CopyOnWriteArrayList<>();
    private static final java.util.concurrent.atomic.AtomicBoolean CLOSED = new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile MobBattleApi CURRENT;
    private static final java.util.concurrent.atomic.AtomicLong GENERATIONS = new java.util.concurrent.atomic.AtomicLong();
    private final long generation;

    static final class EventRecord {
        final UUID id;
        final UUID worldId;
        final Mode mode;
        volatile Snapshot snapshot;
        volatile long endedAt;

        EventRecord(UUID id, UUID worldId, Mode mode) {
            this.id = id;
            this.worldId = worldId;
            this.mode = mode;
            this.snapshot = new Snapshot(id, worldId, mode, State.SCHEDULED, List.of(), null, null,
                    System.currentTimeMillis());
        }
    }

    MobBattleApi() {
        generation = GENERATIONS.incrementAndGet();
        CLOSED.set(false);
        EVENTS.clear();
        LISTENERS.clear();
        CURRENT = this;
    }

    /** Register for terminal results. Close the returned handle to unsubscribe. Listener exceptions are isolated. */
    public AutoCloseable subscribe(Consumer<BattleEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (!usable()) throw new IllegalStateException("Mob Battle API is shut down or no longer current.");
        LISTENERS.add(listener);
        if (!usable()) {
            LISTENERS.remove(listener);
            throw new IllegalStateException("Mob Battle API is shut down.");
        }
        return () -> LISTENERS.remove(listener);
    }

    public Snapshot get(UUID eventId) {
        if (!usable()) return unknown(eventId);
        if (eventId == null) return unknown(null);
        prune();
        EventRecord record = EVENTS.get(eventId);
        return record == null ? unknown(eventId) : record.snapshot;
    }

    public CompletableFuture<StartResult> startDuel(UUID worldId, UUID firstId, UUID secondId) {
        return startDuel(resolveWorld(worldId), worldId, firstId, secondId);
    }

    public CompletableFuture<StartResult> startDuel(World world, UUID firstId, UUID secondId) {
        return startDuel(world, worldId(world), firstId, secondId);
    }

    private CompletableFuture<StartResult> startDuel(World world, UUID worldId, UUID firstId, UUID secondId) {
        UUID id = UUID.randomUUID();
        if (!usable()) return CompletableFuture.completedFuture(reject("Mob Battle is shutting down."));
        return submit(world, worldId, Mode.DUEL, id, (store, anchor) -> {
            if (firstId == null || secondId == null || firstId.equals(secondId)) return reject("Duel requires two different entity UUIDs.");
            Ref<EntityStore> first = world.getEntityRef(firstId), second = world.getEntityRef(secondId);
            if (!livingNpc(first, store) || !livingNpc(second, store)) return reject("Both UUIDs must identify living NPCs in the selected world.");
            Vector3d center = midpoint(first, second, store);
            if (center == null) return reject("A fighter has no current position.");
            BattleManager.Battle.Anchor eventAnchor = new BattleManager.Battle.Anchor(worldId, center, id);
            List<Ref<EntityStore>> fighters = List.of(first, second);
            String nameA = ChaosWand.nameOf(first, store), nameB = ChaosWand.nameOf(second, store);
            StartResult refused = registerOrReject(id, worldId, Mode.DUEL);
            if (refused != null) return refused;
            beginIntro(BattleManager.Kind.DUEL, eventAnchor, center, List.of(List.of(first), List.of(second)), store);
            Countdown.run(BattleManager.Kind.DUEL, eventAnchor, store, "DUEL", fighters, acc -> {
                if (!livingNpc(first, acc) || !livingNpc(second, acc)
                        || !PlayerFighters.canStart(first, acc) || !PlayerFighters.canStart(second, acc)) {
                    terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
                    return;
                }
                BattleManager.detach(List.of(first, second));
                FightManager.Result a = FightManager.turnAgainst(first, second, acc);
                FightManager.Result b = FightManager.turnAgainst(second, first, acc);
                if (a != FightManager.Result.AGGRESSIVE && b != FightManager.Result.AGGRESSIVE) {
                    terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
                    return;
                }
                BattleManager.Battle battle = BattleManager.startDuel(eventAnchor, first, second);
                BattleManager.setTarget(battle, first, second);
                BattleManager.setTarget(battle, second, first);
                FightManager.provoke(second, first, acc);
                FightManager.provoke(first, second, acc);
                Arena.setupAround(battle, acc);
                FightFx.title(battle, "FIGHT!", nameA + " vs " + nameB);
                running(id);
            }, reason -> terminal(id, reason, List.of(), null));
            return accepted(id, eventAnchor, Mode.DUEL);
        });
    }

    public CompletableFuture<StartResult> startRoyale(UUID worldId, Vector3d center, double radius, int maxMobs) {
        return startRoyale(resolveWorld(worldId), worldId, center, radius, maxMobs);
    }

    public CompletableFuture<StartResult> startRoyale(World world, Vector3d center, double radius, int maxMobs) {
        return startRoyale(world, worldId(world), center, radius, maxMobs);
    }

    private CompletableFuture<StartResult> startRoyale(World world, UUID worldId, Vector3d requestedCenter,
                                                        double radius, int maxMobs) {
        UUID id = UUID.randomUUID();
        if (!usable()) return CompletableFuture.completedFuture(reject("Mob Battle is shutting down."));
        Vector3d center = requestedCenter == null ? null : new Vector3d(requestedCenter);
        return submit(world, worldId, Mode.ROYALE, id, (store, anchor) -> {
            if (!validArea(center, radius, maxMobs)) return reject("Royale requires finite coordinates, radius 1-512, and maxMobs 2-200.");
            List<Ref<EntityStore>> mobs = ChaosWand.npcsAround(center, radius, maxMobs, store);
            if (mobs.size() < 2) return reject("Royale requires at least two living NPCs in the selected area.");
            BattleManager.Battle.Anchor eventAnchor = new BattleManager.Battle.Anchor(worldId, center, id);
            StartResult refused = registerOrReject(id, worldId, Mode.ROYALE);
            if (refused != null) return refused;
            beginIntro(BattleManager.Kind.ROYALE, eventAnchor, center, List.of(new ArrayList<>(mobs)), store);
            Countdown.run(BattleManager.Kind.ROYALE, eventAnchor, store, "BATTLE ROYALE", mobs, acc -> {
                mobs.removeIf(m -> !livingNpc(m, acc) || !PlayerFighters.canStart(m, acc));
                if (mobs.size() < 2) {
                    terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
                    return;
                }
                BattleManager.endChaosBattlesOf(mobs);
                BattleManager.Battle battle = BattleManager.startRoyale(eventAnchor, mobs);
                Arena.setup(battle, center, radius, null, acc);
                FightManager.splitFlocksAcrossSides(battle, acc);
                for (Ref<EntityStore> npc : mobs) {
                    Ref<EntityStore> enemy = BattleManager.nearestEnemy(battle, npc, acc);
                    if (enemy == null) continue;
                    BattleManager.setTarget(battle, npc, enemy);
                    FightManager.turnAgainst(npc, enemy, acc);
                    FightManager.provoke(npc, enemy, acc);
                }
                FightFx.title(battle, "BATTLE ROYALE!", mobs.size() + " NPCs, last one standing wins");
                running(id);
            }, reason -> terminal(id, reason, List.of(), null));
            return accepted(id, eventAnchor, Mode.ROYALE);
        });
    }

    public CompletableFuture<StartResult> startFocus(UUID worldId, UUID targetId, double radius, int maxMobs) {
        return startFocus(resolveWorld(worldId), worldId, targetId, radius, maxMobs);
    }

    public CompletableFuture<StartResult> startFocus(World world, UUID targetId, double radius, int maxMobs) {
        return startFocus(world, worldId(world), targetId, radius, maxMobs);
    }

    private CompletableFuture<StartResult> startFocus(World world, UUID worldId, UUID targetId, double radius, int maxMobs) {
        UUID id = UUID.randomUUID();
        if (!usable()) return CompletableFuture.completedFuture(reject("Mob Battle is shutting down."));
        return submit(world, worldId, Mode.FOCUS, id, (store, anchor) -> {
            if (targetId == null || !Double.isFinite(radius) || radius < 1 || radius > 512 || maxMobs < 2 || maxMobs > 200)
                return reject("Focus requires a target UUID, radius 1-512, and maxMobs 2-200.");
            Ref<EntityStore> target = world.getEntityRef(targetId);
            if (!livingNpc(target, store)) return reject("Focus target must be a living NPC in the selected world.");
            Vector3d center = ChaosWand.positionOf(target, store);
            if (center == null) return reject("Focus target has no current position.");
            List<Ref<EntityStore>> attackers = ChaosWand.npcsAround(center, radius, maxMobs, store);
            attackers.removeIf(m -> m == target);
            if (attackers.isEmpty()) return reject("Focus requires at least one living NPC near its target.");
            String targetName = ChaosWand.nameOf(target, store);
            List<Ref<EntityStore>> all = new ArrayList<>(attackers);
            all.add(target);
            BattleManager.Battle.Anchor eventAnchor = new BattleManager.Battle.Anchor(worldId, center, id);
            StartResult refused = registerOrReject(id, worldId, Mode.FOCUS);
            if (refused != null) return refused;
            beginIntro(BattleManager.Kind.ALL_VS_ONE, eventAnchor, center,
                    List.of(List.of(target), new ArrayList<>(attackers)), store);
            Countdown.run(BattleManager.Kind.ALL_VS_ONE, eventAnchor, store, "EVERYONE vs " + targetName, all, acc -> {
                attackers.removeIf(m -> !livingNpc(m, acc) || !PlayerFighters.canStart(m, acc));
                if (!livingNpc(target, acc) || !PlayerFighters.canStart(target, acc) || attackers.isEmpty()) {
                    terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
                    return;
                }
                List<Ref<EntityStore>> involved = new ArrayList<>(attackers);
                involved.add(target);
                BattleManager.endChaosBattlesOf(involved);
                BattleManager.detach(involved);
                int aggressive = 0;
                Ref<EntityStore> firstAttacker = null;
                for (Ref<EntityStore> npc : attackers) {
                    FightManager.startFight(npc, target, acc, true);
                    if (FightManager.turnAgainst(npc, target, acc) == FightManager.Result.AGGRESSIVE) {
                        aggressive++;
                        if (firstAttacker == null) firstAttacker = npc;
                    }
                    FightManager.provoke(npc, target, acc);
                }
                BattleManager.Battle battle = BattleManager.startFocus(eventAnchor, target, targetName, attackers);
                Arena.setup(battle, center, radius, null, acc);
                if (firstAttacker != null) {
                    BattleManager.setTarget(battle, target, firstAttacker);
                    FightManager.turnAgainst(target, firstAttacker, acc);
                }
                WandFx.loneCrown(target, acc);
                Buffs.set(target, Buffs.Source.LONE, 1);
                FightFx.title(battle, "EVERYONE vs " + targetName, aggressive + " attackers");
                running(id);
            }, reason -> terminal(id, reason, List.of(), null));
            return accepted(id, eventAnchor, Mode.FOCUS);
        });
    }

    public CompletableFuture<Snapshot> stop(UUID eventId) {
        if (!usable()) return CompletableFuture.completedFuture(unknown(eventId));
        CompletableFuture<Snapshot> result = new CompletableFuture<>();
        EventRecord record = eventId == null ? null : EVENTS.get(eventId);
        if (record == null) return CompletableFuture.completedFuture(unknown(eventId));
        if (record.snapshot.state() == State.ENDED) return CompletableFuture.completedFuture(record.snapshot);
        World world = resolveWorld(record.worldId);
        if (world == null) {
            terminal(eventId, EndReason.UNLOAD, List.of(), null);
            return CompletableFuture.completedFuture(get(eventId));
        }
        try {
            world.execute(() -> {
                if (!usable()) { result.complete(unknown(eventId)); return; }
                if (record.snapshot.state() == State.ENDED) { result.complete(record.snapshot); return; }
                boolean cancelled = Countdown.cancelEvent(eventId, EndReason.STOPPED);
                if (!cancelled) BattleManager.stopEvent(eventId);
                terminal(eventId, EndReason.STOPPED, List.of(), null);
                result.complete(get(eventId));
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    static void running(UUID eventId) { update(eventId, State.RUNNING, List.of(), null, null, null); }

    static void terminal(UUID eventId, EndReason reason, List<UUID> winners, Integer winnerSide) {
        EventRecord record = EVENTS.get(eventId);
        if (record == null) return;
        if (reason != EndReason.WIN && reason != EndReason.TIMEOUT) {
            winners = List.of();
            winnerSide = null;
        }
        synchronized (record) {
            if (record.snapshot.state() == State.ENDED) return;
            record.endedAt = System.currentTimeMillis();
            record.snapshot = new Snapshot(eventId, record.worldId, record.mode, State.ENDED, winners, winnerSide,
                    reason, record.endedAt);
        }
        BattleEvent event = new BattleEvent(record.snapshot);
        for (Consumer<BattleEvent> listener : LISTENERS) {
            try { listener.accept(event); }
            catch (Throwable t) { MobBattlePlugin.log("MobBattle API listener failed: " + t.getClass().getSimpleName()); }
        }
        prune();
    }

    static void shutdown() {
        if (!CLOSED.compareAndSet(false, true)) return;
        Countdown.cancelAll(MobBattleApi.EndReason.SHUTDOWN);
        BattleManager.shutdownEvents();
        for (EventRecord record : EVENTS.values()) {
            if (record.snapshot.state() != State.ENDED) terminal(record.id, EndReason.SHUTDOWN, List.of(), null);
        }
        LISTENERS.clear();
        prune();
    }

    private static void update(UUID id, State state, List<UUID> winners, Integer side, EndReason reason, UUID worldId) {
        EventRecord record = EVENTS.get(id);
        if (record == null) return;
        synchronized (record) {
            if (record.snapshot.state() == State.ENDED) return;
            record.snapshot = new Snapshot(id, worldId != null ? worldId : record.worldId, record.mode, state, winners,
                    side, reason, System.currentTimeMillis());
        }
        prune();
    }

    private StartResult accepted(UUID id, BattleManager.Battle.Anchor anchor, Mode mode) {
        EventRecord record = register(id, anchor.world(), mode, this);
        return record == null ? reject("Mob Battle is shutting down.") : new StartResult(record.snapshot, null);
    }

    static synchronized EventRecord register(UUID id, UUID worldId, Mode mode) {
        return register(id, worldId, mode, null);
    }

    private static synchronized EventRecord register(UUID id, UUID worldId, Mode mode, MobBattleApi requester) {
        if (CLOSED.get()) return null;
        if (requester != null && (CURRENT != requester || requester.generation != GENERATIONS.get())) return null;
        prune();
        EventRecord existing = EVENTS.get(id);
        if (existing != null) return existing;
        if (EVENTS.size() >= MAX_RECORDS) trimEnded(MAX_RECORDS - 1);
        if (EVENTS.size() >= MAX_RECORDS) return null;
        EventRecord record = new EventRecord(id, worldId, mode);
        EVENTS.put(id, record);
        prune();
        return record;
    }

    private static StartResult reject(String error) { return new StartResult(null, error); }

    /**
     * Registers the event before anything is shown (intro camera, runes). Null when accepted; a refusal after the
     * record exists ends it (CANCELLED_COUNTDOWN), so no record stays SCHEDULED until the 4096 cap.
     */
    private StartResult registerOrReject(UUID id, UUID worldId, Mode mode) {
        if (register(id, worldId, mode, this) == null) return reject("Mob Battle event capacity is full.");
        if (usable()) return null;
        terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
        return reject("Mob Battle is shutting down.");
    }

    @FunctionalInterface private interface StartAction {
        StartResult run(Store<EntityStore> store, BattleManager.Battle.Anchor anchor);
    }

    private CompletableFuture<StartResult> submit(World world, UUID worldId, Mode mode, UUID id, StartAction action) {
        CompletableFuture<StartResult> result = new CompletableFuture<>();
        if (!usable()) return CompletableFuture.completedFuture(reject("Mob Battle is shutting down."));
        if (world == null || worldId == null) return CompletableFuture.completedFuture(reject("World was not found."));
        try {
            world.execute(() -> {
                try {
                    if (!usable()) { result.complete(reject("Mob Battle is shutting down.")); return; }
                Store<EntityStore> store = world.getEntityStore().getStore();
                if (store.isShutdown()) { result.complete(reject("World is shutting down.")); return; }
                Vector3d origin = new Vector3d();
                BattleManager.Battle.Anchor anchor = new BattleManager.Battle.Anchor(worldId, origin, id);
                StartResult accepted = action.run(store, anchor);
                result.complete(accepted);
                } catch (Throwable t) {
                    if (EVENTS.containsKey(id)) {
                        if (!BattleManager.stopEvent(id)) terminal(id, EndReason.CANCELLED_COUNTDOWN, List.of(), null);
                    }
                    result.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            result.completeExceptionally(t);
        }
        return result;
    }

    private static Snapshot unknown(UUID id) {
        return new Snapshot(id, null, null, State.UNKNOWN, List.of(), null, null, System.currentTimeMillis());
    }

    private boolean usable() { return !CLOSED.get() && CURRENT == this && generation == GENERATIONS.get(); }

    private static void prune() {
        long now = System.currentTimeMillis();
        if (now < nextPrune) return;
        nextPrune = now + 1000;
        long cutoff = now - RETAIN_MILLIS;
        EVENTS.values().removeIf(record -> record.snapshot.state() == State.ENDED && record.endedAt < cutoff);
        trimEnded(MAX_RECORDS);
    }

    /** Drops the oldest ENDED records until at most {@code keep} remain. Active records are never dropped. */
    private static void trimEnded(int keep) {
        if (EVENTS.size() <= keep) return;
        EVENTS.values().stream().filter(r -> r.snapshot.state() == State.ENDED)
                .sorted((a, b) -> Long.compare(a.endedAt, b.endedAt))
                .limit(Math.max(0, EVENTS.size() - keep)).forEach(r -> EVENTS.remove(r.id, r));
    }

    private static World resolveWorld(UUID id) {
        Universe universe = Universe.get();
        return id == null || universe == null ? null : universe.getWorld(id);
    }
    private static UUID worldId(World world) { return world == null ? null : world.getWorldConfig().getUuid(); }
    private static boolean livingNpc(Ref<EntityStore> ref, com.hypixel.hytale.component.ComponentAccessor<EntityStore> store) {
        return ref != null && ref.isValid() && store.getComponent(ref, NPCEntity.getComponentType()) != null
                && BattleManager.isAlive(ref, store);
    }
    private static Vector3d midpoint(Ref<EntityStore> a, Ref<EntityStore> b,
                                     com.hypixel.hytale.component.ComponentAccessor<EntityStore> store) {
        Vector3d x = ChaosWand.positionOf(a, store), y = ChaosWand.positionOf(b, store);
        return x == null || y == null ? null : new Vector3d(x).add(y).mul(0.5);
    }
    private static boolean validArea(Vector3d center, double radius, int max) {
        return center != null && Double.isFinite(center.x) && Double.isFinite(center.y) && Double.isFinite(center.z)
                && Double.isFinite(radius) && radius >= 1 && radius <= 512 && max >= 2 && max <= 200;
    }
    private static void beginIntro(BattleManager.Kind kind, BattleManager.Battle.Anchor anchor, Vector3d center,
                                   List<List<Ref<EntityStore>>> sides, Store<EntityStore> store) {
        FightCamera.intro(kind, anchor, center, sides, store);
        FightFx.summonRune(kind, center, store);
    }
}
