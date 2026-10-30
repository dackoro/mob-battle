package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

/**
 * The training dummies' stats from the settings (2026-10-29, playtest: set their health, speed... from the panel). Each
 * MobBattle_Dummy_* that spawns gets dummyHealth / dummyDamage / dummyDefense / dummySpeed / dummySize as direct buffs
 * (Buffs.setDirect, as /mobbattle apply does), so Cleanse or unbuff take them back to the role's own. Dummies already
 * standing keep what they had. ❓ Untested in game.
 */
final class TrainingDummies {
    static final String ROLE_PREFIX = "MobBattle_Dummy_";

    private TrainingDummies() {
    }

    /** LookLoadSystem, an NPC spawned (inside the system callback: the buffs go on the world thread). */
    /**
     * An arrow or bolt of the archer or crossbow dummy hit something: +1 signature energy, as a player's shots give
     * (2026-10-29, playtest: the volley came within 4 s of the start). Their NPC arrows carry no EntityStatsOnHit, so
     * without this the energy never filled; their CAE wants it full (6 / 5) before the signature, then every ~15 s.
     * World thread (the damage systems).
     */
    static void signatureHit(Ref<EntityStore> shooter, com.hypixel.hytale.component.ComponentAccessor<EntityStore> acc) {
        if (shooter == null || !shooter.isValid()) return;
        NPCEntity npc = acc.getComponent(shooter, NPCEntity.getComponentType());
        String role = npc != null ? npc.getRoleName() : null;
        if (!"MobBattle_Dummy_Archer".equals(role) && !"MobBattle_Dummy_Crossbow".equals(role)) return;
        var stats = acc.getComponent(shooter, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap.getComponentType());
        int index = com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType.getAssetMap().getIndex("SignatureEnergy");
        if (stats != null && index >= 0 && stats.get(index) != null) stats.addStatValue(index, 1f);
    }

    static void onSpawn(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!changed()) return;
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        String role = npc != null ? npc.getRoleName() : null;
        if (role == null || !role.startsWith(ROLE_PREFIX)) return;
        store.getExternalData().getWorld().execute(() -> apply(ref));
    }

    /** Whether any setting differs from the role's own stats (else nothing to do). */
    private static boolean changed() {
        return FxSettings.dummyHealth != FxSettings.DUMMY_BASE_HEALTH || FxSettings.dummyDamage > 0
                || FxSettings.dummyDefense > 0 || FxSettings.dummySpeed > 0 || FxSettings.dummySize > 0;
    }

    private static void apply(Ref<EntityStore> ref) {
        if (!ref.isValid()) return;
        // health in hit points over the role's 100: 400 = +300 %; below 100 is not a buff (left as the role's)
        int health = FxSettings.dummyHealth * 100 / FxSettings.DUMMY_BASE_HEALTH - 100;
        if (health > 0) Buffs.setDirect(ref, Buffs.Stat.HEALTH, health);
        if (FxSettings.dummyDamage > 0) Buffs.setDirect(ref, Buffs.Stat.DAMAGE, FxSettings.dummyDamage);
        if (FxSettings.dummyDefense > 0) Buffs.setDirect(ref, Buffs.Stat.DEFENSE, FxSettings.dummyDefense);
        if (FxSettings.dummySpeed > 0) Buffs.setDirect(ref, Buffs.Stat.SPEED, FxSettings.dummySpeed);
        if (FxSettings.dummySize > 0) Buffs.setDirect(ref, Buffs.Stat.SIZE, FxSettings.dummySize);
    }
}
