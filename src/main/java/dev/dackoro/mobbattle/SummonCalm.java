package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calm summons (/mobbattle summonCalm, 2026-10-13, playtest: a Feastmaster and 100 skeletons summoned together fought
 * at once). A calm summon ignores the other calm summons of its world (mode summons) and also players and every other
 * mob around it (mode all), through short IGNORE attitude overrides renewed every REFRESH_MS, until:
 * <ul>
 *   <li>it joins a battle, a wand fight or an army (woken for good: the battle sets its own attitudes);</li>
 *   <li>something hits it: it may fight back that attacker only, and stays calm toward the rest.</li>
 * </ul>
 * Memory only: a summon unloaded and reloaded (new Ref), a role change or a restart ends its calm. Overrides are
 * short, so turning the setting off lets every calm summon go back to its own attitudes within CALM_SECONDS.
 */
final class SummonCalm {

    static final String OFF = "off";
    static final String SUMMONS = "summons";
    static final String ALL = "all";

    /** Each IGNORE lasts this long; the refresh renews it well before. */
    static final double CALM_SECONDS = 10;
    /** Refresh period per world (BattleLifecycleSystem). */
    static final long REFRESH_MS = 4000;
    /** Mode all: players and other mobs within this range are ignored too (as battle isolation's outsiders). */
    private static final double ALL_RANGE = 48;
    /** Calm summons ignore each other within this range (farther ones are past any mob's sight anyway). */
    private static final double CALM_RANGE = 48;
    /** An override this short expires on the mob's next tick: its own attitude toward that entity is back. */
    private static final double FORGET_SECONDS = 0.05;

    /** Calm summon -> the entities that hit it (it may fight them back). */
    private static final Map<Ref<EntityStore>, Set<Ref<EntityStore>>> CALM = new ConcurrentHashMap<>();

    private SummonCalm() {
    }

