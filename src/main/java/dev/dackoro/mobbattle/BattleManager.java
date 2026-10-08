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

    public static final class Battle {
        final UUID id = UUID.randomUUID();
        final PlayerRef owner;
        final boolean royale;
        final String[] names = TEAM_NAMES.clone();
        final String[] colors = TEAM_COLORS.clone();
        com.hypixel.hytale.component.Store<EntityStore> store;
        /** NPC index -> team. In a royale every NPC has its own team. */
        final Map<Integer, Integer> teamOf = new ConcurrentHashMap<>();
        final Map<Integer, Ref<EntityStore>> members = new ConcurrentHashMap<>();
        /** NPC index -> its current target. */
        final Map<Integer, Ref<EntityStore>> targets = new ConcurrentHashMap<>();
        final long expiresAt = System.currentTimeMillis() + MAX_MILLIS;
        volatile boolean over;

        Battle(PlayerRef owner, boolean royale) {
            this.owner = owner;
            this.royale = royale;
        }

        void add(Ref<EntityStore> npc, int team) {
            if (store == null) store = npc.getStore();
            members.put(npc.getIndex(), npc);
            teamOf.put(npc.getIndex(), team);
            MANAGED.add(npc);
        }

        String name(int team) { return team >= 0 && team < names.length && names[team] != null ? names[team] : "?"; }
        String color(int team) { return team >= 0 && team < colors.length && colors[team] != null ? colors[team] : "#aaaaaa"; }

        boolean has(Ref<EntityStore> npc) {
            return npc != null && npc.isValid() && members.containsKey(npc.getIndex())
                    && members.get(npc.getIndex()).isValid()
                    && members.get(npc.getIndex()).getStore() == npc.getStore();
        }

        int team(Ref<EntityStore> npc) {
            return teamOf.getOrDefault(npc.getIndex(), -1);
        }

        public boolean isTeams() {
            return !royale;
        }

        public Map<Integer, Ref<EntityStore>> members() {
            return members;
        }

        public PlayerRef owner() {
            return owner;
        }
    }

    private static final List<Battle> BATTLES = new CopyOnWriteArrayList<>();
    private static final Set<Ref<EntityStore>> MANAGED = ConcurrentHashMap.newKeySet();

    static boolean isManaged(Ref<EntityStore> npc) { return MANAGED.contains(npc); }
    static Set<Ref<EntityStore>> managed() { return MANAGED; }

    /**
     * NPC hits to cancel. Allies in the same battle never hurt each other. With /mobbattle isolation on
     * (default), battle mobs also can't hurt or be hurt by NPCs outside their battle (wild mobs, other
     * battles, or players for the mobs' hits). Players can always hit battle mobs: only NPC sources get here.
     */
    public static boolean blocksDamage(Ref<EntityStore> x, Ref<EntityStore> y) {
        Battle a = battleOf(x), b = battleOf(y);
        if (a != null && a == b) {
            return a.team(x) == a.team(y);
        }
        return FxSettings.battleIsolation && (a != null || b != null);
    }

    static void checkAll(com.hypixel.hytale.component.Store<EntityStore> store) {
        MANAGED.removeIf(npc -> !npc.isValid());
        FightManager.prunePendingStops();
        for (Battle battle : BATTLES) {
            if (battle.store == store) checkWinner(battle, store);
        }
    }

    private static void finish(Battle battle) {
        battle.over = true;
        BATTLES.remove(battle);
        for (Ref<EntityStore> npc : battle.members.values()) {
            if (npc.isValid()) {
                FightManager.queueStop(npc, battle.members.values());
                if (!battle.royale) TeamFx.clear(npc);
            }
        }
    }

    private BattleManager() {
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
            for (Ref<EntityStore> npc : npcs) {
                if (b.has(npc)) FightManager.queueStop(npc, b.members.values());
                if (b.has(npc) && b.members.remove(npc.getIndex()) != null) {
                    b.teamOf.remove(npc.getIndex());
                    b.targets.remove(npc.getIndex());
                    touched = true;
                }
            }
            if (!touched) {
                continue;
            }
            // Fewer than 2 sides left: nothing to win, end it silently.
            long sides = b.teamOf.values().stream().distinct().count();
            if (sides < 2) {
                finish(b);
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
            if (b.over) {
                continue;
            }
            for (Ref<EntityStore> member : b.members.values()) {
                if (!member.isValid() || member.getStore() != npc.getStore() || member.getIndex() == npc.getIndex()) {
                    continue;
                }
                Ref<EntityStore> other = com.hypixel.hytale.server.flock.FlockPlugin.getFlockReference(member, buffer);
                if (other != null && other.isValid() && other.getStore() == flock.getStore()
                        && other.getIndex() == flock.getIndex()) {
                    b.add(npc, b.team(member));
                    if (!b.royale) {
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
        for (Ref<EntityStore> other : battle.members.values()) {
            if (other.getIndex() == self.getIndex() || battle.team(other) == myTeam || !isAlive(other, buffer)) {
                continue;
            }
            Vector3d pos = ChaosWand.positionOf(other, buffer);
            double d = me == null || pos == null ? Double.MAX_VALUE - 1 : pos.distanceSquared(me);
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
        Ref<EntityStore> target = battle.targets.get(self.getIndex());
        if (target != null && battle.has(target) && isAlive(target, buffer)) {
            return target;
        }
        target = nearestEnemy(battle, self, buffer);
        if (target != null) {
            battle.targets.put(self.getIndex(), target);
            changed[0] = true;
        } else {
            battle.targets.remove(self.getIndex());
        }
        return target;
    }

    /** Sets the first target of an NPC (used at battle start). */
    public static void setTarget(Battle battle, Ref<EntityStore> self, Ref<EntityStore> target) {
        battle.targets.put(self.getIndex(), target);
    }

    /** Ends the battle and announces the winner once only one side has living members. */
    public static void checkWinner(Battle battle, ComponentAccessor<EntityStore> buffer) {
        if (battle.over) {
            return;
        }
        Set<Integer> aliveTeams = new HashSet<>();
        Ref<EntityStore> lastAlive = null;
        int alive = 0;
        for (Ref<EntityStore> npc : battle.members.values()) {
            if (isAlive(npc, buffer)) {
                aliveTeams.add(battle.team(npc));
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
        } else if (battle.royale) {
            main = ChaosWand.nameOf(lastAlive, buffer) + " WINS!";
            sub = battle.members.size() == 2 ? "Duel won" : "Last one standing";
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
                for (Ref<EntityStore> r : teams.get(t)) {
                    if (r.isValid() && r.getStore() == npc.getStore() && r.getIndex() == npc.getIndex()) {
                        return t;
                    }
                }
            }
            return -1;
        }

        void remove(Ref<EntityStore> npc) {
            teams.forEach(t -> t.removeIf(r -> !r.isValid()
                    || (r.getStore() == npc.getStore() && r.getIndex() == npc.getIndex())));
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
            if (!battle.royale) {
                battle.members.values().stream().filter(npc -> battle.team(npc) == slot).forEach(affected::add);
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
