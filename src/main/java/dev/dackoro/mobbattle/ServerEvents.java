package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Admin commands for ownerless, server-launched battles. Start lasers are intentionally omitted: the packet's
 * emitter is a player NetworkId, and this server build has not verified that an NPC NetworkId is accepted.
 */
final class ServerEvents {
    private static final String OK = "#ffd23e";
    private static final String ERROR = "#ff6b5e";
    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = FxSettings.MAX_RADIUS;
    private static final int LIST_DEFAULT_RADIUS = 32;
    private static final int LIST_MAX_RADIUS = 128;
    private static final int LIST_ROWS = 20;

    private ServerEvents() {}

    /** args[0] is "event"; the permission check and dispatch remain in MobBattleCommand. */
    static void handle(CommandContext ctx, String[] args) {
        if (args == null || args.length < 2) {
            reply(ctx, I18n.tr("chat.event.usage"), ERROR);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "duel" -> duel(ctx, args);
            case "royale" -> royale(ctx, args);
            case "focus" -> focus(ctx, args);
            case "list" -> list(ctx, args);
            default -> reply(ctx, I18n.tr("chat.event.usage"), ERROR);
        }
    }

    private static void duel(CommandContext ctx, String[] args) {
        if (args.length < 4 || args.length > 5) {
            reply(ctx, I18n.tr("chat.event.usage.duel"), ERROR);
            return;
        }
        UUID a = parseUuid(args[2]), b = parseUuid(args[3]);
        if (a == null || b == null || a.equals(b)) {
            reply(ctx, I18n.tr("chat.event.duel.invalid"), ERROR);
            return;
        }
        World world = resolveWorld(ctx, args.length == 5 ? args[4] : null);
        if (world == null) return;
        queue(ctx, "Duel", api -> api.startDuel(world, a, b));
    }

    private static void royale(CommandContext ctx, String[] args) {
        if (args.length < 6 || args.length > 8) {
            reply(ctx, I18n.tr("chat.event.usage.royale"), ERROR);
            return;
        }
        Double x = finite(args[2]), y = finite(args[3]), z = finite(args[4]), radiusValue = finite(args[5]);
        if (x == null || y == null || z == null || radiusValue == null || radiusValue < MIN_RADIUS || radiusValue > MAX_RADIUS) {
            reply(ctx, I18n.tr("chat.event.royale.coords"), ERROR);
            return;
        }
        int maxMobs = FxSettings.maxMobs;
        String worldName = null;
        if (args.length == 7) {
            Integer n = integer(args[6]);
            if (n == null) worldName = args[6];
            else maxMobs = n;
        } else if (args.length == 8) {
            Integer n = integer(args[6]);
            if (n == null) {
                reply(ctx, I18n.tr("chat.event.maxmobs.int").param("limit", FxSettings.MAX_MOBS_LIMIT), ERROR);
                return;
            }
            maxMobs = n;
            worldName = args[7];
        }
        if (maxMobs < 2 || maxMobs > FxSettings.MAX_MOBS_LIMIT) {
            reply(ctx, I18n.tr("chat.event.maxmobs.range").param("limit", FxSettings.MAX_MOBS_LIMIT), ERROR);
            return;
        }
        World world = resolveWorld(ctx, worldName);
        if (world == null) return;
        Vector3d center = new Vector3d(x, y, z);
        int cap = maxMobs;
        queue(ctx, "Royale", api -> api.startRoyale(world, center, radiusValue, cap));
    }

    private static void focus(CommandContext ctx, String[] args) {
        if (args.length < 3 || args.length > 4) {
            reply(ctx, I18n.tr("chat.event.usage.focus"), ERROR);
            return;
        }
        UUID targetId = parseUuid(args[2]);
        if (targetId == null) {
            reply(ctx, I18n.tr("chat.event.focus.invalid"), ERROR);
            return;
        }
        World world = resolveWorld(ctx, args.length == 4 ? args[3] : null);
        if (world == null) return;
        queue(ctx, "Focus", api -> api.startFocus(world, targetId, FxSettings.radius, FxSettings.maxMobs));
    }

