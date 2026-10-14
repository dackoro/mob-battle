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
import com.hypixel.hytale.server.core.util.TargetUtil;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Effect Wand held left click (the charged branch of a vanilla Charging interaction, as the staffs' charged cast): a
 * shockwave in the effect's color, and every mob and player within effectRadius (the caster only with effectAreaSelf) gets the selected
 * effect as by a click (/mobbattle effectArea). Off: it acts as a normal left click.
 */
public class EffectAreaInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_EffectArea";

    public static final BuilderCodec<EffectAreaInteraction> CODEC = BuilderCodec
            .builder(EffectAreaInteraction.class, EffectAreaInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Effect Wand held left click: gives the selected effect to every mob and player around.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!FxSettings.effectArea) {
            // Turned off: the held click does what a quick one does.
            new EffectGiveInteraction().firstRun(type, context, cooldownHandler);
            return;
        }
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        List<EntityEffect> chain = EffectWand.chain(FxSettings.effect);
        if (chain == null || chain.isEmpty()) {
            player.sendMessage(I18n.tr("chat.effect.unknown").param("effect", FxSettings.effect).color("#ffcc55"));
            return;
        }
        Ref<EntityStore> caster = context.getEntity();
        // The held click plays the ground slam: the wave and the effects go when it hits (as the Snap Wand's).
        var store = caster.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !caster.isValid()) return;
            burst(player, caster, chain, store);
        }), FxSettings.snapHitDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** The wave from where the caster stands now: every mob and player in effectRadius gets the effect. World thread. */
    private static void burst(PlayerRef player, Ref<EntityStore> caster, List<EntityEffect> chain,
                              com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        Vector3d center = ChaosWand.positionOf(caster, buffer);
        if (center == null) return;
        int radius = FxSettings.effectRadius;
        String color = EffectWand.colorOf(FxSettings.effect);
        List<Ref<EntityStore>> hit = wave(center, caster, chain, buffer);
        player.sendMessage((hit.isEmpty()
                ? I18n.tr("chat.effect.areaNone").param("radius", radius)
                : I18n.tr("chat.effect.areaSent").param("effect", FxSettings.effect).param("count", hit.size())
                        .param("radius", radius)).color(hit.isEmpty() ? "#ffcc55" : color));
    }

    /**
     * The effect wave from {@code center} ({@code caster} may be null: a wave from a spot, /mobbattle test wave).
     * Returns who it reaches. World thread.
     */
    static List<Ref<EntityStore>> wave(Vector3d center, Ref<EntityStore> caster, List<EntityEffect> chain,
                                       com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        // An unknown effect id (/mobbattle test wave effect <bad id>) has no chain: nothing to give.
        if (chain == null || chain.isEmpty()) return List.of();
        int radius = FxSettings.effectRadius;
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, radius, buffer));
        // getAllEntitiesInSphere returns a thread-local list: read it right away.
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, radius, buffer)) {
            if ((FxSettings.effectAreaSelf || !r.equals(caster)) && PlayerFighters.inWave(r, buffer)) around.add(r);
        }
        String color = EffectWand.colorOf(FxSettings.effect);
        List<Ref<EntityStore>> hit = new ArrayList<>();
        for (Ref<EntityStore> r : WaveHit.filter(center, around, buffer)) {
            if (r.isValid() && buffer.getComponent(r, EffectControllerComponent.getComponentType()) != null) hit.add(r);
        }
        FightFx.shockwave(center, radius, color, buffer);
        WandFx.burst(null, "SFX_Skeleton_Mage_Spellbook_Impact", center, buffer);
        // Each one gets it as the ring reaches it; immune ones just don't take it.
        WaveHit.hit(center, radius, hit, buffer, (group, acc) -> {
            for (Ref<EntityStore> r : group) {
                EffectControllerComponent effects = acc.getComponent(r, EffectControllerComponent.getComponentType());
                if (effects != null) EffectGiveInteraction.give(r, chain, effects, acc);
            }
        });
        return hit;
    }
}
