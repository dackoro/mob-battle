package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
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
 * Cleanse Wand held left click (the charged branch of a vanilla Charging interaction, as the staffs' charged cast): a
 * shockwave in the cleanse color, and every mob and player within cleanseRadius (the caster only with cleanseAreaSelf) is cleansed as by a
 * click (/mobbattle cleanseArea). Off: it acts as a normal left click.
 */
public class CleanseAreaInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_CleanseArea";

    public static final BuilderCodec<CleanseAreaInteraction> CODEC = BuilderCodec
            .builder(CleanseAreaInteraction.class, CleanseAreaInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Cleanse Wand held left click: cleanses every mob and player around.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!FxSettings.cleanseArea) {
            // Turned off: the held click does what a quick one does.
            new CleanseInteraction().firstRun(type, context, cooldownHandler);
            return;
        }
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> caster = context.getEntity();
        Vector3d center = ChaosWand.positionOf(caster, buffer);
        if (center == null) return;
        int radius = FxSettings.cleanseRadius;
        List<Ref<EntityStore>> done = wave(center, caster, buffer);
        player.sendMessage((done.isEmpty()
                ? I18n.tr("chat.cleanse.areaNone").param("radius", radius)
                : I18n.tr("chat.cleanse.areaDone").param("count", done.size()).param("radius", radius))
                .color(done.isEmpty() ? "#ffcc55" : "#7fd3ff"));
    }

    /**
     * The cleanse wave from {@code center} ({@code caster} may be null: a wave from a spot, /mobbattle test wave).
     * Returns who it reaches. World thread.
     */
    static List<Ref<EntityStore>> wave(Vector3d center, Ref<EntityStore> caster,
                                       com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        int radius = FxSettings.cleanseRadius;
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, radius, buffer));
        // getAllEntitiesInSphere returns a thread-local list: read it right away.
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, radius, buffer)) {
            if ((FxSettings.cleanseAreaSelf || !r.equals(caster)) && PlayerFighters.inWave(r, buffer)) around.add(r);
        }
        List<Ref<EntityStore>> done = new ArrayList<>();
        for (Ref<EntityStore> r : WaveHit.filter(center, around, buffer)) {
            if (r.isValid() && buffer.getComponent(r, EffectControllerComponent.getComponentType()) != null) done.add(r);
        }
        FightFx.shockwave(center, radius, Teams.PALETTE.get(FxSettings.cleanseColor), buffer);
        WandFx.burst(null, CleanseInteraction.SOUND, center, buffer);
        // Each one's light comes down as the ring reaches it, and lands as with a click.
        WaveHit.hit(center, radius, done, buffer, (group, acc) -> {
            List<String> fxs = new ArrayList<>();
            for (Ref<EntityStore> r : group) fxs.add(CleanseInteraction.start(r, false, acc));
            CleanseInteraction.landLater(group, fxs);
        });
        return done;
    }
}
