package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Group battles that last until one side is left standing.
 * <ul>
 *   <li>Battle royale (Chaos Wand right click): every NPC is its own team.</li>
 *   <li>Team battle (Team Wand): NPCs grouped into colored teams.</li>
 * </ul>
 * When an NPC's target dies, it picks the nearest living enemy (see FightTickSystem).
 */
public final class BattleManager {

    /** Team names and colors, in the order the Team Wand cycles through them. */
    /** Team slots (null = free). Filled and managed by {@link Teams}. */
    public static final String[] TEAM_NAMES = new String[Teams.MAX];
    public static final String[] TEAM_COLORS = new String[Teams.MAX];

    /** Safety: a battle stops after battleMaxTime minutes even if nobody wins; 0 = never (infinite battle). */
    static long deadline() {
        int min = FxSettings.battleMaxMinutes;
        return min <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + min * 60_000L;
    }

    /** Battle type; its key names it in /mobbattle friendlyfire and battleisolation. */
    public enum Kind {
        DUEL("duel"), ALL_VS_ONE("allvsone"), ROYALE("royale"), TEAMS("teams");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /**
     * One fighter's kills in a battle: name and side as they were when it scored, and when it reached this count
     * ({@code at}, ms): the last tie break of {@link Battle#ranking} (playtest 2026-10-13: two fighters on 5 kills were
     * listed in map order).
     */
    record Kills(String name, int team, int count, long at) {
    }

    public static final class Battle {
        /** Stable presentation anchor for server-launched battles that have no player owner. */
        record Anchor(UUID world, Vector3d center, UUID eventId) {
            Anchor(UUID world, Vector3d center) {
                this(world, center, UUID.randomUUID());
            }

            Anchor {
                center = center == null ? null : new Vector3d(center);
                eventId = eventId == null ? UUID.randomUUID() : eventId;
            }

            public Vector3d center() {
                return center == null ? null : new Vector3d(center);
            }
        }

        final UUID id = UUID.randomUUID();
        /** Set from the constructor; SelectFighterInteraction marks its 2-mob royale as a DUEL. */
        volatile Kind kind;
        final PlayerRef owner;
        final Anchor anchor;
        final boolean royale;
        /** Chaos Wand "EVERYONE vs X": the target's name (side 1, alone) vs everyone else (side 0); else null. */
        final String focusName;
        final String[] names = TEAM_NAMES.clone();
        final String[] colors = TEAM_COLORS.clone();
        com.hypixel.hytale.component.Store<EntityStore> store;
        /**
         * Member -> team (in a royale every NPC has its own team). Keyed by the Ref itself, never by
         * getIndex(): Store.removeEntity moves the world's last entity into the freed slot, so indexes
         * change whenever anything else (an arrow, a dead mob, a dropped item) is removed.
         */
        final Map<Ref<EntityStore>, Integer> teamOf = new ConcurrentHashMap<>();
        /** Member -> its current target. */
        final Map<Ref<EntityStore>, Ref<EntityStore>> targets = new ConcurrentHashMap<>();
        /** selfDefense: member -> the enemy that last hit it (FightDamageSystem), read once by FightTickSystem. */
        final Map<Ref<EntityStore>, Ref<EntityStore>> hitBy = new ConcurrentHashMap<>();
        /** selfDefense: member -> when it last turned on an attacker (no switching back and forth). */
        final Map<Ref<EntityStore>, Long> turnedAt = new ConcurrentHashMap<>();
        /** Chase limit: member -> when it last hit an enemy, or got its current target (whichever is later). */
        final Map<Ref<EntityStore>, Long> chaseFrom = new ConcurrentHashMap<>();
        /** Member -> when it last hit its own current target (selfDefense keeps a target it is busy hitting). */
        final Map<Ref<EntityStore>, Long> hitTarget = new ConcurrentHashMap<>();
        /** Target picking: member -> whether it can attack at all (FightManager.canAttack), asked once. */
        final Map<Ref<EntityStore>, Boolean> canFight = new ConcurrentHashMap<>();
        /** All vs one: attackers hit by a fellow attacker, put back on the lone target next tick (FightTickSystem). */
        final Set<Ref<EntityStore>> refocus = ConcurrentHashMap.newKeySet();
        /**
         * Players who fell (died, went Creative) in this battle while it goes on -> the NPC members that already let
         * them go (FightManager.dropFallen). A respawned player is the same entity: an outsider of this battle from now.
         */
        final Map<Ref<EntityStore>, Set<Ref<EntityStore>>> fallen = new ConcurrentHashMap<>();
        /** FocusCard: the lone target (kept after it falls and leaves teamOf) and the most attackers seen at once. */
        volatile Ref<EntityStore> cardLone;
        volatile int cardPeak;
        /** passiveWin: when only one side (or none) could still fight back, or its last hit since; 0 = not now. */
        volatile long passiveSince;
        /** passiveWin: ended by it (end titles say so). */
        volatile boolean passiveEnded;
        /** passiveWin ended it with nobody able to reach the others (Medium): won on health. */
        volatile boolean unreachEnded;
        /** idleEnd: the battle's start or its last hit between enemies; ended by it. */
        volatile long lastHit = System.currentTimeMillis();
        /** Who took the last enemy hit (FilmCamera leans toward it while lastHit is recent). */
        volatile Ref<EntityStore> lastVictim;
        volatile boolean idleEnded;
        /** When the battle began: only flock mates spawned after it join a side (joinOwnersSide). */
        final long startedAt = System.currentTimeMillis();
        /** Players shown the passiveWin alert (PassiveAlert), to take it off again. */
        final Set<UUID> alertViewers = ConcurrentHashMap.newKeySet();
        /** (flock, member) pairs refreshed once per second, so pets can join without scanning every member. */
        volatile List<Ref<EntityStore>[]> flocks = List.of();
        /** Safety deadline; an arena timer longer than battleMaxTime pushes it past its own end (Arena.setup). */
        volatile long expiresAt = deadline();
        /** Next winner check; only touched by the battle's own world thread. */
        long nextCheck;
        volatile boolean over;
        final java.util.concurrent.atomic.AtomicBoolean finishOnce = new java.util.concurrent.atomic.AtomicBoolean();
        volatile MobBattleApi.EndReason eventEndReason;
        /** -2 = not a winner result, -1 = explicit draw, otherwise winning team index. */
        volatile int eventWinnerSide = -2;
        volatile List<UUID> eventWinners = List.of();
        /** Time up with timeUp continue or kill: the end gets no winner title. */
        volatile boolean silent;
        /** A living fighter was unloaded with its chunk (BattleUnloadSystem): checkWinner cancels it, no winner. */
        volatile boolean abandoned;
        /** Time up with timeUp kill / judge: members are disintegrated (FightTickSystem), except survivorTeam's. */
        volatile boolean killAll;
        /** Damage each side dealt to its enemies (FightDamageSystem), for the tieBreak damage rule. */
        final Map<Integer, Double> damageBy = new ConcurrentHashMap<>();
        /** Damage each fighter dealt to its enemies, for killTieBreak damage/alive. */
        final Map<Ref<EntityStore>, Double> damageOf = new ConcurrentHashMap<>();
        /** Kills per fighter (KillTracker), with its name and side kept for after it dies or leaves. */
        final Map<Ref<EntityStore>, Kills> kills = new ConcurrentHashMap<>();
        /** Who scored the latest kill (endCamera lasthit: the final blow). */
        volatile Ref<EntityStore> lastKiller;
        /** Kills per side, for /mobbattle tieBreak kills. */
        final Map<Integer, Integer> killsBy = new ConcurrentHashMap<>();

        /** A kill by {@code killer} (side {@code team}) in this battle. */
        void recordKill(Ref<EntityStore> killer, String name, int team) {
            lastKiller = killer;
            long now = System.currentTimeMillis();
            kills.compute(killer, (k, old) -> new Kills(name, team, old == null ? 1 : old.count() + 1, now));
            killsBy.merge(team, 1, Integer::sum);
        }

        /**
         * Kill table order (killTieBreak of this kind, playtest 2026-10-13): most kills; even = more damage dealt (damage),
         * standing fighters first then damage (alive), or nothing more (first, shared); last, who reached it first.
         */
        java.util.Comparator<Map.Entry<Ref<EntityStore>, Kills>> ranking() {
            String mode = KindFx.killTieBreak(kind);
            java.util.Comparator<Map.Entry<Ref<EntityStore>, Kills>> c =
                    (x, y) -> Integer.compare(y.getValue().count(), x.getValue().count());
            // Damage and standing frozen for this sort (audit 2026-10-14): the world thread changes them while /mobbattle
            // stop sorts on the command thread, and a comparator whose values move can throw mid-sort.
            if ("alive".equals(mode)) {
                java.util.Set<Ref<EntityStore>> standing = new java.util.HashSet<>();
                for (Ref<EntityStore> r : kills.keySet()) if (has(r)) standing.add(r);
                c = c.thenComparingInt(e -> standing.contains(e.getKey()) ? 0 : 1);
            }
            if ("alive".equals(mode) || "damage".equals(mode)) {
                Map<Ref<EntityStore>, Double> dealt = new java.util.HashMap<>(damageOf);
                c = c.thenComparingDouble(e -> -dealt.getOrDefault(e.getKey(), 0.0));
            }
            return c.thenComparingLong(e -> e.getValue().at());
        }

        /** killTieBreak shared: the same kills share a place (1, 2, 2, 4). */
        boolean sharedPlaces() {
            return "shared".equals(KindFx.killTieBreak(kind));
        }

        /** The fighters with the most kills, in {@link #ranking} order. */
        List<Kills> topKillers(int n) {
            return kills.entrySet().stream().sorted(ranking()).limit(n).map(Map.Entry::getValue).toList();
        }

        /** The first of {@link #ranking}, or null; one pass, no copy (the timer box asks every second). */
        Kills topKiller() {
            java.util.Comparator<Map.Entry<Ref<EntityStore>, Kills>> order = ranking();
            Map.Entry<Ref<EntityStore>, Kills> best = null;
            for (Map.Entry<Ref<EntityStore>, Kills> e : kills.entrySet()) if (best == null || order.compare(e, best) < 0) best = e;
            return best != null ? best.getValue() : null;
        }

        /** How many fighters share the top kill count (the timer box says "2 tied" with killTieBreak shared). */
        int tiedAtTop() {
            Kills top = topKiller();
            if (top == null) return 0;
            int n = 0;
            for (Kills k : kills.values()) if (k.count() == top.count()) n++;
            return n;
        }
        /** timeUp judge: the side that won on health (healed, spared); -1 = nobody is spared. */
        volatile int survivorTeam = -1;

        Battle(PlayerRef owner, boolean royale) {
            this(owner, royale, null);
        }

        Battle(PlayerRef owner, boolean royale, String focusName) {
            this(owner, null, royale, focusName);
        }

        Battle(PlayerRef owner, Anchor anchor, boolean royale, String focusName) {
            this.owner = owner;
            this.anchor = anchor;
            this.royale = royale;
            this.focusName = focusName;
            this.kind = focusName != null ? Kind.ALL_VS_ONE : royale ? Kind.ROYALE : Kind.TEAMS;
        }

        void add(Ref<EntityStore> npc, int team) {
            if (store == null) store = npc.getStore();
            fallen.remove(npc);   // back in this battle: no longer an outsider of it (audit 2026-10-29)
            teamOf.put(npc, team);
            MANAGED.add(npc);
            MEMBERSHIP.incrementAndGet();
        }

        String name(int team) { return team >= 0 && team < names.length && names[team] != null ? names[team] : "?"; }
        String color(int team) { return team >= 0 && team < colors.length && colors[team] != null ? colors[team] : "#aaaaaa"; }

        boolean has(Ref<EntityStore> npc) {
            return npc != null && npc.isValid() && teamOf.containsKey(npc);
        }

        int team(Ref<EntityStore> npc) {
            return teamOf.getOrDefault(npc, -1);
        }

        /** Team Wand battles (team colors, rings, team look); not royales or "EVERYONE vs X". */
        public boolean isTeams() {
            return !royale && focusName == null;
        }

        public Set<Ref<EntityStore>> members() {
            return teamOf.keySet();
        }

        /** One shared snapshot for every member's cleanup, instead of a copy per member. */
        List<Ref<EntityStore>> snapshot() {
            return List.copyOf(teamOf.keySet());
        }

        public PlayerRef owner() {
            return owner;
        }
    }

