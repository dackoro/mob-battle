package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.lang.reflect.Method;

/**
 * MajorDungeons bosses in Mob Battle fights (2026-10-16, user: a duel of two Barons ended at once). MajorDungeons'
 * BossArenaSystem (javap, 0.4.14) resets a boss whose arena has had no player in it for the boss's ResetAfterSeconds
 * (Baron: 10 s): it removes the boss and spawns a new one at its leash point, which Mob Battle counted as a death. It
 * starts that clock when BossComponent.arenaEmptySinceMs is 0 and resets once now - arenaEmptySinceMs passes the
 * limit, so a boss that is fighting for Mob Battle (or waiting in its countdown) gets the clock set to now once a
 * second and never reaches it. Read by reflection through MajorDungeons' own class loader: no compile dependency,
 * nothing happens without the mod.
 */
final class MajorDungeonsCompat {

    private static final String BOSS_COMPONENT = "com.danbagh.dungeonframework.features.boss.BossComponent";

    private static volatile boolean looked;
    private static volatile ComponentType<EntityStore, ?> type;
    private static volatile Method setEmptySince;

    private MajorDungeonsCompat() {
    }

    /** {@code mob} (world thread) keeps its MajorDungeons arena from resetting it; no-op for any other mob. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void keepBoss(Ref<EntityStore> mob, ComponentAccessor<EntityStore> acc) {
        // the lock only on the first call: this runs for every battle mob every second, on every world thread
        if (!looked) ready();
        if (type == null || mob == null || !mob.isValid()) return;
        try {
            Object boss = acc.getComponent(mob, (ComponentType) type);
            if (boss != null) setEmptySince.invoke(boss, System.currentTimeMillis());
        } catch (ReflectiveOperationException | RuntimeException e) {
            MobBattlePlugin.log("MajorDungeons compat off: " + e);
            type = null;
        }
    }

    private static synchronized boolean ready() {
        if (looked) return type != null;
        looked = true;
        for (PluginBase plugin : PluginManager.get().getPlugins()) {
            try {
                Class<?> c = Class.forName(BOSS_COMPONENT, false, plugin.getClass().getClassLoader());
                Object t = c.getMethod("getComponentType").invoke(null);
                if (!(t instanceof ComponentType<?, ?> ct)) continue;
                @SuppressWarnings("unchecked")
                ComponentType<EntityStore, ?> found = (ComponentType<EntityStore, ?>) ct;
                setEmptySince = c.getMethod("setArenaEmptySinceMs", long.class);
                type = found;
                MobBattlePlugin.log("MajorDungeons found: its bosses do not reset while they fight in Mob Battle.");
                return true;
            } catch (ClassNotFoundException | LinkageError e) {
                // Not this plugin's class.
            } catch (ReflectiveOperationException | RuntimeException e) {
                MobBattlePlugin.log("MajorDungeons compat off: " + e);
                return false;
            }
        }
        return false;
    }
}
