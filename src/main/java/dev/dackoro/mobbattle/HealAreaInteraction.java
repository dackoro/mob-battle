package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
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
import com.hypixel.hytale.server.core.util.TargetUtil;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Heal Wand held left click (the charged branch of a vanilla Charging interaction): a ground slam (the Snap Wand's),
 * and when it hits a shockwave in the heal light color; every mob and player within healRadius, the caster too (healAreaSelf), is
 * healed to full as by a click (its light comes down first) (/mobbattle healArea). Off: it acts as a normal left click.
 */
public class HealAreaInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_HealArea";

    public static final BuilderCodec<HealAreaInteraction> CODEC = BuilderCodec
            .builder(HealAreaInteraction.class, HealAreaInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Heal Wand held left click: heals every mob and player around to full.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!FxSettings.healArea) {
            // Turned off: the held click does what a quick one does.
            new HealInteraction().firstRun(type, context, cooldownHandler);
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
        // The wave and the heal go when the slam hits (as the Snap Wand's).
        var store = caster.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !caster.isValid()) return;
            burst(player, caster, store);
        }), FxSettings.snapHitDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** World thread: the wave, each target's light, then one task heals them all when the lights land. */
    private static void burst(PlayerRef player, Ref<EntityStore> caster, ComponentAccessor<EntityStore> buffer) {
        Vector3d center = ChaosWand.positionOf(caster, buffer);
        if (center == null) return;
        int radius = FxSettings.healRadius;
        List<Ref<EntityStore>> around = wave(center, caster, buffer);
        player.sendMessage((around.isEmpty()
                ? I18n.tr("chat.heal.areaNone").param("radius", radius)
                : I18n.tr("chat.heal.areaDone").param("count", around.size()).param("radius", radius))
                .color(around.isEmpty() ? "#ffcc55" : "#55ff77"));
    }

    /**
     * The heal wave from {@code center} ({@code caster} may be null: a wave from a spot, /mobbattle test wave).
     * Returns who it reaches. World thread.
     */
    static List<Ref<EntityStore>> wave(Vector3d center, Ref<EntityStore> caster, ComponentAccessor<EntityStore> buffer) {
        int radius = FxSettings.healRadius;
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, radius, buffer));
        // getAllEntitiesInSphere returns a thread-local list: read it right away.
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, radius, buffer)) {
            if (PlayerFighters.inWave(r, buffer) && !around.contains(r) && (FxSettings.healAreaSelf || !r.equals(caster))) around.add(r);
        }
        around.removeIf(r -> !r.isValid() || buffer.getComponent(r, EntityStatMap.getComponentType()) == null);
        around = WaveHit.filter(center, around, buffer);
        String light = FxSettings.wandFx ? FxSettings.healLightColor() : null;
        String hex = Teams.PALETTE.getOrDefault(light != null ? light : "Green", Teams.PALETTE.get("Green"));
        FightFx.shockwave(center, radius, hex, buffer);
        WandFx.burst(null, "SFX_Deployable_Totem_Heal_Despawn", center, buffer);
        // Each one's light comes down as the ring reaches it; the heal lands with the light.
        WaveHit.hit(center, radius, around, buffer, (group, acc) -> {
            if (light == null) {
                for (Ref<EntityStore> r : group) HealInteraction.healNow(r, acc);
                return;
            }
            for (Ref<EntityStore> r : group) WandFx.onTarget(r, WandFx.BUFF_LIGHT + light + WandFx.lightHeight(r, acc), acc);
            var store = group.get(0).getStore();
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
                if (store.isShutdown()) return;
                for (Ref<EntityStore> r : group) if (r.isValid()) HealInteraction.healNow(r, store);
            }), HealInteraction.LIGHT_LANDS_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        });
        return around;
    }
}
