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
 * Army Wand: each player's own mobs. Army mobs don't attack their owner and can't be hurt by the owner
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

    /** Mob -> the army tint effect id we put on it (/mobbattle armyTint). Added and removed in follow(). */
    private static final Map<Ref<EntityStore>, String> TINTED = new ConcurrentHashMap<>();

    private Army() {
    }

    static boolean any() {
        return !OWNER.isEmpty() || !TINTED.isEmpty();
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
        FightManager.befriend(npc, playerEntity, buffer);
        return true;
    }

    /** Empties the player's army. Returns how many mobs left it. */
    static int disband(UUID owner, Ref<EntityStore> playerEntity, ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> mobs = members(owner);
        releaseAll(mobs, playerEntity);
        OWNER.values().removeIf(owner::equals);
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
        return n;
    }

    /** After a release, how long the mob and the ones it fought ignore each other (and it its ex-owner). */
    static final double RELEASE_GRACE_SECONDS = 3;

    private static void release(Ref<EntityStore> npc, Ref<EntityStore> playerEntity, ComponentAccessor<EntityStore> buffer) {
        releaseAll(List.of(npc), playerEntity);
    }

    /**
     * Back to their own AI after a short pause: a hostile mob turns on its ex-owner, and the fights the army gave them
     * end on both sides, so same-faction mobs make peace again (user, 2026-10-12: disbanded goblins and the
     * Feastmaster they fought kept going for the whole 10 min forced hostility). An opponent forgets every mob
     * released together: it was turned against several of them over the fight, not only its current one.
     */
    private static void releaseAll(List<Ref<EntityStore>> mobs, Ref<EntityStore> playerEntity) {
        java.util.Set<Ref<EntityStore>> opponents = new java.util.LinkedHashSet<>();
        for (Ref<EntityStore> npc : mobs) {
            OWNER.remove(npc);
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
        if (enemy == null || !enemy.isValid() || isMember(owner, enemy) || !BattleManager.isAlive(enemy, buffer)) {
            return;
        }
        List<Ref<EntityStore>> free = new ArrayList<>(), busy = new ArrayList<>();
        boolean someoneOnIt = false;
        for (Ref<EntityStore> npc : members(owner)) {
            if (npc.getStore() != enemy.getStore() || !BattleManager.isAlive(npc, buffer)) continue;
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
    }

    /** Rule 1: army mob {@code mob} was hit by {@code attacker}: it fights back, with the nearest free mate. */
    static void defend(Ref<EntityStore> mob, Ref<EntityStore> attacker, ComponentAccessor<EntityStore> buffer) {
        UUID owner = ownerOf(mob);
        if (owner == null || attacker == null || !attacker.isValid() || isMember(owner, attacker)
                || attacker.getStore() != mob.getStore() || !BattleManager.isAlive(attacker, buffer)) {
            return;
        }
        Ref<EntityStore> me = entityOf(owner);
        if (FightManager.enemyOf(mob) != attacker) {
            engage(mob, attacker, me, buffer);
        }
        org.joml.Vector3d at = ChaosWand.positionOf(mob, buffer);
        if (at == null) return;
        List<Ref<EntityStore>> mates = members(owner);
        for (Ref<EntityStore> npc : mates) {
            // One helper: if a mate already fights this attacker, further hits don't pull in more of them.
            if (npc != mob && FightManager.enemyOf(npc) == attacker) return;
        }
        Ref<EntityStore> helper = null;
        double best = Double.MAX_VALUE;
        for (Ref<EntityStore> npc : mates) {
            if (npc == mob || npc.getStore() != mob.getStore() || !BattleManager.isAlive(npc, buffer)
                    || !isFree(npc, buffer)) continue;
            double d = ChaosWand.distSq(at, npc, buffer);
            if (d < best) {
                best = d;
                helper = npc;
            }
        }
        if (helper != null) engage(helper, attacker, me, buffer);
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
            if (!BattleManager.isAlive(npc, buffer)) continue;
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
            for (Ref<EntityStore> npc : mobs) {
                if (!BattleManager.isAlive(npc, store)) continue;
                var entity = store.getComponent(npc, npcType);
                var t = store.getComponent(npc, tType);
                if (entity == null || t == null) continue;
                entity.setLeashPoint(new org.joml.Vector3d(p));
                if (!isFree(npc, store)
                        || t.getPosition().distanceSquared(p) < far * far) continue;
                // Even spread over the ring's area (sqrt), never closer than 2 blocks.
                double angle = rnd.nextDouble(Math.PI * 2), dist = 2 + Math.sqrt(rnd.nextDouble()) * (ring - 2);
                double x = p.x + Math.cos(angle) * dist, z = p.z + Math.sin(angle) * dist;
                // A standable spot nearest the owner's height; none in reach: no teleport this second.
                double y = FightFx.standableY(x, z, p.y, STAND_RANGE, store);
                if (!Double.isFinite(y)) continue;
                org.joml.Vector3d to = new org.joml.Vector3d(x, y, z);
                store.putComponent(npc, com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.getComponentType(),
                        com.hypixel.hytale.server.core.modules.entity.teleport.Teleport.createExact(to,
                                new com.hypixel.hytale.math.vector.Rotation3f(0f, (float) rnd.nextDouble(Math.PI * 2), 0f)));
                WandFx.burst(WandFx.SUMMON_PARTICLE, null, to, store);
            }
        });
    }

    /**
     * The owner's current entity, looked up by UUID (not a stored PlayerRef: after a reconnect the player has
     * a new one, and a stored one would keep a disconnected player's data alive). Null if offline.
     */
    private static Ref<EntityStore> entityOf(UUID owner) {
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
            PlayerRef player = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(owner);
            if (player == null) return;
            if (holds.computeIfAbsent(owner, o -> FightFx.holdsItem(player, ARMY_WAND_ID, store))) {
                FightFx.teamRing(player, npc, RING_COLOR, store);
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
        UUID owner = OWNER.remove(old);
        if (FOUGHT.remove(old) && owner != null) FOUGHT.add(neu);
        TINTED.remove(old);
        if (owner == null) return;
        OWNER.put(neu, owner);
        store.getExternalData().getWorld().execute(() -> {
            if (store.isShutdown() || !neu.isValid()) return;
            PlayerRef player = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(owner);
            Ref<EntityStore> ref = player != null && player.isValid() ? player.getReference() : null;
            if (ref != null && ref.isValid() && ref.getStore() == store) FightManager.befriend(neu, ref, store);
        });
    }

    /** Drops dead or unloaded mobs (BattleManager.maintain). */
    static void prune() {
        TINTED.keySet().removeIf(npc -> !npc.isValid());
        OWNER.keySet().removeIf(npc -> !npc.isValid());
        FOUGHT.retainAll(OWNER.keySet());
    }
}
