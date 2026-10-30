package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
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
 * /mobbattle film (2026-10-16, playtest: a camera like the AFK Experience mod's that films the whole battle while it runs,
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
    /** Longest step one frame moves a shot (a world hitch doesn't make it jump ahead), see stepOf. */
    private static final long MAX_STEP_MS = 250;
    /** Frames between two reads of where the fighters are (every 250 ms; the eased point moves every frame). */
    private static final int FOCUS_EVERY = 5;
    /** The footprint's radius covers this share of the fighters, so one straggler doesn't blow the frame up. */
    // 0.75 (2026-10-30, playtest video: 13 dummies spread over a field and a raptor far off filmed as specks from ~60
    // blocks away); the footprint is also capped at MAX_SPREAD, so the camera films the thick of the fight
    private static final double SPREAD_SHARE = 0.75;
    /** Largest footprint radius the shots frame (blocks): fighters past it are left out of the frame. */
    private static final double MAX_SPREAD = 28;
    /** A close-up's subject: share of the gap to where it really is closed per frame. */
    // 0.35 (2026-10-29, playtest video: a running fighter left the close-up behind at 0.15)
    private static final double STAR_EASE = 0.35;
    /** The plain center's ease per frame (zenith), and the zenith's lowest height over the fight. */
    private static final double CENTER_EASE = 0.03, ZENITH_MIN = 14;
    /**
     * Ground this many blocks above the fight counts as stands, a wall or a hill to step off (STANDS_STEP blocks at a
     * time toward the fight), unless the camera is STANDS_CLEAR above it already.
     */
    private static final double STANDS_RISE = 4;
    /** An obstacle this much over the shot's own height is stepped around (shot()), not climbed. */
    private static final double AROUND_RISE = 1.5;
    /** Sideways search for a free column: step and farthest offset (blocks), both sides. */
    private static final double AROUND_STEP = 1, AROUND_MAX = 6;
    /** Fighters this close to the camera column lift it over them; at most CROWD_SCAN members looked at. */
    private static final double CROWD_NEAR = 1.6;
    private static final int CROWD_SCAN = 600;
    /** How far a blocked view steps sideways before the pull toward the fight is used (blocks, each way). */
    private static final double SLIDE_MAX = 4;
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
    /** Client follow for close-ups and profiles: quicker, so a running fighter stays in the frame. */
    private static final float CLOSE_LERP = 0.25f;
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
    private static final double FAR = 1.35;   // was 1.7: a 22-mob royale became specks in the landscape (recording 2026-10-17)
    /** A fighter at least this tall (blocks) adds BIG_MOB_WIDE_BONUS percent to filmWideShots. */
    private static final double BIG_MOB = 4;
    private static final int BIG_MOB_WIDE_BONUS = 15;
    /** autoFilm: waits at most this long for the viewer's intro to end. */
    private static final long WAIT_MS = 120_000;
    private static final long WAIT_POLL_MS = 250;
    /** Angles tried around the battle for a shot with a clear view (30 degrees apart). */
    private static final int ANGLE_TRIES = 12;
    /** Shots FilmPicker may offer for one cut when the earlier ones have no clear view. */
    private static final int PICK_TRIES = 4;
    /** roofed(): how high above the fighters a roof is looked for. */
    private static final int ROOF_LOOK = 48;
    private static final String WARN = "#ffcc55";

    /** Shot types; {@code word}: its name in /mobbattle filmShots; {@code player}: shows the viewer watching. */
    private enum Type {
        WIDE_PAN("wide", false), TRACK("track", false), ARC("arc", false), PUSH_IN("pushin", false),
        CLOSE_UP("closeup", false), LANDSCAPE("landscape", false), PLAYER_BACK("playerback", true),
        PLAYER_CLOSE("playerclose", true),
        // 2026-10-16, playtest: shots after AFK Experience's scenes (zenith, spiral, telephoto, descent, groundrise,
        // profile), off by default (filmWeight 0).
        ZENITH("zenith", false), SPIRAL("spiral", false), TELEPHOTO("telephoto", false), DESCENT("descent", false),
        GROUNDRISE("groundrise", false), PROFILE("profile", false);

        final String word;
        final boolean player;

        Type(String word, boolean player) {
            this.word = word;
            this.player = player;
        }

        static Type of(String word) {
            for (Type t : values()) if (t.word.equals(word)) return t;
            return WIDE_PAN;
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
        /** The fight's plain center (no lean toward the action), eased slower: the zenith shot's look point. */
        Vector3d center, aimCenter;
        double spread = 6, aimSpread = 6;
        /**
         * The tallest fighter's hitbox height and the widest one's half width (eased, and what they ease toward): the
         * battle shots back off and aim higher for big mobs (2026-10-16, playtest: a general shot ended inside a boss).
         */
        double tall = 2, aimTall = 2, wide = 0.5, aimWide = 0.5;
        /** The current shot is one of filmWideShots' far ones: it stands FAR times further back. */
        boolean far;
        /** How far out the current shot may stand at its angle (room()), else +inf. */
        double room = Double.POSITIVE_INFINITY;
        /** The last close-up / profile subjects, newest first (pickStar films them less). */
        final java.util.ArrayDeque<Ref<EntityStore>> recentStars = new java.util.ArrayDeque<>();
        /** How far down the crane (DESCENT) may come, as a share of its move; set where its view got blocked. */
        double descentCap = 1;
        /** Blocked-view checks in a row (shot(): BLOCKED_CHECKS end the shot early). */
        int blocked;
        /** The push-in's starting distance, fixed at its first frame (0 = not set yet). */
        double pushFrom;
        /** The push-in's target point: the action point at the shot's start, drifting toward it by PUSH_DRIFT. */
        Vector3d pushAt;
        /** hotSpot's current fighter and since when (HOT_HOLD_MS). */
        Ref<EntityStore> hotRef;
        /** The close-up's fighter fell: the shot holds STAR_FALL_MS on the body, then ends. */
        boolean starFalling;
        /** The shot battlePlan is trying (room() reads its distance), else null. */
        Type planning;
        long hotSince;
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
        /** How far toward the fight the camera moves off high stands (shot(), refreshed every PULL_EVERY frames). */
        double standsShift, standsWant;
        /** Sideways step around an obstacle in the camera's column (shot()), eased toward aroundWant. */
        double around, aroundWant;
        /** Lift over fighters standing at the camera (shot()), eased toward crowdWant. */
        double crowdLift, crowdWant;
        /** Sideways step out of a blocked view (shot()), eased toward slideWant. */
        double slide, slideWant;
        /** Where the close-up looks at the star's face from, kept clear of walls and other mobs. */
        final FightCamera.FaceAim starAim = new FightCamera.FaceAim();
        double starHeight = 2;
        int frame;
        Plan plan;
        long t;
        /** When the last frame ran (stepOf), 0 before the first. */
        long frameAt;
        Type last;
        int shots;
        final FilmPicker picker = new FilmPicker(rnd);
        double camY = Double.NaN;
        final double[] roof = {Double.NaN};
        long overAt;
        /** With endSlowMo: how long the film stays on the winner after the battle ended (winnerShot), else 0. */
        long winnerHold;

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
        long id = 0;
        try {
            id = FightCamera.claim(player);
            Film f = new Film(battle, store, player, id);
            if (!readFocus(f)) {
                // claim already stopped any intro filming: give the camera, bands and HUD back, not just the id
                FightCamera.glideBack(player, id);
                player.sendMessage(I18n.tr("battle.film.none").color(WARN));
                return;
            }
            f.focus = new Vector3d(f.aimFocus);
            f.center = new Vector3d(f.aimCenter);
            f.hot = new Vector3d(f.aimHot);
            f.spread = f.aimSpread;
            f.tall = f.aimTall;
            f.wide = f.aimWide;
            FILMS.put(player.getUuid(), f);
            CinemaBars.in(player, KindFx.cameraBars(battle.kind));
            CinemaBars.filmTitle(player, com.hypixel.hytale.server.core.Message.translation("server.mobbattle.timer." + battle.kind.key));
            CameraHud.hide(player);
            // Q leaves it (the film game mode binds Ability1). Queued after the hide above: the spectator mode sets its
            // own HUD (no hotbar), and a hide that ran after it saved that one, so the film's end put it back over the
            // player's real HUD (playtest 2026-10-28: no hotbar after a film)
            f.world.execute(() -> {
                if (me.isValid() && FILMS.get(player.getUuid()) == f) FilmMode.enter(player, me, store);
            });
            FightCamera.watchSkip(player, f.world);
            if (tell) player.sendMessage(I18n.tr("battle.film.help").color("#ffd23e"));
            MobBattlePlugin.debug("film: " + player.getUsername() + " films battle #" + battle.id.toString().substring(0, 8));
            next(f, 0);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Battle film failed: " + e);
            FILMS.remove(player.getUuid());
            // the id too (it stayed in SHOT, the player counted as busy until their next camera)
            if (id != 0) FightCamera.glideBack(player, id);
            else FightCamera.reset(player);
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
                // moved to another world: camera, bands and HUD back where they are now (audit 2026-10-17: the HUD
                // stayed hidden there)
                FILMS.remove(uuid, f);
                FightCamera.glideBack(f.player, f.id);
                return;
            }
            boolean over = f.battle.over || f.store.isShutdown();
            if (!over && f.frame % FOCUS_EVERY == 0 && !readFocus(f)) over = true;
            if (over) {
                long now = System.currentTimeMillis();
                if (f.overAt == 0) {
                    f.overAt = now;
                    f.winnerHold = winnerShot(f);
                }
                // The end camera (endCamera) takes the camera over by itself; without one, glide back. With endSlowMo
                // the film stays on the winner for the slow motion first (playtest 2026-10-29: it quit before it showed).
                if (now - f.overAt >= Math.max(END_HOLD_MS, f.winnerHold)) {
                    FILMS.remove(uuid, f);
                    FightCamera.glideBack(f.player, f.id);
                    return;
                }
                // not starAlive: after the end the winner may no longer be listed as the battle's member
                if (f.winnerHold > 0 && f.star != null && f.star.isValid() && f.star.getStore() == f.store
                        && BattleManager.isAlive(f.star, f.store)) {
                    ease(f);
                    ServerCameraSettings s = shot(f);
                    if (s != null) FightCamera.send(f.player, FightCamera.blurred(s, FightCamera.blurOf(KindFx.cameraBlur(f.battle.kind))));
                    f.t += stepOf(f);
                    f.frame++;
                }
            } else {
                ease(f);
                // A close-up whose fighter fell or left ends now.
                // A close-up whose fighter fell ends STAR_FALL_MS later, on the body falling (2026-10-29, playtest: it cut the
                // moment it died, the fall was never seen).
                // A profile whose pair split up (one ran off to another fight, 2026-10-29 playtest video) or lost one ends.
                if (f.plan != null && f.plan.type() == Type.PROFILE && f.t > 600 && !pairTogether(f)) f.t = f.plan.ms();
                if (f.plan != null && f.plan.type() == Type.CLOSE_UP && !starAlive(f) && !f.starFalling) {
                    f.starFalling = true;
                    f.t = Math.max(f.t, f.plan.ms() - STAR_FALL_MS);
                }
                if (f.plan == null || f.t >= f.plan.ms()) nextPlan(f);
                ServerCameraSettings s = shot(f);
                if (s != null) FightCamera.send(f.player, FightCamera.blurred(s, FightCamera.blurOf(KindFx.cameraBlur(f.battle.kind))));
                // the first frames of each shot (2026-10-29, playtest: shots still seem to jump right after a cut): what the
                // camera is sent, to tell a server-side change from the client's own settling
                if (s != null && f.t <= 3 * STEP_MS && MobBattlePlugin.debugging() && s.position != null && s.rotation != null) {
                    MobBattlePlugin.debug(String.format(java.util.Locale.ROOT, "film frame %d of %s: at %.2f %.2f %.2f yaw %.3f pitch %.3f fov %s lerp %.2f",
                            f.t / STEP_MS, f.plan != null ? f.plan.type() : null, s.position.x, s.position.y, s.position.z,
                            s.rotation.yaw, s.rotation.pitch, s.baseFov, s.positionLerpSpeed));
                }
                f.t += stepOf(f);
                f.frame++;
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Battle film frame failed: " + e);
        }
        next(f, STEP_MS);
    }

    /**
     * How far a shot moves on this frame: the real time since the last one, at most MAX_STEP_MS. A frame is scheduled
     * STEP_MS after the last and then waits for the world's next tick, so it comes every 75-100 ms, and counting STEP_MS
     * per frame made every shot last up to twice its filmShotTime (2026-10-29, log: 5 s action shots took 7-12 s).
     */
    private static long stepOf(Film f) {
        long now = System.currentTimeMillis();
        long step = f.frameAt == 0 ? STEP_MS : Math.max(1, Math.min(MAX_STEP_MS, now - f.frameAt));
        f.frameAt = now;
        return step;
    }

    /**
     * The next frame, on the world thread. Escape in single player opens the pause menu and pauses the world: the
     * film ends at once and the player gets their camera back (2026-10-16, playtest: as AFK Experience does; it reads
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
            // A world that stopped (an instance closed) refuses tasks: the film ends here, not silently with its id
            // kept, the player "busy" for good and their HUD hidden (audit 2026-10-17).
            if (f.store.isShutdown()) {
                FILMS.remove(f.player.getUuid(), f);
                FightCamera.glideBack(f.player, f.id);
                return;
            }
            try {
                f.world.execute(() -> run(f));
            } catch (RuntimeException e) {
                FILMS.remove(f.player.getUuid(), f);
                FightCamera.glideBack(f.player, f.id);
            }
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
        Vector3d hot = hotSpot(f, store);
        // One height band only (review 2026-10-29: half in a cave, half on the surface put the center inside the rock
        // between them and every shot failed): the band of the action, else the fullest; the rest are stragglers.
        pts = mainBand(pts, hot);
        // the median, not the mean: one fighter far off no longer drags the center (and the frame) toward it
        Vector3d c = median(pts);
        double[] dist = new double[pts.size()];
        for (int i = 0; i < dist.length; i++) dist[i] = Math.hypot(pts.get(i).x - c.x, pts.get(i).z - c.z);
        double[] sorted = dist.clone();
        java.util.Arrays.sort(sorted);
        double radius = sorted[(int) Math.min(sorted.length - 1, Math.ceil(SPREAD_SHARE * (sorted.length - 1)))];
        // The center again without the stragglers past that radius.
        List<Vector3d> core = new ArrayList<>();
        for (int i = 0; i < dist.length; i++) if (dist[i] <= radius + 0.01) core.add(pts.get(i));
        if (!core.isEmpty()) c = mean(core);
        double spread = Math.max(1.5, Math.min(MAX_SPREAD, radius));
        if (hot != null && Math.abs(hot.y - c.y) > BAND / 2 + 2) hot = null;
        if (hot == null || Math.hypot(hot.x - c.x, hot.z - c.z) > spread * 2 + 6) hot = c;
        f.count = alive.size();
        f.aimHot = hot;
        // a crowd leans less toward one hit (2026-10-29, playtest video: 100 dummies, the wide shot swung left and right
        // every quarter second): full share up to HOT_FULL_COUNT fighters, then shrinking with the crowd
        double share = HOT_SHARE * Math.min(1, (double) HOT_FULL_COUNT / alive.size());
        f.aimFocus = new Vector3d(c.x + (hot.x - c.x) * share, c.y + (hot.y - c.y) * share, c.z + (hot.z - c.z) * share);
        f.aimCenter = new Vector3d(c);
        f.aimSpread = spread;
        f.aimTall = Math.max(1, tall);
        f.aimWide = Math.max(0.3, wide);
        if (f.star != null && starAlive(f)) f.starHeight = heightOf(f.star, store);
        return true;
    }

    /** Height band (blocks) of mainBand. */
    static final double BAND = 10;

    /**
     * The points in one height band of BAND blocks: around {@code hot} (the fight's action) when some point is there,
     * else the band holding the most points. All of them when they already fit in one band.
     */
    static List<Vector3d> mainBand(List<Vector3d> pts, Vector3d hot) {
        if (pts.size() < 2) return pts;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Vector3d p : pts) {
            lo = Math.min(lo, p.y);
            hi = Math.max(hi, p.y);
        }
        if (hi - lo <= BAND) return pts;
        double from;
        if (hot != null && count(pts, hot.y - BAND / 2) > 0) {
            from = hot.y - BAND / 2;
        } else {
            // the fullest BAND-high window: sorted heights and a sliding end (audit 2026-10-29: counting for every
            // fighter was O(n^2) a frame with hundreds of them)
            double[] ys = new double[pts.size()];
            for (int i = 0; i < ys.length; i++) ys[i] = pts.get(i).y;
            java.util.Arrays.sort(ys);
            from = lo;
            int best = -1;
            for (int i = 0, j = 0; i < ys.length; i++) {
                if (j < i) j = i;
                while (j < ys.length && ys[j] <= ys[i] + BAND + 0.01) j++;
                if (j - i > best) {
                    best = j - i;
                    from = ys[i];
                }
            }
        }
        List<Vector3d> out = new ArrayList<>();
        for (Vector3d p : pts) if (p.y >= from - 0.01 && p.y <= from + BAND + 0.01) out.add(p);
        return out.isEmpty() ? pts : out;
    }

    private static int count(List<Vector3d> pts, double from) {
        int n = 0;
        for (Vector3d p : pts) if (p.y >= from - 0.01 && p.y <= from + BAND + 0.01) n++;
        return n;
    }

    /** Per-axis median of the points (robust to stragglers). */
    private static Vector3d median(List<Vector3d> pts) {
        int n = pts.size();
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i).x;
            ys[i] = pts.get(i).y;
            zs[i] = pts.get(i).z;
        }
        java.util.Arrays.sort(xs);
        java.util.Arrays.sort(ys);
        java.util.Arrays.sort(zs);
        return new Vector3d(xs[n / 2], ys[n / 2], zs[n / 2]);
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

    /** How long a close-up stays on its fighter's body after it fell. */
    private static final long STAR_FALL_MS = 1800;
    /** The push-in's target drifts this share toward the action per frame; its height is at least distance x PUSH_IN_RISE. */
    private static final double PUSH_DRIFT = 0.012, PUSH_IN_RISE = 0.12;
    /** Blocked-view checks in a row (every PULL_EVERY frames, 250 ms each) that end a shot early. */
    private static final int BLOCKED_CHECKS = 3;
    /** A shot runs at least this long before a blocked view can end it. */
    private static final long BLOCKED_MIN_MS = 1500;

    /** The action point stays on one fighter at least this long while it stands (it jumped with every hit). */
    private static final long HOT_HOLD_MS = 3000;
    /** Fighters up to which the aim leans the whole HOT_SHARE toward the action. */
    private static final int HOT_FULL_COUNT = 8;

    private static Vector3d hotSpot(Film f, Store<EntityStore> store) {
        BattleManager.Battle b = f.battle;
        long now = System.currentTimeMillis();
        Ref<EntityStore> held = f.hotRef;
        if (held != null && now - f.hotSince < HOT_HOLD_MS && held.isValid() && held.getStore() == store && b.has(held)
                && BattleManager.isAlive(held, store)) {
            return ChaosWand.positionOf(held, store);
        }
        f.hotRef = null;
        Ref<EntityStore> v = b.lastVictim;
        if (v != null && now - b.lastHit < HOT_HIT_MS && v.isValid() && v.getStore() == store
                && b.has(v) && BattleManager.isAlive(v, store)) {
            f.hotRef = v;
            f.hotSince = now;
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
        if (f.center != null && f.aimCenter != null) approach(f.center, f.aimCenter, CENTER_EASE);
        approach(f.hot, f.aimHot, FOCUS_EASE * 1.2);
        if (f.pushAt != null) approach(f.pushAt, f.hot, PUSH_DRIFT);
        f.spread += (f.aimSpread - f.spread) * SPREAD_EASE;
        f.tall += (f.aimTall - f.tall) * SPREAD_EASE;
        f.wide += (f.aimWide - f.wide) * SPREAD_EASE;
        // Only the shots that film a fighter follow it (audit 2026-10-17: after the first close-up every later frame of
        // the film kept reading it and ran the clear-side search every 5 frames).
        Type now = f.plan != null ? f.plan.type() : null;
        if ((now == Type.CLOSE_UP || now == Type.PROFILE) && f.starPos != null && starAlive(f)) {
            Vector3d p = ChaosWand.positionOf(f.star, f.store);
            if (p != null) {
                approach(f.starPos, p, STAR_EASE);
                if (f.partnerPos != null && f.partner != null && f.partner.isValid()) {
                    Vector3d q = ChaosWand.positionOf(f.partner, f.store);
                    if (q != null) approach(f.partnerPos, q, STAR_EASE);
                }
                if (now == Type.CLOSE_UP) {
                    double h = f.starHeight;
                    f.starAim.next(f.star, p, f.focus, Math.max(2.8, h * 1.2 + 1.2) * 1.08, p.y + h * 0.7 + 0.3, p.y + h * 0.6,
                            f.battle.members(), f.store);
                }
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
        // anyone standing, preferring one not filmed lately
        List<Ref<EntityStore>> members = new ArrayList<>(b.members());
        for (int tries = 0; tries < 6 && !members.isEmpty(); tries++) {
            Ref<EntityStore> r = members.get(f.rnd.nextInt(members.size()));
            if (!BattleManager.isAlive(r, store)) continue;
            add.accept(r, 1.0);
            if (!f.recentStars.contains(r)) break;
        }
        if (picks.isEmpty()) return null;
        // Lately filmed fighters count less (2026-10-29, playtest: with only close-ups and few fighters left it filmed the
        // same one again and again: the top killer usually is the top damage dealer too). Only when someone else is
        // on the list.
        boolean others = false;
        for (Ref<EntityStore> r : picks) others |= !f.recentStars.contains(r);
        double sum = 0;
        for (int i = 0; i < picks.size(); i++) {
            if (others) {
                int ago = indexOf(f.recentStars, picks.get(i));
                if (ago >= 0) weights.set(i, weights.get(i) * STAR_AGAIN[Math.min(ago, STAR_AGAIN.length - 1)]);
            }
            sum += weights.get(i);
        }
        double roll = f.rnd.nextDouble() * sum;
        Ref<EntityStore> chosen = picks.get(picks.size() - 1);
        for (int i = 0; i < picks.size(); i++) {
            roll -= weights.get(i);
            if (roll <= 0) {
                chosen = picks.get(i);
                break;
            }
        }
        f.recentStars.remove(chosen);
        f.recentStars.addFirst(chosen);
        while (f.recentStars.size() > STAR_AGAIN.length) f.recentStars.removeLast();
        return chosen;
    }

    /** pickStar: weight left to the fighter filmed last, the one before, and the one before that. */
    private static final double[] STAR_AGAIN = {0.1, 0.35, 0.6};

    private static int indexOf(java.util.Deque<Ref<EntityStore>> q, Ref<EntityStore> r) {
        int i = 0;
        for (Ref<EntityStore> x : q) {
            if (x == r) return i;
            i++;
        }
        return -1;
    }

    /** PROFILE: both fighters alive, in this battle and within PROFILE_APART of each other. */
    private static boolean pairTogether(Film f) {
        Ref<EntityStore> o = f.partner;
        if (!starAlive(f) || o == null || !o.isValid() || o.getStore() != f.store || !f.battle.has(o)
                || !BattleManager.isAlive(o, f.store)) return false;
        Vector3d a = ChaosWand.positionOf(f.star, f.store), b = ChaosWand.positionOf(o, f.store);
        return a != null && b != null && Math.hypot(a.x - b.x, a.z - b.z) <= PROFILE_APART;
    }

    /** A profile ends once its two fighters are this far apart. */
    private static final double PROFILE_APART = 9;

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
        // the last shot's roof stayed in use over solid columns of the new one (review 2026-10-29: inside a hill)
        f.roof[0] = Double.NaN;
        f.room = Double.POSITIVE_INFINITY;
        f.blocked = 0;
        f.descentCap = 1;
        f.starFalling = false;
        f.pushAt = null;
        // A cut starts the height easing over; a glide keeps it, so the camera travels to the new shot.
        if (!"glide".equals(FxSettings.filmTransition)) f.camY = Double.NaN;
        double vary = FxSettings.filmShotVariation / 100.0;
        long ms = Math.max(1000, (long) (FxSettings.filmShotSeconds * 1000L * (1 + vary * (f.rnd.nextDouble() * 2 - 1))));
        double base = f.rnd.nextDouble() * Math.PI * 2;
        double side = f.rnd.nextBoolean() ? 1 : -1;
        // FilmPicker chooses (no shot twice, no family three times, shares kept over the fight); a shot whose view is
        // blocked from every angle is skipped and another one asked for, up to PICK_TRIES.
        boolean roofed = roofed(f);
        int wideShare = Math.min(100, FxSettings.filmWideShots + (f.tall > BIG_MOB ? BIG_MOB_WIDE_BONUS : 0));
        FilmPicker.Scene scene = new FilmPicker.Scene(f.count, f.spread, f.tall, roofed, playerPos(f) != null, f.shots == 0);
        java.util.Set<String> skip = new java.util.HashSet<>();
        Plan plan = null;
        FilmPicker.Choice chosen = null;
        FilmPicker.Choice lastTry = null;
        for (int tries = 0; tries < PICK_TRIES && plan == null; tries++) {
            FilmPicker.Choice c = f.picker.next(scene, FxSettings::filmWeight, FxSettings.filmPlayerShots, wideShare, skip);
            if (c == null) break;
            lastTry = c;
            Type type = Type.of(c.shot());
            f.far = c.far();
            plan = c.player() ? playerPlan(f, type, ms, side) : battlePlan(f, type, ms, base, side);
            if (plan != null) {
                chosen = c;
            } else {
                if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("film: " + f.player.getUsername() + " skips " + c.shot() + " (no clear view)");
                skip.add(c.shot());
                if (c.player()) skip.addAll(FilmPicker.PLAYER);
            }
        }
        if (plan == null && lastTry != null && FxSettings.filmWeight("wide") <= 0
                && (!lastTry.player() || playerPos(f) != null)) {
            // Nothing clear and the wide shot is off: the last shot tried anyway, without the clear-view test
            // (2026-10-29, playtest: with only close-ups on, a general shot from above showed for a moment; with only the
            // player shots on, the same).
            Type type = Type.of(lastTry.shot());
            if (lastTry.player()) {
                plan = new Plan(type, type == Type.PLAYER_CLOSE ? ms * 4 / 5 : ms, 0, side, false);
                chosen = lastTry;
            } else if (type != Type.CLOSE_UP && type != Type.PROFILE || f.star != null) {
                plan = new Plan(type, ms, base, side, false);
                chosen = lastTry;
                f.far = lastTry.far();
                f.planning = type;
                f.room = room(f, base);
                f.planning = null;
            }
        }
        if (plan == null) {
            // Nothing clear: a wide pan anyway, raised over the obstacles outdoors; under a roof it stays below it (the
            // raised one flew over a colosseum's dome) and the blocked-view pull brings it in.
            f.far = false;
            chosen = new FilmPicker.Choice("wide", false);
            // never raised any more (2026-10-29, playtest video: in an open colosseum the raised fallback filmed from high
            // over the stands); room() keeps it inside, the pull and side step clear the view
            plan = new Plan(Type.WIDE_PAN, ms, base, side, false);
            f.planning = Type.WIDE_PAN;
            f.room = room(f, base);
            f.planning = null;
        }
        f.picker.commit(chosen);
        // a push-in closes in on one fighter: its action point is held for the whole shot (it moved with every new hit)
        if (plan.type() == Type.PUSH_IN && f.hotRef != null) f.hotSince = System.currentTimeMillis() + plan.ms() - HOT_HOLD_MS;
        f.pushFrom = 0;
        f.pushAt = (plan.type() == Type.PUSH_IN || plan.type() == Type.TELEPHOTO) && f.hot != null ? new Vector3d(f.hot) : null;
        f.plan = plan;
        f.last = plan.type();
        f.shots++;
        MobBattlePlugin.debug("film: " + f.player.getUsername() + " shot " + plan.type() + (f.far ? " (far)" : "") + (plan.high() ? " (raised)" : ""));
        CinemaBars.shotName(f.player, "Film: " + plan.type().word + (f.far ? " (far)" : "") + (plan.high() ? " (raised)" : ""));
    }

    /** A player shot with a clear view from either side, or null (back and close-to-the-head alternate in FilmPicker). */
    private static Plan playerPlan(Film f, Type type, long ms, double side) {
        for (double s : new double[] {side, -side}) {
            Plan p = new Plan(type, type == Type.PLAYER_CLOSE ? ms * 4 / 5 : ms, 0, s, false);
            if (clearShot(f, p)) return p;
        }
        return null;
    }

    /**
     * As the battle ends with endSlowMo on: a close-up of the winner (the last killer, else someone still standing) for
     * the slow motion's time plus a little, so the film shows it. Returns how long to hold (0 = no slow motion or nobody
     * to show: the film ends as before).
     */
    private static long winnerShot(Film f) {
        // only when the world really went slow (another battle there, or a draw nobody fought, skips it)
        if (FxSettings.endSlowMo < 2 || !SlowMo.slowed(f.battle)) return 0;
        Ref<EntityStore> winner = f.battle.lastKiller;
        if (winner == null || !BattleManager.isAlive(winner, f.store)) {
            winner = null;
            for (Ref<EntityStore> m : f.battle.members()) {
                if (BattleManager.isAlive(m, f.store)) {
                    winner = m;
                    break;
                }
            }
        }
        Vector3d at = winner != null ? ChaosWand.positionOf(winner, f.store) : null;
        if (at == null) return 0;
        long ms = Math.max(1, FxSettings.endSlowMoSeconds) * 1000L + WINNER_TAIL_MS;
        f.star = winner;
        f.starPos = at;
        f.starAim.reset();
        f.starHeight = heightOf(winner, f.store);
        // the clear face side from the first frame, as battlePlan's close-ups (2026-10-29, playtest video: the winner
        // shot stood on the wall's side and the final blow was hardly seen)
        double h = f.starHeight;
        f.starAim.next(winner, at, f.focus, Math.max(2.8, h * 1.2 + 1.2) * 1.08, at.y + h * 0.7 + 0.3, at.y + h * 0.6,
                f.battle.members(), f.store);
        f.plan = new Plan(Type.CLOSE_UP, ms, f.rnd.nextDouble() * Math.PI * 2, 0, false);
        f.t = 0;
        f.camY = Double.NaN;   // a cut onto the winner
        f.starFalling = false;
        CinemaBars.shotName(f.player, "Film: winner");
        return ms;
    }

    /** How long the winner shot stays after the slow motion ends. */
    private static final long WINNER_TAIL_MS = 600;

    /**
     * A battle shot of {@code type} with a clear view from one of ANGLE_TRIES angles, or null. Close-up and profile
     * pick their fighters first (null when there are none).
     */
    private static Plan battlePlan(Film f, Type type, long ms, double base, double side) {
        if (type == Type.PROFILE) {
            // Two fighters side by side: the star and its nearest enemy.
            f.star = pickStar(f);
            f.partner = f.star != null ? BattleManager.nearestEnemy(f.battle, f.star, f.store) : null;
            Vector3d a = f.star != null ? ChaosWand.positionOf(f.star, f.store) : null;
            Vector3d b = f.partner != null ? ChaosWand.positionOf(f.partner, f.store) : null;
            if (a == null || b == null) {
                f.star = null;
                f.partner = null;
                return null;
            }
            f.starPos = a;
            f.partnerPos = b;
            f.starHeight = heightOf(f.star, f.store);
        }
        if (type == Type.CLOSE_UP) {
            f.star = pickStar(f);
            Vector3d at = f.star != null ? ChaosWand.positionOf(f.star, f.store) : null;
            if (at == null) {
                f.star = null;
                return null;
            }
            f.starPos = at;
            f.starAim.reset();
            f.starHeight = heightOf(f.star, f.store);
            // the face side now, not a frame later (review 2026-10-29: the first frame used the plan's random angle, the
            // next swung up to 180 degrees to the face: the "settling" after a cut into a close-up)
            double h = f.starHeight;
            f.starAim.next(f.star, at, f.focus, Math.max(2.8, h * 1.2 + 1.2) * 1.08, at.y + h * 0.7 + 0.3, at.y + h * 0.6,
                    f.battle.members(), f.store);
        }
        boolean roomed = !Double.isNaN(reach(f, type));
        f.planning = type;
        for (int i = 0; i < ANGLE_TRIES; i++) {
            Plan p = new Plan(type, ms, base + i * Math.PI * 2 / ANGLE_TRIES, side, false);
            f.room = roomed ? room(f, p.angle()) : Double.POSITIVE_INFINITY;
            if (clearShot(f, p)) {
                f.planning = null;
                return p;
            }
        }
        f.planning = null;
        f.room = Double.POSITIVE_INFINITY;
        return null;
    }

    /**
     * How far from the fight shot {@code type} wants to stand (the largest r its pose uses), or NaN for the shots that
     * do not stand at a distance on a fixed angle (close-ups, profile, zenith overhead, player shots): room() walks
     * this far.
     */
    private static double reach(Film f, Type type) {
        if (type == null) return Double.NaN;
        double far = f.far ? FAR : 1;
        return switch (type) {
            case WIDE_PAN -> fit(f, 70) * 1.15 * far;
            case TRACK -> fit(f, 60) * 0.75;
            case ARC -> fit(f, 66);
            case SPIRAL -> fit(f, 66) * 0.8 * 1.35;
            case TELEPHOTO -> Math.min(160, fit(f, 24));
            case DESCENT -> fit(f, 64) * 0.9;
            case GROUNDRISE -> fit(f, 66) * 0.8;
            case LANDSCAPE -> fit(f, 74) * 1.3 * far;
            default -> Double.NaN;
        };
    }

    /** room(): headroom under a roof that still counts as the arena, not a corridor or the space under the stands. */
    private static final double ROOM_HEADROOM = 6;

    /**
     * How far from the fight the shot being planned may stand at {@code angle} (2026-10-29, playtest: the wide shot of an
     * 8-team battle climbed to the arena roof or ended in the corridors behind the stands: its distance follows the
     * fighters' spread, not the room). Walks out a block at a time at the camera's low height and stops before a block,
     * a low ceiling (ROOM_HEADROOM; open sky never stops it) or a blocked view of the fight (walls, hills), up to the
     * distance the shot wants (reach); one block of margin. At least 6. Every shot that stands at a distance on a
     * fixed angle is capped by it, since the same video showed the problem for any of them.
     */
    /** room() stops walking out here: that much clear ground is room enough for any shot. */
    private static final double ROOM_SCAN_MAX = 96;

    private static double room(Film f, double angle) {
        Vector3d c = f.focus;
        double ca = Math.cos(angle), sa = Math.sin(angle);
        double y = c.y + 2.5;
        Vector3d look = new Vector3d(c.x, c.y + Math.max(1.2, f.tall * 0.45), c.z);
        double want = reach(f, f.plan != null && f.planning == null ? f.plan.type() : f.planning);
        if (Double.isNaN(want)) return Double.POSITIVE_INFINITY;
        double good = 3;
        for (double d = 4; d <= want + 1; d += 1) {
            // this far with room all the way: as good as unlimited (audit 2026-10-29: a far landscape wanted ~280 blocks,
            // up to 12 angles x 4 shot types per shot change on the world thread)
            if (d > ROOM_SCAN_MAX) return Double.POSITIVE_INFINITY;
            double x = c.x + ca * d, z = c.z + sa * d;
            // only the headroom matters here (audit 2026-10-29: a 48-block roof scan at every step of 12 angles)
            double roof = FightFx.roofAbove(x, z, y, (int) ROOM_HEADROOM + 1, f.store);
            if (Double.isNaN(roof) || roof - y < ROOM_HEADROOM) break;
            // the view back to the fight, every 12 blocks (a wall with a doorway: the camera stays on the arena side)
            if ((int) d % 12 == 0 && !clearView(new Vector3d(x, y, z), look, f.store)) break;
            good = d;
        }
        return good >= want ? Double.POSITIVE_INFINITY : Math.max(6, good - 1);
    }

    /**
     * The fight is under a roof (2026-10-16, playtest: a far landscape, raised, filmed the top of a colosseum's dome): at
     * least two of the center and four points around it at the spread have a solid block within ROOF_LOOK above. Then
     * there are no far shots and no raised fallback. A dome with an open middle still counts.
     */
    private static boolean roofed(Film f) {
        double r = Math.max(6, f.spread);
        int roofs = 0;
        for (int i = 0; i < 5; i++) {
            double x = f.focus.x + (i == 0 ? 0 : Math.cos(i * Math.PI / 2) * r);
            double z = f.focus.z + (i == 0 ? 0 : Math.sin(i * Math.PI / 2) * r);
            double roof = FightFx.roofAbove(x, z, f.focus.y + 2, ROOF_LOOK, f.store);
            if (!Double.isNaN(roof) && !Double.isInfinite(roof) && ++roofs >= 2) return true;
        }
        return false;
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
    static boolean clearView(Vector3d at, Vector3d look, Store<EntityStore> store) {
        if (Double.isNaN(FightFx.roofAbove(at.x, at.z, at.y, 2, store))) return false;
        if (inLeaves(at.x, at.y, at.z, store)) return false;
        // about one sample per block (review 2026-10-29: 7 samples on a 60-block line were 6+ blocks apart and missed
        // a thin wall), 7 to 64
        double len = Math.sqrt((look.x - at.x) * (look.x - at.x) + (look.y - at.y) * (look.y - at.y) + (look.z - at.z) * (look.z - at.z));
        int n = (int) Math.max(7, Math.min(64, Math.ceil(len * 0.85)));
        for (int i = 1; i <= n; i++) {
            double k = i / (n + 1.0) * 0.85;
            double x = at.x + (look.x - at.x) * k, y = at.y + (look.y - at.y) * k, z = at.z + (look.z - at.z) * k;
            // floorBelow starts 3 blocks above its argument.
            double g = FightFx.floorBelow(y - 3, store, true).applyAsDouble(x, z);
            if (Double.isFinite(g) && g > y - 0.2) return false;
        }
        return !sideBlocked(at, look, store);
    }

    /** sideBlocked: how far beside the line, and how far along it (blocks), a block counts as filling the frame. */
    private static final double SIDE_OFF = 0.7, SIDE_REACH = 2.5;

    /**
     * A block right beside the camera, off the line of sight (2026-10-29, playtest videos: a trunk next to the camera
     * covered half the screen while the line to the fighter stayed clear): samples SIDE_OFF to each side of the line
     * over its first SIDE_REACH blocks, at the camera's height going toward the look point.
     */
    static boolean sideBlocked(Vector3d at, Vector3d look, ComponentAccessor<EntityStore> acc) {
        double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
        double flat = Math.hypot(dx, dz);
        double len = Math.sqrt(flat * flat + dy * dy);
        if (flat < 0.5 || len < 1.5) return false;
        double px = -dz / flat, pz = dx / flat;
        double reach = Math.min(SIDE_REACH, len * 0.4);
        for (double side : new double[] {SIDE_OFF, -SIDE_OFF}) {
            for (double d = 0; d <= reach + 0.01; d += 0.8) {
                double k = d / len;
                double x = at.x + dx * k + px * side, y = at.y + dy * k, z = at.z + dz * k + pz * side;
                double g = FightFx.floorBelow(y - 3, acc, true).applyAsDouble(x, z);
                if (Double.isFinite(g) && g > y - 0.2) return true;
            }
        }
        return false;
    }

    /**
     * The camera sits inside leaves (or any ground floorBelow counts) at its own height: roofAbove only sees solid
     * blocks, and leaves have no material (2026-10-29, playtest video: a spotlight crowd shot inside a bush by the wall).
     */
    static boolean inLeaves(double x, double y, double z, ComponentAccessor<EntityStore> acc) {
        // floorBelow starts 3 blocks above its argument: from the camera's height down
        double g = FightFx.floorBelow(y - 3, acc, true).applyAsDouble(x, z);
        return Double.isFinite(g) && g > y - 0.2;
    }

    /** This frame of the current shot: the pose, kept over the ground and tree tops and under a roof (world thread). */
    private static ServerCameraSettings shot(Film f) {
        Plan p = f.plan;
        double lin = Math.min(1, (double) f.t / p.ms());
        Pose pose = pose(f, p, lin * lin * (3 - 2 * lin), f.t);
        if (pose == null) return null;
        Vector3d at = new Vector3d(pose.at());
        // Heights and the stands rule are measured against what the shot films: a close-up's fighter on a post or the
        // stands, not the fight's center (2026-10-29, playtest video: the camera was pulled away from a dummy that climbed).
        boolean onStar = (p.type() == Type.CLOSE_UP || p.type() == Type.PROFILE) && f.starPos != null;
        Vector3d subj = onStar ? f.starPos : f.focus;
        double ground = FightCamera.canopy(at.x, at.z, at.y, subj.y, f.roof, true, f.store);
        // Stands, walls and hills well above the fight (2026-10-16, playtest: a shot ended up on top of the colosseum's
        // stands, looking at its torches): a camera that would sit on them moves toward the fight until the ground
        // under it is near the fighters' level again. One flying well above them keeps its place.
        // The search walks the ground every STANDS_STEP toward the fight (up to ~100 canopy reads): done every
        // PULL_EVERY frames and on cuts, the found shift kept in between (audit 2026-10-17: it ran every frame).
        boolean cutNow = Double.isNaN(f.camY);
        // Something in the camera's own column, well over the height the shot wants (a pillar, a banner frame, a fighter
        // standing on a crate): step sideways around it, across the line of sight, instead of rising over it (2026-10-29,
        // playtest video: the tracking shot climbed over every obstacle). Searched every PULL_EVERY frames and on cuts, eased
        // like the stands shift; nothing free within AROUND_MAX = the old rules (stands shift, then climbing).
        double lx = pose.look().x - at.x, lz = pose.look().z - at.z;
        double llen = Math.max(0.01, Math.hypot(lx, lz));
        double px = -lz / llen, pz = lx / llen;
        if (cutNow || f.frame % PULL_EVERY == 0) {
            double kept = f.aroundWant;
            f.aroundWant = 0;
            if (ground + pose.clearance() > at.y + AROUND_RISE) {
                // the side already taken while it is still free (2026-10-29, playtest video: re-searched every quarter
                // second it took the other side now and then, and the camera swung through the post between them)
                if (kept != 0 && FightCamera.canopy(at.x + px * kept, at.z + pz * kept, at.y, subj.y, f.roof, false, f.store)
                        + pose.clearance() <= at.y + 0.5) {
                    f.aroundWant = kept;
                }
                int first = kept < 0 || kept == 0 && f.around < 0 ? -1 : 1;
                search:
                for (double d = AROUND_STEP; d <= AROUND_MAX && f.aroundWant == 0; d += AROUND_STEP) {
                    for (int sign : new int[] {first, -first}) {
                        double s2 = d * sign;
                        double g = FightCamera.canopy(at.x + px * s2, at.z + pz * s2, at.y, subj.y, f.roof, false, f.store);
                        if (g + pose.clearance() <= at.y + 0.5) {
                            f.aroundWant = s2;
                            break search;
                        }
                    }
                }
            }
        }
        f.around = cutNow ? f.aroundWant : f.around + (f.aroundWant - f.around) * PULL_EASE;
        if (Math.abs(f.around) > 0.05) {
            at.x += px * f.around;
            at.z += pz * f.around;
            ground = FightCamera.canopy(at.x, at.z, at.y, subj.y, f.roof, false, f.store);
        }
        if (cutNow || f.frame % PULL_EVERY == 0) {
            f.standsWant = 0;
            if (ground > subj.y + STANDS_RISE && at.y < ground + STANDS_CLEAR) {
                double dx = subj.x - at.x, dz = subj.z - at.z;
                double len = Math.hypot(dx, dz);
                for (double step = STANDS_STEP; step < len; step += STANDS_STEP) {
                    double x = at.x + dx / len * step, z = at.z + dz / len * step;
                    double g = FightCamera.canopy(x, z, at.y, subj.y, f.roof, false, f.store);
                    if (g <= subj.y + STANDS_RISE * 0.5) {
                        f.standsWant = step;
                        break;
                    }
                }
            }
        }
        // eased (review 2026-10-29: it jumped 1.5 blocks at a time every 5 frames, part of the "settling" after cuts)
        f.standsShift = cutNow ? f.standsWant : f.standsShift + (f.standsWant - f.standsShift) * PULL_EASE;
        if (f.standsShift > 0.05) {
            double dx = subj.x - at.x, dz = subj.z - at.z;
            double len = Math.hypot(dx, dz);
            if (len > f.standsShift) {
                at.x += dx / len * f.standsShift;
                at.z += dz / len * f.standsShift;
                ground = FightCamera.canopy(at.x, at.z, at.y, subj.y, f.roof, false, f.store);
            }
        }
        // A column too tight for the camera (ground + 0.6 above what the roof allows: stands under the dome) moves toward
        // the look point until one fits, else keeps the old rule (review 2026-10-29: the ground won and the camera
        // went into the roof).
        if (FightCamera.underRoof(ground + 0.6, f.roof) < ground + 0.6) {
            for (int i = 1; i <= 6; i++) {
                Vector3d q = along(at, pose.look(), i * 0.1);
                double g = FightCamera.canopy(q.x, q.z, at.y, subj.y, f.roof, false, f.store);
                if (FightCamera.underRoof(g + 0.6, f.roof) >= g + 0.6) {
                    at.x = q.x;
                    at.z = q.z;
                    ground = g;
                    break;
                }
            }
        }
        // Fighters right at the camera (2026-10-29, playtest video: a track shot through a crowd of 300 had bodies sweeping
        // the lens): every PULL_EVERY frames, the tallest body within CROWD_NEAR of the camera column lifts the camera
        // over its head (close-ups and profiles keep their own height: they film one of them).
        if ((cutNow || f.frame % PULL_EVERY == 0) && !onStar) {
            double top = Double.NEGATIVE_INFINITY;
            int seen = 0;
            for (Ref<EntityStore> m : f.battle.members()) {
                if (++seen > CROWD_SCAN) break;
                Vector3d q = m != null && m.isValid() && m.getStore() == f.store ? ChaosWand.positionOf(m, f.store) : null;
                if (q == null || Math.hypot(q.x - at.x, q.z - at.z) > CROWD_NEAR) continue;
                top = Math.max(top, q.y + heightOf(m, f.store));
            }
            f.crowdWant = Double.isInfinite(top) ? 0 : Math.max(0, top + 0.6 - at.y);
        }
        f.crowdLift = cutNow ? f.crowdWant : f.crowdLift + (f.crowdWant - f.crowdLift) * PULL_EASE;
        if (f.crowdLift > 0.05) at.y += f.crowdLift;
        double want = Math.max(at.y, ground + pose.clearance());
        want = Math.max(ground + 0.6, FightCamera.underRoof(want, f.roof));
        boolean cut = Double.isNaN(f.camY);
        f.camY = cut ? want : f.camY + (want - f.camY) * (want > f.camY ? CLIMB : SINK);
        at.y = Math.max(f.camY, ground + 0.6);
        // A blocked view first tries a short step sideways at the same distance (2026-10-29, playtest video, spiral: a column
        // hid the fight and the only way out was the pull below, all the way in front of it). The side already taken is
        // kept while it still sees; SLIDE_MAX each way.
        if (cut || f.frame % PULL_EVERY == 0) {
            double keptSlide = f.slideWant;
            f.slideWant = 0;
            Vector3d lk = pose.look();
            if (!clearView(at, lk, f.store)) {
                if (keptSlide != 0 && clearView(new Vector3d(at.x + px * keptSlide, at.y, at.z + pz * keptSlide), lk, f.store)) {
                    f.slideWant = keptSlide;
                } else {
                    int first = keptSlide < 0 || keptSlide == 0 && f.slide < 0 ? -1 : 1;
                    search:
                    for (double d = 1; d <= SLIDE_MAX; d += 1) {
                        for (int sign : new int[] {first, -first}) {
                            Vector3d q = new Vector3d(at.x + px * d * sign, at.y, at.z + pz * d * sign);
                            if (clearView(q, lk, f.store)) {
                                f.slideWant = d * sign;
                                break search;
                            }
                        }
                    }
                }
            }
        }
        f.slide = cut ? f.slideWant : f.slide + (f.slideWant - f.slide) * PULL_EASE;
        if (Math.abs(f.slide) > 0.05) {
            at.x += px * f.slide;
            at.z += pz * f.slide;
        }
        // Blocked view (2026-10-16, playtest: shots that rise a lot ended above the colosseum's roof, filming its top): every
        // PULL_EVERY frames the line to the look point is checked, and a blocked camera slides along it toward the
        // fight until it sees (eased, so it does not jump).
        Vector3d look = pose.look();
        if (cut || f.frame % PULL_EVERY == 0) {
            // whole steps up to PULL_MAX; none clears = no pull (review 2026-10-29: it went to 0.9 of the way blind,
            // into the ground, a wall or a mob)
            double pullTo = 0;
            boolean clear = false;
            int steps = (int) Math.round(PULL_MAX / PULL_STEP);
            for (int i = 0; i <= steps; i++) {
                Vector3d q = along(at, look, i * PULL_STEP);
                if (clearView(q, look, f.store)) {
                    pullTo = i * PULL_STEP;
                    clear = true;
                    break;
                }
            }
            f.pullWant = pullTo;
            // No pull clears it (2026-10-29, playtest video: the last fighters walked to the arena wall and the wide shot,
            // its angle fixed at the start, sat inside the wall): BLOCKED_CHECKS checks in a row end the shot, and the
            // next one picks a clear angle. Not the winner shot, which keeps its place on purpose.
            f.blocked = clear || f.winnerHold > 0 ? 0 : f.blocked + 1;
            // A crane coming down stops where its view got blocked, a little higher, instead of being cut and starting
            // again from the top (2026-10-29, playtest video: the descent jumped up and down among the arena's posts).
            // A profile whose fighters moved against a wall: the other side of the pair, if it sees (2026-10-29, playtest
            // video: the side picked at the start ended inside the arena wall).
            if (!clear && p.type() == Type.PROFILE) {
                Plan other = new Plan(p.type(), p.ms(), p.angle(), -p.side(), p.high());
                Pose op = pose(f, other, lin * lin * (3 - 2 * lin), f.t);
                if (op != null && clearView(op.at(), op.look(), f.store)) {
                    f.plan = other;
                    f.blocked = 0;
                }
            }
            if (!clear && p.type() == Type.DESCENT && f.descentCap >= 1) {
                f.descentCap = Math.max(0, lin * lin * (3 - 2 * lin) - 0.12);
                f.blocked = 0;
            }
            // not before BLOCKED_MIN_MS into the shot: among many posts each new shot was cut again at once
            if (f.blocked >= BLOCKED_CHECKS && f.t > BLOCKED_MIN_MS) {
                f.blocked = 0;
                f.t = p.ms();
            }
        }
        // A cut starts at its own pull (2026-10-16, playtest: in cut mode the camera still settled for about a second after
        // each cut): the last shot's pull used to ease out over ~15 frames, and the new one's waited for PULL_EVERY.
        if (cut) f.pull = f.pullWant;
        else f.pull += (f.pullWant - f.pull) * PULL_EASE;
        if (f.pull > 0.01) at = along(at, look, f.pull);
        // filmTransition glide: the first second of a shot glides slowly from the last one, then the usual follow.
        float lerp = cut ? CUT : "glide".equals(FxSettings.filmTransition) && f.t < GLIDE_MS ? GLIDE_LERP : onStar ? CLOSE_LERP : LERP;
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
                // never past the room's edge at this angle (room()): walls, low ceilings, hills; a closer camera stays lower
                double r = Math.min(fit(f, 70) * 1.15 * (f.far ? FAR : 1), f.room);
                double slide = r * 0.3 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + ca * r + tx * slide, c.y + Math.max(2.5, r * 0.25) + raise * r * 0.6, c.z + sa * r + tz * slide),
                        lookAt, 70, 2);
            }
            case TRACK -> {
                double r = Math.min(fit(f, 60) * 0.75, f.room);
                double slide = 1.2 * pace * (t - p.ms() / 2.0) / 1000.0;
                yield new Pose(new Vector3d(c.x + ca * r + tx * slide, c.y + Math.max(1.4, f.tall * 0.4) + raise * r * 0.5, c.z + sa * r + tz * slide),
                        lookAt, 60, 1.2);
            }
            case ARC -> {
                double r = Math.min(fit(f, 66), f.room);
                double a = p.angle() + p.side() * 0.7 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, c.y + Math.max(2, r * 0.3) + raise * r * 0.6, c.z + Math.sin(a) * r),
                        lookAt, 66, 2);
            }
            case PUSH_IN -> {
                // a point that only drifts toward the action (pushAt), not the moving fighter itself (2026-10-29, playtest
                // video: the whole camera followed its fighter and jumped when it died)
                Vector3d h = f.pushAt != null ? f.pushAt : f.hot;
                // Never closer than a big mob's body allows (its width plus about its height).
                double nearest = Math.max(4, f.wide + f.tall * 0.9 + 1);
                // distances fixed at the shot's start (2026-10-29, playtest video: the fit followed the spread every frame,
                // so the dolly moved in surges instead of at a steady pace)
                if (f.pushFrom <= 0) f.pushFrom = fit(f, 62);
                double from = f.pushFrom, to = Math.max(nearest, from - from * 0.55 * Math.min(1.5, pace));
                double d = from + (to - from) * e;
                // higher the farther it stands (it started ~60 blocks out at 2.4 high: mostly floor, the fight a thin line)
                double lift = Math.max(Math.max(2.4, f.tall * 0.6), d * PUSH_IN_RISE);
                yield new Pose(new Vector3d(h.x + ca * d, h.y + lift - 0.8 * e + raise * d * 0.5, h.z + sa * d),
                        new Vector3d(h.x, h.y + body, h.z), 62 - 10 * e, 1.5);
            }
            case CLOSE_UP -> {
                Vector3d m = f.starPos;
                if (m == null) yield null;
                double h = f.starHeight;
                double d = Math.max(2.8, h * 1.2 + 1.2) * (1.08 - 0.12 * e);
                // On its face's side (2026-10-16, playtest: the close-up often showed the back of the head), swinging a
                // little either way; the plan's own angle only while no facing is known.
                double[] fd = f.starAim.dir;
                double a = (Double.isNaN(fd[0]) || !FxSettings.cameraFace ? p.angle() : Math.atan2(fd[1], fd[0]))
                        + p.side() * 0.45 * pace * (e - 0.5);
                yield new Pose(new Vector3d(m.x + Math.cos(a) * d, m.y + h * 0.7 + 0.3 + f.starAim.lift + raise * d * 0.5, m.z + Math.sin(a) * d),
                        new Vector3d(m.x, m.y + h * 0.6, m.z), 50, 0.8);
            }
            case ZENITH -> {
                // Straight down over the fight, turning slowly (a small offset keeps the look off the vertical). Over the
                // plain center, eased slower, and never under ZENITH_MIN (2026-10-29, playtest video: with few fighters left
                // it came down to 8 blocks over a point that jumped toward each hit, and seemed to race and lose them).
                if (f.center != null) c = f.center;
                double r = fit(f, 60) * (f.far ? FAR : 1);
                double h = Math.max(ZENITH_MIN, r * 1.1);
                double a = p.angle() + p.side() * 0.5 * pace * e;
                double off = Math.max(0.6, r * 0.12);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * off, c.y + h + raise * 4, c.z + Math.sin(a) * off),
                        new Vector3d(c.x, c.y, c.z), 60, 3);
            }
            case SPIRAL -> {
                // Round the fight while rising and backing off.
                double r = Math.min(fit(f, 66) * 0.8 * (1 + 0.35 * e), f.room);
                double a = p.angle() + p.side() * 1.6 * pace * e;
                double y = c.y + Math.max(1.5, f.tall * 0.4) + r * 0.6 * e + raise * r * 0.5;
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, y, c.z + Math.sin(a) * r), lookAt, 66, 1.2);
            }
            case TELEPHOTO -> {
                // Far away with a long lens: the fighters look packed together. Aimed at the action point drifting slowly
                // (pushAt, as the push-in), not the crowd's mean (2026-10-29, playtest video: the narrow lens showed empty
                // sand between spread fighters, and every shift of the leaning center looked like a jump).
                Vector3d g = f.pushAt != null ? f.pushAt : c;
                double r = Math.min(Math.min(160, fit(f, 24)), f.room);
                double slide = r * 0.04 * pace * (e - 0.5);
                yield new Pose(new Vector3d(g.x + ca * r + tx * slide, g.y + Math.max(2, r * 0.06) + raise * r * 0.3, g.z + sa * r + tz * slide),
                        new Vector3d(g.x, g.y + body, g.z), 24, 1.5);
            }
            case DESCENT -> {
                // A crane coming down from high above into the fight.
                double r = Math.min(fit(f, 64) * 0.9, f.room);
                double top = Math.max(10, r * 0.9), low = Math.max(2, f.tall * 0.5);
                e = Math.min(e, f.descentCap);
                double a = p.angle() + p.side() * 0.25 * pace * (e - 0.5);
                yield new Pose(new Vector3d(c.x + Math.cos(a) * r, c.y + top + (low - top) * e + raise * 4, c.z + Math.sin(a) * r),
                        lookAt, 64, 1.2);
            }
            case GROUNDRISE -> {
                // From the grass up. Starts 0.9 over the fight's floor with 0.8 of clearance (2026-10-29, playtest video: at
                // 0.35 / 0.3 the lens went into slabs and sand bumps and the posts filled the frame).
                double r = Math.min(fit(f, 66) * 0.8, f.room);
                double y = c.y + 0.9 + Math.max(3, r * 0.35) * e + raise * r * 0.4;
                yield new Pose(new Vector3d(c.x + ca * r, y, c.z + sa * r), lookAt, 66, 0.8);
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
                double r = Math.min(fit(f, 74) * 1.3 * (f.far ? FAR : 1), f.room);
                // A slow drift sideways while it turns, so the camera never stands still.
                double drift = r * 0.08 * pace * (e - 0.5);
                Vector3d at = new Vector3d(c.x + ca * r + tx * drift, c.y + Math.max(4, r * 0.35) + raise * r * 0.4, c.z + sa * r + tz * drift);
                double toBattle = Math.atan2(c.z - at.z, c.x - at.x);
                // It starts with the fight already on the frame's edge (~0.55 rad off, the FOV's half is 37 degrees) and has
                // turned to it by 35 % of the shot, then holds on the fight (2026-10-29, playtest: it started 80 degrees away,
                // on the horizon, and lost much of the fight; before, the turn took 60 % of the shot).
                double k = Math.min(1, e / 0.35);
                k = k * k * (3 - 2 * k);
                double a = toBattle + p.side() * 0.55 * Math.min(1.2, pace) * (1 - k);
                double dist = Math.hypot(c.x - at.x, c.z - at.z);
                double ky = 0.5 + 0.5 * k;   // half way down to the fight from the start, not at the horizon
                double y = at.y - 3 + (c.y + 1.2 - (at.y - 3)) * ky;
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
                    // A battle down below (2026-10-16, playtest: the shot over the shoulder showed only the back of the
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
        // Wide enough for the footprint plus the widest body, and tall enough for the tallest one (2026-10-16).
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
