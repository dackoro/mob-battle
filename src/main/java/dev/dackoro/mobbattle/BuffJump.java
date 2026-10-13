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
 * A size buff makes jumps grow with the body (user, 2026-10-12: a x3 player still jumped one block).
 * <ul>
 *   <li>Players: their MovementSettings jumpForce times sqrt(size), so the jump height grows with the size (height
 *   goes with the square of the take-off speed). Vanilla {@code ModelSystems.PlayerUpdateMovementManager} resets a
 *   player's movement to defaults whenever their model changes, and the size buff changes it
 *   (MorphWand.setPlayerSize), so this system runs right after it on every model change and puts the jump back.</li>
 *   <li>Mobs: the walk controllers of their role keep jumpHeight / maxClimbHeight as final fields and the entity
 *   scale doesn't touch them (javap), so they are set by reflection to base x size. Each NPC has its own
 *   controllers (BuilderMotionControllerWalk.build makes a new one), base values are kept per controller.</li>
 * </ul>
 * ❓ Untested in game; sqrt is the frictionless formula, the client's air drag may cut it.
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

    /** Players: jumpForce from the defaults the game just put back, times sqrt of their size. */
    private static void player(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        float size = MorphWand.playerSize(ref);
        if (Math.abs(size - 1f) < 0.001f) return; // the game's own reset is already right
        MovementManager mm = accessor.getComponent(ref, MovementManager.getComponentType());
        PlayerRef player = accessor.getComponent(ref, PlayerRef.getComponentType());
        if (mm == null || player == null) return;
        MovementSettings defaults = mm.getDefaultSettings();
        MovementSettings now = mm.getSettings();
        if (defaults == null || now == null) return;
        float k = (float) Math.sqrt(size);
        now.jumpForce = defaults.jumpForce * k;
        now.swimJumpForce = defaults.swimJumpForce * k;
        mm.update(player.getPacketHandler());
    }

    // --- Mobs ---

    private static final Field JUMP_HEIGHT = field(MotionControllerWalk.class, "jumpHeight");
    private static final Field CLIMB_HEIGHT = field(MotionControllerWalk.class, "maxClimbHeight");
    private static final Field CONTROLLERS = field(Role.class, "motionControllers");
    /** Walk controller -> {jumpHeight, maxClimbHeight} before any size buff. Weak: they go with their NPC. */
    private static final Map<MotionControllerWalk, double[]> BASE = new WeakHashMap<>();

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

    /** A mob's size changed (Buffs, world thread): its walk jump and climb heights follow. Players: nothing. */
    static void npc(Ref<EntityStore> mob, float size, ComponentAccessor<EntityStore> accessor) {
        if (JUMP_HEIGHT == null || CLIMB_HEIGHT == null || CONTROLLERS == null || !mob.isValid()) return;
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
                        if (Math.abs(size - 1f) < 0.001f) continue; // never changed
                        base = new double[] {JUMP_HEIGHT.getDouble(walk), CLIMB_HEIGHT.getDouble(walk)};
                        BASE.put(walk, base);
                    }
                }
                JUMP_HEIGHT.setDouble(walk, base[0] * size);
                CLIMB_HEIGHT.setDouble(walk, base[1] * size);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobBattlePlugin.log("BuffJump: could not set the jump of " + ChaosWand.nameOf(mob, accessor) + " (" + e + ")");
        }
    }
}
