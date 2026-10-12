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
        if (KindFx.startTitle(BattleManager.Kind.DUEL)) {
            showTitle(player, "FIGHT!", a + "  vs  " + b);
        }
        if (KindFx.laser(BattleManager.Kind.DUEL)) {
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
                            org.joml.Vector3d center, double radius, String style, String colorName, String glowColor) {
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
        area(owner, center, radius, RING_MILLIS / 1000f, buffer);
    }

    /** The area of a {@code kind} battle, in that kind's areaStyle (KindFx). */
    public static void area(BattleManager.Kind kind, Ref<EntityStore> owner, org.joml.Vector3d center, double radius,
                            ComponentAccessor<EntityStore> buffer) {
        String name = FxSettings.pickAreaColorName();
        area(owner, center, radius, RING_MILLIS / 1000f, KindFx.areaStyle(kind), name, name, buffer);
    }

    /** The area of a wand action (ActionFx glow: strips or adds the glow, maybe in its own color). */
    static void area(ActionFx.Action action, BattleManager.Kind kind, Ref<EntityStore> owner, org.joml.Vector3d center,
                     double radius, float seconds, ComponentAccessor<EntityStore> buffer) {
        // The ground in the area color (areaStyle, areaColor); the border glow in the action's borderColor.
        String style = kind != null ? KindFx.areaStyle(kind) : FxSettings.areaStyle;
        String name = FxSettings.pickAreaColorName();
        area(owner, center, radius, seconds, style, name, ActionFx.glowColor(action, name), buffer);
    }

    static float areaSeconds() {
        return RING_MILLIS / 1000f;
    }

    /** Same, shown for {@code seconds} (the summon area: 3 s). The long glow system is cut at that time. */
    public static void area(Ref<EntityStore> owner, org.joml.Vector3d center, double radius, float seconds,
                            ComponentAccessor<EntityStore> buffer) {
        // Previews (/mobbattle areaRadius, areaStyle...): the areaStyle, ground and border in the area color.
        String name = FxSettings.pickAreaColorName();
        area(owner, center, radius, seconds, FxSettings.areaStyle, name, name, buffer);
    }

    private static void area(Ref<EntityStore> owner, org.joml.Vector3d center, double radius, float seconds,
                             String style, String name, String glowColor, ComponentAccessor<EntityStore> buffer) {
        PlayerRef ownerPlayer = owner != null && owner.isValid() ? buffer.getComponent(owner, PlayerRef.getComponentType()) : null;
        if (ownerPlayer != null) LAST_AREA_COLOR.put(ownerPlayer.getUuid(), name);
        // areaStyle -> the ground drawArea takes (floor/zone/off) and the border glow, if the style has one.
        if (!FxSettings.hasBorder(style)) glowColor = null;
        style = FxSettings.groundOf(style);
        if ("off".equals(style) && glowColor == null) return;
        if (FxSettings.areaOnlyWand) {
            PlayerRef player = owner != null && owner.isValid()
                    ? buffer.getComponent(owner, PlayerRef.getComponentType()) : null;
            if (player == null) return;
            HeldArea held = new HeldArea(player, owner.getStore(), new org.joml.Vector3d(center), radius, style, name, glowColor);
            HELD_AREAS.put(player.getUuid(), held);
            if (holdsItem(player, CHAOS_WAND_ID, buffer)) {
                drawArea(held.center(), radius, style, name, glowColor, HELD_AREA_SECONDS, player, buffer);
            }
            return;
        }
        // A new area replaces this owner's previous glow (and any glow in its own spot): repeated summons
        // would otherwise pile up columns of light for 6 s each.
        PlayerRef who = owner != null && owner.isValid() ? buffer.getComponent(owner, PlayerRef.getComponentType()) : null;
        if (who != null) {
            double[] prev = LAST_AREA.put(who.getUuid(),
                    new double[]{center.x(), center.y(), center.z(), radius});
            if (prev != null) cancelGlow(prev[0], prev[1], prev[2], prev[3], buffer);
        }
        cancelGlow(center.x(), center.y(), center.z(), radius, buffer);
        // A disc can't be cancelled on its own (DebugUtils shapes have no id): a new one clears every shape of the
        // world first, so summons don't pile discs up. Arenas and rings redraw themselves within a second.
        if (!"off".equals(style)) com.hypixel.hytale.server.core.modules.debug.DebugUtils.clear(buffer.getExternalData().getWorld());
        drawArea(center, radius, style, name, glowColor, seconds, null, buffer);
    }

    /** Per owner: center and radius of the last area drawn (for cancelling its glow). */
    private static final java.util.Map<java.util.UUID, double[]> LAST_AREA = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Stops every glow column of the area system inside the circle's box, right away, for everyone in the world
     * (CancelParticleSystems with a region, as the game does it). The floor / zone shapes can't be cancelled.
     */
    private static void cancelGlow(double x, double y, double z, double radius, ComponentAccessor<EntityStore> buffer) {
        PlayerUtil.broadcastPacketToPlayers(buffer, glowCancel(x, y, z, radius));
    }

    private static com.hypixel.hytale.protocol.packets.world.CancelParticleSystems glowCancel(double x, double y, double z,
                                                                                             double radius) {
        double r = radius + 3;
        return new com.hypixel.hytale.protocol.packets.world.CancelParticleSystems(
                new com.hypixel.hytale.protocol.Position(x - r, y - 64, z - r),
                new com.hypixel.hytale.protocol.Position(x + r, y + 160, z + r), GLOW_IDS, true);
    }

    /**
     * Settings panel preview (ConfigPage): an area of {@code radius} in a style and color maybe not saved yet, drawn
     * only for {@code viewer} (it ignores areaOnlyWand: the panel is open, no wand in hand). Replaces their previous
     * preview, {@code previous} = {x, y, z, radius} or null: its glow is cancelled and their shapes cleared (arenas
     * and team rings redraw themselves), so typing a radius doesn't pile circles up. Returns this one's {x, y, z, r}.
     */
    static double[] previewArea(PlayerRef viewer, org.joml.Vector3d center, double radius, String style, String colorName,
                                double[] previous, ComponentAccessor<EntityStore> buffer) {
        var out = viewer.getPacketHandler();
        if (previous != null) out.write(glowCancel(previous[0], previous[1], previous[2], previous[3]));
        out.write(new com.hypixel.hytale.protocol.packets.player.ClearDebugShapes());
        drawArea(center, radius, FxSettings.groundOf(style), colorName, FxSettings.hasBorder(style) ? colorName : null,
                RING_MILLIS / 1000f, viewer, buffer);
        return new double[]{center.x(), center.y(), center.z(), radius};
    }

    /** Once per second per world (BattleLifecycleSystem): redraw held areas whose owner holds the Chaos Wand. */
    static void refreshHeldAreas(com.hypixel.hytale.component.Store<EntityStore> store) {
        for (HeldArea h : HELD_AREAS.values()) {
            if (h.store() == store && holdsItem(h.player(), CHAOS_WAND_ID, store)) {
                drawArea(h.center(), h.radius(), h.style(), h.colorName(), h.glowColor(), HELD_AREA_SECONDS, h.player(), store);
            }
        }
    }

    /**
     * /mobbattle stop: no more held-area redraws, and the shapes already drawn (areas, zones, rings) are erased
     * for everyone in {@code world} (DebugUtils.clear: ClearDebugShapes). Team rings of drafts come back by
     * themselves on their next redraw.
     */
    static void clearAreas(com.hypixel.hytale.server.core.universe.world.World world) {
        HELD_AREAS.clear();
        if (world != null) com.hypixel.hytale.server.core.modules.debug.DebugUtils.clear(world);
    }

    /**
     * BattleManager.maintain: a held area keeps its world's Store reachable. Drops it when the player left,
     * its world was unloaded, or areaonlywand was turned off (then refreshHeldAreas no longer runs).
     */
    static void pruneHeldAreas() {
        if (HELD_AREAS.isEmpty()) return;
        HELD_AREAS.values().removeIf(h -> !FxSettings.areaOnlyWand || !h.player().isValid() || h.store().isShutdown());
    }

    /** Per player: the color of their last Chaos Wand area, so its arena keeps it (areaColor random). */
    private static final java.util.Map<java.util.UUID, String> LAST_AREA_COLOR = new java.util.concurrent.ConcurrentHashMap<>();

    static String lastAreaColor(PlayerRef owner) {
        return owner != null ? LAST_AREA_COLOR.get(owner.getUuid()) : null;
    }

    /**
     * Draws the area for {@code seconds}, to everyone in the world, or only to {@code only} when not null:
     * {@code style} floor/zone in {@code colorName}, and the border glow in {@code glowColor} (null = none).
     */
    static void drawArea(org.joml.Vector3d center, double radius, String style, String colorName, String glowColor,
                         float seconds, PlayerRef only, ComponentAccessor<EntityStore> buffer) {
        drawArea(center, radius, style, colorName, glowColor, seconds, only, 0, buffer);
    }

    /** Glow columns for a circle of {@code radius}: about every 2 blocks of the edge, between 12 and GLOW_MAX_POINTS. */
    static int glowPoints(double radius) {
        return Math.max(12, Math.min(GLOW_MAX_POINTS, (int) Math.ceil(Math.PI * radius)));
    }

    /**
     * Same, with a fixed number of glow columns ({@code glowPoints}, 0 = by radius). A closing arena passes the count
     * of its starting radius, so every redraw puts each column on the same angle and it only slides inward, instead
     * of the whole ring reshuffling (and every column "growing out of the ground" again) as the radius shrinks.
     */
    static void drawArea(org.joml.Vector3d center, double radius, String style, String colorName, String glowColor,
                         float seconds, PlayerRef only, int glowPoints, ComponentAccessor<EntityStore> buffer) {
        java.util.List<com.hypixel.hytale.protocol.ToClientPacket> packets = new java.util.ArrayList<>();
        var top = groundOf(buffer);
        var below = floorBelow(center.y(), buffer);
        // The heightmap is the highest block: under a roof (caves, the goblin instance) that is the ceiling.
        // So first the nearest solid block under the center's feet; if none is loaded within reach, the
        // heightmap, unless it is far above the center (a roof): then the center height.
        double cy = center.y();
        java.util.function.DoubleBinaryOperator ground = (x, z) -> {
            double y = below.applyAsDouble(x, z);
            if (Double.isFinite(y)) return y;
            y = top.applyAsDouble(x, z);
            return Double.isFinite(y) && y > cy + ROOF_MARGIN ? Double.NaN : y;
        };
        if ("zone".equals(style) || "floor".equals(style)) {
            zone(center, radius, Teams.PALETTE.get(colorName), "zone".equals(style), seconds, ground, packets);
        }
        if (glowColor != null && Teams.PALETTE.containsKey(glowColor)) {
            glow(center, radius, glowColor, seconds, glowPoints, ground, packets);
        }
        if (packets.isEmpty()) return;
        if (only != null) {
            packets.forEach(only.getPacketHandler()::write);
        } else {
            PlayerUtil.broadcastPacketToPlayers(buffer, packets.toArray(new com.hypixel.hytale.protocol.ToClientPacket[0]));
        }
    }

    /** Big summoning rune at a Chaos Wand area's center (Server/Particles/MobBattle). */
    public static final String RUNE_CENTER_ID = "MobBattle_Rune_Center";

    /**
     * A summoning rune on the ground at {@code center} for the countdown (at least 2 s), to everyone in the
     * world. The fighters get their own small runes from their Rage / Lone / Brawl effects.
     */
    static void summonRune(BattleManager.Kind kind, org.joml.Vector3d center, ComponentAccessor<EntityStore> buffer) {
        if (!FxSettings.wandFx || !KindFx.runes(kind) || center == null) return;
        double y = floorBelow(center.y(), buffer).applyAsDouble(center.x(), center.z());
        y = Double.isFinite(y) ? y - 0.25 : center.y();
        float seconds = Math.max(2, KindFx.countdown(kind));
        PlayerUtil.broadcastPacketToPlayers(buffer, new com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem(
                RUNE_CENTER_ID, new com.hypixel.hytale.protocol.Position(center.x(), y, center.z()), null, 1f, null, seconds));
        WandFx.burst(null, WandFx.CHAOS_SOUND, new org.joml.Vector3d(center.x(), y, center.z()), buffer);
    }

    private static final long WAVE_STEP_MS = 50;
    private static final float WAVE_RING = 1.5f;
    /** Radius up to which the wave lasts {@link #WAVE_MIN_MS}; beyond it, {@link #WAVE_MS_PER_BLOCK} more per block. */
    private static final double WAVE_BASE_RADIUS = 30;
    private static final long WAVE_MIN_MS = 700;
    private static final long WAVE_MAX_MS = 2200;
    private static final long WAVE_MS_PER_BLOCK = 15;
    /** Ease-in exponent: the ring leaves the center slowly and speeds up toward the edge (1 = constant speed). */
    private static final double WAVE_EASE = 1.7;

    /**
     * A shockwave: a ring on the ground growing from {@code center} out to {@code radius} (one DisplayDebug disc
     * with a hole per step, like the zone floor), then the full area edge for a moment. It leaves the center slowly
     * and accelerates (WAVE_EASE); it lasts 0.7 s up to 30 blocks and longer for bigger radii (up to 2.2 s), so a
     * large reach stays visible. The ring is at least as thick as one step's advance, so it never breaks into
     * flashes. Shows how far the Snap Wand reached. To everyone in the world.
     */
    static void shockwave(org.joml.Vector3d center, double radius, String hex, ComponentAccessor<EntityStore> buffer) {
        if (MobBattlePlugin.debugging()) {
            MobBattlePlugin.debug("shockwave at " + Math.round(center == null ? 0 : center.x()) + ", "
                    + Math.round(center == null ? 0 : center.y()) + ", " + Math.round(center == null ? 0 : center.z())
                    + " radius " + radius + " color " + hex + (FxSettings.wandFx ? "" : " (skipped: wandFx off)"));
        }
        if (!FxSettings.wandFx || center == null || radius <= 0) return;
        double y = floorBelow(center.y(), buffer).applyAsDouble(center.x(), center.z());
        double baseY = (Double.isFinite(y) ? y - 0.25 : center.y()) + 0.15;
        var rgb = ColorParseUtil.hexStringToColor(hex);
        var color = new org.joml.Vector3f((rgb.red & 255) / 255f, (rgb.green & 255) / 255f, (rgb.blue & 255) / 255f);
        byte flags = (byte) com.hypixel.hytale.server.core.modules.debug.DebugUtils.FLAG_NO_WIREFRAME;
        var world = buffer.getExternalData().getWorld();
        float[] matrix = com.hypixel.hytale.math.matrix.Matrix4dUtil.asFloatData(
                new org.joml.Matrix4d().translate(center.x(), baseY, center.z()));
        long duration = Math.max(WAVE_MIN_MS, Math.min(WAVE_MAX_MS,
                WAVE_MIN_MS + Math.round((radius - WAVE_BASE_RADIUS) * WAVE_MS_PER_BLOCK)));
        int steps = (int) Math.max(1, duration / WAVE_STEP_MS);
        // Thin for a small wave, thick for a big one: the base width scales with the radius (30 blocks = 1x).
        double width = WAVE_RING * Math.max(0.3, Math.min(2.5, radius / WAVE_BASE_RADIUS));
        double previous = 0;
        for (int i = 1; i <= steps; i++) {
            boolean last = i == steps;
            double reach = radius * Math.pow((double) i / steps, WAVE_EASE);
            float outer = (float) reach;
            float ring = (float) Math.max(width, Math.min(width * 2, (reach - previous) * 1.3));
            previous = reach;
            float[] disc = new float[16];
            disc[0] = outer;
            disc[1] = ZONE_DISC_SEGMENTS;
            disc[2] = Math.max(0, outer - ring);
            var packet = new com.hypixel.hytale.protocol.packets.player.DisplayDebug(com.hypixel.hytale.protocol.DebugShape.Disc,
                    matrix, color, last ? 0.8f : (WAVE_STEP_MS + 30) / 1000f, flags, disc, last ? 0.45f : 0.6f);
            com.hypixel.hytale.server.core.HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> world.execute(() -> {
                if (!world.getEntityStore().getStore().isShutdown()) {
                    PlayerUtil.broadcastPacketToPlayers(world.getEntityStore().getStore(), packet);
                }
            }), (i - 1) * WAVE_STEP_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** Blocks above the area center from which the top block counts as a roof, not the ground. */
    private static final double ROOF_MARGIN = 6;

    /** How far up (step onto a ledge) and down the nearest floor under the center is searched. */
    private static final int FLOOR_UP = 3;
    private static final int FLOOR_DOWN = 32;

    /**
     * Nearest solid block under {@code fromY} (0.25 above it), or NaN. Reads only loaded chunk sections
     * (no loading); works under roofs, unlike the heightmap.
     */
    static java.util.function.DoubleBinaryOperator floorBelow(double fromY, ComponentAccessor<EntityStore> buffer) {
        var chunks = buffer.getExternalData().getWorld().getChunkStore();
        var store = chunks.getStore();
        var blockTypes = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
        var sectionType = com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType();
        int start = (int) Math.floor(fromY) + FLOOR_UP;
        return (x, z) -> {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection section = null;
            int sectionY = Integer.MIN_VALUE;
            for (int y = start; y >= start - FLOOR_UP - FLOOR_DOWN; y--) {
                if (y < 0) break;
                if ((y >> 5) != sectionY) {
                    sectionY = y >> 5;
                    var ref = chunks.getChunkSectionReference(bx >> 5, sectionY, bz >> 5);
                    section = ref != null && ref.isValid() ? store.getComponent(ref, sectionType) : null;
                }
                if (section == null) continue;
                var type = blockTypes.getAsset(section.get(bx & 31, y & 31, bz & 31));
                if (type != null && type.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid) {
                    return y + 1.25;
                }
            }
            return Double.NaN;
        };
    }

    /**
     * Feet height of the standable spot (a solid block with 2 free blocks above it) in the column at x, z that is
     * closest to {@code preferY}, within {@code range} blocks up or down; NaN if there is none in loaded chunks.
     * Unlike floorBelow it never picks a spot inside a hill or under a low cave roof, and it keeps a mob at its
     * own level (a cave mob stays in the cave, a mountain mob on the mountain).
     */
    static double standableY(double x, double z, double preferY, int range, ComponentAccessor<EntityStore> buffer) {
        var chunks = buffer.getExternalData().getWorld().getChunkStore();
        var store = chunks.getStore();
        var blockTypes = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
        var sectionType = com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection.getComponentType();
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = (int) Math.floor(preferY) + range + 2, bottom = Math.max(0, (int) Math.floor(preferY) - range);
        var fluidType = com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection.getComponentType();
        var fluids = com.hypixel.hytale.server.core.asset.type.fluid.Fluid.getAssetMap();
        // 1 = solid, 0 = free, -1 = unknown (unloaded) or a hurting fluid (lava), from top to bottom.
        int[] cell = new int[top - bottom + 1];
        com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection section = null;
        com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection fluidSection = null;
        int sectionY = Integer.MIN_VALUE;
        for (int y = top; y >= bottom; y--) {
            if ((y >> 5) != sectionY) {
                sectionY = y >> 5;
                var ref = chunks.getChunkSectionReference(bx >> 5, sectionY, bz >> 5);
                boolean ok = ref != null && ref.isValid();
                section = ok ? store.getComponent(ref, sectionType) : null;
                fluidSection = ok ? store.getComponent(ref, fluidType) : null;
            }
            if (section == null) {
                cell[y - bottom] = -1;
                continue;
            }
            var type = blockTypes.getAsset(section.get(bx & 31, y & 31, bz & 31));
            if (type != null && type.getMaterial() == com.hypixel.hytale.protocol.BlockMaterial.Solid) {
                cell[y - bottom] = 1;
                continue;
            }
            int fluidId = fluidSection != null ? fluidSection.getFluidId(bx & 31, y & 31, bz & 31) : 0;
            var fluid = fluidId != 0 ? fluids.getAsset(fluidId) : null;
            cell[y - bottom] = fluid != null && hurts(fluid) ? -1 : 0;
        }
        double best = Double.NaN, bestDist = Double.MAX_VALUE;
        for (int y = bottom; y + 2 <= top; y++) {
            int i = y - bottom;
            if (cell[i] == 1 && cell[i + 1] == 0 && cell[i + 2] == 0) {
                double dist = Math.abs(y + 1 - preferY);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = y + 1;
                }
            }
        }
        return best;
    }

    /**
     * A fluid that harms whoever is in it. Lava and fire burn through a collision interaction (Lava_Source.json:
     * "Interactions": {"Collision": ... "Lava_Burn"}, inherited by Lava), not DamageToEntities; poison has neither,
     * so it goes by name. Water, slime, tar and mud are fine to stand in.
     */
    private static boolean hurts(com.hypixel.hytale.server.core.asset.type.fluid.Fluid fluid) {
        var interactions = fluid.getInteractions();
        return fluid.getDamageToEntities() > 0 || interactions != null && !interactions.isEmpty()
                || fluid.getId() != null && fluid.getId().contains("Poison");
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
    /** Long and short glow ids of every palette color (cancelGlow). Teams.PALETTE is constant, so built once. */
    private static final String[] GLOW_IDS = Teams.PALETTE.keySet().stream()
            .flatMap(c -> java.util.stream.Stream.of(AREA_GLOW_ID + c, AREA_GLOW_SHORT_ID + c))
            .toArray(String[]::new);

    /** "glow" area: tall columns of rising light along the edge, one SpawnParticleSystem packet each. */
    private static void glow(org.joml.Vector3d c, double radius, String colorName, float seconds, int fixedPoints,
                             java.util.function.DoubleBinaryOperator ground,
                             java.util.List<com.hypixel.hytale.protocol.ToClientPacket> out) {
        var tint = ColorParseUtil.hexStringToColor(Teams.PALETTE.get(colorName));
        String glowId = (seconds <= HELD_AREA_SECONDS ? AREA_GLOW_SHORT_ID : AREA_GLOW_ID) + colorName;
        int points = fixedPoints > 0 ? fixedPoints : glowPoints(radius);
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
    /**
     * Team rings are redrawn this often and last one tick (~33 ms) longer, so they look continuous with
     * almost no overlap (two rings at 0.6 opacity look darker). A drawn ring cannot be moved or replaced
     * (DisplayDebug has no id), so a shorter refresh is also how long a ring lags behind a moving mob.
     */
    public static final long TEAM_RING_REFRESH_MS = 250;
    private static final int TEAM_RING_MILLIS = 283;

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
    /** Parsed ring colors by hex string (teamRing runs 4 times a second per mob). Read-only once parsed. */
    private static final java.util.Map<String, com.hypixel.hytale.protocol.Color> RING_TINT =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        var rgb = RING_TINT.computeIfAbsent(color, ColorParseUtil::hexStringToColor);
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
    public static void link(BattleManager.Kind kind, Ref<EntityStore> owner, Ref<EntityStore> from, Ref<EntityStore> to,
                            String color, ComponentAccessor<EntityStore> buffer) {
        if (!KindFx.laser(kind)) {
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

    /** Big start title for group fights (startTitle of that kind). */
    public static void title(BattleManager.Kind kind, PlayerRef player, String main, String sub) {
        if (KindFx.startTitle(kind)) {
            for (PlayerRef viewer : titleViewers(player)) showTitle(viewer, main, sub);
        }
    }

    /** Ownerless event title; recipients are resolved from the battle's immutable world/center anchor. */
    static void title(BattleManager.Battle battle, String main, String sub) {
        if (battle != null && KindFx.startTitle(battle.kind)) {
            for (PlayerRef viewer : titleViewers(battle)) showTitle(viewer, main, sub);
        }
    }

    /** Winner titles have their own switch (winTitle of that kind), independent of fight-start titles. */
    public static void winnerTitle(BattleManager.Kind kind, PlayerRef player, String main, String sub) {
        if (KindFx.winTitle(kind)) {
            for (PlayerRef viewer : titleViewers(player)) showTitle(viewer, main, sub, true);
        }
    }

    static void winnerTitle(BattleManager.Battle battle, String main, String sub) {
        if (battle != null && KindFx.winTitle(battle.kind)) {
            for (PlayerRef viewer : titleViewers(battle)) showTitle(viewer, main, sub, true);
        }
    }

    /**
     * /mobbattle titlescope: who sees the titles of {@code owner}'s fights. Each viewer keeps their own title
     * queue, so two players' fights never cancel each other's titles: start titles are merged per viewer,
     * winner titles all play.
     */
    static java.util.List<PlayerRef> titleViewers(PlayerRef owner) {
        java.util.List<PlayerRef> viewers = scopeViewers(owner);
        if (owner == null || !BattleManager.hasPlayerFighters()) {
            return viewers;
        }
        // Players fighting in this owner's battles see its titles, timer and music whatever titleScope says.
        // Matched through the online players, not read from the entity: countdown titles run off the world thread.
        java.util.List<Ref<EntityStore>> fighters = BattleManager.playerFightersOf(owner.getUuid());
        if (fighters.isEmpty()) {
            return viewers;
        }
        java.util.List<PlayerRef> out = new java.util.ArrayList<>(viewers);
        for (PlayerRef p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            if (p.isValid() && fighters.contains(p.getReference()) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static java.util.List<PlayerRef> titleViewers(BattleManager.Battle battle) {
        if (battle == null) return java.util.List.of();
        return battle.owner() != null ? titleViewers(battle.owner()) : scopeViewers(battle.anchor, FxSettings.titleScope);
    }

    /** The owner and, per /mobbattle titleScope, the players near them or in their world. */
    private static java.util.List<PlayerRef> scopeViewers(PlayerRef owner) {
        return scopeViewers(owner, FxSettings.titleScope);
    }

    /** /mobbattle cameraScope: who gets {@code owner}'s fight cameras (owner, nearby or world). */
    static java.util.List<PlayerRef> cameraViewers(PlayerRef owner) {
        return scopeViewers(owner, FxSettings.cameraScope);
    }

    static java.util.List<PlayerRef> cameraViewers(BattleManager.Battle battle) {
        if (battle == null) return java.util.List.of();
        return battle.owner() != null ? cameraViewers(battle.owner()) : scopeViewers(battle.anchor, FxSettings.cameraScope);
    }

    static java.util.List<PlayerRef> eventViewers(BattleManager.Battle.Anchor anchor, String scope) {
        return scopeViewers(anchor, scope);
    }

    private static java.util.List<PlayerRef> scopeViewers(PlayerRef owner, String scope) {
        if ("owner".equals(scope) || owner == null || !owner.isValid()) {
            return owner == null ? java.util.List.of() : java.util.List.of(owner);
        }
        java.util.UUID world = owner.getWorldUuid();
        var center = owner.getTransform() != null ? owner.getTransform().getPosition() : null;
        boolean nearby = "nearby".equals(scope);
        double max = FxSettings.TITLE_NEARBY_BLOCKS * FxSettings.TITLE_NEARBY_BLOCKS;
        java.util.List<PlayerRef> out = new java.util.ArrayList<>();
        out.add(owner);
        for (PlayerRef p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            if (p == owner || !p.isValid() || world == null || !world.equals(p.getWorldUuid())) continue;
            if (nearby) {
                var t = p.getTransform();
                if (center == null || t == null || t.getPosition().distanceSquared(center) > max) continue;
            }
            out.add(p);
        }
        return out;
    }

    /** Ownerless `owner` scope falls back to the 64-block event audience around its launch anchor. */
    private static java.util.List<PlayerRef> scopeViewers(BattleManager.Battle.Anchor anchor, String scope) {
        if (anchor == null || anchor.world() == null || anchor.center() == null) return java.util.List.of();
        boolean nearby = !"world".equals(scope);
        double max = FxSettings.TITLE_NEARBY_BLOCKS * FxSettings.TITLE_NEARBY_BLOCKS;
        java.util.List<PlayerRef> out = new java.util.ArrayList<>();
        for (PlayerRef p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
            if (!p.isValid() || !anchor.world().equals(p.getWorldUuid())) continue;
            if (nearby) {
                var t = p.getTransform();
                if (t == null || t.getPosition() == null || t.getPosition().distanceSquared(anchor.center()) > max) continue;
            }
            out.add(p);
        }
        return out;
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

    /** /mobbattle countdown: "3", "2", "1"... shown at once, about one second each. */
    static void countdownTitle(PlayerRef owner, int left, String label) {
        for (PlayerRef viewer : titleViewers(owner)) countdownTitleTo(viewer, left, label);
    }

    static void countdownTitle(BattleManager.Battle.Anchor anchor, int left, String label) {
        for (PlayerRef viewer : scopeViewers(anchor, FxSettings.titleScope)) countdownTitleTo(viewer, left, label);
    }

    private static synchronized void countdownTitleTo(PlayerRef player, int left, String label) {
        if (!player.isValid()) {
            return;
        }
        // The client queues titles: hide the one on screen (e.g. an earlier FIGHT!) so the number shows now.
        if (TITLE_BUSY_UNTIL.getOrDefault(player.getUuid(), 0L) > System.currentTimeMillis()) {
            EventTitleUtil.hideEventTitleFromPlayer(player, 0f);
        }
        var style = FxSettings.pickTitleStyle();
        EventTitleUtil.showEventTitleToPlayer(player, Message.raw(String.valueOf(left)), Message.raw(label), style, null,
                0.6f, 0.1f, 0.2f);
        TITLE_BUSY_UNTIL.put(player.getUuid(), System.currentTimeMillis() + 950);
    }

    private static void sendTitle(PlayerRef player, PendingTitle title, long now) {
        var style = FxSettings.pickTitleStyle();
        float fadeIn = EventTitleUtil.getDefaultFadeInDuration(style);
        EventTitleUtil.showEventTitleToPlayer(player, Message.raw(title.main()), Message.raw(title.sub()), style, null,
                TITLE_SECONDS, fadeIn, TITLE_FADE_OUT);
        TITLE_BUSY_UNTIL.put(player.getUuid(), now + (long) ((fadeIn + TITLE_SECONDS + TITLE_FADE_OUT) * 1000));
    }

    /** Laser between two entities (public for the Chaos Wand). */
    public static void link(BattleManager.Kind kind, Ref<EntityStore> owner, Ref<EntityStore> from, Ref<EntityStore> to,
                            ComponentAccessor<EntityStore> buffer) {
        if (KindFx.laser(kind)) {
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
