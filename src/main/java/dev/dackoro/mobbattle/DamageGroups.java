package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A role's DisableDamageGroups while it fights in Mob Battle (2026-10-16, user: in a duel the Dungeon_Feastmaster_Boss
 * never hurt the Gobliterator; Debug Wand: it ran its Slam and Melee 3.3 blocks away and not one hit came out).
 * Vanilla Template_Dungeon_Feastmaster_Boss lists {@code "DisableDamageGroups": ["Goblin"]} so its slams spare its own
 * goblins, and CombatSupport.getCanCauseDamage (javap 0.7.2: disableDamageFlock, then the disableDamageGroups int[])
 * keeps every Goblin-group NPC out of its hits, the Gobliterator included. FightDamageSystem can only un-cancel a hit
 * that happened. While a mob is in a battle or wand fight its CombatSupport gets an empty array instead (its own field,
 * by reflection; the original is kept and put back once it no longer fights); the battle rules (friendly fire,
 * isolation) still apply in our damage systems. Keyed weakly by the CombatSupport: a role change or removal drops it.
 */
final class DamageGroups {

    private static final int[] NONE = new int[0];
    private static final Map<CombatSupport, int[]> SAVED = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Field field;
    private static volatile boolean broken;

    private DamageGroups() {
    }

    /** Anything opened right now (FightTickSystem skips the restore check for every NPC otherwise). */
    static boolean any() {
        return !SAVED.isEmpty();
    }

    /** {@code self} fights: its role may damage every group for now (world thread, once a second). */
    static void open(Ref<EntityStore> self, ComponentAccessor<EntityStore> acc) {
        if (broken) return;
        CombatSupport combat = CombatSupport.get(self, acc);
        if (combat == null || SAVED.containsKey(combat)) return;
        int[] groups = combat.getDisableDamageGroups();
        if (groups == null || groups.length == 0) return;
        if (set(combat, NONE)) {
            SAVED.put(combat, groups);
            if (MobBattlePlugin.debugging()) {
                MobBattlePlugin.debug("battle: " + ChaosWand.nameOf(self, acc) + " may now hurt the " + groups.length
                        + " group(s) its role never damages (DisableDamageGroups)");
            }
        }
    }

    /** {@code self} no longer fights: its role's own DisableDamageGroups again. */
    static void restore(Ref<EntityStore> self, ComponentAccessor<EntityStore> acc) {
        CombatSupport combat = CombatSupport.get(self, acc);
        if (combat == null) return;
        int[] groups = SAVED.remove(combat);
        if (groups != null) set(combat, groups);
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
