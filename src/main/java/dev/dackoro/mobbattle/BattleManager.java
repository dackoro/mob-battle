package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
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

    public static final class Battle {
        final UUID id = UUID.randomUUID();
        /** Set from the constructor; SelectFighterInteraction marks its 2-mob royale as a DUEL. */
        volatile Kind kind;
        final PlayerRef owner;
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
        /** (flock, member) pairs refreshed once per second, so pets can join without scanning every member. */
        volatile List<Ref<EntityStore>[]> flocks = List.of();
        final long expiresAt = System.currentTimeMillis() + MAX_MILLIS;
        /** Next winner check; only touched by the battle's own world thread. */
        long nextCheck;
        volatile boolean over;

        Battle(PlayerRef owner, boolean royale) {
            this(owner, royale, null);
        }

        Battle(PlayerRef owner, boolean royale, String focusName) {
            this.owner = owner;
            this.royale = royale;
            this.focusName = focusName;
            this.kind = focusName != null ? Kind.ALL_VS_ONE : royale ? Kind.ROYALE : Kind.TEAMS;
        }

        void add(Ref<EntityStore> npc, int team) {
            if (store == null) store = npc.getStore();
            teamOf.put(npc, team);
            MANAGED.add(npc);
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

    /** Some draft has mobs (refreshed by maintain, set right away when one is added). */
    private static volatile boolean draftMembers;
    /** Some battle member belongs to a flock, so new pets may need to join (refreshed once per second). */
    private static volatile boolean anyFlocks;

    static boolean isManaged(Ref<EntityStore> npc) { return MANAGED.contains(npc); }
    static Set<Ref<EntityStore>> managed() { return MANAGED; }
    static boolean hasDraftMembers() { return draftMembers; }
    static boolean hasFlocks() { return anyFlocks; }

    /**
     * NPC hits to cancel. Allies in the same battle never hurt each other. With /mobbattle isolation on
     * (default), battle mobs also can't hurt or be hurt by NPCs outside their battle (wild mobs, other
     * battles, or players for the mobs' hits). Players can always hit battle mobs: only NPC sources get here.
     */
    public static boolean blocksDamage(Ref<EntityStore> x, Ref<EntityStore> y) {
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
        Army.prune();
        for (Battle battle : BATTLES) {
            // World unloaded (e.g. a closed instance): its tick will never run checkWinner again. A live world
            // handles its own empty battles there, with the "DRAW!" announcement.
            if (battle.store == null || battle.store.isShutdown()) {
                finish(battle);
                MobBattlePlugin.log("Battle dropped: its world was unloaded");
            }
        }
        boolean members = false;
        for (Draft draft : DRAFTS.values()) {
            draft.prune();
            members |= draft.teams.stream().anyMatch(t -> !t.isEmpty());
        }
        draftMembers = members;
        anyFlocks = BATTLES.stream().anyMatch(b -> !b.flocks.isEmpty());
        DRAFT_OWNERS.keySet().retainAll(DRAFTS.keySet());
        FightManager.prune();
        TeamFx.prune();
        MorphWand.prune();
        FightFx.pruneTitles();
        FightFx.pruneHeldAreas();
        Countdown.prune();
        BattleDebug.prune();
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
        battle.over = true;
        BATTLES.remove(battle);
        List<Ref<EntityStore>> all = battle.snapshot();
        for (Ref<EntityStore> npc : all) {
            if (npc.isValid()) {
                FightManager.queueStop(npc, formerMembers);
                if (battle.isTeams()) TeamFx.clear(npc);
            }
        }
    }

    private BattleManager() {
    }

    /** Ends every running battle (duels, Chaos and team battles) with no winner. Returns how many. */
    public static int stopAll() {
        int n = 0;
        for (Battle battle : new ArrayList<>(BATTLES)) {
            finish(battle);
            n++;
        }
        return n;
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
        DRAFT_OWNERS.clear();
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
        if (x == null || y == null || BATTLES.isEmpty()) {
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
            boolean touched = false;
            List<Ref<EntityStore>> opponents = null;
            for (Ref<EntityStore> npc : npcs) {
                if (!b.has(npc)) continue;
                if (opponents == null) opponents = b.snapshot();
                FightManager.queueStop(npc, opponents);
                b.teamOf.remove(npc);
                b.targets.remove(npc);
                touched = true;
            }
            if (!touched) {
                continue;
            }
            // Fewer than 2 sides left: nothing to win, end it silently.
            long sides = b.teamOf.values().stream().distinct().count();
            if (sides < 2) {
                finish(b, opponents);
                MobBattlePlugin.log("Battle ended: its mobs got new wand orders");
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
        if (!accumulate) {
            Battle duel = startRoyale(owner, List.of(a, b));
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
        Battle duel = new Battle(owner, true);
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

    /** Starts a battle royale: every NPC for itself. */
    public static Battle startRoyale(PlayerRef owner, List<Ref<EntityStore>> npcs) {
        detach(npcs);
        FightManager.endFightsOf(npcs);
        Battle battle = new Battle(owner, true);
        for (int i = 0; i < npcs.size(); i++) {
            battle.add(npcs.get(i), i);
        }
        BATTLES.add(battle);
        return battle;
    }

    /** Chaos Wand focus: every attacker (allies, side 0) vs the target (side 1), so the end gets a winner title. */
    public static Battle startFocus(PlayerRef owner, Ref<EntityStore> target, String targetName,
                                    List<Ref<EntityStore>> attackers) {
        List<Ref<EntityStore>> all = new ArrayList<>(attackers);
        all.add(target);
        detach(all);
        FightManager.endFightsOf(all);
        Battle battle = new Battle(owner, false, targetName);
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

    /** Sets the first target of an NPC (used at battle start). */
    public static void setTarget(Battle battle, Ref<EntityStore> self, Ref<EntityStore> target) {
        battle.targets.put(self, target);
    }

    /** Ends the battle and announces the winner once only one side has living members. */
    public static void checkWinner(Battle battle, ComponentAccessor<EntityStore> buffer) {
        if (battle.over) {
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
        finish(battle);

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
        MobBattlePlugin.log("Battle over: " + main + " (" + sub + ")");
        if (battle.owner != null && battle.owner.isValid()) {
            battle.owner.sendMessage(Message.raw(main + " " + sub).color(color));
            FightFx.winnerTitle(battle.owner, main, sub);
        }
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

    /** Player UUID -> draft, plus the PlayerRef to draw the team rings for. */
    private static final Map<UUID, Draft> DRAFTS = new ConcurrentHashMap<>();
    private static final Map<UUID, PlayerRef> DRAFT_OWNERS = new ConcurrentHashMap<>();

    public static Draft draft(PlayerRef player) {
        DRAFT_OWNERS.put(player.getUuid(), player);
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
        DRAFT_OWNERS.clear();
    }

    public static Map<UUID, Draft> drafts() {
        return DRAFTS;
    }

    public static PlayerRef draftOwner(UUID id) {
        return DRAFT_OWNERS.get(id);
    }

    public static void clearDraft(UUID player) {
        DRAFTS.remove(player);
        DRAFT_OWNERS.remove(player);
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
