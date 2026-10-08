package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/** Chaos Wand right click: free-for-all among the mobs in the radius (around the clicked mob, or the player). */
public class ChaosBrawlInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ChaosBrawl";

    public static final BuilderCodec<ChaosBrawlInteraction> CODEC = BuilderCodec
            .builder(ChaosBrawlInteraction.class, ChaosBrawlInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Chaos Wand: free-for-all among the mobs in the radius.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        boolean onNpc = target != null && target.isValid()
                && buffer.getComponent(target, NPCEntity.getComponentType()) != null;
        Vector3d center = ChaosWand.positionOf(onNpc ? target : context.getEntity(), buffer);
        if (center != null) {
            ChaosWand.brawl(player, context.getEntity(), center, buffer);
        }
    }
}
