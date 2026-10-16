package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * /mobbattle film (2026-10-16, user: a camera like the AFK Experience mod's that films the whole battle while it runs,
 * slow and smooth, instead of watch's jumps from mob to mob): the player's camera becomes a film crew for the rest of
 * the battle. Long shots ({@code filmShotTime}, default 8 s, {@code filmShotVariation} % longer or shorter at random)
 * of slow camera moves around a smoothed point of the fight
 * (the living fighters' center, leaning toward the last hit or the top damage dealer, eased frame by frame so it
 * never jumps to one mob): a wide pan, a low tracking shot, an arc, a push-in on the action and a landscape pan that
 * ends on the battle, and {@code filmPlayerShots} % of shots that show the player watching (over their shoulder, the
 * battle in the background, or close on them with the battle blurred behind). Uses the intro cameras' film bands
 * (cameraBars), background blur (cameraBlur) and lens (baseFov), and the drone's terrain checks so the camera stays
 * out of hills, trees and roofs.
 * <p>
 * Server camera frames every 50 ms as FightCamera's shots (it owns the player's camera through FightCamera.claim,
 * so crouching, /mobbattle skipCamera, /mobbattle stop and a newer camera end it). The player can't move meanwhile.
 * When the battle ends it hands over to the end camera if that one starts, else glides back to the player.
 * {@code autoFilm} (per kind, default off) starts it for the camera viewers once the intro is over, and wins over
 * autoWatch.
 */
final class FilmCamera {

    private static final long STEP_MS = 50;
    /** Frames between two reads of where the fighters are (every 250 ms; the eased point moves every frame). */
    private static final int FOCUS_EVERY = 5;
    /** The footprint's radius covers this share of the fighters, so one straggler doesn't blow the frame up. */
    private static final double SPREAD_SHARE = 0.85;
    /** A close-up's subject: share of the gap to where it really is closed per frame. */
    private static final double STAR_EASE = 0.15;
    /**
     * Ground this many blocks above the fight counts as stands, a wall or a hill to step off (STANDS_STEP blocks at a
     * time toward the fight), unless the camera is STANDS_CLEAR above it already.
     */
    private static final double STANDS_RISE = 4;
    private static final double STANDS_CLEAR = 3;
    private static final double STANDS_STEP = 1.5;
    /** Blocked view: checked every PULL_EVERY frames, the camera pulled toward the fight PULL_STEP of the way at a time, at most PULL_MAX. */
    private static final int PULL_EVERY = 5;
    private static final double PULL_STEP = 0.1;
    private static final double PULL_MAX = 0.8;
    private static final double PULL_EASE = 0.2;
    /** Close-ups pick a fighter hit this recently first (a fresh exchange of hits). */
    private static final long FRESH_HIT_MS = 2000;
    /** Most fighters read for the center (an even spread of them). */
    private static final int FOCUS_SAMPLE = 48;
    /** Share of the gap to the read point closed per frame: the camera's aim drifts, never jumps. */
    private static final double FOCUS_EASE = 0.05;
    private static final double SPREAD_EASE = 0.04;
    /** How much the aim leans toward the action (last hit or top damage dealer). */
    private static final double HOT_SHARE = 0.3;
    /** A hit this recent pulls the aim toward its victim. */
    private static final long HOT_HIT_MS = 3000;
    /** Client glide between frames, and the first frame of a shot cuts (1 = at once). */
    private static final float LERP = 0.12f;
    private static final float CUT = 1f;
    /** filmTransition glide: how long and how slowly the camera travels from one shot to the next. */
    private static final long GLIDE_MS = 1500;
    private static final float GLIDE_LERP = 0.04f;
    /** Camera height easing over ground and tree tops (as the drone): climbs fast, sinks slowly. */
    private static final double CLIMB = 0.3;
    private static final double SINK = 0.06;
    /** After the battle ends: time for the end camera to take over before the film glides back itself. */
    private static final long END_HOLD_MS = 400;
    /** filmWideShots' far shots stand this many times further back than a normal wide shot. */
    private static final double FAR = 1.7;
    /** A fighter at least this tall (blocks) adds BIG_MOB_WIDE_BONUS percent to filmWideShots. */
    private static final double BIG_MOB = 4;
    private static final int BIG_MOB_WIDE_BONUS = 15;
    /** autoFilm: waits at most this long for the viewer's intro to end. */
    private static final long WAIT_MS = 120_000;
    private static final long WAIT_POLL_MS = 250;
    /** Angles tried around the battle for a shot with a clear view (30 degrees apart). */
    private static final int ANGLE_TRIES = 12;
    private static final String WARN = "#ffcc55";

    /** Shot types; {@code word}: its name in /mobbattle filmShots; {@code player}: shows the viewer watching. */
    private enum Type {
        WIDE_PAN("wide", false), TRACK("track", false), ARC("arc", false), PUSH_IN("pushin", false),
        CLOSE_UP("closeup", false), LANDSCAPE("landscape", false), PLAYER_BACK("playerback", true),
        PLAYER_CLOSE("playerclose", true),
        // 2026-10-16, user: shots after AFK Experience's scenes (zenith, spiral, telephoto, descent, groundrise,
        // profile), off by default (filmWeight 0).
        ZENITH("zenith", false), SPIRAL("spiral", false), TELEPHOTO("telephoto", false), DESCENT("descent", false),
        GROUNDRISE("groundrise", false), PROFILE("profile", false);

        final String word;
        final boolean player;

        Type(String word, boolean player) {
            this.word = word;
            this.player = player;
        }
    }

    /** Every shot word, for usage messages and autocomplete. */
    static final String[] SHOT_WORDS = java.util.Arrays.stream(Type.values()).map(t -> t.word).toArray(String[]::new);

    /**
     * /mobbattle filmShots: a comma (or plus) list of shot words, "all", or "battle" (every shot but the player ones),
     * as the canonical list in Type order; null if a word is unknown or none is left.
     */
    static String parseShots(String raw) {
        if (raw == null || raw.isBlank()) return null;
        java.util.Set<Type> picked = java.util.EnumSet.noneOf(Type.class);
        for (String w : raw.toLowerCase(java.util.Locale.ROOT).split("[,+\\s]+")) {
            if (w.isEmpty()) continue;
            if (w.equals("all")) {
                picked.addAll(java.util.EnumSet.allOf(Type.class));
                continue;
            }
            if (w.equals("battle")) {
                for (Type t : Type.values()) if (!t.player) picked.add(t);
                continue;
            }
            Type found = null;
            for (Type t : Type.values()) if (t.word.equals(w)) found = t;
            if (found == null) return null;
            picked.add(found);
        }
        if (picked.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Type t : picked) sb.append(sb.length() > 0 ? "," : "").append(t.word);
        return sb.toString();
    }

