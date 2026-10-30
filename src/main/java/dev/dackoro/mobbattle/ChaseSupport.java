package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.instructions.Instruction;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import org.joml.Vector3d;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps battle mobs on their target with the role's own chase (2026-10-13, playtest video: 500 Kweebecs vs a Gobliterator,
 * the far ones walked, stopped and turned with a green "?", the near ones stopped attacking; in a royale mobs walked
 * away from the center past enemies right beside them). Two vanilla limits, read from Assets.zip:
 * <ul>
 *   <li>Soft leash (Component_Instruction_Soft_Leash, 21 templates): 20-30 blocks from the leash point (where the mob
 *   spawned) for a few seconds without a hit, it gives up and goes home (Kweebecs: back to Idle, which walks to the
 *   leash point). Fix: while it fights, its leash point is where it stands (refreshed every second), and the original
 *   comes back when it leaves the battle ({@link #restoreLeash}).</li>
 *   <li>Intelligent chase (Component_Instruction_Intelligent_Chase, 38 templates): it walks to the "LastSeen" position
 *   slot, which it only updates while the target is within ViewRange (Kweebec AlertedRange 30) and in sight; beyond
 *   that it drops to Search (wander, stop, "Question" particle). Fix: for a target past that range, the slot is set
 *   to a point toward the target inside the range, and the mob is put in the state that holds the chase.</li>
 * </ul>
 * The chase slot and state are found once per role name by walking the role's instruction tree (reflection): an
 * instruction with a non-marked ReadPosition sensor and a Seek (BodyMotionFind) body motion inside a Combat, Chase or
 * Attack state, the states every vanilla template puts the chase in. Roles without one are left alone.
 */
final class ChaseSupport {

    /** Main states that hold the chase in vanilla templates (Combat: goblins, predators; Chase: trorks; Attack: kweebecs, ferans). */
    private static final Set<String> CHASE_STATES = Set.of("Combat", "Chase", "Attack");
    /** States in which a mob has given up (soft leash home, lost target): put back in its chase state. */
    private static final Set<String> GAVE_UP = Set.of("Idle", "Search", "ReturnHome", "TargetLost", "Leash");

    /** Whether the mob's main state is one of those that dropped the fight (Idle, Search, ReturnHome, ...). */
    static boolean gaveUp(StateSupport state) {
        String current = state != null ? state.getStateName() : null;
        String main = current != null && current.indexOf('.') > 0 ? current.substring(0, current.indexOf('.')) : current;
        return main != null && GAVE_UP.contains(main);
    }
    /** The written LastSeen point stays this far inside the chase range. */
    private static final double RANGE_MARGIN = 4;
    /** Beyond this share of the chase range the slot is written (the chase's own sight test may fail first). */
    private static final double FAR_SHARE = 0.8;

    /** Slot and range of the role's chase, and the state that holds it; slot -1 = no chase found. */
    record Chase(int slot, double range, String state) {
    }

    private static final Chase NONE = new Chase(-1, 0, null);
    private static final Map<String, Chase> BY_ROLE = new ConcurrentHashMap<>();
    /** Battle mob -> its leash point before the battle (restored when it leaves). */
    private static final Map<Ref<EntityStore>, Vector3d> HOME = new ConcurrentHashMap<>();

    /** Set last by initFields (volatile: the other fields are visible once it is). */
    private static volatile Field sensorF;
    private static Field listF, motionF, readSlotF, readRangeF, readMarkedF, stateF, localF;
    private static volatile boolean reflectionFailed;

    private ChaseSupport() {
    }

    /** Roles reloaded (PickLists.rolesChanged): look the trees up again. */
    static void clear() {
        BY_ROLE.clear();
    }

    /**
     * Once a second per battle mob with a target (FightTickSystem.tickBattle, on its world thread): leash point here,
     * and when the target is past its chase range or it gave up, the chase slot toward the target and its chase state.
     */
    static void keep(Ref<EntityStore> self, Ref<EntityStore> target, ComponentAccessor<EntityStore> accessor) {
        NPCEntity npc = accessor.getComponent(self, NPCEntity.getComponentType());
        if (npc == null) return;
        Vector3d me = ChaosWand.positionOf(self, accessor), them = ChaosWand.positionOf(target, accessor);
        if (me == null || them == null) return;
        // Army mobs too: Army.follow puts their home on the owner again only once they stop fighting.
        Vector3d leash = npc.getLeashPoint();
        if (leash != null) {
            HOME.putIfAbsent(self, new Vector3d(leash));
            npc.setLeashPoint(new Vector3d(me));
        }
        StateSupport state = StateSupport.get(self, accessor);
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        if (state == null || marked == null) return;
        Chase chase = chaseOf(npc, state.getStateHelper());
        if (chase.slot < 0) return;
        double dist = me.distance(them);
        String current = state.getStateName();
        String main = current != null && current.indexOf('.') > 0 ? current.substring(0, current.indexOf('.')) : current;
        boolean far = dist > chase.range * FAR_SHARE;
        boolean gaveUp = main != null && GAVE_UP.contains(main);
        if (!far && !gaveUp) return;
        Vector3d slot;
        try {
            slot = marked.getStoredPosition(chase.slot);
        } catch (RuntimeException e) {
            return;
        }
        if (slot == null) return;
        double step = Math.min(dist, Math.max(2, chase.range - RANGE_MARGIN));
        double k = dist > 0.01 ? step / dist : 0;
        slot.set(me.x + (them.x - me.x) * k, me.y + (them.y - me.y) * k, me.z + (them.z - me.z) * k);
        if (!chase.state.equals(main)) {
            if (MobBattlePlugin.debugging()) {
                MobBattlePlugin.debug("chase: " + ChaosWand.nameOf(self, accessor) + " " + current + " -> " + chase.state
                        + " toward " + ChaosWand.nameOf(target, accessor) + " (" + Math.round(dist) + " blocks)");
            }
            state.setState(self, chase.state, FightManager.combatSubStateOf(state.getStateHelper(), chase.state), accessor);
        }
    }

    /**
     * The mob left its battle (FightManager.applyPendingStop) or its wand fight ended (FightTickSystem): its leash
     * point goes back to where it was.
     */
    static void restoreLeash(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        Vector3d home = HOME.remove(self);
        if (home == null) return;
        NPCEntity npc = accessor.getComponent(self, NPCEntity.getComponentType());
        if (npc != null) npc.setLeashPoint(home);
    }

    /** Some mob still has its leash point moved (FightTickSystem keeps ticking until it is restored). */
    static boolean anyHome() {
        return !HOME.isEmpty();
    }

    static boolean hasHome(Ref<EntityStore> self) {
        return HOME.containsKey(self);
    }

    /** BattleManager.maintain: drop removed mobs. */
    static void prune() {
        HOME.keySet().removeIf(r -> !r.isValid());
    }

    /** RoleChange: the same mob under a new Ref keeps its saved home. */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        Vector3d home = HOME.remove(old);
        if (home != null && neu != null) HOME.put(neu, home);
    }

    private static Chase chaseOf(NPCEntity npc, StateMappingHelper states) {
        Role role = npc.getRole();
        String name = npc.getRoleName();
        if (role == null || name == null || reflectionFailed) return NONE;
        return BY_ROLE.computeIfAbsent(name, k -> find(role, states, k));
    }

    /**
     * The reflection fields, once (synchronized, sensorF published last: two worlds starting battles at once could see
     * sensorF set and listF still null, and the NPE turned chase support off for good; audit 2026-10-14).
     */
    private static synchronized void initFields() throws ReflectiveOperationException {
        if (sensorF != null) return;
        Field list = field(Instruction.class, "instructionList");
        Field motion = field(Instruction.class, "bodyMotion");
        Class<?> read = Class.forName("com.hypixel.hytale.server.npc.corecomponents.world.SensorReadPosition");
        readSlotF = field(read, "slot");
        readRangeF = field(read, "range");
        readMarkedF = field(read, "useMarkedTarget");
        Class<?> st = Class.forName("com.hypixel.hytale.server.npc.corecomponents.statemachine.SensorState");
        stateF = field(st, "state");
        localF = field(st, "componentLocal");
        listF = list;
        motionF = motion;
        sensorF = field(Instruction.class, "sensor");
    }

    private static Chase find(Role role, StateMappingHelper states, String name) {
        try {
            initFields();
        } catch (ReflectiveOperationException | RuntimeException e) {
            // The game's classes changed: no chase support for any role.
            reflectionFailed = true;
            MobBattlePlugin.log("Chase support off (role tree not readable): " + e);
            return NONE;
        }
        try {
            Chase found = walk(role.getRootInstruction(), null, states, 0);
            Chase out = found != null ? found : NONE;
            MobBattlePlugin.debug("chase: role " + name + (out.slot >= 0
                    ? " chases from state " + out.state + " (slot " + out.slot + ", range " + out.range + ")" : " has no chase"));
            return out;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // One odd role tree: only that role goes without chase support.
            MobBattlePlugin.log("Chase support off for " + name + " (role tree not readable): " + e);
            return NONE;
        }
    }

    private static Chase walk(Instruction in, String state, StateMappingHelper states, int depth) throws IllegalAccessException {
        if (in == null || depth > 64) return null;
        Object sensor = sensorF.get(in);
        String here = state;
        if (sensor != null && stateF.getDeclaringClass().isInstance(sensor) && !localF.getBoolean(sensor)) {
            here = stateName(states, stateF.getInt(sensor));
        }
        if (here != null && CHASE_STATES.contains(here) && sensor != null && readSlotF.getDeclaringClass().isInstance(sensor)
                && !readMarkedF.getBoolean(sensor)) {
            Object motion = motionF.get(in);
            if (motion != null && motion.getClass().getSimpleName().contains("Find")) {
                return new Chase(readSlotF.getInt(sensor), readRangeF.getDouble(sensor), here);
            }
        }
        Instruction[] list = (Instruction[]) listF.get(in);
        if (list == null) return null;
        for (Instruction child : list) {
            Chase c = walk(child, here, states, depth + 1);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * /mobbattle test ai: what the built role really has, read the same way {@link #keep} reads it. Returns
     * {chase, leash}: the chase ("state Combat, slot 3, range 28" or "none") and the Leash sensor ranges found per main
     * state ("Combat 20/60", "none"), the soft leash that sends a fighting mob home.
     */
    static String[] describe(Ref<EntityStore> mob, ComponentAccessor<EntityStore> accessor) {
        NPCEntity npc = accessor.getComponent(mob, NPCEntity.getComponentType());
        StateSupport state = StateSupport.get(mob, accessor);
        if (npc == null || npc.getRole() == null || state == null) return new String[] {"no role", "?"};
        Chase c = chaseOf(npc, state.getStateHelper());
        if (reflectionFailed) return new String[] {"role tree not readable", "?"};
        String chase = c.slot < 0 ? "none" : "state " + c.state + ", slot " + c.slot + ", range " + fmt(c.range);
        java.util.TreeMap<String, java.util.TreeSet<Double>> leash = new java.util.TreeMap<>();
        try {
            if (leashRangeF == null) {
                leashRangeF = field(Class.forName("com.hypixel.hytale.server.npc.corecomponents.world.SensorLeash"), "range");
            }
            leashes(npc.getRole().getRootInstruction(), null, state.getStateHelper(), leash, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return new String[] {chase, "not readable: " + e};
        }
        if (leash.isEmpty()) return new String[] {chase, "none"};
        StringBuilder sb = new StringBuilder();
        leash.forEach((st, ranges) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(st).append(' ');
            StringBuilder r = new StringBuilder();
            for (double d : ranges) r.append(r.length() > 0 ? "/" : "").append(fmt(d));
            sb.append(r);
        });
        return new String[] {chase, sb.toString()};
    }

    private static Field leashRangeF;

    private static void leashes(Instruction in, String state, StateMappingHelper states,
                                Map<String, java.util.TreeSet<Double>> out, int depth) throws IllegalAccessException {
        if (in == null || depth > 64) return;
        Object sensor = sensorF.get(in);
        String here = state;
        if (sensor != null && stateF.getDeclaringClass().isInstance(sensor) && !localF.getBoolean(sensor)) {
            here = stateName(states, stateF.getInt(sensor));
        }
        if (sensor != null && leashRangeF.getDeclaringClass().isInstance(sensor) && here != null && !"Idle".equals(here)) {
            out.computeIfAbsent(here, k -> new java.util.TreeSet<>()).add(leashRangeF.getDouble(sensor));
        }
        Instruction[] list = (Instruction[]) listF.get(in);
        if (list == null) return;
        for (Instruction child : list) leashes(child, here, states, out, depth + 1);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static String stateName(StateMappingHelper states, int index) {
        try {
            return states.getStateName(index);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
