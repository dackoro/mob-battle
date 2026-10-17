package dev.dackoro.mobbattle;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Buff Wand left click: one more buff stack on the clicked mob or player (/mobbattle buff wand). */
public class BuffGiveInteraction extends SimpleInstantInteraction {

    public static final String TYPE = "MobBattle_BuffGive";

    public static final BuilderCodec<BuffGiveInteraction> CODEC = BuilderCodec
            .builder(BuffGiveInteraction.class, BuffGiveInteraction::new, SimpleInstantInteraction.CODEC)
            .documentation("Buff Wand: one more buff stack on the clicked entity.")
            .build();

    /** The light reaching the mob, as CleanseInteraction.LIGHT_LANDS_MS (same MobBattle_Light_&lt;Color&gt; rays). */
    private static final long LIGHT_LANDS_MS = 400;

    @Override
    protected void firstRun(@Nonnull InteractionType type, @Nonnull InteractionContext context, @Nonnull CooldownHandler cooldownHandler) {
        if (!Perms.interactionAllowed(context)) return;
        CommandBuffer<EntityStore> buffer = context.getCommandBuffer();
        PlayerRef player = buffer != null ? buffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return;
        }
        Ref<EntityStore> target = context.getTargetEntity();
        if (target == null || !target.isValid() || buffer.getComponent(target, EntityStatMap.getComponentType()) == null) {
            player.sendMessage(I18n.tr("chat.look.mobOrPlayer").color("#ffcc55"));
            return;
        }
        String name = ChaosWand.nameOf(target, buffer);
        if (!Buffs.enabled(Buffs.Source.WAND)) {
            player.sendMessage(I18n.tr("chat.buff.off").color("#ffcc55"));
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            return;
        }
        // Stacks still coming down with the light count too, so quick clicks can't go past the max.
        int before = Buffs.wandStacksSoon(target);
        if (before >= Buffs.wandMax) {
            // Already at the top: the error look and a message, no buff look (nothing changed).
            WandFx.onTarget(target, WandFx.FAIL, buffer);
            player.sendMessage(I18n.tr("chat.buff.atMax").param("name", MobNames.message(name)).param("stacks", before)
                    .param("max", Buffs.wandMax).param("desc", Buffs.describeWand(before)).color("#ffcc55"));
            return;
        }
        // buffLight: a light comes down from the sky (as the Cleanse Wand's) and the buff lands with it: the mark
        // (buffColor tint, burst, aura, rune) and the stack (size, health...). The swirl shows on the click. Off, or
        // with wandFx off: all at once.
        String light = FxSettings.wandFx ? FxSettings.buffLightColor() : null;
        int stacks;
        if (light == null) {
            stacks = Buffs.addWandStack(target);
            WandFx.onTarget(target, WandFx.MARK + FxSettings.buffColor, buffer);
        } else {
            WandFx.onTarget(target, WandFx.BUFF_LIGHT + light + WandFx.lightHeight(target, buffer), buffer);
            // The swirl right on the click, so it shows the click was taken; the rest lands with the light.
            WandFx.swirlNow(target, FxSettings.buffColor, buffer);
            stacks = Buffs.addWandStackLater(target, LIGHT_LANDS_MS,
                    store -> WandFx.onTarget(target, WandFx.MARK + FxSettings.buffColor, store));
        }
        player.sendMessage(I18n.tr(stacks == Buffs.wandMax ? "chat.buff.givenMax" : "chat.buff.given")
                .param("name", MobNames.message(name)).param("stacks", stacks).param("max", Buffs.wandMax)
                .param("desc", Buffs.describeWand(stacks)).color("#ffd23e"));
    }
}
