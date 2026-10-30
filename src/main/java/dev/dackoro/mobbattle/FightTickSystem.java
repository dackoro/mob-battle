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
    /** The full "ignore every other managed mob" pass, unless battle membership changed meanwhile. */
    private static final long IGNORE_ALL_INTERVAL_MS = 30_000;
    /** Timers are keyed by entity reference, so removed mobs must be dropped or the map keeps growing. */
    private static final long PRUNE_INTERVAL_MS = 10_000;

    /** Last run of each periodic job for one NPC. */
    private static final class Timers {
        long check, ring, join, isolation, ignoreAll;
        long membership = -1;
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
        boolean homes = ChaseSupport.anyHome();
        // DamageGroups: a mob whose wand fight ended with nothing else running still needs its groups back
        boolean groups = DamageGroups.any();
        if (!battles && !drafts && !stops && !heals && !looks && !kills && !countdowns && !snaps && !homes && !groups
                && !FightManager.hasFights()) {
            // Nothing running anywhere: drop the timers, their Refs would keep unloaded worlds reachable.
            if (!timers.isEmpty()) timers.clear();
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
        // An onlooker of the same group (an Outlander next to an Outlander duel) must not answer the fighters' calls.
        if (battles && !managed) {
            dropCallsAboutFighters(self, buffer);
            FightManager.dropFighterTarget(self, buffer);
        }
        if (managed && due(self, now, ISOLATION_INTERVAL_MS, t -> t.isolation, (t, v) -> t.isolation = v)) {
            Timers t = timers.get(self);
            long version = BattleManager.membership();
            boolean full = t == null || t.membership != version || now - t.ignoreAll >= IGNORE_ALL_INTERVAL_MS;
            if (full && t != null) {
                t.membership = version;
                t.ignoreAll = now;
            }
            FightManager.refreshBattleIsolation(self, battle, buffer, full);
        }
        if (battles && !managed && BattleManager.hasFlocks() && BattleManager.recentlySpawned(self) && !FightManager.isFighter(self)
                && due(self, now, CHECK_INTERVAL_MS, t -> t.join, (t, v) -> t.join = v)) {
            // Pets/summons spawned mid-battle (e.g. the Trork Hunter's wolf) join their owner's side.
            battle = BattleManager.joinOwnersSide(self, buffer);
        }
        boolean ring = battle != null ? battle.isTeams() : drafts && managed;
        if (ring && due(self, now, FightFx.TEAM_RING_REFRESH_MS, t -> t.ring, (t, v) -> t.ring = v)) {
            drawTeamRing(self, battle, buffer);
        }

        if (battle != null) {
            if (FxSettings.isolated(battle.kind)) dropOutsideBeacons(battle, self, buffer);
            // Every tick, not once a second: pack animals join a flock again right away (wolves in a royale were back
            // in one flock each second and never hurt each other, test 2026-10-14). Two component reads.
            Ref<EntityStore> current = battle.targets.get(self);
            if (current != null) leaveSharedFlock(self, current, buffer);
            if (!battle.refocus.isEmpty() && battle.refocus.remove(self)) {
                // All vs one: a fellow attacker hit it; back on the lone target at once (and it ignores that ally).
                Ref<EntityStore> target = battle.targets.get(self);
                if (target != null && target.isValid()) FightManager.turnAgainst(self, target, buffer);
            }
            if (due(self, now, CHECK_INTERVAL_MS, t -> t.check, (t, v) -> t.check = v)) {
                tickBattle(battle, self, buffer);
            }
            return;
        }
        if (!FightManager.isFighter(self)) {
            // Its wand fight (army) is over: home goes back to where it was (Army.follow then moves it to the owner).
            // Managed mobs too (audit 2026-10-14): a mob stays managed after its battle, and an army fight it had later
            // left its HOME entry for good, which kept this system busy on every NPC.
            if (homes && ChaseSupport.hasHome(self)) ChaseSupport.restoreLeash(self, buffer);
            if (groups) DamageGroups.restore(self, buffer);
            return;
        }
        if (!due(self, now, CHECK_INTERVAL_MS, t -> t.check, (t, v) -> t.check = v)) {
            return;
        }
        Ref<EntityStore> enemy = FightManager.enemyOf(self);
        MajorDungeonsCompat.keepBoss(self, buffer);
        DamageGroups.open(self, buffer);
        if (enemy != null) {
            relock(self, enemy, buffer);
            // Army fights too: no soft leash back mid-fight, and a far enemy is still walked to (ChaseSupport).
            if (BattleManager.isAlive(enemy, buffer)) ChaseSupport.keep(self, enemy, buffer);
        }
    }

    /**
     * Battle isolation for group calls (audit 2026-10-13, playtest: Outlanders in test duels went for other pairs). Trork
     * and Outlander roles shout "TrorkEnemySighted" (Kweebecs "Kweebec_All_Danger") to every NPC of their group in 30
     * blocks, and the call SETS the receiver's LockedTarget to the caller's target, whatever the attitudes say. Every
     * tick, a battle member drops calls whose target is not one of its own enemies in this battle; calls about a real
     * enemy are kept (teamwork). Read through BeaconSupport (the NPC's message slots, pollMessage = consume).
     */
    private static void dropOutsideBeacons(BattleManager.Battle battle, Ref<EntityStore> self, CommandBuffer<EntityStore> buffer) {
        var beacons = buffer.getComponent(self, com.hypixel.hytale.server.npc.components.messaging.BeaconSupport.getComponentType());
        var slots = beacons != null ? beacons.getMessageSlots() : null;
        if (slots == null) return;
        int team = battle.team(self);
        for (int i = 0; i < slots.length; i++) {
            if (!beacons.isMessageQueued(i)) continue;
            Ref<EntityStore> about = beacons.peekMessage(i);
            // Only calls pointing at someone: target-less ones are signals of boss phases and encounters.
            if (about == null) continue;
            boolean enemy = about.isValid() && battle.has(about) && battle.team(about) != team;
            if (!enemy) {
                beacons.pollMessage(i);
                TestTools.callDropped(self);
            }
        }
    }

    /**
     * The other half of {@link #dropOutsideBeacons}: an NPC outside every battle drops a group call whose target fights
     * in an isolated battle (playtest 2026-10-13: nearby Outlanders kept joining an Outlander duel). Runs only while a
     * battle exists; one component read and a few message slots per NPC.
     */
    private static void dropCallsAboutFighters(Ref<EntityStore> self, CommandBuffer<EntityStore> buffer) {
        var beacons = buffer.getComponent(self, com.hypixel.hytale.server.npc.components.messaging.BeaconSupport.getComponentType());
        var slots = beacons != null ? beacons.getMessageSlots() : null;
        if (slots == null) return;
        for (int i = 0; i < slots.length; i++) {
            if (!beacons.isMessageQueued(i)) continue;
            Ref<EntityStore> about = beacons.peekMessage(i);
            if (about == null || !BattleManager.isManaged(about)) continue;
            BattleManager.Battle b = BattleManager.battleOf(about);
            if (b != null && FxSettings.isolated(b.kind)) {
                beacons.pollMessage(i);
                TestTools.callDropped(self);
            }
        }
    }

    /**
     * Two enemies in one flock can't hurt each other: vanilla CombatSupport.getCanCauseDamage is false between flock
     * members (disableDamageFlock), the damage filter cancels melee (FightDamageSystem un-cancels it) and ranged
     * attackers never even aim (PositionCache skips targets they can't damage). Battles split flocks at the start, but
     * mobs spawned together join one again later: two Outlander Stalkers in a royale never shot each other (test,
     * 2026-10-13). Like vanilla FlockLeave, both leave the flock, checked once a second against the current target.
     */
    private static void leaveSharedFlock(Ref<EntityStore> self, Ref<EntityStore> target, CommandBuffer<EntityStore> buffer) {
        if (!target.isValid() || target.getStore() != self.getStore()) return;
        Ref<EntityStore> mine = com.hypixel.hytale.server.flock.FlockPlugin.getFlockReference(self, buffer);
        if (mine == null) return;
        Ref<EntityStore> theirs = com.hypixel.hytale.server.flock.FlockPlugin.getFlockReference(target, buffer);
        if (theirs == null || (mine != theirs && !(mine.isValid() && theirs.isValid() && mine.getIndex() == theirs.getIndex()))) return;
        var flock = com.hypixel.hytale.server.flock.FlockMembership.getComponentType();
        buffer.tryRemoveComponent(self, flock);
        buffer.tryRemoveComponent(target, flock);
        long now = System.currentTimeMillis();
        if (MobBattlePlugin.debugging() && now - lastFlockLog > FLOCK_LOG_MS) {
            lastFlockLog = now;
            MobBattlePlugin.debug("battle: " + ChaosWand.nameOf(self, buffer) + " and its target " + ChaosWand.nameOf(target, buffer)
                    + " shared a flock (no damage between them): both left it");
        }
    }

    /** The flock line above at most this often (it now runs every tick). */
    private static final long FLOCK_LOG_MS = 5000;
    private static volatile long lastFlockLog;

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

    /**
     * A wild mob in a battle drops out of natural spawning: the world's overpopulation despawn (SpawningPlugin
     * .shouldNPCDespawn) would REMOVE it mid-fight and that counts as a death. Integer.MIN_VALUE is what vanilla
     * RoleChangeSystem gives a changed mob; the mob simply stays in the world afterwards.
     */
    private static void keepFromDespawn(Ref<EntityStore> self, CommandBuffer<EntityStore> buffer) {
        NPCEntity npc = buffer.getComponent(self, NPCEntity.getComponentType());
        if (npc != null && npc.getSpawnConfiguration() != Integer.MIN_VALUE) npc.setSpawnConfiguration(Integer.MIN_VALUE);
    }

    /** Winners and the timeout are checked once per second per world by BattleLifecycleSystem. */
    private static void tickBattle(BattleManager.Battle battle, Ref<EntityStore> self, CommandBuffer<EntityStore> buffer) {
        if (battle.over || !BattleManager.isAlive(self, buffer)) {
            return;
        }
        MajorDungeonsCompat.keepBoss(self, buffer);   // its arena must not reset (remove) it mid-battle
        keepFromDespawn(self, buffer);
        DamageGroups.open(self, buffer);             // e.g. a Feastmaster may hit a goblin enemy (DisableDamageGroups)
        if (battle.killAll && battle.team(self) != battle.survivorTeam) {
            if (!WandFx.isDying(self)) WandFx.kill(self, null, true, buffer);
            return;
        }
        // Players who fell in this battle are outsiders now: let them go (FightManager.dropFallen).
        if (!battle.fallen.isEmpty()) FightManager.dropFallen(self, battle, buffer);
        boolean[] changed = {BattleManager.selfDefense(battle, self, buffer)};
        Ref<EntityStore> target = BattleManager.currentTarget(battle, self, buffer, changed);
        if (target == null) {
            return;
        }
        if (holdsBack(self, target, buffer)) {
            return;
        }
        if (changed[0]) {
            // Previous target died: go for the next one. In a royale the old target stays an enemy, so it is not
            // ignored for 30 s: after a few switches a mob ignored most of the arena and stood confused (playtest 2026-10-12).
            FightManager.turnAgainst(self, target, buffer, !battle.royale);
            FightManager.provoke(self, target, buffer);
        } else {
            relock(self, target, buffer);
        }
        // A player target never walks in and hits by itself as an enemy mob does: hostility, memory and the CAE's
        // target are put again each check (FightManager.pressPlayerTarget).
        if (BattleManager.isPlayerFighter(target)) FightManager.pressPlayerTarget(self, target, buffer);
        // Leash point here, and back on the chase when the target is out of its sight or it gave up (ChaseSupport).
        ChaseSupport.keep(self, target, buffer);
    }

    /**
     * Medium (2026-10-14): a walker that would drown does not follow a target into deep water (Zombie, wolf, Trork and
     * golem sank 66 blocks and drowned in the test), and a water-only mob does not try to reach one on land. It lets
     * go and ignores it a moment, checked again every second; passiveWin ends the battle if nobody can reach anybody.
     * A flier out of reach is still followed from below, as before.
     */
    private static boolean holdsBack(Ref<EntityStore> self, Ref<EntityStore> target, CommandBuffer<EntityStore> buffer) {
        Medium.Can can = Medium.of(self, buffer);
        if (!Medium.drowns(can) && !Medium.waterOnly(can)) return false;
        Medium.Where there = Medium.where(target, buffer);
        if (Medium.reaches(can, there)) return false;
        boolean wet = there == Medium.Where.SURFACE || there == Medium.Where.UNDER;
        if (Medium.drowns(can) ? !wet : there != Medium.Where.GROUND) return false;
        FightManager.holdOff(self, target, buffer);
        return true;
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
