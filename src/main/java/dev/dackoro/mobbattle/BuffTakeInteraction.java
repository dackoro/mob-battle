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

/** Buff Wand right click: takes the wand's buff off the clicked mob or player (other buffs stay). */
public class BuffTakeInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_BuffTake";

    public static final BuilderCodec<BuffTakeInteraction> CODEC = BuilderCodec
            .builder(BuffTakeInteraction.class, BuffTakeInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Buff Wand: takes the wand's buff off the clicked entity.")
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
        // Stacks still coming down with the light count: they are cancelled, else they would land after this.
        if (target == null || !target.isValid() || Buffs.wandStacksSoon(target) == 0) {
            player.sendMessage(I18n.tr("chat.buff.takeLook").color("#ffcc55"));
            return;
        }
        Buffs.cancelFalling(target);
        Buffs.set(target, Buffs.Source.WAND, 0);
        WandFx.onTarget(target, WandFx.UNSELECT_QUIET, buffer);
        // Played here, not by the effect: an effect's sound does not replay on a quick second click.
        WandFx.burst(null, "SFX_Deployable_Totem_Heal_Despawn", ChaosWand.positionOf(target, buffer), buffer);
        // After the size goes back (Buffs.set, queued): the new scale rebuilds the mob and would drop particles.
        WandFx.swirlLater(target, "Gray");
        WandFx.buffTakeLater(target);
        player.sendMessage(I18n.tr("chat.buff.taken").param("name", ChaosWand.nameOf(target, buffer)).color("#7fd3ff"));
    }
}
