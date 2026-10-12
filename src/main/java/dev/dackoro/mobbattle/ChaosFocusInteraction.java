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

import javax.annotation.Nonnull;

/** Chaos Wand left click: every mob in the radius attacks the clicked mob. */
public class ChaosFocusInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ChaosFocus";

    public static final BuilderCodec<ChaosFocusInteraction> CODEC = BuilderCodec
            .builder(ChaosFocusInteraction.class, ChaosFocusInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Chaos Wand: every mob in the radius attacks the clicked mob.")
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
        if (PlayerFighters.isPlayer(target, buffer)) {
            // Everyone vs that player (/mobbattle playerFights allvsone).
            if (!PlayerFighters.eligible(player, target, BattleManager.Kind.ALL_VS_ONE, buffer)) {
                return;
            }
            BattleManager.markPlayer(target, player.getUuid());
            ChaosWand.focus(player, context.getEntity(), target, buffer);
            return;
        }
        if (target == null || !target.isValid() || buffer.getComponent(target, NPCEntity.getComponentType()) == null
                || !BattleManager.isAlive(target, buffer)) {
            player.sendMessage(Message.raw("Look at a living mob and click it.").color("#ffcc55"));
            return;
        }
        ChaosWand.focus(player, context.getEntity(), target, buffer);
    }
}