    private static final List<Battle> BATTLES = new CopyOnWriteArrayList<>();
    private static final Set<Ref<EntityStore>> MANAGED = ConcurrentHashMap.newKeySet();
    /** Bumped on every battle join: FightTickSystem redoes the full "ignore the rest" pass when it changes. */
    private static final java.util.concurrent.atomic.AtomicLong MEMBERSHIP = new java.util.concurrent.atomic.AtomicLong();

    static long membership() { return MEMBERSHIP.get(); }
    /** Wand fights (FightManager) change who is fighting whom too. */
    static void membershipChanged() { MEMBERSHIP.incrementAndGet(); }

    /** Some draft has mobs (refreshed by maintain, set right away when one is added). */
    private static volatile boolean draftMembers;
    /** Some battle member belongs to a flock, so new pets may need to join (refreshed once per second). */
    private static volatile boolean anyFlocks;

    /** Every battle member is managed (add), so a hit between two unmanaged entities needs no battle lookup. */
    static boolean isManaged(Ref<EntityStore> npc) { return npc != null && MANAGED.contains(npc); }
    static Set<Ref<EntityStore>> managed() { return MANAGED; }

    /**
     * Players fighting in a battle or waiting in a team draft (/mobbattle playerFights). FightTickSystem only
     * ticks NPCs, so they never get the NPC-only queues (stops, heals, provocations); PlayerFighters handles their
     * team look and ring, PlayerDeathSystem takes them out when they die.
     */
    private static final Map<Ref<EntityStore>, UUID> PLAYER_FIGHTERS = new ConcurrentHashMap<>();

    /** {@code player} fights in {@code owner}'s battle or draft; they see that owner's titles, timer and music. */
    static void markPlayer(Ref<EntityStore> player, UUID owner) {
        // a new fighter (not a re-mark): their VS picture now, they are about to be in one
        if (PLAYER_FIGHTERS.put(player, owner) == null) VsScreen.playerPicked(player);
    }
    static void unmarkPlayer(Ref<EntityStore> player) { PLAYER_FIGHTERS.remove(player); }
    static boolean isPlayerFighter(Ref<EntityStore> ref) { return !PLAYER_FIGHTERS.isEmpty() && PLAYER_FIGHTERS.containsKey(ref); }
    static boolean hasPlayerFighters() { return !PLAYER_FIGHTERS.isEmpty(); }
    static Set<Ref<EntityStore>> playerFighters() { return PLAYER_FIGHTERS.keySet(); }

    /** Player fighters put in by {@code owner} (FightFx.titleViewers: they see the titles too). */
    static List<Ref<EntityStore>> playerFightersOf(UUID owner) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        PLAYER_FIGHTERS.forEach((ref, o) -> { if (o.equals(owner) && ref.isValid()) out.add(ref); });
        return out;
    }

    /**
     * A player fighter died (PlayerDeathSystem): out of its battle for good. A player respawns as the same
     * entity, so leaving them in it would put them back in the fight. Their side may lose: checkWinner decides
     * within a second, with the normal titles. Returns the battle they were in, or null.
     */
    static Battle fall(Ref<EntityStore> player) {
        Battle b = battleOf(player);
        if (b == null) return null;
        b.teamOf.remove(player);
        b.targets.remove(player);
        b.hitBy.remove(player);
        b.turnedAt.remove(player);
        b.chaseFrom.remove(player);
        b.hitTarget.remove(player);
        b.canFight.remove(player);
        b.refocus.remove(player);
        // Nobody keeps him as target or attacker to answer: each mob picks another enemy on its next check
        // (currentTarget), and FightTickSystem makes it drop him (LockedTarget, hostile memory, HOSTILE override).
        b.targets.values().removeIf(t -> t == player);
        b.hitBy.values().removeIf(t -> t == player);
        b.fallen.put(player, ConcurrentHashMap.newKeySet());
        anyFallen = true;
        FightManager.endFightsOf(List.of(player));
        releasePlayer(player);
        return b;
    }

    /** Some running battle has a fallen player (fast check for the damage systems). */
    private static volatile boolean anyFallen;

    static boolean anyFallen() { return anyFallen; }

    /** The running battle {@code player} fell in (and is now an outsider of), or null. */
    static Battle fellFrom(Ref<EntityStore> player) {
        if (!anyFallen || player == null) return null;
        for (Battle b : BATTLES) {
            if (!b.over && b.fallen.containsKey(player)) return b;
        }
        return null;
    }

    /**
     * Whether {@code player} fell in a running isolated battle that {@code mob} fights in: hits between them are
     * cancelled. Every battle he fell from counts (audit 2026-10-29: only the first one was looked at).
     */
    static boolean fallenApart(Ref<EntityStore> player, Ref<EntityStore> mob) {
        if (!anyFallen || player == null || mob == null || !isManaged(mob)) return false;
        for (Battle b : BATTLES) {
            if (!b.over && b.fallen.containsKey(player) && FxSettings.isolated(b.kind) && b.has(mob)) return true;
        }
        return false;
    }

    /**
     * /mobbattle teams leave (and switching to Creative): the player leaves their team draft, or the battle they
     * fight in. Returns true if they were in one.
     */
    static boolean leave(Ref<EntityStore> player) {
        if (battleOf(player) != null) {
            // Like falling: their side may lose, with the normal titles (detach would end it silently).
            fall(player);
            PLAYER_FIGHTERS.remove(player);
            return true;
        }
        boolean was = DRAFTS.values().stream().anyMatch(d -> d.teamOf(player) >= 0);
        if (was) {
            DRAFTS.values().forEach(d -> d.remove(player));
            releasePlayer(player);
            PLAYER_FIGHTERS.remove(player);
        }
        return was;
    }

    /** A player leaves the battle side of things: no team look, not managed, not a fighter any more. */
    private static void releasePlayer(Ref<EntityStore> player) {
        TeamFx.clear(player);
        MANAGED.remove(player);
        if (DRAFTS.values().stream().noneMatch(d -> d.teamOf(player) >= 0)) PLAYER_FIGHTERS.remove(player);
    }
    static boolean hasDraftMembers() { return draftMembers; }
    static boolean hasFlocks() { return anyFlocks; }

    /**
     * NPC hits to cancel. Allies in the same battle never hurt each other. With /mobbattle battleIsolation on
     * (default), battle mobs also can't hurt or be hurt by NPCs outside their battle (wild mobs, other
     * battles, or players for the mobs' hits). Players can always hit battle mobs: only NPC sources get here.
     */
    public static boolean blocksDamage(Ref<EntityStore> x, Ref<EntityStore> y) {
        // Per hit: wild vs wild never walks the battle list.
        if (!isManaged(x) && !isManaged(y)) return false;
        Battle a = battleOf(x), b = battleOf(y);
        if (a != null && a == b) {
            // Allies never pick each other as targets; with /mobbattle friendlyfire on, stray hits still hurt.
            return !FxSettings.friendlyFire(a.kind) && a.team(x) == a.team(y);
        }
        return a != null && FxSettings.isolated(a.kind) || b != null && FxSettings.isolated(b.kind);
    }

    /** Winner/timeout check and pet cache of this world's battles; runs on the world thread once per second. */
    static void checkAll(com.hypixel.hytale.component.Store<EntityStore> store, long now) {
        for (Battle battle : BATTLES) {
            if (battle.store == store && now >= battle.nextCheck) {
                battle.nextCheck = now + 1000;
                checkWinner(battle, store);
                if (!battle.over) refreshFlocks(battle, store);
            }
        }
    }

