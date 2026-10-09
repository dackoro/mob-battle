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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /mobbattle camera: during a fight countdown the owner's camera films the fight, then comes back to them as the
 * fight starts.
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

    /** Wide circling shot of the area around {@code center} for the countdown (no-op with camera off or no countdown). */
    static void overview(PlayerRef player, Vector3d center, List<Ref<EntityStore>> mobs,
                         ComponentAccessor<EntityStore> buffer) {
        // Never let a camera problem break the fight that called it.
        try {
            int seconds = FxSettings.countdownSeconds;
            if (!FxSettings.fightCamera || seconds <= 0 || player == null || !player.isValid() || center == null
                    || mobs.isEmpty()) {
                return;
            }
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
            play(player, seq, eye);
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Fight camera failed: " + e);
        }
    }

    /**
     * Duel standoff, like a western: a close shot of each fighter seen from its opponent's side, then a wide side
     * shot of both facing each other, then back to the player. Unlike the orbit (a fixed path), it follows the
     * fighters as they move: a server camera frame (SetServerCamera) every {@link #STEP_MS} from their current
     * positions, the client gliding between frames.
     */
    static void duel(PlayerRef player, Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer) {
        try {
            int seconds = FxSettings.countdownSeconds;
            if (!FxSettings.fightCamera || seconds <= 0 || player == null || !player.isValid()) return;
            Vector3d pa = ChaosWand.positionOf(a, buffer), pb = ChaosWand.positionOf(b, buffer);
            if (pa == null || pb == null) return;
            long total = seconds * 1000L;
            long shots = Math.max(STEP_MS * 3, total - BACK_MS);
            long id = SEQ.incrementAndGet();
            UUID uuid = player.getUuid();
            SHOT.put(uuid, id);
            Vector3d[] last = {pa, pb};
            var world = buffer.getExternalData().getWorld();
            for (long t = 0; t < shots; t += STEP_MS) {
                int shot = (int) Math.min(2, t * 3 / shots);
                schedule(uuid, id, t, () -> world.execute(() -> {
                    if (!Long.valueOf(id).equals(SHOT.get(uuid))) return;
                    // Where they are now (the last known place if one is gone).
                    Vector3d na = a.isValid() ? ChaosWand.positionOf(a, a.getStore()) : null;
                    Vector3d nb = b.isValid() ? ChaosWand.positionOf(b, b.getStore()) : null;
                    if (na != null) last[0] = na;
                    if (nb != null) last[1] = nb;
                    send(player, duelFrame(last[0], last[1], shot));
                }));
            }
            schedule(uuid, id, shots, () -> {
                ServerCameraSettings back = behindPlayer(player);
                if (back != null) send(player, back);
            });
            schedule(uuid, id, total, () -> {
                if (SHOT.remove(uuid, id)) reset(player);
            });
        } catch (RuntimeException e) {
            MobBattlePlugin.log("Duel camera failed: " + e);
            reset(player);
        }
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
