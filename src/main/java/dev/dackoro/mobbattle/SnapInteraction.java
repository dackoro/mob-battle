package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Snap Wand: half of the fighters (the clicked mob's battle, your battles, or the mobs around) turn to dust. */
public class SnapInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Snap";

    public static final BuilderCodec<SnapInteraction> CODEC = BuilderCodec
            .builder(SnapInteraction.class, SnapInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Snap Wand: half of the fighters turn to dust.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> self = context.getEntity();
        if (buffer == null || self == null || !self.isValid()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        PlayerRef player = buffer.getComponent(self, PlayerRef.getComponentType());
        if (Snap.snap(player, self, target != null && target.isValid() ? target : null, buffer) == 0) {
            context.getState().state = InteractionState.Failed;
        }
    }
}
