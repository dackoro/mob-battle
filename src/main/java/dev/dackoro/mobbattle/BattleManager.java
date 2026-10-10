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

    /** Safety: a battle stops after this long even if nobody wins. */
    private static final long MAX_MILLIS = 15 * 60 * 1000;

    /** Battle type; its key names it in /mobbattle friendlyfire and battleisolation. */
    public enum Kind {
        DUEL("duel"), ALL_VS_ONE("allvsone"), ROYALE("royale"), TEAMS("teams");

        public final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    /** One fighter's kills in a battle: name and side as they were when it scored. */
    record Kills(String name, int team, int count) {
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
        /** (flock, member) pairs refreshed once per second, so pets can join without scanning every member. */
        volatile List<Ref<EntityStore>[]> flocks = List.of();
        /** Safety deadline; an arena timer longer than MAX_MILLIS pushes it past its own end (Arena.setup). */
        volatile long expiresAt = System.currentTimeMillis() + MAX_MILLIS;
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
        /** Kills per fighter (KillTracker), with its name and side kept for after it dies or leaves. */
        final Map<Ref<EntityStore>, Kills> kills = new ConcurrentHashMap<>();
        /** Who scored the latest kill (endCamera lasthit: the final blow). */
        volatile Ref<EntityStore> lastKiller;
        /** Kills per side, for /mobbattle tieBreak kills. */
        final Map<Integer, Integer> killsBy = new ConcurrentHashMap<>();

        /** A kill by {@code killer} (side {@code team}) in this battle. */
        void recordKill(Ref<EntityStore> killer, String name, int team) {
            lastKiller = killer;
            kills.compute(killer, (k, old) -> new Kills(name, team, old == null ? 1 : old.count() + 1));
            killsBy.merge(team, 1, Integer::sum);
        }

        /** The fighters with the most kills, most first (ties in no particular order). */
        List<Kills> topKillers(int n) {
            List<Kills> all = new ArrayList<>(kills.values());
            all.sort((x, y) -> Integer.compare(y.count(), x.count()));
            return all.subList(0, Math.min(n, all.size()));
        }

        /** The fighter with the most kills, or null; one pass, no copy (the timer box asks every second). */
        Kills topKiller() {
            Kills best = null;
            for (Kills k : kills.values()) if (best == null || k.count() > best.count()) best = k;
            return best;
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
    static void markPlayer(Ref<EntityStore> player, UUID owner) { PLAYER_FIGHTERS.put(player, owner); }
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
        FightManager.endFightsOf(List.of(player));
        releasePlayer(player);
        return b;
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
        // Retired mobs stay managed on purpose (joinOwnersSide must not pull them back into a battle).
        MANAGED.removeIf(npc -> !npc.isValid());
        WandFx.prune();
        Snap.prune();
        Army.prune();
        Buffs.prune();
        SummonDespawn.maintain();
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

    /** At the end: the top 3 killers in chat, to the owner and the player fighters (one line, any battle size). */
    private static void killSummary(Battle battle) {
        if (battle.kills.isEmpty()) return;
        StringBuilder line = new StringBuilder("Most kills: ");
        int place = 1;
        for (Kills k : battle.topKillers(3)) {
            if (place > 1) line.append(", ");
            line.append(place++).append(". ").append(k.name().replace('_', ' ')).append(" (").append(k.count()).append(')');
        }
        Message msg = Message.raw(line.toString()).color("#ffd23e");
        for (PlayerRef p : FightFx.titleViewers(battle)) {
            if (p.isValid()) p.sendMessage(msg);
        }
        MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + " kills by side " + battle.killsBy + " | " + line);
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

    /**
     * If {@code npc} shares a flock with a battle member (a pet or summon spawned by it), adds it to
     * that member's side. Returns the battle it joined, or null.
     */
    public static Battle joinOwnersSide(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        // A previously assigned/retired mob must never silently rejoin its old flock's battle.
        if (isManaged(npc) || !isAlive(npc, buffer)) return null;
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
        for (int i = 0; i < npcs.size(); i++) {
            battle.add(npcs.get(i), i);
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

    /** The NPC's current target, picking a new one if it has none or it died. */
    public static Ref<EntityStore> currentTarget(Battle battle, Ref<EntityStore> self, ComponentAccessor<EntityStore> buffer,
                                                 boolean[] changed) {
        Ref<EntityStore> target = battle.targets.get(self);
        if (target != null && battle.has(target) && isAlive(target, buffer)) {
            return target;
        }
        target = nearestEnemy(battle, self, buffer);
        if (target != null) {
            battle.targets.put(self, target);
            changed[0] = true;
        } else {
            battle.targets.remove(self);
        }
        return target;
    }

    /** selfDefense: after turning on an attacker, a mob keeps it at least this long (another hit can't pull it away). */
    private static final long SELF_DEFENSE_MS = 3000;

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
        battle.targets.put(self, by);
        battle.turnedAt.put(self, t);
        if (FxSettings.debug) MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": "
                + ChaosWand.nameOf(self, buffer) + " defends itself against " + ChaosWand.nameOf(by, buffer));
        return true;
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
                battle.owner.sendMessage(Message.raw("Battle cancelled: some fighters were unloaded (too far from every player). No winner.").color("#aaaaaa"));
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
            return;
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
                + ", fighters alive " + alive + ", damage by side " + battle.damageBy + (expired ? " (15 min limit)" : ""));
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

        String main;
        String sub;
        String color;
        if (outcome == BattleOutcome.EMPTY || outcome == BattleOutcome.TIMEOUT) {
            main = "DRAW!";
            sub = outcome == BattleOutcome.TIMEOUT ? "Time is up" : "Nobody is left standing";
            color = "#aaaaaa";
        } else if (battle.focusName != null) {
            boolean targetWon = aliveTeams.contains(1);
            main = targetWon ? battle.focusName + " WINS!" : battle.focusName + " IS DEFEATED!";
            sub = targetWon ? "Survived everyone" : alive + " attackers left standing";
            color = targetWon ? "#ffd23e" : "#ff6b5e";
        } else if (battle.royale) {
            main = ChaosWand.nameOf(lastAlive, buffer) + " WINS!";
            sub = battle.teamOf.size() == 2 ? "Duel won" : "Last one standing";
            color = "#ffd23e";
        } else {
            int team = aliveTeams.iterator().next();
            main = battle.name(team) + " TEAM WINS!";
            sub = alive + " left standing";
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
                sub += " | " + ("lasthit".equals(endMode) ? "Final blow: " : "") + ChaosWand.nameOf(focus, buffer).replace('_', ' ')
                        + ", " + n + (n == 1 ? " kill" : " kills");
            }
        }
        MobBattlePlugin.log("Battle over: " + main + " (" + sub + ")");
        for (PlayerRef viewer : messageViewers(battle)) {
            if (viewer.isValid()) viewer.sendMessage(Message.raw(main + " " + sub).color(color));
        }
        FightFx.winnerTitle(battle, main, sub);
        if (focus != null) FightCamera.ending(battle, focus, buffer);
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
        Ref<EntityStore> best = null;
        int bestKills = -1;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            if (e.getValue() != team || !isAlive(e.getKey(), buffer)) continue;
            Kills k = battle.kills.get(e.getKey());
            int n = k != null ? k.count() : 0;
            if (n > bestKills) {
                best = e.getKey();
                bestKills = n;
            }
        }
        return best;
    }

    /** The arena timer reached 0 (Arena.tick): draw, kill everyone, keep fighting, or the healthiest side wins. */
    static void timeUp(Battle battle, Arena.TimeUp rule, ComponentAccessor<EntityStore> buffer) {
        if (battle.over) return;
        switch (rule) {
            case CONTINUE -> {
                battle.silent = true;
                announce(battle, "TIME'S UP!", "The fight goes on", "#aaaaaa");
            }
            case KILL -> {
                battle.silent = true;
                battle.killAll = true;
                battle.eventEndReason = MobBattleApi.EndReason.TIMEOUT;
                battle.eventWinnerSide = -1;
                announce(battle, "TIME'S UP!", "Nobody survives", "#ff6b5e");
            }
            case DRAW -> {
                finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.TIMEOUT, -1);
                announce(battle, "DRAW!", "Time is up", "#aaaaaa");
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
        if (FxSettings.debug) {
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
        String tie = null;
        if (best >= 0 && bestScore > 0 && bestScore - second < TIE) {
            // Even on health: /mobbattle tieBreak damage (who dealt more), random, or draw.
            java.util.List<Integer> tied = new java.util.ArrayList<>();
            for (Map.Entry<Integer, Double> e : scores.entrySet()) {
                if (bestScore - e.getValue() < TIE) tied.add(e.getKey());
            }
            best = breakTie(battle, tied);
            String rule = FxSettings.tieBreak(battle.kind);
            MobBattlePlugin.debug("battle #" + battle.id.toString().substring(0, 8) + ": tie between sides " + tied + ", tieBreak "
                    + rule + (rule.equals(FxSettings.tieBreak) ? "" : " (" + FxSettings.tieBreak + " = judgeBy)") + " -> " + (best < 0 ? "draw" : "side " + best));
            tie = best < 0 ? null : "damage".equals(rule) ? "more damage dealt"
                    : "kills".equals(rule) ? "more kills" : "a coin toss";
        }
        if (best < 0 || bestScore <= 0) {
            finish(battle, battle.snapshot(), Set.of(), MobBattleApi.EndReason.TIMEOUT, -1);
            announce(battle, "DRAW!", "Time is up, even on " + judgeWord(by), "#aaaaaa");
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
        String winner;
        String color = "#ffd23e";
        if (battle.focusName != null) {
            winner = best == 1 ? battle.focusName : "THE ATTACKERS";
        } else if (battle.royale) {
            winner = ChaosWand.nameOf(anyMember.get(best), buffer);
        } else {
            winner = battle.name(best) + " TEAM";
            color = battle.color(best);
        }
        String why = tie != null ? "even on " + judgeWord(by) + " (" + Math.round(bestScore * 100) + "%), won by " + tie
                : Math.round(bestScore * 100) + "% " + switch (by) {
                    case "damage" -> "of the damage";
                    case "kills" -> "of the kills";
                    case "mix" -> "score";
                    default -> "health left";
                } + " vs " + Math.round(Math.max(0, second) * 100) + "%";
        announce(battle, winner + " WINS!", (judge ? "Time is up: the losers fall. " : "Time is up: ") + why, color);
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

    /** A hit between enemies of the same battle (FightDamageSystem): adds to the attacker's side's damage. */
    static void recordDamage(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount) {
        if (amount <= 0 || !isManaged(attacker)) return;
        Battle b = battleOf(attacker);
        if (b == null || !b.has(victim)) return;
        int team = b.team(attacker);
        if (team != b.team(victim)) {
            b.damageBy.merge(team, (double) amount, Double::sum);
            // selfDefense: a mob hit by an enemy that isn't its target turns on it (FightTickSystem, next tick).
            if (b.targets.get(victim) != attacker && FxSettings.selfDefense(b.kind) && !isPlayerFighter(victim)) {
                b.hitBy.put(victim, attacker);
            }
            Unstick.hit(attacker);
            Unstick.hurt(victim, attacker);
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

    private static void announce(Battle battle, String main, String sub, String color) {
        MobBattlePlugin.log("Battle: " + main + " (" + sub + ")");
        for (PlayerRef viewer : messageViewers(battle)) {
            if (viewer.isValid()) viewer.sendMessage(Message.raw(main + " " + sub).color(color));
        }
        FightFx.winnerTitle(battle, main, sub);
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
