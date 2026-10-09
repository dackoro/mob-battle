package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
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
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/**
 * Team Wand left click: add the mob to the current team, or remove it if it's already in it.
 * With /mobbattle teamByType on, every mob of the same type within areaRadius follows the clicked one.
 */
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
        // teamByType: the same-type mobs around the clicked one follow it (NPCs only, never players).
        String role = FxSettings.teamByType ? roleOf(target, buffer) : null;
        int more = role != null ? followSameType(draft, target, role, team, buffer) : 0;
        if (team < 0) {
            TeamFx.clear(target);
            String tail = more > 0 ? " (and " + more + " more " + role + ")" : "";
            player.sendMessage(Message.raw(name + " removed from the "
                    + Teams.name(draft.currentTeam()) + " team" + tail + ".").color("#aaaaaa"));
            return;
        }
        TeamFx.show(target, team);
        // The swirl in the team color (no tint: the team look stays).
        WandFx.swirl(target, Teams.color(team), buffer);
        String extra = more > 0 ? ", +" + more + " more " + role : "";
        player.sendMessage(Message.raw(name + " joins the " + Teams.name(team) + " team ("
                + draft.teams().get(team).size() + " mobs" + extra + ").").color(Teams.color(team)));
        FightFx.teamRing(player, target, Teams.color(team), buffer);
    }

    /** The NPC role name of {@code ref}, or null if it is not an NPC (or has no role name). */
    private static String roleOf(Ref<EntityStore> ref, ComponentAccessor<EntityStore> buffer) {
        NPCEntity npc = buffer.getComponent(ref, NPCEntity.getComponentType());
        return npc != null ? npc.getRoleName() : null;
    }

    /**
     * teamByType: the living mobs of the same role within the chaos area of {@code target} join {@code team}
     * (team &gt;= 0), or leave the current team (team &lt; 0). Returns how many changed.
     */
    private static int followSameType(BattleManager.Draft draft, Ref<EntityStore> target, String role, int team,
                                      ComponentAccessor<EntityStore> buffer) {
        Vector3d center = ChaosWand.positionOf(target, buffer);
        if (center == null) {
            return 0;
        }
        // npcsAround may return a reused list: copy it before changing teams.
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, buffer));
        int n = 0;
        for (Ref<EntityStore> r : around) {
            if (r == target || !BattleManager.isAlive(r, buffer) || !role.equals(roleOf(r, buffer))) {
                continue;
            }
            if (team >= 0) {
                if (draft.teamOf(r) != team) {
                    BattleManager.assign(draft, r, team);
                    n++;
                }
            } else if (draft.teamOf(r) == draft.currentTeam()) {
                BattleManager.toggle(draft, r);
                TeamFx.clear(r);
                n++;
            }
        }
        return n;
    }
}
