package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.protocol.InteractionState;

import javax.annotation.Nonnull;

/** Mob Battle's stable permissions, independent of Hytale's generated command tree nodes. */
final class Perms {
    static final String USE = "dackoro.mob_battle.use";
    static final String CONFIG = "dackoro.mob_battle.config";
    static final String ADMIN = "dackoro.mob_battle.admin";

    private Perms() {
    }

    static void register() {
        PermissionsModule.registerPermission(USE, "hytale:Adventurer");
        PermissionsModule.registerPermission(CONFIG, "hytale:Admin");
        PermissionsModule.registerPermission(ADMIN, "hytale:Admin");
        PermissionsModule.get().refreshVirtualGroups();
    }

    static boolean allowed(@Nonnull CommandSender sender, @Nonnull String permission) {
        return ADMIN.equals(permission) ? admin(sender)
                : !FxSettings.serverPermissions() || sender.hasPermission(permission);
    }

    static boolean allowed(@Nonnull PlayerRef player, @Nonnull String permission) {
        return ADMIN.equals(permission) ? admin(player)
                : !FxSettings.serverPermissions() || player.hasPermission(permission);
    }

    /** Changing the global permission mode always requires a real grant, including while mode is open. */
    static boolean admin(@Nonnull CommandSender sender) {
        return sender.hasPermission(ADMIN);
    }

    static boolean admin(@Nonnull PlayerRef player) {
        return player.hasPermission(ADMIN);
    }

    /** Gate an item interaction before it can mutate a target or persistent configuration. */
    static boolean interactionAllowed(@Nonnull InteractionContext context, @Nonnull String permission) {
        var commandBuffer = context.getCommandBuffer();
        PlayerRef player = commandBuffer != null
                ? commandBuffer.getComponent(context.getEntity(), PlayerRef.getComponentType()) : null;
        if (player == null) {
            context.getState().state = InteractionState.Failed;
            return false;
        }
        String missing = !allowed(player, USE) ? USE : !allowed(player, permission) ? permission : null;
        if (missing == null) return true;
        player.sendMessage(I18n.tr("chat.perms.denied").param("perm", missing)
                .color("#ffcc55"));
        context.getState().state = InteractionState.Failed;
        return false;
    }

    static boolean interactionAllowed(@Nonnull InteractionContext context) {
        return interactionAllowed(context, USE);
    }
}
