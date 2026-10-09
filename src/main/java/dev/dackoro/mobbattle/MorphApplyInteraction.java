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
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import org.joml.Vector3d;

import javax.annotation.Nonnull;

/** Morph Wand left click: give the clicked mob the copied model. */
public class MorphApplyInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_MorphApply";

    public static final BuilderCodec<MorphApplyInteraction> CODEC = BuilderCodec
            .builder(MorphApplyInteraction.class, MorphApplyInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand: puts the copied model on the clicked mob.")
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
        if (target == null || !target.isValid() || buffer.getComponent(target, NPCEntity.getComponentType()) == null) {
            player.sendMessage(Message.raw("Look at a mob and click it.").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        MorphWand.Copy copy = MorphWand.copyOf(player.getUuid());
        if (copy == null) {
            player.sendMessage(Message.raw("No model copied yet. Right click a mob, or use /mobbattle morph <model>.").color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        MorphWand.Copy now = MorphWand.read(target, buffer);
        if (now != null && now.modelId().equals(copy.modelId())) {
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            player.sendMessage(Message.raw(name + " already looks like " + copy.modelId() + ".").color("#ffcc55"));
            return;
        }
        if (!MorphWand.apply(target, copy, buffer)) {
            player.sendMessage(Message.raw("Could not apply model " + copy.modelId() + ".").color("#ffcc55"));
            return;
        }
        WandFx.onTarget(target, WandFx.MORPH, buffer);
        // The new model is rebuilt on the client, which can drop particles attached to the old one:
        // also play the burst in the world at the mob.
        Vector3d at = ChaosWand.positionOf(target, buffer);
        if (at != null) WandFx.burst("MobBattle_Copy_Burst", null, at.add(0, 0.8, 0), buffer);
        player.sendMessage(Message.raw(name + " now looks like " + copy.modelId()
                + ". It keeps its own AI and attacks. Cleanse Wand or /mobbattle morph reset to undo.").color("#c88bff"));
    }
}
