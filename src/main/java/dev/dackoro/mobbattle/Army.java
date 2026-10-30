package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Army Wand: each player's own mobs. The owner can also be another player (apply ... army add <player>) or a mob,
 * the army's leader (apply ... army add id:<uuid>, 2026-10-14): the army follows it, defends itself and it,
 * and attacks what it attacks, by the same four rules. A leader is kept by its entity UUID in {@link #LEADERS};
 * when it dies or unloads its army is released. Army mobs don't attack their owner and can't be hurt by the owner
 * or by each other. They defend themselves and their owner and attack what the owner hits (see order()).
 * Kept in memory only (like the team drafts): the army is gone after a server restart.
 */
final class Army {

    /** Army mob -> owner's UUID. */
    private static final Map<Ref<EntityStore>, UUID> OWNER = new ConcurrentHashMap<>();
    /**
     * Army mobs that got a fight order and have not regrouped yet. Rule 4 only moves these: an idle mob that
     * is just following its owner stays with the owner instead of joining every fight of the army.
     */
    private static final java.util.Set<Ref<EntityStore>> FOUGHT = ConcurrentHashMap.newKeySet();

    /** Mob leaders: the army owner UUID (the mob's own UUID) -> its entity. Players are looked up by UUID instead. */
    private static final Map<UUID, Ref<EntityStore>> LEADERS = new ConcurrentHashMap<>();

    /**
     * Army Wand crouch click (2026-10-14): whom each player recruits for. Absent = their own army. A player owner
     * is kept by UUID only (a stored Ref would outlive a reconnect); a mob leader by its Ref (pruned when gone).
     */
    record Pick(UUID owner, Ref<EntityStore> leader, String name) {
    }

    private static final Map<UUID, Pick> PICKS = new ConcurrentHashMap<>();

    /** The player's current pick, or null for their own army (a gone leader counts as none). */
    static Pick pickOf(UUID player) {
        Pick p = PICKS.get(player);
        if (p != null && p.leader() != null && !p.leader().isValid() && !RoleChange.pending(p.owner())) {
            PICKS.remove(player, p);
            return null;
        }
        return p;
    }

    /** Crouch + click: recruit for {@code pick} from now on; null = for themselves again. */
    static void setPick(UUID player, Pick pick) {
        if (pick == null || pick.owner().equals(player)) PICKS.remove(player);
        else PICKS.put(player, pick);
    }

    /** Ring color under the picked leader while the Army Wand is held (its army keeps the blue ring). */
    static final String LEADER_RING_COLOR = "#ffd23e";

    /** Mob -> the army tint effect id we put on it (/mobbattle armyTint). Added and removed in follow(). */
    private static final Map<Ref<EntityStore>, String> TINTED = new ConcurrentHashMap<>();

    private Army() {
    }

    static boolean any() {
        return !OWNER.isEmpty() || !TINTED.isEmpty() || !PICKS.isEmpty();
    }

    /** The army key of {@code npc} if it leads an army (its own UUID), else null. */
    static UUID leaderKey(Ref<EntityStore> npc) {
        if (npc == null || LEADERS.isEmpty()) return null;
        for (Map.Entry<UUID, Ref<EntityStore>> e : LEADERS.entrySet()) {
            if (e.getValue() == npc) return e.getKey();
        }
        return null;
    }

    /**
     * The top of the chain of command {@code ref} belongs to (audit 2026-10-14: a mob two levels down, in the army of a
     * leader that serves a player, attacked that player, whose army then went for it while the leader's hits on its own
     * member were cancelled). {@code ownKey} is the army it leads itself (a player's UUID, {@link #leaderKey}), or null.
     * Walks up through leaders that serve in another army; a player never serves in one. Null = in no army at all.
     */
    static UUID topOf(Ref<EntityStore> ref, UUID ownKey) {
        UUID key = ownKey;
        Ref<EntityStore> cur = ref;
        for (int depth = 0; depth < 32 && cur != null; depth++) {
            UUID up = OWNER.get(cur);
            if (up == null) return key;
            key = up;
            cur = LEADERS.get(up);
        }
        return key;
    }

    /**
     * For debug lines: whose army the mob is in ("Dackoro", or a leader mob's role and "#uuid"), plus "leads N" when it
     * leads one; "none".
     */
    static String describe(Ref<EntityStore> npc) {
        UUID owner = ownerOf(npc);
        UUID lead = leaderKey(npc);
        if (owner == null && lead == null) return "none";
        StringBuilder b = new StringBuilder();
        if (owner != null) {
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(owner);
            Ref<EntityStore> l = LEADERS.get(owner);
            b.append(p != null ? p.getUsername() : l != null && l.isValid() ? "leader#" + owner.toString().substring(0, 4) : owner.toString().substring(0, 8));
        } else {
            b.append("-");
        }
        if (lead != null) b.append(",leads ").append(members(lead).size());
        return b.toString();
    }

    /** The army key {@code ref} leads: a player's UUID, a leader mob's, or null. */
    static UUID keyOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        UUID lead = leaderKey(ref);
        if (lead != null || ref == null || !ref.isValid()) return lead;
        PlayerRef p = accessor.getComponent(ref, PlayerRef.getComponentType());
        return p != null ? p.getUuid() : null;
    }

    /** {@link #topOf} for an army key (a player's or a leader mob's). */
    static UUID topOfKey(UUID key) {
        if (key == null) return null;
        Ref<EntityStore> leader = LEADERS.get(key);
        return leader == null ? key : topOf(leader, key);
    }

    /**
     * True when {@code mob} serves {@code player} somewhere down their chain of command (their army, or the army of a
     * leader under them): the Kill Wand's wave and the Snap spare them (2026-10-18). False for a null player.
     */
    static boolean servesPlayer(Ref<EntityStore> mob, UUID player) {
        return player != null && mob != null && player.equals(topOf(mob, leaderKey(mob)));
    }

    /** True when both are in one chain of command: mates, a leader and its mobs, an owner and every army under it. */
    static boolean sameChain(Ref<EntityStore> a, UUID aKey, Ref<EntityStore> b, UUID bKey) {
        UUID top = topOf(a, aKey);
        return top != null && top.equals(topOf(b, bKey));
    }

    /**
     * Friendly toward every owner up its chain (a leader mob also toward the member), for {@link FightManager#befriend}'s
     * HOSTILE_SECONDS: renewed by follow() (audit 2026-10-14: after 10 idle minutes, or a player's reconnect with a new
     * entity, hostile army mobs turned on their leader or owner).
     */
    private static void befriendChain(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        UUID key = OWNER.get(npc);
        for (int depth = 0; depth < 32 && key != null; depth++) {
            Ref<EntityStore> boss = entityOf(key);
            if (boss != null && boss.getStore() == npc.getStore()) {
                FightManager.befriend(npc, boss, buffer);
                if (LEADERS.containsKey(key)) FightManager.befriend(boss, npc, buffer);
            }
            Ref<EntityStore> leader = LEADERS.get(key);
            key = leader != null ? OWNER.get(leader) : null;
        }
    }

    /**
     * Army mobs and leaders unloaded with their chunk (the owner logged off or walked away; test 2026-10-14: the army
     * was gone after a reconnect, since a reloaded mob comes back under a new Ref). Kept by the mob's UUID, which the
     * game saves with it, and put back when it loads again. {@code bossIsLeader}: its owner was a leader mob, so it only
     * comes back while that leader still exists.
     */
    private record Away(UUID owner, boolean bossIsLeader, boolean leads, boolean fought, long at) {
    }

    private static final Map<UUID, Away> AWAY = new ConcurrentHashMap<>();
    /** An unloaded army mob is forgotten after this long. */
    private static final long AWAY_MS = 6 * 3600_000L;

    /** Army mob -> its entity UUID (for saving the armies: OWNER is keyed by Ref). */
    private static final Map<Ref<EntityStore>, UUID> MEMBER_ID = new ConcurrentHashMap<>();
    /** Something changed since the armies were last written to {@link #FILE}. */
    private static volatile boolean dirty;
    /**
     * The armies across server restarts (test 2026-10-14, playtest: a restart lost every army). One line per mob: its UUID,
     * its owner key or "-", bossIsLeader, leads, fought. Read into AWAY at startup, so each mob rejoins as it loads.
     */
    static final String FILE = "armies.txt";

    private static void remember(Ref<EntityStore> npc, ComponentAccessor<EntityStore> accessor) {
        var id = accessor.getComponent(npc, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (id != null) MEMBER_ID.put(npc, id.getUuid());
        dirty = true;
    }

    /** Plugin start: the armies saved last time wait for their mobs to load (as after a chunk unload). */
    static void loadSaved(java.nio.file.Path dataDir) {
        if (dataDir == null) return;
        java.nio.file.Path file = dataDir.resolve(FILE);
        if (!java.nio.file.Files.isRegularFile(file)) return;
        long now = System.currentTimeMillis();
        try {
            for (String line : java.nio.file.Files.readAllLines(file)) {
                String[] w = line.trim().split("\\s+");
                if (w.length < 5 || line.startsWith("#")) continue;
                try {
                    AWAY.put(UUID.fromString(w[0]), new Away("-".equals(w[1]) ? null : UUID.fromString(w[1]),
                            Boolean.parseBoolean(w[2]), Boolean.parseBoolean(w[3]), Boolean.parseBoolean(w[4]), now));
                } catch (IllegalArgumentException ignored) {
                    // a broken line: skipped
                }
            }
            if (!AWAY.isEmpty()) MobBattlePlugin.log("Armies: " + AWAY.size() + " mobs saved, they rejoin as they load");
        } catch (java.io.IOException | RuntimeException e) {
            MobBattlePlugin.log("Armies: could not read " + file + " (" + e + ")");
        }
    }

    /** Writes the armies when they changed (from prune, once a second at most). */
    private static void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        java.nio.file.Path dir = FxSettings.dataDirectory();
        if (dir == null) return;
        Map<UUID, Away> all = new java.util.HashMap<>(AWAY);
        long now = System.currentTimeMillis();
        OWNER.forEach((npc, owner) -> {
            UUID id = MEMBER_ID.get(npc);
            if (id == null || !npc.isValid()) return;
            Away boss = AWAY.get(owner);
            all.put(id, new Away(owner, LEADERS.containsKey(owner) || boss != null && boss.leads(), leaderKey(npc) != null,
                    FOUGHT.contains(npc), now));
        });
        LEADERS.forEach((key, ref) -> {
            if (!all.containsKey(key)) all.put(key, new Away(ref.isValid() ? OWNER.get(ref) : null, false, true, false, now));
        });
        StringBuilder b = new StringBuilder("# Mob Battle armies: mob owner bossIsLeader leads fought\n");
        all.forEach((id, a) -> b.append(id).append(' ').append(a.owner() == null ? "-" : a.owner()).append(' ')
                .append(a.bossIsLeader()).append(' ').append(a.leads()).append(' ').append(a.fought()).append('\n'));
        try {
            FxSettings.writeAtomically(dir.resolve(FILE), b.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            MobBattlePlugin.log("Armies: could not save (" + e + ")");
        }
    }

    /** An NPC unloaded with its chunk (BattleUnloadSystem, not a role change): an army mob or leader is remembered. */
    static void unloaded(Ref<EntityStore> ref, com.hypixel.hytale.component.Store<EntityStore> store) {
        if (OWNER.isEmpty() && LEADERS.isEmpty()) return;
        UUID owner = OWNER.get(ref);
        UUID leads = leaderKey(ref);
        if (owner == null && leads == null) return;
        // Already dying: it fell, it doesn't come back.
        if (store.getComponent(ref, com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent.getComponentType()) != null) return;
        var id = store.getComponent(ref, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (id == null) return;
        AWAY.put(id.getUuid(), new Away(owner, owner != null && LEADERS.containsKey(owner), leads != null,
                FOUGHT.contains(ref), System.currentTimeMillis()));
        dirty = true;
    }

    /** An NPC loaded again (BattleUnloadSystem, AddReason.LOAD): back in its army, or leading it again. */
    static void loaded(Ref<EntityStore> ref, com.hypixel.hytale.component.Store<EntityStore> store) {
        if (AWAY.isEmpty()) return;
        var id = store.getComponent(ref, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        Away a = id != null ? AWAY.remove(id.getUuid()) : null;
        if (a == null) return;
        UUID uuid = id.getUuid();
        if (a.leads()) {
            LEADERS.put(uuid, ref);
            PICKS.replaceAll((k, p) -> uuid.equals(p.owner()) && p.leader() != null ? new Pick(p.owner(), ref, p.name()) : p);
        }
        // Its leader died meanwhile: it stays free. A leader still unloaded (or not loaded yet after a restart) is not
        // dead: it leads again when it loads.
        if (a.owner() != null && (!a.bossIsLeader() || LEADERS.containsKey(a.owner()) || AWAY.containsKey(a.owner()))) {
            OWNER.put(ref, a.owner());
            MEMBER_ID.put(ref, uuid);
            if (a.fought()) FOUGHT.add(ref);
        }
        dirty = true;
        // Befriended again along its chain on the next follow pass (a reloaded mob has no attitude overrides).
        RENEWED.clear();
    }

    /** Owner key -> when its army was last befriended along its chain, and the owner's entity then. */
    private record Renew(long at, Ref<EntityStore> me) {
    }

    private static final Map<UUID, Renew> RENEWED = new ConcurrentHashMap<>();
    /** befriend lasts HOSTILE_SECONDS (600 s): renewed well before. */
    private static final long RENEW_MS = 60_000;

    /**
     * Adds {@code npc} to the army of {@code owner} (a player's UUID or a leader mob's), led by {@code ownerEntity}.
     * A mob leader is remembered in LEADERS. False if it already was in that army.
     */
    static boolean add(UUID owner, Ref<EntityStore> ownerEntity, boolean mobLeader, Ref<EntityStore> npc,
                       ComponentAccessor<EntityStore> buffer) {
        if (owner == null || npc == null || owner.equals(OWNER.get(npc)) || npc == ownerEntity) return false;
        if (wouldLoop(ownerEntity, npc)) return false;
        if (mobLeader) LEADERS.put(owner, ownerEntity);
        BattleManager.detach(List.of(npc));
        FightManager.endFightsOf(List.of(npc));
        OWNER.put(npc, owner);
        remember(npc, buffer);
        // Friendly toward its owner and every owner above it; a leader mob must not attack its own army either.
        befriendChain(npc, buffer);
        // Its own army (if it leads one) now serves a new chain too: follow() renews every army next second.
        RENEWED.clear();
        return true;
    }

    /**
     * Leaders can serve in another army (a chain of command), but never in a loop: true if {@code npc} leads, directly
     * or down its chain, the new owner {@code ownerEntity} (which may not lead anything yet: test 2026-10-14, a
     * chieftain was put into the army of one of its own goblins).
     */
    static boolean wouldLoop(Ref<EntityStore> ownerEntity, Ref<EntityStore> npc) {
        UUID mine = leaderKey(npc);
        if (mine == null) return false;
        Ref<EntityStore> cur = ownerEntity;
        for (int depth = 0; depth < 32 && cur != null; depth++) {
            if (cur == npc) return true;
            UUID up = OWNER.get(cur);
            if (up == null) return false;
            if (up.equals(mine)) return true;
            cur = entityOf(up);
        }
        return false;
    }

    /** Takes {@code npc} out of whatever army it is in. False if it was in none. */
    static boolean remove(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        UUID owner = OWNER.get(npc);
        if (owner == null) return false;
        release(npc, entityOf(owner), buffer);
        return true;
    }

    static UUID ownerOf(Ref<EntityStore> npc) {
        return npc == null ? null : OWNER.get(npc);
    }

    static boolean isMember(UUID owner, Ref<EntityStore> npc) {
        return owner != null && owner.equals(ownerOf(npc));
    }

    static List<Ref<EntityStore>> members(UUID owner) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        OWNER.forEach((npc, o) -> {
            if (o.equals(owner) && npc.isValid()) out.add(npc);
        });
        return out;
    }

    /** Adds the mob to the player's army, or removes it if it was already in it. Returns true if added. */
    static boolean toggle(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> npc,
                          ComponentAccessor<EntityStore> buffer) {
        UUID id = player.getUuid();
        if (id.equals(OWNER.get(npc))) {
            release(npc, playerEntity, buffer);
            return false;
        }
        // An army order replaces any battle, fight or team the mob was in.
        BattleManager.detach(List.of(npc));
        FightManager.endFightsOf(List.of(npc));
        OWNER.put(npc, id);
        remember(npc, buffer);
        FightManager.befriend(npc, playerEntity, buffer);
        return true;
    }

    /** Empties the player's army. Returns how many mobs left it. */
    static int disband(UUID owner, Ref<EntityStore> playerEntity, ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> mobs = members(owner);
        releaseAll(mobs, playerEntity);
        OWNER.values().removeIf(owner::equals);
        AWAY.values().removeIf(a -> owner.equals(a.owner()));
        FOUGHT.retainAll(OWNER.keySet());
        return mobs.size();
    }

    /** /mobbattle resetall and army clearall: every army, every player. Returns how many mobs. */
    static int clearAll() {
        int n = 0;
        for (Ref<EntityStore> npc : new ArrayList<>(OWNER.keySet())) {
            if (npc.isValid()) {
                FightManager.queueStop(npc, List.of());
                n++;
            }
        }
        FightManager.endFightsOf(new ArrayList<>(OWNER.keySet()));
        OWNER.clear();
        FOUGHT.clear();
        LEADERS.clear();
        PICKS.clear();
        RENEWED.clear();
        AWAY.clear();
        MEMBER_ID.clear();
        dirty = true;
        return n;
    }

    /** After a release, how long the mob and the ones it fought ignore each other (and it its ex-owner). */
    static final double RELEASE_GRACE_SECONDS = 3;

    private static void release(Ref<EntityStore> npc, Ref<EntityStore> playerEntity, ComponentAccessor<EntityStore> buffer) {
        releaseAll(List.of(npc), playerEntity);
    }

    /**
     * Back to their own AI after a short pause: a hostile mob turns on its ex-owner, and the fights the army gave them
     * end on both sides, so same-faction mobs make peace again (playtest 2026-10-12: disbanded goblins and the
     * Feastmaster they fought kept going for the whole 10 min forced hostility). An opponent forgets every mob
     * released together: it was turned against several of them over the fight, not only its current one.
     */
    private static void releaseAll(List<Ref<EntityStore>> mobs, Ref<EntityStore> playerEntity) {
        java.util.Set<Ref<EntityStore>> opponents = new java.util.LinkedHashSet<>();
        dirty = true;
        for (Ref<EntityStore> npc : mobs) {
            OWNER.remove(npc);
            MEMBER_ID.remove(npc);
            FOUGHT.remove(npc);
            opponents.addAll(FightManager.opponentsOf(npc));
        }
        opponents.removeAll(mobs);
        FightManager.endFightsOf(mobs);
        for (Ref<EntityStore> npc : mobs) {
            List<Ref<EntityStore>> forget = new ArrayList<>(opponents);
            if (playerEntity != null) forget.add(playerEntity);
            FightManager.queueRelease(npc, forget, RELEASE_GRACE_SECONDS);
        }
        for (Ref<EntityStore> other : opponents) {
            if (other.isValid() && ownerOf(other) == null && !BattleManager.isManaged(other) && !FightManager.isFighter(other)) {
                FightManager.queueRelease(other, mobs, RELEASE_GRACE_SECONDS);
            }
        }
    }

    /*
     * Army orders, most urgent first:
     *  1. Self-defense: a mob hit by X turns on X (and the nearest free mate helps) - defend().
     *  2. Bodyguard: someone hits the owner - order().
     *  3. Owner's order: the owner hits a target - order().
     *     order(): every free mob goes; if none is free, the nearest half to the enemy switches, the rest
     *     finish what they were doing.
     *  4. Regroup: a mob whose target died joins the nearest enemy the army still fights, or follows the
     *     owner again - in follow(), once per second.
     * "Free" = not fighting, or its target is dead.
     */

    /** Rules 2 and 3: the owner hit {@code enemy}, or {@code enemy} hit the owner. */
    static void order(UUID owner, Ref<EntityStore> enemy, ComponentAccessor<EntityStore> buffer) {
        if (enemy == null || !enemy.isValid() || !BattleManager.isAlive(enemy, buffer)) return;
        // Never against its own chain of command (a mob under a sub-leader of this owner).
        UUID top = topOfKey(owner);
        if (top != null && top.equals(topOf(enemy, keyOf(enemy, buffer)))) return;
        List<Ref<EntityStore>> free = new ArrayList<>(), busy = new ArrayList<>();
        boolean someoneOnIt = false;
        for (Ref<EntityStore> npc : members(owner)) {
            if (npc.getStore() != enemy.getStore() || !BattleManager.isAlive(npc, buffer)) continue;
            if (inBattle(npc)) continue; // its battle picks its targets
            // An isolated battle cancels every outsider hit (a leader mob in a duel): standing there swinging is no help.
            if (BattleManager.blocksDamage(npc, enemy)) continue;
            if (FightManager.enemyOf(npc) == enemy) {
                someoneOnIt = true; // already on it
                continue;
            }
            (isFree(npc, buffer) ? free : busy).add(npc);
        }
        // Only when nobody is on it yet: otherwise every further hit on the same enemy would pull another
        // half of the busy mobs off their fights, until the whole army had switched.
        if (free.isEmpty() && !busy.isEmpty() && !someoneOnIt) {
            // Nobody free: the nearest half switches, the rest finish their fight.
            org.joml.Vector3d at = ChaosWand.positionOf(enemy, buffer);
            if (at != null) busy.sort(java.util.Comparator.comparingDouble(n -> ChaosWand.distSq(at, n, buffer)));
            free = busy.subList(0, (busy.size() + 1) / 2);
        }
        Ref<EntityStore> me = entityOf(owner);
        for (Ref<EntityStore> npc : free) {
            engage(npc, enemy, me, buffer);
        }
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("army: order against " + ChaosWand.nameOf(enemy, buffer) + ": "
                + free.size() + " go" + (someoneOnIt ? " (one was already on it)" : ""));
    }

    /** Rule 1: army mob {@code mob} was hit by {@code attacker}: it fights back, with the nearest free mate. */
    static void defend(Ref<EntityStore> mob, Ref<EntityStore> attacker, ComponentAccessor<EntityStore> buffer) {
        UUID owner = ownerOf(mob);
        if (owner == null || attacker == null || !attacker.isValid() || isMember(owner, attacker)
                || attacker.getStore() != mob.getStore() || !BattleManager.isAlive(attacker, buffer)
                || sameChain(mob, leaderKey(mob), attacker, keyOf(attacker, buffer))) {
            return;
        }
        // In a battle, its selfDefense answers (BattleManager.recordDamage); army rules only for mobs outside one.
        if (inBattle(mob)) return;
        Ref<EntityStore> me = entityOf(owner);
        boolean debug = MobBattlePlugin.debugging();
        if (FightManager.enemyOf(mob) != attacker) {
            engage(mob, attacker, me, buffer);
            if (debug) MobBattlePlugin.debug("army: " + ChaosWand.nameOf(mob, buffer) + " was hit by " + ChaosWand.nameOf(attacker, buffer) + ", fights back");
        }
        org.joml.Vector3d at = ChaosWand.positionOf(mob, buffer);
        if (at == null) return;
        List<Ref<EntityStore>> mates = members(owner);
        for (Ref<EntityStore> npc : mates) {
            // One helper: if a mate already fights this attacker, further hits don't pull in more of them.
            if (npc != mob && FightManager.enemyOf(npc) == attacker) return;
        }
        int busy = 0;
        Ref<EntityStore> helper = null;
        double best = Double.MAX_VALUE;
        for (Ref<EntityStore> npc : mates) {
            if (npc == mob || npc.getStore() != mob.getStore() || !BattleManager.isAlive(npc, buffer)) continue;
            if (!isFree(npc, buffer) || inBattle(npc)) {
                busy++;
                continue;
            }
            double d = ChaosWand.distSq(at, npc, buffer);
            if (d < best) {
                best = d;
                helper = npc;
            }
        }
        if (helper != null) engage(helper, attacker, me, buffer);
        if (debug) MobBattlePlugin.debug("army: helper for " + ChaosWand.nameOf(mob, buffer) + " against " + ChaosWand.nameOf(attacker, buffer)
                + ": " + (helper != null ? ChaosWand.nameOf(helper, buffer) + String.format(java.util.Locale.ROOT, " (%.1f blocks away)", Math.sqrt(best))
                : "none free (" + busy + " busy, " + (mates.size() - 1) + " mates)"));
    }

    /**
     * In a running battle (e.g. summonArmy summons in a royale): the battle picks its targets, so the army orders,
     * self-defense and regroup skip it. Before (audit 2026-10-13) an owner's hit pulled every one of them onto that
     * mob (they had no wand fight, so they counted as free) and the battle pulled them back a second later.
     */
    private static boolean inBattle(Ref<EntityStore> npc) {
        return BattleManager.isManaged(npc) && BattleManager.battleOf(npc) != null;
    }

    /** Not fighting, or its target is dead. */
    private static boolean isFree(Ref<EntityStore> npc, ComponentAccessor<EntityStore> buffer) {
        Ref<EntityStore> now = FightManager.enemyOf(npc);
        return now == null || !now.isValid() || !BattleManager.isAlive(now, buffer);
    }

    private static void engage(Ref<EntityStore> npc, Ref<EntityStore> enemy, Ref<EntityStore> owner,
                               ComponentAccessor<EntityStore> buffer) {
        // Group fight: the enemy keeps its other fights, so several army mobs can be on it.
        FightManager.startFight(npc, enemy, buffer, true);
        FightManager.turnAgainst(npc, enemy, buffer, false);
        FOUGHT.add(npc);
        if (owner != null && owner.isValid() && owner.getStore() == npc.getStore()) {
            FightManager.befriend(npc, owner, buffer);
        }
    }

    /** Rule 4 for one owner's mobs in this world: free mobs join the nearest enemy the army still fights. */
    private static void regroup(UUID owner, List<Ref<EntityStore>> mobs, ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> enemies = new ArrayList<>();
        List<Ref<EntityStore>> free = new ArrayList<>();
        for (Ref<EntityStore> npc : mobs) {
            if (!BattleManager.isAlive(npc, buffer) || inBattle(npc)) continue;
            if (isFree(npc, buffer)) {
                if (FightManager.enemyOf(npc) != null) FightManager.endFightsOf(List.of(npc));
                if (FOUGHT.contains(npc)) free.add(npc); // its target died; idle followers stay put
            } else {
                Ref<EntityStore> e = FightManager.enemyOf(npc);
                if (!enemies.contains(e)) enemies.add(e);
            }
        }
        if (free.isEmpty()) return;
        if (enemies.isEmpty()) {
            // Nothing left to fight: they follow the owner again.
            free.forEach(FOUGHT::remove);
            return;
        }
        Ref<EntityStore> me = entityOf(owner);
        for (Ref<EntityStore> npc : free) {
            org.joml.Vector3d at = ChaosWand.positionOf(npc, buffer);
            if (at == null) continue;
            Ref<EntityStore> nearest = null;
            double best = Double.MAX_VALUE;
            for (Ref<EntityStore> e : enemies) {
                double d = ChaosWand.distSq(at, e, buffer);
                if (d < best) {
                    best = d;
                    nearest = e;
                }
            }
            if (nearest != null) engage(npc, nearest, me, buffer);
        }
    }

    /**
     * Blocks from the owner after which an army mob that isn't fighting is teleported back next to them,
     * plus the landing ring ({@link #landingRing}), so a big army isn't pulled back while it stands around them.
     */
    static final double TELEPORT_DISTANCE = 20;

    /** Radius around the owner where teleported army mobs land: grows with the army (as the summon ring), 4 to 20. */
    static double landingRing(int armySize) {
        return Math.max(4, Math.min(20, 3 + Math.sqrt(armySize) * 1.6));
    }
    /** Blocks up or down from the owner's height within which the teleport spot must be (FightFx.standableY). */
    private static final int STAND_RANGE = 6;
    /** Blocks below a flying owner searched for ground when nothing is within STAND_RANGE of their height. */
    private static final int FLY_SEARCH = 96;

    /**
     * Once per second per world (BattleLifecycleSystem, on the world thread): army mobs follow their owner.
     * Their leash point (home) moves to the owner, so roles with a Leash sensor walk back to them; any mob
     * farther than TELEPORT_DISTANCE and not fighting is teleported next to the owner.
     */
    static void follow(com.hypixel.hytale.component.Store<EntityStore> store) {
        syncTints(store);
        // One pass over every army mob of this world, grouped by owner.
        Map<UUID, List<Ref<EntityStore>>> byOwner = new java.util.HashMap<>();
        OWNER.forEach((npc, owner) -> {
            if (npc.isValid() && npc.getStore() == store) byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(npc);
        });
        if (byOwner.isEmpty()) return;
        var tType = com.hypixel.hytale.server.core.modules.entity.component.TransformComponent.getComponentType();
        var npcType = com.hypixel.hytale.server.npc.entities.NPCEntity.getComponentType();
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        byOwner.forEach((owner, mobs) -> {
            regroup(owner, mobs, store);
            Ref<EntityStore> me = entityOf(owner);
            if (me == null || me.getStore() != store) return;
            var mt = store.getComponent(me, tType);
            if (mt == null) return;
            org.joml.Vector3d p = new org.joml.Vector3d(mt.getPosition());
            double ring = landingRing(mobs.size());
            double far = TELEPORT_DISTANCE + ring;
            // An owner fighting in an isolated battle (a leader mob in a duel): the army waits where it is instead of
            // crowding the arena, where it picked fights with the onlookers (playtest 2026-10-29).
            BattleManager.Battle ownerBattle = BattleManager.battleOf(me);
            boolean waits = ownerBattle != null && FxSettings.isolated(ownerBattle.kind);
            long now = System.currentTimeMillis();
            Renew renew = RENEWED.get(owner);
            if (renew == null || renew.me() != me || now - renew.at() > RENEW_MS) {
                RENEWED.put(owner, new Renew(now, me));
                for (Ref<EntityStore> npc : mobs) {
                    if (BattleManager.isAlive(npc, store)) befriendChain(npc, store);
                }
            }
            for (Ref<EntityStore> npc : mobs) {
                if (!BattleManager.isAlive(npc, store)) continue;
                var entity = store.getComponent(npc, npcType);
                var t = store.getComponent(npc, tType);
                if (entity == null || t == null) continue;
                if (waits && !inBattle(npc)) {
                    if (isFree(npc, store)) entity.setLeashPoint(new org.joml.Vector3d(t.getPosition()));
                    continue;
                }
                // Home = the owner only while it isn't fighting: a fighting mob's leash point is where it stands
                // (ChaseSupport), else the soft leash pulled it back to the owner 20-30 blocks into a chase.
                if (isFree(npc, store) && !inBattle(npc)) entity.setLeashPoint(new org.joml.Vector3d(p));
                // A battle member stays in its battle (audit 2026-10-14: an army summon in a royale was pulled out to
                // its owner every second and the arena wall pulled it back).
                if (inBattle(npc) || !isFree(npc, store)
                        || t.getPosition().distanceSquared(p) < far * far) continue;
                // Even spread over the ring's area (sqrt), never closer than 2 blocks.
                double angle = rnd.nextDouble(Math.PI * 2), dist = 2 + Math.sqrt(rnd.nextDouble()) * (ring - 2);
                double x = p.x + Math.cos(angle) * dist, z = p.z + Math.sin(angle) * dist;
                // A standable spot nearest the owner's height; none in reach: no teleport this second. Medium: water
                // for a fish (none near the owner: it stays), dry land for a walker that drowns (2026-10-14).
                FightFx.Spot spot = Medium.spotFor(Medium.of(npc, store));
                double y = FightFx.standableY(x, z, p.y, STAND_RANGE, spot, store);
                // An owner flying high (Creative) has no ground within STAND_RANGE: the ground under that spot then
                // (test 2026-10-14: an owner 22 blocks up never got any army mob back).
                if (!Double.isFinite(y)) y = FightFx.standableY(x, z, p.y - FLY_SEARCH / 2.0, FLY_SEARCH / 2, spot, store);
                if (!Double.isFinite(y)) continue;
                org.joml.Vector3d to = new org.joml.Vector3d(x, y, z);
                if (!FightFx.teleport(npc, to, new com.hypixel.hytale.math.vector.Rotation3f(0f, (float) rnd.nextDouble(Math.PI * 2), 0f), store)) continue;
                WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
            }
        });
    }

    /**
     * The owner's current entity, looked up by UUID (not a stored PlayerRef: after a reconnect the player has
     * a new one, and a stored one would keep a disconnected player's data alive). Null if offline.
     */
    static Ref<EntityStore> entityOf(UUID owner) {
        Ref<EntityStore> leader = LEADERS.get(owner);
        if (leader != null) return leader.isValid() ? leader : null;
        PlayerRef player = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(owner);
        Ref<EntityStore> ref = player != null && player.isValid() ? player.getReference() : null;
        return ref != null && ref.isValid() ? ref : null;
    }

    public static final String ARMY_WAND_ID = "MobBattle_ArmyWand";
    /** Ring color under army mobs (the Army Wand join color). */
    static final String RING_COLOR = "#2a8cff";

    /**
     * Every FightFx.TEAM_RING_REFRESH_MS per world (BattleLifecycleSystem): while an owner holds the Army
     * Wand, a ring at the feet of each of their army mobs, seen only by that owner (like the team rings).
     */
    static void drawRings(com.hypixel.hytale.component.Store<EntityStore> store) {
        Map<UUID, Boolean> holds = new java.util.HashMap<>();
        OWNER.forEach((npc, owner) -> {
            if (!npc.isValid() || npc.getStore() != store) return;
            // The player at the top of its chain sees every mob under them (test 2026-10-14: a leader's goblins in the
            // player's army had no ring): leaders in gold, the rest in blue.
            UUID top = topOfKey(owner);
            // An owner recruiting for someone else sees only that army (below), not two sets of rings.
            if (top == null || PICKS.containsKey(top)) return;
            PlayerRef player = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(top);
            if (player == null) return;
            if (holds.computeIfAbsent(top, o -> FightFx.holdsItem(player, ARMY_WAND_ID, store))) {
                FightFx.teamRing(player, npc, leaderKey(npc) != null ? LEADER_RING_COLOR : RING_COLOR, store);
            }
        });
        // A player recruiting for someone else sees that army instead: its leader in gold, its mobs in blue.
        PICKS.forEach((viewerId, pick) -> {
            PlayerRef viewer = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(viewerId);
            if (viewer == null || !FightFx.holdsItem(viewer, ARMY_WAND_ID, store)) return;
            if (pick.leader() != null && pick.leader().isValid() && pick.leader().getStore() == store) {
                FightFx.teamRing(viewer, pick.leader(), LEADER_RING_COLOR, store);
            }
            for (Ref<EntityStore> npc : members(pick.owner())) {
                if (npc.getStore() == store) FightFx.teamRing(viewer, npc, RING_COLOR, store);
            }
        });
    }

    /**
     * Army tint (/mobbattle armyTint, armyTintColor; default off): army mobs wear the team tint effect of that
     * color for as long as they are in the army. Once per second from follow() (world thread): members get the
     * wanted tint, ex-members and a turned-off setting lose ours (so release, disband and clearAll need no buffer).
     */
    private static void syncTints(com.hypixel.hytale.component.Store<EntityStore> store) {
        String want = FxSettings.armyTint ? Teams.effectIdForColor(Teams.PALETTE.get(FxSettings.armyTintColor), "Tint") : null;
        var ecType = com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent.getComponentType();
        var map = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap();
        for (Map.Entry<Ref<EntityStore>, String> e : TINTED.entrySet()) {
            Ref<EntityStore> npc = e.getKey();
            if (!npc.isValid()) {
                TINTED.remove(npc);
                continue;
            }
            if (npc.getStore() != store || (OWNER.containsKey(npc) && e.getValue().equals(want))) continue;
            TINTED.remove(npc);
            var effects = store.getComponent(npc, ecType);
            int index = map.getIndex(e.getValue());
            if (effects != null && index >= 0 && effects.hasEffect(index)) {
                effects.removeEffect(npc, index, store);
                LookKeeper.touched(npc);
            }
        }
        if (want == null) return;
        var effect = map.getAsset(want);
        if (effect == null) return;
        OWNER.keySet().forEach(npc -> {
            if (!npc.isValid() || npc.getStore() != store || !BattleManager.isAlive(npc, store)) return;
            var effects = store.getComponent(npc, ecType);
            // Also puts it back when something else took it off (Cleanse Wand).
            if (effects == null || (TINTED.containsKey(npc) && effects.hasEffect(map.getIndex(want)))) return;
            effects.addEffect(npc, effect, store);
            TINTED.put(npc, want);
        });
    }

    /**
     * RoleChange: the same mob under a new Ref stays in its army. The new role has no attitude memory yet, so it is
     * befriended with its owner again on the next tick (else it could turn on them).
     */
    static void carry(Ref<EntityStore> old, Ref<EntityStore> neu, com.hypixel.hytale.component.Store<EntityStore> store) {
        // A leader that changes role keeps leading (same UUID, new Ref), and players recruiting for it keep their pick.
        LEADERS.replaceAll((k, v) -> v == old ? neu : v);
        PICKS.replaceAll((k, p) -> p.leader() == old ? new Pick(p.owner(), neu, p.name()) : p);
        UUID owner = OWNER.remove(old);
        UUID id = MEMBER_ID.remove(old);
        if (id != null && owner != null) MEMBER_ID.put(neu, id);
        if (FOUGHT.remove(old) && owner != null) FOUGHT.add(neu);
        TINTED.remove(old);
        if (owner == null) return;
        OWNER.put(neu, owner);
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !neu.isValid()) return;
            Ref<EntityStore> ref = entityOf(owner);
            if (ref != null && ref.getStore() == store) FightManager.befriend(neu, ref, store);
        });
    }

    /** Drops dead or unloaded mobs (BattleManager.maintain). */
    static void prune() {
        // Picks of a gone leader (unless it is changing role) or of a picker who left: else Army.any() stayed true for
        // good and every NPC hit went through the army checks.
        PICKS.values().removeIf(p -> p.leader() != null && !p.leader().isValid() && !RoleChange.pending(p.owner())
                && !AWAY.containsKey(p.owner()));
        long now = System.currentTimeMillis();
        if (AWAY.values().removeIf(a -> now - a.at() > AWAY_MS)) dirty = true;
        PICKS.keySet().removeIf(viewer -> com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(viewer) == null);
        RENEWED.values().removeIf(r -> !r.me().isValid());
        // A dead or unloaded leader: its army goes back to its own AI (RoleChange.carry already moved a role swap).
        for (Map.Entry<UUID, Ref<EntityStore>> e : LEADERS.entrySet()) {
            if (e.getValue().isValid()) continue;
            if (RoleChange.pending(e.getKey())) continue; // a role change re-adds it under its new Ref in a moment
            if (AWAY.containsKey(e.getKey())) continue;   // unloaded with its chunk: it leads again when it loads
            LEADERS.remove(e.getKey());
            dirty = true;
            List<Ref<EntityStore>> mobs = members(e.getKey());
            if (!mobs.isEmpty()) {
                releaseAll(mobs, null);
                MobBattlePlugin.debug("army: leader gone, " + mobs.size() + " mobs released");
            }
        }
        TINTED.keySet().removeIf(npc -> !npc.isValid() || npc.getStore().isShutdown());
        if (OWNER.keySet().removeIf(npc -> !npc.isValid() || npc.getStore().isShutdown())) dirty = true;
        FOUGHT.retainAll(OWNER.keySet());
        MEMBER_ID.keySet().retainAll(OWNER.keySet());
        // A living leader whose army is empty leads nothing any more (add() puts it back when it gets a mob).
        if (LEADERS.entrySet().removeIf(e -> e.getValue().isValid() && !OWNER.containsValue(e.getKey())
                && PICKS.values().stream().noneMatch(p -> p.leader() == e.getValue())
                && AWAY.values().stream().noneMatch(a -> e.getKey().equals(a.owner())))) dirty = true;
        RENEWED.keySet().retainAll(new java.util.HashSet<>(OWNER.values()));
        saveIfDirty();
    }
}
