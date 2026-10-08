package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.StateSupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.npc.util.AttitudeMemoryEntry;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-player wand selection and the code that turns two NPCs against each other.
 * <p>
 * Uses the same mechanisms vanilla roles use when they get hit (see Template_Predator):
 * an attitude override to Hostile, the "LockedTarget" marked slot and the "Combat" state.
 */
public final class FightManager {

    /** Default target slot of every vanilla role (MarkedEntitySupport.DEFAULT_TARGET_SLOT). */
    public static final String TARGET_SLOT = "LockedTarget";
    /**
     * States that start a fight, in order of preference. Predators, goblins and most roles use
     * "Combat"; Trork melee attack from "Chase" (their "Alerted" only waits to spot a target).
     */
    public static final String[] COMBAT_STATES = {"Combat", "Chase", "Alerted"};
    /** How long (seconds) the forced hostility lasts. */
    public static final double HOSTILE_SECONDS = 600;
    /** How long an NPC ignores its previous target after the wand gives it a new one. */
    public static final double RETARGET_IGNORE_SECONDS = 30;

    private static final Map<UUID, Ref<EntityStore>> SELECTION = new ConcurrentHashMap<>();
    private static final Map<Ref<EntityStore>, List<Ref<EntityStore>>> PENDING_STOPS = new ConcurrentHashMap<>();

    static boolean hasPendingStops() { return !PENDING_STOPS.isEmpty(); }
    static void prunePendingStops() { PENDING_STOPS.keySet().removeIf(npc -> !npc.isValid()); }

    static void queueStop(Ref<EntityStore> npc, java.util.Collection<Ref<EntityStore>> opponents) {
        PENDING_STOPS.put(npc, new java.util.ArrayList<>(opponents));
    }

    /** Runs on the NPC's own world thread; stale hostility must not survive a finished battle. */
    static void applyPendingStop(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        List<Ref<EntityStore>> others = PENDING_STOPS.remove(self);
        if (others == null || !BattleManager.isAlive(self, accessor)) return;
        WorldSupport world = WorldSupport.get(self, accessor);
        StateSupport state = StateSupport.get(self, accessor);
        if (world != null) {
            for (Ref<EntityStore> other : others) {
                if (other.isValid() && other.getStore() == self.getStore() && !same(self, other)) {
                    tryOverride(world, other, Attitude.IGNORE, HOSTILE_SECONDS, combatStateOf(state) != null);
                }
            }
        }
        releaseTarget(self, state, accessor);
    }

    private static void releaseTarget(Ref<EntityStore> self, StateSupport state, ComponentAccessor<EntityStore> accessor) {
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        if (marked != null) marked.setMarkedEntity(TARGET_SLOT, null);
        forgetHostiles(self, accessor);
        if (state != null && state.getStateHelper().getStateIndex("Idle") >= 0) {
            state.setState(self, "Idle", null, accessor);
        }
    }

