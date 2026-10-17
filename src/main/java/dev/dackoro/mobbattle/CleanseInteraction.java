package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Cleanse Wand: removes every effect from the clicked entity. */
public class CleanseInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Cleanse";

    public static final BuilderCodec<CleanseInteraction> CODEC = BuilderCodec
            .builder(CleanseInteraction.class, CleanseInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Cleanse Wand: removes every effect from the clicked entity.")
            .build();

    /**
     * Look to the light landing on the mob: MobBattle_Light_&lt;Color&gt; rays start 0.15 s in, 2.5-3 blocks above
     * the head, falling at 10-15 blocks/s (Effect_Crown_Gold spawners).
     */
    private static final long LIGHT_LANDS_MS = 400;

    static final String SOUND = "SFX_Deployable_Totem_Heal_Despawn";

    /** Every buff and effect goes, except the Cleanse look itself. World thread. */
    static void cleanseNow(com.hypixel.hytale.component.Ref<EntityStore> target, String keepFx,
                           com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor) {
        // Buffs first: they take their crown and put back the scale and max health.
        Buffs.clearNow(target, accessor);
        // The look goes too (removed on purpose): LookKeeper must not put it back.
        LookKeeper.untrack(target);
        EffectControllerComponent effects = accessor.getComponent(target, EffectControllerComponent.getComponentType());
        if (effects == null) return;
        int keep = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + keepFx);
        int unmorph = com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect.getAssetMap().getIndex("MobBattle_Fx_" + WandFx.UNMORPH);
        for (int index : effects.getActiveEffectIndexes()) {
            if (index == keep || index == unmorph) continue;
            effects.removeEffect(target, index,
                    com.hypixel.hytale.server.core.asset.type.entityeffect.config.RemovalBehavior.COMPLETE, accessor);
        }
    }

    /**
     * The light comes down now (look on the mob, sound at it); everything happens when it lands, in
     * {@link #landLater}. Returns the look's fx name. Any thread with a buffer for the target's world.
     */
    static String start(Ref<EntityStore> target, boolean sound, com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        String fx = WandFx.cleanse(target, buffer);
        WandFx.onTarget(target, fx, buffer);
        // Played here, not by the effect: re-applying the effect on a quick second click didn't replay its sound.
        // An area cleanse plays it once at the caster instead of once per mob.
        if (sound) WandFx.burst(null, SOUND, ChaosWand.positionOf(target, buffer), buffer);
        return fx;
    }

    /**
     * When the light lands (LIGHT_LANDS_MS, one task for all of them, on their world thread): effects and buffs go
     * (size and health back), and the morph is undone. The unmorph swaps the model, which drops the look's leftover
     * particles on the client, so it comes last, with its own Unmorph burst on the restored model. All targets are in
     * one world.
     */
    static void landLater(java.util.List<Ref<EntityStore>> targets, java.util.List<String> fxs) {
        if (targets.isEmpty()) return;
        var store = targets.get(0).getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown()) return;
            for (int i = 0; i < targets.size(); i++) {
                Ref<EntityStore> target = targets.get(i);
                if (!target.isValid()) continue;
                cleanseNow(target, fxs.get(i), store);
                if (MorphWand.restore(target, store)) WandFx.onTarget(target, WandFx.UNMORPH, store);
            }
        }), LIGHT_LANDS_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        EffectControllerComponent effects = target != null && target.isValid()
                ? buffer.getComponent(target, EffectControllerComponent.getComponentType()) : null;
        if (effects == null) {
            player.sendMessage(I18n.tr("chat.look.mob").color("#ffcc55"));
            return;
        }
        landLater(java.util.List.of(target), java.util.List.of(start(target, true, buffer)));
        player.sendMessage(I18n.tr("chat.cleanse.done").param("name", MobNames.message(ChaosWand.nameOf(target, buffer))).color("#7fd3ff"));
    }
}
