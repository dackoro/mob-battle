package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A role's DisableDamageGroups while it fights in Mob Battle (2026-10-16, user: in a duel the Dungeon_Feastmaster_Boss
 * never hurt the Gobliterator; Debug Wand: it ran its Slam and Melee 3.3 blocks away and not one hit came out).
 * Vanilla Template_Dungeon_Feastmaster_Boss lists {@code "DisableDamageGroups": ["Goblin"]} so its slams spare its own
 * goblins, and CombatSupport.getCanCauseDamage (javap 0.7.2: disableDamageFlock, then the disableDamageGroups int[])
 * keeps every Goblin-group NPC out of its hits, the Gobliterator included. FightDamageSystem can only un-cancel a hit
 * that happened. While a mob is in a battle or wand fight its CombatSupport gets an empty array instead (its own field,
 * by reflection; the original is kept and put back once it no longer fights); the battle rules (friendly fire,
 * isolation) still apply in our damage systems.
 */
final class DamageGroups {

    private static final int[] NONE = new int[0];

    /** The role's own groups, per mob that fights (its CombatSupport kept to know a role change built a new one). */
    private record Saved(CombatSupport combat, int[] groups) {
    }

    /**
     * Keyed by the mob's Ref (lock-free lookups: every NPC of every world asks on every tick while one is open; the old
     * synchronized weak map made them queue on one lock, audit 2026-10-17). Pruned once a second (BattleManager.maintain).
     */
    private static final Map<Ref<EntityStore>, Saved> SAVED = new ConcurrentHashMap<>();

    private static volatile Field field;
    private static volatile boolean broken;

    private DamageGroups() {
    }

    /** Anything opened right now (FightTickSystem stays awake to restore them, and skips the check otherwise). */
    static boolean any() {
        return !SAVED.isEmpty();
    }

    /** {@code self} fights: its role may damage every group for now (world thread, once a second). */
    static void open(Ref<EntityStore> self, ComponentAccessor<EntityStore> acc) {
        if (broken || SAVED.containsKey(self)) return;
        CombatSupport combat = CombatSupport.get(self, acc);
        if (combat == null) return;
        int[] groups = combat.getDisableDamageGroups();
        if (groups == null || groups.length == 0) return;
        if (set(combat, NONE)) {
            SAVED.put(self, new Saved(combat, groups));
            if (MobBattlePlugin.debugging()) {
                MobBattlePlugin.debug("battle: " + ChaosWand.nameOf(self, acc) + " may now hurt the " + groups.length
                        + " group(s) its role never damages (DisableDamageGroups)");
            }
        }
    }

    /** {@code self} no longer fights: its role's own DisableDamageGroups again. Nothing to do for any other mob. */
    static void restore(Ref<EntityStore> self, ComponentAccessor<EntityStore> acc) {
        Saved s = SAVED.remove(self);
        if (s == null) return;
        // a role change rebuilt its CombatSupport (the new one has its own groups): nothing to put back
        if (CombatSupport.get(self, acc) == s.combat()) set(s.combat(), s.groups());
    }

    /** BattleManager.maintain: forgets mobs that are gone (a Ref holds its whole world). */
    static void prune() {
        if (!SAVED.isEmpty()) SAVED.keySet().removeIf(r -> !r.isValid());
    }

    private static boolean set(CombatSupport combat, int[] groups) {
        try {
            Field f = field;
            if (f == null) {
                f = CombatSupport.class.getDeclaredField("disableDamageGroups");
                f.setAccessible(true);
                field = f;
            }
            f.set(combat, groups);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            MobBattlePlugin.log("DamageGroups off (roles keep their DisableDamageGroups in battles): " + e);
            return false;
        }
    }
}
