package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chaos Wand logic: group fights inside a radius (/mobbattle arearadius).
 * <ul>
 *   <li>focus: every NPC around the clicked one attacks it.</li>
 *   <li>brawl: battle royale among the NPCs around, until one is left (BattleManager).</li>
 * </ul>
 */
public final class ChaosWand {

    private static final String FIGHT = "#ff6b5e";
    private static final String WARN = "#ffcc55";

    private ChaosWand() {
    }

    private static List<Ref<EntityStore>> withTarget(List<Ref<EntityStore>> around, Ref<EntityStore> target) {
        List<Ref<EntityStore>> all = new ArrayList<>(around);
        all.add(target);
        return all;
    }

    static Vector3d positionOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> buffer) {
        TransformComponent t = buffer.getComponent(ref, TransformComponent.getComponentType());
        return t != null ? new Vector3d(t.getPosition()) : null;
    }

    /** The color of the area just drawn for this player, as hex (red if unknown). */
    /** NPCs within the configured radius of {@code center}, nearest first, capped at /mobbattle maxmobs. */
    static List<Ref<EntityStore>> npcsAround(Vector3d center, ComponentAccessor<EntityStore> buffer) {
        return npcsAround(center, FxSettings.radius, buffer);
    }

    /** Same, within {@code radius} blocks (Snap Wand: snapRadius). */
    static List<Ref<EntityStore>> npcsAround(Vector3d center, double radius, ComponentAccessor<EntityStore> buffer) {
        return npcsAround(center, radius, FxSettings.maxMobs, buffer);
    }

    /** Same query with an explicit per-event cap; does not change the server's persistent maxMobs setting. */
    static List<Ref<EntityStore>> npcsAround(Vector3d center, double radius, int maxMobs,
                                             ComponentAccessor<EntityStore> buffer) {
        return npcsAround(center, radius, false, maxMobs, buffer);
    }

    /** Blocks above and below the center that a column search ({@code column}) reaches: the whole world height. */
    private static final double COLUMN_HALF_HEIGHT = 512;

    /**
     * Same; {@code column}: every height within {@code radius} horizontally (fliers high up, mobs in caves
     * below), not a sphere.
     */
    static List<Ref<EntityStore>> npcsAround(Vector3d center, double radius, boolean column,
                                             ComponentAccessor<EntityStore> buffer) {
        return npcsAround(center, radius, column, FxSettings.maxMobs, buffer);
    }

    static List<Ref<EntityStore>> npcsAround(Vector3d center, double radius, boolean column, int maxMobs,
                                             ComponentAccessor<EntityStore> buffer) {
        // getAllEntitiesInSphere returns a thread-local list: copy it right away.
        // Distance once per NPC, not once per sort comparison (n log n component reads and copies).
        List<Near> near = new ArrayList<>();
        List<Ref<EntityStore>> found = column
                ? TargetUtil.getAllEntitiesInBox(new Vector3d(center.x - radius, center.y - COLUMN_HALF_HEIGHT, center.z - radius),
                        new Vector3d(center.x + radius, center.y + COLUMN_HALF_HEIGHT, center.z + radius), buffer)
                : TargetUtil.getAllEntitiesInSphere(center, radius, buffer);
        for (Ref<EntityStore> r : found) {
            if (r != null && r.isValid() && buffer.getComponent(r, NPCEntity.getComponentType()) != null
                    && BattleManager.isAlive(r, buffer) && (!column || horizontal(center, r, buffer) <= radius)) {
                near.add(new Near(r, distSq(center, r, buffer)));
            }
        }
        near.sort((x, y) -> Double.compare(x.distSq(), y.distSq()));
        int n = Math.min(near.size(), FxSettings.clampMaxMobs(maxMobs));
        LEFT_OUT.set(near.size() - n);
        List<Ref<EntityStore>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(near.get(i).ref());
        }
        return out;
    }

    private static double horizontal(Vector3d center, Ref<EntityStore> r, ComponentAccessor<EntityStore> buffer) {
        Vector3d p = positionOf(r, buffer);
        return p == null ? Double.MAX_VALUE : Math.hypot(p.x - center.x, p.z - center.z);
    }

    /** How many mobs the last npcsAround call on this thread left out because of /mobbattle maxmobs. */
    private static final ThreadLocal<Integer> LEFT_OUT = ThreadLocal.withInitial(() -> 0);

    /** Chat warning when the last npcsAround call hit /mobbattle maxmobs. */
    static void warnLeftOut(PlayerRef player) {
        int left = LEFT_OUT.get();
        if (left > 0) {
            player.sendMessage(Message.raw(left + " mobs were left out: the limit is " + FxSettings.maxMobs
                    + " (/mobbattle maxMobs <2-" + FxSettings.MAX_MOBS_LIMIT + ">).").color(WARN));
        }
    }

    private record Near(Ref<EntityStore> ref, double distSq) {
    }

    /** Squared distance without copying the position; MAX_VALUE if it has no transform. */
    static double distSq(Vector3d c, Ref<EntityStore> r, ComponentAccessor<EntityStore> buffer) {
        TransformComponent t = buffer.getComponent(r, TransformComponent.getComponentType());
        return t == null ? Double.MAX_VALUE : t.getPosition().distanceSquared(c);
    }

    static String nameOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> buffer) {
        NPCEntity npc = buffer.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null && npc.getRoleName() != null) return npc.getRoleName();
        // A player fighter (/mobbattle playerFights): titles and chat say their name.
        PlayerRef player = buffer.getComponent(ref, PlayerRef.getComponentType());
        return player != null ? player.getUsername() : "mob";
    }

    /** Everyone around {@code target} attacks it. */
    static void focus(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> target,
                      ComponentAccessor<EntityStore> buffer) {
        Vector3d center = positionOf(target, buffer);
        if (center == null) {
            return;
        }
        List<Ref<EntityStore>> around = npcsAround(center, buffer);
        around.removeIf(r -> r == target);
        String targetName = nameOf(target, buffer);
        if (around.isEmpty()) {
            // Only the cast animation and this message: no area, no effects.
            player.sendMessage(Message.raw("No mobs within " + FxSettings.radius + " blocks of " + targetName
                    + ". Use /mobbattle areaRadius <blocks>.").color(WARN));
            return;
        }
        BattleManager.Kind kind = BattleManager.Kind.ALL_VS_ONE;
        FightFx.area(ActionFx.Action.ALL_VS_ONE, kind, playerEntity, center, FxSettings.radius, FightFx.areaSeconds(), buffer);
        // A shockwave out to the area edge (/mobbattle wave allVsOne, default gold), with the Snap Wand's sound.
        ActionFx.shockwave(ActionFx.Action.ALL_VS_ONE, center, FxSettings.radius, buffer);
        WandFx.burst(null, WandFx.SNAP_SOUND, center, buffer);
        warnLeftOut(player);
        // The lone target in gold, everyone against it in red (with their runes; /mobbattle runes).
        if (KindFx.runes(kind)) {
            // /mobbattle mobFx allVsOne / attackers.
            String lone = ActionFx.mobFx(ActionFx.Role.LONE);
            String rage = ActionFx.mobFx(ActionFx.Role.ATTACKERS);
            if (lone != null) WandFx.onTarget(target, lone, KindFx.countdown(kind), buffer);
            for (Ref<EntityStore> npc : around) {
                if (rage != null) WandFx.onTarget(npc, rage, KindFx.countdown(kind), buffer);
            }
        }
        FightFx.summonRune(kind, center, buffer);
        FightCamera.intro(kind, player, center, List.of(List.of(target), new ArrayList<>(around)), buffer);
        Countdown.run(kind, player, buffer, "EVERYONE vs " + targetName, withTarget(around, target),
                acc -> focusNow(player, playerEntity, target, targetName, around, center, acc),
                reason -> Buffs.cancelPreLone(target));
        // The lone buff from the countdown on (after Countdown.run: a replaced countdown of this target cancels
        // first), so it is already big and healthy when the mobs around it start hitting.
        if (KindFx.countdown(kind) > 0) Buffs.preLone(target);
    }

    private static void focusNow(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> target,
                                 String targetName, List<Ref<EntityStore>> around, Vector3d center,
                                 ComponentAccessor<EntityStore> buffer) {
        around.removeIf(r -> !BattleManager.isAlive(r, buffer) || !PlayerFighters.canStart(r, buffer));
        if (!BattleManager.isAlive(target, buffer) || !PlayerFighters.canStart(target, buffer) || around.isEmpty()) {
            Buffs.cancelPreLone(target);
            player.sendMessage(Message.raw("EVERYONE vs " + targetName + " cancelled: the mobs are gone.").color(WARN));
            return;
        }

        // A new order replaces any group battle they were in (e.g. a running battle royale).
        List<Ref<EntityStore>> involved = new ArrayList<>(around);
        involved.add(target);
        BattleManager.endChaosBattlesOf(involved);
        BattleManager.detach(involved);

        int attackers = 0;
        Ref<EntityStore> firstAttacker = null;
        for (Ref<EntityStore> npc : around) {
            FightManager.startFight(npc, target, buffer, true);
            if (FightManager.turnAgainst(npc, target, buffer) == FightManager.Result.AGGRESSIVE) {
                attackers++;
                if (firstAttacker == null) {
                    firstAttacker = npc;
                }
            }
            // Each one "feels" a hit from the target, so roles from any mod react.
            FightManager.provoke(npc, target, buffer);
            FightFx.link(BattleManager.Kind.ALL_VS_ONE, playerEntity, npc, target, buffer);
        }
        // A 2-side battle so the end shows who won (the target or the attackers).
        BattleManager.Battle battle = BattleManager.startFocus(player, target, targetName, around);
        Arena.setup(battle, center, FxSettings.radius, FightFx.lastAreaColor(player), buffer);
        WandFx.loneCrown(target, buffer);
        // /mobbattle buff lone: bigger, more health, harder hits, fewer taken (undone in BattleManager.finish).
        Buffs.set(target, Buffs.Source.LONE, 1);
        // The target fights back against the nearest attacker.
        if (firstAttacker != null) {
            BattleManager.setTarget(battle, target, firstAttacker);
            FightManager.turnAgainst(target, firstAttacker, buffer);
        }
        MobBattlePlugin.log("Chaos focus on " + targetName + ": " + around.size() + " mobs, " + attackers + " aggressive");
        player.sendMessage(Message.raw("EVERYONE vs " + targetName + "! " + attackers + " of " + around.size()
                + " mobs attack (radius " + FxSettings.radius + ").").color(FIGHT));
        FightFx.title(BattleManager.Kind.ALL_VS_ONE, player, "EVERYONE vs " + targetName, attackers + " attackers");
    }

    /** Free-for-all: every NPC around {@code center} gets a random opponent. */
    static void brawl(PlayerRef player, Ref<EntityStore> playerEntity, Vector3d center,
                      ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> around = npcsAround(center, buffer);
        // /mobbattle playerFights royale: the players in the area fight too (not in Creative).
        around.addAll(PlayerFighters.playersAround(player, center, FxSettings.radius, buffer));
        if (around.size() < 2) {
            // Only the cast animation and this message: no area, no effects.
            player.sendMessage(Message.raw("A free-for-all needs at least 2 mobs within " + FxSettings.radius
                    + " blocks. Use /mobbattle areaRadius <blocks>.").color(WARN));
            return;
        }
        BattleManager.Kind kind = BattleManager.Kind.ROYALE;
        FightFx.area(ActionFx.Action.ROYALE, kind, playerEntity, center, FxSettings.radius, FightFx.areaSeconds(), buffer);
        // A shockwave out to the area edge, in the area color (same reach as the arena), when the right click's
        // ground slam (SnapSlam) lands: snapHitDelay after the click, like the Snap Wand.
        // /mobbattle wave royale (default purple), with the snap's sound.
        var world = buffer.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            var store = world.getEntityStore().getStore();
            if (store.isShutdown()) return;
            ActionFx.shockwave(ActionFx.Action.ROYALE, center, FxSettings.radius, store);
            WandFx.burst(null, WandFx.SNAP_SOUND, center, store);
        }), FxSettings.snapHitDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        warnLeftOut(player);
        if (KindFx.runes(kind)) {
            // /mobbattle mobFx royale (default purple).
            String brawl = ActionFx.mobFx(ActionFx.Role.ROYALE);
            for (Ref<EntityStore> npc : around) {
                if (brawl != null) WandFx.onTarget(npc, brawl, KindFx.countdown(kind), buffer);
            }
        }
        FightFx.summonRune(kind, center, buffer);
        FightCamera.intro(kind, player, center, List.of(new ArrayList<>(around)), buffer);
        Countdown.run(kind, player, buffer, "BATTLE ROYALE", around, acc -> brawlNow(player, playerEntity, around, center, acc));
    }

    /** How many of the closest opponents a royale fighter picks its first one from. */
    private static final int NEAREST_START = 3;

    /** One of the NEAREST_START closest others of {@code around[i]}, at random (any other if positions are unknown). */
    private static Ref<EntityStore> nearbyOpponent(List<Ref<EntityStore>> around, List<Vector3d> at, int i) {
        Vector3d me = at.get(i);
        List<Integer> others = new ArrayList<>(around.size() - 1);
        for (int j = 0; j < around.size(); j++) if (j != i) others.add(j);
        if (me != null) {
            others.sort((x, y) -> Double.compare(
                    at.get(x) != null ? at.get(x).distanceSquared(me) : Double.MAX_VALUE,
                    at.get(y) != null ? at.get(y).distanceSquared(me) : Double.MAX_VALUE));
        }
        int pick = ThreadLocalRandom.current().nextInt(Math.min(NEAREST_START, others.size()));
        return around.get(others.get(pick));
    }

    private static void brawlNow(PlayerRef player, Ref<EntityStore> playerEntity, List<Ref<EntityStore>> around,
                                 Vector3d center, ComponentAccessor<EntityStore> buffer) {
        around.removeIf(r -> !BattleManager.isAlive(r, buffer) || !PlayerFighters.canStart(r, buffer));
        if (around.size() < 2) {
            player.sendMessage(Message.raw("BATTLE ROYALE cancelled: fewer than 2 mobs left.").color(WARN));
            return;
        }

        // Battle royale: each one starts on a random opponent; when its target dies it goes for
        // the nearest one still standing, until only one is left (FightTickSystem).
        BattleManager.endChaosBattlesOf(around);
        BattleManager.Battle battle = BattleManager.startRoyale(player, around);
        Arena.setup(battle, center, FxSettings.radius, FightFx.lastAreaColor(player), buffer);
        FightManager.splitFlocksAcrossSides(battle, buffer);
        int aggressive = 0;
        // Positions once: each fighter starts on one of its NEAREST_START closest opponents, picked at random.
        // A random opponent anywhere in a big area (radius 100) could be 150+ blocks away: the fight never
        // started before a short timer ran out, and some roles give up chasing that far.
        List<Vector3d> at = new ArrayList<>(around.size());
        for (Ref<EntityStore> r : around) at.add(positionOf(r, buffer));
        for (int i = 0; i < around.size(); i++) {
            Ref<EntityStore> npc = around.get(i);
            Ref<EntityStore> enemy = nearbyOpponent(around, at, i);
            BattleManager.setTarget(battle, npc, enemy);
            if (FightManager.turnAgainst(npc, enemy, buffer) == FightManager.Result.AGGRESSIVE) {
                aggressive++;
            }
            FightManager.provoke(npc, enemy, buffer);
            FightFx.link(BattleManager.Kind.ROYALE, playerEntity, npc, enemy, buffer);
        }
        MobBattlePlugin.log("Chaos brawl: " + around.size() + " mobs, " + aggressive + " aggressive");
        player.sendMessage(Message.raw("BATTLE ROYALE! " + aggressive + " of " + around.size()
                + " mobs fight until one is left (radius " + FxSettings.radius + ").").color(FIGHT));
        FightFx.title(BattleManager.Kind.ROYALE, player, "BATTLE ROYALE!", around.size() + " mobs, last one standing wins");
    }
}
