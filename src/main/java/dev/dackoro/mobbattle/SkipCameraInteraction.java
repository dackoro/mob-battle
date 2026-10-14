package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * Q (Ability1) with any Mob Battle wand in hand: the running intro or end camera ends now, as crouching or
 * /mobbattle skipCamera. ESC can't do it: the pause menu is client-only, the server never hears of it. Does nothing
 * when no camera runs.
 */
public class SkipCameraInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_SkipCamera";

    public static final BuilderCodec<SkipCameraInteraction> CODEC = BuilderCodec
            .builder(SkipCameraInteraction.class, SkipCameraInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Q with a Mob Battle wand: ends the running fight camera.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) return;
        boolean cut = FightCamera.cancel(player);
        // Q not cutting the camera (user, 2026-10-13): tells whether the client sent Ability1 at all.
        if (MobBattlePlugin.debugging()) MobBattlePlugin.debug("Q (Ability1) from " + player.getUsername() + (cut ? ": camera cut" : ": no camera running"));
    }
}
