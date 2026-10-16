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

/** Morph Wand right click: on a mob, copy its model; in the air, show the copied model. */
public class MorphCopyInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_MorphCopy";

    /** The "what you have copied" hint (a right click on nothing) at most this often per player. */
    private static final long HINT_GAP_MS = 3000;
    /** Player -> when the hint last showed. One small entry per player who used the wand. */
    private static final java.util.Map<java.util.UUID, Long> LAST_HINT = new java.util.concurrent.ConcurrentHashMap<>();

    public static final BuilderCodec<MorphCopyInteraction> CODEC = BuilderCodec
            .builder(MorphCopyInteraction.class, MorphCopyInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand: copies the clicked entity's model.")
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
        MorphWand.Copy copy = target != null && target.isValid() ? MorphWand.read(target, buffer) : null;
        if (copy == null) {
            // Once per HINT_GAP_MS: a right click with no mob under the cursor came 4-5 times in a row (user,
            // 2026-10-16: the chat filled with the yellow line, which read as if it had copied from far away).
            long now = System.currentTimeMillis();
            Long last = LAST_HINT.get(player.getUuid());
            if (last != null && now - last < HINT_GAP_MS) return;
            LAST_HINT.put(player.getUuid(), now);
            MorphWand.Copy current = MorphWand.copyOf(player.getUuid());
            player.sendMessage((current == null
                    ? I18n.tr("chat.morph.copyHint")
                    : I18n.tr("chat.morph.copyCurrent").param("model", current.label())).color("#ffcc55"));
            return;
        }
        MorphWand.setCopy(player.getUuid(), copy);
        WandFx.onTarget(target, WandFx.COPY, buffer);
        // The omnitrix "recalibrated" sound, played here and not by the effect: a re-applied effect (quick clicks)
        // does not replay its sound.
        WandFx.burst(null, "SFX_MobBattle_OmnitrixCopy", ChaosWand.positionOf(target, buffer), buffer);
        player.sendMessage(I18n.tr("chat.morph.copied").param("name", ChaosWand.nameOf(target, buffer))
                .param("model", copy.label()).color("#7dff8a"));
    }
}
