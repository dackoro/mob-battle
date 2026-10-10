package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Buff Wand left click: one more buff stack on the clicked mob or player (/mobbattle buff wand). */
public class BuffGiveInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_BuffGive";

    public static final BuilderCodec<BuffGiveInteraction> CODEC = BuilderCodec
            .builder(BuffGiveInteraction.class, BuffGiveInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Buff Wand: one more buff stack on the clicked entity.")
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
        if (target == null || !target.isValid() || buffer.getComponent(target, EntityStatMap.getComponentType()) == null) {
            player.sendMessage(Message.raw("Look at a mob or player and click it.").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        if (!Buffs.enabled(Buffs.Source.WAND)) {
            player.sendMessage(Message.raw("The Buff Wand is off: set it with /mobbattle buff wand <stat> <percent>").color("#ffcc55"));
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            context.getState().state = InteractionState.Failed;
            return;
        }
        int before = Buffs.wandStacks(target);
        if (before >= Buffs.wandMax) {
            // Already at the top: the error look and a message, no buff look (nothing changed).
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            player.sendMessage(Message.raw(name + " is already at the max: " + before + "/" + Buffs.wandMax + " stacks ("
                    + Buffs.describeWand(before) + "). Raise it with /mobbattle buff wand maxStacks <n>").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        int stacks = Buffs.addWandStack(target);
        // buffColor: the mark's tint, burst, aura and rune; the swirl comes from Buffs.set once the buff is on.
        WandFx.onTarget(target, WandFx.MARK + FxSettings.buffColor, buffer);
        String msg = "Buffed " + name + ": " + stacks + "/" + Buffs.wandMax + " stacks (" + Buffs.describeWand(stacks) + ")"
                + (stacks == Buffs.wandMax ? ". Max reached." : ".");
        player.sendMessage(Message.raw(msg).color("#ffd23e"));
    }
}
