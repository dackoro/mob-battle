package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Team look: our own infinite entity effects (Server/Entity/Effects/MobBattle) that tint the mob
 * and/or wrap it in a mist of the team color (recolored copy of the vanilla Enrage mist).
 * Changes are queued and applied by FightTickSystem, which has an accessor for every NPC.
 */
public final class TeamFx {

    public static final String[] MODES = {"both", "tint", "mist", "off"};

    /** Entity reference -> (ref, team to show or -1 to clear), isolated across worlds. */
    private record Pending(Ref<EntityStore> npc, String color) {
    }

    private static final Map<Ref<EntityStore>, Pending> PENDING = new ConcurrentHashMap<>();
    /** Entity reference -> id of its latest show(), so a timed clear doesn't undo a newer look. */
    private static final Map<Ref<EntityStore>, Long> SHOWN = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    private TeamFx() {
    }

    public static boolean isMode(String value) {
        for (String m : MODES) {
            if (m.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public static void show(Ref<EntityStore> npc, int team) {
        show(npc, Teams.color(team));
    }

    public static void show(Ref<EntityStore> npc, String color) {
        PENDING.put(npc, new Pending(npc, color));
        long id = SEQ.incrementAndGet();
        SHOWN.put(npc, id);
        int seconds = FxSettings.teamFxSeconds;
        if (seconds > 0) {
            // Timed look: fade it out after N seconds, unless the mob got a newer look meanwhile.
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (SHOWN.remove(npc, id) && npc.isValid()) {
                    PENDING.put(npc, new Pending(npc, null));
                }
            }, seconds, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    /** teamlook onlywand: the color currently shown because the owner holds the Team Wand (absent = hidden). */
    private static final Map<Ref<EntityStore>, String> HELD = new ConcurrentHashMap<>();

    /**
     * /mobbattle teamlook onlywand on: show the look while the owner holds the Team Wand ({@code color}),
     * hide it otherwise ({@code null}). Only queues a change when the state really changes.
     */
    public static void setHeld(Ref<EntityStore> npc, String color) {
        String now = HELD.get(npc);
        if (color == null ? now == null : color.equals(now)) {
            return;
        }
        if (color == null) {
            clear(npc);
        } else {
            show(npc, color);
            HELD.put(npc, color);
        }
    }

    public static void clear(Ref<EntityStore> npc) {
        HELD.remove(npc);
        SHOWN.remove(npc);
        PENDING.put(npc, new Pending(npc, null));
    }

    public static boolean hasPending() {
        return !PENDING.isEmpty();
    }

    /** Drops queued looks of removed mobs (BattleManager.maintain): a dead mob never ticks to consume them. */
    static void prune() {
        PENDING.keySet().removeIf(npc -> !npc.isValid());
        SHOWN.keySet().removeIf(npc -> !npc.isValid());
        HELD.keySet().removeIf(npc -> !npc.isValid());
    }

    /**
     * PlayerFighters.tick (world thread): applies the queued looks of players in this world. FightTickSystem does
     * it for NPCs, but it never ticks players (/mobbattle teams join).
     */
    static void applyPlayers(com.hypixel.hytale.component.Store<EntityStore> store) {
        if (PENDING.isEmpty()) return;
        for (Ref<EntityStore> ref : PENDING.keySet()) {
            if (ref.isValid() && ref.getStore() == store
                    && store.getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType()) != null) {
                apply(ref, store);
            }
        }
    }

    /** Applies the queued change for {@code self}, if any. */
    public static void apply(Ref<EntityStore> self, ComponentAccessor<EntityStore> accessor) {
        Pending p = PENDING.remove(self);
        if (p == null) {
            return;
        }
        EffectControllerComponent effects = accessor.getComponent(self, EffectControllerComponent.getComponentType());
        if (effects == null) {
            return;
        }
        var map = EntityEffect.getAssetMap();
        // Remove every team look first (switching team or mode).
        for (String id : Teams.ALL_EFFECT_IDS) {
            int index = map.getIndex(id);
            if (index >= 0 && effects.hasEffect(index)) {
                effects.removeEffect(self, index, accessor);
            }
        }
        if (p.color() == null || "off".equals(FxSettings.teamFx)) {
            return;
        }
        String mode = FxSettings.teamFx.substring(0, 1).toUpperCase() + FxSettings.teamFx.substring(1);
        String id = Teams.effectIdForColor(p.color(), mode);
        EntityEffect effect = id != null ? map.getAsset(id) : null;
        if (effect != null) {
            effects.addEffect(self, effect, accessor);
        }
    }
}