    /**
     * Global cleanup, once per second from any world thread. Everything here is keyed by Ref, and a Ref
     * keeps its whole Store (world) reachable, so dead or unloaded entities must be dropped.
     */
    static void maintain() {
        Kits.prune();
        // Retired mobs stay managed on purpose (joinOwnersSide must not pull them back into a battle).
        MANAGED.removeIf(npc -> !npc.isValid());
        long spawnCutoff = System.currentTimeMillis() - SPAWN_MEMORY_MS;
        SPAWNED_AT.entrySet().removeIf(e -> !e.getKey().isValid() || e.getValue() < spawnCutoff || BATTLES.isEmpty());
        RoleChange.prune();
        Medium.prune();
        WandFx.prune();
        Snap.prune();
        Army.prune();
        Buffs.prune();
        SummonDespawn.maintain();
        SummonCalm.prune();
        DamageGroups.prune();
        SkinRespawnSystem.prune();
        BattleMusic.maintain();
        for (Battle battle : BATTLES) {
            // World unloaded (e.g. a closed instance): its tick will never run checkWinner again. A live world
            // handles its own empty battles there, with the "DRAW!" announcement.
            if (battle.store == null || battle.store.isShutdown()) {
                finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.UNLOAD, -2);
                MobBattlePlugin.log("Battle dropped: its world was unloaded");
            }
        }
        // A draft keeps its world (EntityStore) reachable: drop it once that world is unloaded.
        DRAFTS.values().removeIf(d -> d.entityStore.getStore().isShutdown());
        boolean members = false;
        for (Draft draft : DRAFTS.values()) {
            draft.prune();
            members |= draft.teams.stream().anyMatch(t -> !t.isEmpty());
        }
        draftMembers = members;
        anyFlocks = BATTLES.stream().anyMatch(b -> !b.flocks.isEmpty());
        boolean fell = false;
        for (Battle b : BATTLES) {
            if (b.fallen.isEmpty()) continue;
            b.fallen.keySet().removeIf(p -> !p.isValid());
            b.fallen.values().forEach(done -> done.removeIf(npc -> !npc.isValid()));
            fell |= !b.fallen.isEmpty();
        }
        anyFallen = fell;
        if (!PLAYER_FIGHTERS.isEmpty()) {
            // A player marked by a wand or command waits in a countdown before their battle exists: keep them.
            PLAYER_FIGHTERS.keySet().removeIf(p -> !p.isValid()
                    || battleOf(p) == null && DRAFTS.values().stream().noneMatch(d -> d.teamOf(p) >= 0)
                    && !Countdown.involves(p));
        }
        FightManager.prune();
        TeamFx.prune();
        MorphWand.prune();
        EyeRecolor.prune();
        FightFx.pruneTitles();
        FightFx.pruneHeldAreas();
        FightFx.pruneTeleports();
        Countdown.prune();
        BattleDebug.prune();
        Arena.prune();
        Unstick.prune();
        FightCamera.prune();
    }

    private static void refreshFlocks(Battle battle, ComponentAccessor<EntityStore> accessor) {
        List<Ref<EntityStore>[]> pairs = new ArrayList<>();
        for (Ref<EntityStore> member : battle.teamOf.keySet()) {
            Ref<EntityStore> flock = member.isValid()
                    ? com.hypixel.hytale.server.flock.FlockPlugin.getFlockReference(member, accessor) : null;
            if (flock != null && flock.isValid()) {
                @SuppressWarnings("unchecked")
                Ref<EntityStore>[] pair = new Ref[]{flock, member};
                pairs.add(pair);
            }
        }
        battle.flocks = pairs;
        anyFlocks = BATTLES.stream().anyMatch(b -> !b.flocks.isEmpty());
    }

    private static void finish(Battle battle) {
        finish(battle, battle.snapshot());
    }

    private static void finish(Battle battle, List<Ref<EntityStore>> formerMembers) {
        finish(battle, formerMembers, Set.of());
    }

    /** How long the winning team keeps its team look (tint, mist) after a team battle, to see who won. */
    static final int WINNER_LOOK_SECONDS = 8;

    /** keepLook: winners whose team look stays WINNER_LOOK_SECONDS longer. */
    private static void finish(Battle battle, List<Ref<EntityStore>> formerMembers, Set<Ref<EntityStore>> keepLook) {
        finish(battle, formerMembers, keepLook, null, -2);
    }

    /** False if another thread already finished it (stop, new order) and this call did nothing. */
    private static boolean finish(Battle battle, List<Ref<EntityStore>> formerMembers, Set<Ref<EntityStore>> keepLook,
                                  MobBattleApi.EndReason reason, int winnerSide) {
        if (!battle.finishOnce.compareAndSet(false, true)) return false;
        if (reason != null) battle.eventEndReason = reason;
        if (winnerSide != -2) battle.eventWinnerSide = winnerSide;
        battle.over = true;
        BATTLES.remove(battle);
        try {
            cleanUp(battle, formerMembers, keepLook);
        } finally {
            // Whatever failed above, an API event still ends (else it stays RUNNING for good).
            publishApiResult(battle);
        }
        return true;
    }

    private static void cleanUp(Battle battle, List<Ref<EntityStore>> formerMembers, Set<Ref<EntityStore>> keepLook) {
        if (!battle.alertViewers.isEmpty()) PassiveAlert.hideAll(battle.alertViewers);
        killSummary(battle);
        List<Ref<EntityStore>> all = battle.snapshot();
        // An intro camera still filming this battle ends with it (the end camera keeps playing).
        FightCamera.battleOver(formerMembers);
        FightCamera.battleOver(all);
        // The lone and streak buffs go (formerMembers: a mob may have just been detached), and the lone target's crown.
        for (Ref<EntityStore> r : formerMembers) Buffs.endBattle(r);
        if (battle.kind == Kind.ALL_VS_ONE) WandFx.removeCrownsLater(formerMembers);
        // summonDespawn battleEnd: the surviving summons that fought here leave (each claimed once).
        SummonDespawn.battleEnded(all);
        SummonDespawn.battleEnded(formerMembers);
        if (battle.isTeams()) {
            List<Ref<EntityStore>> geared = new ArrayList<>(all);
            geared.addAll(formerMembers);
            Kits.teamBattleEnd(geared, battle.store);
        }
        // Players who fell in it: the calm-down below only covers members, so its mobs drop them here (audit
        // 2026-10-29: a dummy that killed a player kept its 10-minute HOSTILE toward him after the battle).
        if (!battle.fallen.isEmpty() && battle.store != null && !battle.store.isShutdown()) {
            List<Ref<EntityStore>> fell = new ArrayList<>(battle.fallen.keySet());
            List<Ref<EntityStore>> mobs = new ArrayList<>(all);
            for (Ref<EntityStore> r : formerMembers) if (!mobs.contains(r)) mobs.add(r);
            var st = battle.store;
            try {
                st.getExternalData().getWorld().execute(() -> {
                    for (Ref<EntityStore> npc : mobs) {
                        if (npc.isValid() && !isPlayerFighter(npc)) FightManager.releaseFallen(npc, fell, st);
                    }
                });
            } catch (RuntimeException ignored) {
                // world stopping
            }
        }
        for (Ref<EntityStore> npc : all) {
            if (!npc.isValid()) continue;
            if (isPlayerFighter(npc)) {
                releasePlayer(npc);
                continue;
            }
            FightManager.queueStop(npc, formerMembers);
            if (battle.isTeams() && !keepLook.contains(npc)) TeamFx.clear(npc);
        }
        if (battle.isTeams() && !keepLook.isEmpty()) {
            List<Ref<EntityStore>> winners = new ArrayList<>(keepLook);
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                for (Ref<EntityStore> npc : winners) {
                    // Put in a new team or battle meanwhile: it keeps its new look.
                    if (npc.isValid() && battleOf(npc) == null && !inDraft(npc)) TeamFx.clear(npc);
                }
            }, WINNER_LOOK_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static void publishApiResult(Battle battle) {
        if (battle.anchor == null || battle.anchor.eventId() == null) return;
        MobBattleApi.EndReason reason = battle.eventEndReason != null ? battle.eventEndReason
                : battle.abandoned ? MobBattleApi.EndReason.UNLOAD : MobBattleApi.EndReason.STOPPED;
        List<UUID> winners = reason == MobBattleApi.EndReason.WIN || reason == MobBattleApi.EndReason.TIMEOUT
                ? battle.eventWinners : List.of();
        Integer side = (reason == MobBattleApi.EndReason.WIN || reason == MobBattleApi.EndReason.TIMEOUT)
                && battle.eventWinnerSide >= 0 ? battle.eventWinnerSide : null;
        MobBattleApi.terminal(battle.anchor.eventId(), reason, winners,
                side);
    }

    static boolean stopEvent(UUID eventId) {
        if (eventId == null) return false;
        for (Battle battle : new ArrayList<>(BATTLES)) {
            if (battle.anchor != null && eventId.equals(battle.anchor.eventId())) {
                finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.STOPPED, -2);
                return true;
            }
        }
        return false;
    }

    private static boolean inDraft(Ref<EntityStore> npc) {
        for (Draft d : DRAFTS.values()) if (d.teamOf(npc) >= 0) return true;
        return false;
    }

    private BattleManager() {
    }

    /** Places of a ranked kill list: 1, 2, 3... or, shared, the same kills the same place (1, 2, 2, 4). */
    static int[] places(List<Kills> ranked, boolean shared) {
        int[] out = new int[ranked.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = shared && i > 0 && ranked.get(i).count() == ranked.get(i - 1).count() ? out[i - 1] : i + 1;
        }
        return out;
    }

    /** At the end: the top 3 killers in chat, to the owner and the player fighters (one line, any battle size). */
    private static void killSummary(Battle battle) {
        if (battle.kills.isEmpty()) return;
        StringBuilder line = new StringBuilder();
        List<Message> parts = new ArrayList<>();
        List<Kills> top = battle.topKillers(3);
        int[] places = places(top, battle.sharedPlaces());
        for (int i = 0; i < top.size(); i++) {
            Kills k = top.get(i);
            if (i > 0) line.append(", ");
            line.append(places[i]).append(". ").append(k.name()).append(" (").append(k.count()).append(')');
            // each viewer's client translates the names into their own language (not the first player's)
            parts.add(Message.raw((i > 0 ? ", " : "") + places[i] + ". "));
            parts.add(MobNames.message(k.name()));
            parts.add(Message.raw(" (" + k.count() + ")"));
        }
        Message msg = I18n.tr("battle.killsummary").param("list", Message.join(parts.toArray(new Message[0]))).color("#ffd23e");
        for (PlayerRef p : FightFx.titleViewers(battle)) {
            if (p.isValid()) p.sendMessage(msg);
        }
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + " kills by side " + battle.killsBy + " | Most kills: " + line);
    }

    /** Ends every running battle (duels, Chaos and team battles) with no winner. Returns how many. */
    public static int stopAll() {
        int n = 0;
        for (Battle battle : new ArrayList<>(BATTLES)) {
            finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.STOPPED, -2);
            n++;
        }
        return n;
    }

    /** Plugin shutdown or unload: every battle ends (wand battles too, so their mobs calm down if the worlds go on). */
    static void shutdownEvents() {
        for (Battle battle : new ArrayList<>(BATTLES)) {
            finish(battle, battle.snapshot(), Set.of(),
                    battle.anchor != null ? MobBattleApi.EndReason.SHUTDOWN : MobBattleApi.EndReason.STOPPED, -2);
        }
    }

    /** Empties every player's teams and removes the team look. Returns how many mobs were in a team. */
    public static int clearAllDrafts() {
        int n = 0;
        for (Draft draft : DRAFTS.values()) {
            for (List<Ref<EntityStore>> team : draft.teams) {
                for (Ref<EntityStore> npc : team) {
                    if (npc.isValid()) {
                        TeamFx.clear(npc);
                        n++;
                    }
                }
            }
        }
        DRAFTS.clear();
        draftMembers = false;
        return n;
    }

    public static boolean hasBattles() {
        return !BATTLES.isEmpty();
    }

    /**
     * RoleChange: {@code old} came back as {@code neu} (same mob, new Ref). It keeps its side and kills in every
     * battle; its target and the targets of those after it are picked again (the new role's AI starts fresh, and
     * currentTarget turns it, and them, against the nearest enemy).
     */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu) {
        boolean managed = MANAGED.remove(old);
        for (Battle b : BATTLES) {
            Integer team = b.teamOf.remove(old);
            if (team == null) continue;
            b.add(neu, team);
            b.targets.remove(old);
            b.targets.values().removeIf(t -> t == old);
            b.hitBy.remove(old);
            b.hitBy.values().removeIf(t -> t == old);
            b.turnedAt.remove(old);
            b.chaseFrom.remove(old);
            b.hitTarget.remove(old);
            b.canFight.remove(old);
            b.refocus.remove(old);
            Kills k = b.kills.remove(old);
            if (k != null) b.kills.put(neu, k);
            Double dealt = b.damageOf.remove(old);
            if (dealt != null) b.damageOf.put(neu, dealt);
            if (b.lastKiller == old) b.lastKiller = neu;
            managed = false;
        }
        // A retired mob (managed, in no battle) stays retired.
        if (managed) MANAGED.add(neu);
    }

    public static List<Battle> battles() {
        return BATTLES;
    }

    public static Battle battleOf(Ref<EntityStore> npc) {
        for (Battle b : BATTLES) {
            if (!b.over && b.has(npc)) {
                return b;
            }
        }
        return null;
    }

    /** True if both are in the same battle on different sides (their hits must never be filtered). */
    public static boolean areEnemies(Ref<EntityStore> x, Ref<EntityStore> y) {
        if (BATTLES.isEmpty() || !isManaged(x) || !isManaged(y)) {
            return false;
        }
        Battle b = battleOf(x);
        return b != null && b.has(y) && b.team(x) != b.team(y);
    }

    /** Pulls these NPCs out of any older battle, so each NPC only belongs to one. */
    public static void detach(List<Ref<EntityStore>> npcs) {
        for (Ref<EntityStore> npc : npcs) {
            if (npc != null && npc.isValid()) {
                TeamFx.clear(npc);
                DRAFTS.values().forEach(d -> d.remove(npc));
            }
        }
        // The newest wand order wins, but only for the mobs it touches: they leave their battle and
        // the rest keep fighting (anyone targeting them picks a new enemy, see currentTarget).
        for (Battle b : BATTLES) {
            List<Ref<EntityStore>> opponents = null;
            List<Ref<EntityStore>> left = null;
            for (Ref<EntityStore> npc : npcs) {
                if (!b.has(npc)) continue;
                if (opponents == null) {
                    opponents = b.snapshot();
                    left = new ArrayList<>();
                }
                if (!isPlayerFighter(npc)) FightManager.queueStop(npc, opponents);
                b.teamOf.remove(npc);
                b.targets.remove(npc);
                b.hitBy.remove(npc);
                b.turnedAt.remove(npc);
                b.chaseFrom.remove(npc);
                b.hitTarget.remove(npc);
                b.canFight.remove(npc);
                b.refocus.remove(npc);
                left.add(npc);
            }
            if (left == null) {
                continue;
            }
            // Fewer than 2 sides left: nothing to win, end it silently.
            long sides = b.teamOf.values().stream().distinct().count();
            if (sides < 2) {
                finish(b, opponents, Set.of(), MobBattleApi.EndReason.REPLACED, -2);
                MobBattlePlugin.log("Battle ended: its mobs got new wand orders");
            } else {
                // The battle goes on without them: what finish does for its members, only for the ones that left
                // (lone/streak buff, the lone target's crown). Its intro keeps filming the rest.
                for (Ref<EntityStore> r : left) Buffs.endBattle(r);
                if (b.kind == Kind.ALL_VS_ONE) WandFx.removeCrownsLater(left);
            }
        }
    }

    /** NPCs spawned while a battle runs (LookLoadSystem, AddReason.SPAWN) and when; pruned in maintain. */
    private static final Map<Ref<EntityStore>, Long> SPAWNED_AT = new ConcurrentHashMap<>();
    /** How long a spawn is remembered: a pet joins its owner's side within a few seconds of appearing. */
    private static final long SPAWN_MEMORY_MS = 60_000;

    /** An NPC was just spawned (not loaded): remembered only while some battle runs. */
    static void noteSpawn(Ref<EntityStore> npc) {
        if (!BATTLES.isEmpty()) SPAWNED_AT.put(npc, System.currentTimeMillis());
    }

    /**
     * If {@code npc} shares a flock with a battle member (a pet or summon spawned by it), adds it to
     * that member's side. Returns the battle it joined, or null.
     */
    /** Spawned while a battle runs (FightTickSystem asks first, so wild NPCs get no timer entry for joinOwnersSide). */
    static boolean recentlySpawned(Ref<EntityStore> npc) {
        return !SPAWNED_AT.isEmpty() && SPAWNED_AT.containsKey(npc);
    }

    public static Battle joinOwnersSide(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        // A previously assigned/retired mob must never silently rejoin its old flock's battle.
        if (isManaged(npc) || !isAlive(npc, buffer)) return null;
        // Only something spawned during a battle (a Trork Hunter's wolf, a Broodmother's young): pack animals join the
        // flock of any of their kind nearby, so onlooking hyenas, cave rexes, scarak lice and feran civilians were
        // pulled into a duel next to them (test calls, 2026-10-14).
        Long spawned = SPAWNED_AT.get(npc);
        if (spawned == null) return null;
        Ref<EntityStore> flock = com.hypixel.hytale.server.flock.FlockPlugin.getFlockReference(npc, buffer);
        if (flock == null || !flock.isValid()) {
            return null;
        }
        for (Battle b : BATTLES) {
            if (b.over || b.store != npc.getStore()) {
                continue;
            }
            for (Ref<EntityStore>[] pair : b.flocks) {
                Ref<EntityStore> other = pair[0], member = pair[1];
                if (member == npc || !b.has(member)) {
                    continue;
                }
                if (spawned < b.startedAt) continue;
                if (other == flock || (other.isValid() && other.getStore() == flock.getStore()
                        && other.getIndex() == flock.getIndex())) {
                    b.add(npc, b.team(member));
                    if (b.isTeams()) {
                        TeamFx.show(npc, b.color(b.team(member)));
                    }
                    MobBattlePlugin.log(ChaosWand.nameOf(npc, buffer) + " joined the battle on "
                            + ChaosWand.nameOf(member, buffer) + "'s side");
                    return b;
                }
            }
        }
        return null;
    }

    /** Starts/replaces a duel, or merges only the connected duels in accumulated mode. */
    static Battle startDuel(PlayerRef owner, Ref<EntityStore> a, Ref<EntityStore> b, boolean accumulate) {
        return startDuel(owner, null, a, b, accumulate);
    }

    static Battle startDuel(Battle.Anchor anchor, Ref<EntityStore> a, Ref<EntityStore> b) {
        return startDuel(null, anchor, a, b, false);
    }

    private static Battle startDuel(PlayerRef owner, Battle.Anchor anchor, Ref<EntityStore> a, Ref<EntityStore> b,
                                    boolean accumulate) {
        if (!accumulate) {
            Battle duel = startRoyale(owner, anchor, List.of(a, b));
            duel.kind = Kind.DUEL;
            return duel;
        }
        // Only explicitly connected Battle Wand duels merge; team and Chaos battles stay separate.
        Set<Battle> previous = new java.util.LinkedHashSet<>();
        List<Ref<EntityStore>> leaveOtherBattles = new ArrayList<>();
        for (Ref<EntityStore> selected : List.of(a, b)) {
            Battle old = battleOf(selected);
            if (old != null && old.kind == Kind.DUEL) previous.add(old);
            else if (old != null) leaveOtherBattles.add(selected);
        }
        detach(leaveOtherBattles);
        Battle duel = new Battle(owner, anchor, true, null);
        duel.kind = Kind.DUEL;
        int nextSide = 0;
        for (Battle old : previous) {
            Map<Integer, Integer> sides = new java.util.HashMap<>();
            for (Ref<EntityStore> member : old.snapshot()) {
                if (!member.isValid()) continue;
                Integer side = sides.get(old.team(member));
                if (side == null) {
                    side = nextSide++;
                    sides.put(old.team(member), side);
                }
                duel.add(member, side);
            }
            duel.targets.putAll(old.targets);
            duel.fallen.putAll(old.fallen);
            // Transfer membership without stopping the former participants or declaring a winner.
            old.over = true;
            BATTLES.remove(old);
            if (old.anchor != null && old.finishOnce.compareAndSet(false, true)) {
                MobBattleApi.terminal(old.anchor.eventId(), MobBattleApi.EndReason.REPLACED, List.of(), null);
            }
        }
        if (!duel.has(a)) duel.add(a, nextSide++);
        if (!duel.has(b)) duel.add(b, nextSide++);
        if (duel.team(a) == duel.team(b)) duel.add(b, nextSide);
        FightManager.endFightsOf(duel.snapshot());
        FightManager.cancelPendingStop(a);
        FightManager.cancelPendingStop(b);
        duel.targets.put(a, b);
        duel.targets.put(b, a);
        BATTLES.add(duel);
        return duel;
    }

    /**
     * A new Chaos Wand battle (everyone vs one, royale) ends, whole, every other everyone-vs-one or royale battle
     * that shares a mob with it: two chaos battles in the same area would fight over the same mobs. (detach alone
     * only takes the shared mobs out, leaving the rest of the old battle, and its looks, running.)
     */
    public static void endChaosBattlesOf(List<Ref<EntityStore>> npcs) {
        java.util.Set<Ref<EntityStore>> mine = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        mine.addAll(npcs);
        for (Battle b : new ArrayList<>(BATTLES)) {
            if (b.kind != Kind.ALL_VS_ONE && b.kind != Kind.ROYALE) continue;
            if (b.snapshot().stream().anyMatch(mine::contains)) {
                finish(b, b.snapshot(), Set.of(), MobBattleApi.EndReason.REPLACED, -2);
                MobBattlePlugin.log("Battle ended: a new Chaos Wand battle took its mobs");
            }
        }
    }

    /** Starts a battle royale: every NPC for itself. */
    public static Battle startRoyale(PlayerRef owner, List<Ref<EntityStore>> npcs) {
        return startRoyale(owner, null, npcs);
    }

    static Battle startRoyale(Battle.Anchor anchor, List<Ref<EntityStore>> npcs) {
        return startRoyale(null, anchor, npcs);
    }

    private static Battle startRoyale(PlayerRef owner, Battle.Anchor anchor, List<Ref<EntityStore>> npcs) {
        detach(npcs);
        FightManager.endFightsOf(npcs);
        Battle battle = new Battle(owner, anchor, true, null);
        // Every mob for itself, but an army (a whole chain of command, its owner too if they fight) is one side
        // (test 2026-10-14, playtest: army mobs in a royale fought each other).
        Map<java.util.UUID, Integer> armySide = new java.util.HashMap<>();
        int side = 0;
        for (Ref<EntityStore> npc : npcs) {
            java.util.UUID top = npc.isValid() ? Army.topOf(npc, Army.keyOf(npc, npc.getStore())) : null;
            Integer t = top != null ? armySide.get(top) : null;
            if (t == null) {
                t = side++;
                if (top != null) armySide.put(top, t);
            }
            battle.add(npc, t);
        }
        BATTLES.add(battle);
        return battle;
    }

    /** Chaos Wand focus: every attacker (allies, side 0) vs the target (side 1), so the end gets a winner title. */
    public static Battle startFocus(PlayerRef owner, Ref<EntityStore> target, String targetName,
                                    List<Ref<EntityStore>> attackers) {
        return startFocus(owner, null, target, targetName, attackers);
    }

    static Battle startFocus(Battle.Anchor anchor, Ref<EntityStore> target, String targetName,
                             List<Ref<EntityStore>> attackers) {
        return startFocus(null, anchor, target, targetName, attackers);
    }

    private static Battle startFocus(PlayerRef owner, Battle.Anchor anchor, Ref<EntityStore> target,
                                     String targetName, List<Ref<EntityStore>> attackers) {
        List<Ref<EntityStore>> all = new ArrayList<>(attackers);
        all.add(target);
        detach(all);
        FightManager.endFightsOf(all);
        Battle battle = new Battle(owner, anchor, false, targetName);
        for (Ref<EntityStore> npc : attackers) {
            battle.add(npc, 0);
            battle.targets.put(npc, target);
        }
        battle.add(target, 1);
        BATTLES.add(battle);
        return battle;
    }

    /** Starts a team battle. {@code teams.get(t)} = NPCs of team t. */
    public static Battle startTeams(PlayerRef owner, List<List<Ref<EntityStore>>> teams) {
        List<Ref<EntityStore>> all = new ArrayList<>();
        teams.forEach(all::addAll);
        detach(all);
        FightManager.endFightsOf(all);
        Battle battle = new Battle(owner, false);
        for (int t = 0; t < teams.size(); t++) {
            for (Ref<EntityStore> npc : teams.get(t)) {
                battle.add(npc, t);
                TeamFx.show(npc, battle.color(t));
            }
        }
        BATTLES.add(battle);
        return battle;
    }

    public static boolean isAlive(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        if (accessor.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return false;
        }
        EntityStatMap stats = accessor.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue health = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        return health == null || health.get() > 0;
    }

    /** Nearest living member of another side, or null. */
    public static Ref<EntityStore> nearestEnemy(Battle battle, Ref<EntityStore> self, ComponentAccessor<EntityStore> buffer) {
        Vector3d me = ChaosWand.positionOf(self, buffer);
        int myTeam = battle.team(self);
        Ref<EntityStore> best = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            Ref<EntityStore> other = e.getKey();
            if (other == self || e.getValue() == myTeam || !isAlive(other, buffer)) {
                continue;
            }
            double d = me == null ? Double.MAX_VALUE - 1 : ChaosWand.distSq(me, other, buffer);
            if (d < bestDist) {
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    /**
     * Chase limit: a mob that hit nobody for this long gives up its target for the best other enemy (see
     * {@link #reach}). A target used to be kept until it died, so a goblin chased a flying owl or a fleeing squirrel
     * along the arena wall for the whole battle (playtest 2026-10-12).
     */
    static final long CHASE_GIVE_UP_MS = 8000;

    /**
     * Target tiers, best first: an enemy that fights back, a passive one, the same two in another medium it can still
     * reach (a fish at the surface for an archer, 2026-10-14), one it can't reach right now.
     */
    private static final int FIGHTER = 0, PASSIVE = 1, OTHER_MEDIUM = 2, OUT_OF_REACH = 4;

    /**
     * How good {@code other} is as a target for {@code self}: out of reach when {@code self} can't fly and
     * {@code other} is flying right now, or when {@code other} is past the walled arena; else a fighter before a
     * passive mob (a royale skeleton went for a frog 36 blocks away with another skeleton 8 blocks from it, and stood
     * "confused" once the frog hopped out of sight; 2026-10-12). Players count as fighters.
     */
    private static int reach(Battle battle, Medium.Can self, Ref<EntityStore> other, ComponentAccessor<EntityStore> buffer) {
        Medium.Where there = Medium.where(other, buffer);
        // Flying while it can't fly or shoot, under deep water while it drowns, on land for a fish (Medium).
        if (!Medium.reaches(self, there)) return OUT_OF_REACH;
        if (Arena.outside(battle, ChaosWand.positionOf(other, buffer))) return OUT_OF_REACH;
        int tier = fights(battle, other, buffer) ? FIGHTER : PASSIVE;
        return Medium.same(self, there) ? tier : tier + OTHER_MEDIUM;
    }

    /**
     * True if {@code self} can reach some living enemy that fights back, where it is now (passiveWin). A passive one
     * does not count: a piranha that could reach only a wild fish won a royale against a wolf it could not reach
     * (test 2026-10-14).
     */
    static boolean reachesAnyEnemy(Battle battle, Ref<EntityStore> self, ComponentAccessor<EntityStore> buffer) {
        Medium.Can can = Medium.of(self, buffer);
        int myTeam = battle.team(self);
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            Ref<EntityStore> other = e.getKey();
            if (other == self || e.getValue() == myTeam || !isAlive(other, buffer) || !fights(battle, other, buffer)) continue;
            if (Medium.reaches(can, Medium.where(other, buffer))) return true;
        }
        return false;
    }

    /** True if {@code self} can reach {@code other} where it is now. */
    static boolean canReach(Ref<EntityStore> self, Ref<EntityStore> other, ComponentAccessor<EntityStore> buffer) {
        return Medium.reaches(Medium.of(self, buffer), Medium.where(other, buffer));
    }

    /** Whether a member can attack at all (FightManager.canAttack, asked once per battle); players always can. */
    private static boolean fights(Battle battle, Ref<EntityStore> member, ComponentAccessor<EntityStore> buffer) {
        if (isPlayerFighter(member)) return true;
        Boolean f = battle.canFight.get(member);
        if (f == null) {
            // Cows, deer and other prey have an attitude memory and a combat state, so canAttack says yes, yet they
            // never attack (test verify); a royale of them never reached passiveWin (test 2026-10-14).
            com.hypixel.hytale.server.npc.entities.NPCEntity npc = buffer.getComponent(member,
                    com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
            f = (npc == null || !PickLists.observedNoAttack(npc.getRoleName())) && FightManager.canAttack(member, buffer);
            battle.canFight.put(member, f);
        }
        return f;
    }

    /** passiveWin waits this long without an enemy hit (since the start or the last hit) before its countdown. */
    private static final long PASSIVE_GRACE_MS = 20_000;

    /**
     * /mobbattle passiveWin (checkWinner, once a second, world thread): when the sides still alive can't all fight
     * back (only one side, or none, has a member that can attack), the battle ends after passiveWin seconds without
     * a hit: that side wins, or a draw if none can fight. Meanwhile PassiveAlert shows who wins in how long. A
     * skeleton could never finish a royale against a passive duck flying out of its reach (playtest 2026-10-12).
     * Returns null while the battle goes on, else the winning side or -1 for a draw. The countdown waits
     * PASSIVE_GRACE_MS without an enemy hit first.
     */
    private static Integer passiveEnd(Battle battle, Set<Integer> aliveTeams, ComponentAccessor<EntityStore> buffer) {
        int seconds = FxSettings.passiveWinSeconds;
        Set<Integer> fighting = new HashSet<>();
        // Sides with a fighter that can reach some enemy where it is now (Medium: a fish and a wolf on land can't).
        Set<Integer> reaching = new HashSet<>();
        boolean dummy = false;
        if (seconds > 0 && aliveTeams.size() >= 2) {
            // One pass: the living fighters, and the sides standing in each medium (Where) - so each fighter's "can it
            // reach a fighting enemy" is a few lookups, not a scan of every member (audit 2026-10-14: 500 fish vs 500
            // walkers was ~250000 checks a second).
            List<Ref<EntityStore>> fighters = new ArrayList<>();
            Map<Medium.Where, Set<Integer>> sidesAt = new java.util.EnumMap<>(Medium.Where.class);
            for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                if (!isAlive(e.getKey(), buffer)) continue;
                // passiveWinIgnore (a training dummy): while one is alive the battle never ends this way.
                if (ignored(battle, e.getKey(), buffer)) {
                    dummy = true;
                    break;
                }
                if (!fights(battle, e.getKey(), buffer)) continue;
                fighting.add(e.getValue());
                fighters.add(e.getKey());
                sidesAt.computeIfAbsent(Medium.where(e.getKey(), buffer), k -> new HashSet<>()).add(e.getValue());
            }
            if (!dummy && fighting.size() > 1) {
                for (Ref<EntityStore> f : fighters) {
                    int team = battle.team(f);
                    if (reaching.contains(team)) continue;
                    Medium.Can can = Medium.of(f, buffer);
                    for (Map.Entry<Medium.Where, Set<Integer>> at : sidesAt.entrySet()) {
                        Set<Integer> there = at.getValue();
                        if ((there.size() > 1 || !there.contains(team)) && Medium.reaches(can, at.getKey())) {
                            reaching.add(team);
                            break;
                        }
                    }
                    if (reaching.size() > 1) break; // the battle goes on, nothing more to know
                }
            }
        }
        // Two or more sides that fight and reach each other: the battle goes on.
        if (seconds <= 0 || aliveTeams.size() < 2 || fighting.size() > 1 && reaching.size() > 1 || dummy) {
            stopPassiveAlert(battle);
            return null;
        }
        long now = System.currentTimeMillis();
        // Only after PASSIVE_GRACE_MS without an enemy hit (since the start or the last hit): the side that can attack
        // first gets the time to reach and kill a passive mob, and a walker the time to hit a flier that comes low
        // (playtest 2026-10-28: a Trork vs a passive mob and a Yeti vs a low Thunder Spirit got the countdown at once).
        if (now - battle.lastHit < PASSIVE_GRACE_MS) {
            stopPassiveAlert(battle);
            battle.passiveSince = 0;
            return null;
        }
        if (battle.passiveSince == 0) battle.passiveSince = now;
        long left = seconds * 1000L - (now - battle.passiveSince);
        // Nobody reaches anybody but several sides could fight: the side with the most health left wins (2026-10-14).
        // One side that fights (the rest passive): it wins, as before. Several that fight but only one reaches a
        // fighting enemy: that one. None reaches: the side with the most health left among those that fight.
        boolean unreach = fighting.size() > 1 && reaching.isEmpty();
        int side = fighting.size() <= 1 ? (fighting.isEmpty() ? -1 : fighting.iterator().next())
                : unreach ? healthiestSide(battle, fighting, buffer) : reaching.iterator().next();
        if (left <= 0) {
            stopPassiveAlert(battle);
            battle.passiveEnded = true;
            battle.unreachEnded = unreach;
            return side;
        }
        if (!battle.silent && battle.store != null) {
            Message who = side < 0 ? null : sideName(battle, side, buffer);
            // Several sides fight but only one reaches the others (a bat over zombies): not "only passive mobs".
            Message title = I18n.tr(unreach ? "battle.passive.title.unreach" : fighting.size() > 1 ? "battle.passive.title.oneway"
                    : side < 0 ? "battle.passive.title.nobody" : "battle.passive.title.only");
            Message text = side < 0 ? I18n.tr("battle.passive.text.draw")
                    : I18n.tr("battle.passive.text.wins").param("who", who);
            int secs = (int) Math.ceil(left / 1000.0);
            for (PlayerRef viewer : FightFx.titleViewers(battle)) {
                if (!viewer.isValid()) continue;
                battle.alertViewers.add(viewer.getUuid());
                PassiveAlert.show(viewer, battle.store, title, text, secs);
            }
        }
        return null;
    }

    /** The living side with the highest average health (percent of each member's max, the fallen at 0); -1 if none. */
    private static int healthiestSide(Battle battle, Set<Integer> aliveTeams, ComponentAccessor<EntityStore> buffer) {
        Map<Integer, double[]> sides = new java.util.HashMap<>();
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            if (!aliveTeams.contains(e.getValue())) continue;
            double[] v = sides.computeIfAbsent(e.getValue(), k -> new double[2]);
            v[0] += healthFraction(e.getKey(), buffer);
            v[1]++;
        }
        int best = -1;
        double bestScore = -1;
        for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
            double score = e.getValue()[1] > 0 ? e.getValue()[0] / e.getValue()[1] : 0;
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * /mobbattle idleEnd (checkWinner, once a second): no enemy hit for idleEnd seconds since the start or the last
     * hit = a draw (2026-10-13: a royale of defensive animals that never start a fight stood still for its whole
     * timer). passiveWin, when it applies, is shorter and says who wins; this one catches mobs that could fight but
     * don't. A passiveWinIgnore dummy alive keeps the battle going, as for passiveWin.
     */
    private static boolean idleEnd(Battle battle, ComponentAccessor<EntityStore> buffer) {
        int seconds = FxSettings.idleEndSeconds;
        if (seconds <= 0 || System.currentTimeMillis() - battle.lastHit < seconds * 1000L) return false;
        for (Ref<EntityStore> member : battle.teamOf.keySet()) {
            if (isAlive(member, buffer) && ignored(battle, member, buffer)) return false;
        }
        battle.idleEnded = true;
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": no enemy hit for " + seconds + " s, draw (idleEnd)");
        return true;
    }

    /** passiveWinIgnore: a member whose role is on the list (asked through the role name, NPCs only). */
    private static boolean ignored(Battle battle, Ref<EntityStore> member, ComponentAccessor<EntityStore> buffer) {
        if (FxSettings.passiveWinIgnore.isEmpty() || isPlayerFighter(member)) return false;
        com.hypixel.hytale.server.npc.entities.NPCEntity npc = buffer.getComponent(member,
                com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType());
        return npc != null && FxSettings.passiveWinIgnored(npc.getRoleName());
    }

    /** Name of a side for the passiveWin alert: the mob in a royale, "<name> team", the lone target or "The attackers". */
    private static Message sideName(Battle battle, int side, ComponentAccessor<EntityStore> buffer) {
        if (battle.focusName != null) return side == 1 ? MobNames.message(battle.focusName) : I18n.tr("battle.side.attackers");
        if (battle.royale) {
            for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                if (e.getValue() != side || !isAlive(e.getKey(), buffer)) continue;
                String army = armyName(e.getKey(), buffer);
                return army != null ? I18n.tr("battle.side.army").param("name", MobNames.message(army)) : MobNames.message(ChaosWand.nameOf(e.getKey(), buffer));
            }
        }
        return I18n.tr("battle.side.team").param("name", battle.name(side));
    }

    private static void stopPassiveAlert(Battle battle) {
        battle.passiveSince = 0;
        if (!battle.alertViewers.isEmpty()) PassiveAlert.hideAll(battle.alertViewers);
    }

    /** The nearest living enemy in the best tier ({@link #reach}), other than {@code skip}; null if none. */
    private static Ref<EntityStore> bestEnemy(Battle battle, Ref<EntityStore> self, Ref<EntityStore> skip,
                                              ComponentAccessor<EntityStore> buffer) {
        Vector3d me = ChaosWand.positionOf(self, buffer);
        int myTeam = battle.team(self);
        Medium.Can can = Medium.of(self, buffer);
        Ref<EntityStore> best = null;
        int bestTier = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            Ref<EntityStore> other = e.getKey();
            if (other == self || other == skip || e.getValue() == myTeam || !isAlive(other, buffer)) continue;
            int tier = reach(battle, can, other, buffer);
            if (tier > bestTier) continue;
            double d = me == null ? Double.MAX_VALUE - 1 : ChaosWand.distSq(me, other, buffer);
            if (tier < bestTier || d < bestDist) {
                bestTier = tier;
                bestDist = d;
                best = other;
            }
        }
        return best;
    }

    /** A target farther than this may be swapped for a fighter right beside the mob ({@link #nearerEnemy}). */
    private static final double SWITCH_FROM = 8;
    /** "Right beside": an enemy fighter this close. */
    private static final double BESIDE = 4;
    /** nearerEnemy runs for each mob once every this many seconds. */
    private static final long NEAR_EVERY = 3;

    /**
     * An enemy fighter within {@link #BESIDE} while {@code self} still walks to a target farther than
     * {@link #SWITCH_FROM}; null if none, or if {@code self} hit its target in the last SELF_DEFENSE_MS. In a royale of
     * 500 Kweebecs mobs walked past enemies right beside them to the one picked at the start (playtest 2026-10-13).
     * Duels and all-vs-one attackers have no other enemy. One cheap distance pass per mob and second, only when far.
     */
    private static Ref<EntityStore> nearerEnemy(Battle battle, Ref<EntityStore> self, Ref<EntityStore> target, long now,
                                                ComponentAccessor<EntityStore> buffer) {
        // Only royale and teams have other enemies to pick; an all-vs-one of 1000 would scan 1000 x 1000 a second.
        if (!battle.royale && !battle.isTeams()) return null;
        // Each mob looks every NEAR_EVERY seconds, staggered by its index: a 1000-mob royale is ~330 scans a second.
        if (Math.floorMod(self.getIndex() + now / 1000, NEAR_EVERY) != 0) return null;
        Long hit = battle.hitTarget.get(self);
        if (hit != null && now - hit < SELF_DEFENSE_MS) return null;
        Vector3d me = ChaosWand.positionOf(self, buffer);
        if (me == null || ChaosWand.distSq(me, target, buffer) <= SWITCH_FROM * SWITCH_FROM) return null;
        int myTeam = battle.team(self);
        Ref<EntityStore> best = null;
        double bestDist = BESIDE * BESIDE;
        // Only what stands within BESIDE (audit 2026-10-14: a scan of every fighter was ~8 million reads a second in a
        // 5000-mob royale). The sphere list is thread-local: copied, since reach() below reads more of the world.
        List<Ref<EntityStore>> near = new ArrayList<>(com.hypixel.hytale.server.core.util.TargetUtil
                .getAllEntitiesInSphere(me, BESIDE, buffer));
        Medium.Can can = null;
        for (Ref<EntityStore> other : near) {
            if (other == null || other == self || other == target || !other.isValid()) continue;
            Integer team = battle.teamOf.get(other);
            if (team == null || team == myTeam) continue;
            double d = ChaosWand.distSq(me, other, buffer);
            if (d >= bestDist || !isAlive(other, buffer)) continue;
            if (can == null) can = Medium.of(self, buffer);
            if (reach(battle, can, other, buffer) != FIGHTER) continue;
            best = other;
            bestDist = d;
        }
        if (best != null && MobBattlePlugin.debugging()) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8)
                + ": " + ChaosWand.nameOf(self, buffer) + " leaves " + ChaosWand.nameOf(target, buffer) + " (far) for "
                + ChaosWand.nameOf(best, buffer) + " beside it");
        return best;
    }

    /** The NPC's current target, picking a new one if it has none, it died, or the chase went on too long. */
    public static Ref<EntityStore> currentTarget(Battle battle, Ref<EntityStore> self, ComponentAccessor<EntityStore> buffer,
                                                 boolean[] changed) {
        Ref<EntityStore> target = battle.targets.get(self);
        long now = System.currentTimeMillis();
        if (target != null && battle.has(target) && isAlive(target, buffer)) {
            Long from = battle.chaseFrom.putIfAbsent(self, now);
            boolean giveUp = from != null && now - from >= CHASE_GIVE_UP_MS;
            // Out of reach now (flown off, past the wall): any enemy it can reach goes first, without waiting.
            boolean outOfReach = !giveUp && reach(battle, Medium.of(self, buffer), target, buffer) == OUT_OF_REACH;
            if (!giveUp && !outOfReach) {
                Ref<EntityStore> beside = nearerEnemy(battle, self, target, now, buffer);
                if (beside == null) return target;
                battle.targets.put(self, beside);
                battle.chaseFrom.put(self, now);
                battle.turnedAt.put(self, now); // a stray hit from farther away can't pull it off at once
                changed[0] = true;
                return beside;
            }
            Ref<EntityStore> other = bestEnemy(battle, self, target, buffer);
            // Never swap for one it can't reach: after the chase limit a wolf left a wolf for a piranha out at sea,
            // dropped it a second later as out of reach, and so on (test 2026-10-14).
            if (other != null && reach(battle, Medium.of(self, buffer), other, buffer) == OUT_OF_REACH) {
                other = null;
            }
            if (giveUp) battle.chaseFrom.put(self, now);
            if (other == null) return target;
            if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": "
                    + ChaosWand.nameOf(self, buffer) + " gave up on " + ChaosWand.nameOf(target, buffer)
                    + (giveUp ? " after " + CHASE_GIVE_UP_MS / 1000 + " s without a hit" : " (out of reach)")
                    + ", goes for " + ChaosWand.nameOf(other, buffer));
            battle.targets.put(self, other);
            battle.chaseFrom.put(self, now);
            changed[0] = true;
            return other;
        }
        Ref<EntityStore> lost = target;
        target = bestEnemy(battle, self, null, buffer);
        if (target != null) {
            battle.targets.put(self, target);
            battle.chaseFrom.put(self, now);
            changed[0] = true;
            if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": "
                    + ChaosWand.nameOf(self, buffer) + " targets " + ChaosWand.nameOf(target, buffer) + " ("
                    + (lost == null ? "had none" : "previous one gone") + ")");
        } else {
            battle.targets.remove(self);
        }
        return target;
    }

    /** selfDefense: after turning on an attacker, a mob keeps it at least this long (another hit can't pull it away). */
    private static final long SELF_DEFENSE_MS = 3000;
    /**
     * selfDefense: a mob that can't fly ignores a hit from an enemy flying farther than this (a ranged flier it could
     * never reach). A flier that dives in to hit is closer, so it is answered; if it flies off again, the chase
     * limit lets it go after CHASE_GIVE_UP_MS (playtest 2026-10-12: melee goblin vs flying owl).
     */
    private static final double SELF_DEFENSE_FLIER_REACH = 4.0;

    /**
     * selfDefense (FightTickSystem, world thread): the enemy that hit {@code self} becomes its target, unless it
     * already is, it is gone, or {@code self} turned on someone less than {@link #SELF_DEFENSE_MS} ago while that
     * one is still standing. Like Army.defend for Army Wand mobs. Returns true if the target changed.
     */
    static boolean selfDefense(Battle battle, Ref<EntityStore> self, ComponentAccessor<EntityStore> buffer) {
        Ref<EntityStore> by = battle.hitBy.remove(self);
        if (by == null || by == self || !FxSettings.selfDefense(battle.kind) || !by.isValid() || !battle.has(by)
                || battle.team(by) == battle.team(self) || !isAlive(by, buffer)) return false;
        Ref<EntityStore> now = battle.targets.get(self);
        if (now == by) return false;
        long t = System.currentTimeMillis();
        Long last = battle.turnedAt.get(self);
        if (last != null && t - last < SELF_DEFENSE_MS && now != null && battle.has(now) && isAlive(now, buffer)) return false;
        // Busy hitting its own target: it finishes that fight. In a royale crowd every stray hit pulled it away, so
        // trorks switched every couple of seconds and hit nobody (playtest 2026-10-12).
        // Not when that target is up or down another level (2026-10-29, playtest video: dummies hitting one stranded on a
        // wooden tower took hits from the others and never answered).
        Long hit = battle.hitTarget.get(self);
        if (hit != null && t - hit < SELF_DEFENSE_MS && now != null && battle.has(now) && isAlive(now, buffer)
                && !otherLevel(self, now, buffer)) return false;
        // An attacker it can't get at (a flier, a fish under water) is answered only when it comes close.
        if (now != null && !canReach(self, by, buffer)) {
            Vector3d me = ChaosWand.positionOf(self, buffer);
            double d = me == null ? Double.MAX_VALUE : Math.sqrt(ChaosWand.distSq(me, by, buffer));
            if (d > SELF_DEFENSE_FLIER_REACH) {
                if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": "
                        + ChaosWand.nameOf(self, buffer) + " ignores a hit from " + ChaosWand.nameOf(by, buffer) + " (" + Medium.where(by, buffer).name().toLowerCase(java.util.Locale.ROOT) + ", "
                        + String.format(java.util.Locale.ROOT, "%.1f", d) + " blocks away, out of reach)");
                return false;
            }
        }
        battle.targets.put(self, by);
        battle.turnedAt.put(self, t);
        battle.chaseFrom.put(self, t);
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": "
                + ChaosWand.nameOf(self, buffer) + " defends itself against " + ChaosWand.nameOf(by, buffer));
        return true;
    }

    /** selfDefense: a target this many blocks above or below no longer keeps a mob "busy". */
    private static final double BUSY_LEVEL_GAP = 2.5;

    private static boolean otherLevel(Ref<EntityStore> a, Ref<EntityStore> b, ComponentAccessor<EntityStore> buffer) {
        Vector3d pa = ChaosWand.positionOf(a, buffer), pb = ChaosWand.positionOf(b, buffer);
        return pa != null && pb != null && Math.abs(pa.y - pb.y) > BUSY_LEVEL_GAP;
    }

    /** Sets the first target of an NPC (used at battle start). */
    public static void setTarget(Battle battle, Ref<EntityStore> self, Ref<EntityStore> target) {
        battle.targets.put(self, target);
    }

    /** Ends the battle and announces the winner once only one side has living members. */
    public static void checkWinner(Battle battle, ComponentAccessor<EntityStore> buffer) {
        if (battle.over) {
            return;
        }
        if (battle.abandoned) {
            // Fighters unloaded with their chunks (no player near): they didn't die, so nobody wins.
            finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.UNLOAD, -1);
            MobBattlePlugin.log("Battle cancelled: fighters were unloaded");
            if (!battle.silent && battle.owner != null && battle.owner.isValid()) {
                battle.owner.sendMessage(I18n.tr("battle.cancelled.unloaded").color("#aaaaaa"));
            }
            return;
        }
        Set<Integer> aliveTeams = new HashSet<>();
        Ref<EntityStore> lastAlive = null;
        int alive = 0;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            Ref<EntityStore> npc = e.getKey();
            if (isAlive(npc, buffer)) {
                aliveTeams.add(e.getValue());
                lastAlive = npc;
                alive++;
            }
        }
        boolean expired = System.currentTimeMillis() > battle.expiresAt;
        BattleOutcome outcome = BattleOutcome.resolve(aliveTeams.size(), expired);
        if (outcome == BattleOutcome.ONGOING) {
            Integer forced = passiveEnd(battle, aliveTeams, buffer);
            if (forced == null && idleEnd(battle, buffer)) forced = -1;
            if (forced == null) return;
            if (forced < 0) {
                outcome = BattleOutcome.EMPTY;
                battle.eventEndReason = MobBattleApi.EndReason.DRAW;
            } else {
                // Only the side that can fight counts as standing: the passive ones are left out of the result.
                aliveTeams.retainAll(Set.of(forced));
                alive = 0;
                for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                    if (e.getValue() == (int) forced && isAlive(e.getKey(), buffer)) {
                        lastAlive = e.getKey();
                        alive++;
                    }
                }
                outcome = BattleOutcome.WIN;
            }
        } else {
            stopPassiveAlert(battle);
        }
        if (battle.eventEndReason == null) {
            battle.eventEndReason = battle.abandoned ? MobBattleApi.EndReason.UNLOAD
                    : outcome == BattleOutcome.TIMEOUT ? MobBattleApi.EndReason.TIMEOUT
                    : outcome == BattleOutcome.WIN ? MobBattleApi.EndReason.WIN : MobBattleApi.EndReason.DRAW;
        }
        if (outcome != BattleOutcome.WIN && battle.eventEndReason == MobBattleApi.EndReason.TIMEOUT
                && battle.eventWinnerSide >= 0) {
            battle.eventWinnerSide = -1;
            battle.eventWinners = List.of();
        }
        if (outcome == BattleOutcome.WIN && battle.eventWinnerSide == -2) {
            battle.eventWinnerSide = aliveTeams.iterator().next();
            battle.eventWinners = winnerUuids(battle, battle.eventWinnerSide, buffer);
        }
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + " over: " + outcome + ", sides alive " + aliveTeams
                + ", fighters alive " + alive + ", damage by side " + battle.damageBy + (expired ? " (battleMaxTime)" : ""));
        Set<Ref<EntityStore>> keepLook = new HashSet<>();
        if (outcome == BattleOutcome.WIN && battle.isTeams()) {
            int side = aliveTeams.iterator().next();
            for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                if (e.getValue() == side && !isPlayerFighter(e.getKey()) && isAlive(e.getKey(), buffer)) keepLook.add(e.getKey());
            }
        }
        // Already ended from another thread (/mobbattle stop, a new order): no champion, no end messages.
        if (!finish(battle, battle.snapshot(), keepLook, battle.eventEndReason, battle.eventWinnerSide)) return;
        // /mobbattle buff champion: the winners keep an edge (after finish, which took their battle buffs).
        if (outcome == BattleOutcome.WIN && Buffs.enabled(Buffs.Source.CHAMPION)) {
            int side = aliveTeams.iterator().next();
            for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                if (e.getValue() == side && isAlive(e.getKey(), buffer)) Buffs.set(e.getKey(), Buffs.Source.CHAMPION, 1);
            }
        }
        if (battle.silent) {
            MobBattlePlugin.log("Battle over after its time was up (" + outcome + ")");
            return;
        }

        Txt main;
        Txt sub;
        String color;
        if (outcome == BattleOutcome.EMPTY || outcome == BattleOutcome.TIMEOUT) {
            main = Txt.tr("battle.draw", "DRAW!");
            if (outcome == BattleOutcome.TIMEOUT) sub = Txt.tr("battle.sub.timeup", "Time is up");
            else if (battle.idleEnded) {
                String time = MobBattleCommand.formatDuration(FxSettings.idleEndSeconds);
                sub = new Txt("Nobody fought for " + time, I18n.tr("battle.sub.nobody.fought").param("time", time));
            } else if (battle.passiveEnded) sub = Txt.tr("battle.sub.nobody.can", "Nobody left can fight");
            else sub = Txt.tr("battle.sub.nobody.standing", "Nobody is left standing");
            color = "#aaaaaa";
        } else if (battle.focusName != null) {
            boolean targetWon = aliveTeams.contains(1);
            main = targetWon ? winTxt(battle.focusName)
                    : new Txt(battle.focusName + " IS DEFEATED!", I18n.tr("battle.defeated").param("name", MobNames.message(battle.focusName)));
            if (battle.passiveEnded) {
                sub = targetWon ? Txt.tr("battle.sub.attackers.cant", "The attackers can't fight")
                        : Txt.tr("battle.sub.cant.back", "Couldn't fight back");
            } else if (targetWon) sub = Txt.tr("battle.sub.survived", "Survived everyone");
            else sub = new Txt(alive + " attackers left standing",
                    I18n.tr("battle.sub.attackers.left").param("count", alive));
            color = targetWon ? "#ffd23e" : "#ff6b5e";
        } else if (battle.royale) {
            main = royaleWinTxt(lastAlive, buffer);
            sub = battle.unreachEnded ? Txt.tr("battle.sub.unreach", "Nobody could reach the others: most health left")
                    : battle.passiveEnded ? Txt.tr("battle.sub.rest.cant", "The rest can't fight back")
                    : battle.teamOf.size() == 2 ? Txt.tr("battle.sub.duel.won", "Duel won")
                    : Txt.tr("battle.sub.last.standing", "Last one standing");
            color = "#ffd23e";
        } else {
            int team = aliveTeams.iterator().next();
            main = teamWinTxt(battle.name(team));
            sub = battle.passiveEnded ? Txt.tr("battle.sub.rest.cant", "The rest can't fight back")
                    : new Txt(alive + " left standing", I18n.tr("battle.sub.left.standing").param("count", alive));
            color = battle.color(team);
        }
        // endCamera winner / lasthit: film that fighter, and say its kill count.
        Ref<EntityStore> focus = null;
        String endMode = KindFx.endCamera(battle.kind);
        if (outcome == BattleOutcome.WIN && !"off".equals(endMode)) {
            focus = endFocus(battle, endMode, aliveTeams, lastAlive, buffer);
            if (focus != null) {
                Kills k = battle.kills.get(focus);
                int n = k != null ? k.count() : 0;
                String who = ChaosWand.nameOf(focus, buffer);
                boolean lasthit = "lasthit".equals(endMode);
                sub = new Txt(sub.en() + " | " + (lasthit ? "Final blow: " : "") + who + ", " + n + (n == 1 ? " kill" : " kills"),
                        Message.join(sub.msg(), Message.raw(" | "),
                                I18n.tr(lasthit ? "battle.sub.lasthit" : "battle.sub.kills").param("name", MobNames.message(who)).param("n", n)));
            }
        }
        MobBattlePlugin.log("Battle over: " + main.en() + " (" + sub.en() + ")");
        for (PlayerRef viewer : messageViewers(battle)) {
            if (viewer.isValid()) viewer.sendMessage(Message.join(main.msg(), Message.raw(" "), sub.msg()).color(color));
        }
        FightFx.winnerTitle(battle, main.msg(), sub.msg());
        if (focus != null) FightCamera.ending(battle, focus, buffer);
        // endSlowMo: a win, or a draw where the last ones fell together (2026-10-29, playtest: no slow motion on a draw);
        // not the draws where nobody fought (passiveWin, idleEnd)
        if (outcome == BattleOutcome.WIN || (outcome == BattleOutcome.EMPTY || outcome == BattleOutcome.TIMEOUT)
                && !battle.passiveEnded && !battle.idleEnded) {
            SlowMo.afterLastHit(battle);
        }
    }

    /**
     * Who the end camera films. lasthit: the fighter that scored the latest kill (the final blow), if still
     * standing. Else the winner: the last one standing (duel, royale), or the winning side's top killer still
     * standing (everyone vs one, teams). Null if there is nobody to show.
     */
    private static Ref<EntityStore> endFocus(Battle battle, String mode, Set<Integer> aliveTeams, Ref<EntityStore> lastAlive,
                                             ComponentAccessor<EntityStore> buffer) {
        Ref<EntityStore> last = battle.lastKiller;
        if ("lasthit".equals(mode) && last != null && isAlive(last, buffer)) return last;
        if (aliveTeams.size() != 1) return null;
        if (battle.royale) return lastAlive;
        int team = aliveTeams.iterator().next();
        // The first of the kill table among that side's standing fighters; nobody scored = any standing one.
        Ref<EntityStore> any = null;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            if (e.getValue() == team && isAlive(e.getKey(), buffer)) {
                any = e.getKey();
                break;
            }
        }
        return battle.kills.entrySet().stream().sorted(battle.ranking())
                .map(Map.Entry::getKey)
                .filter(r -> battle.team(r) == team && isAlive(r, buffer))
                .findFirst().orElse(any);
    }

    /** The arena timer reached 0 (Arena.tick): draw, kill everyone, keep fighting, or the healthiest side wins. */
    static void timeUp(Battle battle, Arena.TimeUp rule, ComponentAccessor<EntityStore> buffer) {
        if (battle.over) return;
        switch (rule) {
            case CONTINUE -> {
                battle.silent = true;
                announce(battle, Txt.tr("battle.timesup", "TIME'S UP!"), Txt.tr("battle.sub.goes.on", "The fight goes on"), "#aaaaaa");
            }
            case KILL -> {
                battle.silent = true;
                battle.killAll = true;
                battle.eventEndReason = MobBattleApi.EndReason.TIMEOUT;
                battle.eventWinnerSide = -1;
                announce(battle, Txt.tr("battle.timesup", "TIME'S UP!"), Txt.tr("battle.sub.nobody.survives", "Nobody survives"), "#ff6b5e");
            }
            case DRAW -> {
                finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.TIMEOUT, -1);
                announce(battle, Txt.tr("battle.draw", "DRAW!"), Txt.tr("battle.sub.timeup", "Time is up"), "#aaaaaa");
            }
            case HEALTH -> healthWinner(battle, false, buffer);
            case JUDGE -> healthWinner(battle, true, buffer);
        }
        MobBattlePlugin.log("Battle time up: " + rule);
    }

    /**
     * timeUp health/judge, scored by judgeBy (per kind):
     * <ul>
     * <li>health: the average of the members' remaining health in percent of their own max (the fallen count as
     * 0%), so a 1000 HP mob doesn't win just for being big;</li>
     * <li>damage / kills: the side's share of all damage / kills, each side divided by its size first (20 attackers
     * don't win against one target just for being 20);</li>
     * <li>mix: the three averaged.</li>
     * </ul>
     * Highest score wins; within half a percent it goes to tieBreak.
     */
    private static void healthWinner(Battle battle, boolean judge, ComponentAccessor<EntityStore> buffer) {
        Map<Integer, double[]> sides = new java.util.HashMap<>();   // team -> {sum of health fractions, members}
        Map<Integer, Ref<EntityStore>> anyMember = new java.util.HashMap<>();
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            double[] s = sides.computeIfAbsent(e.getValue(), k -> new double[2]);
            s[0] += healthFraction(e.getKey(), buffer);
            s[1]++;
            anyMember.putIfAbsent(e.getValue(), e.getKey());
        }
        String by = FxSettings.judgeBy(battle.kind);
        double damageTotal = 0, killTotal = 0;
        for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
            double members = Math.max(1, e.getValue()[1]);
            damageTotal += battle.damageBy.getOrDefault(e.getKey(), 0.0) / members;
            killTotal += battle.killsBy.getOrDefault(e.getKey(), 0) / members;
        }
        Map<Integer, Double> scores = new java.util.HashMap<>();
        for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
            double members = Math.max(1, e.getValue()[1]);
            double health = e.getValue()[1] > 0 ? e.getValue()[0] / e.getValue()[1] : 0;
            double damage = damageTotal > 0 ? battle.damageBy.getOrDefault(e.getKey(), 0.0) / members / damageTotal : 0;
            double kills = killTotal > 0 ? battle.killsBy.getOrDefault(e.getKey(), 0) / members / killTotal : 0;
            scores.put(e.getKey(), switch (by) {
                case "damage" -> damage;
                case "kills" -> kills;
                case "mix" -> (health + damage + kills) / 3;
                default -> health;
            });
        }
        // Royale: hiding does not win (2026-10-29, playtest: at time up two dummies that never fought, 100 % health and no
        // damage, tied and drew over the one that fought, 10 % health, 164 damage, 3 kills). Once a side still standing
        // has dealt damage, the standing sides that dealt none score 0.
        if (battle.kind == Kind.ROYALE) {
            boolean anyFought = false;
            for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
                if (e.getValue()[0] > 0 && battle.damageBy.getOrDefault(e.getKey(), 0.0) > 0) anyFought = true;
            }
            if (anyFought) {
                for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
                    if (battle.damageBy.getOrDefault(e.getKey(), 0.0) <= 0) scores.put(e.getKey(), 0.0);
                }
            }
        }
        int best = -1;
        double bestScore = -1, second = -1;
        for (Map.Entry<Integer, Double> e : scores.entrySet()) {
            double score = e.getValue();
            if (score > bestScore) {
                second = bestScore;
                bestScore = score;
                best = e.getKey();
            } else if (score > second) {
                second = score;
            }
        }
        if (MobBattlePlugin.debugging()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, double[]> e : sides.entrySet()) {
                double[] v = e.getValue();
                sb.append(" | side ").append(e.getKey()).append(": ")
                        .append(Math.round((v[1] > 0 ? v[0] / v[1] : 0) * 1000) / 10.0).append("% of ")
                        .append((int) v[1]).append(" fighters, damage ")
                        .append(Math.round(battle.damageBy.getOrDefault(e.getKey(), 0.0)))
                        .append(", kills ").append(battle.killsBy.getOrDefault(e.getKey(), 0))
                        .append(", score ").append(Math.round(scores.get(e.getKey()) * 1000) / 10.0);
            }
            MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + " time up (" + (judge ? "judge" : "health")
                    + ", judgeBy " + by + "):" + sb);
        }
        Txt tie = null;
        if (best >= 0 && bestScore > 0 && bestScore - second < TIE) {
            // Even on health: /mobbattle tieBreak damage (who dealt more), random, or draw.
            java.util.List<Integer> tied = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                if (bestScore - e.getValue() < TIE) tied.add(e.getKey());
            }
            best = breakTie(battle, tied);
            String rule = FxSettings.tieBreak(battle.kind);
            MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": tie between sides " + tied + ", tieBreak "
                    + rule + (rule.equals(KindFx.get(battle.kind, KindFx.Opt.TIE_BREAK)) ? "" : " (" + KindFx.get(battle.kind, KindFx.Opt.TIE_BREAK) + " = judgeBy)") + " -> " + (best < 0 ? "draw" : "side " + best));
            tie = best < 0 ? null : "damage".equals(rule) ? Txt.tr("battle.tie.damage", "more damage dealt")
                    : "kills".equals(rule) ? Txt.tr("battle.tie.kills", "more kills") : Txt.tr("battle.tie.coin", "a coin toss");
        }
        if (best < 0 || bestScore <= 0) {
            finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.TIMEOUT, -1);
            announce(battle, Txt.tr("battle.draw", "DRAW!"),
                    new Txt("Time is up, even on " + judgeWord(by),
                            I18n.tr("battle.sub.time.even").param("what", judgeMessage(by))), "#aaaaaa");
            SlowMo.afterLastHit(battle);
            return;
        }
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": side " + best + " wins"
                + (judge ? ", the other sides are disintegrated, the winners healed" : ""));
        if (judge) {
            // timeUp judge: the losers are disintegrated, the winners healed; the battle then ends by itself
            // (one side left) without a second title.
            battle.silent = true;
            battle.survivorTeam = best;
            battle.eventEndReason = MobBattleApi.EndReason.TIMEOUT;
            battle.eventWinnerSide = best;
            battle.eventWinners = winnerUuids(battle, best, buffer);
            battle.killAll = true;
            for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                if (e.getValue() != best || !isAlive(e.getKey(), buffer)) continue;
                EntityStatMap stats = buffer.getComponent(e.getKey(), EntityStatMap.getComponentType());
                if (stats != null) stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
                WandFx.onTarget(e.getKey(), WandFx.HEAL, buffer);
            }
        } else {
            battle.eventWinners = winnerUuids(battle, best, buffer);
            finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.TIMEOUT, best);
        }
        Txt winMain;
        String color = "#ffd23e";
        if (battle.focusName != null) {
            winMain = best == 1 ? winTxt(battle.focusName) : Txt.tr("battle.win.attackers", "THE ATTACKERS WIN!");
        } else if (battle.royale) {
            winMain = royaleWinTxt(anyMember.get(best), buffer);
        } else {
            winMain = teamWinTxt(battle.name(best));
            color = battle.color(best);
        }
        long pct = Math.round(bestScore * 100);
        long otherPct = Math.round(Math.max(0, second) * 100);
        Txt why;
        if (tie != null) {
            why = new Txt("even on " + judgeWord(by) + " (" + pct + "%), won by " + tie.en(),
                    I18n.tr("battle.why.tie").param("what", judgeMessage(by)).param("pct", pct).param("by", tie.msg()));
        } else {
            String en = pct + "% " + switch (by) {
                case "damage" -> "of the damage";
                case "kills" -> "of the kills";
                case "mix" -> "score";
                default -> "health left";
            } + " vs " + otherPct + "%";
            why = new Txt(en, I18n.tr("battle.why." + switch (by) {
                case "damage", "kills", "mix" -> by;
                default -> "health";
            }).param("pct", pct).param("other", otherPct));
        }
        announce(battle, winMain, new Txt((judge ? "Time is up: the losers fall. " : "Time is up: ") + why.en(),
                I18n.tr(judge ? "battle.timeup.judge" : "battle.timeup.why").param("why", why.msg())), color);
        // endSlowMo on a win by time too (2026-10-29, playtest: a royale won when time ran out had none): this path never
        // reaches checkWinner's call (judge goes silent, health finishes here)
        SlowMo.afterLastHit(battle);
    }

    /** A line of battle text: English for the server log, the translated Message for the players. */
    record Txt(String en, Message msg) {
        static Txt tr(String key, String en) {
            return new Txt(en, I18n.tr(key));
        }
    }

    /** "NAME WINS!" with a name that can be anything (mob, lone target). */
    private static Txt winTxt(String name) {
        name = String.valueOf(name);
        return new Txt(name + " WINS!", I18n.tr("battle.win").param("name", MobNames.message(name)));
    }

    /**
     * Royale winner title: the mob, or its army when it is one (test 2026-10-14: a Chieftain's army won and the title
     * named one of its goblins). The army is named after the top of its chain: a player, or a leader mob's role.
     */
    private static Txt royaleWinTxt(Ref<EntityStore> winner, ComponentAccessor<EntityStore> buffer) {
        String army = armyName(winner, buffer);
        if (army == null) return winTxt(ChaosWand.nameOf(winner, buffer));
        return new Txt(army + "'s ARMY WINS!", I18n.tr("battle.win.army").param("name", MobNames.message(army)));
    }

    /** The name of the army {@code member} fights for (its chain's top: a player or a leader mob), or null. */
    static String armyName(Ref<EntityStore> member, ComponentAccessor<EntityStore> buffer) {
        if (member == null || !member.isValid()) return null;
        java.util.UUID top = Army.topOf(member, Army.keyOf(member, buffer));
        if (top == null) return null;
        // a player is the top of its own chain: alone it is not an army (2026-10-29, playtest: a player won a duel as
        // "DACKORO'S ARMY WINS!")
        PlayerRef self = buffer.getComponent(member, PlayerRef.getComponentType());
        if (self != null && top.equals(self.getUuid()) && Army.members(top).isEmpty()) return null;
        Ref<EntityStore> leader = Army.entityOf(top);
        if (leader != null && leader.isValid() && leader.getStore() == member.getStore()) return ChaosWand.nameOf(leader, buffer);
        PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(top);
        return p != null ? p.getUsername() : null;
    }

    private static Txt teamWinTxt(String name) {
        return new Txt(name + " TEAM WINS!", I18n.tr("battle.win.team").param("name", name));
    }

    private static Message judgeMessage(String by) {
        return I18n.tr("battle.judge." + switch (by) {
            case "damage", "kills" -> by;
            case "mix" -> "score";
            default -> "health";
        });
    }

    private static String judgeWord(String by) {
        return switch (by) {
            case "damage" -> "damage";
            case "kills" -> "kills";
            case "mix" -> "score";
            default -> "health";
        };
    }

    /** Scores closer than this (half a percent) are a tie. */
    private static final double TIE = 0.005;

    private static List<UUID> winnerUuids(Battle battle, int side, ComponentAccessor<EntityStore> accessor) {
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            if (e.getValue() != side || !isAlive(e.getKey(), accessor)) continue;
            UUIDComponent component = accessor.getComponent(e.getKey(), UUIDComponent.getComponentType());
            if (component != null) ids.add(component.getUuid());
        }
        return List.copyOf(ids);
    }

    /**
     * /mobbattle tieBreak: damage = the tied side that dealt the most damage (a draw if that is even too),
     * random = a fair coin toss between them, draw = none. Returns the winning team or -1.
     */
    private static int breakTie(Battle battle, java.util.List<Integer> tied) {
        if (tied.size() < 2) return tied.isEmpty() ? -1 : tied.get(0);
        switch (FxSettings.tieBreak(battle.kind)) {
            case "random" -> {
                return tied.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(tied.size()));
            }
            case "kills" -> {
                int best = -1, most = -1, next = -1;
                for (int team : tied) {
                    int k = battle.killsBy.getOrDefault(team, 0);
                    if (k > most) {
                        next = most;
                        most = k;
                        best = team;
                    } else if (k > next) {
                        next = k;
                    }
                }
                return most > 0 && most > next ? best : -1;
            }
            case "damage" -> {
                int best = -1;
                double most = -1, next = -1;
                for (int team : tied) {
                    double d = battle.damageBy.getOrDefault(team, 0.0);
                    if (d > most) {
                        next = most;
                        most = d;
                        best = team;
                    } else if (d > next) {
                        next = d;
                    }
                }
                return most > 0 && most - next > 0.01 ? best : -1;
            }
            default -> {
                return -1;
            }
        }
    }

    /**
     * All vs one (FightDamageSystem): a stray hit from a fellow attacker (friendlyFire on) makes the vanilla AI turn on
     * it; every attacker must stay on the lone target whatever hits it (playtest 2026-10-12), so the victim is put back
     * on its target on its next tick. The hit itself still lands.
     */
    static void allyHit(Ref<EntityStore> attacker, Ref<EntityStore> victim) {
        if (attacker == victim || !isManaged(victim) || !isManaged(attacker) || isPlayerFighter(victim)) return;
        Battle b = battleOf(victim);
        if (b == null || b.kind != Kind.ALL_VS_ONE || !b.has(attacker) || b.team(attacker) != b.team(victim)) return;
        b.refocus.add(victim);
    }

    /** A hit between enemies of the same battle (FightDamageSystem): adds to the attacker's side's damage. */
    static void recordDamage(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount) {
        // A provocation (FightManager.provoke, sent on every target switch) is not a hit: counted, it reset the
        // chase limit and unstick of the "attacker", so a skeleton kept a fleeing frog as target (2026-10-12).
        if (amount <= FightManager.PROVOKE_DAMAGE * 2 || !isManaged(attacker)) return;
        Battle b = battleOf(attacker);
        if (b == null || !b.has(victim)) return;
        int team = b.team(attacker);
        if (team != b.team(victim)) {
            b.damageBy.merge(team, (double) amount, Double::sum);
            b.damageOf.merge(attacker, (double) amount, Double::sum);
            // selfDefense: a mob hit by an enemy that isn't its target turns on it (FightTickSystem, next tick).
            if (b.targets.get(victim) != attacker && FxSettings.selfDefense(b.kind) && !isPlayerFighter(victim)) {
                b.hitBy.put(victim, attacker);
            }
            Unstick.hit(attacker);
            Unstick.hurt(victim, attacker);
            b.chaseFrom.put(attacker, System.currentTimeMillis());
            if (b.targets.get(attacker) == victim) b.hitTarget.put(attacker, System.currentTimeMillis());
            // passiveWin: a hit on the mobs that can't fight back starts its time again.
            if (b.passiveSince != 0) b.passiveSince = System.currentTimeMillis();
            b.lastHit = System.currentTimeMillis();
            b.lastVictim = victim;
        }
    }

    /** Current health / max health (0 if dead or gone; 1 if it has no health stat). */
    private static double healthFraction(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        if (!isAlive(npc, buffer)) return 0;
        EntityStatMap stats = buffer.getComponent(npc, EntityStatMap.getComponentType());
        EntityStatValue health = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        if (health == null || health.getMax() <= 0) return 1;
        return Math.max(0, Math.min(1, health.get() / health.getMax()));
    }

    private static void announce(Battle battle, Txt main, Txt sub, String color) {
        MobBattlePlugin.log("Battle: " + main.en() + " (" + sub.en() + ")");
        for (PlayerRef viewer : messageViewers(battle)) {
            if (viewer.isValid()) viewer.sendMessage(Message.join(main.msg(), Message.raw(" "), sub.msg()).color(color));
        }
        FightFx.winnerTitle(battle, main.msg(), sub.msg());
    }

    /** Chat results stay owner-only for wand battles; ownerless events report to their configured spectators. */
    private static List<PlayerRef> messageViewers(Battle battle) {
        if (battle.owner() != null) {
            return battle.owner().isValid() ? List.of(battle.owner()) : List.of();
        }
        return FightFx.titleViewers(battle);
    }

    // ---- Team Wand drafts: the teams a player is building before starting the battle ----

    public static final class Draft {
        final EntityStore entityStore;
        int currentTeam = Teams.next(Teams.MAX - 1);
        final List<List<Ref<EntityStore>>> teams = new ArrayList<>();

        Draft(EntityStore entityStore) {
            this.entityStore = entityStore;
            for (int i = 0; i < TEAM_NAMES.length; i++) {
                teams.add(new CopyOnWriteArrayList<>());
            }
        }

        public int currentTeam() {
            return currentTeam;
        }

        public List<List<Ref<EntityStore>>> teams() {
            return teams;
        }

        void prune() {
            for (int slot = 0; slot < teams.size(); slot++) {
                var members = teams.get(slot);
                if (!Teams.exists(slot)) {
                    members.stream().filter(Ref::isValid).forEach(TeamFx::clear);
                    members.clear();
                } else {
                    members.removeIf(r -> !r.isValid() || r.getStore().getExternalData() != entityStore);
                }
            }
            if (!Teams.exists(currentTeam)) {
                currentTeam = Teams.next(currentTeam);
            }
        }

        int teamOf(Ref<EntityStore> npc) {
            for (int t = 0; t < teams.size(); t++) {
                if (teams.get(t).contains(npc)) {
                    return t;
                }
            }
            return -1;
        }

        void remove(Ref<EntityStore> npc) {
            teams.forEach(t -> t.removeIf(r -> r == npc || !r.isValid()));
        }
    }

    /** Player UUID -> draft. Its owner is looked up by UUID (draftOwner): a PlayerRef is never kept. */
    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();

    /** The player's draft if they have one (never creates it: the settings panel only shows it). */
    static Draft existingDraft(UUID player) {
        return player == null ? null : DRAFTS.get(player);
    }

    public static Draft draft(PlayerRef player) {
        EntityStore current = player.getReference().getStore().getExternalData();
        return DRAFTS.compute(player.getUuid(), (id, old) -> {
            if (old != null && old.entityStore == current) {
                return old;
            }
            if (old != null) {
                old.teams.forEach(members -> members.stream().filter(Ref::isValid).forEach(TeamFx::clear));
            }
            return new Draft(current);
        });
    }

    /** Removing/reusing a slot must not transfer its old mobs to a newly created team. */
    public static void removeConfiguredTeam(int slot) {
        List<Ref<EntityStore>> affected = new ArrayList<>();
        for (Draft draft : DRAFTS.values()) {
            affected.addAll(draft.teams.get(slot));
        }
        for (Battle battle : BATTLES) {
            if (battle.isTeams()) {
                battle.teamOf.forEach((npc, team) -> {
                    if (team == slot) affected.add(npc);
                });
            }
        }
        detach(affected);
    }

    public static void clearConfiguredTeams() {
        for (int slot = 0; slot < Teams.MAX; slot++) {
            removeConfiguredTeam(slot);
        }
        DRAFTS.clear();
    }

    public static Map<UUID, Draft> drafts() {
        return DRAFTS;
    }

    public static PlayerRef draftOwner(UUID id) {
        // Looked up, not stored: a reconnect makes a new PlayerRef and the old one keeps a gone player's data.
        return com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(id);
    }

    public static void clearDraft(UUID player) {
        DRAFTS.remove(player);
    }

    /** Toggles the NPC in the player's current team. Returns the team it is now in, or -1 if removed. */
    public static int toggle(Draft draft, Ref<EntityStore> npc) {
        draft.prune();
        int was = draft.teamOf(npc);
        detach(List.of(npc));
        FightManager.endFightsOf(List.of(npc));
        if (was == draft.currentTeam) {
            return -1;
        }
        draft.teams.get(draft.currentTeam).add(npc);
        MANAGED.add(npc);
        draftMembers = true;
        return draft.currentTeam;
    }

    /** Team slot by name (see /mobbattle teams list), or -1. */
    public static int teamIndex(String name) {
        return Teams.find(name);
    }

    /** Puts the NPC in the given team of the draft (out of any other) and shows the team look. */
    public static void assign(Draft draft, Ref<EntityStore> npc, int team) {
        draft.prune();
        detach(List.of(npc));
        FightManager.endFightsOf(List.of(npc));
        draft.teams.get(team).add(npc);
        MANAGED.add(npc);
        draftMembers = true;
        TeamFx.show(npc, team);
    }

    /** Empties the player's draft and removes the team look from its mobs. */
    public static int clearDraftAndFx(PlayerRef player) {
        Draft draft = DRAFTS.get(player.getUuid());
        if (draft == null) {
            return 0;
        }
        int n = 0;
        for (List<Ref<EntityStore>> team : draft.teams) {
            for (Ref<EntityStore> npc : team) {
                if (npc.isValid()) {
                    TeamFx.clear(npc);
                    n++;
                }
            }
        }
        clearDraft(player.getUuid());
        return n;
    }

    public static int nextTeam(Draft draft) {
        draft.currentTeam = Teams.next(draft.currentTeam);
        return draft.currentTeam;
    }
}