    private static void list(CommandContext ctx, String[] args) {
        if (!ctx.isPlayer()) {
            reply(ctx, I18n.tr("chat.event.usage.list.player"), ERROR);
            return;
        }
        if (args.length > 3) {
            reply(ctx, I18n.tr("chat.event.usage.list"), ERROR);
            return;
        }
        double radius = LIST_DEFAULT_RADIUS;
        if (args.length == 3) {
            Double n = finite(args[2]);
            if (n == null || n < 1 || n > LIST_MAX_RADIUS) {
                reply(ctx, I18n.tr("chat.event.list.radius").param("max", LIST_MAX_RADIUS), ERROR);
                return;
            }
            radius = n;
        }
        final double scanRadius = radius;
        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            reply(ctx, I18n.tr("chat.event.list.noplayer"), ERROR);
            return;
        }
        Store<EntityStore> store = playerRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (store.isShutdown() || !playerRef.isValid()) return;
            Vector3d center = ChaosWand.positionOf(playerRef, store);
            if (center == null) {
                reply(ctx, I18n.tr("chat.event.list.nopos"), ERROR);
                return;
            }
            List<Ref<EntityStore>> mobs = ChaosWand.npcsAround(center, scanRadius, FxSettings.MAX_MOBS_LIMIT, store);
            if (mobs.isEmpty()) {
                reply(ctx, I18n.tr("chat.event.list.none").param("radius", scanRadius), ERROR);
                return;
            }
            int count = Math.min(LIST_ROWS, mobs.size());
            reply(ctx, I18n.tr("chat.event.list.header").param("radius", scanRadius).param("count", mobs.size()), OK);
            for (int i = 0; i < count; i++) {
                Ref<EntityStore> mob = mobs.get(i);
                UUIDComponent uuid = store.getComponent(mob, UUIDComponent.getComponentType());
                String name = ChaosWand.nameOf(mob, store);
                reply(ctx, "  " + (uuid != null ? uuid.getUuid() : "UUID unavailable") + "  " + name, "#c8e6ff");
            }
            if (mobs.size() > count) reply(ctx, I18n.tr("chat.event.list.more").param("count", mobs.size() - count), "#aaaaaa");
        });
    }

    private static World resolveWorld(CommandContext ctx, String requested) {
        World world = null;
        if (requested != null && !requested.isBlank()) {
            UUID id = parseUuid(requested);
            world = id != null ? Universe.get().getWorld(id) : Universe.get().getWorld(requested);
            if (world == null) reply(ctx, I18n.tr("chat.event.world.notfound").param("name", requested), ERROR);
            return world;
        }
        if (ctx.isPlayer()) {
            Ref<EntityStore> player = ctx.senderAsPlayerRef();
            if (player != null && player.isValid()) world = player.getStore().getExternalData().getWorld();
        } else {
            world = Universe.get().getDefaultWorld();
        }
        if (world == null) reply(ctx, I18n.tr("chat.event.world.none"), ERROR);
        return world;
    }

    private static boolean livingNpc(Ref<EntityStore> ref, com.hypixel.hytale.component.ComponentAccessor<EntityStore> store) {
        return ref != null && ref.isValid() && store.getComponent(ref, NPCEntity.getComponentType()) != null
                && BattleManager.isAlive(ref, store);
    }

    private static Vector3d midpoint(Ref<EntityStore> a, Ref<EntityStore> b,
                                     com.hypixel.hytale.component.ComponentAccessor<EntityStore> store) {
        Vector3d x = ChaosWand.positionOf(a, store), y = ChaosWand.positionOf(b, store);
        return x == null || y == null ? null : new Vector3d(x).add(y).mul(0.5);
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); } catch (RuntimeException ignored) { return null; }
    }

    private static Double finite(String value) {
        try {
            double n = Double.parseDouble(value);
            return Double.isFinite(n) ? n : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static Integer integer(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return null; }
    }

    private static void queue(CommandContext ctx, String label,
                              Function<MobBattleApi, CompletableFuture<MobBattleApi.StartResult>> request) {
        MobBattleApi api = MobBattlePlugin.getApi();
        if (api == null) {
            reply(ctx, I18n.tr("chat.event.cancelled.shutdown").param("label", labelText(label)), ERROR);
            return;
        }
        try {
            request.apply(api).whenComplete((result, failure) -> {
                if (failure != null) {
                    MobBattlePlugin.log("Server event " + label.toLowerCase(Locale.ROOT) + " request failed: "
                            + failure.getClass().getSimpleName());
                    reply(ctx, I18n.tr("chat.event.cancelled.failed").param("label", labelText(label)), ERROR);
                } else if (result == null) {
                    reply(ctx, I18n.tr("chat.event.cancelled.noresult").param("label", labelText(label)), ERROR);
                } else if (result.accepted()) {
                    reply(ctx, I18n.tr("chat.event.queued").param("label", labelText(label)).param("id", String.valueOf(result.snapshot().eventId())), OK);
                } else {
                    reply(ctx, I18n.tr("chat.event.cancelled.error").param("label", labelText(label)).param("error", String.valueOf(result.error())), ERROR);
                }
            });
        } catch (Throwable failure) {
            MobBattlePlugin.log("Server event " + label.toLowerCase(Locale.ROOT) + " request failed: "
                    + failure.getClass().getSimpleName());
            reply(ctx, I18n.tr("chat.event.cancelled.failed").param("label", labelText(label)), ERROR);
        }
    }

    private static Message labelText(String label) {
        return I18n.tr("chat.event.label." + label.toLowerCase(Locale.ROOT));
    }

    private static void reply(CommandContext ctx, Message text, String color) {
        if (ctx != null) ctx.sendMessage(text.color(color));
    }

    private static void reply(CommandContext ctx, String text, String color) {
        if (ctx != null) ctx.sendMessage(Message.raw(text).color(color));
    }
}
