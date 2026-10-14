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

/**
 * Army Wand left click: the clicked mob joins your army, or leaves it if it was already in it. Crouching picks whom you
 * recruit for: a mob (it leads the army), another player, or the air for your own army again.
 */
public class ArmyToggleInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_ArmyToggle";

    public static final BuilderCodec<ArmyToggleInteraction> CODEC = BuilderCodec
            .builder(ArmyToggleInteraction.class, ArmyToggleInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Army Wand: adds the clicked mob to your army, or removes it.")
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
        Ref<EntityStore> me = context.getEntity();
        Ref<EntityStore> target = context.getTargetEntity();
        boolean crouch = PlayerFighters.crouching(me, buffer);
        if (MobBattlePlugin.debugging()) {
            // A crouch click while flying in Creative may not count as crouching (test 2026-10-14): say what the game saw.
            var states = buffer.getComponent(me, com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent.getComponentType());
            var ms = states != null ? states.getMovementStates() : null;
            MobBattlePlugin.debug("army wand: " + player.getUsername() + " clicked " + (target == null ? "the air" : ChaosWand.nameOf(target, buffer))
                    + (ms == null ? "" : ", crouching " + ms.crouching + ", flying " + ms.flying + ", on ground " + ms.onGround));
        }
        click(player, me, target, crouch, buffer);
    }

    /** One Army Wand left click (the wand and /mobbattle test wand army|armypick go through here). */
    static void click(PlayerRef player, Ref<EntityStore> me, Ref<EntityStore> target, boolean crouch,
                      com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        boolean mob = target != null && target.isValid() && buffer.getComponent(target, NPCEntity.getComponentType()) != null
                && BattleManager.isAlive(target, buffer);
        if (crouch) {
            pick(player, me, target, mob, buffer);
            return;
        }
        if (!mob) {
            player.sendMessage(I18n.tr("chat.army.look").color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        Army.Pick pick = Army.pickOf(player.getUuid());
        if (pick == null) {
            boolean added = Army.toggle(player, me, target, buffer);
            WandFx.onTarget(target, added ? WandFx.ARMY_ADD : WandFx.ARMY_REMOVE, buffer);
            int size = Army.members(player.getUuid()).size();
            player.sendMessage((added
                    ? I18n.tr("chat.army.joined").param("name", name).param("size", size)
                    : I18n.tr("chat.army.left").param("name", name).param("size", size)).color(added ? "#4bff6a" : "#aaaaaa"));
            return;
        }
        // Recruiting for another player or a leader mob (crouch click).
        if (target == pick.leader()) {
            player.sendMessage(I18n.tr("chat.army.self").param("name", name).color("#ffcc55"));
            return;
        }
        if (Army.isMember(pick.owner(), target)) {
            Army.remove(target, buffer);
            WandFx.onTarget(target, WandFx.ARMY_REMOVE, buffer);
            player.sendMessage(I18n.tr("chat.army.left.other").param("name", name).param("owner", pick.name())
                    .param("size", Army.members(pick.owner()).size()).color("#aaaaaa"));
            return;
        }
        Ref<EntityStore> ownerEntity = pick.leader() != null ? pick.leader() : Army.entityOf(pick.owner());
        if (ownerEntity == null || ownerEntity.getStore() != target.getStore()) {
            player.sendMessage(I18n.tr("chat.army.ownergone").param("owner", pick.name()).color("#ffcc55"));
            return;
        }
        if (!Army.add(pick.owner(), ownerEntity, pick.leader() != null, target, buffer)) {
            player.sendMessage(I18n.tr("chat.army.loop").param("name", name).param("owner", pick.name()).color("#ffcc55"));
            return;
        }
        WandFx.onTarget(target, WandFx.ARMY_ADD, buffer);
        player.sendMessage(I18n.tr("chat.army.joined.other").param("name", name).param("owner", pick.name())
                .param("size", Army.members(pick.owner()).size()).color("#4bff6a"));
    }

    /**
     * Crouch + left click (2026-10-14, user): a mob = recruit for it (it leads its army), another player = recruit for
     * them, nothing (the air) or yourself = for your own army again.
     */
    private static void pick(PlayerRef player, Ref<EntityStore> me, Ref<EntityStore> target, boolean mob,
                             com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        if (target == null || !target.isValid() || target == me) {
            Army.setPick(player.getUuid(), null);
            player.sendMessage(I18n.tr("chat.army.own").color("#7fd3ff"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        PlayerRef other = buffer.getComponent(target, PlayerRef.getComponentType());
        if (other != null) {
            // Another player's army (adding, removing, disbanding) is a config-level action on a server with
            // permissions on (audit 2026-10-14: any Adventurer could empty someone else's army).
            if (!Perms.allowed(player, Perms.CONFIG)) {
                player.sendMessage(I18n.tr("chat.army.player.denied").param("perm", Perms.CONFIG).color("#ffcc55"));
                return;
            }
            Army.setPick(player.getUuid(), new Army.Pick(other.getUuid(), null, other.getUsername()));
            player.sendMessage(I18n.tr("chat.army.player.picked").param("name", other.getUsername()).color("#7fd3ff"));
            return;
        }
        var uuid = buffer.getComponent(target, com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
        if (!mob || uuid == null) {
            player.sendMessage(I18n.tr("chat.army.look").color("#ffcc55"));
            return;
        }
        Army.setPick(player.getUuid(), new Army.Pick(uuid.getUuid(), target, name));
        WandFx.onTarget(target, WandFx.CHARM, buffer);
        player.sendMessage(I18n.tr("chat.army.leader.picked").param("name", name).color("#ffd23e"));
    }
}
