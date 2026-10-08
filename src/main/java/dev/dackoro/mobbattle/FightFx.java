package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolLaserPointer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.PlayerUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Fight start effects: a thin laser linking both fighters (the creative Laser Pointer line,
 * BuilderToolLaserPointer packet) and a big event title (the one /eventtitle shows).
 */
public final class FightFx {

    /** Colors, styles and on/off live in FxSettings (changed with /mobbattle). */
    public static final int LASER_MILLIS = 1500;
    /** Height above the feet where the laser starts/ends, roughly chest height. */
    public static final double LASER_HEIGHT = 1.0;

    private FightFx() {
    }

    public static void showFightStart(PlayerRef player, Ref<EntityStore> playerEntity, String a, String b,
                                      Ref<EntityStore> first, Ref<EntityStore> second,
                                      ComponentAccessor<EntityStore> buffer) {
        if (FxSettings.titleEnabled) {
            showTitle(player, "FIGHT!", a + "  vs  " + b);
        }
        if (FxSettings.laserEnabled) {
            laser(playerEntity, first, second, buffer);
        }
    }

    /** How long the Chaos Wand area stays visible. */
    public static final int RING_MILLIS = 6000;

    /**
     * Marks the Chaos Wand area (/mobbattle area): floor, zone (floor + see-through wall), glow (columns of
     * light along the edge), both (zone + glow) or off. Color: /mobbattle areacolor.
     */
    public static void area(Ref<EntityStore> owner, org.joml.Vector3d center, double radius,
                            ComponentAccessor<EntityStore> buffer) {
        String style = FxSettings.areaStyle;
        if ("off".equals(style)) return;
        String hex = FxSettings.pickAreaColor();
        boolean both = "both".equals(style);
        if (both || "zone".equals(style) || "floor".equals(style)) {
            zone(center, radius, hex, !"floor".equals(style), buffer);
        }
        if (both || "glow".equals(style)) {
            glow(center, radius, hex, groundOf(buffer), buffer);
        }
    }

    /** Ground height (0.25 above the top collision block) from resident heightmaps only, or NaN. */
    private static java.util.function.DoubleBinaryOperator groundOf(ComponentAccessor<EntityStore> buffer) {
        var chunks = buffer.getExternalData().getWorld().getChunkStore();
        // Read only resident heightmaps; no chunk generation, futures or waits on the world thread.
        return (x, z) -> {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            var heights = chunks.getChunkComponent(
                    com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(bx, bz),
                    com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn.getComponentType());
            if (heights == null) return Double.NaN;
            int top = heights.getCollisionHeight(bx & 31, bz & 31);
            if (top == com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn.NO_HEIGHT) {
                return Double.NaN;
            }
            return top + 1.25;
        };
    }

    /** Opacity of the zone floor disc and wall (the game's debug shapes default to 0.8). */
    public static final float ZONE_FLOOR_OPACITY = 0.35f;
    public static final float ZONE_WALL_OPACITY = 0.18f;

    /**
     * "zone" / "floor" area: the game's own translucent shapes (DisplayDebug packet, sent by DebugUtils to every
     * player in the world), like boss attack telegraphs. A flat disc on the ground at the center height, and for
     * "zone" also a see-through cylinder wall up to /mobbattle height, visible over hills and bushes.
     * Vanilla AOECylinderSelector passes range * 2 to addCylinder: the unit cylinder has diameter 1.
     */
    private static void zone(org.joml.Vector3d c, double radius, String hex, boolean wall,
                             ComponentAccessor<EntityStore> buffer) {
        var world = buffer.getExternalData().getWorld();
        var rgb = ColorParseUtil.hexStringToColor(hex);
        var color = new org.joml.Vector3f((rgb.red & 255) / 255f, (rgb.green & 255) / 255f, (rgb.blue & 255) / 255f);
        float seconds = RING_MILLIS / 1000f;
        com.hypixel.hytale.server.core.modules.debug.DebugUtils.addDisc(world, c.x(), c.y() + 0.1, c.z(),
                radius, 0, color, ZONE_FLOOR_OPACITY, seconds, com.hypixel.hytale.server.core.modules.debug.DebugUtils.FLAG_NONE);
        if (wall) {
            double h = FxSettings.pillarHeight;
            var m = new org.joml.Matrix4d().translate(c.x(), c.y() + h / 2, c.z()).scale(radius * 2, h, radius * 2);
            com.hypixel.hytale.server.core.modules.debug.DebugUtils.add(world,
                    com.hypixel.hytale.protocol.DebugShape.Cylinder, m, color, ZONE_WALL_OPACITY, seconds,
                    com.hypixel.hytale.server.core.modules.debug.DebugUtils.FLAG_NONE);
        }
    }