    static String parseMode(String value) {
        if (value == null) return null;
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case OFF, SUMMONS, ALL -> v;
            default -> null;
        };
    }

    static boolean any() {
        return !CALM.isEmpty();
    }

    /** The setting was turned off: every summon wakes (their last overrides expire within CALM_SECONDS). */
    static void clear() {
        CALM.clear();
    }

    /** A battle, wand fight or army owns the mob now: its attitudes are theirs. */
    /** Whether {@code mob} is in the calm set now (/mobbattle test stats / expect). */
    static boolean isCalm(Ref<EntityStore> mob) {
        return mob != null && CALM.containsKey(mob);
    }

    private static boolean busy(Ref<EntityStore> mob) {
        return BattleManager.isManaged(mob) || FightManager.isFighter(mob) || Army.ownerOf(mob) != null;
    }

    /**
     * A new summon (SummonList, world thread, right after its spawn): calm toward every calm summon of its world, and
     * they toward it, before its AI ticks once.
     */
    static void add(Ref<EntityStore> mob, Store<EntityStore> store) {
        if (OFF.equals(FxSettings.summonCalm) || mob == null || !mob.isValid()) return;
        CALM.put(mob, ConcurrentHashMap.newKeySet());
        WorldSupport mine = WorldSupport.get(mob, store);
        org.joml.Vector3d at = ChaosWand.positionOf(mob, store);
        for (Ref<EntityStore> other : CALM.keySet()) {
            if (other == mob || !other.isValid() || other.getStore() != store) continue;
            if (at != null && ChaosWand.distSq(at, other, store) > CALM_RANGE * CALM_RANGE) continue;
            if (mine != null) FightManager.ignore(mine, other, CALM_SECONDS);
            WorldSupport theirs = WorldSupport.get(other, store);
            if (theirs != null) FightManager.ignore(theirs, mob, CALM_SECONDS);
        }
        if (mine != null && ALL.equals(FxSettings.summonCalm)) ignoreAround(mob, mine, CALM.get(mob), store);
    }

    /**
     * {@code attacker} hit {@code victim} (FightDamageSystem, any NPC hit that was not cancelled): a calm victim drops
     * its calm toward that attacker only, so it can fight back.
     */
    static void hurt(Ref<EntityStore> victim, Ref<EntityStore> attacker, ComponentAccessor<EntityStore> accessor) {
        Set<Ref<EntityStore>> answers = victim != null ? CALM.get(victim) : null;
        if (answers == null || attacker == null || attacker == victim) return;
        // A battle, wand fight or army took it already (its first hits come before the 4 s tick drops it): it leaves
        // the calm set now instead of answering this one attacker (each hit logged a line at every battle start).
        if (busy(victim)) {
            CALM.remove(victim);
            return;
        }
        if (!answers.add(attacker)) return;
        WorldSupport world = WorldSupport.get(victim, accessor);
        if (world != null) FightManager.ignore(world, attacker, FORGET_SECONDS);
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("calm summon was hit and may fight back");
    }

    /** Renews the calm of this world's summons (BattleLifecycleSystem every REFRESH_MS, world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        if (OFF.equals(FxSettings.summonCalm)) {
            clear();
            return;
        }
        List<Ref<EntityStore>> here = new ArrayList<>();
        for (Ref<EntityStore> mob : CALM.keySet()) {
            if (!mob.isValid() || busy(mob)) {
                CALM.remove(mob);
            } else if (mob.getStore() == store) {
                here.add(mob);
            }
        }
        boolean all = ALL.equals(FxSettings.summonCalm);
        // Each calm summon only ignores the calm summons within CALM_RANGE, found through a grid of CALM_RANGE cells
        // (audit 2026-10-14: every calm summon of the world was O(n^2) overrides every 4 s, ~1M for 1000 summons).
        int n = here.size();
        double[] xs = new double[n], ys = new double[n], zs = new double[n];
        Map<Long, List<Integer>> grid = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            org.joml.Vector3d p = ChaosWand.positionOf(here.get(i), store);
            if (p == null) {
                xs[i] = Double.NaN;
                continue;
            }
            xs[i] = p.x;
            ys[i] = p.y;
            zs[i] = p.z;
            grid.computeIfAbsent(cell(p.x, p.y, p.z, 0, 0, 0), k -> new ArrayList<>()).add(i);
        }
        double r2 = CALM_RANGE * CALM_RANGE;
        for (int i = 0; i < n; i++) {
            Ref<EntityStore> mob = here.get(i);
            Set<Ref<EntityStore>> answers = CALM.get(mob);
            WorldSupport world = answers != null ? WorldSupport.get(mob, store) : null;
            if (world == null || Double.isNaN(xs[i])) continue;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> near = grid.get(cell(xs[i], ys[i], zs[i], dx, dy, dz));
                        if (near == null) continue;
                        for (int j : near) {
                            if (j == i) continue;
                            double ex = xs[j] - xs[i], ey = ys[j] - ys[i], ez = zs[j] - zs[i];
                            if (ex * ex + ey * ey + ez * ez > r2) continue;
                            Ref<EntityStore> other = here.get(j);
                            if (!answers.contains(other)) FightManager.ignore(world, other, CALM_SECONDS);
                        }
                    }
                }
            }
            if (all) ignoreAround(mob, world, answers, store);
        }
    }

    /** Grid cell key of a position moved by (dx, dy, dz) cells of CALM_RANGE. */
    private static long cell(double x, double y, double z, int dx, int dy, int dz) {
        long cx = (long) Math.floor(x / CALM_RANGE) + dx, cy = (long) Math.floor(y / CALM_RANGE) + dy,
                cz = (long) Math.floor(z / CALM_RANGE) + dz;
        return (cx & 0x1FFFFF) << 42 | (cy & 0x1FFFFF) << 21 | (cz & 0x1FFFFF);
    }

    /** Mode all: players and the other mobs near the summon (not the calm ones, done already, nor its attackers). */
    private static void ignoreAround(Ref<EntityStore> mob, WorldSupport world, Set<Ref<EntityStore>> answers,
                                     ComponentAccessor<EntityStore> accessor) {
        org.joml.Vector3d pos = ChaosWand.positionOf(mob, accessor);
        if (pos == null) return;
        // Thread-local list: iterated right away, and nothing in the loop runs another sphere query.
        for (Ref<EntityStore> other : com.hypixel.hytale.server.core.util.TargetUtil
                .getAllEntitiesInSphere(pos, ALL_RANGE, accessor)) {
            if (other == null || !other.isValid() || other == mob || CALM.containsKey(other)
                    || answers != null && answers.contains(other)) continue;
            // Only things a mob can target: skip arrows, dropped items and other entities.
            if (accessor.getComponent(other, NPCEntity.getComponentType()) == null
                    && accessor.getComponent(other, PlayerRef.getComponentType()) == null) continue;
            FightManager.ignore(world, other, CALM_SECONDS);
        }
    }

    /** Drops removed mobs and attackers (BattleManager.maintain, once per second): a Ref holds its whole world. */
    static void prune() {
        if (CALM.isEmpty()) return;
        CALM.keySet().removeIf(mob -> !mob.isValid() || mob.getStore().isShutdown());
        for (Set<Ref<EntityStore>> answers : CALM.values()) answers.removeIf(r -> !r.isValid());
    }
}
