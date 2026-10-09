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

/** Right click with the Battle Wand: forget the selected mob. */
public class ClearSelectionInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Clear";

    public static final BuilderCodec<ClearSelectionInteraction> CODEC = BuilderCodec
            .builder(ClearSelectionInteraction.class, ClearSelectionInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Battle Wand: clears the player's selected mob.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        if (buffer == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        var selected = FightManager.getSelection(player.getUuid());
        boolean had = FightManager.clear(player.getUuid());
        if (selected != null && selected.isValid() && selected.getStore() == context.getEntity().getStore()) {
            WandFx.onTarget(selected, WandFx.UNSELECT, buffer);
        }
        player.sendMessage(Message.raw(had ? "Selection cleared." : "Nothing selected.").color("#aaaaaa"));
    }
}
