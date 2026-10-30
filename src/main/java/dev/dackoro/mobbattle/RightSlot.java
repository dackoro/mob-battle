package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The card spot on the right of the screen (2026-10-16, playtest screenshot: a duel's card and a team battle's scoreboard
 * drawn over each other when both ran at once): the scoreboard, the duel card and the focus card all sit there, so each
 * player gets the one of the battle nearest to them. A battle counts when it shows something in that spot (its
 * scoreboard, or the duel / focus card) and the player is one of its title viewers. Worked out once per second per
 * world and kept until the next second, so the three HUD ticks of that second agree.
 */
final class RightSlot {

    private RightSlot() {
    }

    /** Members looked at per battle for its distance (the nearest of a sample is close enough). */
    private static final int SAMPLE = 32;
    private static final long FRESH_MS = 900;

    private static final Map<Store<EntityStore>, Pass> PASSES = new java.util.WeakHashMap<>();

    private record Pass(long at, Map<UUID, UUID> owner) {
    }

    /** Whether {@code battle} owns the right-hand spot for {@code viewer} (the nearest battle that uses it). */
    static boolean owns(PlayerRef viewer, BattleManager.Battle battle, Store<EntityStore> store) {
        UUID id = pass(store).owner().get(viewer.getUuid());
        return id != null && id.equals(battle.id);
    }

    /** Whether some running battle already uses the spot for {@code viewer} (a duel still counting down then waits). */
    static boolean taken(PlayerRef viewer, Store<EntityStore> store) {
        return pass(store).owner().containsKey(viewer.getUuid());
    }

    /** A battle that puts something in the spot: its scoreboard, or the duel / focus card instead of it. */
    static boolean usesSlot(BattleManager.Battle b) {
        // (a duel card only shows for a 1v1 duel, which cardShown covers; an accumulated duel shows none)
        return Scoreboard.cardShown(b) || FxSettings.scoreboard(b.kind);
    }

    private static synchronized Pass pass(Store<EntityStore> store) {
        long now = System.currentTimeMillis();
        Pass p = PASSES.get(store);
        if (p != null && now - p.at() < FRESH_MS) return p;
        Map<UUID, UUID> owner = new HashMap<>();
        Map<UUID, Double> best = new HashMap<>();
        for (BattleManager.Battle b : BattleManager.battles()) {
            if (b.over || b.store != store || !usesSlot(b)) continue;
            java.util.List<Vector3d> spots = new java.util.ArrayList<>();
            int n = 0;
            for (Ref<EntityStore> m : b.members()) {
                if (n++ >= SAMPLE) break;
                Vector3d at = ChaosWand.positionOf(m, store);
                if (at != null) spots.add(at);
            }
            for (PlayerRef v : FightFx.titleViewers(b)) {
                if (v == null || !v.isValid()) continue;
                // the owner is always a title viewer, even from another world: reading their entity through this store
                // threw every second and stopped every card of the world (audit 2026-10-17)
                Ref<EntityStore> vr = v.getReference();
                if (vr == null || !vr.isValid() || vr.getStore() != store) continue;
                Vector3d me = ChaosWand.positionOf(vr, store);
                double d = Double.MAX_VALUE;
                if (me != null) for (Vector3d s : spots) d = Math.min(d, s.distanceSquared(me));
                Double was = best.get(v.getUuid());
                if (was == null || d < was) {
                    best.put(v.getUuid(), d);
                    owner.put(v.getUuid(), b.id);
                }
            }
        }
        p = new Pass(now, owner);
        PASSES.put(store, p);
        return p;
    }
}
