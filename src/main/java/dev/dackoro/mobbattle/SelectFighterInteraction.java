package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.EntityUtils;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

/**
 * Left click with the Battle Wand.
 * First NPC clicked is remembered; clicking a second NPC makes both fight each other.
 * An NPC whose role can't become hostile (passive) won't attack, it only reacts.
 */
public class SelectFighterInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Select";

    private static final String COLOR_INFO = "#7fd3ff";
    private static final String COLOR_FIGHT = "#ff6b5e";
    private static final String COLOR_WARN = "#ffcc55";

    public static final BuilderCodec<SelectFighterInteraction> CODEC = BuilderCodec
            .builder(SelectFighterInteraction.class, SelectFighterInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Battle Wand: select two mobs to make them fight.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        if (buffer == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }

        Ref<EntityStore> target = context.getTargetEntity();
        if (target == null || !target.isValid()
                || buffer.getComponent(target, NPCEntity.getComponentType()) == null
                || !BattleManager.isAlive(target, buffer)) {
            player.sendMessage(Message.raw("Look at a living mob and click it.").color(COLOR_WARN));
            context.getState().state = InteractionState.Failed;
            return;
        }

        String targetName = nameOf(target, buffer);
        Ref<EntityStore> first = FightManager.getSelection(player.getUuid());
        if (first != null && (first.getStore() != target.getStore() || !BattleManager.isAlive(first, buffer))) {
            FightManager.clear(player.getUuid());
            first = null;
        }

        if (first == null) {
            FightManager.select(player.getUuid(), target);
            WandFx.onTarget(target, WandFx.CHARM, buffer);
            player.sendMessage(Message.raw("Selected: " + targetName + ". Now click its opponent.").color(COLOR_INFO));
            return;
        }
        if (first.getIndex() == target.getIndex()) {
            player.sendMessage(Message.raw(targetName + " is already selected. Click another mob.").color(COLOR_WARN));
            return;
        }

        FightManager.clear(player.getUuid());
        Ref<EntityStore> playerEntity = context.getEntity();
        Ref<EntityStore> picked = first;
        WandFx.onTarget(target, WandFx.CHARM, buffer);
        FightCamera.duel(player, picked, target, buffer);
        Countdown.run(player, buffer, "DUEL: " + nameOf(picked, buffer) + " vs " + targetName, java.util.List.of(picked, target),
                acc -> duel(player, playerEntity, picked, target, acc));
    }

    /** The duel itself, right away or after /mobbattle countdown. */
    static void duel(PlayerRef player, Ref<EntityStore> playerEntity, Ref<EntityStore> first, Ref<EntityStore> target,
                     ComponentAccessor<EntityStore> buffer) {
        if (!BattleManager.isAlive(first, buffer) || !BattleManager.isAlive(target, buffer)) {
            player.sendMessage(Message.raw("Duel cancelled: a fighter is gone.").color(COLOR_WARN));
            return;
        }
        String firstName = ChaosWand.nameOf(first, buffer);
        String targetName = ChaosWand.nameOf(target, buffer);
        boolean accumulate = FxSettings.accumulateDuels;
        if (!accumulate) BattleManager.detach(java.util.List.of(first, target));
        FightManager.Result a = FightManager.turnAgainst(first, target, buffer);
        FightManager.Result b = FightManager.turnAgainst(target, first, buffer);
        if (a != FightManager.Result.AGGRESSIVE && b != FightManager.Result.AGGRESSIVE) {
            player.sendMessage(Message.raw("Neither " + firstName + " nor " + targetName
                    + " can attack. No fight.").color(COLOR_WARN));
            return;
        }
        // Also a 2-mob "royale", so the winner gets a title when the other one dies.
        BattleManager.Battle duel = BattleManager.startDuel(player, first, target, accumulate);
        BattleManager.setTarget(duel, first, target);
        BattleManager.setTarget(duel, target, first);
        // Universal trigger: each one "feels" a hit from the other, so any role that can fight
        // (including NPCs from other mods) reacts with its own logic; passive ones flee.
        FightManager.provoke(target, first, buffer);
        FightManager.provoke(first, target, buffer);
        MobBattlePlugin.log("Fight " + firstName + " (" + a + ", state " + FightManager.stateOf(first, buffer) + ") vs "
                + targetName + " (" + b + ", state " + FightManager.stateOf(target, buffer) + ")");

        player.sendMessage(Message.raw("FIGHT! " + firstName + " vs " + targetName).color(COLOR_FIGHT));
        FightFx.showFightStart(player, playerEntity, firstName, targetName, first, target, buffer);
        if (a == FightManager.Result.PASSIVE) {
            player.sendMessage(Message.raw(firstName + " is passive: it will only run.").color(COLOR_WARN));
        }
        if (b == FightManager.Result.PASSIVE) {
            player.sendMessage(Message.raw(targetName + " is passive: it will only run.").color(COLOR_WARN));
        }
    }

    private static String nameOf(Ref<EntityStore> ref, CommandBuffer<EntityStore> buffer) {
        NPCEntity npc = buffer.getComponent(ref, NPCEntity.getComponentType());
        if (npc != null && npc.getRoleName() != null) {
            return npc.getRoleName();
        }
        String name = EntityUtils.getEntityName(ref, buffer);
        return name != null ? name : "mob";
    }
}
