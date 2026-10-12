package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Debug Wand left click: full report of the clicked entity (chat summary, server log, debug/ file), then a follow. */
public class DebugInspectInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_DebugInspect";

    public static final BuilderCodec<DebugInspectInteraction> CODEC = BuilderCodec
            .builder(DebugInspectInteraction.class, DebugInspectInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Debug Wand: dumps everything about the clicked entity.")
            .build();

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
        if (target == null || !target.isValid()) {
            player.sendMessage(Message.raw("Look at a mob and click it.").color("#ffcc55"));
            return;
        }
        DebugWand.inspect(player, target, buffer);
    }
}