    /** Ignore other rounds and allies, even when the role's natural sensors would pick them. */
    static void refreshBattleIsolation(Ref<EntityStore> self, BattleManager.Battle battle,
                                       ComponentAccessor<EntityStore> accessor) {
        if (!BattleManager.isAlive(self, accessor)) return;
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world == null) return;
        StateSupport state = StateSupport.get(self, accessor);
        boolean hasCombat = combatStateOf(state) != null;
        for (Ref<EntityStore> other : BattleManager.managed()) {
            if (!other.isValid() || other.getStore() != self.getStore() || same(self, other)) continue;
            boolean opponent = battle != null ? battle.has(other) && battle.team(other) != battle.team(self)
                    : isFighting(self, other);
            if (!opponent) tryOverride(world, other, Attitude.IGNORE, HOSTILE_SECONDS, hasCombat);
        }
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        Ref<EntityStore> target = marked != null ? marked.getMarkedEntityRef(TARGET_SLOT) : null;
        if (target != null && target.isValid() && (battle != null
                ? battle.has(target) && battle.team(target) != battle.team(self) : isFighting(self, target))) {
            // Long battles must not lose forced hostility when the initial ten-minute override expires.
            tryOverride(world, target, Attitude.HOSTILE, HOSTILE_SECONDS, hasCombat);
        }
        if (target != null && BattleManager.isManaged(target)
                && (battle == null ? !isFighting(self, target)
                    : !battle.has(target) || battle.team(target) == battle.team(self))) {
            releaseTarget(self, state, accessor);
        }
    }

    private FightManager() {
    }

    public static Ref<EntityStore> getSelection(UUID player) {
        Ref<EntityStore> ref = SELECTION.get(player);
        if (ref != null && !ref.isValid()) {
            SELECTION.remove(player);
            return null;
        }
        return ref;
    }

    public static void select(UUID player, Ref<EntityStore> npc) {
        SELECTION.put(player, npc);
    }

    public static boolean clear(UUID player) {
        return SELECTION.remove(player) != null;
    }

    /** An active wand fight; damage between these two is never filtered (see FightDamageSystem). */
    private record Fight(Ref<EntityStore> a, Ref<EntityStore> b, long expiresAt) {
        boolean involves(Ref<EntityStore> x, Ref<EntityStore> y) {
            return (same(a, x) && same(b, y)) || (same(a, y) && same(b, x));
        }

        boolean has(Ref<EntityStore> x) {
            return same(a, x) || same(b, x);
        }

        Ref<EntityStore> other(Ref<EntityStore> x) {
            return same(a, x) ? b : a;
        }

        boolean isOver() {
            return System.currentTimeMillis() > expiresAt || !a.isValid() || !b.isValid();
        }
    }

    private static final List<Fight> FIGHTS = new CopyOnWriteArrayList<>();

    private static boolean same(Ref<EntityStore> x, Ref<EntityStore> y) {
        return x == y || (x.isValid() && y.isValid() && x.getStore() == y.getStore() && x.getIndex() == y.getIndex());
    }

    /** Registers a fight and pulls both NPCs out of their flock so the flock rules don't protect them. */
    public static void startFight(Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer) {
        startFight(a, b, buffer, false);
    }

    /**
     * @param keepFightsOfB true for group fights (Chaos Wand): b keeps its other fights, so many
     *                      NPCs can be at war with the same one. a always drops its older fights.
     */
    public static void startFight(Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer,
                                  boolean keepFightsOfB) {
        // Each NPC only fights its newest wand opponent: drop any older fight of either one.
        FIGHTS.removeIf(f -> f.isOver() || f.has(a) || (!keepFightsOfB && f.has(b)));
        addFight(a, b, buffer);
    }

    /** Ends every wand fight of these NPCs (before a free-for-all reshuffles them). */
    public static void endFightsOf(List<Ref<EntityStore>> npcs) {
        FIGHTS.removeIf(f -> f.isOver() || npcs.stream().anyMatch(f::has));
    }

    /** Registers a fight without touching any other fight of a or b. */
    public static void addFight(Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer) {
        FIGHTS.add(new Fight(a, b, System.currentTimeMillis() + (long) (HOSTILE_SECONDS * 1000)));

        // Same flock (e.g. a wolf pack): CombatSupport refuses damage between flock members.
        // Leaving the flock is what the vanilla FlockLeave action does.
        Ref<EntityStore> flockA = FlockPlugin.getFlockReference(a, buffer);
        Ref<EntityStore> flockB = FlockPlugin.getFlockReference(b, buffer);
        if (flockA != null && flockB != null && same(flockA, flockB)) {
            buffer.tryRemoveComponent(a, FlockMembership.getComponentType());
            buffer.tryRemoveComponent(b, FlockMembership.getComponentType());
        }
    }

    public static boolean isFighting(Ref<EntityStore> x, Ref<EntityStore> y) {
        if (x == null || y == null || FIGHTS.isEmpty()) {
            return false;
        }
        FIGHTS.removeIf(Fight::isOver);
        for (Fight f : FIGHTS) {
            if (f.involves(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static String combatStateOf(StateSupport state) {
        if (state == null) {
            return null;
        }
        for (String name : COMBAT_STATES) {
            if (state.getStateHelper().getStateIndex(name) >= 0) {
                return name;
            }
        }
        return null;
    }

    /** Some CAE bosses execute movement and abilities only in Combat.Main, not Combat.Default. */
    static String combatSubStateOf(com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper helper,
                                   String combatState) {
        int index = helper.getStateIndex(combatState);
        return index >= 0 && helper.getSubStateIndex(index, "Main") >= 0 ? "Main" : null;
    }

    /** Damage of the opening "provocation" hit. Tiny: it's only there to trigger reactions. */
    public static final float PROVOKE_DAMAGE = 0.01f;

    /**
     * Makes {@code attacker} "hit" {@code victim} for a tiny amount through the normal damage
     * pipeline. Almost every role that can fight (vanilla or from other mods) reacts to being
     * damaged with its Damage sensor: it retaliates, or flees if it's passive. This is the
     * universal trigger; the attitude / target / state tweaks are the reinforcement.
     */
    public static void provoke(Ref<EntityStore> victim, Ref<EntityStore> attacker, ComponentAccessor<EntityStore> buffer) {
        if (!victim.isValid() || !attacker.isValid()) {
            return;
        }
        // "Physical" = Server/Entity/Damage/Physical.json (the DamageCause.PHYSICAL constant is deprecated).
        int cause = DamageCause.getAssetMap().getIndex("Physical");
        DamageSystems.executeDamage(victim, buffer,
                new Damage(new Damage.EntitySource(attacker), cause, PROVOKE_DAMAGE));
        // Give the health back a moment later, once the damage pipeline has applied it.
        PENDING_HEALS.add(new PendingHeal(victim, PROVOKE_DAMAGE, System.currentTimeMillis() + HEAL_DELAY_MS));
    }

    private static final long HEAL_DELAY_MS = 300;

    private record PendingHeal(Ref<EntityStore> npc, float amount, long at) {
    }

    private static final List<PendingHeal> PENDING_HEALS = new CopyOnWriteArrayList<>();

    public static boolean hasPendingHeals() {
        return !PENDING_HEALS.isEmpty();
    }

    /** Called from the tick system for {@code self}: restores the provocation damage. */
    public static void applyPendingHeals(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        long now = System.currentTimeMillis();
        for (PendingHeal heal : PENDING_HEALS) {
            if (!heal.npc().isValid()) {
                PENDING_HEALS.remove(heal);
                continue;
            }
            if (now < heal.at() || heal.npc().getIndex() != self.getIndex()) {
                continue;
            }
            EntityStatMap stats = accessor.getComponent(self, EntityStatMap.getComponentType());
            if (stats != null) {
                stats.addStatValue(DefaultEntityStatTypes.getHealth(), heal.amount());
            }
            PENDING_HEALS.remove(heal);
        }
    }

    /** Current AI state name, for the debug log. */
    public static String stateOf(Ref<EntityStore> npc, ComponentAccessor<EntityStore> accessor) {
        StateSupport state = StateSupport.get(npc, accessor);
        return state != null ? state.getStateName() : "?";
    }

    public static boolean hasFights() {
        return !FIGHTS.isEmpty();
    }

    /** The current wand opponent of {@code npc}, or null if it isn't in a wand fight. */
    public static Ref<EntityStore> enemyOf(Ref<EntityStore> npc) {
        for (Fight f : FIGHTS) {
            if (!f.isOver() && f.has(npc)) {
                return f.other(npc);
            }
        }
        return null;
    }

    /** Result of turning one NPC against another. */
    public enum Result {
        /** Role accepted the hostility override: it will attack. */
        AGGRESSIVE,
        /** Role has no attitude memory (passive): it will only react, e.g. flee. */
        PASSIVE,
        /** Not an NPC with a role. */
        NOT_NPC
    }

    /** Makes {@code self} hostile to {@code enemy} and locks it as its target. */
    public static Result turnAgainst(Ref<EntityStore> self, Ref<EntityStore> enemy, ComponentAccessor<EntityStore> accessor) {
        PENDING_STOPS.remove(self); // A newer combat order replaces a queued cleanup.
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world == null) {
            return Result.NOT_NPC;
        }

        // The attitude override memory only exists in roles that use the OverrideAttitude action
        // (predators, neutral animals, livestock...). Others, like goblins, don't have it but do
        // have a "Combat" state driven by LockedTarget, so they can still fight.
        StateSupport state = StateSupport.get(self, accessor);
        String combatState = combatStateOf(state);
        boolean hasCombat = combatState != null;
        // Only fighters (roles with a Combat state) get the attitude memory created for them,
        // otherwise pigs and dummies would be counted as aggressive.
        boolean hostile = tryOverride(world, enemy, Attitude.HOSTILE, HOSTILE_SECONDS, hasCombat);
        if (!hostile && !hasCombat) {
            // Truly passive: it will only react (flee) when hit.
            return Result.PASSIVE;
        }

        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        if (marked != null) {
            // If it was busy with someone else, make it ignore that one for a while,
            // otherwise its own sensors lock the old target again.
            Ref<EntityStore> previous = marked.getMarkedEntityRef(TARGET_SLOT);
            if (previous != null && previous.isValid() && !same(previous, enemy)) {
                tryOverride(world, previous, Attitude.IGNORE, RETARGET_IGNORE_SECONDS, hasCombat);
            }
            marked.setMarkedEntity(TARGET_SLOT, enemy);
        }

        // A new wand order replaces older ones: forget the hostiles it was given before (e.g. the
        // boss everyone was told to attack), otherwise intelligent NPCs keep going back to them.
        forgetHostiles(self, accessor);
        rememberAsHostile(self, enemy, accessor);

        if (hasCombat) {
            state.setState(self, combatState, combatSubStateOf(state.getStateHelper(), combatState), accessor);
        }
        return Result.AGGRESSIVE;
    }

    /** Clears the NPC's hostile target memory (known hostiles + closest hostile). */
    static void forgetHostiles(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        TargetMemory memory = accessor.getComponent(self, TargetMemory.getComponentType());
        if (memory == null) {
            return;
        }
        memory.getKnownHostiles().clear();
        memory.getKnownHostilesList().clear();
        memory.setClosestHostile(null);
    }

    /**
     * Intelligent NPCs (goblins, trorks...) don't attack LockedTarget directly: their
     * CombatActionEvaluator picks targets from the TargetMemory component, which vanilla fills
     * with the AddToHostileTargetMemory action. Put the enemy there (and out of the friendlies,
     * e.g. goblin vs goblin) and make it the closest hostile so it gets picked first.
     */
    static void rememberAsHostile(Ref<EntityStore> self, Ref<EntityStore> enemy, ComponentAccessor<EntityStore> accessor) {
        TargetMemory memory = accessor.getComponent(self, TargetMemory.getComponentType());
        if (memory == null) {
            return;
        }
        int id = enemy.getIndex();
        if (memory.getKnownFriendlies().containsKey(id)) {
            memory.getKnownFriendlies().remove(id);
            memory.getKnownFriendliesList().removeIf(r -> same(r, enemy));
        }
        boolean known = memory.getKnownHostiles().containsKey(id);
        memory.getKnownHostiles().put(id, memory.getRememberFor());
        if (!known) {
            memory.getKnownHostilesList().add(enemy);
        }
        memory.setClosestHostile(enemy);
    }

    /**
     * overrideAttitude throws NPE when the role has no attitude override memory (it is only
     * created for roles that use the OverrideAttitude action). Roles like goblins lack it, yet
     * their combat logic only attacks Hostile targets, so we create that memory ourselves.
     */
    private static boolean tryOverride(WorldSupport world, Ref<EntityStore> other, Attitude attitude, double seconds,
                                       boolean createMemory) {
        try {
            world.overrideAttitude(other, attitude, seconds);
            return true;
        } catch (NullPointerException e) {
            if (!createMemory || !ensureOverrideMemory(world)) {
                return false;
            }
            try {
                world.overrideAttitude(other, attitude, seconds);
                return true;
            } catch (NullPointerException again) {
                return false;
            }
        }
    }

    private static Field overrideMemoryField;

    private static boolean ensureOverrideMemory(WorldSupport world) {
        try {
            if (overrideMemoryField == null) {
                Field f = WorldSupport.class.getDeclaredField("attitudeOverrideMemory");
                f.setAccessible(true);
                overrideMemoryField = f;
            }
            if (overrideMemoryField.get(world) == null) {
                overrideMemoryField.set(world, new Int2ObjectOpenHashMap<AttitudeMemoryEntry>());
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobBattlePlugin.log("Could not create attitude memory: " + e);
            return false;
        }
    }
}
