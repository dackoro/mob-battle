package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Effect Wand left click: gives the selected effect (/mobbattle effect) to the clicked entity. */
public class EffectGiveInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_EffectGive";

    public static final BuilderCodec<EffectGiveInteraction> CODEC = BuilderCodec
            .builder(EffectGiveInteraction.class, EffectGiveInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Effect Wand: gives the selected effect to the clicked entity.")
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
        EntityEffect effect = EffectWand.find(FxSettings.effect);
        if (effect == null) {
            player.sendMessage(Message.raw("Unknown effect " + FxSettings.effect + ". Use /mobbattle effect <id>.").color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        boolean ok = effects.addEffect(target, effect, buffer);
        player.sendMessage(Message.raw(ok ? "Gave " + effect.getId() + " to " + name + "."
                : name + " rejected " + effect.getId() + " (immune or not applicable).").color(ok ? "#b44bff" : "#ffcc55"));
    }
}
