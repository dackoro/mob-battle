package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Debug Wand right click: stops following the mob of the last left click (works anywhere, no target needed). */
public class DebugTrackInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_DebugTrack";

    public static final BuilderCodec<DebugTrackInteraction> CODEC = BuilderCodec
            .builder(DebugTrackInteraction.class, DebugTrackInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Debug Wand: stops following the mob of the last left click.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context, Perms.ADMIN)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        DebugWand.stopTrack(player);
    }
}
