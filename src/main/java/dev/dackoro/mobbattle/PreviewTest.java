package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.EditorSelection;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.EntityScaleComponent;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * /mobbattle test preview [two] [spin N] [tilt N] [scale N] (dev spike, 2026-10-16, user: a Smash-like VS screen with
 * the mobs' real models, since modded mobs often have no icon). Opens PreviewTest.ui, whose vanilla
 * PrefabPreviewComponent (the prefab editor's browser preview) shows what a BuilderToolPrefabPreview packet holds; its
 * entityChanges (ClipboardEntityChange: model, position, body/look orientation, scale) are filled like vanilla
 * BlockSelection.toClipboardEntityChange, from the mob you look at ("two": also the nearest other one, side by side
 * and facing each other). The editor sends tilt 23, spin 27, scale 100 (javap). Closing the page clears the preview.
 * ❓ Untested: whether models show outside the prefab editor, posed or animated, and for non-builders.
 */
final class PreviewTest {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    /** "two": the second mob is looked for this far around the first. */
    private static final double PAIR_RANGE = 24;
    /** "two": distance between the two in the preview (blocks, plus their widths). */
    private static final float PAIR_GAP = 1.5f;
    /** The packet is sent again this late, in case the page's component wasn't there for the first one. */
    private static final long RESEND_MS = 300;

    private PreviewTest() {
    }

    static void command(CommandContext ctx, String[] a) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can open the preview.", ERR);
            return;
        }
        boolean two = false;
        int spin = 0, tilt = 10, scale = 100, speed = 100;
        String pose = null;
        boolean keepId = false, allSets = false, box = false;
        float yaw = 180;   // user test: 180 shows a mob's face
        int pad = 0;
        double y = Double.NaN, dx = 0, dz = 0;
        String modelId = null;
        for (int i = 1; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            try {
                switch (w) {
                    case "two" -> two = true;
                    case "spin" -> spin = Integer.parseInt(a[++i]);
                    case "tilt" -> tilt = Integer.parseInt(a[++i]);
                    case "scale" -> scale = Integer.parseInt(a[++i]);
                    case "pose" -> pose = a[++i];
                    case "speed" -> speed = Integer.parseInt(a[++i]);
                    case "keepid" -> keepId = true;
                    case "box" -> box = true;
                    case "yaw" -> yaw = Float.parseFloat(a[++i]);
                    case "pad" -> pad = Integer.parseInt(a[++i]);
                    case "y" -> y = Double.parseDouble(a[++i]);
                    case "x" -> dx = Double.parseDouble(a[++i]);
                    case "z" -> dz = Double.parseDouble(a[++i]);
                    case "model" -> modelId = a[++i];
                    case "allsets" -> allSets = true;
                    default -> {
                        TestTools.say(ctx.sender(), USAGE, ERR);
                        return;
                    }
                }
            } catch (RuntimeException e) {
                TestTools.say(ctx.sender(), USAGE, ERR);
                return;
            }
        }
        Ref<EntityStore> me = ctx.senderAsPlayerRef();
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        boolean pair = two;
        int s = spin, t = tilt, sc = scale;
        Look look = new Look(pose, speed / 100f, keepId, allSets, yaw, Math.max(0, pad), y, modelId, dx, dz, box, "/mobbattle test " + String.join(" ", a));
        store.getExternalData().getWorld().execute(() -> open(ctx, me, store, pair, s, t, sc, look));
    }

    private static final String USAGE =
            "Usage: /mobbattle test preview [two] [spin N] [tilt N] [scale N] [pose <animation set>] [speed <percent>] [keepid] [allsets] [yaw <deg>] [pad N] [x N] [y N] [z N] [model <asset id>] [box]";

    /**
     * What the preview plays (2026-10-16, user: the preview animates; "can we put them in a pose?"): the preview loops
     * the model's Idle set, so {@code pose} swaps the Idle of a COPY of the network model for that set (Roar, Taunt,
     * an attack...), looping; {@code speed} scales it (a small value nearly freezes it on its first frames).
     */
    private record Look(String pose, float speed, boolean keepId, boolean allSets, float yaw, int pad, double y,
                        String modelId, double dx, double dz, boolean box, String line) {
    }

    private static void open(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, boolean two,
                             int spin, int tilt, int scale, Look look) {
        if (!me.isValid()) return;
        Ref<EntityStore> first = ApplyCommand.lookedAt(me, store);
        if (first == null) {
            TestTools.say(ctx.sender(), "Look at a mob first (up to " + (int) ApplyCommand.LOOK_RANGE + " blocks).", ERR);
            return;
        }
        List<Ref<EntityStore>> shown = new ArrayList<>();
        shown.add(first);
        if (two) {
            Ref<EntityStore> second = nearestOther(first, me, store);
            if (second == null) {
                TestTools.say(ctx.sender(), "No second mob within " + (int) PAIR_RANGE + " blocks of the first.", ERR);
                return;
            }
            shown.add(second);
        }
        BuilderToolPrefabPreview packet = packet(shown, store, spin, tilt, scale, look);
        if (packet == null) {
            TestTools.say(ctx.sender(), "That mob has no model to show.", ERR);
            return;
        }
        PlayerRef player = store.getComponent(me, PlayerRef.getComponentType());
        Player p = store.getComponent(me, Player.getComponentType());
        if (player == null || p == null) return;
        p.getPageManager().openCustomPage(me, store, new Page(player));
        player.getPacketHandler().write(packet);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (player.isValid()) player.getPacketHandler().write(packet);
        }, RESEND_MS, TimeUnit.MILLISECONDS);
        StringBuilder names = new StringBuilder();
        for (Ref<EntityStore> r : shown) {
            if (names.length() > 0) names.append(" + ");
            names.append(nameOf(r, store));
        }
        TestTools.say(ctx.sender(), "Preview: " + names + " (spin " + spin + ", tilt " + tilt + ", scale " + scale
                + (look.pose() != null ? ", pose " + look.pose() : "") + ", speed " + Math.round(look.speed() * 100)
                + "%). Tell whether the model shows, and if it moves.", OK);
        String info = LAST_INFO.get();
        if (info != null) TestTools.say(ctx.sender(), "Frame: " + info, OK);
        for (Ref<EntityStore> r : shown) {
            ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
            var sets = mc != null && mc.getModel() != null ? mc.getModel().toPacket().animationSets : null;
            if (sets == null) continue;
            boolean has = look.pose() == null || sets.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(look.pose()));
            // Unknown pose (2026-10-16: "pose Roar" on a Baron, which has no Roar, kept its Idle and looked broken).
            if (!has) TestTools.say(ctx.sender(), nameOf(r, store) + " has no \"" + look.pose() + "\" pose: it plays Idle.", ERR);
            // The pose list only when a pose was asked (user: it filled the chat and poses don't work in this view).
            if (look.pose() != null) TestTools.say(ctx.sender(), nameOf(r, store) + " poses: "
                    + String.join(", ", new java.util.TreeSet<>(sets.keySet())), OK);
        }
        // Last line: the command as typed, to compare screenshots (user, 2026-10-16).
        TestTools.say(ctx.sender(), "Command: " + look.line(), "#ffd23e");
        MobBattlePlugin.log("[test] preview of " + names + " for " + player.getUsername());
    }

    /** The preview packet: no blocks, one entity per mob, side by side along X and facing each other. */
    private static BuilderToolPrefabPreview packet(List<Ref<EntityStore>> mobs, Store<EntityStore> store,
                                                   int spin, int tilt, int scale, Look look) {
        List<ClipboardEntityChange> changes = new ArrayList<>();
        double[][] sizes = new double[mobs.size()][];
        double width = 0;
        for (int i = 0; i < mobs.size(); i++) {
            sizes[i] = visualOf(mobs.get(i), store, yawOf(look, mobs.size(), i));
            width += sizes[i][1] * 2;
        }
        width += PAIR_GAP * (mobs.size() - 1);
        // Centered on the middle of block (0, 0): the bounds below are whole blocks around it.
        double x = 0.5 - width / 2;
        double top = 1, deepest = 0.5;
        for (int i = 0; i < mobs.size(); i++) {
            Ref<EntityStore> r = mobs.get(i);
            double half = sizes[i][1];
            ClipboardEntityChange c = change(r, store);
            if (c == null) return null;
            c.model = posed(c.model, look);
            if (look.modelId() != null) {
                // A model asset instead of the mob's own (pose test: a pack model whose Parent is the mob's and whose
                // Idle is an attack, e.g. MobBattle_PoseTest_Gobliterator).
                com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset asset =
                        com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset.getAssetMap().getAsset(look.modelId());
                if (asset != null) {
                    c.model = com.hypixel.hytale.server.core.asset.type.model.config.Model.createScaledModel(asset, 1f).toPacket();
                }
            }
            // Placed by what the model really draws (visualOf): its drawn box centered on its slot, its lowest point on
            // the floor. "x N" / "y N" / "z N" still shift it for tests.
            c.x = (float) (x + half - sizes[i][3] + look.dx());
            c.y = (float) (-sizes[i][5] + (Double.isNaN(look.y()) ? 0 : look.y()));
            c.z = (float) (0.5 - sizes[i][4] + look.dz());
            deepest = Math.max(deepest, sizes[i][2]);
            // One mob: a fixed facing (yaw <deg>, 180 = toward the camera, user test); two: facing each other.
            float yaw = (float) yawOf(look, mobs.size(), i);
            c.bodyOrientation = new Direction(yaw, 0f, 0f);
            c.lookOrientation = new Direction(yaw, 0f, 0f);
            changes.add(c);
            x += half * 2 + PAIR_GAP;
            top = Math.max(top, sizes[i][0]);
        }
        BuilderToolPrefabPreview p = new BuilderToolPrefabPreview();
        p.tilt = tilt;
        p.spinSpeed = spin;
        p.previewScale = scale;
        p.blocksOmitted = false;
        p.blocksChange = new BlockChange[0];
        p.fluidsChange = new FluidChange[0];
        p.entityChanges = changes.toArray(new ClipboardEntityChange[0]);
        // Tight whole-block bounds around the mobs (2026-10-16, user: "it shows so zoomed out, it should fit the window";
        // the preview frames its bounds, and the old fixed 3-block-wide box made a 2-block skeleton small). Inclusive
        // block coordinates, ❓ as the prefab editor's; "pad N" adds N blocks on every side for the test.
        EditorSelection bounds = new EditorSelection();
        bounds.minX = (int) Math.floor(0.5 - width / 2) - look.pad();
        bounds.maxX = (int) Math.ceil(0.5 + width / 2) - 1 + look.pad();
        bounds.minZ = (int) Math.floor(0.5 - deepest) - look.pad();
        bounds.maxZ = (int) Math.ceil(0.5 + deepest) - 1 + look.pad();
        bounds.minY = 0;
        bounds.maxY = Math.max(0, (int) Math.ceil(top) - 1) + look.pad();
        p.bounds = bounds;
        // The viewer frames its BLOCKS, not the bounds (user test, 2026-10-16: with the stone box the mob was centered,
        // without it the view sat high and at another size): invisible Barrier blocks (DrawType Empty) at the 8
        // corners make it frame the mob's box; "box" shows the edges in stone instead.
        p.blocksChange = look.box() ? outline(bounds, BOX_BLOCK, false) : outline(bounds, FRAME_BLOCK, true);
        LAST_INFO.set(String.format(java.util.Locale.ROOT,
                "drawn %.2f tall x %.2f wide x %.2f deep (blocks); bounds x %d..%d y %d..%d z %d..%d; mob at %.2f %.2f %.2f",
                top, width, deepest * 2, bounds.minX, bounds.maxX, bounds.minY, bounds.maxY, bounds.minZ, bounds.maxZ,
                changes.get(0).x, changes.get(0).y, changes.get(0).z));
        return p;
    }

    /** The last packet()'s sizes, for the chat (world thread). */
    private static final ThreadLocal<String> LAST_INFO = new ThreadLocal<>();

    /**
     * "box": the 12 edges of the bounds as stone blocks (2026-10-16, user: show the framed box to see whether the
     * preview or the bounds are off). Block coordinates are inclusive like the bounds.
     */
    private static BlockChange[] outline(EditorSelection b, String block, boolean cornersOnly) {
        int id = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap().getIndex(block);
        if (id < 0) return new BlockChange[0];
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<BlockChange> out = new ArrayList<>();
        for (int x = b.minX; x <= b.maxX; x++) {
            for (int y = b.minY; y <= b.maxY; y++) {
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    int edges = (x == b.minX || x == b.maxX ? 1 : 0) + (y == b.minY || y == b.maxY ? 1 : 0)
                            + (z == b.minZ || z == b.maxZ ? 1 : 0);
                    if (edges >= (cornersOnly ? 3 : 2) && seen.add(x + "," + y + "," + z)) {
                        out.add(new BlockChange(x, y, z, id, (byte) 0));
                    }
                }
            }
        }
        return out.toArray(new BlockChange[0]);
    }

    private static final String BOX_BLOCK = "Rock_Stone";
    /** Solid but never drawn (DrawType Empty): gives the viewer its frame without showing anything. */
    private static final String FRAME_BLOCK = "Barrier";

    /** As vanilla BlockSelection.toClipboardEntityChange, from a live entity: model, body and head rotation, scale. */
    private static ClipboardEntityChange change(Ref<EntityStore> r, Store<EntityStore> store) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        if (mc == null || mc.getModel() == null) return null;
        ClipboardEntityChange c = new ClipboardEntityChange();
        c.model = mc.getModel().toPacket();
        TransformComponent t = store.getComponent(r, TransformComponent.getComponentType());
        if (t != null) {
            var rot = t.getRotation();
            c.bodyOrientation = new Direction(rot.yaw(), rot.pitch(), rot.roll());
        }
        HeadRotation head = store.getComponent(r, HeadRotation.getComponentType());
        if (head != null) {
            var rot = head.getRotation();
            c.lookOrientation = new Direction(rot.yaw(), rot.pitch(), rot.roll());
        }
        EntityScaleComponent es = store.getComponent(r, EntityScaleComponent.getComponentType());
        c.scale = es != null ? es.getScale() : 0f;   // 0 = the model's own scale, as vanilla
        return c;
    }

    /**
     * A copy of {@code model} whose Idle is {@code look.pose()}'s set (case ignored; unknown = Idle kept), looping, at
     * {@code look.speed()}. Never changes the model's own packet (it may be cached and shared).
     */
    private static com.hypixel.hytale.protocol.Model posed(com.hypixel.hytale.protocol.Model model, Look look) {
        if (model == null || model.animationSets == null || look.pose() == null && look.speed() == 1f) return model;
        String key = "Idle";
        if (look.pose() != null) {
            for (String k : model.animationSets.keySet()) if (k.equalsIgnoreCase(look.pose())) key = k;
        }
        com.hypixel.hytale.protocol.AnimationSet src = model.animationSets.get(key);
        if (src == null || src.animations == null) return model;
        com.hypixel.hytale.protocol.AnimationSet set = new com.hypixel.hytale.protocol.AnimationSet(src);
        set.animations = new com.hypixel.hytale.protocol.Animation[src.animations.length];
        for (int i = 0; i < src.animations.length; i++) {
            com.hypixel.hytale.protocol.Animation an = new com.hypixel.hytale.protocol.Animation(src.animations[i]);
            an.looping = true;
            an.speed = (an.speed > 0 ? an.speed : 1f) * look.speed();
            set.animations[i] = an;
        }
        com.hypixel.hytale.protocol.Model copy = new com.hypixel.hytale.protocol.Model(model);
        copy.animationSets = new java.util.HashMap<>(model.animationSets);
        copy.animationSets.put("Idle", set);
        // Only the Idle swap kept the Idle (user test: Run, Sleep): the client most likely takes the animations of a
        // model it knows by assetId from its own copy. "keepid" sends the real id (texture safe); by default the id is
        // made unique so the packet's sets must be used; "allsets" also puts the pose under every set name.
        if (!look.keepId() && copy.assetId != null) copy.assetId = copy.assetId + "_MobBattlePose_" + key;
        if (look.allSets()) for (String k : model.animationSets.keySet()) copy.animationSets.put(k, set);
        return copy;
    }

    /** The body yaw (radians) of mob {@code i} of {@code n}: the test's yaw alone, else the left one toward +X, the right one toward -X. */
    private static double yawOf(Look look, int n, int i) {
        if (n == 1) return Math.toRadians(look.yaw());
        return i == 0 ? Math.atan2(-1, 0) : Math.atan2(1, 0);
    }

    /** Model blockymodel units per block (Characters/Player.blockymodel draws 121 units, ~1.9 blocks). */
    private static final double UNITS_PER_BLOCK = 64;
    /** Drawn bounds per model file set (unscaled units), so a second preview of a model reads nothing again. */
    private static final java.util.Map<String, double[]> DRAWN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * What the mob really draws, turned by {@code yaw}, in blocks: {height, half width along X, half depth along Z,
     * center X offset, center Z offset, lowest Y} from the entity's origin (2026-10-16, user: the preview drew a
     * Gobliterator up and to the left; it was framed by its 4.25 x 2.4 hitbox while it draws 5.3 x 4.4 blocks). Its
     * model pieces measured by ModelThumbnail.bounds, times the model's (or entity's) scale; the hitbox when it can't.
     */
    private static double[] visualOf(Ref<EntityStore> r, Store<EntityStore> store, double yaw) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        var model = mc != null ? mc.getModel() : null;
        double[] b = null;
        if (model != null) {
            List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
            StringBuilder key = new StringBuilder();
            for (ThumbnailCache.Piece p : pieces) key.append(p.model()).append('|');
            b = DRAWN.computeIfAbsent(key.toString(), k -> {
                List<ModelThumbnail.Part> parts = new ArrayList<>();
                for (ThumbnailCache.Piece p : pieces) {
                    try {
                        parts.add(new ModelThumbnail.Part(new String(ThumbnailCache.load(p.model()),
                                java.nio.charset.StandardCharsets.UTF_8), null));
                    } catch (Exception e) {
                        if (parts.isEmpty()) return new double[0];
                    }
                }
                double[] d = parts.isEmpty() ? null : ModelThumbnail.bounds(parts);
                return d != null ? d : new double[0];
            });
        }
        if (b == null || b.length < 6) {
            double[] hit = sizeOf(r, store);
            return new double[] {hit[0], hit[1], hit[1], 0, 0, 0};
        }
        EntityScaleComponent es = store.getComponent(r, EntityScaleComponent.getComponentType());
        double scale = es != null && es.getScale() > 0 ? es.getScale() : model.getScale() > 0 ? model.getScale() : 1;
        double k = scale / UNITS_PER_BLOCK;
        // The four ground corners turned by the yaw (about +Y): their box along X and Z.
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double cx : new double[] {b[0], b[3]}) {
            for (double cz : new double[] {b[2], b[5]}) {
                double rx = cx * cos + cz * sin, rz = -cx * sin + cz * cos;
                minX = Math.min(minX, rx); maxX = Math.max(maxX, rx);
                minZ = Math.min(minZ, rz); maxZ = Math.max(maxZ, rz);
            }
        }
        return new double[] {(b[4] - b[1]) * k, (maxX - minX) / 2 * k, (maxZ - minZ) / 2 * k,
                (maxX + minX) / 2 * k, (maxZ + minZ) / 2 * k, b[1] * k};
    }

    /** {height, half width} of the hitbox; a normal mob's when unknown. */
    private static double[] sizeOf(Ref<EntityStore> r, Store<EntityStore> store) {
        BoundingBox bb = store.getComponent(r, BoundingBox.getComponentType());
        if (bb == null || bb.getBoundingBox() == null) return new double[] {2, 0.5};
        var box = bb.getBoundingBox();
        return new double[] {box.max.y - box.min.y, Math.max(box.max.x - box.min.x, box.max.z - box.min.z) / 2};
    }

    private static Ref<EntityStore> nearestOther(Ref<EntityStore> first, Ref<EntityStore> me, Store<EntityStore> store) {
        Vector3d at = ChaosWand.positionOf(first, store);
        if (at == null) return null;
        Ref<EntityStore> best = null;
        double bestD = Double.MAX_VALUE;
        for (Ref<EntityStore> r : new ArrayList<>(com.hypixel.hytale.server.core.util.TargetUtil.getAllEntitiesInSphere(at, PAIR_RANGE, store))) {
            if (r.equals(first) || r.equals(me) || store.getComponent(r, NPCEntity.getComponentType()) == null) continue;
            if (!BattleManager.isAlive(r, store)) continue;
            Vector3d q = ChaosWand.positionOf(r, store);
            if (q == null) continue;
            double d = q.distanceSquared(at);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    private static String nameOf(Ref<EntityStore> r, Store<EntityStore> store) {
        NPCEntity npc = store.getComponent(r, NPCEntity.getComponentType());
        if (npc != null) return npc.getRoleName();
        PlayerRef pr = store.getComponent(r, PlayerRef.getComponentType());
        return pr != null ? pr.getUsername() : "?";
    }

    /** The page: only the preview; closing it clears the preview, as the prefab browser does. */
    private static final class Page extends BasicCustomUIPage {
        Page(PlayerRef player) {
            super(player, CustomPageLifetime.CanDismiss);
        }

        @Override
        public void build(UICommandBuilder ui) {
            ui.append("MobBattle/PreviewTest.ui");
        }

        @Override
        public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
            super.onDismiss(ref, store);
            BuilderToolPrefabPreview empty = new BuilderToolPrefabPreview();
            empty.blocksChange = new BlockChange[0];
            empty.fluidsChange = new FluidChange[0];
            empty.entityChanges = new ClipboardEntityChange[0];
            if (playerRef.isValid()) playerRef.getPacketHandler().write(empty);
        }
    }
}
