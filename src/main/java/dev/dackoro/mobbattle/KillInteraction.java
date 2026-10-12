package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageSystems;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;

/** Kill Wand: instantly kills the clicked entity. */
public class KillInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_Kill";

    public static final BuilderCodec<KillInteraction> CODEC = BuilderCodec
            .builder(KillInteraction.class, KillInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Kill Wand: instantly kills the clicked entity.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> target = context.getTargetEntity();
        if (buffer == null || target == null || !target.isValid()) {
            return;
        }
        EntityStatMap stats = buffer.getComponent(target, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        NPCEntity npc = buffer.getComponent(target, NPCEntity.getComponentType());
        String name = npc != null ? npc.getRoleName() : "target";
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        // Players only with /mobbattle killPlayers on (default off): the wand's error look and a message.
        if (!FxSettings.killPlayers && buffer.getComponent(target, PlayerRef.getComponentType()) != null) {
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            if (player != null) {
                player.sendMessage(Message.raw("The Kill Wand doesn't work on players. Turn it on with /mobbattle killPlayers on")
                        .color("#ffcc55"));
            }
            return;
        }
        // NPCs get the soul rip first and die after /mobbattle killdelay; anything else dies right away.
        Ref<EntityStore> self = context.getEntity();
        boolean isNpc = npc != null;
        int hit = FxSettings.wandFx ? FxSettings.killHitDelayMs : 0;
        if (hit <= 0) {
            hitNow(target, self, isNpc, player, name, buffer);
            return;
        }
        // The cast lands its hit /mobbattle killHitDelay ms in (default 175: the hit at 0.175 s of KillCast): the Hakai
        // sound and the disintegration wait for it, like the Snap Wand's slam (snapHitDelay).
        var store = self.getStore();
        var world = store.getExternalData().getWorld();
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
            if (store.isShutdown() || !target.isValid() || !BattleManager.isAlive(target, store)) return;
            hitNow(target, self.isValid() ? self : null, isNpc, player, name, store);
        }), hit, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void hitNow(Ref<EntityStore> target, Ref<EntityStore> killer, boolean npc, PlayerRef player,
                               String name, com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        WandFx.kill(target, killer, npc, buffer);
        if (player != null && player.isValid()) {
            player.sendMessage(Message.raw("Killed " + name + ".").color("#ff5555"));
        }
    }

    /**
     * Damage far above max health (cause "Command", else "Physical"). Every caller is a wand-style death (Kill Wand,
     * Snap, timeUp kill/judge, a disintegrating summon), so an NPC drops nothing with killLoot off: its role counts the
     * death items as dropped (vanilla DropDeathItems checks it).
     */
    static void killNow(Ref<EntityStore> target, Ref<EntityStore> killer,
                        com.hypixel.hytale.component.ComponentAccessor<EntityStore> buffer) {
        if (target == null || !target.isValid() || !BattleManager.isAlive(target, buffer)) {
            return;
        }
        EntityStatMap stats = buffer.getComponent(target, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        EntityStatValue value = stats.get(DefaultEntityStatTypes.getHealth());
        float amount = (value != null ? Math.max(value.get(), value.getMax()) : 0f) * 10f + 1000f;
        int cause = DamageCause.getAssetMap().getIndex("Command");
        if (cause < 0) {
            cause = DamageCause.getAssetMap().getIndex("Physical");
        }
        if (!FxSettings.killLoot) {
            NPCEntity npc = buffer.getComponent(target, NPCEntity.getComponentType());
            if (npc != null && npc.getRole() != null) npc.getRole().setDeathItemsDropped();
        }
        Damage.Source source = killer != null && killer.isValid() ? new Damage.EntitySource(killer) : Damage.NULL_SOURCE;
        DamageSystems.executeDamage(target, buffer, new Damage(source, cause, amount));
    }
}
