package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.flock.FlockMembership;
import com.hypixel.hytale.server.flock.FlockPlugin;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
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
    static void cancelPendingStop(Ref<EntityStore> npc) { PENDING_STOPS.remove(npc); }

    /** @param opponents an immutable list, shared by every member of the same battle */
    /** True if {@code ref} is an online player's entity (no component read: queueStop runs on any thread). */
    private static boolean isOnlinePlayer(Ref<EntityStore> ref) {
        for (com.hypixel.hytale.server.core.universe.PlayerRef p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            if (p.getReference() == ref) return true;
        }
        return false;
    }

    static void queueStop(Ref<EntityStore> npc, List<Ref<EntityStore>> opponents) {
        // Only NPCs tick and consume this queue: a player left in it would keep FightTickSystem busy for good.
        if (BattleManager.isPlayerFighter(npc) || isOnlinePlayer(npc)) {
            return;
        }
        PENDING_STOPS.put(npc, opponents);
        STOP_SECONDS.remove(npc);
    }

    /** Ignore time of a pending stop queued with {@link #queueRelease}; absent = HOSTILE_SECONDS. */
    private static final Map<Ref<EntityStore>, Double> STOP_SECONDS = new ConcurrentHashMap<>();

    /**
     * Like queueStop, but the IGNORE toward {@code others} only lasts {@code seconds}: afterwards the mob's own
     * attitudes apply again (Army release: goblins go back to hating the player and liking other goblins).
     * Adds to a stop already pending (a disband releases several mobs fighting the same opponent).
     */
    static void queueRelease(Ref<EntityStore> npc, List<Ref<EntityStore>> others, double seconds) {
        if (BattleManager.isPlayerFighter(npc) || isOnlinePlayer(npc)) return;
        PENDING_STOPS.merge(npc, others, (a, b) -> {
            List<Ref<EntityStore>> all = new java.util.ArrayList<>(a);
            all.addAll(b);
            return all;
        });
        STOP_SECONDS.merge(npc, seconds, Math::min);
    }

    /** The other side of every wand fight {@code npc} is in. */
    static List<Ref<EntityStore>> opponentsOf(Ref<EntityStore> npc) {
        List<Ref<EntityStore>> out = new java.util.ArrayList<>();
        for (Fight f : FIGHTS) {
            if (!f.isOver() && f.has(npc)) out.add(f.other(npc));
        }
        return out;
    }

    /** Drops state of removed entities and finished fights (BattleManager.maintain, once per second). */
    static void prune() {
        PENDING_STOPS.keySet().removeIf(npc -> !npc.isValid());
        ChaseSupport.prune();
        STOP_SECONDS.keySet().removeIf(npc -> !PENDING_STOPS.containsKey(npc));
        long now = System.currentTimeMillis();
        PENDING_HEALS.values().removeIf(h -> !h.npc().isValid() || now > h.at() + HEAL_EXPIRE_MS);
        SELECTION.values().removeIf(npc -> !npc.isValid());
        if (FIGHTS.removeIf(Fight::isOver)) {
            reindex();
        }
    }

    /** Runs on the NPC's own world thread; stale hostility must not survive a finished battle. */
    static void applyPendingStop(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        List<Ref<EntityStore>> others = PENDING_STOPS.remove(self);
        Double custom = STOP_SECONDS.remove(self);
        double seconds = custom != null ? custom : HOSTILE_SECONDS;
        if (others == null || !BattleManager.isAlive(self, accessor)) return;
        ChaseSupport.restoreLeash(self, accessor);
        WorldSupport world = WorldSupport.get(self, accessor);
        StateSupport state = StateSupport.get(self, accessor);
        if (world != null) {
            for (Ref<EntityStore> other : others) {
                if (other.isValid() && other.getStore() == self.getStore() && !same(self, other)) {
                    tryOverride(world, other, Attitude.IGNORE, seconds, combatStateOf(state) != null);
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
    /**
     * {@code full}: also the pass over every managed mob (O(managed), so FightTickSystem runs it only when battle
     * membership changed or every half minute; the IGNORE it sets lasts HOSTILE_SECONDS anyway).
     */
    static void refreshBattleIsolation(Ref<EntityStore> self, BattleManager.Battle battle,
                                       ComponentAccessor<EntityStore> accessor, boolean full) {
        if (!BattleManager.isAlive(self, accessor)) return;
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world == null) return;
        StateSupport state = StateSupport.get(self, accessor);
        boolean hasCombat = combatStateOf(state) != null;
        if (full) {
            for (Ref<EntityStore> other : BattleManager.managed()) {
                if (!other.isValid() || other.getStore() != self.getStore() || same(self, other)) continue;
                boolean opponent = battle != null ? battle.has(other) && battle.team(other) != battle.team(self)
                        : isFighting(self, other);
                if (!opponent) tryOverride(world, other, Attitude.IGNORE, HOSTILE_SECONDS, hasCombat);
            }
        }
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        Ref<EntityStore> target = marked != null ? marked.getMarkedEntityRef(TARGET_SLOT) : null;
        if (target != null && target.isValid() && (battle != null
                ? battle.has(target) && battle.team(target) != battle.team(self) : isFighting(self, target))) {
            // Long battles must not lose forced hostility when the initial ten-minute override expires.
            tryOverride(world, target, Attitude.HOSTILE, HOSTILE_SECONDS, hasCombat);
        }
        boolean isolated = battle != null && FxSettings.isolated(battle.kind);
        if (isolated) {
            ignoreOutsiders(self, world, hasCombat, accessor);
        }
        boolean outsider = target != null && !BattleManager.isManaged(target)
                && isolated;
        if (outsider || target != null && BattleManager.isManaged(target)
                && (battle == null ? !isFighting(self, target)
                    : !battle.has(target) || battle.team(target) == battle.team(self))) {
            releaseTarget(self, state, accessor);
        }
    }

    /**
     * Battle isolation from the other side (2026-10-14, test calls: onlookers of one event goblin went for an isolated
     * duel's fighters with no group call at all; isolation only made the fighters ignore outsiders). An NPC outside
     * every battle whose LockedTarget fights in an isolated battle lets it go and ignores it OUTSIDER_IGNORE_SECONDS:
     * its hits were cancelled anyway, now it no longer chases and swings at them. Also catches a group call the role
     * read before FightTickSystem could drop it. Every tick while a battle runs: one component read.
     */
    static void dropFighterTarget(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        Ref<EntityStore> target = marked != null ? marked.getMarkedEntityRef(TARGET_SLOT) : null;
        if (target == null || !target.isValid() || !BattleManager.isManaged(target) || isFighting(self, target)) return;
        BattleManager.Battle battle = BattleManager.battleOf(target);
        if (battle == null || !FxSettings.isolated(battle.kind)) return;
        WorldSupport world = WorldSupport.get(self, accessor);
        StateSupport state = StateSupport.get(self, accessor);
        if (world != null) tryOverride(world, target, Attitude.IGNORE, OUTSIDER_IGNORE_SECONDS, combatStateOf(state) != null);
        releaseTarget(self, state, accessor);
        if (MobBattlePlugin.debugging()) {
            MobBattlePlugin.debug("outsider " + ChaosWand.nameOf(self, accessor) + " let go of " + ChaosWand.nameOf(target, accessor) + " (isolated battle)");
        }
    }

    /** Range in which battle mobs are told to ignore players and wild mobs (/mobbattle battleIsolation on). */
    static final double OUTSIDER_RANGE = 48;
    /** Short override, renewed by every isolation refresh, so turning isolation off takes effect quickly. */
    static final double OUTSIDER_IGNORE_SECONDS = 12;

    /** Their hits are cancelled anyway; this keeps battle mobs from chasing or swinging at outsiders. */
    private static void ignoreOutsiders(Ref<EntityStore> self, WorldSupport world, boolean hasCombat,
                                        ComponentAccessor<EntityStore> accessor) {
        org.joml.Vector3d pos = ChaosWand.positionOf(self, accessor);
        if (pos == null) return;
        // Thread-local list: iterated right away, and nothing in the loop runs another sphere query.
        for (Ref<EntityStore> other : com.hypixel.hytale.server.core.util.TargetUtil
                .getAllEntitiesInSphere(pos, OUTSIDER_RANGE, accessor)) {
            if (other == null || !other.isValid() || other == self || BattleManager.isManaged(other)) continue;
            // Only things a mob can target: skip arrows, dropped items and other entities.
            if (accessor.getComponent(other, NPCEntity.getComponentType()) == null
                    && accessor.getComponent(other, PlayerRef.getComponentType()) == null) continue;
            tryOverride(world, other, Attitude.IGNORE, OUTSIDER_IGNORE_SECONDS, hasCombat);
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
    /** Every NPC in a wand fight, so the per-tick check for the other NPCs is one set lookup. */
    private static volatile java.util.Set<Ref<EntityStore>> fighters = java.util.Set.of();

    /** Synchronized: two worlds reindexing at once could otherwise publish an older set last. */
    private static synchronized void reindex() {
        java.util.Set<Ref<EntityStore>> set = new java.util.HashSet<>();
        for (Fight f : FIGHTS) {
            set.add(f.a());
            set.add(f.b());
        }
        fighters = set;
        BattleManager.membershipChanged();
    }

    static boolean isFighter(Ref<EntityStore> npc) {
        return fighters.contains(npc);
    }

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
        if (FIGHTS.removeIf(f -> f.isOver() || npcs.stream().anyMatch(f::has))) {
            reindex();
        }
    }

    /**
     * Group battles (teams, royale): members on different sides that share a flock (an owner and its pet or
     * summon) leave it, so the flock rules don't protect them from each other. Same-side flocks stay intact.
     * Leaving is permanent, like in a duel (the vanilla FlockLeave action).
     */
    static void splitFlocksAcrossSides(BattleManager.Battle battle, ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> members = new java.util.ArrayList<>(battle.members());
        List<Ref<EntityStore>> flocks = new java.util.ArrayList<>(members.size());
        for (Ref<EntityStore> m : members) {
            flocks.add(m.isValid() ? FlockPlugin.getFlockReference(m, buffer) : null);
        }
        java.util.Set<Ref<EntityStore>> leave = new java.util.HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            Ref<EntityStore> fi = flocks.get(i);
            if (fi == null) continue;
            for (int j = i + 1; j < members.size(); j++) {
                Ref<EntityStore> fj = flocks.get(j);
                if (fj != null && same(fi, fj) && battle.team(members.get(i)) != battle.team(members.get(j))) {
                    leave.add(members.get(i));
                    leave.add(members.get(j));
                }
            }
        }
        for (Ref<EntityStore> npc : leave) {
            buffer.tryRemoveComponent(npc, FlockMembership.getComponentType());
        }
        if (!leave.isEmpty()) {
            MobBattlePlugin.log(leave.size() + " mobs left their flock to fight on different sides");
        }
    }

    /** Registers a fight without touching any other fight of a or b. */
    public static void addFight(Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer) {
        FIGHTS.add(new Fight(a, b, System.currentTimeMillis() + (long) (HOSTILE_SECONDS * 1000)));
        reindex();

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
        if (x == null || y == null || !isFighter(x)) {
            return false;
        }
        for (Fight f : FIGHTS) {
            if (!f.isOver() && f.involves(x, y)) {
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
        // Players (/mobbattle playerFights) need no provocation, and their heal back would wait in the NPC-only
        // queue (FightTickSystem never ticks players).
        if (buffer.getComponent(victim, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) != null) {
            return;
        }
        // "Physical" = Server/Entity/Damage/Physical.json (the DamageCause.PHYSICAL constant is deprecated).
        int cause = DamageCause.getAssetMap().getIndex("Physical");
        DamageSystems.executeDamage(victim, buffer,
                new Damage(new Damage.EntitySource(attacker), cause, PROVOKE_DAMAGE));
        // Give the health back a moment later, once the damage pipeline has applied it.
        PENDING_HEALS.merge(victim, new PendingHeal(victim, PROVOKE_DAMAGE, System.currentTimeMillis() + HEAL_DELAY_MS),
                (old, add) -> new PendingHeal(victim, old.amount() + add.amount(), add.at()));
    }

    private static final long HEAL_DELAY_MS = 300;
    /** A heal for an NPC that never ticked again (unloaded chunk) is dropped after this. */
    private static final long HEAL_EXPIRE_MS = 10_000;

    private record PendingHeal(Ref<EntityStore> npc, float amount, long at) {
    }

    /** One entry per NPC (provocations add up), so each NPC's tick is one lookup instead of a list scan. */
    private static final Map<Ref<EntityStore>, PendingHeal> PENDING_HEALS = new ConcurrentHashMap<>();

    public static boolean hasPendingHeals() {
        return !PENDING_HEALS.isEmpty();
    }

    /** Called from the tick system for {@code self}: restores the provocation damage. */
    public static void applyPendingHeals(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        PendingHeal heal = PENDING_HEALS.get(self);
        if (heal == null || System.currentTimeMillis() < heal.at() || !PENDING_HEALS.remove(self, heal)) {
            return;
        }
        EntityStatMap stats = accessor.getComponent(self, EntityStatMap.getComponentType());
        if (stats != null) {
            stats.addStatValue(DefaultEntityStatTypes.getHealth(), heal.amount());
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
        if (!isFighter(npc)) {
            return null;
        }
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

    /**
     * Whether {@code self} could attack at all, without changing anything (same test as {@link #turnAgainst}):
     * a role with a combat state, or with an attitude memory.
     */
    static boolean canAttack(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world == null) return false;
        if (combatStateOf(StateSupport.get(self, accessor)) != null) return true;
        return ensureOverrideMemory(world, false) && RoleAttack.mayAttack(self, accessor);
    }

    /** Makes {@code self} hostile to {@code enemy} and locks it as its target. */
    public static Result turnAgainst(Ref<EntityStore> self, Ref<EntityStore> enemy, ComponentAccessor<EntityStore> accessor) {
        return turnAgainst(self, enemy, accessor, true);
    }

    /**
     * @param ignorePrevious false for Army Wand mobs: an old target they switched away from must still get
     *                       an answer if it keeps hitting them (self-defense), so it is not ignored for 30 s.
     */
    public static Result turnAgainst(Ref<EntityStore> self, Ref<EntityStore> enemy, ComponentAccessor<EntityStore> accessor,
                                     boolean ignorePrevious) {
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
        if (!hasCombat && !RoleAttack.mayAttack(self, accessor)) {
            // Its role has no attack (Bunny, Rabbit...): even with an attitude memory it can only flee.
            return Result.PASSIVE;
        }
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
            if (ignorePrevious && previous != null && previous.isValid() && !same(previous, enemy)) {
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

    /**
     * Army Wand: {@code self} must not attack {@code player} (its owner): friendly attitude toward it, and the
     * player out of its target and hostile memory. Roles without attitude memory (e.g. goblins) get it created.
     */
    static void befriend(Ref<EntityStore> self, Ref<EntityStore> player, ComponentAccessor<EntityStore> accessor) {
        if (player == null || !player.isValid() || player.getStore() != self.getStore()) {
            return;
        }
        WorldSupport world = WorldSupport.get(self, accessor);
        StateSupport state = StateSupport.get(self, accessor);
        if (world != null) {
            tryOverride(world, player, Attitude.FRIENDLY, HOSTILE_SECONDS, combatStateOf(state) != null);
        }
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        Ref<EntityStore> locked = marked != null ? marked.getMarkedEntityRef(TARGET_SLOT) : null;
        TargetMemory memory = accessor.getComponent(self, TargetMemory.getComponentType());
        if (memory != null) {
            int id = player.getIndex();
            memory.getKnownHostiles().remove(id);
            memory.getKnownHostilesList().removeIf(r -> same(r, player));
            Ref<EntityStore> closest = memory.getClosestHostile();
            if (closest != null && same(closest, player)) memory.setClosestHostile(null);
        }
        if (locked != null && same(locked, player)) {
            releaseTarget(self, state, accessor);
        }
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
        // Check the memory first: battle isolation calls this for every pair of managed mobs every 5 s, and
        // a thrown NPE per call (passive roles have no memory) costs far more than a field read.
        if (!ensureOverrideMemory(world, createMemory)) {
            return false;
        }
        try {
            world.overrideAttitude(other, attitude, seconds);
            return true;
        } catch (NullPointerException e) {
            return false;
        }
    }

    /** How long a mob holding back from a target it can't reach ignores it (renewed every second while it lasts). */
    static final double HOLD_IGNORE_SECONDS = 2.5;

    /**
     * Medium: {@code self} stops going for {@code target} for a moment (FightTickSystem.holdsBack): IGNORE toward it
     * and its LockedTarget cleared if it is that one. The battle keeps the target; the isolation refresh and relock
     * make it hostile again once it can be reached.
     */
    static void holdOff(Ref<EntityStore> self, Ref<EntityStore> target, ComponentAccessor<EntityStore> accessor) {
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world != null) tryOverride(world, target, Attitude.IGNORE, HOLD_IGNORE_SECONDS, combatStateOf(StateSupport.get(self, accessor)) != null);
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        if (marked != null && marked.getMarkedEntityRef(TARGET_SLOT) == target) marked.setMarkedEntity(TARGET_SLOT, null);
    }

    /**
     * Countdown truce: IGNORE toward each of {@code others} for {@code seconds}, and a LockedTarget that is one of
     * them is let go. False if {@code self} has no NPC attitude support (a player).
     */
    static boolean truce(Ref<EntityStore> self, java.util.List<Ref<EntityStore>> others, double seconds,
                         ComponentAccessor<EntityStore> accessor) {
        WorldSupport world = WorldSupport.get(self, accessor);
        if (world == null) return false;
        for (Ref<EntityStore> other : others) {
            if (other != self) tryOverride(world, other, Attitude.IGNORE, seconds, true);
        }
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, accessor);
        Ref<EntityStore> locked = marked != null ? marked.getMarkedEntityRef(TARGET_SLOT) : null;
        if (locked != null && others.contains(locked)) marked.setMarkedEntity(TARGET_SLOT, null);
        return true;
    }

    /** IGNORE toward {@code other} for {@code seconds}, creating the override memory if needed (SummonCalm). */
    static void ignore(WorldSupport world, Ref<EntityStore> other, double seconds) {
        tryOverride(world, other, Attitude.IGNORE, seconds, true);
    }

    private static volatile Field overrideMemoryField;
    private static volatile boolean overrideMemoryUnreadable;

    /** True if the role has an attitude override memory, creating it when {@code create} is set. */
    private static boolean ensureOverrideMemory(WorldSupport world, boolean create) {
        if (overrideMemoryUnreadable) {
            return true; // Field missing in this game version: just try the call (the NPE is still caught).
        }
        try {
            Field f = overrideMemoryField;
            if (f == null) {
                f = WorldSupport.class.getDeclaredField("attitudeOverrideMemory");
                f.setAccessible(true);
                overrideMemoryField = f;
            }
            if (f.get(world) != null) {
                return true;
            }
            if (!create) {
                return false;
            }
            f.set(world, new Int2ObjectOpenHashMap<AttitudeMemoryEntry>());
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            overrideMemoryUnreadable = true;
            MobBattlePlugin.log("Could not read the attitude memory: " + e);
            return true;
        }
    }
}
