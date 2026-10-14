package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

/** Heal Wand: restores the clicked entity to full health. */
public class HealInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Heal";

    public static final BuilderCodec<HealInteraction> CODEC = BuilderCodec
            .builder(HealInteraction.class, HealInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Heal Wand: restores the clicked entity to full health.")
            .build();

    /** The light reaching the mob, as CleanseInteraction.LIGHT_LANDS_MS (same MobBattle_Light_&lt;Color&gt; rays). */
    static final long LIGHT_LANDS_MS = 400;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> target = context.getTargetEntity();
        if (buffer == null || target == null || !target.isValid()) {
            return;
        }
        if (buffer.getComponent(target, EntityStatMap.getComponentType()) == null) {
            return;
        }
        NPCEntity npc = buffer.getComponent(target, NPCEntity.getComponentType());
        String name = npc != null ? npc.getRoleName() : "target";
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        // healLight: a light comes down from the sky (the Buff Wand's, in healLight) and the heal lands with it.
        // Off, or with wandFx off: at once.
        String light = FxSettings.wandFx ? FxSettings.healLightColor() : null;
        if (light == null) {
            healNow(target, buffer);
        } else {
            WandFx.onTarget(target, WandFx.BUFF_LIGHT + light + WandFx.lightHeight(target, buffer), buffer);
            var store = target.getStore();
            var world = store.getExternalData().getWorld();
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
                if (store.isShutdown() || !target.isValid()) return;
                healNow(target, store);
            }), LIGHT_LANDS_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        if (player != null) {
            player.sendMessage(I18n.tr("chat.heal.done").param("name", name).color("#55ff77"));
        }
    }

    /** Full health and the heal look (burst, totem fireflies and sound, green tint). World thread. */
    static void healNow(Ref<EntityStore> target, ComponentAccessor<EntityStore> accessor) {
        // Dead or dying by the time the light lands (a delayed wave group): no heal for a corpse.
        if (target == null || !BattleManager.isAlive(target, accessor)) return;
        EntityStatMap stats = accessor.getComponent(target, EntityStatMap.getComponentType());
        if (stats == null) return;
        stats.maximizeStatValue(DefaultEntityStatTypes.getHealth());
        WandFx.onTarget(target, WandFx.HEAL, accessor);
    }
}
