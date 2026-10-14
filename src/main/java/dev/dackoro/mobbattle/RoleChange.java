package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.Role;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A role change re-creates the NPC: vanilla RoleChangeSystem removes the entity (RemoveReason.UNLOAD), swaps the role
 * on its holder and adds it back (AddReason.LOAD), so the same mob (same UUID, effects, stats, scale, light) comes back
 * under a new Ref. 0.7.0 bosses change role between stages (the encounter manager's ChangeTargetRole), so without this
 * a boss fight ended as "abandoned" at the first stage and the boss lost its team, buffs and army (javap, 2026-10-12).
 * BattleUnloadSystem hands the leaving Ref here and carries everything over when the mob comes back.
 */
final class RoleChange {

    private record Leaving(Ref<EntityStore> ref, long at) {
    }

    /** Mob UUID -> its Ref that just left for a role change. Both steps run in one tick; pruned after PENDING_MS. */
    private static final Map<UUID, Leaving> PENDING = new ConcurrentHashMap<>();
    private static final long PENDING_MS = 5000;

    private RoleChange() {
    }

    /** UNLOAD of an NPC: true (and remembered) when it is a role change, not a chunk unload. */
    static boolean leaving(Ref<EntityStore> ref, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
        Role role = npc != null ? npc.getRole() : null;
        if (role == null || !role.isRoleChangeRequested()) return false;
        UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
        if (id == null) return false;
        PENDING.put(id.getUuid(), new Leaving(ref, System.currentTimeMillis()));
        return true;
    }

    /** LOAD of an NPC: if it is the same mob back from a role change, everything Mob Battle kept on it follows. */
    static void arrived(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (PENDING.isEmpty()) return;
        UUIDComponent id = store.getComponent(ref, UUIDComponent.getComponentType());
        Leaving left = id != null ? PENDING.remove(id.getUuid()) : null;
        if (left == null || left.ref() == ref) return;
        Ref<EntityStore> old = left.ref();
        BattleManager.carry(old, ref);
        Buffs.carry(old, ref);
        Army.carry(old, ref, store);
        LookLight.carry(old, ref);
        SummonDespawn.carry(old, ref);
        ChaseSupport.carry(old, ref);
        MorphWand.carry(old, ref);
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("role change: " + ChaosWand.nameOf(ref, store) + " carried over");
    }

    /** BattleManager.maintain (1/s): a Ref keeps its whole world alive. */
    /** True while the entity with this UUID is between its old and new role (Army keeps a leader meanwhile). */
    static boolean pending(UUID id) {
        return id != null && PENDING.containsKey(id);
    }

    static void prune() {
        if (PENDING.isEmpty()) return;
        long now = System.currentTimeMillis();
        PENDING.values().removeIf(l -> now - l.at() > PENDING_MS);
    }
}
