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

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
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
        effects.clearEffects(target, buffer);
        boolean unmorphed = MorphWand.restore(target, buffer);
        WandFx.onTarget(target, WandFx.CLEANSE, buffer);
        if (unmorphed) WandFx.onTarget(target, WandFx.UNMORPH, buffer);
        player.sendMessage(Message.raw("Removed every effect from " + ChaosWand.nameOf(target, buffer) + ".").color("#7fd3ff"));
    }
}
