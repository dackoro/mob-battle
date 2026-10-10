package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Effect Wand left click: gives the selected effect (/mobbattle wandeffect) to the clicked entity. */
public class EffectGiveInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_EffectGive";

    public static final BuilderCodec<EffectGiveInteraction> CODEC = BuilderCodec
            .builder(EffectGiveInteraction.class, EffectGiveInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Effect Wand: gives the selected effect to the clicked entity.")
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
        EffectControllerComponent effects = target != null && target.isValid()
                ? buffer.getComponent(target, EffectControllerComponent.getComponentType()) : null;
        if (effects == null) {
            player.sendMessage(Message.raw("Look at a mob and click it.").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        java.util.List<EntityEffect> chain = EffectWand.chain(FxSettings.effect);
        if (chain == null || chain.isEmpty()) {
            player.sendMessage(Message.raw("Unknown effect " + FxSettings.effect + ". Use /mobbattle wandEffect <id>.").color("#ffcc55"));
            return;
        }
        EntityEffect effect = chain.get(0);
        String name = ChaosWand.nameOf(target, buffer);
        boolean ok = effects.addEffect(target, effect, buffer);
        if (ok) {
            // A Jade or Shimmer look stays visible: LookKeeper re-sends it when the mob loses another effect.
            LookKeeper.track(target, effect);
            // A buffed mob wearing the buff's shimmer keeps only its runes and eyes under its new look.
            if (LookKeeper.hasOwnLook(target)) Buffs.refreshLook(target, buffer);
            WandFx.onTarget(target, WandFx.EFFECT, buffer);
            giveRestLater(target, chain, 1, effect);
        }
        player.sendMessage(Message.raw(ok ? "Gave " + FxSettings.effect + " to " + name + "."
                : name + " rejected " + effect.getId() + " (immune or not applicable).").color(ok ? "#b44bff" : "#ffcc55"));
    }

    /**
     * A chain "A>B": gives chain[i] when the previous effect ends (its Duration; 0.5 s for infinite ones). Used to try
     * multi-step looks (e.g. a death that first turns purple, then bursts), since a mob shows one ModelVFX at a time.
     */
    private static void giveRestLater(Ref<EntityStore> target, java.util.List<EntityEffect> chain, int i, EntityEffect previous) {
        if (i >= chain.size() || target == null || !target.isValid()) return;
        float wait = previous.isInfinite() || previous.getDuration() <= 0 ? 0.5f : previous.getDuration();
        var store = target.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !target.isValid()) return;
            EffectControllerComponent fx = store.getComponent(target, EffectControllerComponent.getComponentType());
            EntityEffect next = chain.get(i);
            if (fx != null && fx.addEffect(target, next, store)) {
                LookKeeper.track(target, next);
                if (LookKeeper.hasOwnLook(target)) Buffs.refreshLook(target, store);
            }
            giveRestLater(target, chain, i + 1, next);
        }), (long) (wait * 1000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
