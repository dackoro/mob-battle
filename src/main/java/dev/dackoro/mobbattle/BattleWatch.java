package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.gamemode.GameModeTypes;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle watch: spectate a battle that is already running, fighter by fighter. Built on the game's own spectator
 * (0.7.0-pre): our game mode {@code MobBattle_Spectator} (Server/Entity/GameMode, Parent Spectator: flying, invisible,
 * invulnerable) and the vanilla {@link Spectating} component, whose OnSpectatingChange system attaches the camera to
 * the target in third person (the mouse turns it around the target) and FollowTarget keeps the player's body with it.
 * Our game mode binds right click = nearest next fighter, left click = back to the previous one, Q = exit ({@link WatchInteraction}),
 * where the vanilla HardcoreSpectator cycles players only.
 * <p>
 * When the watched fighter is killed, the camera goes to its killer ({@link #onKill}, from KillTracker); when it is
 * gone some other way (disintegrated, unloaded) or the vanilla system let go of it, {@link #tick} picks the battle's
 * latest killer or the nearest fighter left. A card with the watched fighter's stats is on the left
 * (Common/UI/Custom/MobBattle/WatchCard.ui). When the battle ends the camera stays on the winner for
 * {@link #END_MS}, then the player gets their game mode and position back.
 */
final class BattleWatch extends CustomUIHud {

    static final String GAME_MODE = "MobBattle_Spectator";
    static final String KEY = "MobBattleWatch";
    private static final String UI = "MobBattle/WatchCard.ui";
    /** A 1v1 duel: both fighters, the watched one tagged (two mobs of one kind share a name). */
    private static final String UI_DUEL = "MobBattle/WatchDuel.ui";
    private static final long END_MS = 5000;
    private static final long CARD_MS = 1000;
    private static final String WARN = "#ffcc55";

    private static final class Watch {
        final BattleManager.Battle battle;
        final Store<EntityStore> store;
        final Vector3d home;
        final Rotation3f homeLook;
        volatile long endsAt;
        long nextCard;
        /** Fighters watched before, latest last (left click goes back). */
        final java.util.ArrayDeque<Ref<EntityStore>> history = new java.util.ArrayDeque<>();
        /** Fighters already shown in this round of right clicks (identity set; pruned of the gone ones each tick). */
        final java.util.Set<Ref<EntityStore>> seen = ConcurrentHashMap.newKeySet();
        /** A 1v1 duel's two fighters, in team order, kept once seen (the fallen one leaves the battle's members). */
        List<Ref<EntityStore>> duelPair;

        Watch(BattleManager.Battle battle, Store<EntityStore> store, Vector3d home, Rotation3f homeLook) {
            this.battle = battle;
            this.store = store;
            this.home = home;
            this.homeLook = homeLook;
        }
    }

    /** Watching players by UUID (never a PlayerRef: a reconnect makes a new one). */
    private static final Map<UUID, Watch> WATCHING = new ConcurrentHashMap<>();

    /** Built with the duel layout (UI_DUEL) or the one-fighter card (UI). */
    private final boolean duel;

    private BattleWatch(PlayerRef player, boolean duel) {
        super(player, KEY);
        this.duel = duel;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(duel ? UI_DUEL : UI);
    }

    static boolean any() {
        return !WATCHING.isEmpty();
    }

    static boolean isWatching(PlayerRef player) {
        return player != null && !WATCHING.isEmpty() && WATCHING.containsKey(player.getUuid());
    }

    /** /mobbattle watch (world thread): the player's own battle in this world, else the nearest one. */
    static void start(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        if (WATCHING.containsKey(player.getUuid())) {
            player.sendMessage(I18n.tr("battle.watch.already").color(WARN));
            return;
        }
        if (BattleManager.isPlayerFighter(me)) {
            player.sendMessage(I18n.tr("battle.watch.fighting").color(WARN));
            return;
        }
        if (Spectating.isSpectating(me, store)) {
            player.sendMessage(I18n.tr("battle.watch.spectating").color(WARN));
            return;
        }
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        if (t == null) return;
        Vector3d at = new Vector3d(t.getPosition());
        BattleManager.Battle battle = null;
        double best = Double.MAX_VALUE;
        for (BattleManager.Battle b : BattleManager.battles()) {
            if (b.over || b.store != store) continue;
            Ref<EntityStore> near = nearestAlive(b, at, null, store);
            if (near == null) continue;
            double d = distSq(near, at, store);
            // Your own battle first, whatever the distance.
            if (b.owner() != null && b.owner().getUuid().equals(player.getUuid())) d -= 1e12;
            if (d < best) {
                best = d;
                battle = b;
            }
        }
        if (battle == null) {
            player.sendMessage(I18n.tr("battle.watch.none").color(WARN));
            return;
        }
        watch(player, me, store, battle, at, t);
    }

    /**
     * /mobbattle autoWatch: a battle just started (Arena.setup, after its countdown). The camera viewers of its
     * owner (cameraScope) start watching it, unless they fight, already watch or spectate; it ends their intro.
     */
    static void auto(BattleManager.Battle battle) {
        // autoFilm on as well: the film (FilmCamera.auto) wins.
        if (battle == null || battle.store == null || !KindFx.autoWatch(battle.kind) || KindFx.autoFilm(battle.kind)) return;
        Store<EntityStore> store = battle.store;
        // Game mode and camera changes outside the system iteration that started the battle.
        store.getExternalData().getWorld().execute(() -> {
            if (battle.over || store.isShutdown()) return;
            for (PlayerRef p : FightFx.cameraViewers(battle)) {
                Ref<EntityStore> me = p.isValid() ? p.getReference() : null;
                if (me == null || !me.isValid() || me.getStore() != store || WATCHING.containsKey(p.getUuid())
                        || battle.has(me) || BattleManager.isPlayerFighter(me) || Spectating.isSpectating(me, store)
                        || !BattleManager.isAlive(me, store)) continue;
                TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
                if (t != null) watch(p, me, store, battle, new Vector3d(t.getPosition()), t);
            }
        });
    }

    private static void watch(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, BattleManager.Battle battle,
                              Vector3d at, TransformComponent t) {
        Ref<EntityStore> target = firstTarget(battle, at, store);
        if (target == null) {
            player.sendMessage(I18n.tr("battle.watch.none").color(WARN));
            return;
        }
        HeadRotation head = store.getComponent(me, HeadRotation.getComponentType());
        Rotation3f look = head != null ? new Rotation3f(head.getRotation()) : new Rotation3f(t.getRotation());
        // An intro still playing (cameraTime longer than the countdown) or an orbit gives way to the watch camera.
        FightCamera.cancel(player);
        if (!GameModeTypes.enter(me, store, GAME_MODE)) {
            player.sendMessage(I18n.tr("battle.watch.cant.start").color(WARN));
            return;
        }
        switchTo(me, target, store);
        WATCHING.put(player.getUuid(), new Watch(battle, store, at, look));
        // Cutting that camera gives the player their normal camera back a moment later (and an orbit glides back
        // first), after the spectator camera was attached: the client was left flying free while the server still
        // thought it followed a fighter. Attach it again once the cut is done.
        // Always, not only when we cut one: an intro that ends right now (duels: cameraTime 0 = the countdown, so it
        // ends exactly when the battle starts and autoWatch runs) sends its own camera reset after ours.
        reattachLater(player, store, REATTACH_MS);
        reattachLater(player, store, REATTACH_LATE_MS);
        reattachLater(player, store, REATTACH_LAST_MS);
        MobBattlePlugin.debug("watch: " + player.getUsername() + " watches battle #" + battle.id.toString().substring(0, 8));
        player.sendMessage(I18n.tr("battle.watch.help").color("#ffd23e"));
    }

    /**
     * Right click (dir +1): the fighter still standing nearest to the one watched, skipping the ones already seen
     * in this round (so two fighters side by side don't bounce back and forth; when everyone was seen, a new round
     * starts). Left click (-1): back to the fighter watched before (world thread).
     */
    static void cycle(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc, int dir) {
        Watch w = WATCHING.get(player.getUuid());
        if (w == null) {
            player.sendMessage(I18n.tr("battle.watch.not").color(WARN));
            return;
        }
        Spectating sp = acc.getComponent(me, Spectating.getComponentType());
        Ref<EntityStore> current = sp != null ? sp.getTargetRef() : null;
        Ref<EntityStore> next = null;
        if (dir < 0) {
            synchronized (w.history) {
                while (!w.history.isEmpty() && next == null) {
                    Ref<EntityStore> r = w.history.removeLast();
                    if (r != current && r.isValid() && w.battle.has(r) && BattleManager.isAlive(r, acc)) next = r;
                }
            }
            if (next == null) {
                player.sendMessage(I18n.tr("battle.watch.noearlier").color("#aaaaaa"));
                return;
            }
        } else {
            Vector3d from = current != null && current.isValid() ? ChaosWand.positionOf(current, acc) : null;
            if (from == null) {
                TransformComponent t = acc.getComponent(me, TransformComponent.getComponentType());
                from = t != null ? new Vector3d(t.getPosition()) : new Vector3d();
            }
            next = nearestAlive(w.battle, from, current, w.seen, acc);
            if (next == null) {
                // Everyone was seen: a new round, from the one watched now.
                w.seen.clear();
                next = nearestAlive(w.battle, from, current, w.seen, acc);
            }
            if (next == null) return;
            if (current != null) {
                synchronized (w.history) {
                    w.history.addLast(current);
                    if (w.history.size() > HISTORY) w.history.removeFirst();
                }
            }
        }
        if (current != null) w.seen.add(current);
        w.seen.add(next);
        switchTo(me, next, acc);
    }

    /** After a cut camera's glide back (FightCamera.cut: 0.3 s sequence, then the camera reset). */
    private static final long REATTACH_MS = 500;
    /** Second try, after a camera reset that left the world queue late. */
    private static final long REATTACH_LATE_MS = 1500;

    /** Third try: a drone/showcase intro that ended with the battle start can send its reset this late. */
    private static final long REATTACH_LAST_MS = 3000;

    /**
     * Sends the watcher's follow camera again, on the same target. Putting the same Spectating again does nothing:
     * vanilla OnSpectatingChange.onComponentSet skips it when the target is equal (javap, 2026-10-12), so the old
     * re-put never re-attached and a watcher whose intro reset came after the watch start was left floating free
     * (user). Calls vanilla's private applyFollowCamera instead.
     */
    private static void reattachLater(PlayerRef player, Store<EntityStore> store, long delayMs) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (store.isShutdown()) return;
            store.getExternalData().getWorld().execute(() -> {
                Ref<EntityStore> me = player.isValid() ? player.getReference() : null;
                if (me == null || !me.isValid() || me.getStore() != store || !WATCHING.containsKey(player.getUuid())) return;
                Spectating sp = store.getComponent(me, Spectating.getComponentType());
                Ref<EntityStore> target = sp != null ? sp.getTargetRef() : null;
                if (target != null && target.isValid()) followCamera(me, target, store);
            });
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Watches {@code target}: the vanilla Spectating component (its OnSpectatingChange sends a follow camera 4 blocks
     * back, whatever the target's size), then our sized follow camera a tick later, which replaces it.
     */
    private static void switchTo(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        acc.putComponent(me, Spectating.getComponentType(), new Spectating(target));
        var store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !me.isValid() || !target.isValid()) return;
            Spectating sp = store.getComponent(me, Spectating.getComponentType());
            if (sp != null && sp.getTargetRef() == target) followCamera(me, target, store);
        });
    }

    /**
     * The follow camera on {@code target}, as vanilla SpectatorSystems.OnSpectatingChange.applyFollowCamera (javap,
     * 0.7.0: attached third person at the eyes, raycast distance, the watcher's own rotation, raised to 1 block for
     * tiny eyes) but sized: vanilla keeps 4 blocks for anything, so a giant boss filled the screen with the camera
     * inside it (user, 2026-10-13). Distance = its half width + 1.1 x its hitbox height (a size buff grows the
     * hitbox), at least 4. Also used to re-attach after an intro reset. World thread.
     */
    static void followCamera(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        PlayerRef player = acc.getComponent(me, PlayerRef.getComponentType());
        var net = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType());
        if (player == null || net == null) return;
        var transform = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (transform != null) {
            acc.putComponent(me, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                    com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createForPlayer(transform.getPosition(), transform.getRotation()));
        }
        var s = new com.hypixel.hytale.protocol.ServerCameraSettings();
        s.attachedToType = com.hypixel.hytale.protocol.AttachedToType.EntityId;
        s.attachedToEntityId = net.getId();
        s.followAttachedEntity = true;
        s.eyeOffset = true;
        s.isFirstPerson = false;
        float eye = com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getEyeHeight(target, acc);
        if (eye < 1) s.positionOffset = new com.hypixel.hytale.protocol.Position(0, 1 - eye, 0);
        s.positionDistanceOffsetType = com.hypixel.hytale.protocol.PositionDistanceOffsetType.DistanceOffsetRaycast;
        s.distance = (float) followDistance(target, acc);
        s.rotationType = com.hypixel.hytale.protocol.RotationType.Custom;
        s.applyLookType = com.hypixel.hytale.protocol.ApplyLookType.Rotation;
        var mine = acc.getComponent(me, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (mine != null) {
            var r = mine.getRotation();
            s.rotation = new com.hypixel.hytale.protocol.Direction(r.yaw(), r.pitch(), r.roll());
        }
        player.getPacketHandler().writeNoCache(new com.hypixel.hytale.protocol.packets.camera.SetServerCamera(
                com.hypixel.hytale.protocol.ClientCameraView.Custom, true, s));
    }

    /** Follow distance for {@code target}: 4 blocks for a normal mob, its half width + 1.1 x its height when bigger (max 60). */
    static double followDistance(Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        var bb = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType());
        if (bb == null || bb.getBoundingBox() == null) return 4;
        var box = bb.getBoundingBox();
        double h = box.max.y - box.min.y;
        double w = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2;
        return Math.min(60, Math.max(4, w + h * 1.1));
    }

    /** How many fighters left click can go back through. */
    private static final int HISTORY = 32;

    /** Q, /mobbattle watch exit: back to the player's own game mode, camera and position (world thread). */
    static void exit(PlayerRef player, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        Watch w = WATCHING.remove(player.getUuid());
        hide(player, acc);
        var mode = GameModeTypes.getCurrentType(me, acc);
        if (mode == null || !mode.isSpectator()) {
            if (w == null) player.sendMessage(I18n.tr("battle.watch.notwatching").color(WARN));
            return;
        }
        if (!GameModeTypes.exit(me, acc)) {
            player.sendMessage(I18n.tr("battle.watch.cant.stop").color(WARN));
            return;
        }
        acc.tryRemoveComponent(me, Spectating.getComponentType());
        // Spectators fly through blocks: back where they started watching, not inside a hill.
        if (w != null && w.store == me.getStore()) {
            acc.putComponent(me, Teleport.getComponentType(), Teleport.createForPlayer(w.home, w.homeLook));
        }
        player.sendMessage(I18n.tr("battle.watch.stopped").color("#aaaaaa"));
    }

    /** /mobbattle stop: everyone stops watching (each on their world thread). */
    static int exitAll() {
        int n = 0;
        for (UUID id : new ArrayList<>(WATCHING.keySet())) {
            PlayerRef p = Universe.get().getPlayer(id);
            Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
            if (ref == null || !ref.isValid()) {
                WATCHING.remove(id);
                continue;
            }
            Store<EntityStore> st = ref.getStore();
            st.getExternalData().getWorld().execute(() -> exit(p, ref, st));
            n++;
        }
        return n;
    }

    /**
     * PlayerReadyEvent: the game mode is saved with the player (PersistentGameModeType), so someone who left
     * while watching (disconnect, server stop) comes back as our spectator with no watch behind it: give their
     * own game mode back. Their start position is gone with the watch, so they stay where they are.
     */
    static void onReady(Ref<EntityStore> me) {
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid() || !GAME_MODE.equals(GameModeTypes.getCurrentTypeId(me, store))) return;
            PlayerRef p = store.getComponent(me, PlayerRef.getComponentType());
            if (p != null && !WATCHING.containsKey(p.getUuid())) exit(p, me, store);
        });
    }

    /** KillTracker: the camera of whoever watched {@code victim} goes to its {@code killer}. */
    static void onKill(Ref<EntityStore> victim, Ref<EntityStore> killer, ComponentAccessor<EntityStore> acc) {
        if (WATCHING.isEmpty() || killer == null || !killer.isValid() || killer.getStore() != victim.getStore()) return;
        for (UUID id : WATCHING.keySet()) {
            PlayerRef p = Universe.get().getPlayer(id);
            Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
            if (ref == null || !ref.isValid() || ref.getStore() != victim.getStore()) continue;
            Spectating sp = acc.getComponent(ref, Spectating.getComponentType());
            if (sp != null && sp.getTargetRef() == victim) {
                switchTo(ref, killer, acc);
            }
        }
    }

    /** Every 250 ms per world (BattleLifecycleSystem, on the world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Watch> e : WATCHING.entrySet()) {
            Watch w = e.getValue();
            if (w.store != store && !w.store.isShutdown()) continue;
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            Ref<EntityStore> me = p != null && p.isValid() ? p.getReference() : null;
            if (me == null || !me.isValid() || me.getStore() != w.store) {
                // Left the server or the world. Moved to another world still as our spectator (/tp, the watched
                // world closed): give their game mode back there, else they keep flying through blocks.
                if (WATCHING.remove(e.getKey(), w) && me != null && me.isValid()) {
                    Store<EntityStore> there = me.getStore();
                    PlayerRef player = p;
                    there.getExternalData().getWorld().execute(() -> {
                        if (me.isValid() && GAME_MODE.equals(GameModeTypes.getCurrentTypeId(me, there))) exit(player, me, there);
                    });
                }
                continue;
            }
            // Still inside a world that shut down (picked up by another world's tick): never touch it with this
            // world's store; its refs are invalidated with it.
            if (w.store != store) {
                WATCHING.remove(e.getKey(), w);
                continue;
            }
            // Left our game mode some other way (/gamemode, /spectate exit): forget it.
            if (!GAME_MODE.equals(GameModeTypes.getCurrentTypeId(me, store))) {
                WATCHING.remove(e.getKey());
                hide(p, store);
                continue;
            }
            w.seen.removeIf(r -> !r.isValid());
            synchronized (w.history) {
                w.history.removeIf(r -> !r.isValid());
            }
            Spectating sp = store.getComponent(me, Spectating.getComponentType());
            Ref<EntityStore> target = sp != null ? sp.getTargetRef() : null;
            if (w.battle.over) {
                if (w.endsAt == 0) {
                    w.endsAt = now + END_MS;
                    Ref<EntityStore> winner = winner(w.battle, store);
                    if (winner != null && winner != target) {
                        switchTo(me, winner, store);
                        target = winner;
                    }
                } else if (now >= w.endsAt) {
                    exit(p, me, store);
                    continue;
                }
            } else if (target == null || !target.isValid() || !w.battle.has(target) || !BattleManager.isAlive(target, store)) {
                Ref<EntityStore> next = replacement(w.battle, target, me, store);
                if (next != null) {
                    switchTo(me, next, store);
                    target = next;
                }
            }
            if (now >= w.nextCard) {
                w.nextCard = now + CARD_MS;
                show(p, me, store, w, target);
            }
        }
    }

    // ---- picking fighters ----

    /**
     * Everyone vs one: the lone target (the one the battle is about). Otherwise the battle's top killer still
     * standing, else the fighter nearest to the player (a duel: the closer duelist).
     */
    private static Ref<EntityStore> firstTarget(BattleManager.Battle b, Vector3d at, Store<EntityStore> store) {
        if (b.kind == BattleManager.Kind.ALL_VS_ONE) {
            for (Ref<EntityStore> r : b.members()) {
                if (b.team(r) == 1 && r.isValid() && BattleManager.isAlive(r, store)) return r;
            }
        }
        Ref<EntityStore> top = topKillerAlive(b, store);
        return top != null ? top : nearestAlive(b, at, null, store);
    }

    /** The watched fighter is gone: the latest killer if standing, else the fighter nearest to the camera. */
    private static Ref<EntityStore> replacement(BattleManager.Battle b, Ref<EntityStore> old, Ref<EntityStore> me,
                                                Store<EntityStore> store) {
        Ref<EntityStore> last = b.lastKiller;
        if (last != null && last != old && b.has(last) && BattleManager.isAlive(last, store)) return last;
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        return nearestAlive(b, t != null ? t.getPosition() : new Vector3d(), old, store);
    }

    private static Ref<EntityStore> winner(BattleManager.Battle b, Store<EntityStore> store) {
        Ref<EntityStore> top = topKillerAlive(b, store);
        if (top != null) return top;
        List<Ref<EntityStore>> alive = aliveSorted(b, store);
        return alive.isEmpty() ? null : alive.get(0);
    }

    private static Ref<EntityStore> topKillerAlive(BattleManager.Battle b, ComponentAccessor<EntityStore> acc) {
        // The kill table's order (killTieBreak), first one still standing.
        return b.kills.entrySet().stream().sorted(b.ranking()).map(Map.Entry::getKey)
                .filter(r -> b.has(r) && BattleManager.isAlive(r, acc)).findFirst().orElse(null);
    }

    private static Ref<EntityStore> nearestAlive(BattleManager.Battle b, org.joml.Vector3dc at, Ref<EntityStore> skip,
                                                 ComponentAccessor<EntityStore> acc) {
        return nearestAlive(b, at, skip, java.util.Set.of(), acc);
    }

    /** The fighter still standing nearest to {@code at}, other than {@code skip} and the ones in {@code exclude}. */
    private static Ref<EntityStore> nearestAlive(BattleManager.Battle b, org.joml.Vector3dc at, Ref<EntityStore> skip,
                                                 java.util.Set<Ref<EntityStore>> exclude, ComponentAccessor<EntityStore> acc) {
        Ref<EntityStore> best = null;
        double bestD = Double.MAX_VALUE;
        for (Ref<EntityStore> r : b.members()) {
            if (r == skip || exclude.contains(r) || !r.isValid() || !BattleManager.isAlive(r, acc)) continue;
            double d = distSq(r, at, acc);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    private static double distSq(Ref<EntityStore> r, org.joml.Vector3dc at, ComponentAccessor<EntityStore> acc) {
        TransformComponent t = acc.getComponent(r, TransformComponent.getComponentType());
        return t != null ? t.getPosition().distanceSquared(at) : Double.MAX_VALUE;
    }

    /** Fighters still standing, side by side (team order). */
    private static List<Ref<EntityStore>> aliveSorted(BattleManager.Battle b, ComponentAccessor<EntityStore> acc) {
        List<Ref<EntityStore>> alive = new ArrayList<>();
        for (Ref<EntityStore> r : b.members()) if (r.isValid() && BattleManager.isAlive(r, acc)) alive.add(r);
        alive.sort((x, y) -> {
            int c = Integer.compare(b.team(x), b.team(y));
            return c != 0 ? c : Integer.compare(System.identityHashCode(x), System.identityHashCode(y));
        });
        return alive;
    }

    // ---- the card ----

    private static void show(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, Watch w,
                             Ref<EntityStore> mob) {
        Player p = store.getComponent(me, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        BattleManager.Battle b = w.battle;
        List<Ref<EntityStore>> pair = duelPair(w, store);
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof BattleWatch bw) || bw.duel != (pair != null)) {
            if (hud != null) huds.removeCustomHud(player, KEY);
            hud = new BattleWatch(player, pair != null);
            huds.addCustomHud(player, hud);
        }
        if (pair != null) {
            showDuel(hud, b, pair, mob, store);
            return;
        }
        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#Title.TextSpans", Message.translation("server.mobbattle.timer." + b.kind.key));
        int left = aliveSorted(b, store).size();
        line(ui, "#Left", b.over ? I18n.tr("hud.battleover") : I18n.tr("hud.fighters.left").param("count", left), "#c8d4e0");
        if (mob == null || !mob.isValid()) {
            line(ui, "#Name", "?", "#888888");
            for (String id : new String[] {"#Side", "#Health", "#Attack", "#Defense", "#Size", "#Kills"}) line(ui, id, "", "#888888");
            hud.update(false, ui);
            return;
        }
        boolean up = BattleManager.isAlive(mob, store);
        String name = ChaosWand.nameOf(mob, store);
        int team = b.team(mob);
        line(ui, "#Name", DuelCard.shorten(name == null ? "?" : name.replace('_', ' ')),
                b.isTeams() ? b.color(team) : up ? "#ffd23e" : "#888888");
        line(ui, "#Side", sideText(b, team), b.isTeams() ? b.color(team) : "#96a9be");
        line(ui, "#Health", DuelCard.healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
        line(ui, "#Attack", I18n.tr("hud.attack").param("v", DuelCard.stat(mob, Buffs.Stat.DAMAGE)), DuelCard.color(mob, Buffs.Stat.DAMAGE));
        line(ui, "#Defense", I18n.tr("hud.defense").param("v", DuelCard.stat(mob, Buffs.Stat.DEFENSE)), DuelCard.color(mob, Buffs.Stat.DEFENSE));
        float scale = DuelCard.scaleOf(mob, store);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        line(ui, "#Size", I18n.tr("hud.size").param("v", DuelCard.sizeValue(scale)), scaled ? "#7dff8a" : "#c8d4e0");
        BattleManager.Kills k = b.kills.get(mob);
        line(ui, "#Kills", I18n.tr("hud.kills").param("n", k != null ? k.count() : 0), k != null ? "#ffd23e" : "#c8d4e0");
        hud.update(false, ui);
    }

    /** The duel's two fighters (team order), or null when the battle is not a 1v1 duel. */
    private static List<Ref<EntityStore>> duelPair(Watch w, Store<EntityStore> store) {
        BattleManager.Battle b = w.battle;
        if (b.kind != BattleManager.Kind.DUEL) return null;
        if (w.duelPair == null) {
            List<Ref<EntityStore>> members = new ArrayList<>(b.members());
            if (members.size() != 2) return null;
            members.sort((x, y) -> Integer.compare(b.team(x), b.team(y)));
            w.duelPair = List.copyOf(members);
        }
        return w.duelPair;
    }

    /** The duel layout: both fighters (DuelCard's lines), the watched one tagged "Watching". */
    private static void showDuel(CustomUIHud hud, BattleManager.Battle b, List<Ref<EntityStore>> pair,
                                 Ref<EntityStore> watched, Store<EntityStore> store) {
        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#Title.TextSpans", Message.translation("server.mobbattle.timer." + b.kind.key));
        String[] sides = {"A", "B"};
        for (int i = 0; i < 2; i++) {
            Ref<EntityStore> mob = pair.get(i);
            String side = sides[i];
            boolean up = mob.isValid() && BattleManager.isAlive(mob, store);
            boolean on = mob == watched;
            String name = mob.isValid() ? ChaosWand.nameOf(mob, store) : null;
            line(ui, "#Name" + side, DuelCard.shorten(name == null ? "?" : name.replace('_', ' ')),
                    up ? (on ? "#ffd23e" : "#e8eef5") : "#888888");
            line(ui, "#Tag" + side, on ? I18n.tr("hud.watching.tag") : Message.empty(), "#ffd23e");
            line(ui, "#Health" + side, DuelCard.healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
            line(ui, "#Attack" + side, I18n.tr("hud.attack").param("v", DuelCard.stat(mob, Buffs.Stat.DAMAGE)), DuelCard.color(mob, Buffs.Stat.DAMAGE));
            line(ui, "#Defense" + side, I18n.tr("hud.defense").param("v", DuelCard.stat(mob, Buffs.Stat.DEFENSE)), DuelCard.color(mob, Buffs.Stat.DEFENSE));
            float scale = DuelCard.scaleOf(mob, store);
            boolean scaled = Math.abs(scale - 1f) > 0.01f;
            line(ui, "#Size" + side, I18n.tr("hud.size").param("v", DuelCard.sizeValue(scale)), scaled ? "#7dff8a" : "#c8d4e0");
        }
        line(ui, "#Left", b.over ? I18n.tr("hud.battleover") : Message.empty(), "#c8d4e0");
        hud.update(false, ui);
    }

    private static Message sideText(BattleManager.Battle b, int team) {
        return switch (b.kind) {
            case TEAMS -> I18n.tr("hud.side.team").param("name", b.name(team));
            case ALL_VS_ONE -> I18n.tr(team == 1 ? "hud.side.lone" : "hud.side.attacker");
            case ROYALE -> I18n.tr("hud.side.royale");
            case DUEL -> I18n.tr("hud.side.duelist");
        };
    }

    private static void line(UICommandBuilder b, String id, String text, String color) {
        b.set(id + ".TextSpans", Message.raw(text).color(color));
    }

    private static void line(UICommandBuilder b, String id, Message text, String color) {
        b.set(id + ".TextSpans", text.color(color));
    }

    private static void hide(PlayerRef player, ComponentAccessor<EntityStore> acc) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        Player p = acc.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
        }
    }
}
