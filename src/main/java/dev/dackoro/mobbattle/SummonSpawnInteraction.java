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
import org.joml.Vector3d;

import javax.annotation.Nonnull;

/** Summon Wand right click: spawns the whole summon list around the player. */
public class SummonSpawnInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_SummonSpawn";

    public static final BuilderCodec<SummonSpawnInteraction> CODEC = BuilderCodec
            .builder(SummonSpawnInteraction.class, SummonSpawnInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Summon Wand: spawns the summon list around the player.")
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
        Vector3d center = ChaosWand.positionOf(context.getEntity(), buffer);
        if (center == null) {
            return;
        }
        Ref<EntityStore> self = context.getEntity();
        // Spawning needs the Store, not the interaction's CommandBuffer: run it on the world thread.
        var store = self.getStore();
        store.getExternalData().getWorld().execute(() -> SummonList.spawn(store, center, player, self));
    }
}
