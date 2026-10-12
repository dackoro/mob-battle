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

/** Morph Wand right click: on a mob, copy its model; in the air, show the copied model. */
public class MorphCopyInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_MorphCopy";

    public static final BuilderCodec<MorphCopyInteraction> CODEC = BuilderCodec
            .builder(MorphCopyInteraction.class, MorphCopyInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand: copies the clicked entity's model.")
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
        MorphWand.Copy copy = target != null && target.isValid() ? MorphWand.read(target, buffer) : null;
        if (copy == null) {
            MorphWand.Copy current = MorphWand.copyOf(player.getUuid());
            player.sendMessage(Message.raw(current == null
                    ? "Right click a mob to copy its model, or use /mobbattle morph <model>."
                    : "Copied model: " + current.modelId() + ". Left click a mob to apply it.").color("#ffcc55"));
            return;
        }
        MorphWand.setCopy(player.getUuid(), copy);
        WandFx.onTarget(target, WandFx.COPY, buffer);
        // The omnitrix "recalibrated" sound, played here and not by the effect: a re-applied effect (quick clicks)
        // does not replay its sound.
        WandFx.burst(null, "SFX_MobBattle_OmnitrixCopy", ChaosWand.positionOf(target, buffer), buffer);
        player.sendMessage(Message.raw("Copied the model of " + ChaosWand.nameOf(target, buffer) + " (" + copy.modelId()
                + "). Left click a mob to give it this look.").color("#7dff8a"));
    }
}
