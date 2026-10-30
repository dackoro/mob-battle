package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kill Wand held left click (the charged branch of a vanilla Charging interaction, 2026-10-18, playtest: like the other
 * wands' held clicks): the Snap Wand's ground slam, and when it hits a shockwave in the soul rip's color; every mob
 * within killRadius dies as by a click (soul rip, killDelay, killLoot), each when the ring reaches it (waveTravel).
 * Never the caster nor the mobs of their own army (its whole chain of command); players only with killAreaPlayers
 * (and killPlayers) on, never one who can't die (/mobbattle killArea). Off: it acts as a normal left click.
 */
public class KillAreaInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_KillArea";

    public static final BuilderCodec<KillAreaInteraction> CODEC = BuilderCodec
            .builder(KillAreaInteraction.class, KillAreaInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Kill Wand held left click: every mob around dies, your own army spared.")
            .build();

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!FxSettings.killArea) {
            // Turned off: the held click does what a quick one does.
            new KillInteraction().firstRun(type, context, cooldownHandler);
            return;
        }
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> caster = context.getEntity();
        // The wave goes when the slam hits (as the Snap Wand's).
        var store = caster.getStore();
        var world = store.getExternalData().getWorld();
        world.scheduleAfter(() -> {
            if (store.isShutdown() || !caster.isValid()) return;
            Vector3d center = ChaosWand.positionOf(caster, store);
            if (center == null) return;
            int radius = FxSettings.killRadius;
            List<Ref<EntityStore>> hit = wave(center, caster, player.getUuid(), store);
            player.sendMessage((hit.isEmpty()
                    ? I18n.tr("chat.kill.areaNone").param("radius", radius)
                    : I18n.tr("chat.kill.areaDone").param("count", hit.size()).param("radius", radius))
                    .color(hit.isEmpty() ? "#ffcc55" : "#ff5555"));
        }, FxSettings.snapHitDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * The kill wave from {@code center}: {@code caster} (may be null: a wave from a spot, /mobbattle test wave) is never
     * in it, nor the army of {@code owner} (the caster's UUID, or null). Returns who it reaches. World thread.
     */
    static List<Ref<EntityStore>> wave(Vector3d center, Ref<EntityStore> caster, UUID owner, ComponentAccessor<EntityStore> buffer) {
        int radius = FxSettings.killRadius;
        List<Ref<EntityStore>> around = new ArrayList<>(ChaosWand.npcsAround(center, radius, buffer));
        if (FxSettings.killAreaPlayers && FxSettings.killPlayers) {
            // getAllEntitiesInSphere returns a thread-local list: read it right away.
            for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, radius, buffer)) {
                if (!r.equals(caster) && !around.contains(r) && PlayerFighters.inWave(r, buffer)
                        && !PlayerFighters.cantDie(r, buffer)) around.add(r);
            }
        }
        around.removeIf(r -> !r.isValid() || r.equals(caster) || !BattleManager.isAlive(r, buffer)
                || buffer.getComponent(r, EntityStatMap.getComponentType()) == null || Army.servesPlayer(r, owner));
        around = WaveHit.filter(center, around, buffer);
        FightFx.shockwave(center, radius, LookLight.SOUL_RIP_HEX, buffer);
        WandFx.burst(null, WandFx.HAKAI_SOUND, center, buffer);
        // Each one dies as the ring reaches it; one Hakai sound for the whole wave (above), not one per mob.
        WaveHit.hit(center, radius, around, buffer, (group, acc) -> {
            Ref<EntityStore> killer = caster != null && caster.isValid() ? caster : null;
            for (Ref<EntityStore> r : group) {
                if (!r.isValid() || !BattleManager.isAlive(r, acc)) continue;
                WandFx.kill(r, killer, acc.getComponent(r, NPCEntity.getComponentType()) != null, acc, false);
            }
        });
        return around;
    }
}
