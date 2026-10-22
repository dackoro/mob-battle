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

/** Army Wand right click: disbands your army; every mob goes back to its own AI. */
public class ArmyDisbandInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ArmyDisband";

    public static final BuilderCodec<ArmyDisbandInteraction> CODEC = BuilderCodec
            .builder(ArmyDisbandInteraction.class, ArmyDisbandInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Army Wand: disbands your army.")
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
        disband(player, context.getEntity(), buffer);
    }

    /** One Army Wand right click (the wand and /mobbattle test wand armydisband). */
    static void disband(PlayerRef player, Ref<EntityStore> me, com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        // Recruiting for someone else (crouch click): that army is the one disbanded.
        Army.Pick pick = Army.pickOf(player.getUuid());
        if (pick != null) {
            var theirs = Army.members(pick.owner());
            for (var npc : theirs) {
                if (npc.getStore() == me.getStore()) WandFx.onTarget(npc, WandFx.ARMY_REMOVE, buffer);
            }
            Ref<EntityStore> ownerEntity = pick.leader() != null ? pick.leader() : Army.entityOf(pick.owner());
            int n = Army.disband(pick.owner(), ownerEntity, buffer);
            player.sendMessage((n == 0
                    ? I18n.tr("chat.army.none.other").param("owner", MobNames.message(pick.name()))
                    : I18n.tr("chat.army.disbanded.other").param("owner", MobNames.message(pick.name())).param("count", n)).color(n == 0 ? "#ffcc55" : "#aaaaaa"));
            return;
        }
        var mobs = Army.members(player.getUuid());
        for (var npc : mobs) {
            if (npc.getStore() == me.getStore()) {
                WandFx.onTarget(npc, WandFx.ARMY_REMOVE, buffer);
            }
        }
        int n = Army.disband(player.getUuid(), me, buffer);
        player.sendMessage((n == 0
                ? I18n.tr("chat.army.none")
                : I18n.tr("chat.army.disbanded").param("count", n)).color(n == 0 ? "#ffcc55" : "#aaaaaa"));
    }
}
