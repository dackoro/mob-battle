package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * debugLog only: follows every arrow (old-style ProjectileComponent) shot by a battle fighter and logs how close it
 * came to the enemy nearest to its line, inside that enemy's hitbox or not, and whether the game counted a hit
 * (2026-10-29, playtest video: archer arrows went through a dummy's chest with no damage event at all).
 */
public class ArrowDebugSystem extends EntityTickingSystem<EntityStore> {

    /** Per arrow: shooter, the enemy it was aimed at (nearest at launch), closest distance seen, logged yet. */
    private static final class Track {
        Ref<EntityStore> shooter, target;
        String shooterName, targetName;
        double best = Double.MAX_VALUE;
        boolean inside, logged;
        Vector3d bestAt, prev;
    }

    private static final Map<Ref<EntityStore>, Track> TRACKS = new WeakHashMap<>();

    @Override
    public Query<EntityStore> getQuery() {
        return ProjectileComponent.getComponentType();
    }

    @Override
    public void tick(float delta, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                     CommandBuffer<EntityStore> buffer) {
        if (!MobBattlePlugin.debugging() || !BattleManager.hasBattles()) {
            // left alone, its tracks would keep closed worlds' Refs (audit 2026-10-29)
            synchronized (TRACKS) {
                if (!TRACKS.isEmpty()) TRACKS.clear();
            }
            return;
        }
        Ref<EntityStore> arrow = chunk.getReferenceTo(index);
        ProjectileComponent p = chunk.getComponent(index, ProjectileComponent.getComponentType());
        TransformComponent t = chunk.getComponent(index, TransformComponent.getComponentType());
        if (p == null || t == null || p.getCreatorUuid() == null) return;
        Track tr;
        synchronized (TRACKS) {
            tr = TRACKS.get(arrow);
            if (tr == null) {
                Ref<EntityStore> shooter = store.getExternalData().getRefFromUUID(p.getCreatorUuid());
                BattleManager.Battle battle = shooter != null ? BattleManager.battleOf(shooter) : null;
                tr = new Track();
                if (battle != null) {
                    tr.shooter = shooter;
                    tr.target = BattleManager.nearestEnemy(battle, shooter, store);
                    tr.shooterName = ChaosWand.nameOf(shooter, store);
                    tr.targetName = tr.target != null ? ChaosWand.nameOf(tr.target, store) : "-";
                } else {
                    tr.logged = true;   // not a battle arrow: never looked at again
                }
                TRACKS.put(arrow, tr);
            }
        }
        if (tr.logged || tr.target == null || !tr.target.isValid()) return;
        Vector3d at = t.getPosition();
        TransformComponent tt = store.getComponent(tr.target, TransformComponent.getComponentType());
        BoundingBox bb = store.getComponent(tr.target, BoundingBox.getComponentType());
        if (tt == null) return;
        Vector3d c = tt.getPosition();
        // an arrow moves ~2.5 blocks a tick: the closest point is on the segment from the last sample, not a sample
        Vector3d q = new Vector3d(at.x, at.y, at.z);
        if (tr.prev != null) {
            double sx = at.x - tr.prev.x, sz = at.z - tr.prev.z, len = sx * sx + sz * sz;
            double u = len > 1e-9 ? ((c.x - tr.prev.x) * sx + (c.z - tr.prev.z) * sz) / len : 1;
            u = Math.max(0, Math.min(1, u));
            q = new Vector3d(tr.prev.x + u * sx, tr.prev.y + u * (at.y - tr.prev.y), tr.prev.z + u * sz);
        }
        tr.prev = new Vector3d(at.x, at.y, at.z);
        double d = Math.sqrt((q.x - c.x) * (q.x - c.x) + (q.z - c.z) * (q.z - c.z));
        if (d < tr.best) {
            tr.best = d;
            tr.bestAt = new Vector3d(q.x - c.x, q.y - c.y, q.z - c.z);
            if (bb != null && bb.getBoundingBox() != null) {
                var box = bb.getBoundingBox();
                double rx = q.x - c.x, ry = q.y - c.y, rz = q.z - c.z;
                tr.inside = rx >= box.min.x - 0.1 && rx <= box.max.x + 0.1 && ry >= box.min.y - 0.1 && ry <= box.max.y + 0.1
                        && rz >= box.min.z - 0.1 && rz <= box.max.z + 0.1;
            }
        }
        if (d <= tr.best + 1e-6 && !p.isOnGround()) return;
        // moving away again (or the arrow stopped near it): report the closest pass once
        if (d > tr.best + 0.5 || p.isOnGround()) {
            tr.logged = true;
            var box = bb != null ? bb.getBoundingBox() : null;
            MobBattlePlugin.debug(String.format(java.util.Locale.ROOT,
                    "arrow: %s -> %s closest %.2f blocks (offset %.2f %.2f %.2f, target box y %.2f..%.2f half %.2f) %s",
                    tr.shooterName, tr.targetName, tr.best, tr.bestAt.x, tr.bestAt.y, tr.bestAt.z,
                    box != null ? box.min.y : 0, box != null ? box.max.y : 0,
                    box != null ? (box.max.x - box.min.x) / 2 : 0,
                    tr.inside ? "INSIDE its hitbox" : "outside its hitbox"));
        }
    }
}
