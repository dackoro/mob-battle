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

/**
 * Morph Wand held left click (the charged branch of a vanilla Charging interaction, as the staffs' charged cast):
 * every mob in morphBurstRadius takes the copied look (/mobbattle morphBurst). Off: it acts as a normal left click.
 */
public class MorphBurstInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_MorphBurst";

    public static final BuilderCodec<MorphBurstInteraction> CODEC = BuilderCodec
            .builder(MorphBurstInteraction.class, MorphBurstInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand held left click: every mob around takes the copied model.")
            .build();

    /** True for the held right click (the looks around are swapped), false for the held left click. */
    protected boolean shuffle() {
        return false;
    }

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        boolean on = shuffle() ? FxSettings.morphShuffle : FxSettings.morphBurst;
        if (!on) {
            // Turned off: the held click does what a quick one does.
            if (shuffle()) new MorphCopyInteraction().firstRun(type, context, cooldownHandler);
            else new MorphApplyInteraction().firstRun(type, context, cooldownHandler);
            return;
        }
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        int changed = MorphWand.burst(context.getEntity(), player, shuffle(), buffer);
        if (changed < 0) return;
        String what = shuffle() ? "swapped looks" : "now look like " + MorphWand.copyOf(player.getUuid()).label();
        player.sendMessage(Message.raw(changed == 0
                ? (shuffle() ? "Nobody within " + FxSettings.morphBurstRadius + " blocks to swap looks with."
                        : "Nobody within " + FxSettings.morphBurstRadius + " blocks to morph.")
                : changed + (changed == 1 ? " mob " : " mobs ") + what
                        + ". Cleanse Wand or /mobbattle morph reset to undo.").color(changed == 0 ? "#ffcc55" : "#7dff8a"));
    }
}
