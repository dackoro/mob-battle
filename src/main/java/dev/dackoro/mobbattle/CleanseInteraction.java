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
            player.sendMessage(Message.raw("Look at a mob and click it.").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        // The light comes down now; everything happens when it lands: effects and buffs go (size and health back),
        // and the morph is undone. The unmorph swaps the model, which drops the look's leftover particles on the
        // client, so it comes last, with its own Unmorph burst on the restored model.
        String fx = WandFx.cleanse();
        WandFx.onTarget(target, fx, buffer);
        // Played here, not by the effect: re-applying the effect on a quick second click didn't replay its sound.
        WandFx.burst(null, "SFX_Deployable_Totem_Heal_Despawn", ChaosWand.positionOf(target, buffer), buffer);
        var store = target.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !target.isValid()) return;
            cleanseNow(target, fx, store);
            if (MorphWand.restore(target, store)) WandFx.onTarget(target, WandFx.UNMORPH, store);
        }), LIGHT_LANDS_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        player.sendMessage(Message.raw("Removed every effect from " + ChaosWand.nameOf(target, buffer) + ".").color("#7fd3ff"));
    }
}
