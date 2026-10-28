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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Team Wand right click.
 * <ul>
 *   <li>In the air: switch to the next team (RED, BLUE, GREEN, YELLOW).</li>
 *   <li>On a mob: start the battle with the teams built so far.</li>
 * </ul>
 */
public class TeamActionInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_TeamAction";

    public static final BuilderCodec<TeamActionInteraction> CODEC = BuilderCodec
            .builder(TeamActionInteraction.class, TeamActionInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Team Wand: right click in the air switches team, right click on a mob starts the battle.")
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
        BattleManager.Draft draft = BattleManager.draft(player);
        Ref<EntityStore> target = context.getTargetEntity();
        boolean onNpc = target != null && target.isValid()
                && buffer.getComponent(target, NPCEntity.getComponentType()) != null;

        if (!onNpc) {
            // crouch + right click in the air: clear every team, after two warnings (user, 2026-10-17: three clicks,
            // a yellow then a red warning in chat before the last one)
            if (PlayerFighters.crouching(context.getEntity(), buffer)) {
                askClear(player, draft);
                return;
            }
            CLEAR_ASK.remove(player.getUuid());
            int team = BattleManager.nextTeam(draft);
            player.sendMessage(I18n.tr("chat.team.adding").param("team", Teams.name(team))
                    .param("count", draft.teams().get(team).size())
                    .color(Teams.color(team)));
            return;
        }
        CLEAR_ASK.remove(player.getUuid());
        start(player, context.getEntity(), draft, buffer);
    }

    /** Per player: {crouch clicks so far, time of the last one} toward clearing the teams. */
    private static final java.util.Map<java.util.UUID, long[]> CLEAR_ASK = new java.util.concurrent.ConcurrentHashMap<>();

    /** Longest gap between two of the three clicks; a slower click starts over. */
    private static final long CLEAR_WINDOW_MS = 4000;

    private static void askClear(PlayerRef player, BattleManager.Draft draft) {
        int mobs = 0;
        for (List<Ref<EntityStore>> team : draft.teams()) for (Ref<EntityStore> r : team) if (r.isValid()) mobs++;
        if (mobs == 0) {
            CLEAR_ASK.remove(player.getUuid());
            player.sendMessage(I18n.tr("chat.team.clear.none").color("#aaaaaa"));
            return;
        }
        long now = System.currentTimeMillis();
        long[] ask = CLEAR_ASK.get(player.getUuid());
        int step = ask != null && now - ask[1] <= CLEAR_WINDOW_MS ? (int) ask[0] + 1 : 1;
        if (step >= 3) {
            CLEAR_ASK.remove(player.getUuid());
            player.sendMessage(I18n.tr("chat.team.clear.done").param("count", BattleManager.clearDraftAndFx(player)).color("#5fe35f"));
            return;
        }
        CLEAR_ASK.put(player.getUuid(), new long[] {step, now});
        player.sendMessage(I18n.tr(step == 1 ? "chat.team.clear.ask1" : "chat.team.clear.ask2").param("count", mobs)
                .color(step == 1 ? "#ffd23e" : "#ff5555"));
    }

    static void start(PlayerRef player, Ref<EntityStore> playerEntity, BattleManager.Draft draft,
                              com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        List<List<Ref<EntityStore>>> teams = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        if (!teamsOf(draft, buffer, teams, colors)) {
            player.sendMessage(I18n.tr("chat.team.need2").color("#ffcc55"));
            return;
        }
        Countdown.Label label = new Countdown.Label("TEAM BATTLE", I18n.tr("battle.label.teams"));
        // readyTime first (ReadyWait): time to get in position; the teams are read again when it ends (a mob may have
        // died or joined meanwhile)
        ReadyWait.then(BattleManager.Kind.TEAMS, player, teams.stream().flatMap(List::stream).toList(), label, buffer, acc -> {
            List<List<Ref<EntityStore>>> now = new ArrayList<>();
            List<Integer> nowColors = new ArrayList<>();
            if (!teamsOf(draft, acc, now, nowColors)) {
                player.sendMessage(I18n.tr("chat.team.need2").color("#ffcc55"));
                return;
            }
            FightCamera.intro(BattleManager.Kind.TEAMS, player, null, now, acc);
            Countdown.run(BattleManager.Kind.TEAMS, player, acc, label, now.stream().flatMap(List::stream).toList(),
                    acc2 -> startNow(player, playerEntity, now, nowColors, acc2));
        });
    }

    /** The draft's living teams and their slots into {@code teams}/{@code colors}; false with fewer than two. */
    private static boolean teamsOf(BattleManager.Draft draft, com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer,
                                   List<List<Ref<EntityStore>>> teams, List<Integer> colors) {
        draft.prune();
        for (int t = 0; t < draft.teams().size(); t++) {
            List<Ref<EntityStore>> members = new ArrayList<>(draft.teams().get(t));
            members.removeIf(r -> !BattleManager.isAlive(r, buffer));
            if (!members.isEmpty()) {
                teams.add(members);
                colors.add(t);
            }
        }
        return teams.size() >= 2;
    }

    private static void startNow(PlayerRef player, Ref<EntityStore> playerEntity, List<List<Ref<EntityStore>>> teams,
                                 List<Integer> colors, com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        // Mobs that died during the countdown leave; a team left empty doesn't take part.
        for (int k = teams.size() - 1; k >= 0; k--) {
            teams.get(k).removeIf(r -> !BattleManager.isAlive(r, buffer) || !PlayerFighters.canStart(r, buffer));
            if (teams.get(k).isEmpty()) {
                teams.remove(k);
                colors.remove(k);
            }
        }
        if (teams.size() < 2) {
            player.sendMessage(I18n.tr("chat.team.cancelled").color("#ffcc55"));
            return;
        }

        // The battle stores team indexes 0..n-1; keep the draft's colors for them.
        List<List<Ref<EntityStore>>> byColor = new ArrayList<>();
        for (int t = 0; t < BattleManager.TEAM_NAMES.length; t++) {
            int k = colors.indexOf(t);
            byColor.add(k >= 0 ? teams.get(k) : new ArrayList<>());
        }
        BattleManager.Battle battle = BattleManager.startTeams(player, byColor);
        // Each team's equipment kit (teams kit), taken off again when the battle ends.
        Kits.teamBattleStart(battle, buffer);
        Arena.setupAround(battle, buffer);
        FightManager.splitFlocksAcrossSides(battle, buffer);
        BattleManager.clearDraft(player.getUuid());

        int aggressive = 0;
        for (Ref<EntityStore> npc : battle.members()) {
            Ref<EntityStore> enemy = BattleManager.nearestEnemy(battle, npc, buffer);
            if (enemy == null) {
                continue;
            }
            BattleManager.setTarget(battle, npc, enemy);
            if (FightManager.turnAgainst(npc, enemy, buffer) == FightManager.Result.AGGRESSIVE) {
                aggressive++;
            }
            FightManager.provoke(npc, enemy, buffer);
            FightFx.link(BattleManager.Kind.TEAMS, playerEntity, npc, enemy, battle.color(battle.team(npc)), buffer);
        }

        String versus = colors.stream()
                .map(t -> battle.name(t) + " (" + byColor.get(t).size() + ")")
                .collect(Collectors.joining(" vs "));
        MobBattlePlugin.log("Team battle: " + versus + ", " + aggressive + " aggressive");
        player.sendMessage(I18n.tr("chat.team.start").param("versus", versus).color("#ff6b5e"));
        FightFx.title(BattleManager.Kind.TEAMS, player, I18n.tr("battle.title.teams"), Message.raw(versus));
    }
}
