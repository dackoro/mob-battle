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
    private static final int MAX_RADIUS = 512;
    private static final int LIST_DEFAULT_RADIUS = 32;
    private static final int LIST_MAX_RADIUS = 128;
    private static final int LIST_ROWS = 20;

    private ServerEvents() {}

    /** args[0] is "event"; the permission check and dispatch remain in MobBattleCommand. */
    static void handle(CommandContext ctx, String[] args) {
        if (args == null || args.length < 2) {
            reply(ctx, "Usage: /mobbattle event <duel|royale|focus|list> ...", ERROR);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "duel" -> duel(ctx, args);
            case "royale" -> royale(ctx, args);
            case "focus" -> focus(ctx, args);
            case "list" -> list(ctx, args);
            default -> reply(ctx, "Usage: /mobbattle event <duel|royale|focus|list> ...", ERROR);
        }
    }

    private static void duel(CommandContext ctx, String[] args) {
        if (args.length < 4 || args.length > 5) {
            reply(ctx, "Usage: /mobbattle event duel <uuidA> <uuidB> [world]", ERROR);
            return;
        }
        UUID a = parseUuid(args[2]), b = parseUuid(args[3]);
        if (a == null || b == null || a.equals(b)) {
            reply(ctx, "Duel needs two different, valid entity UUIDs.", ERROR);
            return;
        }
        World world = resolveWorld(ctx, args.length == 5 ? args[4] : null);
        if (world == null) return;
        queue(ctx, "Duel", api -> api.startDuel(world, a, b));
    }

    private static void royale(CommandContext ctx, String[] args) {
        if (args.length < 6 || args.length > 8) {
            reply(ctx, "Usage: /mobbattle event royale <x> <y> <z> <radio> [maxMobs] [world]", ERROR);
            return;
        }
        Double x = finite(args[2]), y = finite(args[3]), z = finite(args[4]), radiusValue = finite(args[5]);
        if (x == null || y == null || z == null || radiusValue == null || radiusValue < MIN_RADIUS || radiusValue > MAX_RADIUS) {
            reply(ctx, "Coordinates must be finite and radio must be 1-512.", ERROR);
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
                reply(ctx, "maxMobs must be an integer from 2 to " + FxSettings.MAX_MOBS_LIMIT + ".", ERROR);
                return;
            }
            maxMobs = n;
            worldName = args[7];
        }
        if (maxMobs < 2 || maxMobs > FxSettings.MAX_MOBS_LIMIT) {
            reply(ctx, "maxMobs must be 2-" + FxSettings.MAX_MOBS_LIMIT + ".", ERROR);
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
            reply(ctx, "Usage: /mobbattle event focus <uuid> [world]", ERROR);
            return;
        }
        UUID targetId = parseUuid(args[2]);
        if (targetId == null) {
            reply(ctx, "Focus needs a valid NPC UUID.", ERROR);
            return;
        }
        World world = resolveWorld(ctx, args.length == 4 ? args[3] : null);
        if (world == null) return;
        queue(ctx, "Focus", api -> api.startFocus(world, targetId, FxSettings.radius, FxSettings.maxMobs));
    }

    private static void list(CommandContext ctx, String[] args) {
        if (!ctx.isPlayer()) {
            reply(ctx, "Usage (player only): /mobbattle event list [radio]", ERROR);
            return;
        }
        if (args.length > 3) {
            reply(ctx, "Usage: /mobbattle event list [radio]", ERROR);
            return;
        }
        double radius = LIST_DEFAULT_RADIUS;
        if (args.length == 3) {
            Double n = finite(args[2]);
            if (n == null || n < 1 || n > LIST_MAX_RADIUS) {
                reply(ctx, "List radius must be 1-" + LIST_MAX_RADIUS + " blocks.", ERROR);
                return;
            }
            radius = n;
        }
        final double scanRadius = radius;
        Ref<EntityStore> playerRef = ctx.senderAsPlayerRef();
        if (playerRef == null || !playerRef.isValid()) {
            reply(ctx, "Player entity is no longer available.", ERROR);
            return;
        }
        Store<EntityStore> store = playerRef.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            if (store.isShutdown() || !playerRef.isValid()) return;
            Vector3d center = ChaosWand.positionOf(playerRef, store);
            if (center == null) {
                reply(ctx, "Could not read your current position.", ERROR);
                return;
            }
            List<Ref<EntityStore>> mobs = ChaosWand.npcsAround(center, scanRadius, FxSettings.MAX_MOBS_LIMIT, store);
            if (mobs.isEmpty()) {
                reply(ctx, "No living NPCs within " + scanRadius + " blocks.", ERROR);
                return;
            }
            int count = Math.min(LIST_ROWS, mobs.size());
            reply(ctx, "Living NPCs within " + scanRadius + " blocks (" + mobs.size() + "): ", OK);
            for (int i = 0; i < count; i++) {
                Ref<EntityStore> mob = mobs.get(i);
                UUIDComponent uuid = store.getComponent(mob, UUIDComponent.getComponentType());
                String name = ChaosWand.nameOf(mob, store);
                reply(ctx, "  " + (uuid != null ? uuid.getUuid() : "UUID unavailable") + "  " + name, "#c8e6ff");
            }
            if (mobs.size() > count) reply(ctx, "  ... and " + (mobs.size() - count) + " more.", "#aaaaaa");
        });
    }

    private static World resolveWorld(CommandContext ctx, String requested) {
        World world = null;
        if (requested != null && !requested.isBlank()) {
            UUID id = parseUuid(requested);
            world = id != null ? Universe.get().getWorld(id) : Universe.get().getWorld(requested);
            if (world == null) reply(ctx, "World not found: " + requested, ERROR);
            return world;
        }
        if (ctx.isPlayer()) {
            Ref<EntityStore> player = ctx.senderAsPlayerRef();
            if (player != null && player.isValid()) world = player.getStore().getExternalData().getWorld();
        } else {
            world = Universe.get().getDefaultWorld();
        }
        if (world == null) reply(ctx, "No current/default world. Pass a world name or UUID.", ERROR);
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
            reply(ctx, label + " cancelled: Mob Battle is shutting down.", ERROR);
            return;
        }
        try {
            request.apply(api).whenComplete((result, failure) -> {
                if (failure != null) {
                    MobBattlePlugin.log("Server event " + label.toLowerCase(Locale.ROOT) + " request failed: "
                            + failure.getClass().getSimpleName());
                    reply(ctx, label + " cancelled: the event request failed.", ERROR);
                } else if (result == null) {
                    reply(ctx, label + " cancelled: the event request returned no result.", ERROR);
                } else if (result.accepted()) {
                    reply(ctx, label + " queued. Event ID: " + result.snapshot().eventId(), OK);
                } else {
                    reply(ctx, label + " cancelled: " + result.error(), ERROR);
                }
            });
        } catch (Throwable failure) {
            MobBattlePlugin.log("Server event " + label.toLowerCase(Locale.ROOT) + " request failed: "
                    + failure.getClass().getSimpleName());
            reply(ctx, label + " cancelled: the event request failed.", ERROR);
        }
    }

    private static void reply(CommandContext ctx, String text, String color) {
        if (ctx != null) ctx.sendMessage(Message.raw(text).color(color));
    }
}
