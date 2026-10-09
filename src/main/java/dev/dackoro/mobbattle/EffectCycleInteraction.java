package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Effect Wand right click: switches to the next effect of the quick list. */
public class EffectCycleInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_EffectCycle";

    public static final BuilderCodec<EffectCycleInteraction> CODEC = BuilderCodec
            .builder(EffectCycleInteraction.class, EffectCycleInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Effect Wand: switches to the next effect of the quick list.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        FxSettings.effect = EffectWand.next(FxSettings.effect);
        FxSettings.save();
        player.sendMessage(Message.raw("Effect Wand: " + FxSettings.effect
                + " (/mobbattle wandEffect <id> for any other).").color("#b44bff"));
    }
}
