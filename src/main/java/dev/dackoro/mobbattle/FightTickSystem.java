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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * About once a second per NPC:
 * <ul>
 *   <li>group battles: if its target died, pick the nearest living enemy (winners: BattleLifecycleSystem);</li>
 *   <li>1v1 fights: re-lock it onto its wand opponent if its own AI switched to something else;</li>
 *   <li>Team Wand: draw the team color ring under it for the player holding the wand.</li>
 * </ul>
 * This runs for every NPC of every world on every tick, so the common case (a wild NPC that is in no
 * battle, fight or draft) must stay a few set lookups: no list scans, no allocations, no timer entries.
 */
public class FightTickSystem extends EntityTickingSystem<EntityStore> {

    private static final long CHECK_INTERVAL_MS = 1000;
    private static final long ISOLATION_INTERVAL_MS = 5000;
    /** Timers are keyed by entity reference, so removed mobs must be dropped or the map keeps growing. */
    private static final long PRUNE_INTERVAL_MS = 10_000;

    /** Last run of each periodic job for one NPC. */
    private static final class Timers {
        long check, ring, join, isolation;
    }

    private final Map<Ref<EntityStore>, Timers> timers = new ConcurrentHashMap<>();
    private volatile long lastPrune;

    @Override
    public Query<EntityStore> getQuery() {
        return NPCEntity.getComponentType();
    }

    @Override
    public void tick(float delta, int entityIndex, ArchetypeChunk<EntityStore> chunk,
                     Store<EntityStore> store, CommandBuffer<EntityStore> buffer) {
        boolean battles = BattleManager.hasBattles();
        boolean drafts = BattleManager.hasDraftMembers();
        boolean stops = FightManager.hasPendingStops();
        boolean heals = FightManager.hasPendingHeals();
        boolean looks = TeamFx.hasPending();
        boolean kills = WandFx.hasPendingKills();
        boolean countdowns = Countdown.hasPending();
        boolean snaps = Snap.any();
        if (!battles && !drafts && !stops && !heals && !looks && !kills && !countdowns && !snaps && !FightManager.hasFights()) {
            return;
        }
        Ref<EntityStore> self = chunk.getReferenceTo(entityIndex);
        long now = System.currentTimeMillis();
        pruneTimers(now);
        if (stops) FightManager.applyPendingStop(self, buffer);
        if (heals) FightManager.applyPendingHeals(self, buffer);
        if (looks) TeamFx.apply(self, buffer);
        if (kills) WandFx.applyPendingKill(self, now, buffer);
        if (snaps) Snap.apply(self, now, buffer);
        if (countdowns) Countdown.runDue(store, now, buffer);

        // Every battle and draft member is managed: wild NPCs skip the battle lookups entirely.
        boolean managed = BattleManager.isManaged(self);
        BattleManager.Battle battle = managed && battles ? BattleManager.battleOf(self) : null;
        if (managed && due(self, now, ISOLATION_INTERVAL_MS, t -> t.isolation, (t, v) -> t.isolation = v)) {
            FightManager.refreshBattleIsolation(self, battle, buffer);
        }
        if (battles && !managed && BattleManager.hasFlocks() && !FightManager.isFighter(self)
                && due(self, now, CHECK_INTERVAL_MS, t -> t.join, (t, v) -> t.join = v)) {
            // Pets/summons spawned mid-battle (e.g. the Trork Hunter's wolf) join their owner's side.
            battle = BattleManager.joinOwnersSide(self, buffer);
        }
        boolean ring = battle != null ? battle.isTeams() : drafts && managed;
        if (ring && due(self, now, FightFx.TEAM_RING_REFRESH_MS, t -> t.ring, (t, v) -> t.ring = v)) {
            drawTeamRing(self, battle, buffer);
        }

        if (battle != null) {
            if (due(self, now, CHECK_INTERVAL_MS, t -> t.check, (t, v) -> t.check = v)) {
                tickBattle(battle, self, buffer);
            }
            return;
        }
        if (!FightManager.isFighter(self) || !due(self, now, CHECK_INTERVAL_MS, t -> t.check, (t, v) -> t.check = v)) {
            return;
        }
        Ref<EntityStore> enemy = FightManager.enemyOf(self);
        if (enemy != null) {
            relock(self, enemy, buffer);
        }
    }

    private void pruneTimers(long now) {
        if (now - lastPrune < PRUNE_INTERVAL_MS) {
            return;
        }
        lastPrune = now;
        timers.keySet().removeIf(ref -> !ref.isValid());
    }

    private interface Getter {
        long get(Timers t);
    }

    private interface Setter {
        void set(Timers t, long value);
    }

    /** True (and restarts the timer) if this job of {@code self} last ran at least {@code interval} ago. */
    private boolean due(Ref<EntityStore> self, long now, long interval, Getter get, Setter set) {
        Timers t = timers.computeIfAbsent(self, k -> new Timers());
        if (now - get.get(t) < interval) {
            return false;
        }
        set.set(t, now);
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
        if (battle.killAll && battle.team(self) != battle.survivorTeam) {
            if (!WandFx.isDying(self)) WandFx.kill(self, null, true, buffer);
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

    /** Team color ring under this NPC (or player fighter, from PlayerFighters.tick), only for the player who owns the draft/battle and holds the Team Wand. */
    static void drawTeamRing(Ref<EntityStore> self, BattleManager.Battle battle,
                             com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        if (battle != null) {
            PlayerRef owner = battle.owner();
            int team = battle.team(self);
            boolean holds = team >= 0 && FightFx.holdsTeamWand(owner, buffer);
            if (holds) {
                FightFx.teamRing(owner, self, battle.color(team), buffer);
            }
            if (team >= 0 && FxSettings.teamLookOnlyWand) {
                TeamFx.setHeld(self, holds ? battle.color(team) : null);
            }
            return;
        }
        for (Map.Entry<UUID, BattleManager.Draft> e : BattleManager.drafts().entrySet()) {
            int team = e.getValue().teamOf(self);
            if (team < 0) {
                continue;
            }
            PlayerRef owner = BattleManager.draftOwner(e.getKey());
            boolean holds = FightFx.holdsTeamWand(owner, buffer);
            if (holds) {
                FightFx.teamRing(owner, self, Teams.color(team), buffer);
            }
            if (FxSettings.teamLookOnlyWand) {
                TeamFx.setHeld(self, holds ? Teams.color(team) : null);
            }
        }
    }
}
