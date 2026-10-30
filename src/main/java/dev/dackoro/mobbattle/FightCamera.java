package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.DepthOfFieldSettings;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.EasingType;
import com.hypixel.hytale.protocol.MouseInputTargetType;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.PositionDistanceOffsetType;
import com.hypixel.hytale.protocol.PositionType;
import com.hypixel.hytale.protocol.RotationType;
import com.hypixel.hytale.protocol.ServerCameraSettings;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.player.CameraManager;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.camera.CameraSequenceBuilder;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /mobbattle camera: the owner's camera films the fight's intro (countdown or cameraTime) and, with endCamera, its
 * winner, then comes back to them. Per battle kind (KindFx): orbit, duel (western), showcase (close-ups), teams
 * (team by team sweep), focus (the lone target, then around it through the crowd), drone (flight over the battle).
 * <p>
 * Uses the game's own camera sequences (CameraSequenceBuilder, as vanilla /tpcinematic's CinematicTeleport does):
 * keyframes with a position, a look-at point, a duration and an easing, played smoothly by the client. Flags
 * LockInput (the player can't move meanwhile, but keeps their movement state, e.g. flying) and
 * ReturnToGameplayCameraOnEnd. The last keyframe is the player's own eye view, so the return is a glide.
 */
final class FightCamera {

    /** How far the camera circles during the countdown (radians, about 140 degrees). */
    private static final double SWEEP = 2.4;
    /** The orbit runs a bit inside the area's edge (/mobbattle areaRadius). */
    private static final double EDGE = 0.85;
    private static final double MIN_RADIUS = 8;
    /** Keyframes along the orbit (the client moves in straight lines between them). */
    private static final int ORBIT_KEYS = 16;
    /** Flying out of the player's view, and back into it at the end (seconds). */
    private static final float OUT_S = 0.7f;
    private static final float BACK_S = 0.9f;
    private static final byte FLAGS = (byte) (CameraSequenceFlags.LockInput | CameraSequenceFlags.ReturnToGameplayCameraOnEnd);

    private FightCamera() {
    }

    /**
     * The intro camera of a fight, per its kind's settings (KindFx): camera, cameraStyle, cameraTime (else the
     * countdown), cameraShots. {@code sides}: the fighters of each side (a royale passes one list with everyone).
     * The owner never gets it while fighting themselves (their own view stays theirs).
     */
    static void intro(BattleManager.Kind kind, PlayerRef owner, Vector3d center, List<List<Ref<EntityStore>>> sides,
                      ComponentAccessor<EntityStore> buffer) {
        // cameraScope: the owner, or every player nearby / in the world; each gets the shot from their own side.
        withVs(kind, owner != null ? owner.getUuid() : null, FightFx.cameraViewers(owner), FightFx.vsViewers(owner), center,
                sides, buffer);
    }

    static void intro(BattleManager.Kind kind, BattleManager.Battle.Anchor anchor, Vector3d center,
                      List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        withVs(kind, null, FightFx.eventViewers(anchor, FxSettings.cameraScope), FightFx.eventViewers(anchor, FxSettings.vsScope),
                center, sides, buffer);
    }

    /**
     * vsScreen (2026-10-16, playtest: the VS screen should open the battle's countdown): the VS screen first
     * (VsScreen.countdownMs, 2.5 s), then the intro camera with the time left (none under 1.5 s). Without it, the intro
     * right away as before. A camera problem never breaks the fight.
     */
    private static void withVs(BattleManager.Kind kind, java.util.UUID owner, Iterable<PlayerRef> viewers,
                               Iterable<PlayerRef> vsViewers, Vector3d center,
                               List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        long vs = 0;
        VsScreen.Plan plan = null;
        try {
            vs = VsScreen.countdownMs(kind);
            plan = vs > 0 ? VsScreen.planOf(kind, owner, sides, buffer) : null;
            VsScreen.onDemand(plan);   // serverVsPictures battle: this battle's pictures go to the players now
            // a screen with many pictures lasts longer (they load before it slides), the countdown with it
            if (plan != null) vs = VsScreen.timeFor(plan);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("VS screen failed: " + e);
        }
        if (plan == null) {
            // No VS shown: the countdown did not grow (Countdown.run asks VsScreen.takeShown), the intro starts now.
            for (PlayerRef viewer : viewers) {
                introFor(kind, KindFx.cameraStyle(kind), KindFx.introSeconds(kind), KindFx.cameraShots(kind), viewer, center,
                        sides, buffer);
            }
            return;
        }
        // The VS adds its time to the countdown (2026-10-16): the intro keeps its whole time after it.
        long left = KindFx.introSeconds(kind) * 1000L;
        int seconds = KindFx.introSeconds(kind);
        com.hypixel.hytale.server.core.universe.world.World world = buffer.getExternalData().getWorld();
        java.util.Set<Ref<EntityStore>> fighters = new java.util.HashSet<>();
        for (List<Ref<EntityStore>> side : sides) fighters.addAll(side);
        boolean shown = false;
        for (PlayerRef viewer : viewers) {
            if (viewer == null || !viewer.isValid() || BattleWatch.isWatching(viewer)) continue;
            VsScreen.present(viewer, plan, vs);
            shown = true;
            if (left < 1500) continue;
            // later, on the world thread with its Store (the caller's CommandBuffer only lives for this tick). Kept in
            // PENDING meanwhile, so a countdown that falls, is replaced or stopped during the VS drops it (audit
            // 2026-10-17: a fallen duel still played its whole intro after the VS).
            Intro pending = new Intro(SEQ.incrementAndGet(), fighters);
            PENDING.put(viewer.getUuid(), pending);
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    world.execute(() -> {
                        if (!PENDING.remove(viewer.getUuid(), pending) || !viewer.isValid()) return;
                        introFor(kind, KindFx.cameraStyle(kind), seconds, KindFx.cameraShots(kind), viewer, center, sides,
                                world.getEntityStore().getStore());
                    });
                } catch (RuntimeException e) {   // the world stopped meanwhile
                    PENDING.remove(viewer.getUuid(), pending);
                }
            }, vs, TimeUnit.MILLISECONDS);
        }
        // The VS also goes to vsScope (default nearby) and the battle's player fighters, without a camera (2026-10-29,
        // playtest: a player standing by saw neither the VS nor the countdown, the cameras only go to cameraScope)
        java.util.Set<UUID> got = new java.util.HashSet<>();
        for (PlayerRef viewer : viewers) if (viewer != null) got.add(viewer.getUuid());
        for (PlayerRef viewer : vsViewers) {
            if (viewer == null || !viewer.isValid() || got.contains(viewer.getUuid()) || BattleWatch.isWatching(viewer)) continue;
            VsScreen.present(viewer, plan, vs);
            shown = true;
        }
        // The countdown that follows in this tick grows by the VS time only when someone really got the VS.
        if (shown) VsScreen.markShown(fighters, vs);
    }

    /** Intros waiting for their VS screen to end, per viewer (the countdown can fall, be replaced or stopped meanwhile). */
    private static final Map<UUID, Intro> PENDING = new ConcurrentHashMap<>();

    /** An intro is about to start for this player (its VS screen still shows). */
    static boolean introComing(PlayerRef player) {
        return player != null && PENDING.containsKey(player.getUuid());
    }

    private static void introFor(BattleManager.Kind kind, PlayerRef player, Vector3d center,
                                 List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        introFor(kind, KindFx.cameraStyle(kind), KindFx.introSeconds(kind), KindFx.cameraShots(kind), player, center, sides, buffer);
    }

    /**
     * /mobbattle test camera (2026-10-13): one intro style for one player over the given fighters, without a battle
     * and whatever the camera settings say. World thread.
     */
    static void test(String style, int seconds, PlayerRef player, List<List<Ref<EntityStore>>> sides,
                     ComponentAccessor<EntityStore> buffer) {
        introFor(null, style, seconds, KindFx.cameraShots(null), player, null, sides, buffer);
    }

    private static void introFor(BattleManager.Kind kind, String chosenStyle, int seconds, int shots, PlayerRef player,
                                 Vector3d center, List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        // Never let a camera problem break the fight that called it.
        try {
            // A /mobbattle watch spectator keeps the camera they chose.
            if (seconds <= 0 || player == null || !player.isValid() || BattleWatch.isWatching(player)) return;
            List<List<Ref<EntityStore>>> live = new ArrayList<>();
            List<Ref<EntityStore>> all = new ArrayList<>();
            for (List<Ref<EntityStore>> side : sides) {
                List<Ref<EntityStore>> ok = new ArrayList<>();
                for (Ref<EntityStore> r : side) if (r != null && r.isValid()) ok.add(r);
                if (!ok.isEmpty()) {
                    live.add(ok);
                    all.addAll(ok);
                }
            }
            if (all.isEmpty() || all.contains(player.getReference())) return;
            if (center == null) center = centroid(all, buffer);
            if (center == null) return;
            long total = seconds * 1000L;
            String style = chosenStyle;
            if (("duel".equals(style) || "focus".equals(style)) && live.size() < 2) style = "showcase";
            if ("teams".equals(style) && live.size() < 2) style = "showcase";
            MobBattlePlugin.debug("intro camera " + style + " for " + (kind != null ? kind.key : "?") + ", " + seconds + " s, "
                    + all.size() + " fighters in " + live.size() + " sides");
            String shotLabel = "Intro: " + style + " (" + seconds + " s)";
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> CinemaBars.shotName(player, shotLabel), 500, TimeUnit.MILLISECONDS);
            // playShots reads who this intro films: it ends on a wide shot of them, or hands over to the film.
            Long shotBefore = SHOT.get(player.getUuid()), orbitBefore = ORBITS.get(player.getUuid());
            INTRO_FIGHTERS.set(all);
            try {
                switch (style) {
                    case "orbit" -> orbit(kind, player, center, seconds, buffer);
                    case "duel" -> duel(kind, player, live.get(0).get(0), live.get(1).get(0), total, buffer);
                    case "teams" -> teams(kind, player, center, live, total, buffer);
                    case "focus" -> {
                        // The first side's first fighter (everyone vs one: the target) against all the others.
                        Ref<EntityStore> target = live.get(0).get(0);
                        List<Ref<EntityStore>> crowd = new ArrayList<>(all);
                        crowd.remove(target);
                        focus(kind, player, target, crowd, total, buffer);
                    }
                    case "drone" -> drone(kind, player, center, all, total, buffer);
                    case "spotlight" -> spotlight(kind, player, center, live, shots, total, buffer);
                    default -> showcase(kind, player, all, shots, total, buffer);
                }
            } finally {
                INTRO_FIGHTERS.remove();
            }
            // Remember what this intro films, so the battle's end cuts it (battleOver); the end camera is never cut.
            // Only a camera this call started: a style that gave up early (no position) left another battle's film or
            // camera there, which this battle's end then cut (audit 2026-10-17).
            Long shot = SHOT.get(player.getUuid()), orbit = ORBITS.get(player.getUuid());
            boolean newShot = shot != null && !shot.equals(shotBefore);
            boolean newOrbit = orbit != null && !orbit.equals(orbitBefore);
            if (newShot || newOrbit) {
                INTROS.put(player.getUuid(), new Intro(newShot ? shot : ORBIT_ID, java.util.Set.copyOf(all)));
                watchSkip(player, buffer.getExternalData().getWorld());
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Fight camera failed: " + e);
            reset(player);
        }
    }

    /**
     * End of a battle (KindFx endCamera winner / lasthit): a slow half-circle around {@code focus} for
     * endCameraTime seconds, then back to the player. No-op with endCamera off.
     */
    static void ending(BattleManager.Kind kind, PlayerRef owner, Ref<EntityStore> focus,
                       ComponentAccessor<EntityStore> buffer) {
        for (PlayerRef viewer : FightFx.cameraViewers(owner)) endingFor(kind, viewer, focus, buffer);
    }

    static void ending(BattleManager.Battle battle, Ref<EntityStore> focus,
                       ComponentAccessor<EntityStore> buffer) {
        if (battle == null) return;
        for (PlayerRef viewer : FightFx.cameraViewers(battle)) endingFor(battle.kind, viewer, focus, buffer);
    }

    private static void endingFor(BattleManager.Kind kind, PlayerRef player, Ref<EntityStore> focus,
                                  ComponentAccessor<EntityStore> buffer) {
        try {
            if ("off".equals(KindFx.endCamera(kind)) || player == null || !player.isValid()
                    || focus == null || !focus.isValid() || BattleWatch.isWatching(player)) return;
            Ref<EntityStore> me = player.getReference();
            // Dead (respawn screen) or in another world: nothing to film for them.
            if (me == null || !me.isValid() || me.getStore() != focus.getStore() || !BattleManager.isAlive(me, buffer)) return;
            long total = KindFx.endCameraSeconds(kind) * 1000L;
            Track track = new Track();
            List<Shot> shots = List.of(new Shot(total, (t, acc) -> {
                Vector3d p = track.pos(focus, acc);
                if (p == null) return null;
                double eye = track.eye(focus, acc);
                double swing = -0.6 + 1.2 * t / total;
                return closeUp(p, track.yaw(focus, acc), eye, swing, closeDist(4, 2.6, eye, track.size(focus, acc)), eye * 0.6 + 1.2);
            }));
            playShots(kind, player, buffer.getExternalData().getWorld(), shots);
            watchSkip(player, buffer.getExternalData().getWorld());
        } catch (RuntimeException e) {
            MobBattlePlugin.log("End camera failed: " + e);
            reset(player);
        }
    }

    /** Wide circling shot of the area around {@code center} (keyframes the client plays by itself). */
    private static void orbit(BattleManager.Kind kind, PlayerRef player, Vector3d center, int seconds,
                              ComponentAccessor<EntityStore> buffer) {
        Eye eye = eyeOf(player, buffer);
        if (eye == null) return;
        double radius = Math.max(MIN_RADIUS, FxSettings.radius * EDGE);
        double height = radius * 0.5 + 4;
        Vector3d look = new Vector3d(center.x, center.y + 1, center.z);
        // Start on the player's side of the battlefield, so the shot opens from where they stand.
        double start = Math.atan2(eye.pos.z - center.z, eye.pos.x - center.x);
        float orbit = Math.max(0.5f, seconds - OUT_S - BACK_S);

        CameraSequenceBuilder seq = new CameraSequenceBuilder();
        for (int i = 0; i <= ORBIT_KEYS; i++) {
            double a = start + SWEEP * i / ORBIT_KEYS;
            Vector3d at = new Vector3d(center.x + Math.cos(a) * radius, center.y + height, center.z + Math.sin(a) * radius);
            if (i == 0) seq.keyframeLookingAt(at, look, OUT_S, EasingType.SineOut);
            else seq.keyframeLookingAt(at, look, orbit / ORBIT_KEYS, EasingType.Linear);
        }
        // A server camera shot of this player (an older intro or end shot) stops sending frames.
        SHOT.remove(player.getUuid());
        play(player, seq, eye);
        long end = System.currentTimeMillis() + seconds * 1000L;
        ORBITS.put(player.getUuid(), end);
        // Cinema bars: in now, out as the orbit glides back to the player (unless a newer camera took over).
        CinemaBars.in(player, KindFx.cameraBars(kind));
        CameraHud.hide(player);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (Long.valueOf(end).equals(ORBITS.get(player.getUuid()))) {
                CinemaBars.out(player);
                CameraHud.restore(player);
                // the orbit took a running film's camera without its reset (audit 2026-10-29): its game mode goes too
                FilmMode.leave(player);
            }
        }, Math.max(0, (long) ((seconds - BACK_S) * 1000)), TimeUnit.MILLISECONDS);
    }

    /**
     * Duel standoff, like a western (2026-10-16, playtest: wide shots, over the shoulder, close on the eyes, zoom): a low
     * wide shot panning past both fighters, over each one's shoulder at the other, a slow push-in on each one's eyes,
     * then both from the side, then back to the player. A short intro keeps fewer of them (DUEL_CUTS). Follows the
     * fighters as they move.
     */
    private static void duel(BattleManager.Kind kind, PlayerRef player, Ref<EntityStore> a, Ref<EntityStore> b, long total,
                             ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS, total - BACK_MS);
        int n = (int) Math.max(1, Math.min(DUEL_CUTS.length, usable / MIN_DUEL_SHOT_MS));
        DuelShot[] cuts = DUEL_CUTS[n - 1];
        double weights = 0;
        for (DuelShot cut : cuts) weights += cut.weight;
        Track track = new Track();
        double[] axis = new double[3];   // A -> B direction the shots keep (ux, uz, set?), see duelAxis
        List<Shot> shots = new ArrayList<>();
        for (DuelShot cut : cuts) {
            long each = Math.max(STEP_MS, (long) (usable * cut.weight / weights));
            FaceAim aim = new FaceAim();   // per shot (eyes shots)
            shots.add(new Shot(each, (t, acc) -> {
                Vector3d pa = track.pos(a, acc), pb = track.pos(b, acc);
                if (pa == null || pb == null) return null;
                duelAxis(axis, a, pa, pb, acc);
                double f = Math.min(1, (double) t / each);
                if (cut == DuelShot.EYES_A || cut == DuelShot.EYES_B) {
                    // In front of the face, wherever it looks (2026-10-16, playtest: during the countdown the duellists don't
                    // face each other yet, so the shot along the line between them showed the side or back of the head);
                    // toward the opponent only when no facing can be read.
                    boolean first = cut == DuelShot.EYES_A;
                    Ref<EntityStore> who = first ? a : b;
                    Vector3d p = first ? pa : pb;
                    double[] sz = track.size(who, acc);
                    double eye = track.eye(who, acc);
                    double[] u = aim.next(who, p, first ? pb : pa, eyesDist(sz, 0), p.y + eye, p.y + eye * 0.97,
                            List.of(a, b), acc);
                    double e = f * f * (3 - 2 * f);
                    return eyes(p, sz, eye, u[0], u[1], -u[1], u[0], e, aim.lift);
                }
                return duelFrame(cut, f, pa, pb, axis[0], axis[1], track.size(a, acc), track.size(b, acc),
                        track.eye(a, acc), track.eye(b, acc), acc);
            }));
        }
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * The A -> B direction the duel's shots are built on, in {@code axis} (ux, uz, 1 once set). It follows the line
     * between them while they stand at least DUEL_AXIS_MIN apart; closer, the line flips with every step they take
     * (2026-10-29, playtest video: two duellists spawned on one spot, the wide shot sat in the sand and the over the
     * shoulder ones inside a shield), so it keeps the last one, or at the start takes the way A faces.
     */
    private static void duelAxis(double[] axis, Ref<EntityStore> a, Vector3d pa, Vector3d pb,
                                 ComponentAccessor<EntityStore> acc) {
        double dx = pb.x - pa.x, dz = pb.z - pa.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len >= DUEL_AXIS_MIN) {
            axis[0] = dx / len;
            axis[1] = dz / len;
            axis[2] = 1;
        } else if (axis[2] == 0) {
            double[] face = faceDir(a, acc);
            if (face != null) {
                axis[0] = face[0];
                axis[1] = face[1];
            } else if (len > 0.01) {
                axis[0] = dx / len;
                axis[1] = dz / len;
            } else {
                axis[0] = 0;
                axis[1] = 1;
            }
            axis[2] = 1;
        }
    }

    /** The duel's shots (see duelFrame); weight = its share of the intro time. */
    private enum DuelShot {
        WIDE(1.4), OVER_A(1), OVER_B(1), EYES_A(0.9), EYES_B(0.9), SIDE(1);

        final double weight;

        DuelShot(double weight) {
            this.weight = weight;
        }
    }

    /** Which shots an intro with room for 1..6 of them (MIN_DUEL_SHOT_MS each) plays, in order. */
    private static final DuelShot[][] DUEL_CUTS = {
            {DuelShot.SIDE},
            {DuelShot.EYES_A, DuelShot.EYES_B},
            {DuelShot.EYES_A, DuelShot.EYES_B, DuelShot.SIDE},
            {DuelShot.OVER_A, DuelShot.EYES_A, DuelShot.EYES_B, DuelShot.SIDE},
            {DuelShot.OVER_A, DuelShot.OVER_B, DuelShot.EYES_A, DuelShot.EYES_B, DuelShot.SIDE},
            {DuelShot.WIDE, DuelShot.OVER_A, DuelShot.OVER_B, DuelShot.EYES_A, DuelShot.EYES_B, DuelShot.SIDE},
    };

    /**
     * Royale showcase: close-ups of {@code count} fighters picked at random (fewer if the time is short), each
     * seen from the front with a slow sideways drift.
     */
    private static void showcase(BattleManager.Kind kind, PlayerRef player, List<Ref<EntityStore>> fighters, int count, long total,
                                 ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS, total - BACK_MS);
        int n = (int) Math.max(1, Math.min(Math.min(count, fighters.size()), usable / MIN_SHOT_MS));
        List<Ref<EntityStore>> picked = new ArrayList<>(fighters);
        java.util.Collections.shuffle(picked);
        picked = picked.subList(0, n);
        long each = usable / n;
        Track track = new Track();
        List<Shot> shots = new ArrayList<>();
        for (Ref<EntityStore> mob : picked) {
            shots.add(new Shot(each, (t, acc) -> {
                Vector3d p = track.pos(mob, acc);
                if (p == null) return null;
                double eye = track.eye(mob, acc);
                double swing = -0.35 + 0.7 * t / each;
                return closeUp(p, track.yaw(mob, acc), eye, swing, closeDist(3, 2.2, eye, track.size(mob, acc)), eye * 0.9 + 0.4);
            }));
        }
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Spotlight (2026-10-16, playtest: close-ups when there are few mobs and the countdown is long enough; with many, only
     * the strongest, then a wide shot of the rest; e.g. more than two teams). Every fighter gets a close-up when they
     * all fit (SPOT_MIN_MS each, at most cameraShots); else the strongest of each side first (one champion per team,
     * then the next strongest), as many as fit after the crowd shot's share, and a low sideways crowd shot over the
     * ones left out. Close-ups go weakest to strongest (the strongest last, seen from low), each facing the battle's
     * center with a push-in on the eyes. Strength = max health (buffs included), then hitbox height.
     */
    private static void spotlight(BattleManager.Kind kind, PlayerRef player, Vector3d center, List<List<Ref<EntityStore>>> sides,
                                  int count, long total, ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS, total - BACK_MS);
        List<Ref<EntityStore>> all = new ArrayList<>();
        for (List<Ref<EntityStore>> side : sides) all.addAll(side);
        Map<Ref<EntityStore>, Double> power = new java.util.HashMap<>();
        for (Ref<EntityStore> r : all) power.put(r, strengthOf(r, buffer));
        java.util.Comparator<Ref<EntityStore>> strongest = (a, b) -> Double.compare(power.get(b), power.get(a));
        int max = Math.max(1, count);
        List<Ref<EntityStore>> picked = new ArrayList<>();
        long crowd = 0;
        if (all.size() <= max && all.size() * SPOT_MIN_MS <= usable) {
            picked.addAll(all);
        } else {
            crowd = Math.max(SPOT_CROWD_MIN_MS, (long) (usable * SPOT_CROWD_SHARE));
            int fit = (int) Math.max(0, Math.min(max, (usable - crowd) / SPOT_MIN_MS));
            if (fit == 0) crowd = usable;
            // One champion per side (the strongest sides first), then the strongest of everyone left.
            if (sides.size() > 1) {
                List<Ref<EntityStore>> champions = new ArrayList<>();
                for (List<Ref<EntityStore>> side : sides) {
                    List<Ref<EntityStore>> sorted = new ArrayList<>(side);
                    sorted.sort(strongest);
                    if (!sorted.isEmpty()) champions.add(sorted.get(0));
                }
                champions.sort(strongest);
                for (Ref<EntityStore> c : champions) if (picked.size() < fit) picked.add(c);
            }
            List<Ref<EntityStore>> rest = new ArrayList<>(all);
            rest.removeAll(picked);
            rest.sort(strongest);
            for (Ref<EntityStore> r : rest) if (picked.size() < fit) picked.add(r);
        }
        picked.sort(strongest.reversed());
        Track track = new Track();
        List<Shot> shots = new ArrayList<>();
        long each = picked.isEmpty() ? 0 : Math.max(STEP_MS, (usable - crowd) / picked.size());
        for (int i = 0; i < picked.size(); i++) {
            Ref<EntityStore> mob = picked.get(i);
            boolean hero = i == picked.size() - 1 && picked.size() > 1;
            FaceAim aim = new FaceAim();   // per shot
            shots.add(new Shot(each, (t, acc) -> {
                Vector3d p = track.pos(mob, acc);
                if (p == null) return null;
                double[] sz = track.size(mob, acc);
                double eye = track.eye(mob, acc);
                // In front of its face (2026-10-16, playtest: during the countdown mobs still look anywhere, so the
                // battle's side often showed the back of the head); toward the battle only if no facing is known.
                double[] u = hero
                        ? aim.next(mob, p, center, heroDist(sz, 0), p.y + Math.max(0.5, sz[0] * 0.15), p.y + eye * 0.85, all, acc)
                        : aim.next(mob, p, center, eyesDist(sz, 0), p.y + eye, p.y + eye * 0.97, all, acc);
                double ux = u[0], uz = u[1];
                double f = Math.min(1, (double) t / each);
                double e = f * f * (3 - 2 * f);
                if (hero) return heroShot(p, sz, eye, ux, uz, e, aim.lift);
                return eyes(p, sz, eye, ux, uz, -uz, ux, e, aim.lift);
            }));
        }
        if (crowd > 0) {
            List<Ref<EntityStore>> others = new ArrayList<>(all);
            others.removeAll(picked);
            if (others.size() < 2) others = all;
            shots.add(crowdShot(others, player, crowd));
        }
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Where an entity's face points on the ground, {x, z} of length 1: its head's yaw (HeadRotation), else its body's;
     * null if neither can be read. A camera at position + this x distance sees its face.
     */
    static double[] faceDir(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        if (r == null || !r.isValid() || r.getStore() != acc) return null;
        HeadRotation head = acc.getComponent(r, HeadRotation.getComponentType());
        float yaw;
        if (head != null && head.getRotation() != null) {
            yaw = head.getRotation().yaw();
        } else {
            TransformComponent tr = acc.getComponent(r, TransformComponent.getComponentType());
            if (tr == null) return null;
            yaw = tr.getRotation().yaw();
        }
        return new double[] {-Math.sin(yaw), -Math.cos(yaw)};
    }

    /**
     * {@code state} ({x, z}, NaN at first) eased toward {@code face} (FACE_EASE per frame, so a head turn swings the
     * camera round smoothly instead of jumping); with no facing, toward the battle's center from {@code p}.
     */
    static double[] followFace(double[] state, double[] face, Vector3d center, Vector3d p) {
        double[] want = face;
        if (want == null) {
            double dx = center != null ? center.x - p.x : 1, dz = center != null ? center.z - p.z : 0;
            double len = Math.hypot(dx, dz);
            want = len < 0.5 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
        }
        if (Double.isNaN(state[0])) {
            state[0] = want[0];
            state[1] = want[1];
        } else {
            double x = state[0] + (want[0] - state[0]) * FACE_EASE, z = state[1] + (want[1] - state[1]) * FACE_EASE;
            double len = Math.hypot(x, z);
            if (len > 1e-3) {
                state[0] = x / len;
                state[1] = z / len;
            }
        }
        return state;
    }

    /**
     * Where a face close-up looks from (2026-10-16, playtest: a close-up stood inside a hill by a cave, another inside the
     * mob in front): the side the face points ({@link #faceDir}), turned to the nearest angle with a clear view
     * ({@link #clearSide}, looked for again every {@link #CLEAR_EVERY} frames), eased frame by frame.
     */
    static final class FaceAim {
        final double[] dir = {Double.NaN, 0};
        /** How far the camera rises over its own height: over the crowd when no side is clear at face height. */
        double lift;
        private double[] want;
        private int frame;

        void reset() {
            dir[0] = Double.NaN;
            lift = 0;
            want = null;
            frame = 0;
        }

        /**
         * This frame's direction {x, z} from {@code p} to the camera: {@code dist} out at {@code camY}, looking at
         * {@code lookY} over {@code p}; {@code fallback} = where the camera goes when no facing can be read (toward it).
         */
        double[] next(Ref<EntityStore> self, Vector3d p, Vector3d fallback, double dist, double camY, double lookY,
                      Iterable<Ref<EntityStore>> others, ComponentAccessor<EntityStore> acc) {
            if (want == null || frame++ % CLEAR_EVERY == 0) {
                double[] base = FxSettings.cameraFace ? faceDir(self, acc) : null;
                if (base == null) {
                    double dx = fallback != null ? fallback.x - p.x : 1, dz = fallback != null ? fallback.z - p.z : 0;
                    double len = Math.hypot(dx, dz);
                    base = len < 0.5 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
                }
                double[] fresh = clearSide(p, base, dist, camY, lookY, self, others, acc, CLEAR_TRIES,
                        new double[] {0, CLEAR_LIFTS[0], CLEAR_LIFTS[1], CLEAR_LIFTS[2]});
                // keep the side already taken while it still sees, unless the face side itself is clear (2026-10-29,
                // playtest video: in a packed royale the spotlight close-ups jumped between sides every few frames); with
                // no clear side at all, stay where it is (audit 2026-10-29: it fell back to the blocked face side)
                boolean faceClear = fresh != null && Math.hypot(fresh[0] - base[0], fresh[1] - base[1]) <= 1e-6 && fresh[2] <= 0;
                if (want != null && want.length > 2 && !faceClear) {
                    double[] kept = clearSide(p, new double[] {want[0], want[1]}, dist, camY, lookY, self, others, acc,
                            new double[] {0}, new double[] {want[2]});
                    if (kept != null || fresh == null) fresh = kept != null ? kept : want;
                }
                want = fresh != null ? fresh : new double[] {base[0], base[1], 0};
            }
            double wantLift = want.length > 2 ? want[2] : 0;
            lift = Double.isNaN(dir[0]) ? wantLift : lift + (wantLift - lift) * LIFT_EASE;
            return followFace(dir, want, null, p);
        }
    }

    /** Turns tried, in order, when a face close-up's view is blocked (radians), and how often it is checked again. */
    private static final double[] CLEAR_TRIES = {0, 0.5, -0.5, 1.0, -1.0, 1.6, -1.6, 2.2, -2.2, Math.PI};
    private static final int CLEAR_EVERY = 5;
    /** No side clear at face height: the camera rises this much over it, looking down at the face (over the crowd). */
    private static final double[] CLEAR_LIFTS = {1, 2, 3};
    private static final double LIFT_EASE = 0.12;

    /**
     * {@code dir} turned (CLEAR_TRIES order) until a camera {@code dist} out from {@code p} along it, at {@code camY},
     * sees the point {@code lookY} over {@code p}: not inside a block, no ground or wall on the line to it, and no
     * other entity's body (of {@code others}, hitboxes read live) around the camera or across the line. None clear =
     * {@code dir} as it was.
     */
    static double[] clearSide(Vector3d p, double[] dir, double dist, double camY, double lookY, Ref<EntityStore> self,
                              Iterable<Ref<EntityStore>> others, ComponentAccessor<EntityStore> acc) {
        double[] r = clearSide(p, dir, dist, camY, lookY, self, others, acc, CLEAR_TRIES,
                new double[] {0, CLEAR_LIFTS[0], CLEAR_LIFTS[1], CLEAR_LIFTS[2]});
        return r != null ? r : new double[] {dir[0], dir[1], 0};
    }

    /** Same, over the given turns and lifts only; null when none sees. */
    static double[] clearSide(Vector3d p, double[] dir, double dist, double camY, double lookY, Ref<EntityStore> self,
                              Iterable<Ref<EntityStore>> others, ComponentAccessor<EntityStore> acc, double[] turns, double[] lifts) {
        List<double[]> bodies = new ArrayList<>();   // {x, z, bottom, top, radius}
        if (others != null) {
            for (Ref<EntityStore> r : others) {
                if (r == null || r == self || !r.isValid() || r.getStore() != acc) continue;
                Vector3d q = ChaosWand.positionOf(r, acc);
                if (q == null || Math.hypot(q.x - p.x, q.z - p.z) > dist + 8) continue;
                double h = 2, w = 0.5;
                BoundingBox bb = acc.getComponent(r, BoundingBox.getComponentType());
                if (bb != null && bb.getBoundingBox() != null) {
                    var box = bb.getBoundingBox();
                    h = box.max.y - box.min.y;
                    w = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2;
                }
                bodies.add(new double[] {q.x, q.z, q.y - 0.3, q.y + h + 0.3, w + 0.4});
            }
        }
        // at face height first, then raised over the heads (2026-10-29, playtest video: in a packed royale every spotlight
        // close-up stood between the bodies)
        for (double lift : lifts) {
            for (double turn : turns) {
                double c = Math.cos(turn), s = Math.sin(turn);
                double ux = dir[0] * c - dir[1] * s, uz = dir[0] * s + dir[1] * c;
                double ax = p.x + ux * dist, az = p.z + uz * dist;
                if (viewClear(ax, camY + lift, az, p.x, lookY, p.z, bodies, acc)) return new double[] {ux, uz, lift};
            }
        }
        return null;
    }

    private static boolean viewClear(double ax, double ay, double az, double bx, double by, double bz,
                                     List<double[]> bodies, ComponentAccessor<EntityStore> acc) {
        // The camera's own block solid (FightFx.roofAbove: NaN), then ground or a wall on the way (as FilmCamera's
        // clearView: floorBelow from 3 over each sample).
        if (Double.isNaN(FightFx.roofAbove(ax, az, ay, 2, acc))) return false;
        if (FilmCamera.inLeaves(ax, ay, az, acc)) return false;
        if (FilmCamera.sideBlocked(new Vector3d(ax, ay, az), new Vector3d(bx, by, bz), acc)) return false;
        for (int i = 1; i <= 7; i++) {
            double k = i / 8.0 * 0.9;
            double x = ax + (bx - ax) * k, y = ay + (by - ay) * k, z = az + (bz - az) * k;
            double g = FightFx.floorBelow(y - 3, acc, true).applyAsDouble(x, z);
            if (Double.isFinite(g) && g > y - 0.2) return false;
        }
        for (double[] b : bodies) {
            // Around the camera, or across the line (closest point of the segment to the body's axis).
            for (int i = 0; i <= 8; i++) {
                double k = i / 8.0 * 0.85;
                double x = ax + (bx - ax) * k, y = ay + (by - ay) * k, z = az + (bz - az) * k;
                if (y >= b[2] && y <= b[3] && Math.hypot(x - b[0], z - b[1]) < b[4]) return false;
            }
        }
        return true;
    }

    /** The strongest fighter's close-up: low in front of it looking up, rising a little, lens 58 to 46 degrees. */
    private static ServerCameraSettings heroShot(Vector3d p, double[] s, double eye, double ux, double uz, double e, double lift) {
        double dist = heroDist(s, e);
        double side = Math.max(0.6, s[1] * 0.4);
        Vector3d at = new Vector3d(p.x + ux * dist - uz * side, p.y + Math.max(0.5, s[0] * 0.15) + eye * 0.2 * e + lift,
                p.z + uz * dist + ux * side);
        return zoom(frame(at, new Vector3d(p.x, p.y + eye * 0.85, p.z), DUEL_LERP), 58 - 12 * e);
    }

    /**
     * The spotlight's crowd shot: low from the side the player watches from, sliding sideways across the fighters it
     * holds (their live spread), never under the ground or a roof.
     */
    private static Shot crowdShot(List<Ref<EntityStore>> fighters, PlayerRef viewer, long ms) {
        Track track = new Track();
        double[][] holder = {null};
        double[] roof = {Double.NaN};
        Settle settle = new Settle();
        return new Shot(ms, (t, acc) -> {
            double x = 0, y = 0, z = 0, tall = NORMAL_SIZE[0];
            List<Vector3d> pts = new ArrayList<>();
            for (Ref<EntityStore> r : fighters) {
                Vector3d p = track.pos(r, acc);
                if (p == null) continue;
                pts.add(p);
                x += p.x; y += p.y; z += p.z;
                tall = Math.max(tall, track.size(r, acc)[0]);
            }
            if (pts.isEmpty()) return null;
            // one height band (a cave and the surface: the center fell inside the rock between them)
            pts = FilmCamera.mainBand(pts, null);
            x = 0; y = 0; z = 0;
            for (Vector3d p : pts) {
                x += p.x; y += p.y; z += p.z;
            }
            Vector3d c = new Vector3d(x / pts.size(), y / pts.size(), z / pts.size());
            double spread = 0;
            for (Vector3d p : pts) spread = Math.max(spread, Math.hypot(p.x - c.x, p.z - c.z));
            spread = Math.min(spread, 40);
            if (holder[0] == null) {
                var tr = viewer.getTransform();
                double dx = 1, dz = 0;
                if (tr != null) {
                    dx = c.x - tr.getPosition().x;
                    dz = c.z - tr.getPosition().z;
                }
                double len = Math.hypot(dx, dz);
                double[] dir = len < 0.5 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
                holder[0] = crowdSpot(c, dir, Math.max(8, spread * 1.1 + tall * 1.2 + 4),
                        Math.max(2.5, tall * 0.6) + spread * 0.15, Math.max(4, spread * 1.2), tall, acc);
            }
            double ux = holder[0][0], uz = holder[0][1], px = -uz, pz = ux;
            double f = (double) t / ms;
            double e = f * f * (3 - 2 * f);
            double along = (e - 0.5) * Math.max(4, spread * 1.2);
            double dist = Math.max(8, spread * 1.1 + tall * 1.2 + 4) * holder[0][2];
            double up = Math.max(2.5, tall * 0.6) + spread * 0.15;
            double ax = c.x - ux * dist + px * along, az = c.z - uz * dist + pz * along;
            double ground = canopy(ax, az, c.y + up, c.y + 1.5, roof, true, acc);
            double ay = underRoof(Math.max(c.y + up, ground + 1.5), roof);
            Vector3d look = new Vector3d(c.x + px * along * 0.4, c.y + Math.max(1.2, tall * 0.4), c.z + pz * along * 0.4);
            return zoom(frame(settle.next(new Vector3d(ax, ay, az), look, c.y, acc), look, DUEL_LERP), 74);
        });
    }

    /**
     * Keeps an intro shot's camera seeing its subject frame by frame (2026-10-29, playtest video: the spotlight's crowd
     * shot, checked only at 3 points of its slide, crossed a pillar and ended in a tree on the stands): every
     * SETTLE_EVERY frames, a camera inside a block or leaves, with no clear view, or over ground more than SETTLE_RISE
     * above the fight (the stands) slides toward what it looks at, whole steps up to SETTLE_MAX, eased.
     */
    static final class Settle {
        private double share, want;
        private int frame;

        Vector3d next(Vector3d at, Vector3d look, double level, ComponentAccessor<EntityStore> acc) {
            if (!(acc instanceof Store<EntityStore> store)) return at;
            boolean first = frame == 0;
            if (frame++ % SETTLE_EVERY == 0) {
                want = 0;
                for (int i = 0; i <= SETTLE_STEPS; i++) {
                    double k = i * SETTLE_MAX / SETTLE_STEPS;
                    Vector3d q = toward(at, look, k);
                    double g = FightFx.floorBelow(q.y - 3, store, true).applyAsDouble(q.x, q.z);
                    if ((!Double.isFinite(g) || g <= level + SETTLE_RISE) && FilmCamera.clearView(q, look, store)) {
                        want = k;
                        break;
                    }
                }
            }
            share = first ? want : share + (want - share) * SETTLE_EASE;
            return share > 0.01 ? toward(at, look, share) : at;
        }

        private static Vector3d toward(Vector3d a, Vector3d b, double k) {
            return new Vector3d(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k);
        }
    }

    private static final int SETTLE_EVERY = 5, SETTLE_STEPS = 8;
    private static final double SETTLE_MAX = 0.8, SETTLE_RISE = 4, SETTLE_EASE = 0.25;

    /**
     * The crowd shot's side and distance: from the viewer's side when the camera sees the fighters from there at the
     * start, middle and end of its slide, else turned (CROWD_TURNS), else closer (CROWD_CLOSER). Returns {ux, uz,
     * distance share}. Without it a viewer near an arena's edge put the camera in the colosseum's corridors behind the
     * wall (playtest 2026-10-29). A non-Store accessor (no world to read) keeps the viewer's side.
     */
    private static double[] crowdSpot(Vector3d c, double[] dir, double dist, double up, double slide, double tall,
                                      ComponentAccessor<EntityStore> acc) {
        if (!(acc instanceof Store<EntityStore> store)) return new double[] {dir[0], dir[1], 1};
        Vector3d look = new Vector3d(c.x, c.y + Math.max(1.2, tall * 0.4), c.z);
        for (double share : CROWD_CLOSER) {
            for (double turn : CROWD_TURNS) {
                double cos = Math.cos(turn), sin = Math.sin(turn);
                double ux = dir[0] * cos - dir[1] * sin, uz = dir[0] * sin + dir[1] * cos;
                boolean clear = true;
                for (double k : new double[] {-0.5, 0, 0.5}) {
                    Vector3d at = new Vector3d(c.x - ux * dist * share - uz * slide * k, c.y + up, c.z - uz * dist * share + ux * slide * k);
                    if (!FilmCamera.clearView(at, look, store)) {
                        clear = false;
                        break;
                    }
                }
                if (clear) return new double[] {ux, uz, share};
            }
        }
        return new double[] {dir[0], dir[1], CROWD_CLOSER[CROWD_CLOSER.length - 1]};
    }

    private static final double[] CROWD_TURNS = {0, 0.5, -0.5, 1, -1, 1.6, -1.6, 2.2, -2.2, Math.PI};
    private static final double[] CROWD_CLOSER = {1, 0.75, 0.5};

    /** A fighter's strength for the spotlight: its max health (buffs included), and a hair more for its height. */
    private static double strengthOf(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
        try {
            if (!r.isValid() || r.getStore() != acc) return 0;
            var stats = acc.getComponent(r, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
            var h = stats != null ? stats.get(com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes.getHealth()) : null;
            double health = h != null ? h.getMax() : 0;
            BoundingBox bb = acc.getComponent(r, BoundingBox.getComponentType());
            double tall = bb != null && bb.getBoundingBox() != null ? bb.getBoundingBox().max.y - bb.getBoundingBox().min.y : 2;
            return health + tall * 0.01;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Team by team: one shot per side, from between the side and the battle's center (where they face). It holds
     * on one member, then sweeps across the others, left to right.
     */
    private static void teams(BattleManager.Kind kind, PlayerRef player, Vector3d center, List<List<Ref<EntityStore>>> sides, long total,
                              ComponentAccessor<EntityStore> buffer) {
        List<List<Ref<EntityStore>>> shown = sides.size() > MAX_TEAM_SHOTS ? sides.subList(0, MAX_TEAM_SHOTS) : sides;
        long each = Math.max(STEP_MS, (total - BACK_MS) / shown.size());
        Track track = new Track();
        List<Shot> shots = new ArrayList<>();
        for (List<Ref<EntityStore>> side : shown) {
            List<Ref<EntityStore>> members = sortedAcross(side, center, buffer);
            shots.add(new Shot(each, (t, acc) -> {
                double cx = 0, cy = 0, cz = 0;
                int k = 0;
                for (Ref<EntityStore> m : members) {
                    Vector3d p = track.pos(m, acc);
                    if (p == null) continue;
                    cx += p.x; cy += p.y; cz += p.z;
                    k++;
                }
                if (k == 0) return null;
                Vector3d c = new Vector3d(cx / k, cy / k, cz / k);
                double spread = 1.5, tall = NORMAL_SIZE[0], wide = NORMAL_SIZE[1];
                for (Ref<EntityStore> m : members) {
                    Vector3d p = track.pos(m, acc);
                    if (p != null) spread = Math.max(spread, Math.hypot(p.x - c.x, p.z - c.z));
                    double[] sz = track.size(m, acc);
                    tall = Math.max(tall, sz[0]);
                    wide = Math.max(wide, sz[1]);
                }
                double dx = center.x - c.x, dz = center.z - c.z;
                double len = Math.hypot(dx, dz);
                if (len < 1) { dx = 1; dz = 0; len = 1; }
                // Bigger members (a boss, a size buff): further back and higher, so their whole upper half is seen.
                double dist = Math.min(Math.max(spread + 5, spread + wide + tall * 1.1), 60);
                Vector3d at = new Vector3d(c.x + dx / len * dist, c.y + Math.max(2.5, tall * 0.7) + spread * 0.25, c.z + dz / len * dist);
                // Hold on the first one, then sweep across the rest.
                double u = (double) t / each;
                // Few stops, each kept at least FOCUS_HOLD_MS (evenly spread over the team): one stop per member made
                // the look-at jump many times a second in a big team.
                int idx = 0;
                if (members.size() > 1 && u >= HOLD) {
                    int steps = (int) Math.max(1, Math.min(members.size() - 1, (long) (each * (1 - HOLD)) / FOCUS_HOLD_MS));
                    int stop = (int) Math.min(steps - 1, (u - HOLD) / (1 - HOLD) * steps);
                    idx = 1 + stop * (members.size() - 1) / steps;
                }
                Vector3d look = track.pos(members.get(idx), acc);
                if (look == null) look = c;
                return frame(at, new Vector3d(look.x, look.y + lookHeight(track.size(members.get(idx), acc)) - 0.3, look.z), DUEL_LERP);
            }));
        }
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Everyone vs one: a close-up of the target, then a circle around it at the crowd's distance, the camera
     * low and outside the crowd, always looking at the attacker in front of it (one after another as it turns),
     * with the target behind.
     */
    private static void focus(BattleManager.Kind kind, PlayerRef player, Ref<EntityStore> target, List<Ref<EntityStore>> crowd, long total,
                              ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS * 2, total - BACK_MS);
        long first = Math.max(Math.min(MIN_SHOT_MS, usable / 2), usable * 35 / 100);
        long circle = Math.max(STEP_MS, usable - first);
        Vector3d t0 = ChaosWand.positionOf(target, buffer);
        if (t0 == null) return;
        // Ring just outside the crowd's average distance; start behind the first attacker found.
        double sum = 0, start = 0;
        int n = 0;
        for (Ref<EntityStore> r : crowd) {
            Vector3d q = ChaosWand.positionOf(r, buffer);
            if (q == null) continue;
            if (n == 0) start = Math.atan2(q.z - t0.z, q.x - t0.x);
            sum += Math.hypot(q.x - t0.x, q.z - t0.z);
            n++;
        }
        double ring = Math.max(6, Math.min(40, (n > 0 ? sum / n : 4) + 4));
        double a0 = start;
        Track track = new Track();
        List<Shot> shots = new ArrayList<>();
        shots.add(new Shot(first, (t, acc) -> {
            Vector3d q = track.pos(target, acc);
            if (q == null) return null;
            double eye = track.eye(target, acc);
            return closeUp(q, track.yaw(target, acc), eye, -0.3 + 0.6 * t / first, closeDist(3, 2.2, eye, track.size(target, acc)), eye * 0.9 + 0.4);
        }));
        // The attacker in front of the camera, kept at least FOCUS_HOLD_MS: picking the closest one every frame made
        // the camera jump from one to another many times a second in a big crowd.
        @SuppressWarnings("unchecked")
        Ref<EntityStore>[] held = new Ref[1];
        long[] heldSince = {-FOCUS_HOLD_MS};
        double[] focusRoof = {Double.NaN};
        double[] ringShare = {1};   // eased share of the radius that still sees the target (walls, the arena edge)
        double[] ringWant = {1};
        int[] ringFrame = {0};
        shots.add(new Shot(circle, (t, acc) -> {
            Vector3d c = track.pos(target, acc);
            if (c == null) return null;
            // A big target (boss, size buff): the circle opens up and rises so it is not filmed from inside.
            double[] ts = track.size(target, acc);
            double a = a0 + FOCUS_SWEEP * t / circle;
            double r0 = Math.max(ring, Math.min(60, ts[1] + ts[0] * 1.4 + 2));
            // Closer while a wall stands between the circle and the target (review 2026-10-29: attackers by the arena
            // wall put the ring in the colosseum's corridors).
            // checked every CLEAR_EVERY frames (audit 2026-10-29: up to four view tests a frame), eased every frame
            if (acc instanceof com.hypixel.hytale.component.Store<EntityStore> st && ringFrame[0]++ % CLEAR_EVERY == 0) {
                Vector3d lookAt = new Vector3d(c.x, c.y + Math.max(1.2, ts[0] * 0.5), c.z);
                double want = RING_SHARES[RING_SHARES.length - 1];
                for (double k : RING_SHARES) {
                    double rr = Math.max(4, r0 * k);
                    Vector3d cam = new Vector3d(c.x + Math.cos(a) * rr, c.y + Math.max(2.5, ts[0] * 0.7) + rr * 0.15, c.z + Math.sin(a) * rr);
                    if (FilmCamera.clearView(cam, lookAt, st)) {
                        want = k;
                        break;
                    }
                }
                ringWant[0] = want;
            }
            ringShare[0] += (ringWant[0] - ringShare[0]) * (t == 0 ? 1 : 0.15);
            double r = Math.max(4, r0 * ringShare[0]);
            double x = c.x + Math.cos(a) * r, z = c.z + Math.sin(a) * r;
            double y = underRoof(Math.max(c.y + Math.max(2.5, ts[0] * 0.7) + r * 0.15,
                    canopy(x, z, c.y, c.y + 1.5, focusRoof, true, acc) + 2.5), focusRoof);
            boolean keep = held[0] != null && held[0].isValid() && BattleManager.isAlive(held[0], acc)
                    && t - heldSince[0] < FOCUS_HOLD_MS;
            if (!keep) {
                // The living attacker whose direction from the target is closest to the camera's.
                Ref<EntityStore> pick = null;
                double best = Double.MAX_VALUE;
                for (Ref<EntityStore> m : crowd) {
                    if (!m.isValid() || !BattleManager.isAlive(m, acc)) continue;
                    Vector3d q = track.pos(m, acc);
                    if (q == null) continue;
                    double d = Math.abs(Math.IEEEremainder(Math.atan2(q.z - c.z, q.x - c.x) - a, Math.PI * 2));
                    if (d < best) {
                        best = d;
                        pick = m;
                    }
                }
                if (pick != held[0]) heldSince[0] = t;
                held[0] = pick;
            }
            Vector3d q = held[0] != null ? track.pos(held[0], acc) : null;
            // Halfway between that attacker and the target: the target never leaves the shot, and a change of
            // attacker only moves the view half as far.
            Vector3d look = q == null ? c : new Vector3d((q.x + c.x) / 2, (q.y + c.y) / 2, (q.z + c.z) / 2);
            return frame(new Vector3d(x, y, z), new Vector3d(look.x, look.y + Math.max(1.2, ts[0] * 0.4), look.z), DUEL_LERP);
        }));
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Drone: one low flight across the battle, from the player's side to the far side, along a smooth curve that
     * weaves past a few fighters (left of one, right of the next) without stopping. It keeps {@link #DRONE_LOW}
     * blocks over the ground or the tree tops under it and just ahead (climbs fast, sinks slowly), looks ahead along
     * its path, a little down, and turns toward the fighter it is coming up to, then ahead again once past it.
     * (2026-10-13, playtest video: the old one stopped at each fighter, stared down at it and climbed away, and flew
     * through tree tops: leaves are not Solid blocks.)
     */
    private static void drone(BattleManager.Kind kind, PlayerRef player, Vector3d center, List<Ref<EntityStore>> fighters, long total,
                              ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS * 2, total - BACK_MS);
        double spread = 0;
        for (Ref<EntityStore> r : fighters) {
            Vector3d q = ChaosWand.positionOf(r, buffer);
            if (q != null) spread = Math.max(spread, Math.hypot(q.x - center.x, q.z - center.z));
        }
        double r = Math.max(12, Math.min(80, spread + 8));
        Eye eye = eyeOf(player, buffer);
        double aS = eye != null ? Math.atan2(eye.pos.z - center.z, eye.pos.x - center.x) : 0;
        double ux = -Math.cos(aS), uz = -Math.sin(aS);    // flight direction (toward the far side)
        double px = -uz, pz = ux;                          // sideways
        Vector3d from = new Vector3d(center.x - ux * r, center.y, center.z - uz * r);
        Vector3d to = new Vector3d(center.x + ux * r, center.y, center.z + uz * r);
        // Indoor arena (a roof over the center; playtest video 2026-10-13: the drone started and ended inside the stands
        // and flew through their corridors): its ends and passing points are pulled in to open floor.
        double level = center.y + 1.5;
        boolean indoor = Double.isFinite(FightFx.roofAbove(center.x, center.z, level, (int) GROUND_SCAN, buffer));
        if (indoor) {
            from = inward(from, center, level, buffer);
            to = inward(to, center, level, buffer);
        }
        Vector3d start = from, end = to;
        long flyMs = Math.max(STEP_MS, (long) (usable * DRONE_FLY_SHARE));
        // The fighters it flies past, in flight order.
        int passes = (int) Math.max(1, Math.min(DRONE_MAX_PASSES, Math.min(fighters.size(), usable / DRONE_PASS_MS)));
        List<Ref<EntityStore>> picked = new ArrayList<>(fighters);
        java.util.Collections.shuffle(picked);
        picked = new ArrayList<>(picked.subList(0, passes));
        Map<Ref<EntityStore>, Double> along = new java.util.HashMap<>();
        for (Ref<EntityStore> f : picked) {
            Vector3d q = ChaosWand.positionOf(f, buffer);
            along.put(f, q == null ? 0 : (q.x - center.x) * ux + (q.z - center.z) * uz);
        }
        picked.sort(java.util.Comparator.comparingDouble(along::get));
        List<Ref<EntityStore>> stops = picked;
        Track track = new Track();
        double[] alt = {Double.NaN};
        double[] roof = {Double.NaN};
        Vector3d[] look = {null};
        // Indoors, each stop's side point (open floor beside the fighter) per stop: {fighter x, z, side x, z, frame}.
        // Worked out again only when the fighter moved over a block or every SIDE_REFRESH_FRAMES frames (audit
        // 2026-10-14: up to ~150 column scans a frame per viewer in a roofed arena).
        double[][] sideCache = new double[stops.size()][];
        int[] frame = {0};
        List<Shot> shots = List.of(new Shot(usable, (t, acc) -> {
            frame[0]++;
            // Waypoints from the live positions: start edge, beside each fighter (sides alternate), far edge.
            int n = stops.size() + 2;
            double[] xs = new double[n], zs = new double[n];
            xs[0] = start.x;
            zs[0] = start.z;
            xs[n - 1] = end.x;
            zs[n - 1] = end.z;
            Vector3d[] at = new Vector3d[stops.size()];
            for (int i = 0; i < stops.size(); i++) {
                Vector3d q = track.pos(stops.get(i), acc);
                at[i] = q != null ? q : center;
                double side = (i % 2 == 0 ? 1 : -1) * Math.max(DRONE_SIDE, track.size(stops.get(i), acc)[1] + 3);
                double sx = at[i].x + px * side, sz = at[i].z + pz * side;
                double[] kept = sideCache[i];
                if (indoor && kept != null && frame[0] - kept[4] < SIDE_REFRESH_FRAMES
                        && Math.hypot(at[i].x - kept[0], at[i].z - kept[1]) <= 1) {
                    sx = kept[2];
                    sz = kept[3];
                } else if (indoor) {
                    if (!droneOpen(sx, sz, level, acc)) {
                        // A wall or the stands on that side: the other side, else closer in.
                        double ox = at[i].x - px * side, oz = at[i].z - pz * side;
                        Vector3d in = droneOpen(ox, oz, level, acc) ? new Vector3d(ox, 0, oz)
                                : inward(new Vector3d(sx, 0, sz), at[i], level, acc);
                        sx = in.x;
                        sz = in.z;
                    }
                    sideCache[i] = new double[] {at[i].x, at[i].z, sx, sz, frame[0]};
                }
                xs[i + 1] = sx;
                zs[i + 1] = sz;
            }
            double[] cum = new double[n];
            for (int i = 1; i < n; i++) cum[i] = cum[i - 1] + Math.max(0.01, Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]));
            // Nearly even speed, easing only into the start and out of the end. After the pass (flyMs) it swings
            // around the battle like the orbit (playtest 2026-10-13), from where the pass ended, a little closer in.
            boolean orbiting = t > flyMs;
            double[] p, q;
            if (!orbiting) {
                double u = Math.min(1, (double) t / flyMs);
                double s = (0.6 * u + 0.4 * u * u * (3 - 2 * u)) * cum[n - 1];
                p = spline(xs, zs, cum, s);
                q = spline(xs, zs, cum, s + 1);
            } else {
                double f = Math.min(1, (double) (t - flyMs) / Math.max(1, usable - flyMs));
                double a0 = Math.atan2(xs[n - 1] - center.x, zs[n - 1] - center.z);
                double r0 = Math.max(DRONE_ORBIT_MIN, Math.hypot(xs[n - 1] - center.x, zs[n - 1] - center.z));
                double rad = r0 * (1 - (1 - DRONE_ORBIT_SHRINK) * Math.min(1, f * 3));
                double a = a0 + DRONE_ORBIT_SWEEP * f;
                double a2 = a + 0.05;
                p = new double[] {center.x + Math.sin(a) * rad, center.z + Math.cos(a) * rad};
                q = new double[] {center.x + Math.sin(a2) * rad, center.z + Math.cos(a2) * rad};
                if (indoor && !droneOpen(p[0], p[1], level, acc)) {
                    Vector3d in = inward(new Vector3d(p[0], 0, p[1]), center, level, acc);
                    p = new double[] {in.x, in.z};
                }
            }
            double dx = q[0] - p[0], dz = q[1] - p[1];
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) {
                dx = ux;
                dz = uz;
            } else {
                dx /= len;
                dz /= len;
            }
            // Height: over the highest ground or tree top here and a little ahead.
            double from0 = Double.isNaN(alt[0]) ? center.y + DRONE_LOW : alt[0];
            double here = canopy(p[0], p[1], from0, level, roof, true, acc);
            double top = here;
            for (double ahead : DRONE_AHEAD) {
                top = Math.max(top, canopy(p[0] + dx * ahead, p[1] + dz * ahead, from0, level, roof, false, acc));
            }
            double want = top + DRONE_LOW;
            if (Double.isNaN(alt[0])) alt[0] = want;
            else alt[0] += (want - alt[0]) * (want > alt[0] ? DRONE_CLIMB : DRONE_SINK);
            alt[0] = underRoof(Math.max(alt[0], here + DRONE_CLEAR), roof);
            Vector3d pos = new Vector3d(p[0], alt[0], p[1]);
            // Ahead along the path, a little down; turned toward the fighter it is coming up to.
            Vector3d target = orbiting ? new Vector3d(center.x, center.y + 1.5, center.z)
                    : new Vector3d(p[0] + dx * DRONE_LOOK, alt[0] - DRONE_LOOK_DOWN, p[1] + dz * DRONE_LOOK);
            double best = orbiting ? 0 : DRONE_TURN_RANGE;
            Vector3d mob = null;
            double mobEye = 1;
            for (int i = 0; i < at.length; i++) {
                double ahead = (at[i].x - p[0]) * dx + (at[i].z - p[1]) * dz;
                if (ahead > 0.5 && ahead < best) {
                    best = ahead;
                    mob = at[i];
                    mobEye = track.eye(stops.get(i), acc);
                }
            }
            if (mob != null) {
                double w = DRONE_TURN * (1 - best / DRONE_TURN_RANGE);
                target = new Vector3d(target.x + (mob.x - target.x) * w, target.y + (mob.y + mobEye * 0.6 - target.y) * w,
                        target.z + (mob.z - target.z) * w);
            }
            Vector3d prev = look[0];
            look[0] = prev == null ? target : new Vector3d(prev.x + (target.x - prev.x) * DRONE_LOOK_EASE,
                    prev.y + (target.y - prev.y) * DRONE_LOOK_EASE, prev.z + (target.z - prev.z) * DRONE_LOOK_EASE);
            return frame(pos, look[0], DRONE_LERP);
        }));
        playShots(kind, player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Point at distance {@code s} along a Catmull-Rom curve through (xs, zs) ({@code cum}: distance to each point
     * along the straight segments), clamped to the ends.
     */
    private static double[] spline(double[] xs, double[] zs, double[] cum, double s) {
        int n = xs.length;
        if (s <= 0) return new double[] {xs[0], zs[0]};
        if (s >= cum[n - 1]) return new double[] {xs[n - 1], zs[n - 1]};
        int i = 0;
        while (i < n - 2 && s > cum[i + 1]) i++;
        double f = (s - cum[i]) / (cum[i + 1] - cum[i]);
        int a = Math.max(0, i - 1), d = Math.min(n - 1, i + 2);
        return new double[] {catmull(xs[a], xs[i], xs[i + 1], xs[d], f), catmull(zs[a], zs[i], zs[i + 1], zs[d], f)};
    }

    private static double catmull(double p0, double p1, double p2, double p3, double f) {
        double f2 = f * f, f3 = f2 * f;
        return 0.5 * (2 * p1 + (p2 - p0) * f + (2 * p0 - 5 * p1 + 4 * p2 - p3) * f2 + (3 * p1 - p0 - 3 * p2 + p3) * f3);
    }

    /**
     * Top of the ground or tree leaves at (x, z), searched from a bit above {@code fromY} down. Under a roof seen from
     * the fighters' {@code level} (an indoor arena, playtest 2026-10-13: the drone flew over it and then rode on the roof)
     * the search starts right under the roof. {@code roof[0]} is the last roof met: a wall column keeps it, open sky
     * clears it (only when {@code update}); callers keep the camera ROOF_CLEAR under it.
     */
    static double canopy(double x, double z, double fromY, double level, double[] roof, boolean update,
                                 ComponentAccessor<EntityStore> acc) {
        double r = FightFx.roofAbove(x, z, level, (int) GROUND_SCAN, acc);
        if (update) {
            if (r == Double.POSITIVE_INFINITY) roof[0] = Double.NaN;
            else if (!Double.isNaN(r)) roof[0] = r;
        }
        double over = r != Double.POSITIVE_INFINITY && !Double.isNaN(r) ? r : Double.isNaN(r) ? roof[0] : Double.NaN;
        // floorBelow starts 3 blocks above its argument: start right under the roof.
        double scan = Double.isNaN(over) ? fromY + GROUND_SCAN : over - 4;
        double g = FightFx.floorBelow(scan, acc, true).applyAsDouble(x, z);
        return Double.isFinite(g) ? g : fromY - DRONE_LOW;
    }

    /**
     * Indoor drone: a column it can fly through at the fighters' {@code level}: not inside a wall, not over the raised
     * stands (floor more than 1.5 blocks above the level), not under a low ceiling (a corridor under the stands).
     */
    private static boolean droneOpen(double x, double z, double level, ComponentAccessor<EntityStore> acc) {
        double roof = FightFx.roofAbove(x, z, level, (int) GROUND_SCAN, acc);
        if (Double.isNaN(roof)) return false;
        boolean sky = roof == Double.POSITIVE_INFINITY;
        // floorBelow starts 3 blocks above its argument.
        double floor = FightFx.floorBelow(sky ? level - 1 : Math.min(roof, level + 2) - 4, acc, false).applyAsDouble(x, z);
        if (!Double.isFinite(floor) || floor > level + 1.5) return false;
        return sky || roof - floor >= DRONE_HEADROOM;
    }

    /** {@code p} moved toward {@code toward} until it is open (droneOpen), at most all the way. */
    private static Vector3d inward(Vector3d p, Vector3d toward, double level, ComponentAccessor<EntityStore> acc) {
        for (int i = 0; i <= 10; i++) {
            double t = 1 - i * 0.1;
            double x = toward.x + (p.x - toward.x) * t, z = toward.z + (p.z - toward.z) * t;
            if (droneOpen(x, z, level, acc)) return new Vector3d(x, p.y, z);
        }
        return new Vector3d(toward.x, p.y, toward.z);
    }

    /** {@code y}, kept ROOF_CLEAR under the roof met last, if any. */
    static double underRoof(double y, double[] roof) {
        return Double.isNaN(roof[0]) ? y : Math.min(y, roof[0] - ROOF_CLEAR);
    }


    /** The side's members ordered left to right as seen from the battle's center. */
    private static List<Ref<EntityStore>> sortedAcross(List<Ref<EntityStore>> side, Vector3d center,
                                                       ComponentAccessor<EntityStore> buffer) {
        Vector3d c = centroid(side, buffer);
        List<Ref<EntityStore>> out = new ArrayList<>(side);
        if (c == null) return out;
        double dx = c.x - center.x, dz = c.z - center.z;
        double px = -dz, pz = dx;   // sideways
        Map<Ref<EntityStore>, Double> across = new java.util.HashMap<>();
        for (Ref<EntityStore> r : side) {
            Vector3d p = ChaosWand.positionOf(r, buffer);
            across.put(r, p == null ? 0 : (p.x - c.x) * px + (p.z - c.z) * pz);
        }
        out.sort(java.util.Comparator.comparingDouble(across::get));
        return out;
    }

    private static Vector3d centroid(List<Ref<EntityStore>> refs, ComponentAccessor<EntityStore> buffer) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (Ref<EntityStore> r : refs) {
            Vector3d p = r.isValid() ? ChaosWand.positionOf(r, buffer) : null;
            if (p == null) continue;
            x += p.x; y += p.y; z += p.z;
            n++;
        }
        return n == 0 ? null : new Vector3d(x / n, y / n, z / n);
    }

    /**
     * Close-up of someone at {@code p} facing {@code yaw}: from the front, turned {@code swing} radians to the
     * side, {@code dist} away and {@code up} above the feet, looking at about chest height.
     */
    private static ServerCameraSettings closeUp(Vector3d p, float yaw, double eye, double swing, double dist, double up) {
        double fx = -Math.sin(yaw), fz = -Math.cos(yaw);   // forward (PhysicsMath.headingX/Z)
        double cos = Math.cos(swing), sin = Math.sin(swing);
        double rx = fx * cos - fz * sin, rz = fx * sin + fz * cos;
        return frame(new Vector3d(p.x + rx * dist, p.y + up, p.z + rz * dist),
                new Vector3d(p.x, p.y + eye * 0.75, p.z), DUEL_LERP);
    }

    /** One shot of a server camera sequence: {@code ms} long, framed by {@code frame} (world thread). */
    private record Shot(long ms, Framer frame) {
    }

    @FunctionalInterface
    private interface Framer {
        /** The camera {@code t} ms into the shot, or null to keep the last frame. */
        ServerCameraSettings at(long t, ComponentAccessor<EntityStore> accessor);
    }

    /** Live positions, facing and eye height of the filmed entities, remembering the last known ones. */
    private static final class Track {
        private final Map<Ref<EntityStore>, Vector3d> last = new java.util.HashMap<>();
        private final Map<Ref<EntityStore>, Float> yaw = new java.util.HashMap<>();
        private final Map<Ref<EntityStore>, Double> eye = new java.util.HashMap<>();
        private final Map<Ref<EntityStore>, double[]> size = new java.util.HashMap<>();

        Vector3d pos(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
            Vector3d p = r.isValid() && r.getStore() == acc ? ChaosWand.positionOf(r, acc) : null;
            if (p != null) last.put(r, p);
            return last.get(r);
        }

        float yaw(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
            TransformComponent t = r.isValid() && r.getStore() == acc
                    ? acc.getComponent(r, TransformComponent.getComponentType()) : null;
            if (t != null) yaw.put(r, t.getRotation().yaw());
            return yaw.getOrDefault(r, 0f);
        }

        /**
         * {height, half width} of the hitbox (already grown by a size buff or the builder tools' scale), the last
         * known one if it can't be read now, else a normal mob's.
         */
        double[] size(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
            BoundingBox bb = r.isValid() && r.getStore() == acc ? acc.getComponent(r, BoundingBox.getComponentType()) : null;
            if (bb != null && bb.getBoundingBox() != null) {
                var box = bb.getBoundingBox();
                double h = box.max.y - box.min.y;
                double w = Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2;
                if (h > 0) size.put(r, new double[] {Math.min(MAX_FRAMED, h), Math.min(MAX_FRAMED, Math.max(0.3, w))});
            }
            return size.getOrDefault(r, NORMAL_SIZE);
        }

        /**
         * Eye height for the close-ups: the model's, or 0.8 of the hitbox height when that is more (a size buff grows
         * the hitbox, not the model; a giant boss went past the old cap of 12 and was filmed at the knees).
         */
        double eye(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
            double model = eye.computeIfAbsent(r, k -> {
                ModelComponent mc = k.isValid() && k.getStore() == acc
                        ? acc.getComponent(k, ModelComponent.getComponentType()) : null;
                Model m = mc != null ? mc.getModel() : null;
                double h = m != null ? m.getEyeHeight(k, acc) : 1.6;
                return Math.max(0.6, Math.min(MAX_FRAMED, h));
            });
            size(r, acc);
            double[] box = size.get(r);   // only a hitbox really read, not NORMAL_SIZE (small mobs)
            return box != null ? Math.max(model, box[0] * 0.8) : model;
        }
    }

    /**
     * Plays the shots one after another for this player: a server camera frame every {@link #STEP_MS}, read on
     * the world thread, then their normal camera. A newer sequence for the same player replaces this one (its frames
     * stop). {@code kind}'s cameraBars slide in for the shots and out at the end (cameraHud hides the game's HUD
     * meanwhile); its cameraBlur sets each frame's depth of field (null kind = the global values).
     * <p>
     * An intro (introFor set {@link #INTRO_FIGHTERS}) never comes back to the player through a shot of them (2026-10-16,
     * playtest: the glide behind the player between the intro and the fight felt wrong): with autoFilm it hands the camera
     * straight to the battle film ({@link #handOff}), else it ends on a wide shot of the fighters running at each
     * other ({@link #wideTail}) and then cuts back to the player.
     */
    private static void playShots(BattleManager.Kind kind, PlayerRef player,
                                  com.hypixel.hytale.server.core.universe.world.World world, List<Shot> intro) {
        UUID uuid = player.getUuid();
        long id = SEQ.incrementAndGet();
        SHOT.put(uuid, id);
        ORBITS.remove(uuid);
        List<Ref<EntityStore>> fighters = INTRO_FIGHTERS.get();
        boolean handoff = fighters != null && kind != null && KindFx.autoFilm(kind);
        List<Shot> shots = intro;
        if (fighters != null && !handoff) {
            shots = new ArrayList<>(intro);
            shots.add(wideTail(fighters, player));
        }
        long total = 0;
        for (Shot shot : shots) total += shot.ms();
        long end = total;
        float blur = blurOf(KindFx.cameraBlur(kind));
        CinemaBars.in(player, KindFx.cameraBars(kind));
        CameraHud.hide(player);
        List<Shot> played = shots;
        // One frame task at a time, each scheduling the next: a 30 s camera to a whole world of viewers used to
        // queue 600 tasks per viewer up front. A newer sequence (or a cut) stops the chain at its next step.
        Runnable[] step = new Runnable[1];
        long[] at = {0};
        step[0] = () -> {
            long t = at[0];
            if (t >= end) {
                if (handoff) handOff(player, id);
                else if (SHOT.remove(uuid, id)) reset(player);
                return;
            }
            long start = 0;
            Shot current = played.get(played.size() - 1);
            for (Shot shot : played) {
                if (t < start + shot.ms()) {
                    current = shot;
                    break;
                }
                start += shot.ms();
            }
            Shot shot = current;
            long local = t - start;
            // Viewer gone or moved to another world: end the chain here, so no further frame is queued, and give them
            // their HUD, bands and camera back where they are now (reset follows the player; audit 2026-10-17: the
            // game's HUD stayed hidden in the new world).
            if (!viewerIn(player, world)) { if (SHOT.remove(uuid, id)) reset(player); return; }
            world.execute(() -> {
                if (!Long.valueOf(id).equals(SHOT.get(uuid))) return;
                if (!viewerIn(player, world)) { if (SHOT.remove(uuid, id)) reset(player); return; }
                ServerCameraSettings s = shot.frame().at(local, world.getEntityStore().getStore());
                if (s != null) send(player, blurred(s, blur));
            });
            at[0] = t + STEP_MS;
            schedule(uuid, id, STEP_MS, step[0]);
        };
        schedule(uuid, id, 0, step[0]);
    }

    /** Who the intro being built films (set by introFor around the style call; null for end cameras). */
    private static final ThreadLocal<List<Ref<EntityStore>>> INTRO_FIGHTERS = new ThreadLocal<>();
    /** The wide shot that closes an intro, while the fighters run at each other. */
    private static final long TAIL_MS = 2500;
    /** autoFilm: how long the last intro frame holds for the film before the player gets their camera back. */
    private static final long HANDOFF_MS = 2500;

    /**
     * End of an intro with autoFilm: lets go of the camera without resetting it (the bands and the hidden HUD stay,
     * the client holds the last frame), so FilmCamera.afterIntro, which waits for the camera to be free, takes it
     * at once. Nobody does within {@link #HANDOFF_MS} (the film couldn't start): back to the player.
     */
    private static void handOff(PlayerRef player, long id) {
        if (!SHOT.remove(player.getUuid(), id)) return;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (player.isValid() && !busy(player)) reset(player);
        }, HANDOFF_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * The last shot of an intro (2026-10-16, playtest: instead of coming back to the player, a wide shot of the mobs,
     * above all when they start far apart and run at each other): from the player's side of the battle, high and far
     * enough to hold every fighter (their spread, live), drifting slowly sideways, never under the ground or a roof.
     * The player is never in it: the camera stands between them and the battle.
     */
    private static Shot wideTail(List<Ref<EntityStore>> fighters, PlayerRef viewer) {
        Track track = new Track();
        double[][] holder = {null};   // the viewing direction, fixed at the first frame
        double[] roof = {Double.NaN};
        Settle settle = new Settle();
        return new Shot(TAIL_MS, (t, acc) -> {
            double x = 0, y = 0, z = 0;
            List<Vector3d> pts = new ArrayList<>();
            for (Ref<EntityStore> r : fighters) {
                Vector3d p = track.pos(r, acc);
                if (p == null) continue;
                pts.add(p);
                x += p.x; y += p.y; z += p.z;
            }
            if (pts.isEmpty()) return null;
            // one height band (a cave and the surface: the center fell inside the rock between them)
            pts = FilmCamera.mainBand(pts, null);
            x = 0; y = 0; z = 0;
            for (Vector3d p : pts) {
                x += p.x; y += p.y; z += p.z;
            }
            Vector3d c = new Vector3d(x / pts.size(), y / pts.size(), z / pts.size());
            double spread = 0;
            for (Vector3d p : pts) spread = Math.max(spread, Math.hypot(p.x - c.x, p.z - c.z));
            spread = Math.min(spread, 40);
            if (holder[0] == null) {
                // Seen from where the player stands (the camera comes from their side), else from any side.
                var tr = viewer.getTransform();
                double dx = 1, dz = 0;
                if (tr != null) {
                    dx = c.x - tr.getPosition().x;
                    dz = c.z - tr.getPosition().z;
                }
                double len = Math.hypot(dx, dz);
                double[] dir = len < 0.5 ? new double[] {1, 0} : new double[] {dx / len, dz / len};
                // a side that sees the fighters (as the crowd shot: from the viewer's side it filmed the colosseum's
                // corridors behind the wall, review 2026-10-29)
                double d0 = Math.max(9, spread * 1.7 + 6);
                holder[0] = crowdSpot(c, dir, d0, Math.max(4, spread * 0.55 + 3), d0 * 0.35, 2, acc);
            }
            double f = (double) t / TAIL_MS;
            double turn = (f - 0.5) * 0.35;   // a slow sideways drift over the shot
            double ux = holder[0][0] * Math.cos(turn) - holder[0][1] * Math.sin(turn);
            double uz = holder[0][0] * Math.sin(turn) + holder[0][1] * Math.cos(turn);
            double dist = Math.max(9, spread * 1.7 + 6) * holder[0][2];
            double up = Math.max(4, spread * 0.55 + 3);
            double ax = c.x - ux * dist, az = c.z - uz * dist;
            double ground = canopy(ax, az, c.y + up, c.y + 1.5, roof, true, acc);
            double ay = underRoof(Math.max(c.y + up, ground + 1.5), roof);
            Vector3d look = new Vector3d(c.x, c.y + 1.2, c.z);
            return frame(settle.next(new Vector3d(ax, ay, az), look, c.y, acc), look, DUEL_LERP);
        });
    }

    /**
     * One frame of a duel shot, {@code f} (0..1) into it. {@code sa}/{@code sb}: each fighter's {height, half width}
     * (Track.size), {@code ea}/{@code eb}: eye heights (Track.eye); a normal mob (2 high) gets the framing below, a
     * bigger one is filmed from further away and higher, so a giant boss shows more than its legs (playtest 2026-10-13).
     * <ul>
     *   <li>WIDE: low, well off to one side, panning slowly along the line between them, a bit wide (FOV 80 to 72).</li>
     *   <li>OVER_A / OVER_B: just behind and over the shoulder of one, looking at the other, pushing in a little.</li>
     *   <li>EYES_A / EYES_B: in front of one at eye height, from the other's side, zooming in on the eyes (FOV 52 to 36).</li>
     *   <li>SIDE: both from the side, facing each other (the old last shot).</li>
     * </ul>
     */
    private static ServerCameraSettings duelFrame(DuelShot shot, double f, Vector3d pa, Vector3d pb, double ux, double uz,
                                                  double[] sa, double[] sb, double ea, double eb,
                                                  ComponentAccessor<EntityStore> acc) {
        double dx = pb.x - pa.x, dz = pb.z - pa.z;
        double len = Math.sqrt(dx * dx + dz * dz);   // (ux, uz): from A toward B, kept by duelAxis
        double px = -uz, pz = ux;               // sideways
        double e = f * f * (3 - 2 * f);         // eased progress
        Vector3d mid = new Vector3d((pa.x + pb.x) / 2, (pa.y + pb.y) / 2, (pa.z + pb.z) / 2);
        double tall = Math.max(sa[0], sb[0]);
        double wide = Math.max(sa[1], sb[1]);
        // Over one's shoulder at the other needs room between them: closer than their two half widths and a block,
        // the camera behind one sat inside the other (or looked at its own back), so it is a side shot instead,
        // from opposite sides for A and B.
        if ((shot == DuelShot.OVER_A || shot == DuelShot.OVER_B) && len < sa[1] + sb[1] + 1) {
            double sign = shot == DuelShot.OVER_A ? 1 : -1;
            double side = Math.max(6, tall * 1.6 + wide);
            return frame(new Vector3d(mid.x - sign * px * side, mid.y + Math.max(2.5, tall * 0.6), mid.z - sign * pz * side),
                    new Vector3d(mid.x, mid.y + Math.max(1, tall * 0.4), mid.z), DUEL_LERP);
        }
        return switch (shot) {
            case WIDE -> {
                // Other side than SIDE, low, the pan runs along A -> B.
                double away = Math.max(10, len * 1.3 + tall * 2.2 + wide);
                double along = len * 0.35 * (2 * e - 1);
                double x = mid.x - px * away + ux * along, z = mid.z - pz * away + uz * along;
                double ground = FightFx.floorBelow(mid.y + 2, acc, false).applyAsDouble(x, z);
                double y = Math.max(mid.y + Math.max(0.8, tall * 0.25), Double.isFinite(ground) ? ground + 0.9 : mid.y + 0.8);
                yield zoom(frame(new Vector3d(x, y, z), new Vector3d(mid.x, mid.y + Math.max(1, tall * 0.45), mid.z), DUEL_LERP),
                        80 - 8 * e);
            }
            case OVER_A -> overShoulder(pa, sa, ea, pb, sb, ux, uz, px, pz, e);
            case OVER_B -> overShoulder(pb, sb, eb, pa, sa, -ux, -uz, -px, -pz, e);
            case EYES_A -> eyes(pa, sa, ea, ux, uz, px, pz, e, 0);
            case EYES_B -> eyes(pb, sb, eb, -ux, -uz, -px, -pz, e, 0);
            case SIDE -> {
                double side = Math.max(Math.max(6, len + 4), tall * 1.6 + wide);
                yield frame(new Vector3d(mid.x + px * side, mid.y + Math.max(2.5, tall * 0.6), mid.z + pz * side),
                        new Vector3d(mid.x, mid.y + Math.max(1, tall * 0.4), mid.z), DUEL_LERP);
            }
        };
    }

    /**
     * Over the shoulder of the fighter at {@code p} (size {@code s}, eye {@code eye}) looking along (ux, uz) at the
     * one at {@code q}: behind it by its half width + 1.1, beside it by its half width + 0.6, just over its eyes,
     * closing in by a tenth over the shot (FOV 62). The near shoulder falls in the blur's near range (blurred()).
     */
    private static ServerCameraSettings overShoulder(Vector3d p, double[] s, double eye, Vector3d q, double[] sq,
                                                     double ux, double uz, double px, double pz, double e) {
        double back = (s[1] + 1.1) * (1.1 - 0.1 * e);
        double side = s[1] + 0.6;
        Vector3d at = new Vector3d(p.x - ux * back + px * side, p.y + eye + Math.max(0.15, s[0] * 0.05), p.z - uz * back + pz * side);
        return zoom(frame(at, new Vector3d(q.x, q.y + lookHeight(sq) * 0.95, q.z), DUEL_LERP), 62);
    }

    /**
     * Close on the eyes of the fighter at {@code p}, seen from the side it faces (ux, uz: toward its opponent): eye
     * height, a little off its nose line, dollying in a bit while the lens zooms from 52 to 36 degrees.
     */
    private static ServerCameraSettings eyes(Vector3d p, double[] s, double eye, double ux, double uz,
                                             double px, double pz, double e, double lift) {
        double dist = eyesDist(s, e);
        double side = Math.max(0.5, s[1] * 0.3);
        Vector3d at = new Vector3d(p.x + ux * dist + px * side, p.y + eye + lift, p.z + uz * dist + pz * side);
        return zoom(frame(at, new Vector3d(p.x, p.y + eye * 0.97, p.z), DUEL_LERP), 52 - 16 * e);
    }

    /** How far the eyes shot stands from a fighter of size {@code s}, {@code e} (0..1) into its dolly. */
    private static double eyesDist(double[] s, double e) {
        return Math.max(2.2, s[1] + s[0] * 0.6) * (1.15 - 0.2 * e);
    }

    private static double heroDist(double[] s, double e) {
        return Math.max(3, s[1] + s[0] * 1.1) * (1.1 - 0.15 * e);
    }

    /** {@code s} with a lens of {@code fov} degrees (ServerCameraSettings.baseFov; the game's normal view is about 70). */
    static ServerCameraSettings zoom(ServerCameraSettings s, double fov) {
        s.baseFov = (float) fov;
        return s;
    }

    /**
     * Close-up distance: {@code min} blocks, {@code k} x the eye height, or its half width + 1.1 x its hitbox height,
     * whichever is most (2026-10-13, playtest: showcase, focus and end shots only grew with the eye height, so a wide boss
     * or a size-buffed mob was filmed from inside). Capped at MAX_FRAMED.
     */
    private static double closeDist(double min, double k, double eye, double[] size) {
        return Math.min(MAX_FRAMED, Math.max(Math.max(min, eye * k), size[1] + size[0] * 1.1));
    }

    /** Where a close duel shot looks: 1.5 up on a normal mob, 0.6 of the height on a bigger one. */
    private static double lookHeight(double[] size) {
        return Math.max(1.5, size[0] * 0.6);
    }

    /** Hitbox of a normal mob for the camera ({height, half width}), and the most a framing counts (blocks). */
    private static final double[] NORMAL_SIZE = {2, 0.5};
    private static final double MAX_FRAMED = 60;

    private static final long STEP_MS = 50;
    /** Time the shots leave free at the end of an intro: none since the glide back to the player is gone (2026-10-16). */
    private static final long BACK_MS = 0;
    /**
     * Spotlight: shortest close-up, the crowd shot's share of the time and its least length (2026-10-16, playtest: a 5 s
     * countdown with 17 mobs showed one face; 2 s / 0.3 / 2.5 s left room for one, now three).
     */
    private static final long SPOT_MIN_MS = 1100;
    /** Close-ups: how fast the camera swings round to a turned face, per 50 ms frame. */
    private static final double FACE_EASE = 0.08;
    private static final double SPOT_CROWD_SHARE = 0.25;
    private static final long SPOT_CROWD_MIN_MS = 1500;
    /** Showcase: shortest close-up; fewer fighters are shown when the time is short. */
    private static final long MIN_SHOT_MS = 1200;
    /** Team sweep: share of each team's shot spent on its first member before sweeping. */
    private static final double HOLD = 0.3;
    private static final int MAX_TEAM_SHOTS = 6;
    /** Focus: how far the camera circles the target (radians, about 200 degrees). */
    private static final double FOCUS_SWEEP = 3.5;
    /** Focus circle: shares of its radius tried, widest first, until the target is in clear view. */
    private static final double[] RING_SHARES = {1, 0.75, 0.5, 0.35};

    /** Focus circle: the attacker it looks at is kept at least this long. */
    private static final long FOCUS_HOLD_MS = 1500;
    /**
     * Drone: flying height over the ground or tree tops, side offset past a fighter (more for wide ones), least
     * clearance right under it, share of the gap to the wanted height closed per frame going up / down.
     */
    private static final double DRONE_LOW = 4;
    private static final double DRONE_SIDE = 4;
    /** Indoors, a stop's side point is looked for again at least this often (frames of STEP_MS). */
    private static final int SIDE_REFRESH_FRAMES = 10;
    private static final double DRONE_CLEAR = 2;
    private static final double DRONE_CLIMB = 0.3;
    private static final double DRONE_SINK = 0.06;
    /** Drone: ground checked this many blocks ahead along the path, so it climbs before a hill or a tree. */
    private static final double[] DRONE_AHEAD = {3, 6, 10};
    /** Drone: fighters passed (one per DRONE_PASS_MS of flight, at most DRONE_MAX_PASSES). */
    private static final int DRONE_MAX_PASSES = 6;
    private static final long DRONE_PASS_MS = 1500;
    /** Drone look: a point this far ahead and this far below the camera; a fighter closer ahead than the range pulls it up to DRONE_TURN. */
    private static final double DRONE_LOOK = 10;
    private static final double DRONE_LOOK_DOWN = 3;
    private static final double DRONE_TURN_RANGE = 14;
    private static final double DRONE_TURN = 0.6;
    /** Drone: share of the gap to the new look point closed per frame (smooths the turns), and the client glide. */
    private static final double DRONE_LOOK_EASE = 0.25;
    private static final float DRONE_LERP = 0.35f;
    /** Ground search starts this far above the camera (hills taller than that are missed). */
    private static final double GROUND_SCAN = 30;
    /** Drone: share of the shot spent on the pass; the rest swings around the battle (DRONE_ORBIT_SWEEP radians). */
    private static final double DRONE_FLY_SHARE = 0.65;
    private static final double DRONE_ORBIT_SWEEP = 1.6;
    /** Drone swing: closes in to this share of the radius where the pass ended, never under DRONE_ORBIT_MIN blocks. */
    private static final double DRONE_ORBIT_SHRINK = 0.8;
    private static final double DRONE_ORBIT_MIN = 8;
    /** Indoor drone: a column with less than this between floor and ceiling is a corridor, not the arena. */
    private static final double DRONE_HEADROOM = 8;
    /** Indoor arena: the camera stays this far under the bottom of the roof. */
    static final double ROOF_CLEAR = 1.2;
    /** Glide between frames: follows a walking mob, and moves between shots in about half a second. */
    private static final float DUEL_LERP = 0.15f;
    /** Duel: closer than this (blocks) the line between the duellists is not trusted as the shots' axis (duelAxis). */
    private static final double DUEL_AXIS_MIN = 1.5;
    /** Duel: shortest shot; a short intro plays fewer of them (DUEL_CUTS). */
    private static final long MIN_DUEL_SHOT_MS = 700;
    /** Depth of field: farthest focus (blocks) and the near blur's share of the far one. */
    private static final double MAX_FOCUS = 64;
    private static final float NEAR_BLUR = 0.6f;

    /** Per player: the newest duel shot, so an older shot's frames and reset don't touch a newer one. */
    private static final Map<UUID, Long> SHOT = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * One server camera frame: at {@code at}, looking at {@code target}. Look-at in the game's convention
     * (PhysicsMath.headingFromDirection / pitchFromDirection): yaw = atan2(-dx, -dz), pitch = atan2(dy, horizontal).
     * The player's movement stays with the character controller (no skipped physics, so someone flying keeps
     * flying); input does nothing: the camera is not attached to them (CanMoveType.AttachedToLocalPlayer) and
     * the multipliers are 0.
     */
    static ServerCameraSettings frame(Vector3d at, Vector3d target, float lerp) {
        double dx = target.x - at.x, dy = target.y - at.y, dz = target.z - at.z;
        float yaw = (float) Math.atan2(-dx, -dz);
        float pitch = (float) Math.atan2(dy, Math.sqrt(dx * dx + dz * dz));
        ServerCameraSettings s = new ServerCameraSettings();
        s.positionLerpSpeed = lerp;
        s.rotationLerpSpeed = lerp;
        s.distance = 0f;
        s.allowPitchControls = false;
        s.displayCursor = false;
        s.displayReticle = false;
        s.hideHeldItem = true;
        s.mouseInputTargetType = MouseInputTargetType.None;
        s.sendMouseMotion = false;
        s.isFirstPerson = false;
        s.attachedToType = AttachedToType.None;
        s.eyeOffset = false;
        s.positionDistanceOffsetType = PositionDistanceOffsetType.None;
        s.positionType = PositionType.Custom;
        s.position = new Position(at.x, at.y, at.z);
        s.rotationType = RotationType.Custom;
        s.rotation = new Direction(yaw, pitch, 0f);
        s.canMoveType = CanMoveType.AttachedToLocalPlayer;
        s.movementMultiplier = new org.joml.Vector3f(0f, 0f, 0f);
        s.lookMultiplier = new org.joml.Vector2f(0f, 0f);
        s.depthOfField = focusAt(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return s;
    }

    /**
     * Depth of field at full strength, sharp around {@code focus} blocks away (what the frame looks at): sharp from
     * 0.55 to past 1.3 x the focus, fully blurred under 0.25 x (a shoulder in front) and from about 2.8 x on (the
     * background). The near blur is lighter (NEAR_BLUR). {@link #blurred} scales it by cameraBlur or drops it.
     * ❓ Distances and blur amounts guessed from the field names (DepthOfFieldSettings, 0.7.0-pre.5.1+), untested.
     */
    private static DepthOfFieldSettings focusAt(double focus) {
        float d = (float) Math.max(0.5, Math.min(MAX_FOCUS, focus));
        float farSharp = d + Math.max(1.5f, d * 0.3f);
        float farBlurry = farSharp + Math.max(4f, d * 1.5f);
        return new DepthOfFieldSettings(d * 0.25f, d * 0.55f, farSharp, farBlurry, NEAR_BLUR, 1f);
    }

    /** cameraBlur strength: off 0, soft 0.5, strong 1. */
    static float blurOf(String blur) {
        return switch (blur == null ? "off" : blur) {
            case "soft" -> 0.5f;
            case "strong" -> 1f;
            default -> 0f;
        };
    }

    /** {@code s} with its depth of field scaled to {@code strength} (0 = none, the client's own camera has none). */
    static ServerCameraSettings blurred(ServerCameraSettings s, float strength) {
        DepthOfFieldSettings dof = s.depthOfField;
        if (strength <= 0 || dof == null) {
            s.depthOfField = null;
        } else {
            dof.nearBlurMax *= strength;
            dof.farBlurMax *= strength;
        }
        return s;
    }

    static void schedule(UUID uuid, long id, long delayMs, Runnable step) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!Long.valueOf(id).equals(SHOT.get(uuid))) return;
            try {
                step.run();
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Duel camera frame failed: " + e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** The viewer is still online and their entity is in this world's store (a shot ends when they are not). */
    static boolean viewerIn(PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world) {
        if (!player.isValid()) return false;
        Ref<EntityStore> ref = player.getReference();
        return ref != null && ref.isValid() && ref.getStore() == world.getEntityStore().getStore();
    }

    static void send(PlayerRef player, ServerCameraSettings settings) {
        if (player.isValid()) {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
        }
    }

    /** The player's normal camera (CameraManager.resetCamera, on their world thread; else "/camera reset"'s packet). */
    static void reset(PlayerRef player) {
        if (player == null || !player.isValid()) return;
        CinemaBars.out(player);   // every end of a camera (glide back, cut, skip, stop, battle over) passes here
        FilmMode.leave(player);
        CameraHud.restore(player);
        Ref<EntityStore> ref = player.getReference();
        if (ref != null && ref.isValid()) {
            ref.getStore().getExternalData().getWorld().execute(() -> {
                CameraManager camera = ref.isValid() ? ref.getStore().getComponent(ref, CameraManager.getComponentType()) : null;
                if (camera != null) camera.resetCamera(player);
                else player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
            });
        } else {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, false, null));
        }
    }

    // ---- for FilmCamera: a film owns the player's camera like a shot (SHOT), so skip, cancel and stop end it too ----

    /** Takes this player's camera for a new sequence (a running shot or orbit stops sending) and returns its id. */
    static long claim(PlayerRef player) {
        UUID uuid = player.getUuid();
        long id = SEQ.incrementAndGet();
        SHOT.put(uuid, id);
        ORBITS.remove(uuid);
        INTROS.remove(uuid);
        PENDING.remove(uuid);   // a film started during a VS: the intro after it would take the camera back
        return id;
    }

    /** The sequence {@code id} still has this player's camera. */
    static boolean owns(UUID uuid, long id) {
        return Long.valueOf(id).equals(SHOT.get(uuid));
    }

    /** Lets go of the camera without touching it (the viewer left or changed world). */
    static void release(UUID uuid, long id) {
        SHOT.remove(uuid, id);
    }

    /** An intro, end camera, orbit or film is filming for this player now. */
    static boolean busy(PlayerRef player) {
        UUID uuid = player.getUuid();
        return SHOT.containsKey(uuid) || ORBITS.getOrDefault(uuid, 0L) > System.currentTimeMillis();
    }

    /**
     * As a shot sequence ends: the player's normal camera (bars out, HUD back). It used to glide to a shot just behind
     * the player first; that shot of the player is gone (2026-10-16).
     */
    static void glideBack(PlayerRef player, long id) {
        if (SHOT.remove(player.getUuid(), id)) reset(player);
    }

    /** Ends this player's duel shot, if any, and gives them the normal camera back. */
    static void stop(PlayerRef player) {
        if (player != null && SHOT.remove(player.getUuid()) != null) reset(player);
    }

    /** Per player: when their orbit sequence ends, so /mobbattle stop can cut it short. */
    private static final Map<UUID, Long> ORBITS = new ConcurrentHashMap<>();

    /** BattleManager.maintain: forgets orbits and intros that already ended. */
    static void prune() {
        SKIP_HINTED.removeIf(id -> com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id) == null);
        CinemaBars.prune();
        CameraHud.prune();
        FilmCamera.prune();
        FilmMode.prune();
        if (ORBITS.isEmpty() && INTROS.isEmpty()) return;
        long now = System.currentTimeMillis();
        ORBITS.values().removeIf(end -> end <= now);
        INTROS.entrySet().removeIf(e -> e.getValue().id() == ORBIT_ID
                ? !ORBITS.containsKey(e.getKey()) : !Long.valueOf(e.getValue().id()).equals(SHOT.get(e.getKey())));
    }

    /**
     * /mobbattle stop: every running shot ends and its player gets the normal camera back. A duel shot stops
     * sending frames; an orbit (a sequence the client plays by itself) is replaced by a short glide back to the
     * player's eyes (a new sequence replaces the running one, CameraSequencePacketHandler.begin).
     */
    static int stopAll() {
        long now = System.currentTimeMillis();
        java.util.Set<UUID> players = new java.util.HashSet<>(SHOT.keySet());
        SHOT.clear();
        INTROS.clear();
        PENDING.clear();
        VsScreen.stopAll();
        ORBITS.forEach((id, end) -> { if (end > now) players.add(id); });
        java.util.Set<UUID> orbiting = new java.util.HashSet<>(ORBITS.keySet());
        ORBITS.clear();
        for (UUID id : players) {
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id);
            if (p != null && p.isValid()) cut(p, orbiting.contains(id));
        }
        return players.size();
    }

    /** Intro shots that a player's battle ending cuts short: the sequence id (or ORBIT_ID) and who it films. */
    private record Intro(long id, java.util.Set<Ref<EntityStore>> fighters) {
    }

    private static final long ORBIT_ID = -1;
    private static final Map<UUID, Intro> INTROS = new ConcurrentHashMap<>();

    /**
     * BattleManager.finish: an intro still filming any of these fighters ends at once (the battle is over, e.g. a
     * quick duel). The end camera (endCamera winner / lastHit) is not an intro, so it keeps playing.
     */
    static void battleOver(java.util.Collection<Ref<EntityStore>> fighters) {
        // Still in its VS screen: the screen goes and the intro after it never starts.
        for (Map.Entry<UUID, Intro> e : PENDING.entrySet()) {
            if (java.util.Collections.disjoint(e.getValue().fighters(), fighters) || !PENDING.remove(e.getKey(), e.getValue())) continue;
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(e.getKey());
            if (p != null) VsScreen.cancel(p);
        }
        if (INTROS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Intro> e : INTROS.entrySet()) {
            Intro intro = e.getValue();
            if (java.util.Collections.disjoint(intro.fighters(), fighters)) continue;
            UUID id = e.getKey();
            INTROS.remove(id, intro);
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id);
            if (p == null || !p.isValid()) continue;
            if (intro.id() == ORBIT_ID) {
                Long end = ORBITS.get(id);
                if (end != null && end > now && ORBITS.remove(id, end)) cut(p, true);
            } else if (SHOT.remove(id, intro.id())) {
                cut(p, false);
            }
        }
    }

    /** Crouching or /mobbattle skipCamera: this player's intro or end camera ends now. Returns false if none. */
    static boolean cancel(PlayerRef player) {
        if (player == null) return false;
        UUID id = player.getUuid();
        INTROS.remove(id);
        // a VS screen and the intro waiting behind it go too
        boolean vs = PENDING.remove(id) != null;
        vs |= VsScreen.cancel(player);
        Long end = ORBITS.remove(id);
        boolean orbit = end != null && end > System.currentTimeMillis();
        boolean shot = SHOT.remove(id) != null;
        if (orbit || shot) cut(player, orbit);
        return orbit || shot || vs;
    }

    /**
     * The watch takes the camera over (BattleWatch.watch): a running shot stops with its bands, HUD and film mode put
     * back, but WITHOUT the camera reset, which reached the client after the watch's follow camera and left it flying
     * free until the next re-attach (2026-10-29, playtest video). An orbit (client keyframes) still glides back as cancel.
     */
    static void handOver(PlayerRef player) {
        if (player == null) return;
        UUID id = player.getUuid();
        INTROS.remove(id);
        PENDING.remove(id);
        VsScreen.cancel(player);
        Long end = ORBITS.remove(id);
        if (end != null && end > System.currentTimeMillis()) {
            cut(player, true);
            return;
        }
        if (SHOT.remove(id) != null) {
            CinemaBars.out(player);
            FilmMode.leave(player);
            CameraHud.restore(player);
        }
    }

    /** Back to the player's view: a short glide for an orbit (a new sequence replaces it), then the normal camera. */
    private static void cut(PlayerRef p, boolean orbiting) {
        Ref<EntityStore> ref = p.getReference();
        if (ref == null || !ref.isValid()) return;
        var store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (orbiting) {
                Eye eye = eyeOf(p, store);
                if (eye != null) {
                    CameraSequenceBuilder cut = new CameraSequenceBuilder();
                    cut.keyframe(eye.pos, eye.look, 0.3f, EasingType.SineOut);
                    cut.addFlag(CameraSequenceFlags.ReturnToGameplayCameraOnEnd);
                    cut.sendTo(p);
                }
            }
            reset(p);
        });
    }

    private static final long SKIP_POLL_MS = 100;
    /** Players already told once this session that crouching skips a camera. */
    private static final java.util.Set<UUID> SKIP_HINTED = ConcurrentHashMap.newKeySet();

    /**
     * Crouching skips the running camera. Polled on the world thread while the shot or orbit lasts; a crouch held
     * when it started has to be let go first, so the crouch + Battle Wand that started a fight doesn't skip it.
     */
    static void watchSkip(PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world) {
        UUID id = player.getUuid();
        Long shot = SHOT.get(id);
        boolean orbit = shot == null && ORBITS.containsKey(id);
        if (shot == null && !orbit) return;
        if (SKIP_HINTED.add(id)) {
            player.sendMessage(I18n.tr("battle.camera.skip").color("#aaaaaa"));
        }
        boolean[] armed = {false};
        Runnable[] poll = new Runnable[1];
        poll[0] = () -> world.execute(() -> {
            boolean running = orbit ? ORBITS.getOrDefault(id, 0L) > System.currentTimeMillis() : shot.equals(SHOT.get(id));
            Ref<EntityStore> ref = player.getReference();
            if (!running || !player.isValid() || ref == null || !ref.isValid()) return;
            boolean crouch = PlayerFighters.crouching(ref, ref.getStore());
            if (!crouch) {
                armed[0] = true;
            } else if (armed[0]) {
                MobBattlePlugin.debug("camera skipped by " + player.getUsername());
                cancel(player);
                return;
            }
            HytaleServer.SCHEDULED_EXECUTOR.schedule(poll[0], SKIP_POLL_MS, TimeUnit.MILLISECONDS);
        });
        HytaleServer.SCHEDULED_EXECUTOR.schedule(poll[0], SKIP_POLL_MS, TimeUnit.MILLISECONDS);
    }

    /** Ends on the player's own eye view (a glide back) and plays it. */
    private static void play(PlayerRef player, CameraSequenceBuilder seq, Eye eye) {
        seq.keyframe(eye.pos, eye.look, BACK_S, EasingType.SineInOut);
        seq.addFlag(FLAGS);
        seq.sendTo(player);
    }

    private record Eye(Vector3d pos, Rotation3f look) {
    }

    /** The player's eye position and head rotation (as CinematicTeleport reads them). */
    private static Eye eyeOf(PlayerRef player, ComponentAccessor<EntityStore> buffer) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return null;
        TransformComponent transform = buffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) return null;
        HeadRotation head = buffer.getComponent(ref, HeadRotation.getComponentType());
        Rotation3f look = new Rotation3f(head != null ? head.getRotation() : transform.getRotation());
        ModelComponent model = buffer.getComponent(ref, ModelComponent.getComponentType());
        Model m = model != null ? model.getModel() : null;
        float eyeHeight = m != null ? m.getEyeHeight(ref, buffer) : 1.6f;
        Vector3d p = transform.getPosition();
        return new Eye(new Vector3d(p.x, p.y + eyeHeight, p.z), look);
    }
}
