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

/** Army Wand left click: the clicked mob joins your army, or leaves it if it was already in it. */
public class ArmyToggleInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ArmyToggle";

    public static final BuilderCodec<ArmyToggleInteraction> CODEC = BuilderCodec
            .builder(ArmyToggleInteraction.class, ArmyToggleInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Army Wand: adds the clicked mob to your army, or removes it.")
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
        if (target == null || !target.isValid() || buffer.getComponent(target, NPCEntity.getComponentType()) == null
                || !BattleManager.isAlive(target, buffer)) {
            player.sendMessage(Message.raw("Look at a mob and click it to add it to your army. Right click the air to disband it.")
                    .color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        boolean added = Army.toggle(player, context.getEntity(), target, buffer);
        WandFx.onTarget(target, added ? WandFx.ARMY_ADD : WandFx.ARMY_REMOVE, buffer);
        int size = Army.members(player.getUuid()).size();
        player.sendMessage(Message.raw(added
                ? name + " joined your army (" + size + "). It attacks whoever you hit or whoever hits you."
                : name + " left your army (" + size + " left).").color(added ? "#4bff6a" : "#aaaaaa"));
    }
}
