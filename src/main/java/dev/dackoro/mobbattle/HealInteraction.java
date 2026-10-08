package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
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

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> target = context.getTargetEntity();
        if (buffer == null || target == null || !target.isValid()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        EntityStatMap stats = buffer.getComponent(target, EntityStatMap.getComponentType());
        if (stats == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        int health = DefaultEntityStatTypes.getHealth();
        NPCEntity npc = buffer.getComponent(target, NPCEntity.getComponentType());
        String name = npc != null ? npc.getRoleName() : "target";
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        stats.maximizeStatValue(health);
        if (player != null) {
            player.sendMessage(Message.raw("Healed " + name + " to full health.").color("#55ff77"));
        }
    }
}
