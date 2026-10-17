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

/** Morph Wand left click: give the clicked mob (or, with morphPlayers, player) the copied model. */
public class MorphApplyInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_MorphApply";

    public static final BuilderCodec<MorphApplyInteraction> CODEC = BuilderCodec
            .builder(MorphApplyInteraction.class, MorphApplyInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand: puts the copied model on the clicked mob.")
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
        Message look = I18n.tr(FxSettings.morphPlayers ? "chat.look.mobOrPlayer" : "chat.look.mob");
        if (target == null || !target.isValid()) {
            player.sendMessage(look.color("#ffcc55"));
            return;
        }
        boolean isPlayer = PlayerFighters.isPlayer(target, buffer);
        if (isPlayer && !FxSettings.morphPlayers) {
            player.sendMessage(I18n.tr("chat.morph.noPlayers").color("#ffcc55"));
            return;
        }
        if (!isPlayer && buffer.getComponent(target, NPCEntity.getComponentType()) == null) {
            player.sendMessage(look.color("#ffcc55"));
            return;
        }
        MorphWand.Copy copy = MorphWand.copyOf(player.getUuid());
        if (copy == null) {
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            player.sendMessage(I18n.tr("chat.morph.noCopy").color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        MorphWand.Copy now = MorphWand.read(target, buffer);
        if (copy.sameLook(now)) {
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            player.sendMessage(I18n.tr("chat.morph.already").param("name", MobNames.message(name)).param("model", copy.label()).color("#ffcc55"));
            return;
        }
        if (!MorphWand.apply(target, copy, buffer)) {
            player.sendMessage(I18n.tr("chat.morph.failed").param("model", copy.label()).color("#ffcc55"));
            return;
        }
        WandFx.onTarget(target, WandFx.MORPH, buffer);
        // The new model is rebuilt on the client, which can drop particles attached to the old one:
        // also play the burst in the world at the mob.
        Vector3d at = ChaosWand.positionOf(target, buffer);
        if (at != null) WandFx.burst("MobBattle_Morph_Burst", null, at.add(0, 0.8, 0), buffer);
        // The omnitrix transformation sound, played here and not by the effect: a re-applied effect (quick clicks)
        // does not replay its sound.
        WandFx.burst(null, "SFX_MobBattle_OmnitrixTransform", ChaosWand.positionOf(target, buffer), buffer);
        player.sendMessage(I18n.tr(isPlayer ? "chat.morph.appliedPlayer" : "chat.morph.applied")
                .param("name", MobNames.message(name)).param("model", copy.label()).color("#7dff8a"));
    }
}
