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

    static Vector3d positionOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> buffer) {
        TransformComponent t = buffer.getComponent(ref, TransformComponent.getComponentType());
        return t != null ? new Vector3d(t.getPosition()) : null;
    }

    /** NPCs within the configured radius of {@code center}, nearest first, capped at /mobbattle maxmobs. */
    static List<Ref<EntityStore>> npcsAround(Vector3d center, ComponentAccessor<EntityStore> buffer) {
        // getAllEntitiesInSphere returns a thread-local list: copy it right away.
        // Distance once per NPC, not once per sort comparison (n log n component reads and copies).
        List<Near> near = new ArrayList<>();
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, FxSettings.radius, buffer)) {
            if (r != null && r.isValid() && buffer.getComponent(r, NPCEntity.getComponentType()) != null
                    && BattleManager.isAlive(r, buffer)) {
                near.add(new Near(r, distSq(center, r, buffer)));
            }
        }
        near.sort((x, y) -> Double.compare(x.distSq(), y.distSq()));
        int n = Math.min(near.size(), FxSettings.maxMobs);
        List<Ref<EntityStore>> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(near.get(i).ref());
        }
        return out;
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
        return npc != null && npc.getRoleName() != null ? npc.getRoleName() : "mob";
    }

    /** Everyone around {@code target} attacks it. */
    static void focus(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> target,
                      ComponentAccessor<EntityStore> buffer) {
        Vector3d center = positionOf(target, buffer);
        if (center == null) {
            return;
        }
        List<Ref<EntityStore>> around = npcsAround(center, buffer);
        around.removeIf(r -> r.getIndex() == target.getIndex());
        FightFx.area(playerEntity, center, FxSettings.radius, buffer);
        String targetName = nameOf(target, buffer);
        if (around.isEmpty()) {
            player.sendMessage(Message.raw("No mobs within " + FxSettings.radius + " blocks of " + targetName
                    + ". Use /mobbattle arearadius <blocks>.").color(WARN));
            return;
        }

        // A new order replaces any group battle they were in (e.g. a running battle royale).
        List<Ref<EntityStore>> involved = new ArrayList<>(around);
        involved.add(target);
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
            FightFx.link(playerEntity, npc, target, buffer);
        }
        // A 2-side battle so the end shows who won (the target or the attackers).
        BattleManager.Battle battle = BattleManager.startFocus(player, target, targetName, around);
        // The target fights back against the nearest attacker.
        if (firstAttacker != null) {
            BattleManager.setTarget(battle, target, firstAttacker);
            FightManager.turnAgainst(target, firstAttacker, buffer);
        }
        MobBattlePlugin.log("Chaos focus on " + targetName + ": " + around.size() + " mobs, " + attackers + " aggressive");
        player.sendMessage(Message.raw("EVERYONE vs " + targetName + "! " + attackers + " of " + around.size()
                + " mobs attack (radius " + FxSettings.radius + ").").color(FIGHT));
        FightFx.title(player, "EVERYONE vs " + targetName, attackers + " attackers");
    }

    /** Free-for-all: every NPC around {@code center} gets a random opponent. */
    static void brawl(PlayerRef player, Ref<EntityStore> playerEntity, Vector3d center,
                      ComponentAccessor<EntityStore> buffer) {
        List<Ref<EntityStore>> around = npcsAround(center, buffer);
        FightFx.area(playerEntity, center, FxSettings.radius, buffer);
        if (around.size() < 2) {
            player.sendMessage(Message.raw("A free-for-all needs at least 2 mobs within " + FxSettings.radius
                    + " blocks. Use /mobbattle arearadius <blocks>.").color(WARN));
            return;
        }

        // Battle royale: each one starts on a random opponent; when its target dies it goes for
        // the nearest one still standing, until only one is left (FightTickSystem).
        BattleManager.Battle battle = BattleManager.startRoyale(player, around);
        int aggressive = 0;
        for (Ref<EntityStore> npc : around) {
            Ref<EntityStore> enemy;
            do {
                enemy = around.get(ThreadLocalRandom.current().nextInt(around.size()));
            } while (enemy.getIndex() == npc.getIndex());
            BattleManager.setTarget(battle, npc, enemy);
            if (FightManager.turnAgainst(npc, enemy, buffer) == FightManager.Result.AGGRESSIVE) {
                aggressive++;
            }
            FightManager.provoke(npc, enemy, buffer);
            FightFx.link(playerEntity, npc, enemy, buffer);
        }
        MobBattlePlugin.log("Chaos brawl: " + around.size() + " mobs, " + aggressive + " aggressive");
        player.sendMessage(Message.raw("BATTLE ROYALE! " + aggressive + " of " + around.size()
                + " mobs fight until one is left (radius " + FxSettings.radius + ").").color(FIGHT));
        FightFx.title(player, "BATTLE ROYALE!", around.size() + " mobs, last one standing wins");
    }
}
