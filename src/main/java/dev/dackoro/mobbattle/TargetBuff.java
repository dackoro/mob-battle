package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everyone vs one: the lone target gets an edge for the battle (/mobbattle targetBuff size|health|damage|defense
 * &lt;percent&gt;). Size scales its model (as the Morph Wand, NPCs only), health multiplies its max health with a
 * StaticModifier (MAX, MULTIPLICATIVE) and fills it up, damage and defense scale its hits in FightDamageSystem.
 * Everything goes back when the battle ends (BattleManager.finish). The model and the modifier are saved with the
 * world like any component, so a server that stops mid-battle keeps them.
 */
final class TargetBuff {

    /** Key of our health modifier in the EntityStatMap. */
    private static final String MODIFIER_KEY = "MobBattle_TargetBuff";

    /** What was changed on a buffed target, to undo it. */
    private record Buff(Model original, Model scaled, boolean health) {
    }

    private static final Map<Ref<EntityStore>, Buff> BUFFED = new ConcurrentHashMap<>();
    /** Ended buffs whose undo is queued on the world thread; a new buff on the same target takes over instead. */
    private static final Map<Ref<EntityStore>, Buff> UNDOING = new ConcurrentHashMap<>();

    private TargetBuff() {
    }

    /** True if any targetBuff setting is on. */
    static boolean enabled() {
        return FxSettings.buffSize > 0 || FxSettings.buffHealth > 0 || FxSettings.buffDamage > 0 || FxSettings.buffDefense > 0;
    }

    /** Right after an everyone vs one battle starts (world thread). */
    static void apply(Ref<EntityStore> target, ComponentAccessor<EntityStore> buffer) {
        if (target == null || !target.isValid() || BUFFED.containsKey(target)) return;
        // Picked again before its last buff was undone: start from what it had before that one.
        Buff previous = UNDOING.remove(target);
        if (!enabled()) {
            if (previous != null) undo(target, previous, buffer);
            return;
        }
        Model original = null, scaled = null;
        boolean npc = buffer.getComponent(target, NPCEntity.getComponentType()) != null;
        ModelComponent mc = buffer.getComponent(target, ModelComponent.getComponentType());
        Model base = previous != null && previous.original() != null && mc != null && mc.getModel() == previous.scaled()
                ? previous.original() : mc != null ? mc.getModel() : null;
        if (FxSettings.buffSize > 0 && npc && base != null && base.getModelAssetId() != null) {
            ModelAsset asset = ModelAsset.getAssetMap().getAsset(base.getModelAssetId());
            if (asset != null) {
                original = base;
                float scale = (original.getScale() > 0 ? original.getScale() : 1f) * (1 + FxSettings.buffSize / 100f);
                scaled = Model.createScaledModel(asset, scale);
                buffer.putComponent(target, ModelComponent.getComponentType(), new ModelComponent(scaled));
            }
        }
        boolean health = false;
        EntityStatMap stats = buffer.getComponent(target, EntityStatMap.getComponentType());
        if (FxSettings.buffHealth > 0 && stats != null) {
            int index = DefaultEntityStatTypes.getHealth();
            stats.putModifier(index, MODIFIER_KEY, new StaticModifier(Modifier.ModifierTarget.MAX,
                    StaticModifier.CalculationType.MULTIPLICATIVE, 1 + FxSettings.buffHealth / 100f));
            stats.maximizeStatValue(index);
            health = true;
        }
        if (previous != null) {
            // What the new buff doesn't redo goes back now.
            undo(target, new Buff(original == null ? previous.original() : null, previous.scaled(),
                    previous.health() && !health), buffer);
        }
        BUFFED.put(target, new Buff(original, scaled, health));
    }

    /** At the battle's end: undoes the buff, on the target's world thread. */
    static void clear(Ref<EntityStore> target) {
        Buff buff = target != null ? BUFFED.remove(target) : null;
        if (buff == null || !target.isValid()) return;
        Store<EntityStore> store = target.getStore();
        if (store.isShutdown()) return;
        UNDOING.put(target, buff);
        store.getExternalData().getWorld().execute(() -> {
            // Gone from UNDOING = a new buff took it over (apply).
            if (UNDOING.remove(target, buff) && target.isValid()) undo(target, buff, store);
        });
    }

    /** Puts back the model (if it still has ours: a morph applied meanwhile stays) and drops the health modifier. */
    private static void undo(Ref<EntityStore> target, Buff buff, ComponentAccessor<EntityStore> accessor) {
        if (buff.original() != null) {
            ModelComponent mc = accessor.getComponent(target, ModelComponent.getComponentType());
            if (mc != null && mc.getModel() == buff.scaled()) {
                accessor.putComponent(target, ModelComponent.getComponentType(), new ModelComponent(buff.original()));
            }
        }
        if (buff.health()) {
            EntityStatMap stats = accessor.getComponent(target, EntityStatMap.getComponentType());
            if (stats != null) stats.removeModifier(DefaultEntityStatTypes.getHealth(), MODIFIER_KEY);
        }
    }

    /** Hit damage between battle enemies: more from a buffed target (damage), less to it (defense). */
    static float scale(Ref<EntityStore> attacker, Ref<EntityStore> victim, float amount) {
        if (BUFFED.isEmpty()) return amount;
        if (FxSettings.buffDamage > 0 && BUFFED.containsKey(attacker)) amount *= 1 + FxSettings.buffDamage / 100f;
        if (FxSettings.buffDefense > 0 && BUFFED.containsKey(victim)) amount *= 1 - FxSettings.buffDefense / 100f;
        return amount;
    }

    /** BattleManager.maintain: drops dead or unloaded targets. */
    static void prune() {
        BUFFED.keySet().removeIf(r -> !r.isValid());
        UNDOING.keySet().removeIf(r -> !r.isValid());
    }

    /** "size 10%, health 50%..." or "off". */
    static String describe() {
        if (!enabled()) return "off";
        return "size " + FxSettings.buffSize + "%, health " + FxSettings.buffHealth + "%, damage " + FxSettings.buffDamage
                + "%, defense " + FxSettings.buffDefense + "%";
    }
}
