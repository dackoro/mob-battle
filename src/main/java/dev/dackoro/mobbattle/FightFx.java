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
    /** With /mobbattle areaonlywand on: redraw interval and lifetime of each redraw (a bit longer: no gaps). */
    public static final long HELD_AREA_REFRESH_MS = 1000;
    public static final float HELD_AREA_SECONDS = 1.3f;
    public static final String CHAOS_WAND_ID = "MobBattle_ChaosWand";

    /** The last area of a player, redrawn while they hold the Chaos Wand (areaonlywand mode). */
    private record HeldArea(PlayerRef player, com.hypixel.hytale.component.Store<EntityStore> store,
                            org.joml.Vector3d center, double radius, String style, String colorName) {
    }

    private static final java.util.Map<java.util.UUID, HeldArea> HELD_AREAS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Marks the Chaos Wand area (/mobbattle areastyle): floor, zone (floor + see-through wall), glow (columns of
     * light along the edge), both (floor + glow), all (zone + glow) or off. Color: /mobbattle areacolor.
     * Default: everyone sees it for 6 s. With /mobbattle areaonlywand on, only the owner sees it, and only
     * while holding the Chaos Wand (like the Team Wand rings); a new area replaces the previous one.
     */
    public static void area(Ref<EntityStore> owner, org.joml.Vector3d center, double radius,
                            ComponentAccessor<EntityStore> buffer) {
        String style = FxSettings.areaStyle;
        if ("off".equals(style)) return;
        String name = FxSettings.pickAreaColorName();
        if (FxSettings.areaOnlyWand) {
            PlayerRef player = owner != null && owner.isValid()
                    ? buffer.getComponent(owner, PlayerRef.getComponentType()) : null;
            if (player == null) return;
            HeldArea held = new HeldArea(player, owner.getStore(), new org.joml.Vector3d(center), radius, style, name);
            HELD_AREAS.put(player.getUuid(), held);
            if (holdsItem(player, CHAOS_WAND_ID, buffer)) {
                drawArea(held.center(), radius, style, name, HELD_AREA_SECONDS, player, buffer);
            }
            return;
        }
        drawArea(center, radius, style, name, RING_MILLIS / 1000f, null, buffer);
    }

    /** Once per second per world (BattleLifecycleSystem): redraw held areas whose owner holds the Chaos Wand. */
    static void refreshHeldAreas(com.hypixel.hytale.component.Store<EntityStore> store) {
        if (HELD_AREAS.isEmpty()) return;
        HELD_AREAS.values().removeIf(h -> !h.player().isValid() || !FxSettings.areaOnlyWand);
        for (HeldArea h : HELD_AREAS.values()) {
            if (h.store() == store && holdsItem(h.player(), CHAOS_WAND_ID, store)) {
                drawArea(h.center(), h.radius(), h.style(), h.colorName(), HELD_AREA_SECONDS, h.player(), store);
            }
        }
    }

    /** Draws the area for {@code seconds}, to everyone in the world, or only to {@code only} when not null. */
    private static void drawArea(org.joml.Vector3d center, double radius, String style, String colorName, float seconds,
                                 PlayerRef only, ComponentAccessor<EntityStore> buffer) {
        java.util.List<com.hypixel.hytale.protocol.ToClientPacket> packets = new java.util.ArrayList<>();
        var ground = groundOf(buffer);
        boolean all = "all".equals(style);
        if (all || "both".equals(style) || "zone".equals(style) || "floor".equals(style)) {
            zone(center, radius, Teams.PALETTE.get(colorName), all || "zone".equals(style), seconds, ground, packets);
        }
        if (all || "both".equals(style) || "glow".equals(style)) {
            glow(center, radius, colorName, seconds, ground, packets);
        }
        if (packets.isEmpty()) return;
        if (only != null) {
            packets.forEach(only.getPacketHandler()::write);
        } else {
            PlayerUtil.broadcastPacketToPlayers(buffer, packets.toArray(new com.hypixel.hytale.protocol.ToClientPacket[0]));
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
    private static final int ZONE_DISC_SEGMENTS = 64;

    /**
     * "zone" / "floor" area: the game's own translucent shapes (DisplayDebug packets, built like DebugUtils does,
     * so they can go to one player), like boss attack telegraphs. A flat disc on the ground under the center, and
     * for "zone" / "all" also a see-through cylinder wall up to /mobbattle areaheight.
     * Disc data layout as in DebugUtils.addDisc: [outer, segments, inner, 0...]. Vanilla AOECylinderSelector
     * passes range * 2 to addCylinder: the unit cylinder has diameter 1.
     */
    private static void zone(org.joml.Vector3d c, double radius, String hex, boolean wall, float seconds,
                             java.util.function.DoubleBinaryOperator ground,
                             java.util.List<com.hypixel.hytale.protocol.ToClientPacket> out) {
        var rgb = ColorParseUtil.hexStringToColor(hex);
        var color = new org.joml.Vector3f((rgb.red & 255) / 255f, (rgb.green & 255) / 255f, (rgb.blue & 255) / 255f);
        byte flags = (byte) com.hypixel.hytale.server.core.modules.debug.DebugUtils.FLAG_NO_WIREFRAME;
        // On the ground under the center (like glow), not at the clicker's height; unloaded: center height.
        double surface = ground.applyAsDouble(c.x(), c.z());
        double baseY = Double.isFinite(surface) ? surface - 0.25 : c.y();
        float[] disc = new float[16];
        disc[0] = (float) radius;
        disc[1] = ZONE_DISC_SEGMENTS;
        disc[2] = 0;
        var floor = new org.joml.Matrix4d().translate(c.x(), baseY + 0.1, c.z());
        out.add(new com.hypixel.hytale.protocol.packets.player.DisplayDebug(com.hypixel.hytale.protocol.DebugShape.Disc,
                com.hypixel.hytale.math.matrix.Matrix4dUtil.asFloatData(floor), color, seconds, flags, disc,
                ZONE_FLOOR_OPACITY));
        if (wall) {
            double h = FxSettings.pillarHeight;
            var m = new org.joml.Matrix4d().translate(c.x(), baseY + h / 2, c.z()).scale(radius * 2, h, radius * 2);
            out.add(new com.hypixel.hytale.protocol.packets.player.DisplayDebug(com.hypixel.hytale.protocol.DebugShape.Cylinder,
                    com.hypixel.hytale.math.matrix.Matrix4dUtil.asFloatData(m), color, seconds, flags, null,
                    ZONE_WALL_OPACITY));
        }
    }

    /**
     * One prebuilt glow per palette color: the client ignores the packet tint. MobBattle_AreaGlow_<Color> lasts
     * 6 s; MobBattle_AreaGlowShort_<Color> about 1.3 s, for the areaonlywand redraws (stops soon after the wand
     * is put away).
     */
    public static final String AREA_GLOW_ID = "MobBattle_AreaGlow_";
    public static final String AREA_GLOW_SHORT_ID = "MobBattle_AreaGlowShort_";
    /** Glow columns about every 2 blocks of the edge, capped so huge radii stay cheap. */
    public static final int GLOW_MAX_POINTS = 120;

    /** "glow" area: tall columns of rising light along the edge, one SpawnParticleSystem packet each. */
    private static void glow(org.joml.Vector3d c, double radius, String colorName, float seconds,
                             java.util.function.DoubleBinaryOperator ground,
                             java.util.List<com.hypixel.hytale.protocol.ToClientPacket> out) {
        var tint = ColorParseUtil.hexStringToColor(Teams.PALETTE.get(colorName));
        String glowId = (seconds < RING_MILLIS / 1000f ? AREA_GLOW_SHORT_ID : AREA_GLOW_ID) + colorName;
        int points = Math.max(12, Math.min(GLOW_MAX_POINTS, (int) Math.ceil(Math.PI * radius)));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            double x = c.x() + Math.cos(a) * radius;
            double z = c.z() + Math.sin(a) * radius;
            double y = ground.applyAsDouble(x, z);
            // ground() is 0.25 above the surface; unloaded columns use the center height.
            y = Double.isFinite(y) ? y - 0.25 : c.y();
            out.add(new com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem(glowId,
                    new com.hypixel.hytale.protocol.Position(x, y, z), null, 1f, tint, seconds));
        }
    }

    public static final String TEAM_WAND_ID = "MobBattle_TeamWand";
    /** Team rings are redrawn this often and last a bit longer, so they look continuous. */
    public static final long TEAM_RING_REFRESH_MS = 1000;
    private static final int TEAM_RING_MILLIS = 1150;

    /** True if the player currently holds the Team Wand and is in the same world as {@code buffer}. */
    public static boolean holdsTeamWand(PlayerRef player, ComponentAccessor<EntityStore> buffer) {
        return holdsItem(player, TEAM_WAND_ID, buffer);
    }

    /** True if the player is in this world and the active hotbar item is {@code itemId}. */
    public static boolean holdsItem(PlayerRef player, String itemId, ComponentAccessor<EntityStore> buffer) {
        if (player == null || !player.isValid()) {
            return false;
        }
        Ref<EntityStore> ref = player.getReference();
        // A player who moved to another world (instance) must not be read from this world's thread.
        if (ref == null || !ref.isValid() || ref.getStore().getExternalData() != buffer.getExternalData()) {
            return false;
        }
        InventoryComponent.Hotbar hotbar = buffer.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        ItemStack item = hotbar != null ? hotbar.getActiveItem() : null;
        return item != null && itemId.equals(item.getItemId());
    }

    /** Small colored ring at the NPC's feet, sent only to {@code player}. */
    /** Team ring: inner/outer radius, opacity and segments of the flat ring under the mob. */
    private static final float TEAM_RING_INNER = 0.65f;
    private static final float TEAM_RING_OUTER = 1.0f;
    private static final float TEAM_RING_OPACITY = 0.6f;
    private static final int TEAM_RING_SMOOTHNESS = 32;

    /**
     * Solid colored ring on the ground under the mob, only for {@code player}: the game's Disc debug shape
     * (DisplayDebug, the same one DebugUtils.addDisc builds) with an inner radius, written to that player only.
     * Data layout as in DebugUtils.addDisc: [outer, segments, inner, 0...].
     */
    public static void teamRing(PlayerRef player, Ref<EntityStore> npc, String color,
                                ComponentAccessor<EntityStore> buffer) {
        TransformComponent t = buffer.getComponent(npc, TransformComponent.getComponentType());
        if (t == null) {
            return;
        }
        var c = t.getPosition();
        var rgb = ColorParseUtil.hexStringToColor(color);
        var tint = new org.joml.Vector3f((rgb.red & 255) / 255f, (rgb.green & 255) / 255f, (rgb.blue & 255) / 255f);
        var m = new org.joml.Matrix4d().translate(c.x(), c.y() + 0.08, c.z());
        float[] data = new float[16];
        data[0] = TEAM_RING_OUTER;
        data[1] = TEAM_RING_SMOOTHNESS;
        data[2] = TEAM_RING_INNER;
        player.getPacketHandler().write(new com.hypixel.hytale.protocol.packets.player.DisplayDebug(
                com.hypixel.hytale.protocol.DebugShape.Disc, com.hypixel.hytale.math.matrix.Matrix4dUtil.asFloatData(m),
                tint, TEAM_RING_MILLIS / 1000f, (byte) com.hypixel.hytale.server.core.modules.debug.DebugUtils.FLAG_NO_WIREFRAME,
                data, TEAM_RING_OPACITY));
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
        followLaser(ownerId.getId(), from, to, ColorParseUtil.hexStringToRGBInt(color), buffer);
    }

    /** The laser is redrawn this often with the fighters' current positions, so it follows them. */
    private static final int LASER_STEP_MILLIS = 200;

    /**
     * A laser pointer line is static, so one line drawn at fight start points where the mobs were.
     * Draw short-lived lines between the current positions every LASER_STEP_MILLIS for LASER_MILLIS,
     * reading positions on the world thread (world.execute).
     */
    private static void followLaser(int ownerId, Ref<EntityStore> from, Ref<EntityStore> to, int rgb,
                                    ComponentAccessor<EntityStore> buffer) {
        drawLaser(ownerId, from, to, rgb, buffer);
        var world = buffer.getExternalData().getWorld();
        for (int t = LASER_STEP_MILLIS; t < LASER_MILLIS; t += LASER_STEP_MILLIS) {
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
                if (from.isValid() && to.isValid()) {
                    drawLaser(ownerId, from, to, rgb, world.getEntityStore().getStore());
                }
            }), t, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private static void drawLaser(int ownerId, Ref<EntityStore> from, Ref<EntityStore> to, int rgb,
                                  ComponentAccessor<EntityStore> buffer) {
        TransformComponent a = buffer.getComponent(from, TransformComponent.getComponentType());
        TransformComponent b = buffer.getComponent(to, TransformComponent.getComponentType());
        if (a == null || b == null) {
            return;
        }
        var start = a.getPosition();
        var end = b.getPosition();
        PlayerUtil.broadcastPacketToPlayers(buffer, new BuilderToolLaserPointer(ownerId,
                (float) start.x(), (float) (start.y() + LASER_HEIGHT), (float) start.z(),
                (float) end.x(), (float) (end.y() + LASER_HEIGHT), (float) end.z(),
                rgb, LASER_STEP_MILLIS + 50));
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

    /** Forgets finished title deadlines (BattleManager.maintain); a missing entry means "free". */
    static void pruneTitles() {
        long now = System.currentTimeMillis();
        TITLE_BUSY_UNTIL.values().removeIf(until -> until < now);
    }

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
        followLaser(ownerId.getId(), from, to, ColorParseUtil.hexStringToRGBInt(FxSettings.pickLaserColor()), buffer);
    }
}
