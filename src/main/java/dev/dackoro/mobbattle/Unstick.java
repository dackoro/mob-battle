package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.movement.controllers.MotionControllerFly;
import com.hypixel.hytale.server.npc.role.Role;
import org.joml.Vector3d;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle unstick (default on) and the stand-off shove (/mobbattle standoffTime, default 15 s, 0 = off; either
 * one keeps this running): a battle mob that can't reach its opponent because they are on different levels
 * (one in a cave, the other on the surface or up a mountain) is brought next to it. "Stuck" = for STUCK_MS it got
 * no closer than before, is not fighting and its opponent is more than MIN_HEIGHT_GAP blocks above or below it.
 * Fighting = it hit an enemy (a ranged mob shooting down from a cliff is not stuck), or something on its own level
 * hit it (being shot from another level does not count: that is exactly the stuck case). It then appears 2-4 blocks
 * from the opponent, on a spot to stand on at the opponent's height (FightFx.standableY), with the summon burst.
 * Fliers (roles with a "Fly" motion controller, as BodyMotionTakeOff checks): height does not trap them, but a cave
 * roof or a wall can, so they are moved after FLY_STUCK_MS whatever the height. A walker is never moved towards an
 * opponent that is flying right now (it would land under it): the flier comes down to attack.
 * Stand-off (2026-10-12): a walker on its opponent's level that, for standoffTime, got no closer, hit nothing and was
 * not hit, and is beyond melee reach, is shoved toward it (not teleported). Two Goblin_Feastmaster each keep 8
 * blocks from their target (role CombatMaintainDistance) and only close in with a charge, which the role turns off
 * while another goblin is within 5 blocks, so a duel of two in a crowd of goblins stood still for good. The shove goes
 * straight to the role's motion controller: the Feastmaster's role has KnockbackScale 0, and Role.processSetVelocity /
 * AddVelocity multiply any knockback by it (javap), so a KnockbackComponent push (the arena wall's) does not move it.
 * Mobs that can't attack (passives) are never shoved.
 * Once per second per world (BattleLifecycleSystem, world thread, outside the systems). Players are never moved.
 */
final class Unstick {

    static final long STUCK_MS = 10_000;
    /** Stand-off shoves without effect before the mob is moved next to its opponent instead. */
    private static final int STANDOFF_SHOVES = 2;
    private static final long FLY_STUCK_MS = 20_000;
    private static final double MIN_HEIGHT_GAP = 3;
    /** Closer than this they are fighting, not stuck. */
    private static final double MELEE = 4;
    /** It counts as getting closer only if it gained at least this much. */
    private static final double PROGRESS = 1;
    private static final int STAND_RANGE = 6;
    /** No stand-off shove past this gap: a mob that far is still walking in (ChaseSupport). */
    private static final double STANDOFF_MAX_GAP = 16;

    private static final class Track {
        Ref<EntityStore> target;
        double best;
        long since;
        /** Stand-off shoves in a row that brought no hit and no progress. */
        int shoves;
    }

    /** Who hit a battle mob last, and when. */
    private record Hurt(Ref<EntityStore> by, long at) {
    }

    private static final Map<Ref<EntityStore>, Track> TRACKS = new ConcurrentHashMap<>();
    /** Battle mob -> when it last hit an enemy (BattleManager.recordDamage). */
    private static final Map<Ref<EntityStore>, Long> LAST_HIT = new ConcurrentHashMap<>();
    /** Battle mob -> its last hit taken (FightDamageSystem). */
    private static final Map<Ref<EntityStore>, Hurt> LAST_HURT = new ConcurrentHashMap<>();

    private Unstick() {
    }

    /** Unstick or the stand-off shove is on: hits are tracked and the check runs. */
    static boolean active() {
        return FxSettings.unstick || FxSettings.standoffSeconds > 0;
    }

    /** A battle hit by {@code attacker} (FightDamageSystem / PlayerBattleDamageSystem): it is fighting. */
    static void hit(Ref<EntityStore> attacker) {
        if (active()) LAST_HIT.put(attacker, System.currentTimeMillis());
    }

    /** A battle mob took a hit from {@code attacker}: fighting, if the attacker is on its level (see check). */
    static void hurt(Ref<EntityStore> victim, Ref<EntityStore> attacker) {
        if (active() && attacker != null && attacker != victim) {
            LAST_HURT.put(victim, new Hurt(attacker, System.currentTimeMillis()));
        }
    }

    static void tick(Store<EntityStore> store) {
        // Queued with world.execute: the world may have closed since.
        if (!active() || store.isShutdown()) return;
        long now = System.currentTimeMillis();
        for (BattleManager.Battle battle : BattleManager.battles()) {
            if (battle.over || battle.store != store) continue;
            for (Ref<EntityStore> mob : battle.members()) {
                if (BattleManager.isPlayerFighter(mob) || !BattleManager.isAlive(mob, store)) continue;
                Ref<EntityStore> target = battle.targets.get(mob);
                if (target == null || !battle.has(target) || !BattleManager.isAlive(target, store)) {
                    TRACKS.remove(mob);
                    continue;
                }
                check(mob, target, now, store);
            }
        }
    }

    private static void check(Ref<EntityStore> mob, Ref<EntityStore> target, long now, Store<EntityStore> store) {
        Vector3d me = ChaosWand.positionOf(mob, store), them = ChaosWand.positionOf(target, store);
        if (me == null || them == null) return;
        double dist = me.distance(them);
        Track t = TRACKS.computeIfAbsent(mob, k -> new Track());
        boolean flier = canFly(mob, store);
        boolean newTarget = t.target != target;
        if (t.target == null || !newTarget && dist < t.best - PROGRESS || dist < MELEE
                || now - LAST_HIT.getOrDefault(mob, 0L) < STUCK_MS || hurtOnItsLevel(mob, me, now, store)
                || !flier && flyingNow(target, store)) {
            // First look, getting closer, in melee range, fighting, or after a flier it can't follow: not stuck.
            t.target = target;
            t.best = dist;
            t.since = now;
            t.shoves = 0;
            return;
        }
        if (newTarget) {
            // Another opponent keeps the clock (2026-10-29, playtest video: a dummy stranded on a wooden tower was never
            // brought down; the chase limit gave it a new target every 8 s, and each one restarted the 10 s count).
            t.target = target;
            t.best = dist;
        }
        // After STANDOFF_SHOVES shoves that changed nothing (2026-10-29, playtest video: daggers dummies 5 blocks apart on
        // both sides of the stands' low wall were shoved every 15 s for a minute, and the battle waited for them), the
        // stand-off is treated as stuck: moved next to the opponent below.
        // With unstick off nothing would move it after that, so the shoves just go on (audit 2026-10-29).
        if (!flier && Math.abs(them.y - me.y) <= MIN_HEIGHT_GAP && (t.shoves < STANDOFF_SHOVES || !FxSettings.unstick)) {
            // Same level and still apart: a stand-off. Shove it toward the opponent; again after standoffTime if needed.
            long standoffMs = FxSettings.standoffSeconds * 1000L;
            if (standoffMs <= 0 || now - t.since < standoffMs) return;
            if (dist > STANDOFF_MAX_GAP) {
                // Far behind a crowd: ChaseSupport walks it in; a shove from 40+ blocks was a long flying leap (log, 2026-10-13).
                t.since = now;
                t.best = dist;
                return;
            }
            long stood = now - t.since;
            t.since = now;
            t.best = dist;
            if (!FightManager.canAttack(mob, store)) return;
            t.shoves++;
            if (shove(mob, target, me, them, dist, store)) {
                MobBattlePlugin.debug("unstick: " + ChaosWand.nameOf(mob, store) + " stood off from " + ChaosWand.nameOf(target, store)
                        + " (" + String.format(java.util.Locale.ROOT, "%.1f", dist) + " blocks, no hit) for " + stood / 1000
                        + " s: shoved toward it");
            }
            return;
        }
        if (!FxSettings.unstick || now - t.since < (flier ? FLY_STUCK_MS : STUCK_MS)) return;
        // Medium (2026-10-14): never next to a target it can't reach (a fish on land, a walker after a fish in deep
        // water), and only where it can live: water for a fish, dry land for a walker that drowns.
        Medium.Can can = Medium.of(mob, store);
        if (!Medium.reaches(can, Medium.where(target, store))) {
            t.since = now;
            return;
        }
        FightFx.Spot spot = Medium.spotFor(can);
        // Stuck: next to the opponent, at its height.
        double angle = Math.atan2(me.z - them.z, me.x - them.x);
        // Room around the spot first (a wide mob next to a wall stood inside it, 2026-10-14); a tight one if nothing else.
        for (int attempt = 0; attempt < 12; attempt++) {
            double a = angle + (attempt % 6) * Math.PI / 3, r = 2 + attempt % 3;
            double x = them.x + Math.cos(a) * r, z = them.z + Math.sin(a) * r;
            double y = FightFx.standableY(x, z, them.y, STAND_RANGE, spot, store);
            if (!Double.isFinite(y)) continue;
            if (attempt < 6 && spot != FightFx.Spot.WATER && !FightFx.roomy(x, y, z, store)) continue;
            Vector3d to = new Vector3d(x, y, z);
            float yaw = (float) Math.atan2(them.x - x, them.z - z);
            // Already moved this tick (the arena wall): it stays tracked and tries again later.
            if (!FightFx.teleport(mob, to, new Rotation3f(0f, yaw, 0f), store)) return;
            t.shoves = 0;
            WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
            MobBattlePlugin.debug("unstick: " + ChaosWand.nameOf(mob, store) + (flier ? " (flier)" : "") + " could not reach "
                    + ChaosWand.nameOf(target, store) + " (" + Math.round(Math.abs(them.y - me.y)) + " blocks up/down) for "
                    + (now - t.since) / 1000 + " s: moved next to it" + String.format(java.util.Locale.ROOT, " (%.1f %.1f %.1f -> %.1f %.1f %.1f)",
                    me.x, me.y, me.z, to.x, to.y, to.z));
            break;
        }
        TRACKS.remove(mob);
    }

    /**
     * Stand-off shove toward {@code them}, through the role's active motion controller (not scaled by the role's
     * KnockbackScale, see the class comment). False if the mob has no role or motion controller.
     */
    /**
     * A steady walk toward the opponent instead of one hard shove (2026-10-29, playtest video: the stand-off shoves, 5-14
     * blocks/s with a hop, looked like blows): NUDGE_STEPS small pushes at walking speed, NUDGE_EVERY_MS apart on the
     * world thread, each re-aimed at where the opponent is, stopping once in melee reach. A low hop only when the last
     * push did not move it (a step or a slab in the way).
     */
    private static boolean shove(Ref<EntityStore> mob, Ref<EntityStore> target, Vector3d me, Vector3d them, double dist,
                                 Store<EntityStore> store) {
        Role role = roleOf(mob, store);
        if (role == null || role.getActiveMotionController() == null) return false;
        double[] last = {me.x, me.z};
        store.getExternalData().getWorld().scheduleAfter(() -> nudge(mob, target, store, last, 0), 0,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * One push of the walk, then the next one NUDGE_EVERY_MS later (one task at a time, audit 2026-10-29: twelve queued
     * at once per shove, thousands in a big royale). Stops when either side is dead, the battle no longer sets them
     * against each other (ended, a fallen player respawned) or the mob is in melee reach.
     */
    private static void nudge(Ref<EntityStore> mob, Ref<EntityStore> target, Store<EntityStore> store, double[] last, int step) {
        if (step >= NUDGE_STEPS || !mob.isValid() || !target.isValid() || store.isShutdown()) return;
        if (!BattleManager.isAlive(mob, store) || !BattleManager.isAlive(target, store)) return;
        BattleManager.Battle battle = BattleManager.battleOf(mob);
        if (battle == null || battle.over || !battle.has(target)) return;
        Vector3d a = ChaosWand.positionOf(mob, store), b = ChaosWand.positionOf(target, store);
        Role r = roleOf(mob, store);
        var c = r != null ? r.getActiveMotionController() : null;
        if (a == null || b == null || c == null) return;
        double dx = b.x - a.x, dz = b.z - a.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < MELEE * 0.6) return;
        boolean stuck = step > 0 && Math.hypot(a.x - last[0], a.z - last[1]) < 0.15;
        last[0] = a.x;
        last[1] = a.z;
        c.setVelocity(new Vector3d(dx / flat * NUDGE_SPEED, stuck ? NUDGE_HOP : 0, dz / flat * NUDGE_SPEED), null, false);
        try {
            store.getExternalData().getWorld().scheduleAfter(() -> nudge(mob, target, store, last, step + 1),
                    NUDGE_EVERY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // world stopping
        }
    }

    /** shove(): pushes, their spacing, walking speed (blocks/s) and the hop over a step. */
    private static final int NUDGE_STEPS = 12;
    private static final long NUDGE_EVERY_MS = 150;
    private static final double NUDGE_SPEED = 4.5, NUDGE_HOP = 2.2;

    /** True if something on the mob's level (or right next to it) hit it within STUCK_MS. */
    private static boolean hurtOnItsLevel(Ref<EntityStore> mob, Vector3d me, long now, ComponentAccessor<EntityStore> accessor) {
        Hurt h = LAST_HURT.get(mob);
        if (h == null || now - h.at() >= STUCK_MS) return false;
        Vector3d by = h.by().isValid() ? ChaosWand.positionOf(h.by(), accessor) : null;
        // Its attacker is gone (killed, despawned): it was in a real fight a moment ago.
        return by == null || Math.abs(by.y - me.y) <= MIN_HEIGHT_GAP || by.distance(me) <= MELEE;
    }

    /** True if the NPC's role can fly (has a "Fly" motion controller), even while it walks or perches. */
    static boolean canFly(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        Role role = roleOf(ref, accessor);
        return role != null && role.hasMotionController(MotionControllerFly.TYPE);
    }

    /** True if the NPC is flying right now (its active motion controller is the "Fly" one). */
    static boolean flyingNow(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        Role role = roleOf(ref, accessor);
        return role != null && role.getActiveMotionController() instanceof MotionControllerFly;
    }

    private static Role roleOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        NPCEntity npc = ref.isValid() ? accessor.getComponent(ref, NPCEntity.getComponentType()) : null;
        return npc != null ? npc.getRole() : null;
    }

    /** BattleManager.maintain: a Ref keeps its world reachable. */
    static void prune() {
        if (!TRACKS.isEmpty()) TRACKS.keySet().removeIf(r -> !r.isValid() || BattleManager.battleOf(r) == null);
        long now = System.currentTimeMillis();
        if (!LAST_HIT.isEmpty()) {
            LAST_HIT.entrySet().removeIf(e -> !e.getKey().isValid() || now - e.getValue() > STUCK_MS);
        }
        if (!LAST_HURT.isEmpty()) {
            // The attacker's Ref is held too: drop old entries even while the victim lives.
            LAST_HURT.entrySet().removeIf(e -> !e.getKey().isValid() || now - e.getValue().at() > STUCK_MS);
        }
    }

    /** /mobbattle unstick off and standoffTime 0: nothing is tracked any more. */
    static void clear() {
        TRACKS.clear();
        LAST_HIT.clear();
        LAST_HURT.clear();
    }
}
