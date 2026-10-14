package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes every client drop an entity and take it again from scratch, the way it does when the entity leaves view range
 * and comes back. Used when a copied PlayerSkin leaves a mob: the client can't remove a PlayerSkin component (it
 * crashes, "Removing PlayerSkin component isn't supported!", user 2026-10-13, both players in view), and vanilla never
 * sends one, so without a fresh spawn the client keeps drawing the skin.
 * <p>
 * Same place as vanilla {@code EntityTrackerSystems.HideFromPlayer} (FIND_VISIBLE_ENTITIES_GROUP, after
 * CollectVisible): the entity is taken out of each viewer's visible set for {@link #HIDE_MS}. SendPackets then sends it
 * as removed (and drops its queued updates); once it is visible again it is newly visible, so the full init goes out
 * without the skin. LookKeeper sees the viewer come back and re-sends a look. Cost with nothing pending: one map check
 * per viewer per tick.
 */
public final class SkinRespawnSystem extends EntityTickingSystem<EntityStore> {

    /** Long enough for at least one tracker pass to send the removal. */
    private static final long HIDE_MS = 150;

    /**
     * Entity -> {hide until (0 = not started), when it was asked}. The window starts at the first pass of the entity's
     * own world, so a slow tick can't let it run out before any removal was sent (audit 2026-10-14). Pruned in
     * {@link #prune}.
     */
    private static final Map<Ref<EntityStore>, long[]> HIDDEN = new ConcurrentHashMap<>();
    /** An entry no pass of its world ever started (no viewer there) is dropped after this. */
    private static final long MAX_WAIT_MS = 10_000;

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));

    /** Every client that sees {@code entity} gets it again from scratch. */
    static void respawn(Ref<EntityStore> entity) {
        HIDDEN.put(entity, new long[] {0, System.currentTimeMillis()});
    }

    /** From BattleManager.maintain: drops finished and invalid entries. */
    static void prune() {
        long now = System.currentTimeMillis();
        HIDDEN.entrySet().removeIf(e -> !e.getKey().isValid() || e.getValue()[0] != 0 && e.getValue()[0] < now
                || now - e.getValue()[1] > MAX_WAIT_MS);
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return EntityTrackerSystems.EntityViewer.getComponentType();
    }

    @Override
    public void tick(float delta, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> buffer) {
        if (HIDDEN.isEmpty()) return;
        EntityTrackerSystems.EntityViewer viewer = chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
        if (viewer == null || viewer.visible == null) return;
        Ref<EntityStore> self = chunk.getReferenceTo(index);
        long now = System.currentTimeMillis();
        for (Map.Entry<Ref<EntityStore>, long[]> e : HIDDEN.entrySet()) {
            if (e.getKey().getStore() != store) continue;
            long[] u = e.getValue();
            if (u[0] == 0) u[0] = now + HIDE_MS;
            // Never a player's own entity: their client would lose itself.
            if (u[0] >= now && !e.getKey().equals(self)) viewer.visible.remove(e.getKey());
        }
    }
}
