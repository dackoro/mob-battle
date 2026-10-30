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

    /** #Body heights of WatchCard.ui and WatchDuel.ui with every line shown (hidden stat lines give theirs back). */
    private static final int WATCH_BODY = 190;
    private static final int WATCH_DUEL_BODY = 298;

    static final String GAME_MODE = "MobBattle_Spectator";
    static final String KEY = "MobBattleWatch";
    private static final String UI = "MobBattle/WatchCard.ui";
    /** A 1v1 duel: both fighters, the watched one tagged (two mobs of one kind share a name). */
    private static final String UI_DUEL = "MobBattle/WatchDuel.ui";
    private static final long END_MS = 5000;
    private static final long CARD_MS = 1000;
    private static final String WARN = "#ffcc55";

    /** Auto switch seconds for this viewer: the setting, or AUTO_DEFAULT_S when E turned it on with the setting off. */
    private static int autoSeconds(Watch w) {
        Boolean on = w.auto;
        if (on == null) return FxSettings.watchAutoSwitch;
        return on ? (FxSettings.watchAutoSwitch > 0 ? FxSettings.watchAutoSwitch : AUTO_DEFAULT_S) : 0;
    }

    /** E while watching: the automatic switch on or off for this viewer only (2026-10-29). */
    static void toggleAuto(PlayerRef player) {
        Watch w = WATCHING.get(player.getUuid());
        if (w == null) return;
        boolean on = autoSeconds(w) <= 0;
        w.auto = on;
        w.lastSwitch = System.currentTimeMillis();
        player.sendMessage(I18n.tr(on ? "battle.watch.auto.on" : "battle.watch.auto.off").param("s", autoSeconds(w)).color(on ? "#7CFF8A" : "#aaaaaa"));
    }

    private static final int AUTO_DEFAULT_S = 6;

    /** How long the watch stays on a fighter that fell before going on (its killer, else another fighter). */
    private static final long FALL_HOLD_MS = 1800;

    private static final class Watch {
        final BattleManager.Battle battle;
        final Store<EntityStore> store;
        final Vector3d home;
        final Rotation3f homeLook;
        /** Flying before the watch (or the film before it): given back on exit (Flight). */
        boolean flying;
        volatile long endsAt;
        long nextCard;
        /** Fighters watched before, latest last (left click goes back). */
        final java.util.ArrayDeque<Ref<EntityStore>> history = new java.util.ArrayDeque<>();
        /** Fighters already shown in this round of right clicks (identity set; pruned of the gone ones each tick). */
        final java.util.Set<Ref<EntityStore>> seen = ConcurrentHashMap.newKeySet();
        /** A 1v1 duel's two fighters, in team order, kept once seen (the fallen one leaves the battle's members). */
        List<Ref<EntityStore>> duelPair;
        /** The camera on its way to another fighter (watchTransition glide), null when it follows one. */
        volatile Glide glide;
        /** When the camera last went to another fighter (watchAutoSwitch counts from here). */
        volatile long lastSwitch = System.currentTimeMillis();
        /** The watched fighter fell at this time (0 = not): the camera stays on it FALL_HOLD_MS before moving on. */
        volatile long fellAt;
        /** Auto switch for this viewer: null = the watchAutoSwitch setting, else E turned it on or off. */
        volatile Boolean auto;
        /** Who to go to after the hold: its killer, else a replacement. */
        volatile Ref<EntityStore> fellNext;
        /** The follow camera was put on the fallen body for the hold (holdOnFall). */
        volatile boolean fallCam;
        /** The body the fall hold films: once it is removed the hold ends early (else the client flies free). */
        volatile Ref<EntityStore> fellOn;

        Watch(BattleManager.Battle battle, Store<EntityStore> store, Vector3d home, Rotation3f homeLook) {
            this.battle = battle;
            this.store = store;
            this.home = home;
            this.homeLook = homeLook;
        }
    }

    /** Watching players by UUID (never a PlayerRef: a reconnect makes a new one). */
    private static final Map<UUID, Watch> WATCHING = new ConcurrentHashMap<>();

    /**
     * The bodies of the players watching in {@code store} (WatcherHideSystem hides them from everyone else: the game
     * shows spectators to other spectators, so two players watching saw each other standing among the fighters and
     * the arrows hitting the other one, playtest 2026-10-29). Empty when nobody watches.
     */
    static List<Ref<EntityStore>> watcherBodies(Store<EntityStore> store) {
        if (WATCHING.isEmpty()) return List.of();
        List<Ref<EntityStore>> out = new ArrayList<>(WATCHING.size());
        for (Map.Entry<UUID, Watch> e : WATCHING.entrySet()) {
            if (e.getValue().store != store) continue;
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            Ref<EntityStore> r = p != null && p.isValid() ? p.getReference() : null;
            if (r != null && r.isValid() && r.getStore() == store) out.add(r);
        }
        return out;
    }

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

    /** The fighter a watcher's camera is on (null if {@code me} is not watching). */
    static Ref<EntityStore> watchedBy(Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        if (WATCHING.isEmpty() || me == null || !me.isValid()) return null;
        PlayerRef p = acc.getComponent(me, PlayerRef.getComponentType());
        Watch w = p != null ? WATCHING.get(p.getUuid()) : null;
        return w != null ? watched(w, me, acc) : null;
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
        FightCamera.handOver(player);
        // from a film: out of its game mode first, else leaving the watch would give that one back; the watch then
        // brings them back where they stood before the film, not to the film's flying body
        Boolean filmFlying = FilmMode.flyingBefore(player);
        boolean flying = filmFlying != null ? filmFlying : Flight.of(me, store);
        Vector3d filmHome = FilmMode.leaveNow(player, me, store);
        if (filmHome != null) at = filmHome;
        if (!GameModeTypes.enter(me, store, GAME_MODE)) {
            player.sendMessage(I18n.tr("battle.watch.cant.start").color(WARN));
            return;
        }
        switchTo(me, target, store);
        Watch watch = new Watch(battle, store, at, look);
        watch.flying = flying;
        WATCHING.put(player.getUuid(), watch);
        // Cutting that camera gives the player their normal camera back a moment later (and an orbit glides back
        // first), after the spectator camera was attached: the client was left flying free while the server still
        // thought it followed a fighter. Attach it again once the cut is done.
        // Always, not only when we cut one: an intro that ends right now (duels: cameraTime 0 = the countdown, so it
        // ends exactly when the battle starts and autoWatch runs) sends its own camera reset after ours.
        reattachLater(player, store, REATTACH_FIRST_MS);
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
        Ref<EntityStore> current = watched(w, me, acc);
        Ref<EntityStore> next = null;
        if (dir < 0) {
            synchronized (w.history) {
                while (!w.history.isEmpty() && next == null) {
                    Ref<EntityStore> r = w.history.removeLast();
                    if (r != current && r.isValid() && w.battle.has(r) && BattleManager.isAlive(r, acc)) next = r;
                }
            }
            if (next == null) {
                // nothing earlier: the fighter before the watched one in team order, the first wrapping to the last
                // (playtest 2026-10-17: "if I'm on the first, jump to the last of the list")
                List<Ref<EntityStore>> alive = aliveSorted(w.battle, acc);
                int at = current != null ? alive.indexOf(current) : -1;
                Ref<EntityStore> before = alive.isEmpty() ? null : alive.get(at < 0 ? alive.size() - 1 : (at - 1 + alive.size()) % alive.size());
                if (before != null && before != current) next = before;
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
        go(player, w, me, next, acc);
    }

    /** The fighter the camera is on, or on its way to. */
    private static Ref<EntityStore> watched(Watch w, Ref<EntityStore> me, ComponentAccessor<EntityStore> acc) {
        Glide g = w.glide;
        if (g != null) return g.to;
        Spectating sp = acc.getComponent(me, Spectating.getComponentType());
        return sp != null ? sp.getTargetRef() : null;
    }

    // ---- going to another fighter: cut or glide ----

    /**
     * The camera on its way from one fighter to the next (watchTransition glide, 2026-10-18, playtest: the watch cut
     * between fighters, the film can glide). Server camera frames (FightCamera.frame) every FRAME_MS: the point it
     * looks at slides from the old fighter's eyes to the new one's (both followed while they move), the distance from
     * one follow distance to the other, and the direction turns from the watcher's own to the line from where the
     * camera started to the new fighter, so it ends behind it as the follow camera would be. A server camera at a
     * set position never collides, so it goes through hills and walls. Spectating keeps the old fighter until the
     * end (putting the new one sends vanilla's follow camera at once, which would cut), then the follow camera
     * takes over with the glide's last direction.
     */
    private static final class Glide {
        final Ref<EntityStore> from;   // followed while it stands; null = start from a fixed point (a glide cut short)
        final Ref<EntityStore> to;
        final double fromDist, toDist;
        final float yaw0, pitch0, yaw1, pitch1;
        final long start, durMs;
        Vector3d fromLook, toLook;     // last known eyes of each end
        float yaw, pitch;              // last frame
        double dist;
        Vector3d look;
        /** A fall hold: a still camera on the fallen fighter until tick() goes on (it never ends by itself). */
        boolean hold;
        /** What the watcher turned with the mouse meanwhile (onMouse), on top of the glide's own direction. */
        volatile float dYaw, dPitch;

        Glide(Ref<EntityStore> from, Ref<EntityStore> to, Vector3d fromLook, Vector3d toLook, double fromDist, double toDist,
              float yaw0, float pitch0, float yaw1, float pitch1, long durMs) {
            this.from = from;
            this.to = to;
            this.fromLook = fromLook;
            this.toLook = toLook;
            this.fromDist = fromDist;
            this.toDist = toDist;
            this.yaw0 = yaw0;
            this.pitch0 = pitch0;
            this.yaw1 = yaw1;
            this.pitch1 = pitch1;
            this.start = System.currentTimeMillis();
            this.durMs = durMs;
            this.yaw = yaw0;
            this.pitch = pitch0;
            this.dist = fromDist;
            this.look = fromLook;
        }
    }

    private static final long FRAME_MS = 50;
    /** Glide time: GLIDE_BASE_MS + GLIDE_MS_PER_BLOCK per block between the two, within GLIDE_MIN_MS..GLIDE_MAX_MS. */
    private static final long GLIDE_BASE_MS = 400, GLIDE_MS_PER_BLOCK = 22, GLIDE_MIN_MS = 500, GLIDE_MAX_MS = 1600;
    /** Frames at the end spot before the follow camera takes over, so the client's smoothing catches up. */
    private static final long SETTLE_MS = 150;
    /** Client smoothing of each frame (as the drone intro). */
    private static final float GLIDE_LERP = 0.4f;
    /** The direction it ends with: not steeper than this down or up (radians). */
    private static final float PITCH_DOWN = -0.6f, PITCH_UP = 0.15f;

    /** Goes to {@code target}: a glide (watchTransition glide) or a cut. World thread. */
    private static void go(PlayerRef player, Watch w, Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        w.lastSwitch = System.currentTimeMillis();
        w.fellAt = 0;
        w.fellNext = null;
        w.fallCam = false;
        w.fellOn = null;
        if ("glide".equals(FxSettings.watchTransition) && glide(player, w, me, target, acc)) return;
        w.glide = null;
        switchTo(me, target, acc);
    }

    /** Starts a glide to {@code target} from where the camera is now; false when it can't (then go() cuts). */
    private static boolean glide(PlayerRef player, Watch w, Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        Vector3d toLook = eyesOf(target, acc);
        if (toLook == null) return false;
        Glide now = w.glide;
        Ref<EntityStore> from;
        Vector3d fromLook;
        double fromDist;
        float yaw0, pitch0;
        if (now != null) {
            // already on the way: from the spot it is at now
            from = null;
            fromLook = now.look;
            fromDist = now.dist;
            yaw0 = now.yaw;
            pitch0 = now.pitch;
        } else {
            Spectating sp = acc.getComponent(me, Spectating.getComponentType());
            from = sp != null ? sp.getTargetRef() : null;
            fromLook = from != null && from.isValid() ? eyesOf(from, acc) : null;
            TransformComponent t = acc.getComponent(me, TransformComponent.getComponentType());
            if (fromLook == null) {
                // the old fighter is gone: the watcher's body still stands where it was
                if (t == null) return false;
                fromLook = new Vector3d(t.getPosition()).add(0, 1.6, 0);
                from = null;
            }
            fromDist = from != null ? followDistance(from, acc) : 4;
            HeadRotation head = acc.getComponent(me, HeadRotation.getComponentType());
            Rotation3f r = head != null ? new Rotation3f(head.getRotation()) : t != null ? new Rotation3f(t.getRotation()) : null;
            yaw0 = r != null ? r.yaw() : 0f;
            pitch0 = r != null ? r.pitch() : -0.3f;
        }
        if (from == target) return false;
        double toDist = followDistance(target, acc);
        // where the camera is now, and the direction from there to the new fighter (kept level enough)
        Vector3d cam = behind(fromLook, yaw0, pitch0, fromDist);
        double dx = toLook.x - cam.x, dy = toLook.y - cam.y, dz = toLook.z - cam.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw1 = flat > 1 ? (float) Math.atan2(-dx, -dz) : yaw0;
        float pitch1 = flat > 1 ? (float) Math.max(PITCH_DOWN, Math.min(PITCH_UP, Math.atan2(dy, flat))) : pitch0;
        long ms = Math.max(GLIDE_MIN_MS, Math.min(GLIDE_MAX_MS, GLIDE_BASE_MS + (long) (fromLook.distance(toLook) * GLIDE_MS_PER_BLOCK)));
        Glide g = new Glide(from, target, fromLook, toLook, fromDist, toDist, yaw0, pitch0, yaw1, pitch1, ms);
        w.glide = g;
        frame(player, g, acc);
        scheduleGlide(player, w, g);
        return true;
    }

    private static void scheduleGlide(PlayerRef player, Watch w, Glide g) {
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (w.glide != g || w.store.isShutdown()) return;
            try {
                w.store.getExternalData().getWorld().execute(() -> glideStep(player, w, g));
            } catch (RuntimeException e) {
                w.glide = null;   // the world stopped
            }
        }, FRAME_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** One glide frame on the world thread; at the end the follow camera on the new fighter. */
    private static void glideStep(PlayerRef player, Watch w, Glide g) {
        if (w.glide != g || WATCHING.get(player.getUuid()) != w) return;
        Ref<EntityStore> me = player.isValid() ? player.getReference() : null;
        Store<EntityStore> store = w.store;
        if (me == null || !me.isValid() || me.getStore() != store || store.isShutdown()) {
            w.glide = null;
            return;
        }
        try {
            frame(player, g, store);
            if (g.hold) {
                // tick() moves on after FALL_HOLD_MS (a new glide or a cut replaces this one); a safety end after that
                if (System.currentTimeMillis() - g.start < FALL_HOLD_MS + HOLD_SAFETY_MS) scheduleGlide(player, w, g);
                else w.glide = null;
                return;
            }
            if (System.currentTimeMillis() - g.start < g.durMs + SETTLE_MS) {
                scheduleGlide(player, w, g);
                return;
            }
            if (g.to.isValid() && BattleManager.isAlive(g.to, store) && (w.battle.has(g.to) || w.battle.over)) {
                w.glide = null;
                var rot = new com.hypixel.hytale.protocol.Direction(g.yaw, g.pitch, 0f);
                switchTo(me, g.to, store, rot);
                followCamera(me, g.to, store, rot);
                return;
            }
            // it fell on the way: on to the next one from here
            Ref<EntityStore> next = w.battle.over ? winner(w.battle, store) : replacement(w.battle, g.to, me, store);
            if (next != null && next != g.to) go(player, w, me, next, store);
            else w.glide = null;
        } catch (RuntimeException e) {
            w.glide = null;
            MobBattlePlugin.log("Watch glide frame failed: " + e);
        }
    }

    /** Sends the glide's frame for now (and keeps it in g for a glide that starts from here). */
    private static void frame(PlayerRef player, Glide g, ComponentAccessor<EntityStore> acc) {
        double u = Math.min(1, (System.currentTimeMillis() - g.start) / (double) g.durMs);
        double e = u * u * u * (u * (u * 6 - 15) + 10);   // smootherstep
        if (g.from != null && g.from.isValid() && BattleManager.isAlive(g.from, acc)) {
            Vector3d a = eyesOf(g.from, acc);
            if (a != null) g.fromLook = a;
        }
        if (g.to.isValid()) {
            Vector3d b = eyesOf(g.to, acc);
            if (b != null) g.toLook = b;
        }
        Vector3d look = new Vector3d(g.fromLook).lerp(g.toLook, e);
        double turn = Math.atan2(Math.sin(g.yaw1 - g.yaw0), Math.cos(g.yaw1 - g.yaw0));
        g.yaw = (float) (g.yaw0 + turn * e) + g.dYaw;
        g.pitch = Math.max(MOUSE_PITCH_MIN, Math.min(MOUSE_PITCH_MAX, (float) (g.pitch0 + (g.pitch1 - g.pitch0) * e) + g.dPitch));
        g.dist = g.fromDist + (g.toDist - g.fromDist) * e;
        g.look = look;
        Vector3d cam = behind(look, g.yaw, g.pitch, clearDistance(look, g.yaw, g.pitch, g.dist, acc));
        var s = FightCamera.blurred(FightCamera.frame(cam, look, GLIDE_LERP), 0);
        // the watcher's own spectator body stays on the old fighter until the switch, and a third-person frame drew it
        // as a ghost in front of the camera (playtest 2026-10-28); a first-person frame does not draw the local body
        s.isFirstPerson = true;
        // the client reports mouse moves while this camera holds (onMouse), so the watcher still turns around the
        // fighter during a glide or a fall hold (2026-10-29, playtest: the mouse froze there), as the game's CameraDemo does
        s.sendMouseMotion = true;
        // ❓ the client sent nothing with FightCamera.frame's None target and a hidden cursor (2026-10-29, playtest test);
        // the game's CameraDemo leaves the target at Any and shows the cursor: both tried from /mobbattle test watchmouse
        s.mouseInputTargetType = mouseTarget;
        s.displayCursor = mouseCursor;
        FightCamera.send(player, s);
    }

    /** Test knobs for the glide's mouse (/mobbattle test watchmouse): what the camera asks the client for. */
    static volatile com.hypixel.hytale.protocol.MouseInputTargetType mouseTarget = com.hypixel.hytale.protocol.MouseInputTargetType.Any;
    // ✅ 2026-10-29: the client only sends mouse motion with the cursor shown; off by default (playtest: the cursor in the
    // watch is a bother), so glides turn by themselves; the fall hold uses the game's follow camera instead
    static volatile boolean mouseCursor = false;
    static volatile float mouseTurn = 0.004f;
    /** Mouse motion events seen (any player), and the last one's motion: tells whether the client sends any. */
    static final java.util.concurrent.atomic.AtomicLong MOUSE_EVENTS = new java.util.concurrent.atomic.AtomicLong();
    static volatile String lastMouse = "-";

    private static final float MOUSE_PITCH_MIN = -1.2f, MOUSE_PITCH_MAX = 0.8f;

    /**
     * PlayerMouseMotionEvent (world thread, InteractionModule.doMouseInteraction): a watcher whose camera the mod holds
     * (glide or fall hold) turns it; the next frame adds the turn. Nothing to do for anyone else.
     */
    static void onMouse(com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent event) {
        var motion = event.getMouseMotion();
        if (motion == null || motion.relativeMotion == null) return;
        MOUSE_EVENTS.incrementAndGet();
        lastMouse = motion.relativeMotion.x + "," + motion.relativeMotion.y;
        if (WATCHING.isEmpty()) return;
        Ref<EntityStore> ref = event.getPlayerRef();
        if (ref == null || !ref.isValid()) return;
        PlayerRef p = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
        Watch w = p != null ? WATCHING.get(p.getUuid()) : null;
        Glide g = w != null ? w.glide : null;
        if (g == null) return;
        g.dYaw -= motion.relativeMotion.x * mouseTurn;
        g.dPitch = Math.max(-2f, Math.min(2f, g.dPitch - motion.relativeMotion.y * mouseTurn));
    }

    /** The camera spot {@code dist} back from {@code look} along the direction yaw/pitch (the game's convention). */
    private static Vector3d behind(Vector3d look, float yaw, float pitch, double dist) {
        double c = Math.cos(pitch);
        return new Vector3d(look.x + Math.sin(yaw) * c * dist, look.y - Math.sin(pitch) * dist, look.z + Math.cos(yaw) * c * dist);
    }

    /**
     * How far back from {@code look} the camera can sit before a block (as the follow camera's DistanceOffsetRaycast):
     * the glide and the fall hold went through walls (playtest 2026-10-29). Steps of 0.25 from 0.5 blocks out.
     */
    private static double clearDistance(Vector3d look, float yaw, float pitch, double dist, ComponentAccessor<EntityStore> acc) {
        for (double d = 0.5; d <= dist; d += 0.25) {
            Vector3d q = behind(look, yaw, pitch, d);
            if (FightFx.solidAt((int) Math.floor(q.x), (int) Math.floor(q.y), (int) Math.floor(q.z), acc)) {
                return Math.max(0.5, d - 0.35);
            }
        }
        return dist;
    }

    /** Where the follow camera looks on {@code r}: its eyes, at least 1 block up (as followCamera). */
    private static Vector3d eyesOf(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        Vector3d p = ChaosWand.positionOf(r, acc);
        if (p == null) return null;
        float eye = com.hypixel.hytale.server.core.modules.entity.component.ModelComponent.getEyeHeight(r, acc);
        return new Vector3d(p).add(0, Math.max(1, eye), 0);
    }

    /** First try: a camera reset already queued on the world thread lands within a tick or two. */
    private static final long REATTACH_FIRST_MS = 150;

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
     *. Calls vanilla's private applyFollowCamera instead.
     */
    private static void reattachLater(PlayerRef player, Store<EntityStore> store, long delayMs) {
        if (store.isShutdown()) return;
        store.getExternalData().getWorld().scheduleAfter(() -> {
            if (store.isShutdown()) return;
            Ref<EntityStore> me = player.isValid() ? player.getReference() : null;
            Watch w = WATCHING.get(player.getUuid());
            if (me == null || !me.isValid() || me.getStore() != store || w == null || w.glide != null) return;
            Spectating sp = store.getComponent(me, Spectating.getComponentType());
            Ref<EntityStore> target = sp != null ? sp.getTargetRef() : null;
            if (target != null && target.isValid()) followCamera(me, target, store);
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Watches {@code target}: the vanilla Spectating component (its OnSpectatingChange sends a follow camera 4 blocks
     * back, whatever the target's size), then our sized follow camera a tick later, which replaces it.
     */
    private static void switchTo(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        switchTo(me, target, acc, null);
    }

    /** As above, the follow camera looking along {@code rot} (null = the watcher's own rotation). */
    private static void switchTo(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc,
                                 com.hypixel.hytale.protocol.Direction rot) {
        acc.putComponent(me, Spectating.getComponentType(), new Spectating(target));
        var store = me.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !me.isValid() || !target.isValid()) return;
            Spectating sp = store.getComponent(me, Spectating.getComponentType());
            if (sp != null && sp.getTargetRef() == target) followCamera(me, target, store, rot);
        });
    }

    /**
     * The follow camera on {@code target}, as vanilla SpectatorSystems.OnSpectatingChange.applyFollowCamera (javap,
     * 0.7.0: attached third person at the eyes, raycast distance, the watcher's own rotation, raised to 1 block for
     * tiny eyes) but sized: vanilla keeps 4 blocks for anything, so a giant boss filled the screen with the camera
     * inside it (playtest 2026-10-13). Distance = its half width + 1.1 x its hitbox height (a size buff grows the
     * hitbox), at least 4. Also used to re-attach after an intro reset. World thread.
     */
    static void followCamera(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        followCamera(me, target, acc, null);
    }

    /** As above, looking along {@code rot} (null = the watcher's own rotation; a glide passes its last direction). */
    static void followCamera(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc,
                             com.hypixel.hytale.protocol.Direction rot) {
        PlayerRef player = acc.getComponent(me, PlayerRef.getComponentType());
        var net = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType());
        if (player == null || net == null) return;
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
        // where the watcher looks now (HeadRotation follows the orbit): a re-attach used the body's rotation and turned
        // the view by itself a moment after each switch (2026-10-29, playtest video)
        var head = acc.getComponent(me, HeadRotation.getComponentType());
        if (rot != null) {
            s.rotation = rot;
        } else if (head != null) {
            var r = new Rotation3f(head.getRotation());
            s.rotation = new com.hypixel.hytale.protocol.Direction(r.yaw(), r.pitch(), 0f);
        } else if (mine != null) {
            var r = mine.getRotation();
            s.rotation = new com.hypixel.hytale.protocol.Direction(r.yaw(), r.pitch(), r.roll());
        }
        // the body goes to the fighter (as vanilla applyFollowCamera) but keeps the look above: vanilla teleports with
        // the TARGET's rotation, which turned the watcher's view to where the mob faced on every re-attach (2026-10-29,
        // playtest video: the view swung round by itself)
        var transform = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (transform != null && bodyMustMove(me, target, acc)) {
            Rotation3f look = s.rotation != null ? new Rotation3f(s.rotation.pitch, s.rotation.yaw, 0f) : transform.getRotation();
            // Projectiles collide with every player body, spectators too (EntityCollisionProvider takes the player
            // spatial set, Intangible or not): arrows at the watched fighter hit the watcher's invisible body there.
            // Lifting the body made the camera jump (every player teleport resets it, and the spectating client moves
            // its body back), so PlayerBattleDamageSystem hands such a hit to the watched fighter instead.
            acc.putComponent(me, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                    com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createForPlayer(transform.getPosition(), look));
        }
        player.getPacketHandler().writeNoCache(new com.hypixel.hytale.protocol.packets.camera.SetServerCamera(
                com.hypixel.hytale.protocol.ClientCameraView.Custom, true, s));
    }

    /**
     * Whether followCamera has to move the watcher's body: only when far from the fighter (chunks, as vanilla
     * FollowTarget's 32 blocks). Every player teleport resets the client's camera a moment (2026-10-29, playtest video).
     */
    private static boolean bodyMustMove(Ref<EntityStore> me, Ref<EntityStore> target, ComponentAccessor<EntityStore> acc) {
        var mine = acc.getComponent(me, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        var theirs = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType());
        if (mine == null || theirs == null) return true;
        var bb = acc.getComponent(target, com.hypixel.hytale.server.core.modules.entity.component.BoundingBox.getComponentType());
        var box = bb != null ? bb.getBoundingBox() : null;
        double top = box != null ? box.max.y : 2, half = box != null ? Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2 : 0.5;
        var a = mine.getPosition();
        var b = theirs.getPosition();
        return a.distanceSquared(b) > 24 * 24;
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
            Flight.restoreLater(me, w.store, w.flying);
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

    /** When the follow camera goes back on a fallen body (ms after its death). */
    private static final long[] FALL_CAM_AT_MS = {100, 400};

    /** A fall hold that tick() never replaced ends by itself after FALL_HOLD_MS + this. */
    private static final long HOLD_SAFETY_MS = 3000;

    /**
     * The watched fighter fell: the camera stays on the body for the fall hold (2026-10-29, playtest: vanilla's FollowTarget
     * lets go of a dead target, empty Spectating = free camera, so the hold showed the watcher's own free-fly camera).
     * The game's follow camera goes back on the body (it is still there through its death animation) right after
     * vanilla's reset, so the mouse turns it and it keeps out of blocks as the normal watch does (playtest: our own camera
     * needed a visible cursor for the mouse and went through blocks). A glide on its way to it keeps going instead.
     * Fallback with no body to attach to: a still glide on its last spot.
     */
    private static void holdOnFall(PlayerRef player, Watch w, Ref<EntityStore> me, Ref<EntityStore> victim,
                                   ComponentAccessor<EntityStore> acc) {
        Glide now = w.glide;
        if (now != null && now.hold || w.fallCam) return;
        if (now == null && victim != null && victim.isValid()
                && acc.getComponent(victim, com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType()) != null) {
            w.fallCam = true;
            w.fellOn = victim;
            HeadRotation head = acc.getComponent(me, HeadRotation.getComponentType());
            Rotation3f r = head != null ? new Rotation3f(head.getRotation()) : null;
            var rot = r != null ? new com.hypixel.hytale.protocol.Direction(r.yaw(), r.pitch(), 0f) : null;
            Store<EntityStore> store = me.getStore();
            // after vanilla's detach (next tick) and once more in case its reset reaches the client late
            for (long ms : FALL_CAM_AT_MS) {
                store.getExternalData().getWorld().scheduleAfter(() -> {
                    if (store.isShutdown() || !me.isValid() || !victim.isValid() || WATCHING.get(player.getUuid()) != w
                            || !w.fallCam || w.glide != null) return;
                    followCamera(me, victim, store, rot);
                }, ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return;
        }
        Vector3d look = now != null ? now.look : victim != null && victim.isValid() ? eyesOf(victim, acc) : null;
        if (look == null) return;
        float yaw, pitch;
        double dist;
        if (now != null) {
            // it fell while the camera was on its way to it: hold from where the glide is
            yaw = now.yaw;
            pitch = now.pitch;
            dist = now.dist;
        } else {
            HeadRotation head = acc.getComponent(me, HeadRotation.getComponentType());
            Rotation3f r = head != null ? new Rotation3f(head.getRotation()) : null;
            yaw = r != null ? r.yaw() : 0f;
            pitch = r != null ? r.pitch() : -0.3f;
            dist = followDistance(victim, acc);
        }
        Glide g = new Glide(null, victim != null ? victim : me, look, look, dist, dist, yaw, pitch, yaw, pitch, FALL_HOLD_MS);
        g.hold = true;
        w.glide = g;
        frame(player, g, acc);
        scheduleGlide(player, w, g);
    }

    /** KillTracker: the camera of whoever watched {@code victim} goes to its {@code killer}. */
    static void onKill(Ref<EntityStore> victim, Ref<EntityStore> killer, ComponentAccessor<EntityStore> acc) {
        if (WATCHING.isEmpty()) return;
        // no known killer (or one in another world): still hold on the body, then tick picks a replacement
        if (killer != null && (!killer.isValid() || killer.getStore() != victim.getStore())) killer = null;
        for (UUID id : WATCHING.keySet()) {
            PlayerRef p = Universe.get().getPlayer(id);
            Ref<EntityStore> ref = p != null && p.isValid() ? p.getReference() : null;
            if (ref == null || !ref.isValid() || ref.getStore() != victim.getStore()) continue;
            Watch w = WATCHING.get(id);
            // stays on the fallen fighter a moment (2026-10-29, playtest: it cut away the instant the fighter died, so
            // the fall was never seen); tick goes to the killer after FALL_HOLD_MS
            if (w != null && watched(w, ref, acc) == victim) {
                if (w.fellAt == 0) w.fellAt = System.currentTimeMillis();
                w.fellNext = killer;
                holdOnFall(p, w, ref, victim, acc);
            }
        }
    }

    /**
     * WatchDetachSystem: vanilla let go of the watched fighter (FollowTarget empties Spectating when its target dies)
     * and its reset put the watcher in first person. The follow camera goes back on the body in the same tick, after
     * that reset (2026-10-29, playtest: a tick of first person every time the watched mob died; FALL_CAM_AT_MS came 100 ms
     * late). Starts the fall hold if KillTracker has not yet.
     */
    static void detached(Ref<EntityStore> me, Ref<EntityStore> lost, ComponentAccessor<EntityStore> acc) {
        if (WATCHING.isEmpty() || lost == null || !lost.isValid() || lost.getStore() != me.getStore()) return;
        PlayerRef p = acc.getComponent(me, PlayerRef.getComponentType());
        Watch w = p != null ? WATCHING.get(p.getUuid()) : null;
        if (w == null || w.glide != null) return;
        if (!w.fallCam) {
            if (w.fellAt == 0) w.fellAt = System.currentTimeMillis();
            holdOnFall(p, w, me, lost, acc);
        }
        if (w.fallCam && w.fellOn == lost) {
            HeadRotation head = acc.getComponent(me, HeadRotation.getComponentType());
            Rotation3f r = head != null ? new Rotation3f(head.getRotation()) : null;
            followCamera(me, lost, acc, r != null ? new com.hypixel.hytale.protocol.Direction(r.yaw(), r.pitch(), 0f) : null);
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
            Ref<EntityStore> target = watched(w, me, store);
            if (w.battle.over) {
                if (w.endsAt == 0) {
                    w.endsAt = now + END_MS;
                    Ref<EntityStore> winner = winner(w.battle, store);
                    if (winner != null && winner != target) {
                        go(p, w, me, winner, store);
                        target = winner;
                    }
                } else if (now >= w.endsAt) {
                    exit(p, me, store);
                    continue;
                }
            } else if (target == null || !target.isValid() || !w.battle.has(target) || !BattleManager.isAlive(target, store)) {
                if (w.fellAt == 0) w.fellAt = now;
                // a body removed before the hold ends (death looks, quick despawns) leaves the follow camera with
                // nothing: the client flew free until the hold ran out (2026-10-29, playtest video), so move on at once
                Ref<EntityStore> body = w.fellOn;
                boolean bodyGone = w.fallCam && (body == null || !body.isValid());
                if (now - w.fellAt < FALL_HOLD_MS && !bodyGone) {
                    // fell with no killer (KillTracker never called onKill): hold here too
                    if (!w.fallCam && (w.glide == null || !w.glide.hold)) holdOnFall(p, w, me, target, store);
                    continue;
                }
                Ref<EntityStore> killer = w.fellNext;
                Ref<EntityStore> next = killer != null && killer.isValid() && killer.getStore() == store && w.battle.has(killer)
                        && BattleManager.isAlive(killer, store) ? killer : replacement(w.battle, target, me, store);
                if (next != null) {
                    go(p, w, me, next, store);
                    target = next;
                }
            } else if (autoSeconds(w) > 0 && w.glide == null
                    && now - w.lastSwitch >= autoSeconds(w) * 1000L) {
                // watchAutoSwitch (2026-10-18): on to the next fighter by itself, as a right click
                w.lastSwitch = now;
                cycle(p, me, store, 1);
                target = watched(w, me, store);
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
            for (String id : new String[] {"#Attack", "#Defense", "#Size"}) ui.set(id + ".Visible", true);
            DuelCard.bodyHeight(ui, WATCH_BODY);
            hud.update(false, ui);
            return;
        }
        boolean up = BattleManager.isAlive(mob, store);
        String name = ChaosWand.nameOf(mob, store);
        int team = b.team(mob);
        line(ui, "#Name", DuelCard.shorten(MobNames.text(name, player)),
                b.isTeams() ? b.color(team) : up ? "#ffd23e" : "#888888");
        line(ui, "#Side", sideText(b, team), b.isTeams() ? b.color(team) : "#96a9be");
        line(ui, "#Health", DuelCard.healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
        // only the stats that differ from the mob's own (2026-10-30, as the duel card)
        DuelCard.bodyHeight(ui, WATCH_BODY - DuelCard.statLines(ui, "", mob, store) * DuelCard.LINE_HEIGHT);
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
        int hidden = 0;
        for (int i = 0; i < 2; i++) {
            Ref<EntityStore> mob = pair.get(i);
            String side = sides[i];
            boolean up = mob.isValid() && BattleManager.isAlive(mob, store);
            boolean on = mob == watched;
            String name = mob.isValid() ? ChaosWand.nameOf(mob, store) : null;
            line(ui, "#Name" + side, DuelCard.shorten(MobNames.text(name, hud.getPlayerRef())),
                    up ? (on ? "#ffd23e" : "#e8eef5") : "#888888");
            line(ui, "#Tag" + side, on ? I18n.tr("hud.watching.tag") : Message.empty(), "#ffd23e");
            line(ui, "#Health" + side, DuelCard.healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
            hidden += DuelCard.statLines(ui, side, mob, store);
        }
        DuelCard.bodyHeight(ui, WATCH_DUEL_BODY - hidden * DuelCard.LINE_HEIGHT);
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
