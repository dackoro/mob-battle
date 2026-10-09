package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
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
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        Ref<EntityStore> target = context.getTargetEntity();
        if (buffer == null || target == null || !target.isValid()) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        EntityStatMap stats = buffer.getComponent(target, EntityStatMap.getComponentType());
        if (stats == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        NPCEntity npc = buffer.getComponent(target, NPCEntity.getComponentType());
        String name = npc != null ? npc.getRoleName() : "target";
        PlayerRef player = buffer.getComponent(context.getEntity(), PlayerRef.getComponentType());
        // NPCs get the soul rip first and die after /mobbattle killdelay; anything else dies right away.
        WandFx.kill(target, context.getEntity(), npc != null, buffer);
        if (player != null) {
            player.sendMessage(Message.raw("Killed " + name + ".").color("#ff5555"));
        }
    }

    /** Damage far above max health (cause "Command", else "Physical"). */
    static void killNow(Ref<EntityStore> target, Ref<EntityStore> killer, CommandBuffer<EntityStore> buffer) {
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
        Damage.Source source = killer != null && killer.isValid() ? new Damage.EntitySource(killer) : Damage.NULL_SOURCE;
        DamageSystems.executeDamage(target, buffer, new Damage(source, cause, amount));
    }
}
