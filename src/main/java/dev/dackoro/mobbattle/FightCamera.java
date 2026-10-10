package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.CameraSequenceFlags;
import com.hypixel.hytale.protocol.AttachedToType;
import com.hypixel.hytale.protocol.CanMoveType;
import com.hypixel.hytale.protocol.ClientCameraView;
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
        for (PlayerRef viewer : FightFx.cameraViewers(owner)) introFor(kind, viewer, center, sides, buffer);
    }

    static void intro(BattleManager.Kind kind, BattleManager.Battle.Anchor anchor, Vector3d center,
                      List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        for (PlayerRef viewer : FightFx.eventViewers(anchor, FxSettings.cameraScope)) {
            introFor(kind, viewer, center, sides, buffer);
        }
    }

    private static void introFor(BattleManager.Kind kind, PlayerRef player, Vector3d center,
                                 List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> buffer) {
        // Never let a camera problem break the fight that called it.
        try {
            int seconds = KindFx.introSeconds(kind);
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
            String style = KindFx.cameraStyle(kind);
            if (("duel".equals(style) || "focus".equals(style)) && live.size() < 2) style = "showcase";
            if ("teams".equals(style) && live.size() < 2) style = "showcase";
            MobBattlePlugin.debug("intro camera " + style + " for " + (kind != null ? kind.key : "?") + ", " + seconds + " s, "
                    + all.size() + " fighters in " + live.size() + " sides");
            switch (style) {
                case "orbit" -> orbit(player, center, seconds, buffer);
                case "duel" -> duel(player, live.get(0).get(0), live.get(1).get(0), total, buffer);
                case "teams" -> teams(player, center, live, total, buffer);
                case "focus" -> {
                    // The first side's first fighter (everyone vs one: the target) against all the others.
                    Ref<EntityStore> target = live.get(0).get(0);
                    List<Ref<EntityStore>> crowd = new ArrayList<>(all);
                    crowd.remove(target);
                    focus(player, target, crowd, total, buffer);
                }
                case "drone" -> drone(player, center, all, total, buffer);
                default -> showcase(player, all, KindFx.cameraShots(kind), total, buffer);
            }
            // Remember what this intro films, so the battle's end cuts it (battleOver); the end camera is never cut.
            Long shot = SHOT.get(player.getUuid());
            INTROS.put(player.getUuid(), new Intro(shot != null ? shot : ORBIT_ID, java.util.Set.copyOf(all)));
            watchSkip(player, buffer.getExternalData().getWorld());
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
                return closeUp(p, track.yaw(focus, acc), eye, swing, Math.max(4, eye * 2.6), eye * 0.6 + 1.2);
            }));
            playShots(player, buffer.getExternalData().getWorld(), shots);
            watchSkip(player, buffer.getExternalData().getWorld());
        } catch (RuntimeException e) {
            MobBattlePlugin.log("End camera failed: " + e);
            reset(player);
        }
    }

    /** Wide circling shot of the area around {@code center} (keyframes the client plays by itself). */
    private static void orbit(PlayerRef player, Vector3d center, int seconds, ComponentAccessor<EntityStore> buffer) {
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
        ORBITS.put(player.getUuid(), System.currentTimeMillis() + seconds * 1000L);
    }

    /**
     * Duel standoff, like a western: a close shot of each fighter seen from its opponent's side, then a wide side
     * shot of both facing each other, then back to the player. Follows the fighters as they move.
     */
    private static void duel(PlayerRef player, Ref<EntityStore> a, Ref<EntityStore> b, long total,
                             ComponentAccessor<EntityStore> buffer) {
        long each = Math.max(STEP_MS, (total - BACK_MS) / 3);
        Track track = new Track();
        List<Shot> shots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int shot = i;
            shots.add(new Shot(each, (t, acc) -> {
                Vector3d pa = track.pos(a, acc), pb = track.pos(b, acc);
                return pa == null || pb == null ? null : duelFrame(pa, pb, shot);
            }));
        }
        playShots(player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Royale showcase: close-ups of {@code count} fighters picked at random (fewer if the time is short), each
     * seen from the front with a slow sideways drift.
     */
    private static void showcase(PlayerRef player, List<Ref<EntityStore>> fighters, int count, long total,
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
                return closeUp(p, track.yaw(mob, acc), eye, swing, Math.max(3, eye * 2.2), eye * 0.9 + 0.4);
            }));
        }
        playShots(player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Team by team: one shot per side, from between the side and the battle's center (where they face). It holds
     * on one member, then sweeps across the others, left to right.
     */
    private static void teams(PlayerRef player, Vector3d center, List<List<Ref<EntityStore>>> sides, long total,
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
                double spread = 1.5;
                for (Ref<EntityStore> m : members) {
                    Vector3d p = track.pos(m, acc);
                    if (p != null) spread = Math.max(spread, Math.hypot(p.x - c.x, p.z - c.z));
                }
                double dx = center.x - c.x, dz = center.z - c.z;
                double len = Math.hypot(dx, dz);
                if (len < 1) { dx = 1; dz = 0; len = 1; }
                double dist = Math.min(spread + 5, 40);
                Vector3d at = new Vector3d(c.x + dx / len * dist, c.y + 2.5 + spread * 0.25, c.z + dz / len * dist);
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
                return frame(at, new Vector3d(look.x, look.y + 1.2, look.z), DUEL_LERP);
            }));
        }
        playShots(player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Everyone vs one: a close-up of the target, then a circle around it at the crowd's distance, the camera
     * low and outside the crowd, always looking at the attacker in front of it (one after another as it turns),
     * with the target behind.
     */
    private static void focus(PlayerRef player, Ref<EntityStore> target, List<Ref<EntityStore>> crowd, long total,
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
            return closeUp(q, track.yaw(target, acc), eye, -0.3 + 0.6 * t / first, Math.max(3, eye * 2.2), eye * 0.9 + 0.4);
        }));
        // The attacker in front of the camera, kept at least FOCUS_HOLD_MS: picking the closest one every frame made
        // the camera jump from one to another many times a second in a big crowd.
        @SuppressWarnings("unchecked")
        Ref<EntityStore>[] held = new Ref[1];
        long[] heldSince = {-FOCUS_HOLD_MS};
        shots.add(new Shot(circle, (t, acc) -> {
            Vector3d c = track.pos(target, acc);
            if (c == null) return null;
            double a = a0 + FOCUS_SWEEP * t / circle;
            double x = c.x + Math.cos(a) * ring, z = c.z + Math.sin(a) * ring;
            double y = Math.max(c.y + 2.5 + ring * 0.15, ground(x, z, c.y, acc) + 2.5);
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
            return frame(new Vector3d(x, y, z), new Vector3d(look.x, look.y + 1.2, look.z), DUEL_LERP);
        }));
        playShots(player, buffer.getExternalData().getWorld(), shots);
    }

    /**
     * Drone: one flight across the battle, from the player's side to the far side, high above the ground. It dives
     * past a few fighters picked at random (one every ~3 s, up to 4), looking at each as it goes by, then climbs
     * again and heads for the next. Never lower than {@link #DRONE_CLEAR} blocks over the ground under it and just
     * ahead of it (hills); tree leaves may still get in the way.
     */
    private static void drone(PlayerRef player, Vector3d center, List<Ref<EntityStore>> fighters, long total,
                              ComponentAccessor<EntityStore> buffer) {
        long usable = Math.max(STEP_MS * 2, total - BACK_MS);
        double spread = 0;
        for (Ref<EntityStore> r : fighters) {
            Vector3d q = ChaosWand.positionOf(r, buffer);
            if (q != null) spread = Math.max(spread, Math.hypot(q.x - center.x, q.z - center.z));
        }
        double r = Math.max(12, Math.min(80, spread + 8));
        double cruise = center.y + r * 0.35 + 8;
        Eye eye = eyeOf(player, buffer);
        double aS = eye != null ? Math.atan2(eye.pos.z - center.z, eye.pos.x - center.x) : 0;
        double ux = -Math.cos(aS), uz = -Math.sin(aS);    // flight direction (toward the far side)
        double px = -uz, pz = ux;                          // sideways
        Vector3d from = new Vector3d(center.x - ux * r, cruise, center.z - uz * r);
        Vector3d to = new Vector3d(center.x + ux * r, cruise, center.z + uz * r);
        // The fighters it dives past, in flight order.
        int dives = (int) Math.max(1, Math.min(4, Math.min(fighters.size(), usable / 3000)));
        List<Ref<EntityStore>> picked = new ArrayList<>(fighters);
        java.util.Collections.shuffle(picked);
        picked = new ArrayList<>(picked.subList(0, dives));
        Map<Ref<EntityStore>, Double> along = new java.util.HashMap<>();
        for (Ref<EntityStore> f : picked) {
            Vector3d q = ChaosWand.positionOf(f, buffer);
            along.put(f, q == null ? 0 : (q.x - center.x) * ux + (q.z - center.z) * uz);
        }
        picked.sort(java.util.Comparator.comparingDouble(along::get));
        List<Ref<EntityStore>> stops = picked;
        Track track = new Track();
        List<Shot> shots = List.of(new Shot(usable, (t, acc) -> {
            // Waypoints: start edge, each dive (beside the fighter, low), far edge.
            int n = stops.size() + 2;
            Vector3d[] pts = new Vector3d[n];
            Vector3d[] watch = new Vector3d[n];
            pts[0] = from;
            pts[n - 1] = to;
            for (int i = 0; i < stops.size(); i++) {
                Vector3d q = track.pos(stops.get(i), acc);
                if (q == null) q = center;
                pts[i + 1] = new Vector3d(q.x + px * DRONE_SIDE, q.y + DRONE_LOW, q.z + pz * DRONE_SIDE);
                watch[i + 1] = new Vector3d(q.x, q.y + 1, q.z);
            }
            double u = (double) t / usable * (n - 1);
            int i = (int) Math.min(n - 2, Math.floor(u));
            double f = u - i;
            double e = f * f * (3 - 2 * f);   // ease in and out of each stop
            Vector3d a = pts[i], b = pts[i + 1];
            double x = a.x + (b.x - a.x) * e, z = a.z + (b.z - a.z) * e;
            // Low at the dives, back up to cruise height between them.
            double base = a.y + (b.y - a.y) * e;
            double y = base + Math.max(0, cruise - base) * Math.sin(Math.PI * f);
            double ax = x + (b.x - a.x) * 0.15, az = z + (b.z - a.z) * 0.15;
            y = Math.max(y, Math.max(ground(x, z, y, acc), ground(ax, az, y, acc)) + DRONE_CLEAR);
            // Watch the fighter just passed for a moment, then the next stop (the battle's center at the end).
            Vector3d look = f < 0.35 && watch[i] != null ? watch[i]
                    : watch[i + 1] != null ? watch[i + 1] : new Vector3d(center.x, center.y + 1, center.z);
            return frame(new Vector3d(x, y, z), look, DUEL_LERP);
        }));
        playShots(player, buffer.getExternalData().getWorld(), shots);
    }

    /** Height of the ground at (x, z), searched from a bit above {@code fromY} down; {@code fromY} if none found. */
    private static double ground(double x, double z, double fromY, ComponentAccessor<EntityStore> acc) {
        double g = FightFx.floorBelow(fromY + GROUND_SCAN, acc).applyAsDouble(x, z);
        return Double.isFinite(g) ? g : fromY - DRONE_CLEAR;
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

        double eye(Ref<EntityStore> r, ComponentAccessor<EntityStore> acc) {
            return eye.computeIfAbsent(r, k -> {
                ModelComponent mc = k.isValid() && k.getStore() == acc
                        ? acc.getComponent(k, ModelComponent.getComponentType()) : null;
                Model m = mc != null ? mc.getModel() : null;
                double h = m != null ? m.getEyeHeight(k, acc) : 1.6;
                return Math.max(0.6, Math.min(12, h));
            });
        }
    }

    /**
     * Plays the shots one after another for this player: a server camera frame every {@link #STEP_MS}, read on
     * the world thread, then a glide back behind the player and their normal camera. A newer sequence for the
     * same player replaces this one (its frames stop).
     */
    private static void playShots(PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world,
                                  List<Shot> shots) {
        UUID uuid = player.getUuid();
        long id = SEQ.incrementAndGet();
        SHOT.put(uuid, id);
        ORBITS.remove(uuid);
        long total = 0;
        for (Shot shot : shots) total += shot.ms();
        long end = total;
        // One frame task at a time, each scheduling the next: a 30 s camera to a whole world of viewers used to
        // queue 600 tasks per viewer up front. A newer sequence (or a cut) stops the chain at its next step.
        Runnable[] step = new Runnable[1];
        long[] at = {0};
        step[0] = () -> {
            long t = at[0];
            if (t >= end) {
                ServerCameraSettings back = behindPlayer(player);
                if (back != null) send(player, back);
                schedule(uuid, id, BACK_MS, () -> {
                    if (SHOT.remove(uuid, id)) reset(player);
                });
                return;
            }
            long start = 0;
            Shot current = shots.get(shots.size() - 1);
            for (Shot shot : shots) {
                if (t < start + shot.ms()) {
                    current = shot;
                    break;
                }
                start += shot.ms();
            }
            Shot shot = current;
            long local = t - start;
            // Viewer gone or moved to another world: end the chain here, so no further frame is queued.
            if (!viewerIn(player, world)) { SHOT.remove(uuid, id); return; }
            world.execute(() -> {
                if (!Long.valueOf(id).equals(SHOT.get(uuid))) return;
                if (!viewerIn(player, world)) { SHOT.remove(uuid, id); return; }
                ServerCameraSettings s = shot.frame().at(local, world.getEntityStore().getStore());
                if (s != null) send(player, s);
            });
            at[0] = t + STEP_MS;
            schedule(uuid, id, STEP_MS, step[0]);
        };
        schedule(uuid, id, 0, step[0]);
    }

    /** Shot 0: close on A from B's side; 1: close on B from A's side; 2: both from the side. */
    private static ServerCameraSettings duelFrame(Vector3d pa, Vector3d pb, int shot) {
        double dx = pb.x - pa.x, dz = pb.z - pa.z;
        double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / len, uz = dz / len;   // from A toward B
        double px = -uz, pz = ux;               // sideways
        if (shot == 0) {
            return frame(near(pa, ux, uz, px, pz, 3), new Vector3d(pa.x, pa.y + 1.5, pa.z), DUEL_LERP);
        }
        if (shot == 1) {
            return frame(near(pb, -ux, -uz, -px, -pz, 3), new Vector3d(pb.x, pb.y + 1.5, pb.z), DUEL_LERP);
        }
        Vector3d mid = new Vector3d((pa.x + pb.x) / 2, (pa.y + pb.y) / 2, (pa.z + pb.z) / 2);
        double side = Math.max(6, len + 4);
        return frame(new Vector3d(mid.x + px * side, mid.y + 2.5, mid.z + pz * side),
                new Vector3d(mid.x, mid.y + 1, mid.z), DUEL_LERP);
    }

    /** {@code dist} blocks from {@code p} along (ux, uz), 1.2 to the side, at face height. */
    private static Vector3d near(Vector3d p, double ux, double uz, double px, double pz, double dist) {
        return new Vector3d(p.x + ux * dist + px * 1.2, p.y + 1.8, p.z + uz * dist + pz * 1.2);
    }

    private static final long STEP_MS = 50;
    private static final long BACK_MS = 900;
    /** Showcase: shortest close-up; fewer fighters are shown when the time is short. */
    private static final long MIN_SHOT_MS = 1200;
    /** Team sweep: share of each team's shot spent on its first member before sweeping. */
    private static final double HOLD = 0.3;
    private static final int MAX_TEAM_SHOTS = 6;
    /** Focus: how far the camera circles the target (radians, about 200 degrees). */
    private static final double FOCUS_SWEEP = 3.5;
    /** Focus circle: the attacker it looks at is kept at least this long. */
    private static final long FOCUS_HOLD_MS = 1500;
    /** Drone: height over a fighter it dives past, side offset (so it passes beside, not over), ground clearance. */
    private static final double DRONE_LOW = 3.5;
    private static final double DRONE_SIDE = 5;
    private static final double DRONE_CLEAR = 3;
    /** Ground search starts this far above the camera (hills taller than that are missed). */
    private static final double GROUND_SCAN = 30;
    /** Glide between frames: follows a walking mob, and moves between shots in about half a second. */
    private static final float DUEL_LERP = 0.15f;
    private static final float LERP_BACK = 0.1f;

    /** Per player: the newest duel shot, so an older shot's frames and reset don't touch a newer one. */
    private static final Map<UUID, Long> SHOT = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    /** Just behind the player, looking where they face: the glide back before the reset. */
    private static ServerCameraSettings behindPlayer(PlayerRef player) {
        var now = player.getTransform();
        if (now == null) return null;
        Vector3d p = new Vector3d(now.getPosition());
        double yaw = now.getRotation().yaw();
        double fx = -Math.sin(yaw), fz = -Math.cos(yaw);   // forward (PhysicsMath.headingX/Z)
        return frame(new Vector3d(p.x - fx * 3, p.y + 2.2, p.z - fz * 3),
                new Vector3d(p.x + fx * 4, p.y + 1.4, p.z + fz * 4), LERP_BACK);
    }

    /**
     * One server camera frame: at {@code at}, looking at {@code target}. Look-at in the game's convention
     * (PhysicsMath.headingFromDirection / pitchFromDirection): yaw = atan2(-dx, -dz), pitch = atan2(dy, horizontal).
     * The player's movement stays with the character controller (no skipped physics, so someone flying keeps
     * flying); input does nothing: the camera is not attached to them (CanMoveType.AttachedToLocalPlayer) and
     * the multipliers are 0.
     */
    private static ServerCameraSettings frame(Vector3d at, Vector3d target, float lerp) {
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
        return s;
    }

    private static void schedule(UUID uuid, long id, long delayMs, Runnable step) {
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
    private static boolean viewerIn(PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world) {
        if (!player.isValid()) return false;
        Ref<EntityStore> ref = player.getReference();
        return ref != null && ref.isValid() && ref.getStore() == world.getEntityStore().getStore();
    }

    private static void send(PlayerRef player, ServerCameraSettings settings) {
        if (player.isValid()) {
            player.getPacketHandler().writeNoCache(new SetServerCamera(ClientCameraView.Custom, true, settings));
        }
    }

    /** The player's normal camera (CameraManager.resetCamera, on their world thread; else "/camera reset"'s packet). */
    static void reset(PlayerRef player) {
        if (player == null || !player.isValid()) return;
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

    /** Ends this player's duel shot, if any, and gives them the normal camera back. */
    static void stop(PlayerRef player) {
        if (player != null && SHOT.remove(player.getUuid()) != null) reset(player);
    }

    /** Per player: when their orbit sequence ends, so /mobbattle stop can cut it short. */
    private static final Map<UUID, Long> ORBITS = new ConcurrentHashMap<>();

    /** BattleManager.maintain: forgets orbits and intros that already ended. */
    static void prune() {
        SKIP_HINTED.removeIf(id -> com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id) == null);
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
        Long end = ORBITS.remove(id);
        boolean orbit = end != null && end > System.currentTimeMillis();
        boolean shot = SHOT.remove(id) != null;
        if (orbit || shot) cut(player, orbit);
        return orbit || shot;
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
    private static void watchSkip(PlayerRef player, com.hypixel.hytale.server.core.universe.world.World world) {
        UUID id = player.getUuid();
        Long shot = SHOT.get(id);
        boolean orbit = shot == null && ORBITS.containsKey(id);
        if (shot == null && !orbit) return;
        if (SKIP_HINTED.add(id)) {
            player.sendMessage(com.hypixel.hytale.server.core.Message.raw("Crouch to skip the camera.").color("#aaaaaa"));
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
