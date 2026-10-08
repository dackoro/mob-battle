package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * About once a second per NPC:
 * <ul>
 *   <li>group battles: if its target died, pick the nearest living enemy; check for a winner;</li>
 *   <li>1v1 fights: re-lock it onto its wand opponent if its own AI switched to something else;</li>
 *   <li>Team Wand: draw the team color ring under it for the player holding the wand.</li>
 * </ul>
 */
public class FightTickSystem extends EntityTickingSystem<EntityStore> {

    private static final long CHECK_INTERVAL_MS = 1000;
    private static final long ISOLATION_INTERVAL_MS = 5000;
    private final Map<Ref<EntityStore>, Long> lastCheck = new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>, Long> lastRing = new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>, Long> lastJoin = new ConcurrentHashMap<>();
    private final Map<Ref<EntityStore>, Long> lastIsolation = new ConcurrentHashMap<>();
    /** Timers are keyed by entity reference, so removed mobs must be dropped or the maps keep growing. */
    private static final long PRUNE_INTERVAL_MS = 10_000;
    private volatile long lastPrune;

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void tick(float delta, int entityIndex, ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        boolean drafts = !BattleManager.drafts().isEmpty();
        if (!FightManager.hasFights() && !FightManager.hasPendingHeals() && !BattleManager.hasBattles() && !drafts
                && !TeamFx.hasPending() && !FightManager.hasPendingStops()) {
            return;
        }
        Ref<EntityStore> self = chunk.getReferenceTo(entityIndex);
        pruneTimers();
        FightManager.applyPendingStop(self, buffer);
        if (FightManager.hasPendingHeals()) {
            FightManager.applyPendingHeals(self, buffer);
        }
        if (TeamFx.hasPending()) {
            TeamFx.apply(self, buffer);
        }
        long now = System.currentTimeMillis();

        BattleManager.Battle battle = BattleManager.hasBattles() ? BattleManager.battleOf(self) : null;
        if ((battle != null || BattleManager.isManaged(self)) && due(lastIsolation, self, now, ISOLATION_INTERVAL_MS)) {
            FightManager.refreshBattleIsolation(self, battle, buffer);
        }
        if (battle == null && BattleManager.hasBattles() && FightManager.enemyOf(self) == null
                && due(lastJoin, self, now, CHECK_INTERVAL_MS)) {
            // Pets/summons spawned mid-battle (e.g. the Trork Hunter's wolf) join their owner's side.
            battle = BattleManager.joinOwnersSide(self, buffer);
        }
        if ((drafts || (battle != null && battle.isTeams())) && due(lastRing, self, now, FightFx.TEAM_RING_REFRESH_MS)) {
            drawTeamRing(self, battle, buffer);
        }

        if (battle != null) {
            if (due(lastCheck, self, now, CHECK_INTERVAL_MS)) {
                tickBattle(battle, self, buffer);
            }
            return;
        }

        Ref<EntityStore> enemy = FightManager.enemyOf(self);
        if (enemy == null || !due(lastCheck, self, now, CHECK_INTERVAL_MS)) {
            return;
        }
        relock(self, enemy, buffer);
    }

    private void pruneTimers() {
        long now = System.currentTimeMillis();
        if (now - lastPrune < PRUNE_INTERVAL_MS) {
            return;
        }
        lastPrune = now;
        for (Map<Ref<EntityStore>, Long> times : List.of(lastCheck, lastRing, lastJoin, lastIsolation)) {
            times.keySet().removeIf(ref -> !ref.isValid());
        }
    }

    private static boolean due(Map<Ref<EntityStore>, Long> times, Ref<EntityStore> self, long now, long interval) {
        Long last = times.get(self);
        if (last != null && now - last < interval) {
            return false;
        }
        times.put(self, now);
        return true;
    }

    private static void relock(Ref<EntityStore> self, Ref<EntityStore> enemy, CommandBuffer<EntityStore> buffer) {
        MarkedEntitySupport marked = MarkedEntitySupport.get(self, buffer);
        Ref<EntityStore> current = marked != null ? marked.getMarkedEntityRef(FightManager.TARGET_SLOT) : null;
        if (current == null || !current.isValid() || current.getStore() != enemy.getStore() || current.getIndex() != enemy.getIndex()) {
            FightManager.turnAgainst(self, enemy, buffer);
        } else {
            // Keep the enemy fresh in the hostile target memory (it expires after RememberFor).
            FightManager.rememberAsHostile(self, enemy, buffer);
        }
    }

    /** Winners and the timeout are checked once per second per world by BattleLifecycleSystem. */
    private static void tickBattle(BattleManager.Battle battle, Ref<EntityStore> self, CommandBuffer<EntityStore> buffer) {
        if (battle.over || !BattleManager.isAlive(self, buffer)) {
            return;
        }
        boolean[] changed = {false};
        Ref<EntityStore> target = BattleManager.currentTarget(battle, self, buffer, changed);
        if (target == null) {
            return;
        }
        if (changed[0]) {
            // Previous target died: go for the next one.
            FightManager.turnAgainst(self, target, buffer);
            FightManager.provoke(self, target, buffer);
        } else {
            relock(self, target, buffer);
        }
    }

    /** Team color ring under this NPC, only for the player who owns the draft/battle and holds the Team Wand. */
    private static void drawTeamRing(Ref<EntityStore> self, BattleManager.Battle battle, CommandBuffer<EntityStore> buffer) {
        if (battle != null && battle.isTeams()) {
            PlayerRef owner = battle.owner();
            int team = battle.teamOf.getOrDefault(self.getIndex(), -1);
            if (team >= 0 && FightFx.holdsTeamWand(owner, buffer)) {
                FightFx.teamRing(owner, self, battle.color(team), buffer);
            }
            return;
        }
        for (Map.Entry<UUID, BattleManager.Draft> e : BattleManager.drafts().entrySet()) {
            int team = e.getValue().teamOf(self);
            if (team < 0) {
                continue;
            }
            PlayerRef owner = BattleManager.draftOwner(e.getKey());
            if (FightFx.holdsTeamWand(owner, buffer)) {
                FightFx.teamRing(owner, self, Teams.color(team), buffer);
            }
        }
    }
}
