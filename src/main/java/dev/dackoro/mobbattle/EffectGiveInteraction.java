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
            player.sendMessage(I18n.tr("chat.look.mob").color("#ffcc55"));
            return;
        }
        java.util.List<EntityEffect> chain = EffectWand.chain(FxSettings.effect);
        if (chain == null || chain.isEmpty()) {
            player.sendMessage(I18n.tr("chat.effect.unknown").param("effect", FxSettings.effect).color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        boolean ok = give(target, chain, effects, buffer);
        player.sendMessage((ok ? I18n.tr("chat.effect.gave").param("effect", FxSettings.effect).param("name", name)
                : I18n.tr("chat.effect.rejected").param("name", name).param("effect", chain.get(0).getId()))
                .color(ok ? EffectWand.colorOf(FxSettings.effect) : "#ffcc55"));
    }

    /**
     * Gives the chain's first effect to {@code target} (and the rest later, each when the previous ends), with the
     * look tracking and wand feedback. False if the entity rejected it (immune or not applicable).
     */
    static boolean give(Ref<EntityStore> target, java.util.List<EntityEffect> chain, EffectControllerComponent effects,
                        com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        return effects != null && giveAll(java.util.List.of(target), chain, buffer) > 0;
    }

    /**
     * {@link #give} for a whole group (an Effect Wand wave group): one scheduled task for the looks put back after
     * REAPPLY_GAP_MS and one per later chain step, for the whole group (audit 2026-10-14: re-applying jade to 200 jade
     * mobs queued 200 tasks). Returns how many took it.
     */
    static int giveAll(java.util.List<Ref<EntityStore>> targets, java.util.List<EntityEffect> chain,
                       com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        EntityEffect effect = chain.get(0);
        java.util.List<Ref<EntityStore>> given = new java.util.ArrayList<>(), later = new java.util.ArrayList<>();
        for (Ref<EntityStore> target : targets) {
            if (target == null || !target.isValid()) continue;
            EffectControllerComponent effects = buffer.getComponent(target, EffectControllerComponent.getComponentType());
            if (effects == null) continue;
            // Re-applying a look: the old one goes first. The very same effect goes back on a moment later.
            if (LookKeeper.dropLook(target, effect, effects, buffer)) {
                later.add(target);
                continue;
            }
            if (!effects.addEffect(target, effect, buffer)) continue;
            landed(target, effect, buffer, true);
            given.add(target);
        }
        giveRestLater(given, chain, 1, effect);
        if (!later.isEmpty()) giveLater(later, chain, effect);
        return given.size() + later.size();
    }

    /**
     * An effect went on: a Jade or Shimmer look stays visible (LookKeeper re-sends it when the mob loses another effect),
     * a buffed mob wearing the buff's shimmer keeps only its runes and eyes under its new look, and the wand's burst.
     */
    private static void landed(Ref<EntityStore> target, EntityEffect effect,
                               com.hypixel.hytale.component.ComponentAccessor<EntityStore> accessor, boolean burst) {
        LookKeeper.track(target, effect);
        if (LookKeeper.hasOwnLook(target)) Buffs.refreshLook(target, accessor);
        if (burst) WandFx.onTarget(target, WandFx.EFFECT + "_" + EffectWand.burstColor(effect), accessor);
    }

    /** Adds {@code effect} (chain[0]) to every target after LookKeeper.REAPPLY_GAP_MS, then the rest of the chain. */
    private static void giveLater(java.util.List<Ref<EntityStore>> targets, java.util.List<EntityEffect> chain, EntityEffect effect) {
        var store = targets.get(0).getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown()) return;
            java.util.List<Ref<EntityStore>> given = new java.util.ArrayList<>();
            for (Ref<EntityStore> target : targets) {
                if (!target.isValid() || target.getStore() != store) continue;
                EffectControllerComponent fx = store.getComponent(target, EffectControllerComponent.getComponentType());
                if (fx == null || !fx.addEffect(target, effect, store)) continue;
                landed(target, effect, store, true);
                given.add(target);
            }
            giveRestLater(given, chain, 1, effect);
        }), LookKeeper.REAPPLY_GAP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * A chain "A>B": gives chain[i] when the previous effect ends (its Duration; 0.5 s for infinite ones), to the whole
     * group at once. Used to try multi-step looks (e.g. a death that first turns purple, then bursts), since a mob shows
     * one ModelVFX at a time.
     */
    private static void giveRestLater(java.util.List<Ref<EntityStore>> targets, java.util.List<EntityEffect> chain, int i,
                                      EntityEffect previous) {
        if (i >= chain.size() || targets.isEmpty()) return;
        float wait = previous.isInfinite() || previous.getDuration() <= 0 ? 0.5f : previous.getDuration();
        var store = targets.get(0).getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown()) return;
            EntityEffect next = chain.get(i);
            java.util.List<Ref<EntityStore>> still = new java.util.ArrayList<>();
            for (Ref<EntityStore> target : targets) {
                if (!target.isValid() || target.getStore() != store) continue;
                still.add(target);
                EffectControllerComponent fx = store.getComponent(target, EffectControllerComponent.getComponentType());
                if (fx != null && fx.addEffect(target, next, store)) landed(target, next, store, false);
            }
            giveRestLater(still, chain, i + 1, next);
        }), (long) (wait * 1000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
