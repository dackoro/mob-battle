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

/** Summon Wand left click: adds the clicked mob's type to the summon list, or removes it. */
public class SummonToggleInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_SummonToggle";

    public static final BuilderCodec<SummonToggleInteraction> CODEC = BuilderCodec
            .builder(SummonToggleInteraction.class, SummonToggleInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Summon Wand: adds the clicked mob's type to the summon list, or removes it.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context, Perms.CONFIG)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        NPCEntity npc = target != null && target.isValid() ? buffer.getComponent(target, NPCEntity.getComponentType()) : null;
        if (npc == null || npc.getRoleName() == null) {
            player.sendMessage(Message.raw("Look at a mob and click it to add its type to the summon list.").color("#ffcc55"));
            return;
        }
        String role = npc.getRoleName();
        boolean added = SummonList.toggle(role);
        FxSettings.save();
        WandFx.onTarget(target, added ? WandFx.SUMMON_ADD : WandFx.SUMMON_REMOVE, buffer);
        player.sendMessage(Message.raw((added ? "Added " + role + " x1 to" : "Removed " + role + " from")
                + " the summon list (" + SummonList.ENTRIES.size() + " types). Change counts with /mobbattle summon modify "
                + role + " <count|random>.").color(added ? "#4bff6a" : "#aaaaaa"));
    }
}
