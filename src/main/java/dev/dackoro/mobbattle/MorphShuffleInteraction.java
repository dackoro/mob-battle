package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

/**
 * Morph Wand held right click: the mobs (and, with morphPlayers, players) in morphBurstRadius swap looks in random
 * pairs (/mobbattle morphShuffle). Off: it acts as a normal right click (copy).
 */
public class MorphShuffleInteraction extends MorphBurstInteraction {

    public static final String TYPE = "MobBattle_MorphShuffle";

    public static final BuilderCodec<MorphShuffleInteraction> CODEC = BuilderCodec
            .builder(MorphShuffleInteraction.class, MorphShuffleInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Morph Wand held right click: the mobs around swap models in random pairs.")
            .build();

    @Override
    protected boolean shuffle() {
        return true;
    }
}
