package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

/** Team Wand left click: add the mob to the current team, or remove it if it's already in it. */
public class TeamSelectInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_TeamSelect";

    public static final BuilderCodec<TeamSelectInteraction> CODEC = BuilderCodec
            .builder(TeamSelectInteraction.class, TeamSelectInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Team Wand: adds the clicked mob to the current team (click again to remove it).")
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
        boolean isPlayer = PlayerFighters.isPlayer(target, buffer);
        if (isPlayer) {
            // A player joins the team like a mob (/mobbattle playerFights teams).
            if (!PlayerFighters.eligible(player, target, BattleManager.Kind.TEAMS, buffer)) {
                context.getState().state = InteractionState.Failed;
                return;
            }
            BattleManager.markPlayer(target, player.getUuid());
        } else if (target == null || !target.isValid() || buffer.getComponent(target, NPCEntity.getComponentType()) == null
                || !BattleManager.isAlive(target, buffer)) {
            player.sendMessage(Message.raw("Look at a living mob and click it to add it to a team.").color("#ffcc55"));
            context.getState().state = InteractionState.Failed;
            return;
        }
        BattleManager.Draft draft = BattleManager.draft(player);
        String name = ChaosWand.nameOf(target, buffer);
        int team = BattleManager.toggle(draft, target);
        if (team < 0) {
            TeamFx.clear(target);
            player.sendMessage(Message.raw(name + " removed from the "
                    + Teams.name(draft.currentTeam()) + " team.").color("#aaaaaa"));
            return;
        }
        TeamFx.show(target, team);
        player.sendMessage(Message.raw(name + " joins the " + Teams.name(team) + " team ("
                + draft.teams().get(team).size() + " mobs).").color(Teams.color(team)));
        FightFx.teamRing(player, target, Teams.color(team), buffer);
    }
}