    /** The shot types the film may use now: a filmWeight above 0 (/mobbattle filmShot <shot> <percent>). */
    private static java.util.Set<Type> allowed() {
        java.util.Set<Type> out = java.util.EnumSet.noneOf(Type.class);
        for (Type t : Type.values()) if (FxSettings.filmWeight(t.word) > 0) out.add(t);
        return out;
    }

    /** One shot: its type, length, angle around the battle, side it moves to, and raised (no clear view found low). */
    private record Plan(Type type, long ms, double angle, double side, boolean high) {
    }

    /** Where the camera is and looks {@code e} (eased) into a shot; clearance = least height over ground/leaves. */
    private record Pose(Vector3d at, Vector3d look, double fov, double clearance) {
    }

    private static final class Film {
        final BattleManager.Battle battle;
        final Store<EntityStore> store;
        final World world;
        final PlayerRef player;
        final long id;
        final Random rnd = new Random();
        /** The aim (eased), the action point (eased), the fighters' spread (eased) and what they ease toward. */
        Vector3d focus, hot, aimFocus, aimHot;
        double spread = 6, aimSpread = 6;
        /**
         * The tallest fighter's hitbox height and the widest one's half width (eased, and what they ease toward): the
         * battle shots back off and aim higher for big mobs (2026-10-16, user: a general shot ended inside a boss).
         */
        double tall = 2, aimTall = 2, wide = 0.5, aimWide = 0.5;
        /** The current shot is one of filmWideShots' far ones: it stands FAR times further back. */
        boolean far;
        /** Fighters standing at the last read. */
        int count = 2;
        /** The close-up's subject, its eased position and its hitbox height. */
        Ref<EntityStore> star;
        Vector3d starPos;
        /** How far along its line of sight the camera is pulled toward the look point to see past a roof (0..PULL_MAX). */
        double pull, pullWant;
        /** PROFILE: the star's opponent and its eased position. */
        Ref<EntityStore> partner;
        Vector3d partnerPos;
        /** Where the close-up looks at the star's face from, kept clear of walls and other mobs. */
        final FightCamera.FaceAim starAim = new FightCamera.FaceAim();
        double starHeight = 2;
        int frame;
        Plan plan;
        long t;
        Type last;
        int shots;
        boolean playerNext;
        double camY = Double.NaN;
        final double[] roof = {Double.NaN};
        long overAt;

        Film(BattleManager.Battle battle, Store<EntityStore> store, PlayerRef player, long id) {
            this.battle = battle;
            this.store = store;
            this.world = store.getExternalData().getWorld();
            this.player = player;
            this.id = id;
        }
    }

    /** Filming players by UUID (a reconnect makes a new PlayerRef). */
    private static final Map<UUID, Film> FILMS = new ConcurrentHashMap<>();

    private FilmCamera() {
    }

    static boolean isFilming(PlayerRef player) {
        if (player == null || FILMS.isEmpty()) return false;
        Film f = FILMS.get(player.getUuid());
        return f != null && FightCamera.owns(player.getUuid(), f.id);
    }

    /** /mobbattle film (world thread): the player's own battle in this world, else the nearest one. */
    static void start(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        if (isFilming(player)) {
            player.sendMessage(I18n.tr("battle.film.already").color(WARN));
            return;
        }
        String why = cantFilm(player, me, store);
        if (why != null) {
            player.sendMessage(I18n.tr(why).color(WARN));
            return;
        }
        TransformComponent t = store.getComponent(me, TransformComponent.getComponentType());
        if (t == null) return;
        Vector3d at = new Vector3d(t.getPosition());
        BattleManager.Battle battle = null;
        double best = Double.MAX_VALUE;
        for (BattleManager.Battle b : BattleManager.battles()) {
            if (b.over || b.store != store) continue;
            double d = nearestSq(b, at, store);
            if (d == Double.MAX_VALUE) continue;
            // Your own battle first, whatever the distance.
            if (b.owner() != null && b.owner().getUuid().equals(player.getUuid())) d -= 1e12;
            if (d < best) {
                best = d;
                battle = b;
            }
        }
        if (battle == null) {
            player.sendMessage(I18n.tr("battle.film.none").color(WARN));
            return;
        }
        begin(player, me, store, battle, true);
    }

    /** /mobbattle film exit: ends this player's film (glide back to them). */
    static void exit(PlayerRef player) {
        if (!isFilming(player)) {
            player.sendMessage(I18n.tr("battle.film.not").color(WARN));
            return;
        }
        FightCamera.cancel(player);
        player.sendMessage(I18n.tr("battle.film.stopped").color("#aaaaaa"));
    }

    /** Why this player can't get a film now (a lang key), or null. */
    private static String cantFilm(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        if (BattleManager.isPlayerFighter(me)) return "battle.film.fighting";
        if (BattleWatch.isWatching(player)) return "battle.film.watching";
        if (Spectating.isSpectating(me, store)) return "battle.watch.spectating";
        if (!BattleManager.isAlive(me, store)) return "battle.film.cant";
        return null;
    }

    /**
     * /mobbattle autoFilm: a battle just started (Arena.setup, after its countdown). Its camera viewers get the film
     * once their intro (if any) is over, unless they fight, watch or spectate.
     */
    static void auto(BattleManager.Battle battle) {
        if (battle == null || battle.store == null || !KindFx.autoFilm(battle.kind)) return;
        Store<EntityStore> store = battle.store;
        store.getExternalData().getWorld().execute(() -> {
            if (battle.over || store.isShutdown()) return;
            for (PlayerRef p : FightFx.cameraViewers(battle)) {
                Ref<EntityStore> me = p.isValid() ? p.getReference() : null;
                if (me == null || !me.isValid() || me.getStore() != store || battle.has(me) || isFilming(p)) continue;
                afterIntro(p, battle, System.currentTimeMillis() + WAIT_MS);
            }
        });
    }

