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

/** Army Wand right click: disbands your army; every mob goes back to its own AI. */
public class ArmyDisbandInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ArmyDisband";

    public static final BuilderCodec<ArmyDisbandInteraction> CODEC = BuilderCodec
            .builder(ArmyDisbandInteraction.class, ArmyDisbandInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Army Wand: disbands your army.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        var mobs = Army.members(player.getUuid());
        for (var npc : mobs) {
            if (npc.getStore() == context.getEntity().getStore()) {
                WandFx.onTarget(npc, WandFx.ARMY_REMOVE, buffer);
            }
        }
        int n = Army.disband(player.getUuid(), context.getEntity(), buffer);
        player.sendMessage(Message.raw(n == 0
                ? "You have no army. Left click mobs with the Army Wand to recruit them."
                : "Army disbanded: " + n + " mobs went back to their own AI.").color(n == 0 ? "#ffcc55" : "#aaaaaa"));
    }
}
