package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefChangeSystem;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.system.ModelSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerWalk;
import com.hypixel.hytale.server.npc.role.Role;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A size buff makes jumps grow with the body (playtest 2026-10-12: a x3 player still jumped one block).
 * <ul>
 *   <li>Players: their MovementSettings jumpForce times sqrt(size), so the jump height grows with the size (height
 *   goes with the square of the take-off speed). Vanilla {@code ModelSystems.PlayerUpdateMovementManager} resets a
 *   player's movement to defaults whenever their model changes, and the size buff changes it
 *   (MorphWand.setPlayerSize), so this system runs right after it on every model change and puts the jump back.</li>
 *   <li>Mobs: the walk controllers of their role keep jumpHeight / maxClimbHeight as final fields and the entity
 *   scale doesn't touch them (javap), so they are set by reflection to base x size. Each NPC has its own
 *   controllers (BuilderMotionControllerWalk.build makes a new one), base values are kept per controller.</li>
 * </ul>
 * Speed (2026-10-13, playtest: a big player ran slower than they should): players' baseSpeed and mobs' walk
 * maxHorizontalSpeed (final, same reflection) times sqrt(size), as the jump: a giant's stride is longer, but a
 * linear speed (x10 = ten times as fast) can't be steered.
 * ✅ Player jump and size tested (playtest 2026-10-13); around x9 the jump stops growing (the client's air drag, which
 * grows with speed, is the likely cause; untouched so falls don't get faster). ❓ Speed untested in game.
 */
public class BuffJump extends RefChangeSystem<EntityStore, ModelComponent> {

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, ModelSystems.PlayerUpdateMovementManager.class));

    @Override
    public ComponentType<EntityStore, ModelComponent> componentType() {
        return ModelComponent.getComponentType();
    }

    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), MovementManager.getComponentType());
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void onComponentAdded(Ref<EntityStore> ref, ModelComponent model, Store<EntityStore> store,
                                 CommandBuffer<EntityStore> buffer) {
        player(ref, store);
    }

    @Override
    public void onComponentSet(Ref<EntityStore> ref, ModelComponent old, ModelComponent model, Store<EntityStore> store,
                               CommandBuffer<EntityStore> buffer) {
        player(ref, store);
    }

    @Override
    public void onComponentRemoved(Ref<EntityStore> ref, ModelComponent model, Store<EntityStore> store,
                                   CommandBuffer<EntityStore> buffer) {
    }

    /** Players: jumpForce (size^Buffs.jumpExponent) and baseSpeed (size^Buffs.speedExponent) from the defaults the game just put back. */
    static void player(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        float size = MorphWand.playerSize(ref);
        float speedBuff = 1 + Buffs.totalOf(ref, Buffs.Stat.SPEED) / 100f;
        MovementManager mm0 = accessor.getComponent(ref, MovementManager.getComponentType());
        // The game's own reset is already right, unless a speed buff was just taken off.
        if (Math.abs(size - 1f) < 0.001f && Math.abs(speedBuff - 1f) < 0.001f && mm0 != null && mm0.getSettings() != null
                && mm0.getDefaultSettings() != null && mm0.getSettings().baseSpeed == mm0.getDefaultSettings().baseSpeed) return;
        MovementManager mm = accessor.getComponent(ref, MovementManager.getComponentType());
        PlayerRef player = accessor.getComponent(ref, PlayerRef.getComponentType());
        if (mm == null || player == null) return;
        MovementSettings defaults = mm.getDefaultSettings();
        MovementSettings now = mm.getSettings();
        if (defaults == null || now == null) return;
        // Jump: a bit over sqrt to make up for the client's drag (playtest 2026-10-13, jump key held: x2 reached 6
        // blocks as it should, x3 only 8 of 9 with sqrt). Speed: sqrt.
        float jump = (float) Math.pow(size, Buffs.jumpExponent());
        float k = (float) Math.pow(size, Buffs.speedExponent());
        now.jumpForce = defaults.jumpForce * jump;
        now.swimJumpForce = defaults.swimJumpForce * jump;
        now.baseSpeed = defaults.baseSpeed * k * speedBuff;
        mm.update(player.getPacketHandler());
    }


    // --- Mobs ---

    private static final Field JUMP_HEIGHT = field(MotionControllerWalk.class, "jumpHeight");
    private static final Field CLIMB_HEIGHT = field(MotionControllerWalk.class, "maxClimbHeight");
    private static final Field MAX_SPEED = field(com.hypixel.hytale.server.npc.movement.controllers.MotionControllerBase.class, "maxHorizontalSpeed");
    private static final Field CONTROLLERS = field(Role.class, "motionControllers");
    /** Walk controller -> {jumpHeight, maxClimbHeight, maxHorizontalSpeed} before any size buff. Weak: they go with their NPC. */
    private static final Map<MotionControllerWalk, double[]> BASE = new WeakHashMap<>();
    /** Writing the fields failed once: no more tries (and no more log lines). */
    private static volatile boolean failed;

    private static Field field(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobBattlePlugin.log("BuffJump: no " + type.getSimpleName() + "." + name + " (" + e + "), mobs keep their jump");
            return null;
        }
    }

    /** A mob's size changed (Buffs, world thread): its walk jump and climb heights and its top speed follow. Players: nothing. */
    static void npc(Ref<EntityStore> mob, float size, float speedBuff, ComponentAccessor<EntityStore> accessor) {
        if (JUMP_HEIGHT == null || CLIMB_HEIGHT == null || CONTROLLERS == null || failed || !mob.isValid()) return;
        NPCEntity npc = accessor.getComponent(mob, NPCEntity.getComponentType());
        Role role = npc != null ? npc.getRole() : null;
        if (role == null) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, MotionController> controllers = (Map<String, MotionController>) CONTROLLERS.get(role);
            if (controllers == null) return;
            for (MotionController c : controllers.values()) {
                if (!(c instanceof MotionControllerWalk walk)) continue;
                double[] base;
                synchronized (BASE) {
                    base = BASE.get(walk);
                    if (base == null) {
                        if (Math.abs(size - 1f) < 0.001f && Math.abs(speedBuff - 1f) < 0.001f) continue; // never changed
                        base = new double[] {JUMP_HEIGHT.getDouble(walk), CLIMB_HEIGHT.getDouble(walk),
                                MAX_SPEED != null ? MAX_SPEED.getDouble(walk) : Double.NaN};
                        BASE.put(walk, base);
                    }
                }
                JUMP_HEIGHT.setDouble(walk, base[0] * Math.pow(size, Buffs.mobJumpExponent()));
                CLIMB_HEIGHT.setDouble(walk, base[1] * size);
                if (MAX_SPEED != null && !Double.isNaN(base[2])) MAX_SPEED.setDouble(walk, base[2] * Math.pow(size, Buffs.speedExponent()) * speedBuff);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Once: a JVM that refuses to write final fields would log this on every buff (audit 2026-10-14).
            failed = true;
            MobBattlePlugin.log("BuffJump: could not set the jump of " + ChaosWand.nameOf(mob, accessor) + " (" + e + "), mobs keep their own jump and speed");
        }
    }
}