    public static final String AREA_GLOW_ID = "MobBattle_AreaGlow";
    /** Glow columns about every 2 blocks of the edge, capped so huge radii stay cheap. */
    public static final int GLOW_MAX_POINTS = 120;

    /**
     * "glow" area: tall columns of rising light along the edge, one SpawnParticleSystem packet each,
     * tinted with the area color and cut after RING_MILLIS (the asset also stops itself after 6 s).
     */
    private static void glow(org.joml.Vector3d c, double radius, String hex,
                             java.util.function.DoubleBinaryOperator ground, ComponentAccessor<EntityStore> buffer) {
        var tint = ColorParseUtil.hexStringToColor(hex);
        int points = Math.max(12, Math.min(GLOW_MAX_POINTS, (int) Math.ceil(Math.PI * radius)));
        var packets = new com.hypixel.hytale.protocol.ToClientPacket[points];
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = c.x() + Math.cos(a) * radius;
            double z = c.z() + Math.sin(a) * radius;
            double y = ground.applyAsDouble(x, z);
            // ground() is 0.25 above the surface; unloaded columns use the center height.
            y = Double.isFinite(y) ? y - 0.25 : c.y();
            packets[i] = new com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem(AREA_GLOW_ID,
                    new com.hypixel.hytale.protocol.Position(x, y, z), null, 1f, tint, RING_MILLIS / 1000f);
        }
        PlayerUtil.broadcastPacketToPlayers(buffer, packets);
    }

    public static final String TEAM_WAND_ID = "MobBattle_TeamWand";
    /** Team rings are redrawn this often and last a bit longer, so they look continuous. */
    public static final long TEAM_RING_REFRESH_MS = 1000;
    private static final int TEAM_RING_MILLIS = 1150;
    private static final int TEAM_RING_SEGMENTS = 10;
    private static final double TEAM_RING_RADIUS = 0.9;

    /** True if the player currently holds the Team Wand. */
    public static boolean holdsTeamWand(PlayerRef player, ComponentAccessor<EntityStore> buffer) {
        if (player == null || !player.isValid()) {
            return false;
        }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        InventoryComponent.Hotbar hotbar = buffer.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        ItemStack item = hotbar != null ? hotbar.getActiveItem() : null;
        return item != null && TEAM_WAND_ID.equals(item.getItemId());
    }

    /** Small colored ring at the NPC's feet, sent only to {@code player}. */
    public static void teamRing(PlayerRef player, Ref<EntityStore> npc, String color,
                                ComponentAccessor<EntityStore> buffer) {
        TransformComponent t = buffer.getComponent(npc, TransformComponent.getComponentType());
        NetworkId ownerId = buffer.getComponent(player.getReference(), NetworkId.getComponentType());
        if (t == null || ownerId == null) {
            return;
        }
        var c = t.getPosition();
        int rgb = ColorParseUtil.hexStringToRGBInt(color);
        float y = (float) (c.y() + 0.1);
        for (int i = 0; i < TEAM_RING_SEGMENTS; i++) {
            double a0 = 2 * Math.PI * i / TEAM_RING_SEGMENTS;
            double a1 = 2 * Math.PI * (i + 1) / TEAM_RING_SEGMENTS;
            player.getPacketHandler().write(new BuilderToolLaserPointer(ownerId.getId(),
                    (float) (c.x() + Math.cos(a0) * TEAM_RING_RADIUS), y, (float) (c.z() + Math.sin(a0) * TEAM_RING_RADIUS),
                    (float) (c.x() + Math.cos(a1) * TEAM_RING_RADIUS), y, (float) (c.z() + Math.sin(a1) * TEAM_RING_RADIUS),
                    rgb, TEAM_RING_MILLIS));
        }
    }

    /** Laser between two entities in a fixed color (team battles). */
    public static void link(Ref<EntityStore> owner, Ref<EntityStore> from, Ref<EntityStore> to, String color,
                            ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.laserEnabled) {
            return;
        }
        TransformComponent a = buffer.getComponent(from, TransformComponent.getComponentType());
        TransformComponent b = buffer.getComponent(to, TransformComponent.getComponentType());
        NetworkId ownerId = buffer.getComponent(owner, NetworkId.getComponentType());
        if (a == null || b == null || ownerId == null) {
            return;
        }
        var start = a.getPosition();
        var end = b.getPosition();
        PlayerUtil.broadcastPacketToPlayers(buffer, new BuilderToolLaserPointer(ownerId.getId(),
                (float) start.x(), (float) (start.y() + LASER_HEIGHT), (float) start.z(),
                (float) end.x(), (float) (end.y() + LASER_HEIGHT), (float) end.z(),
                ColorParseUtil.hexStringToRGBInt(color), LASER_MILLIS));
    }

    /** Big title for group fights. */
    public static void title(PlayerRef player, String main, String sub) {
        if (FxSettings.titleEnabled) {
            showTitle(player, main, sub);
        }
    }

    /** Winner titles have their own switch, independent of fight-start titles. */
    public static void winnerTitle(PlayerRef player, String main, String sub) {
        if (FxSettings.winTitleEnabled) {
            showTitle(player, main, sub, true);
        }
    }

    /** Seconds a title stays fully visible / fades out (vanilla default is 4 / 1.5). */
    public static final float TITLE_SECONDS = 2.5f;
    public static final float TITLE_FADE_OUT = 0.5f;

    private record PendingTitle(String main, String sub, boolean victory) {
    }

    /** Per player: current title deadline, queued victories and latest fight-start title. */
    private static final java.util.Map<java.util.UUID, Long> TITLE_BUSY_UNTIL = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, BattleTitleQueue<PendingTitle>> TITLE_PENDING = new java.util.HashMap<>();
    private static final java.util.Set<java.util.UUID> TITLE_SCHEDULED = new java.util.HashSet<>();

    /**
     * The client queues event titles, so many orders in a row would play one after another for a long time.
     * Keep all victory announcements, and coalesce only start titles. Victories have priority after
     * the currently visible title finishes, so a fast fight cannot lose its winner announcement.
     */
    private static void showTitle(PlayerRef player, String main, String sub) {
        showTitle(player, main, sub, false);
    }

    private static synchronized void showTitle(PlayerRef player, String main, String sub, boolean victory) {
        java.util.UUID id = player.getUuid();
        TITLE_PENDING.computeIfAbsent(id, ignored -> new BattleTitleQueue<>())
                .offer(new PendingTitle(main, sub, victory), victory);
        if (!TITLE_SCHEDULED.contains(id)) flushTitle(player);
    }

    private static synchronized void flushTitle(PlayerRef player) {
        java.util.UUID id = player.getUuid();
        TITLE_SCHEDULED.remove(id);
        BattleTitleQueue<PendingTitle> pending = TITLE_PENDING.get(id);
        if (!player.isValid()) {
            TITLE_PENDING.remove(id);
            TITLE_BUSY_UNTIL.remove(id);
            return;
        }
        if (pending == null || pending.isEmpty()) {
            TITLE_PENDING.remove(id);
            return;
        }
        long now = System.currentTimeMillis();
        long busyUntil = TITLE_BUSY_UNTIL.getOrDefault(id, 0L);
        if (now >= busyUntil) {
            PendingTitle next = pending.poll();
            sendTitle(player, next, now);
            if (pending.isEmpty()) {
                TITLE_PENDING.remove(id);
                return;
            }
            busyUntil = TITLE_BUSY_UNTIL.getOrDefault(id, now);
        }
        TITLE_SCHEDULED.add(id);
        com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(
                () -> flushTitle(player), Math.max(1, busyUntil - now), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void sendTitle(PlayerRef player, PendingTitle title, long now) {
        var style = FxSettings.pickTitleStyle();
        if (title.victory() && style == com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle.GoblinBreach) {
            style = com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle.Major;
        }
        float fadeIn = EventTitleUtil.getDefaultFadeInDuration(style);
        EventTitleUtil.showEventTitleToPlayer(player, Message.raw(title.main()), Message.raw(title.sub()), style, null,
                TITLE_SECONDS, fadeIn, TITLE_FADE_OUT);
        TITLE_BUSY_UNTIL.put(player.getUuid(), now + (long) ((fadeIn + TITLE_SECONDS + TITLE_FADE_OUT) * 1000));
    }

    /** Laser between two entities (public for the Chaos Wand). */
    public static void link(Ref<EntityStore> owner, Ref<EntityStore> from, Ref<EntityStore> to,
                            ComponentAccessor<EntityStore> buffer) {
        if (FxSettings.laserEnabled) {
            laser(owner, from, to, buffer);
        }
    }

    private static void laser(Ref<EntityStore> owner, Ref<EntityStore> from, Ref<EntityStore> to,
                              ComponentAccessor<EntityStore> buffer) {
        TransformComponent a = buffer.getComponent(from, TransformComponent.getComponentType());
        TransformComponent b = buffer.getComponent(to, TransformComponent.getComponentType());
        NetworkId ownerId = buffer.getComponent(owner, NetworkId.getComponentType());
        if (a == null || b == null || ownerId == null) {
            return;
        }
        var start = a.getPosition();
        var end = b.getPosition();
        BuilderToolLaserPointer packet = new BuilderToolLaserPointer(ownerId.getId(),
                (float) start.x(), (float) (start.y() + LASER_HEIGHT), (float) start.z(),
                (float) end.x(), (float) (end.y() + LASER_HEIGHT), (float) end.z(),
                ColorParseUtil.hexStringToRGBInt(FxSettings.pickLaserColor()), LASER_MILLIS);
        PlayerUtil.broadcastPacketToPlayers(buffer, packet);
    }
}
