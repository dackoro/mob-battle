package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/**
 * The /mobbattle watch controls, bound by our game mode Server/Entity/GameMode/MobBattle_Spectator.json:
 * previous fighter (E, left click), next fighter (R, right click), exit (Q). One type per action.
 */
abstract class WatchInteraction extends SimpleInstantInteraction {

    /** -1 previous, +1 next, 0 exit. */
    abstract int direction();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        if (direction() == 0) {
            BattleWatch.exit(player, context.getEntity(), buffer);
        } else {
            BattleWatch.cycle(player, context.getEntity(), buffer, direction());
        }
    }

    public static final class Prev extends WatchInteraction {
        public static final String TYPE = "MobBattle_WatchPrev";
        public static final BuilderCodec<Prev> CODEC = BuilderCodec
                .builder(Prev.class, Prev::new, SimpleInstantInteraction.CODEC)
                .documentation("/mobbattle watch: the camera goes to the previous fighter.")
                .build();

        @Override
        int direction() {
            return -1;
        }
    }

    public static final class Next extends WatchInteraction {
        public static final String TYPE = "MobBattle_WatchNext";
        public static final BuilderCodec<Next> CODEC = BuilderCodec
                .builder(Next.class, Next::new, SimpleInstantInteraction.CODEC)
                .documentation("/mobbattle watch: the camera goes to the next fighter.")
                .build();

        @Override
        int direction() {
            return 1;
        }
    }

    public static final class Exit extends WatchInteraction {
        public static final String TYPE = "MobBattle_WatchExit";
        public static final BuilderCodec<Exit> CODEC = BuilderCodec
                .builder(Exit.class, Exit::new, SimpleInstantInteraction.CODEC)
                .documentation("/mobbattle watch: stop watching, back to your game mode and position.")
                .build();

        @Override
        int direction() {
            return 0;
        }
    }
}
