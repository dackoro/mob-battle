package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only snapshots; temporary hit logging for the four closest NPCs. */
final class BattleDebug {
    private static final Map<Ref<EntityStore>, Long> WATCHED = new ConcurrentHashMap<>();
    private static final AtomicInteger HITS_LEFT = new AtomicInteger();

    private BattleDebug() {}

    static boolean tracing(Ref<EntityStore> source, Ref<EntityStore> victim) {
        // Called for every battle hit: nothing to look up unless a /mobbattle debug is running.
        if (WATCHED.isEmpty()) return false;
        long now = System.currentTimeMillis();
        boolean watched = WATCHED.getOrDefault(source, 0L) > now || WATCHED.getOrDefault(victim, 0L) > now;
        return watched && HITS_LEFT.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
    }

    static void run(Ref<EntityStore> playerRef, Store<EntityStore> store) {
        var world = store.getExternalData().getWorld();
        if (!playerRef.isValid()) return;
        var pos = ChaosWand.positionOf(playerRef, store);
        if (pos == null) return;
        var npcs = com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(pos, 20, store).stream()
                .filter(ref -> ref.isValid() && store.getComponent(ref, NPCEntity.getComponentType()) != null)
                .sorted(java.util.Comparator.comparingDouble(ref -> {
                    var p = ChaosWand.positionOf(ref, store);
                    return p == null ? Double.MAX_VALUE : p.distanceSquared(pos);
                })).limit(4).toList();
        if (npcs.isEmpty()) {
            send(playerRef, store, "No NPCs within 20 blocks.");
            return;
        }
        long until = System.currentTimeMillis() + 10000;
        HITS_LEFT.set(100);
        npcs.forEach(ref -> WATCHED.put(ref, until));
        send(playerRef, store, "Battle debug: " + npcs.size() + " NPCs; snapshots now, +2s, +4s. Hit log for 10s (max 100). No AI changes.");
        snapshot(playerRef, store, npcs, 0);
        for (int seconds : new int[]{2, 4}) {
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    () -> world.execute(() -> snapshot(playerRef, store, npcs, seconds)), seconds, TimeUnit.SECONDS);
        }
    }

    /** BattleManager.maintain: expired or dead watched NPCs (a Ref keeps its world reachable). */
    static void prune() {
        if (WATCHED.isEmpty()) return;
        long now = System.currentTimeMillis();
        WATCHED.entrySet().removeIf(e -> !e.getKey().isValid() || e.getValue() <= now);
    }

    private static void snapshot(Ref<EntityStore> player, Store<EntityStore> store, List<Ref<EntityStore>> npcs, int seconds) {
        if (!player.isValid() || player.getStore() != store) return;
        for (var self : npcs) {
            if (!self.isValid()) continue;
            String line = "[+" + seconds + "s] " + name(self, store) + aiFields(self, store);
            send(player, store, line);
            MobBattlePlugin.log("Battle debug " + line);
        }
    }

    /**
     * The AI part of a snapshot line, starting with " state=": state, alive, frozen, attacking, battle, ordered and
     * locked target, distance, attitude, vanilla canDamage. Read-only; shared with the Debug Wand's inspect.
     */
    static String aiFields(Ref<EntityStore> self, Store<EntityStore> store) {
        var battle = BattleManager.battleOf(self);
        var marked = MarkedEntitySupport.get(self, store);
        var locked = marked != null ? marked.getMarkedEntityRef(FightManager.TARGET_SLOT) : null;
        var ordered = battle != null ? battle.targets.get(self) : FightManager.enemyOf(self);
        var combat = CombatSupport.get(self, store);
        var support = WorldSupport.get(self, store);
        var target = ordered != null && ordered.isValid() ? ordered : locked;
        String distance = "?", attitude = "?", canDamage = "?";
        if (target != null && target.isValid() && target.getStore() == store) {
            var from = ChaosWand.positionOf(self, store);
            var to = ChaosWand.positionOf(target, store);
            if (from != null && to != null) distance = String.format(java.util.Locale.ROOT, "%.1f", from.distance(to));
            if (support != null) attitude = attitudeOf(support, self, target, store);
            if (combat != null) canDamage = String.valueOf(combat.getCanCauseDamage(self, target, store));
        }
        return " state=" + FightManager.stateOf(self, store)
                + " alive=" + BattleManager.isAlive(self, store)
                + " frozen=" + (store.getComponent(self, com.hypixel.hytale.server.core.entity.Frozen.getComponentType()) != null)
                + " attacking=" + (combat != null && combat.isExecutingAttack())
                + " battle=" + (battle == null ? "none" : battle.kind.key + ":" + battle.id.toString().substring(0, 8))
                + " army=" + Army.describe(self)
                + " ordered=" + name(ordered, store) + " locked=" + name(locked, store)
                + " distance=" + distance + " attitude=" + attitude + " vanillaCanDamage=" + canDamage
                + caeFields(self, store);
    }

    /**
     * Combat Action Evaluator roles (Feastmaster, bosses): what the evaluator itself picked and remembers. Its target
     * comes from its own hostile memory, not from LockedTarget (2026-10-12: a Feastmaster boss stood 3.7 blocks from
     * its goblin opponent without one swing).
     */
    private static String caeFields(Ref<EntityStore> self, Store<EntityStore> store) {
        StringBuilder out = new StringBuilder();
        var memory = store.getComponent(self, com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory.getComponentType());
        if (memory != null) {
            out.append(" | memory hostiles=").append(names(memory.getKnownHostilesList(), store))
                    .append(" friendlies=").append(names(memory.getKnownFriendliesList(), store))
                    .append(" closestHostile=").append(name(memory.getClosestHostile(), store));
        }
        var cae = store.getComponent(self, com.hypixel.hytale.builtin.npccombatactionevaluator.evaluator.CombatActionEvaluator.getComponentType());
        if (cae != null) {
            var action = cae.getCurrentAction();
            out.append(" | cae primary=").append(name(cae.getPrimaryTarget(), store))
                    .append(" basicTarget=").append(name(cae.getBasicAttackTarget(), store))
                    .append(" action=").append(action != null && action.getOption() != null ? action.getOption().getClass().getSimpleName() : "none")
                    .append(" attack=").append(cae.getCurrentAttack())
                    .append(" basicAttack=").append(cae.getCurrentBasicAttack())
                    .append(" damageFriendlies=").append(cae.shouldDamageFriendlies());
        }
        return out.toString();
    }

    private static String names(java.util.List<Ref<EntityStore>> refs, Store<EntityStore> store) {
        if (refs == null || refs.isEmpty()) return "[]";
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (Ref<EntityStore> r : refs) {
            if (n > 0) b.append(", ");
            if (n++ == 8) {
                b.append("+").append(refs.size() - 8);
                break;
            }
            b.append(name(r, store));
        }
        return b.append(']').toString();
    }

    /**
     * The role's attitude toward {@code target}, or "n/a": WorldSupport.getAttitude throws a NullPointerException
     * ("attitudeCache is null") on roles without attitude memory (server log 2026-10-12, /mobbattle debug).
     */
    private static String attitudeOf(WorldSupport support, Ref<EntityStore> self, Ref<EntityStore> target,
                                     Store<EntityStore> store) {
        try {
            return String.valueOf(support.getAttitude(self, target, store));
        } catch (RuntimeException ex) {
            return "n/a";
        }
    }

    /** Debug Wand follow: AI state, battle, ordered/locked target and attitude (what changes now and then). */
    static String followFields(Ref<EntityStore> self, Store<EntityStore> store) {
        var battle = BattleManager.battleOf(self);
        var marked = MarkedEntitySupport.get(self, store);
        var locked = marked != null ? marked.getMarkedEntityRef(FightManager.TARGET_SLOT) : null;
        var ordered = battle != null ? battle.targets.get(self) : FightManager.enemyOf(self);
        var support = WorldSupport.get(self, store);
        var target = ordered != null && ordered.isValid() ? ordered : locked;
        String attitude = "?";
        if (target != null && target.isValid() && target.getStore() == store && support != null) {
            attitude = attitudeOf(support, self, target, store);
        }
        return "state=" + FightManager.stateOf(self, store)
                + " battle=" + (battle == null ? "none" : battle.kind.key + ":" + battle.id.toString().substring(0, 8))
                + " army=" + Army.describe(self)
                + " target=" + name(ordered, store) + " locked=" + name(locked, store) + " attitude=" + attitude
                + caeFollow(self, store);
    }

    /** Debug Wand follow: the evaluator's target and action (only roles with one). */
    private static String caeFollow(Ref<EntityStore> self, Store<EntityStore> store) {
        var cae = store.getComponent(self, com.hypixel.hytale.builtin.npccombatactionevaluator.evaluator.CombatActionEvaluator.getComponentType());
        if (cae == null) return "";
        var action = cae.getCurrentAction();
        return " caeTarget=" + name(cae.getPrimaryTarget(), store)
                + " caeAction=" + (action != null && action.getOption() != null ? action.getOption().getClass().getSimpleName() : "none")
                + " caeAttack=" + cae.getCurrentAttack();
    }

    /** Distance to the mob's battle/wand target (else its locked target), as "4.2", or null when it has none. */
    static String targetDistance(Ref<EntityStore> self, Store<EntityStore> store) {
        var battle = BattleManager.battleOf(self);
        var marked = MarkedEntitySupport.get(self, store);
        var locked = marked != null ? marked.getMarkedEntityRef(FightManager.TARGET_SLOT) : null;
        var ordered = battle != null ? battle.targets.get(self) : FightManager.enemyOf(self);
        var target = ordered != null && ordered.isValid() ? ordered : locked;
        if (target == null || !target.isValid() || target.getStore() != store) return null;
        var from = ChaosWand.positionOf(self, store);
        var to = ChaosWand.positionOf(target, store);
        return from == null || to == null ? null : String.format(java.util.Locale.ROOT, "%.1f", from.distance(to));
    }

    private static String name(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || !ref.isValid() || ref.getStore() != store) return "none";
        return ChaosWand.nameOf(ref, store) + "#" + ref.getIndex();
    }

    private static void send(Ref<EntityStore> ref, Store<EntityStore> store, String line) {
        if (!ref.isValid()) return;
        var player = store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        if (player != null) player.sendMessage(Message.raw(line).color("#7fd3ff"));
    }
}
