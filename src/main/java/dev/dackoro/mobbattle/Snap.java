package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Snap Wand and /mobbattle snap: snapPercent (default 50%) of the fighters, picked at random, turn to dust (the
 * Kill Wand's soul rip, killDelay and killLoot), one after another over snapTime seconds (default 5).
 * <ul>
 *   <li>Who: the battle of the clicked mob; else every mob within snapRadius of the clicked
 *       mob or the player, at any height, fighting or not, shown by a shockwave.</li>
 *   <li>/mobbattle snapMode global (default): that share of all of them; team: of each side (sides of one mob,
 *       as in a royale, count together).</li>
 *   <li>A fractional count rounds up or down at random, so it stays right on average.</li>
 * </ul>
 * Kills need a CommandBuffer, so the chosen ones are queued and FightTickSystem disintegrates each on its tick.
 * When the last one of a snap is gone, its player gets a message.
 */
final class Snap {

    private static final long FIRST_MS = 300;

    /** One snap: who did it and how many of its chosen are still waiting. */
    private record Wave(UUID player, int chosen, int total, AtomicInteger left) {
    }

    private record Entry(long due, Wave wave) {
    }

    /** Mob -> when it turns to dust, and its snap. */
    private static final Map<Ref<EntityStore>, Entry> QUEUE = new ConcurrentHashMap<>();

    private Snap() {
    }

    static boolean any() {
        return !QUEUE.isEmpty();
    }

    /** snapRadius: the snap's own reach, independent of the battle area (areaRadius). */
    static int radius() {
        return FxSettings.snapRadius;
    }

    /** World thread. Returns how many will turn to dust (0 if nobody was found). */
    static int snap(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> target,
                    ComponentAccessor<EntityStore> buffer) {
        Map<String, List<Ref<EntityStore>>> sides = new LinkedHashMap<>();
        int reach = radius();
        BattleManager.Battle clicked = target != null ? BattleManager.battleOf(target) : null;
        if (clicked != null) {
            // A mob in a battle: that battle, wherever its fighters are.
            for (Ref<EntityStore> m : clicked.members()) {
                if (BattleManager.isAlive(m, buffer) && !QUEUE.containsKey(m)) {
                    sides.computeIfAbsent(clicked.id + ":" + clicked.team(m), k -> new ArrayList<>()).add(m);
                }
            }
        } else {
            // The air or a mob in no battle: every mob within snapRadius (any height), fighting or not;
            // fighters keep their battle side (snapMode team), the rest are one "wild" side.
            Vector3d center = target != null ? ChaosWand.positionOf(target, buffer)
                    : playerEntity != null ? ChaosWand.positionOf(playerEntity, buffer) : null;
            if (center == null) return 0;
            FightFx.shockwave(center, reach, "#c8b6ff", buffer);
            List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, reach, true, buffer));
            if (target != null && !around.contains(target)) around.add(target);
            for (Ref<EntityStore> m : around) {
                if (!BattleManager.isAlive(m, buffer) || QUEUE.containsKey(m)) continue;
                BattleManager.Battle b = BattleManager.battleOf(m);
                sides.computeIfAbsent(b != null ? b.id + ":" + b.team(m) : "wild", k -> new ArrayList<>()).add(m);
            }
        }
        boolean byTeam = "team".equals(FxSettings.snapMode) && sides.values().stream().anyMatch(l -> l.size() > 1);
        List<List<Ref<EntityStore>>> groups = new ArrayList<>();
        if (byTeam) {
            groups.addAll(sides.values());
        } else {
            List<Ref<EntityStore>> all = new ArrayList<>();
            sides.values().forEach(all::addAll);
            groups.add(all);
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Ref<EntityStore>> picked = new ArrayList<>();
        for (List<Ref<EntityStore>> group : groups) {
            Collections.shuffle(group, rnd);
            double share = group.size() * FxSettings.snapPercent / 100.0;
            int n = (int) Math.floor(share) + (rnd.nextDouble() < share - Math.floor(share) ? 1 : 0);
            picked.addAll(group.subList(0, Math.min(n, group.size())));
        }
        int total = sides.values().stream().mapToInt(List::size).sum();
        int chosen = picked.size();
        MobBattlePlugin.debug("snap (" + (byTeam ? "team" : "global") + ", " + FxSettings.snapPercent + "%, "
                + FxSettings.snapSeconds + " s, radius " + reach + "): " + chosen + " of " + total + " from "
                + sides.size() + " sides");
        if (chosen > 0) {
            Wave wave = new Wave(player != null ? player.getUuid() : null, chosen, total, new AtomicInteger(chosen));
            long now = System.currentTimeMillis();
            long spread = Math.max(1, FxSettings.snapSeconds * 1000L - FIRST_MS);
            for (Ref<EntityStore> m : picked) QUEUE.put(m, new Entry(now + FIRST_MS + rnd.nextLong(spread), wave));
            if (playerEntity != null) {
                WandFx.burst(null, WandFx.HAKAI_SOUND, ChaosWand.positionOf(playerEntity, buffer), buffer);
            }
        }
        if (player != null) {
            player.sendMessage(Message.raw(total == 0 ? "Nobody to snap: no mobs within "
                    + reach + " blocks."
                    : chosen == 0 ? "Snap! Nobody was picked (" + FxSettings.snapPercent + "% of " + total + ")."
                    : "Snap! " + chosen + " of " + total + " will turn to dust" + (byTeam ? " (of each side)" : "")
                    + " within " + FxSettings.snapSeconds + " s...")
                    .color(total == 0 ? "#ffcc55" : "#c8b6ff"));
        }
        return chosen;
    }

    /** FightTickSystem, per NPC: disintegrates it once its turn comes. */
    static void apply(Ref<EntityStore> self, long now, CommandBuffer<EntityStore> buffer) {
        Entry e = QUEUE.get(self);
        if (e == null || now < e.due()) return;
        QUEUE.remove(self);
        if (BattleManager.isAlive(self, buffer) && !WandFx.isDying(self)) WandFx.snapKill(self, buffer);
        done(e.wave());
    }

    /**
     * PlayerFighters.tick (world thread): the player fighters' turn. FightTickSystem only ticks NPCs, so players
     * in a snapped battle are disintegrated from here, with the same ash, then die (WandFx.killPlayer).
     */
    static void applyPlayers(com.hypixel.hytale.component.Store<EntityStore> store, long now) {
        if (QUEUE.isEmpty()) return;
        for (Map.Entry<Ref<EntityStore>, Entry> e : QUEUE.entrySet()) {
            Ref<EntityStore> ref = e.getKey();
            if (!ref.isValid() || ref.getStore() != store || now < e.getValue().due()
                    || !BattleManager.isPlayerFighter(ref) || !QUEUE.remove(ref, e.getValue())) {
                continue;
            }
            if (BattleManager.isAlive(ref, store)) WandFx.killPlayer(ref, WandFx.SNAP_DUST, WandFx.SNAP_DUST_MS, store);
            done(e.getValue().wave());
        }
    }

    /** BattleManager.maintain: drops gone mobs (they still count as done for their snap). */
    static void prune() {
        QUEUE.entrySet().removeIf(en -> {
            if (en.getKey().isValid()) return false;
            done(en.getValue().wave());
            return true;
        });
    }

    /** /mobbattle stop: cancels the snaps still pending. */
    static void clearAll() {
        QUEUE.clear();
    }

    private static void done(Wave wave) {
        if (wave.left().decrementAndGet() != 0 || wave.player() == null) return;
        PlayerRef p = Universe.get().getPlayer(wave.player());
        if (p != null && p.isValid()) {
            p.sendMessage(Message.raw("The snap is over: " + wave.chosen() + " of " + wave.total()
                    + " turned to dust, " + (wave.total() - wave.chosen()) + " remain.").color("#c8b6ff"));
        }
        MobBattlePlugin.debug("snap over: " + wave.chosen() + " of " + wave.total());
    }
}