    /** Starts the film once this viewer's intro or orbit is over (polled), while the battle runs. */
    private static void afterIntro(PlayerRef p, BattleManager.Battle battle, long deadline) {
        if (battle.over || !p.isValid() || System.currentTimeMillis() > deadline) return;
        if (FightCamera.busy(p)) {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> afterIntro(p, battle, deadline), WAIT_POLL_MS, TimeUnit.MILLISECONDS);
            return;
        }
        Store<EntityStore> store = battle.store;
        if (store == null || store.isShutdown()) return;
        store.getExternalData().getWorld().execute(() -> {
            Ref<EntityStore> me = p.isValid() ? p.getReference() : null;
            if (battle.over || me == null || !me.isValid() || me.getStore() != store || isFilming(p)
                    || FightCamera.busy(p) || cantFilm(p, me, store) != null) return;
            begin(p, me, store, battle, false);
        });
    }

    /** Takes the player's camera for this battle (world thread). */
    private static void begin(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store, BattleManager.Battle battle,
                              boolean tell) {
        try {
            long id = FightCamera.claim(player);
            Film f = new Film(battle, store, player, id);
            if (!readFocus(f)) {
                FightCamera.release(player.getUuid(), id);
                player.sendMessage(I18n.tr("battle.film.none").color(WARN));
                return;
            }
            f.focus = new Vector3d(f.aimFocus);
            f.hot = new Vector3d(f.aimHot);
            f.spread = f.aimSpread;
            f.tall = f.aimTall;
            f.wide = f.aimWide;
            FILMS.put(player.getUuid(), f);
            CinemaBars.in(player, KindFx.cameraBars(battle.kind));
            CameraHud.hide(player);
            FightCamera.watchSkip(player, f.world);
            if (tell) player.sendMessage(I18n.tr("battle.film.help").color("#ffd23e"));
            MobBattlePlugin.debug("film: " + player.getUsername() + " films battle #" + battle.id.toString().substring(0, 8));
            next(f, 0);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Battle film failed: " + e);
            FightCamera.reset(player);
        }
    }

    /** One frame (world thread), then the next one is scheduled. Never lets a problem reach the battle. */
    private static void run(Film f) {
        UUID uuid = f.player.getUuid();
        try {
            if (!FightCamera.owns(uuid, f.id)) {
                FILMS.remove(uuid, f);
                return;
            }
            if (!FightCamera.viewerIn(f.player, f.world)) {
                FightCamera.release(uuid, f.id);
                CinemaBars.out(f.player);
                FILMS.remove(uuid, f);
                return;
            }
            boolean over = f.battle.over || f.store.isShutdown();
            if (!over && f.frame % FOCUS_EVERY == 0 && !readFocus(f)) over = true;
            if (over) {
                long now = System.currentTimeMillis();
                if (f.overAt == 0) f.overAt = now;
                // The end camera (endCamera) takes the camera over by itself; without one, glide back.
                if (now - f.overAt >= END_HOLD_MS) {
                    FILMS.remove(uuid, f);
                    FightCamera.glideBack(f.player, f.id);
                    return;
                }
            } else {
                ease(f);
                // A close-up whose fighter fell or left ends now.
                if (f.plan != null && f.plan.type() == Type.CLOSE_UP && !starAlive(f)) f.t = f.plan.ms();
                if (f.plan == null || f.t >= f.plan.ms()) nextPlan(f);
                ServerCameraSettings s = shot(f);
                if (s != null) FightCamera.send(f.player, FightCamera.blurred(s, FightCamera.blurOf(KindFx.cameraBlur(f.battle.kind))));
                f.t += STEP_MS;
                f.frame++;
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Battle film frame failed: " + e);
        }
        next(f, STEP_MS);
    }

    /**
     * The next frame, on the world thread. Escape in single player opens the pause menu and pauses the world: the
     * film ends at once and the player gets their camera back (2026-10-16, user: as AFK Experience does; it reads
     * World.isPaused too). A dedicated server never pauses, so there Escape keeps the film (crouch ends it).
     */
    private static void next(Film f, long delayMs) {
        FightCamera.schedule(f.player.getUuid(), f.id, delayMs, () -> {
            if (f.world.isPaused()) {
                FILMS.remove(f.player.getUuid(), f);
                if (f.player.isValid()) {
                    // Now, not when the world resumes: the game's own view behind the pause menu.
                    f.player.getPacketHandler().writeNoCache(new com.hypixel.hytale.protocol.packets.camera.SetServerCamera(
                            com.hypixel.hytale.protocol.ClientCameraView.Custom, false, null));
                }
                FightCamera.cancel(f.player);
                return;
            }
            f.world.execute(() -> run(f));
        });
    }

    // ---- where to look ----

    /**
     * Reads the fight's footprint (every FOCUS_EVERY frames): how many fighters stand, their center without the
     * stragglers (the mean of an even sample of at most FOCUS_SAMPLE, then again over the ones inside the
     * SPREAD_SHARE radius) and that radius (the spread). The aim is that center leaning HOT_SHARE toward the action
     * point (the last hit's victim if recent, else the top damage dealer standing). False when nobody is left standing.
     */
    private static boolean readFocus(Film f) {
        Store<EntityStore> store = f.store;
        List<Ref<EntityStore>> alive = new ArrayList<>();
        for (Ref<EntityStore> r : f.battle.members()) {
            if (r != null && r.isValid() && r.getStore() == store && BattleManager.isAlive(r, store)) alive.add(r);
        }
        if (alive.isEmpty()) return false;
        int stride = Math.max(1, alive.size() / FOCUS_SAMPLE);
        List<Vector3d> pts = new ArrayList<>();
        double tall = 0, wide = 0;
        for (int i = 0; i < alive.size(); i += stride) {
            Vector3d p = ChaosWand.positionOf(alive.get(i), store);
            if (p != null) pts.add(p);
            double[] size = sizeOf(alive.get(i), store);
            tall = Math.max(tall, size[0]);
            wide = Math.max(wide, size[1]);
        }
        if (pts.isEmpty()) return false;
        Vector3d c = mean(pts);
        double[] dist = new double[pts.size()];
        for (int i = 0; i < dist.length; i++) dist[i] = Math.hypot(pts.get(i).x - c.x, pts.get(i).z - c.z);
        double[] sorted = dist.clone();
        java.util.Arrays.sort(sorted);
        double radius = sorted[(int) Math.min(sorted.length - 1, Math.ceil(SPREAD_SHARE * (sorted.length - 1)))];
        // The center again without the stragglers past that radius.
        List<Vector3d> core = new ArrayList<>();
        for (int i = 0; i < dist.length; i++) if (dist[i] <= radius + 0.01) core.add(pts.get(i));
        if (!core.isEmpty()) c = mean(core);
        double spread = Math.max(1.5, Math.min(80, radius));
        Vector3d hot = hotSpot(f, store);
        if (hot == null || Math.hypot(hot.x - c.x, hot.z - c.z) > spread * 2 + 6) hot = c;
        f.count = alive.size();
        f.aimHot = hot;
        f.aimFocus = new Vector3d(c.x + (hot.x - c.x) * HOT_SHARE, c.y + (hot.y - c.y) * HOT_SHARE, c.z + (hot.z - c.z) * HOT_SHARE);
        f.aimSpread = spread;
        f.aimTall = Math.max(1, tall);
        f.aimWide = Math.max(0.3, wide);
        if (f.star != null && starAlive(f)) f.starHeight = heightOf(f.star, store);
        return true;
    }

    private static Vector3d mean(List<Vector3d> pts) {
        double x = 0, y = 0, z = 0;
        for (Vector3d p : pts) {
            x += p.x;
            y += p.y;
            z += p.z;
        }
        return new Vector3d(x / pts.size(), y / pts.size(), z / pts.size());
    }

    private static Vector3d hotSpot(Film f, Store<EntityStore> store) {
        BattleManager.Battle b = f.battle;
        Ref<EntityStore> v = b.lastVictim;
        if (v != null && System.currentTimeMillis() - b.lastHit < HOT_HIT_MS && v.isValid() && v.getStore() == store
                && b.has(v) && BattleManager.isAlive(v, store)) {
            return ChaosWand.positionOf(v, store);
        }
        Ref<EntityStore> top = null;
        double most = 0;
        for (Map.Entry<Ref<EntityStore>, Double> e : b.damageOf.entrySet()) {
            Ref<EntityStore> r = e.getKey();
            if (e.getValue() > most && r.isValid() && r.getStore() == store && b.has(r) && BattleManager.isAlive(r, store)) {
                most = e.getValue();
                top = r;
            }
        }
        return top != null ? ChaosWand.positionOf(top, store) : null;
    }

    /** The aim, the action point, the spread and a close-up's subject drift toward where they really are. */
    private static void ease(Film f) {
        approach(f.focus, f.aimFocus, FOCUS_EASE);
        approach(f.hot, f.aimHot, FOCUS_EASE * 1.2);
        f.spread += (f.aimSpread - f.spread) * SPREAD_EASE;
        f.tall += (f.aimTall - f.tall) * SPREAD_EASE;
        f.wide += (f.aimWide - f.wide) * SPREAD_EASE;
        if (f.starPos != null && starAlive(f)) {
            Vector3d p = ChaosWand.positionOf(f.star, f.store);
            if (p != null) {
                approach(f.starPos, p, STAR_EASE);
                if (f.partnerPos != null && f.partner != null && f.partner.isValid()) {
                    Vector3d q = ChaosWand.positionOf(f.partner, f.store);
                    if (q != null) approach(f.partnerPos, q, STAR_EASE);
                }
                double h = f.starHeight;
                f.starAim.next(f.star, p, f.focus, Math.max(2.8, h * 1.2 + 1.2) * 1.08, p.y + h * 0.7 + 0.3, p.y + h * 0.6,
                        f.battle.members(), f.store);
            }
        }
    }

    // ---- close-ups ----

    /**
     * An interesting fighter for a close-up, at random by weight: one in a fresh exchange of hits (3), the lone target
     * of an all vs one (3), the top killer standing (2), the top damage dealer standing (2), any fighter (1). Null if
     * nobody stands.
     */
    private static Ref<EntityStore> pickStar(Film f) {
        Store<EntityStore> store = f.store;
        BattleManager.Battle b = f.battle;
        List<Ref<EntityStore>> picks = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        java.util.function.BiConsumer<Ref<EntityStore>, Double> add = (r, w) -> {
            if (r != null && r.isValid() && r.getStore() == store && b.has(r) && BattleManager.isAlive(r, store)) {
                picks.add(r);
                weights.add(w);
            }
        };
        if (System.currentTimeMillis() - b.lastHit < FRESH_HIT_MS) add.accept(b.lastVictim, 3.0);
        if (b.kind == BattleManager.Kind.ALL_VS_ONE) {
            for (Ref<EntityStore> r : b.members()) {
                if (b.team(r) == 1) {
                    add.accept(r, 3.0);
                    break;
                }
            }
        }
        b.kills.entrySet().stream().sorted(b.ranking()).map(Map.Entry::getKey)
                .filter(r -> r.isValid() && b.has(r) && BattleManager.isAlive(r, store)).findFirst()
                .ifPresent(r -> add.accept(r, 2.0));
        Ref<EntityStore> top = null;
        double most = 0;
        for (Map.Entry<Ref<EntityStore>, Double> e : b.damageOf.entrySet()) {
            Ref<EntityStore> r = e.getKey();
            if (e.getValue() > most && r.isValid() && r.getStore() == store && b.has(r) && BattleManager.isAlive(r, store)) {
                most = e.getValue();
                top = r;
            }
        }
        add.accept(top, 2.0);
        List<Ref<EntityStore>> members = new ArrayList<>(b.members());
        if (!members.isEmpty()) add.accept(members.get(f.rnd.nextInt(members.size())), 1.0);
        if (picks.isEmpty()) return null;
        double sum = 0;
        for (double w : weights) sum += w;
        double roll = f.rnd.nextDouble() * sum;
        for (int i = 0; i < picks.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) return picks.get(i);
        }
        return picks.get(picks.size() - 1);
    }

    private static boolean starAlive(Film f) {
        Ref<EntityStore> r = f.star;
        return r != null && r.isValid() && r.getStore() == f.store && f.battle.has(r) && BattleManager.isAlive(r, f.store);
    }

    /** Hitbox height (grown by size buffs), 2 if unknown. */
    private static double heightOf(Ref<EntityStore> r, Store<EntityStore> store) {
        return sizeOf(r, store)[0];
    }

    /** {hitbox height, half width} (grown by size buffs; capped at 40 and 20), a normal mob's when unknown. */
    private static double[] sizeOf(Ref<EntityStore> r, Store<EntityStore> store) {
        BoundingBox bb = store.getComponent(r, BoundingBox.getComponentType());
        if (bb == null || bb.getBoundingBox() == null) return new double[] {2, 0.5};
        var box = bb.getBoundingBox();
        double h = Math.max(0.5, Math.min(40, box.max.y - box.min.y));
        double w = Math.max(0.3, Math.min(20, Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2));
        return new double[] {h, w};
    }

    private static void approach(Vector3d v, Vector3d to, double k) {
        v.x += (to.x - v.x) * k;
        v.y += (to.y - v.y) * k;
        v.z += (to.z - v.z) * k;
    }

    // ---- shots ----

    /**
     * Picks the next shot: filmPlayerShots % show the player (back and close in turn), the rest a battle shot other
     * than the last one (the first shot is a wide pan). Tries ANGLE_TRIES angles for a clear view at its start, middle
     * and end; with none, a battle shot is raised high and a player shot gives way to a battle shot.
     */
    private static void nextPlan(Film f) {
        f.t = 0;
        // A cut starts the height easing over; a glide keeps it, so the camera travels to the new shot.
        if (!"glide".equals(FxSettings.filmTransition)) f.camY = Double.NaN;
        double vary = FxSettings.filmShotVariation / 100.0;
        long ms = Math.max(1000, (long) (FxSettings.filmShotSeconds * 1000L * (1 + vary * (f.rnd.nextDouble() * 2 - 1))));
        java.util.Set<Type> ok = allowed();
        boolean back = ok.contains(Type.PLAYER_BACK), close = ok.contains(Type.PLAYER_CLOSE);
        boolean anyBattle = ok.stream().anyMatch(t -> !t.player);
        int share = anyBattle ? FxSettings.filmPlayerShots : 100;
        boolean player = (back || close) && share > 0 && (share >= 100 || (f.shots > 0 && f.rnd.nextInt(100) < share))
                && playerPos(f) != null;
        double base = f.rnd.nextDouble() * Math.PI * 2;
        double side = f.rnd.nextBoolean() ? 1 : -1;
        Plan plan = null;
        if (player) {
            Type type = back && close ? (f.playerNext ? Type.PLAYER_CLOSE : Type.PLAYER_BACK) : back ? Type.PLAYER_BACK : Type.PLAYER_CLOSE;
            for (double s : new double[] {side, -side}) {
                Plan p = new Plan(type, type == Type.PLAYER_CLOSE ? ms * 4 / 5 : ms, 0, s, false);
                if (clearShot(f, p)) {
                    plan = p;
                    break;
                }
            }
            if (plan != null) f.playerNext = !f.playerNext;
        }
        f.far = false;
        if (plan == null) {
            Type type = pickBattleShot(f, ok);
            // filmWideShots (2026-10-16, user: big attacks, a Nightlord's, look best from far away): that share of the
            // battle shots is a wide pan or a landscape filmed from further back; more of them with a big mob in it.
            java.util.List<Type> wides = new ArrayList<>();
            for (Type w : new Type[] {Type.WIDE_PAN, Type.LANDSCAPE}) if (ok.contains(w) && w != f.last) wides.add(w);
            int wideShare = Math.min(100, FxSettings.filmWideShots + (f.tall > BIG_MOB ? BIG_MOB_WIDE_BONUS : 0));
            if (f.shots > 0 && !wides.isEmpty() && f.rnd.nextInt(100) < wideShare) {
                type = wides.get(f.rnd.nextInt(wides.size()));
                f.far = true;
            }
            if (type == Type.PROFILE) {
                // Two fighters side by side: the star and its nearest enemy.
                f.star = pickStar(f);
                f.partner = f.star != null ? BattleManager.nearestEnemy(f.battle, f.star, f.store) : null;
                Vector3d a = f.star != null ? ChaosWand.positionOf(f.star, f.store) : null;
                Vector3d b = f.partner != null ? ChaosWand.positionOf(f.partner, f.store) : null;
                if (a == null || b == null) {
                    f.star = null;
                    f.partner = null;
                    java.util.Set<Type> rest = java.util.EnumSet.copyOf(ok);
                    rest.remove(Type.PROFILE);
                    type = pickBattleShot(f, rest);
                } else {
                    f.starPos = a;
                    f.partnerPos = b;
                    f.starHeight = heightOf(f.star, f.store);
                }
            }
            if (type == Type.CLOSE_UP) {
                f.star = pickStar(f);
                Vector3d at = f.star != null ? ChaosWand.positionOf(f.star, f.store) : null;
                if (at == null) {
                    f.star = null;
                    java.util.Set<Type> rest = java.util.EnumSet.copyOf(ok);
                    rest.remove(Type.CLOSE_UP);
                    type = pickBattleShot(f, rest);
                } else {
                    f.starPos = at;
                    f.starAim.reset();
                    f.starHeight = heightOf(f.star, f.store);
                }
            }
            for (int i = 0; i < ANGLE_TRIES && plan == null; i++) {
                double a = base + i * Math.PI * 2 / ANGLE_TRIES;
                Plan p = new Plan(type, ms, a, side, false);
                if (clearShot(f, p)) plan = p;
            }
            if (plan == null) {
                Type wide = type == Type.TRACK || type == Type.PUSH_IN || type == Type.CLOSE_UP || type == Type.PROFILE
                        || type == Type.GROUNDRISE ? Type.WIDE_PAN : type;
                plan = new Plan(wide, ms, base, side, true);
            }
        }
        f.plan = plan;
        f.last = plan.type();
        f.shots++;
        MobBattlePlugin.debug("film: " + f.player.getUsername() + " shot " + plan.type() + (f.far ? " (far)" : "") + (plan.high() ? " (raised)" : ""));
        CinemaBars.shotName(f.player, "Film: " + plan.type().word + (f.far ? " (far)" : "") + (plan.high() ? " (raised)" : ""));
    }

    /**
     * A battle shot filmShots allows, other than the last one, at random by weight (user, 2026-10-16: mostly wide
     * shots, now and then a close-up): wide 3 (+1.5 for a spread past 12 blocks, +1 past 20 fighters), arc 2,
     * landscape 1.2 (0.6 for a small battle), track 1, push-in 1, close-up 3 for 2 fighters or fewer, 2 up to 4,
     * 1 up to 10, else 0.5. The film opens on a wide pan when allowed. None allowed: a wide pan anyway.
     */
    private static Type pickBattleShot(Film f, java.util.Set<Type> ok) {
        List<Type> pool = new ArrayList<>();
        for (Type t : Type.values()) if (!t.player && ok.contains(t)) pool.add(t);
        if (pool.isEmpty()) return Type.WIDE_PAN;
        if (f.shots == 0 && pool.contains(Type.WIDE_PAN)) return Type.WIDE_PAN;
        if (pool.size() > 1) pool.remove(f.last);
        double[] w = new double[pool.size()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = switch (pool.get(i)) {
                case WIDE_PAN -> 3 + (f.spread > 12 ? 1.5 : 0) + (f.count > 20 ? 1 : 0);
                case ARC -> 2;
                case LANDSCAPE -> f.spread > 10 ? 0.5 : 0.25;   // was 1.2 / 0.6 (user: the worst shot)
                case CLOSE_UP -> f.count <= 2 ? 3 : f.count <= 4 ? 2 : f.count <= 10 ? 1 : 0.5;
                case ZENITH -> 0.8;
                case SPIRAL, PROFILE -> f.count >= 2 ? 1.2 : 0;
                default -> 1;
            };
            // filmWeight: each shot's percent of its usual weight (0 = never, 200 = twice as often).
            w[i] *= FxSettings.filmWeight(pool.get(i).word) / 100.0;
            sum += w[i];
        }
        double roll = f.rnd.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    /** A clear view at the shot's start, middle and end (camera not in a block, nothing solid or leafy in the way). */
    private static boolean clearShot(Film f, Plan p) {
        for (double e : new double[] {0, 0.5, 1}) {
            Pose pose = pose(f, p, e, e * p.ms());
            if (pose == null) return false;
            Vector3d at = new Vector3d(pose.at());
            double[] roof = {Double.NaN};
            double ground = FightCamera.canopy(at.x, at.z, at.y, f.focus.y, roof, true, f.store);
            at.y = Math.max(at.y, ground + pose.clearance());
            if (!clearView(at, pose.look(), f.store)) return false;
        }
        return true;
    }

    /**
     * Nothing solid between the camera and what it looks at: the camera is not inside a block, and at 7 points along
     * the line (the last 15 % near the subject left out) the ground or tree tops stay under the line.
     */
    private static boolean clearView(Vector3d at, Vector3d look, Store<EntityStore> store) {
        if (Double.isNaN(FightFx.roofAbove(at.x, at.z, at.y, 2, store))) return false;
        for (int i = 1; i <= 7; i++) {
            double k = i / 8.0 * 0.85;
            double x = at.x + (look.x - at.x) * k, y = at.y + (look.y - at.y) * k, z = at.z + (look.z - at.z) * k;
            // floorBelow starts 3 blocks above its argument.
            double g = FightFx.floorBelow(y - 3, store, true).applyAsDouble(x, z);
            if (Double.isFinite(g) && g > y - 0.2) return false;
        }
        return true;
    }

    /** This frame of the current shot: the pose, kept over the ground and tree tops and under a roof (world thread). */
    private static ServerCameraSettings shot(Film f) {
        Plan p = f.plan;
        double lin = Math.min(1, (double) f.t / p.ms());
        Pose pose = pose(f, p, lin * lin * (3 - 2 * lin), f.t);
        if (pose == null) return null;
        Vector3d at = new Vector3d(pose.at());
        double ground = FightCamera.canopy(at.x, at.z, at.y, f.focus.y, f.roof, true, f.store);
        // Stands, walls and hills well above the fight (2026-10-16, user: a shot ended up on top of the colosseum's
        // stands, looking at its torches): a camera that would sit on them moves toward the fight until the ground
        // under it is near the fighters' level again. One flying well above them keeps its place.
        if (ground > f.focus.y + STANDS_RISE && at.y < ground + STANDS_CLEAR) {
            double dx = f.focus.x - at.x, dz = f.focus.z - at.z;
            double len = Math.hypot(dx, dz);
            for (double step = STANDS_STEP; step < len; step += STANDS_STEP) {
                double x = at.x + dx / len * step, z = at.z + dz / len * step;
                double g = FightCamera.canopy(x, z, at.y, f.focus.y, f.roof, false, f.store);
                if (g <= f.focus.y + STANDS_RISE * 0.5) {
                    at.x = x;
                    at.z = z;
                    ground = g;
                    break;
                }
            }
        }
        double want = Math.max(at.y, ground + pose.clearance());
        want = Math.max(ground + 0.6, FightCamera.underRoof(want, f.roof));
        boolean cut = Double.isNaN(f.camY);
        f.camY = cut ? want : f.camY + (want - f.camY) * (want > f.camY ? CLIMB : SINK);
        at.y = Math.max(f.camY, ground + 0.6);
        // Blocked view (2026-10-16, user: shots that rise a lot ended above the colosseum's roof, filming its top): every
        // PULL_EVERY frames the line to the look point is checked, and a blocked camera slides along it toward the
        // fight until it sees (eased, so it does not jump).
        Vector3d look = pose.look();
        if (f.frame % PULL_EVERY == 0) {
            double pullTo = 0;
            while (pullTo < PULL_MAX && !clearView(along(at, look, pullTo), look, f.store)) pullTo += PULL_STEP;
            f.pullWant = pullTo;
        }
        f.pull += (f.pullWant - f.pull) * PULL_EASE;
        if (f.pull > 0.01) at = along(at, look, f.pull);
        // filmTransition glide: the first second of a shot glides slowly from the last one, then the usual follow.
        float lerp = cut ? CUT : "glide".equals(FxSettings.filmTransition) && f.t < GLIDE_MS ? GLIDE_LERP : LERP;
        return FightCamera.zoom(FightCamera.frame(at, pose.look(), lerp), pose.fov());
    }

    /** The point {@code k} (0..1) of the way from {@code a} to {@code b}. */
    private static Vector3d along(Vector3d a, Vector3d b, double k) {
        return new Vector3d(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
    }

    /**
     * Where the camera is and looks for shot {@code p}, {@code e} (eased 0..1) and {@code t} ms into it, before the
     * terrain check. F = the eased aim, S = the fighters' spread, pace = filmPace / 100 (how far it moves). The
     * battle shots stand where their lens fits the fight's footprint ({@link #fit}).
     * <ul>
     *   <li>WIDE_PAN: far and a bit high, sliding sideways past the battle (FOV 70).</li>
     *   <li>TRACK: low beside the battle, moving along at about a walk (FOV 60).</li>
     *   <li>ARC: around the battle, a slow 40-degree arc (FOV 66).</li>
     *   <li>PUSH_IN: toward the action point, closing in and zooming a little (FOV 62 to 52).</li>
     *   <li>CLOSE_UP: one interesting fighter (pickStar), slowly circling and closing in on it (FOV 50).</li>
     *   <li>LANDSCAPE: high and far, turning from the land around to the battle (FOV 74).</li>
     *   <li>PLAYER_BACK: over the player's shoulder at the battle, the player blurred in front (FOV 60).</li>
     *   <li>PLAYER_CLOSE: close behind the player's head, the battle blurred beyond (FOV 55).</li>
     * </ul>
     */
    private static Pose pose(Film f, Plan p, double e, double t) {
        Vector3d c = f.focus;
        double s = f.spread;
        double pace = FxSettings.filmPace / 100.0;
        double raise = p.high() ? 1 : 0;
        double ca = Math.cos(p.angle()), sa = Math.sin(p.angle());
        double tx = -sa * p.side(), tz = ca * p.side();   // along the circle, toward the shot's side
        // Big mobs: look at their bodies, not at their feet (a normal mob keeps the old 1.2).
        double body = Math.max(1.2, f.tall * 0.45);
        Vector3d lookAt = new Vector3d(c.x, c.y + body, c.z);
        return switch (p.type()) {
            case WIDE_PAN -> {
                double r = fit(f, 70) * 1.15 * (f.far ? FAR : 1);
                double slide = r * 0.3 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + ca * r + tx * slide, c.y + Math.max(2.5, r * 0.25) + raise * r * 0.6, c.z + sa * r + tz * slide),
                        lookAt, 70, 2);
            }
            case TRACK -> {
                double r = fit(f, 60) * 0.75;
                double slide = 1.2 * pace * (t - p.ms() / 2.0) / 1000.0;
                yield new Pose(new Vector3d(c.x + ca * r + tx * slide, c.y + Math.max(1.4, f.tall * 0.4) + raise * r * 0.5, c.z + sa * r + tz * slide),
                        lookAt, 60, 1.2);
            }
            case ARC -> {
                double r = fit(f, 66);
                double a = p.angle() + p.side() * 0.7 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, c.y + Math.max(2, r * 0.3) + raise * r * 0.6, c.z + Math.sin(a) * r),
                        lookAt, 66, 2);
            }
            case PUSH_IN -> {
                Vector3d h = f.hot;
                // Never closer than a big mob's body allows (its width plus about its height).
                double nearest = Math.max(4, f.wide + f.tall * 0.9 + 1);
                double from = fit(f, 62), to = Math.max(nearest, from - from * 0.55 * Math.min(1.5, pace));
                double d = from + (to - from) * e;
                yield new Pose(new Vector3d(h.x + ca * d, h.y + Math.max(2.4, f.tall * 0.6) - 0.8 * e + raise * d * 0.5, h.z + sa * d),
                        new Vector3d(h.x, h.y + body, h.z), 62 - 10 * e, 1.5);
            }
            case CLOSE_UP -> {
                Vector3d m = f.starPos;
                if (m == null) yield null;
                double h = f.starHeight;
                double d = Math.max(2.8, h * 1.2 + 1.2) * (1.08 - 0.12 * e);
                // On its face's side (2026-10-16, user: the close-up often showed the back of the head), swinging a
                // little either way; the plan's own angle only while no facing is known.
                double[] fd = f.starAim.dir;
                double a = (Double.isNaN(fd[0]) || !FxSettings.cameraFace ? p.angle() : Math.atan2(fd[1], fd[0]))
                        + p.side() * 0.45 * pace * (e - 0.5);
                yield new Pose(new Vector3d(m.x + Math.cos(a) * d, m.y + h * 0.7 + 0.3 + raise * d * 0.5, m.z + Math.sin(a) * d),
                        new Vector3d(m.x, m.y + h * 0.6, m.z), 50, 0.8);
            }
            case ZENITH -> {
                // Straight down over the fight, turning slowly (a small offset keeps the look off the vertical).
                double r = fit(f, 60) * (f.far ? FAR : 1);
                double h = Math.max(8, r * 1.1);
                double a = p.angle() + p.side() * 0.5 * pace * e;
                double off = Math.max(0.6, r * 0.12);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * off, c.y + h + raise * 4, c.z + Math.sin(a) * off),
                        new Vector3d(c.x, c.y, c.z), 60, 3);
            }
            case SPIRAL -> {
                // Round the fight while rising and backing off.
                double r = fit(f, 66) * 0.8 * (1 + 0.35 * e);
                double a = p.angle() + p.side() * 1.6 * pace * e;
                double y = c.y + Math.max(1.5, f.tall * 0.4) + r * 0.6 * e + raise * r * 0.5;
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, y, c.z + Math.sin(a) * r), lookAt, 66, 1.2);
            }
            case TELEPHOTO -> {
                // Far away with a long lens: the fighters look packed together.
                double r = Math.min(160, fit(f, 24));
                double slide = r * 0.04 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + ca * r + tx * slide, c.y + Math.max(2, r * 0.06) + raise * r * 0.3, c.z + sa * r + tz * slide),
                        lookAt, 24, 1.5);
            }
            case DESCENT -> {
                // A crane coming down from high above into the fight.
                double r = fit(f, 64) * 0.9;
                double top = Math.max(10, r * 0.9), low = Math.max(2, f.tall * 0.5);
                double a = p.angle() + p.side() * 0.25 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, c.y + top + (low - top) * e + raise * 4, c.z + Math.sin(a) * r),
                        lookAt, 64, 1.2);
            }
            case GROUNDRISE -> {
                // From the grass up.
                double r = fit(f, 66) * 0.8;
                double y = c.y + 0.35 + Math.max(3, r * 0.35) * e + raise * r * 0.4;
                yield new Pose(new Vector3d(c.x + ca * r, y, c.z + sa * r), lookAt, 66, 0.3);
            }
            case PROFILE -> {
                // Two fighters from the side, the camera closing in a little.
                Vector3d m = f.starPos, n = f.partnerPos;
                if (m == null || n == null) yield null;
                double mx = (m.x + n.x) / 2, my = (m.y + n.y) / 2, mz = (m.z + n.z) / 2;
                double dx = n.x - m.x, dz = n.z - m.z;
                double len = Math.max(0.01, Math.hypot(dx, dz));
                double px = -dz / len * p.side(), pz = dx / len * p.side();
                double d = Math.max(5, len * 1.1 + f.tall * 1.2 + 2) * (1.05 - 0.1 * e);
                yield new Pose(new Vector3d(mx + px * d, my + Math.max(1.6, f.tall * 0.45) + raise * d * 0.4, mz + pz * d),
                        new Vector3d(mx, my + body, mz), 55, 1.2);
            }
            case LANDSCAPE -> {
                double r = fit(f, 74) * 1.3 * (f.far ? FAR : 1);
                // A slow drift sideways while it turns, so the camera never stands still.
                double drift = r * 0.08 * pace * (e - 0.5);
                Vector3d at = new Vector3d(c.x + ca * r + tx * drift, c.y + Math.max(4, r * 0.35) + raise * r * 0.4, c.z + sa * r + tz * drift);
                double toBattle = Math.atan2(c.z - at.z, c.x - at.x);
                // The turn to the battle is over by 60 % of the shot, then it holds on the fight (2026-10-16, user: keep
                // the shot length the settings give, just turn a bit faster; it was half as long before that).
                double k = Math.min(1, e / 0.6);
                k = k * k * (3 - 2 * k);
                double a = toBattle + p.side() * 1.4 * Math.min(1.5, pace) * (1 - k);
                double dist = Math.hypot(c.x - at.x, c.z - at.z);
                double y = at.y - 3 + (c.y + 1.2 - (at.y - 3)) * k;
                yield new Pose(at, new Vector3d(at.x + Math.cos(a) * dist, y, at.z + Math.sin(a) * dist), 74, 2);
            }
            case PLAYER_BACK, PLAYER_CLOSE -> {
                double[] pp = playerPos(f);
                if (pp == null) yield null;
                double px = pp[0], py = pp[1], pz = pp[2], eye = pp[3];
                double dx = c.x - px, dz = c.z - pz;
                double len = Math.max(0.01, Math.hypot(dx, dz));
                double ux = dx / len, uz = dz / len;          // player -> battle
                double sx = -uz * p.side(), sz = ux * p.side();   // to the shot's side
                if (p.type() == Type.PLAYER_BACK) {
                    // A battle down below (2026-10-16, user: the shot over the shoulder showed only the back of the
                    // head): the further below the eyes it is, the higher and more to the side the camera stands, so
                    // the look down passes beside the player; level or a little below, as before.
                    double below = Math.max(0, (py + eye) - lookAt.y - 1.5);
                    double up = 0.4 + Math.min(3, below * 0.35);
                    double off = 0.7 + 0.4 * e * pace + Math.min(1.2, below * 0.15);
                    yield new Pose(new Vector3d(px - ux * 2.3 + sx * off, py + eye + up, pz - uz * 2.3 + sz * off), lookAt, 60, 0.8);
                }
                double back = 1.6 - 0.3 * e * Math.min(1.5, pace);
                yield new Pose(new Vector3d(px - ux * back + sx * 1.1, py + eye + 0.1, pz - uz * back + sz * 1.1),
                        new Vector3d(px + ux * 0.6, py + eye * 0.95, pz + uz * 0.6), 55, 0.8);
            }
        };
    }

    /**
     * How far a lens of {@code fov} degrees must stand to fit the fight's footprint (its spread plus room for the
     * bodies) in frame; a bigger crowd backs off a little more. A 1v1 films from about 6 blocks, a royale spread over
     * 30 blocks from about 60.
     */
    private static double fit(Film f, double fov) {
        // Wide enough for the footprint plus the widest body, and tall enough for the tallest one (2026-10-16, user).
        double half = Math.max(f.spread + 2.5 + f.wide, f.tall * 0.8);
        double d = half / Math.tan(Math.toRadians(fov) / 2) + f.wide;
        d *= 1 + Math.min(0.3, f.count / 100.0);
        return Math.max(6, Math.min(160, d));
    }

    /** The viewer's feet {x, y, z} and eye height, or null (world thread). */
    private static double[] playerPos(Film f) {
        Ref<EntityStore> me = f.player.isValid() ? f.player.getReference() : null;
        if (me == null || !me.isValid() || me.getStore() != f.store) return null;
        TransformComponent t = f.store.getComponent(me, TransformComponent.getComponentType());
        if (t == null) return null;
        ModelComponent mc = f.store.getComponent(me, ModelComponent.getComponentType());
        Model m = mc != null ? mc.getModel() : null;
        double eye = m != null ? m.getEyeHeight(me, f.store) : 1.6;
        var p = t.getPosition();
        return new double[] {p.x, p.y, p.z, Math.max(0.6, Math.min(20, eye))};
    }

    private static double nearestSq(BattleManager.Battle b, Vector3d at, Store<EntityStore> store) {
        double best = Double.MAX_VALUE;
        for (Ref<EntityStore> r : b.members()) {
            if (r == null || !r.isValid() || r.getStore() != store || !BattleManager.isAlive(r, store)) continue;
            Vector3d p = ChaosWand.positionOf(r, store);
            if (p != null) best = Math.min(best, p.distanceSquared(at));
        }
        return best;
    }

    /** FightCamera.prune (BattleManager.maintain): forgets films whose camera was taken over or ended. */
    static void prune() {
        if (FILMS.isEmpty()) return;
        FILMS.entrySet().removeIf(e -> !FightCamera.owns(e.getKey(), e.getValue().id));
    }
}
