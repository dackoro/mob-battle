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
        String modelId = null, bake = null, frame = FRAME_BLOCK, anim = null;
        int frames = 30, fps = 30;
        int hud = 0;
        boolean compare = false;
        boolean yawSet = false, tiltSet = false;
        int pic = 400;
        int scene = 0;
        Double bakeT = null;
        for (int i = 1; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            try {
                switch (w) {
                    case "two" -> two = true;
                    case "spin" -> spin = Integer.parseInt(a[++i]);
                    case "tilt" -> {
                        tilt = Integer.parseInt(a[++i]);
                        tiltSet = true;
                    }
                    case "pic" -> pic = Integer.parseInt(a[++i]);
                    case "scene" -> {
                        // scene [radius]: every mob around (default 30 blocks) in two facing sides, one 3D scene
                        scene = 30;
                        if (i + 1 < a.length && a[i + 1].matches("[0-9]+")) scene = Integer.parseInt(a[++i]);
                    }
                    case "scale" -> scale = Integer.parseInt(a[++i]);
                    case "pose" -> pose = a[++i];
                    case "speed" -> speed = Integer.parseInt(a[++i]);
                    case "keepid" -> keepId = true;
                    case "box" -> box = true;
                    case "yaw" -> {
                        yaw = Float.parseFloat(a[++i]);
                        yawSet = true;
                    }
                    case "pad" -> pad = Integer.parseInt(a[++i]);
                    case "y" -> y = Double.parseDouble(a[++i]);
                    case "x" -> dx = Double.parseDouble(a[++i]);
                    case "z" -> dz = Double.parseDouble(a[++i]);
                    case "model" -> modelId = a[++i];
                    case "allsets" -> allSets = true;
                    case "bake" -> bake = a[++i];
                    case "at" -> bakeT = Double.parseDouble(a[++i]);
                    case "frame" -> {
                        String v = a[++i];
                        frame = "none".equalsIgnoreCase(v) ? null : v;
                    }
                    case "anim" -> anim = a[++i];
                    case "frames" -> frames = Integer.parseInt(a[++i]);
                    case "fps" -> fps = Integer.parseInt(a[++i]);
                    case "compare" -> {
                        // compare [set]: the VS picture, the 3D still in its pose and the 3D animation, in a HUD
                        compare = true;
                        if (i + 1 < a.length && !a[i + 1].matches("[0-9]+") && !isOption(a[i + 1])) anim = a[++i];
                    }
                    case "hud" -> {
                        // hud [seconds]: in a HUD instead of a page (default 20 s)
                        hud = 20;
                        if (i + 1 < a.length && a[i + 1].matches("[0-9]+")) hud = Integer.parseInt(a[++i]);
                    }
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
        if (compare && !yawSet) yaw = 180 - PHOTO_YAW;
        if (compare && !tiltSet) tilt = PHOTO_TILT;
        int s = spin, t = tilt, sc = scale;
        Look look = new Look(pose, speed / 100f, keepId, allSets, yaw, Math.max(0, pad), y, modelId, dx, dz, box, "/mobbattle test " + String.join(" ", a), bake, bakeT, frame, anim,
                Math.max(2, Math.min(MAX_FRAMES, frames)), Math.max(1, Math.min(MAX_FPS, fps)), Math.max(0, Math.min(600, hud)), compare, Math.max(64, Math.min(1000, pic)), Math.max(0, Math.min(200, scene)));
        store.getExternalData().getWorld().execute(() -> open(ctx, me, store, pair, s, t, sc, look));
    }

    private static final String USAGE =
            "Usage: /mobbattle test preview [two] [spin N] [tilt N] [scale N] [pose <animation set>] [speed <percent>] [keepid] [allsets] [yaw <deg>] [pad N] [x N] [y N] [z N] [model <asset id>] [box] [frame <block id>|none] [bake <animation set> [at <0-1>]] [anim <animation set> [frames N] [fps N]] [hud [seconds]] [compare [animation set]] [pic N] [scene [radius]]";

    /**
     * What the preview plays (2026-10-16, user: the preview animates; "can we put them in a pose?"): the preview loops
     * the model's Idle set, so {@code pose} swaps the Idle of a COPY of the network model for that set (Roar, Taunt,
     * an attack...), looping; {@code speed} scales it (a small value nearly freezes it on its first frames).
     */
    private record Look(String pose, float speed, boolean keepId, boolean allSets, float yaw, int pad, double y,
                        String modelId, double dx, double dz, boolean box, String line, String bake, Double bakeT, String frame, String anim, int frames, int fps, int hud, boolean compare, int pic, int scene) {
    }

    private static void open(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, boolean two,
                             int spin, int tilt, int scale, Look look) {
        if (!me.isValid()) return;
        if (look.scene() > 0) {
            scene(ctx, me, store, tilt, scale, look);
            return;
        }
        Ref<EntityStore> first = ApplyCommand.lookedAt(me, store);
        if (first == null) {
            TestTools.say(ctx.sender(), "Look at a mob first (up to " + (int) ApplyCommand.LOOK_RANGE + " blocks).", ERR);
            return;
        }
        if (look.compare()) {
            compare(ctx, me, store, first, spin, tilt, scale, look);
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
        // bake <set>: each mob's model files with that pose baked in, registered now (one asset reload for the clients),
        // shown BAKE_WAIT_MS later so they have the files.
        java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked = new java.util.HashMap<>();
        if (look.bake() != null) {
            for (Ref<EntityStore> r : shown) {
                try {
                    com.hypixel.hytale.protocol.Model m = bakeModel(r, store, look.bake(), look.bakeT());
                    if (m != null) baked.put(r, m);
                    else TestTools.say(ctx.sender(), nameOf(r, store) + " has no \"" + look.bake() + "\" animation to bake.", ERR);
                } catch (Exception e) {
                    TestTools.say(ctx.sender(), "Bake failed for " + nameOf(r, store) + ": " + e, ERR);
                }
            }
            if (!baked.isEmpty()) {
                com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(
                        new com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild());
                TestTools.say(ctx.sender(), "Baked " + baked.size() + " model(s); showing in " + BAKE_WAIT_MS / 1000.0 + " s.", OK);
                HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(
                        () -> show(ctx, me, store, shown, spin, tilt, scale, look, baked, null)), BAKE_WAIT_MS, TimeUnit.MILLISECONDS);
                return;
            }
        }
        // anim <set>: a flipbook (2026-10-16, user: the viewer never animates): `frames` poses of the set baked at even
        // times, registered at once, then the preview packet is sent again with the next frame every 1000/fps ms.
        if (look.anim() != null) {
            List<java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model>> frames = new ArrayList<>();
            for (int k = 0; k < look.frames(); k++) {
                java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> frame = new java.util.HashMap<>();
                for (Ref<EntityStore> r : shown) {
                    try {
                        com.hypixel.hytale.protocol.Model m = bakeModel(r, store, look.anim(), (double) k / look.frames());
                        if (m != null) frame.put(r, m);
                    } catch (Exception e) {
                        TestTools.say(ctx.sender(), "Bake failed for " + nameOf(r, store) + ": " + e, ERR);
                        return;
                    }
                }
                if (frame.isEmpty()) {
                    TestTools.say(ctx.sender(), "No \"" + look.anim() + "\" animation to bake.", ERR);
                    return;
                }
                frames.add(frame);
            }
            com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(
                    new com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild());
            TestTools.say(ctx.sender(), "Baked " + frames.size() + " frames; playing at " + look.fps() + " fps in "
                    + BAKE_WAIT_MS / 1000.0 + " s.", OK);
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(
                    () -> show(ctx, me, store, shown, spin, tilt, scale, look, frames.get(0), frames)), BAKE_WAIT_MS, TimeUnit.MILLISECONDS);
            return;
        }
        show(ctx, me, store, shown, spin, tilt, scale, look, baked, null);
    }

    /** Running flipbooks per player (cancelled when the page closes or another preview opens). */
    private static final java.util.Map<java.util.UUID, java.util.concurrent.ScheduledFuture<?>> FLIPS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** A flipbook stops by itself after this long. */
    private static final long FLIP_MAX_MS = 120_000;

    private static void stopFlip(java.util.UUID player) {
        var f = FLIPS.remove(player);
        if (f != null) f.cancel(false);
    }

    /** Wait after registering baked models before sending the preview (the clients download them first). */
    private static final long BAKE_WAIT_MS = 2000;

    /**
     * The mob's network model with {@code look.bake()}'s frame baked into every model file it draws (2026-10-16, user:
     * the preview never animates, so the pose has to be in the files): ModelThumbnail.bake per piece (base and
     * attachments), registered as runtime common assets under NPC/MobBattle/Baked/, the copy pointing at them with its
     * own asset id. Null when the model has no such animation set.
     */
    private static com.hypixel.hytale.protocol.Model bakeModel(Ref<EntityStore> r, Store<EntityStore> store, String set,
                                                               Double t) throws Exception {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        var model = mc != null ? mc.getModel() : null;
        if (model == null) return null;
        ThumbnailCache.PoseChoice pc = ThumbnailCache.poseOf(model, set, t);
        if (pc == null) return null;
        ModelThumbnail.Pose pose = new ModelThumbnail.Pose(
                new String(ThumbnailCache.load(pc.file()), java.nio.charset.StandardCharsets.UTF_8), pc.t());
        List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
        String tag = Integer.toHexString((model.getModelAssetId() + "|" + pc.set() + "|" + pc.t() + "|" + pieces).hashCode());
        java.util.Map<String, String> swap = new java.util.HashMap<>();
        String prefix = MorphHologram.registryPrefix();
        for (int i = 0; i < pieces.size(); i++) {
            String src = pieces.get(i).model();
            String json = new String(ThumbnailCache.load(src), java.nio.charset.StandardCharsets.UTF_8);
            String path = "NPC/MobBattle/Baked/" + tag + "/" + i + ".blockymodel";
            byte[] bytes = ModelThumbnail.bake(json, pose, i > 0).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            com.hypixel.hytale.server.core.asset.common.CommonAssetModule.get()
                    .addCommonAsset(EyeRecolor.PACK, new RuntimeCommonAsset(prefix + path, bytes), false);
            swap.putIfAbsent(src, path);
        }
        com.hypixel.hytale.protocol.Model copy = new com.hypixel.hytale.protocol.Model(model.toPacket());
        if (swap.containsKey(copy.path)) copy.path = swap.get(copy.path);
        if (copy.attachments != null) {
            com.hypixel.hytale.protocol.ModelAttachment[] atts = new com.hypixel.hytale.protocol.ModelAttachment[copy.attachments.length];
            for (int i = 0; i < atts.length; i++) {
                atts[i] = new com.hypixel.hytale.protocol.ModelAttachment(copy.attachments[i]);
                if (atts[i].model != null && swap.containsKey(atts[i].model)) atts[i].model = swap.get(atts[i].model);
            }
            copy.attachments = atts;
        }
        copy.assetId = "MobBattle_Baked_" + tag;
        MobBattlePlugin.log("[test] baked " + model.getModelAssetId() + " in " + pc.set() + " at " + pc.t() + ": "
                + pieces.size() + " file(s) under NPC/MobBattle/Baked/" + tag);
        return copy;
    }

    private static void show(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, List<Ref<EntityStore>> shown,
                             int spin, int tilt, int scale, Look look,
                             java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked,
                             List<java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model>> flip) {
        if (!me.isValid()) return;
        BuilderToolPrefabPreview packet = packet(shown, store, spin, tilt, scale, look, baked);
        if (packet == null) {
            TestTools.say(ctx.sender(), "That mob has no model to show.", ERR);
            return;
        }
        PlayerRef player = store.getComponent(me, PlayerRef.getComponentType());
        Player p = store.getComponent(me, Player.getComponentType());
        if (player == null || p == null) return;
        stopFlip(player.getUuid());
        if (look.hud() > 0) {
            // Experiment: the same viewer in a HUD (no page), taken off after the given seconds.
            var huds = p.getHudManager();
            if (huds.getCustomHud(PreviewHud.KEY) != null) huds.removeCustomHud(player, PreviewHud.KEY);
            huds.addCustomHud(player, new PreviewHud(player));
            int gen = HUD_GEN.merge(player.getUuid(), 1, Integer::sum);
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
                if (HUD_GEN.getOrDefault(player.getUuid(), 0) != gen || !me.isValid()) return;
                stopFlip(player.getUuid());
                Player pl = store.getComponent(me, Player.getComponentType());
                if (pl != null && pl.getHudManager().getCustomHud(PreviewHud.KEY) != null) pl.getHudManager().removeCustomHud(player, PreviewHud.KEY);
                clearPreview(player);
            }), look.hud(), TimeUnit.SECONDS);
        } else {
            p.getPageManager().openCustomPage(me, store, new Page(player));
        }
        player.getPacketHandler().write(packet);
        if (flip != null && flip.size() > 1) {
            // Every frame's packet built now (world thread), then sent in a loop from the scheduler.
            List<BuilderToolPrefabPreview> packets = new ArrayList<>();
            for (var frame : flip) packets.add(packet(shown, store, spin, tilt, scale, look, frame));
            long every = Math.max(16, 1000L / Math.max(1, look.fps()));
            long until = System.currentTimeMillis() + FLIP_MAX_MS;
            int[] at = {0};
            java.util.UUID id = player.getUuid();
            FLIPS.put(id, HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                if (!player.isValid() || System.currentTimeMillis() > until) {
                    stopFlip(id);
                    return;
                }
                at[0] = (at[0] + 1) % packets.size();
                player.getPacketHandler().write(packets.get(at[0]));
            }, every, every, TimeUnit.MILLISECONDS));
        } else {
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (player.isValid()) player.getPacketHandler().write(packet);
            }, RESEND_MS, TimeUnit.MILLISECONDS);
        }
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
                                                   int spin, int tilt, int scale, Look look,
                                                   java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked) {
        return packet(mobs, store, spin, tilt, scale, look, baked, null);
    }

    /**
     * {@code models}: one model per entry of {@code mobs} (the same mob may come twice: compare's still and animated
     * copies), all facing look.yaw() side by side; null = as above.
     */
    private static BuilderToolPrefabPreview packet(List<Ref<EntityStore>> mobs, Store<EntityStore> store,
                                                   int spin, int tilt, int scale, Look look,
                                                   java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked,
                                                   List<com.hypixel.hytale.protocol.Model> models) {
        List<ClipboardEntityChange> changes = new ArrayList<>();
        double[][] sizes = new double[mobs.size()][];
        double width = 0;
        for (int i = 0; i < mobs.size(); i++) {
            sizes[i] = visualOf(mobs.get(i), store, models != null ? Math.toRadians(look.yaw()) : yawOf(look, mobs.size(), i));
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
            c.model = models != null && models.get(i) != null ? models.get(i) : baked.containsKey(r) ? baked.get(r) : posed(c.model, look);
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
            float yaw = (float) (models != null ? Math.toRadians(look.yaw()) : yawOf(look, mobs.size(), i));
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
        // ❌ Barrier corners (user test): the viewer drew one wireframe cube and no mob at all, baked or not. So the
        // corner block is chosen per test ("frame <block id>", default none = no blocks, the plain view).
        if (look.box()) p.blocksChange = outline(bounds, BOX_BLOCK, false);
        else if (look.frame() != null) p.blocksChange = outline(bounds, look.frame(), true);
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
    /**
     * Our own invisible block (Server/Item/Items/MobBattle_PreviewFrame.json: a Cube with a fully transparent texture;
     * the game's Barrier is drawn as a wireframe in this viewer and hid the mob): at the 8 corners it gives the viewer
     * a frame to center on, as the stone box did (user, 2026-10-16). "frame none" leaves the blocks out.
     */
    private static final String FRAME_BLOCK = "MobBattle_PreviewFrame";
    /** Most flipbook frames (each is one file per model piece, all registered at once). */
    private static final int MAX_FRAMES = 60;
    /** Fastest flipbook (user, 2026-10-16: 30 looked better than 12, allow 60). */
    private static final int MAX_FPS = 60;

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

    /**
     * The photo's view (ModelThumbnail.THREE_QUARTER = camera at (-0.62, 0.3, 1) from the model): 32 degrees round from
     * the front and 14 above, so compare turns the 3D mob by that much from facing the camera (yaw 180) and tilts the
     * viewer the same (2026-10-16, user: "turn it to the photo's angle, else comparing makes no sense"). If it comes out
     * mirrored, "yaw 212" is the other side.
     */
    private static final float PHOTO_YAW = (float) Math.toDegrees(Math.atan2(0.62, 1));
    private static final int PHOTO_TILT = (int) Math.round(Math.toDegrees(Math.atan2(0.3, Math.hypot(0.62, 1))));

    /** Words that start another option (so "compare" does not take them as its animation set). */
    private static boolean isOption(String w) {
        return java.util.Set.of("two", "spin", "tilt", "scale", "pose", "speed", "keepid", "allsets", "yaw", "pad", "x", "y", "z",
                "model", "box", "frame", "bake", "at", "anim", "frames", "fps", "hud", "compare", "pic", "scene").contains(w.toLowerCase(Locale.ROOT));
    }

    /**
     * compare (2026-10-16, user: "the PNG beside the 3D at the same size, and one animated"): in a HUD (PreviewCompare.ui)
     * the mob's VS picture beside one 3D scene with the mob twice: still in the picture's own pose (VsScreen.variants'
     * first pose, set and time) and animating that set; "compare <set>" picks another set. Everything is baked at once
     * (one asset reload), shown BAKE_WAIT_MS later, for look.hud() seconds (default 30).
     */
    private static void compare(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, Ref<EntityStore> mob,
                                int spin, int tilt, int scale, Look look) {
        ModelComponent mc = store.getComponent(mob, ModelComponent.getComponentType());
        var model = mc != null ? mc.getModel() : null;
        if (model == null) {
            TestTools.say(ctx.sender(), "That mob has no model to show.", ERR);
            return;
        }
        ThumbnailCache.PoseChoice pc;
        if (look.anim() != null) {
            pc = ThumbnailCache.poseOf(model, look.anim(), 0.45);
        } else {
            List<ThumbnailCache.PoseChoice> vs = VsScreen.variants(model, VsScreen.flyingModel(model.getModelAssetId()));
            pc = vs.isEmpty() ? null : vs.get(0);
        }
        if (pc == null) {
            TestTools.say(ctx.sender(), "No animation to compare" + (look.anim() != null ? " named " + look.anim() : "") + ".", ERR);
            return;
        }
        com.hypixel.hytale.protocol.Model still;
        List<com.hypixel.hytale.protocol.Model> frames = new ArrayList<>();
        try {
            still = bakeModel(mob, store, pc.set(), pc.t());
            for (int k = 0; k < look.frames(); k++) frames.add(bakeModel(mob, store, pc.set(), (double) k / look.frames()));
        } catch (Exception e) {
            TestTools.say(ctx.sender(), "Bake failed: " + e, ERR);
            return;
        }
        com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(
                new com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild());
        String role = nameOf(mob, store);
        String picture = VsScreen.iconFor(role);
        TestTools.say(ctx.sender(), "Compare " + role + ": " + pc.set() + " at " + String.format(Locale.ROOT, "%.2f", pc.t())
                + " (the VS picture's pose" + (look.anim() != null ? " asked: " + look.anim() : "") + "), " + frames.size()
                + " frames at " + look.fps() + " fps; view yaw " + Math.round(look.yaw()) + " tilt " + tilt + ", photo " + look.pic() + " px; picture " + (picture != null ? picture : "none registered") + ". Showing in "
                + BAKE_WAIT_MS / 1000.0 + " s for " + Math.max(1, look.hud() > 0 ? look.hud() : 30) + " s.", OK);
        List<Ref<EntityStore>> two = List.of(mob, mob);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid() || !mob.isValid()) return;
            PlayerRef player = store.getComponent(me, PlayerRef.getComponentType());
            Player p = store.getComponent(me, Player.getComponentType());
            if (player == null || p == null) return;
            List<BuilderToolPrefabPreview> packets = new ArrayList<>();
            for (com.hypixel.hytale.protocol.Model f : frames) {
                BuilderToolPrefabPreview pk = packet(two, store, spin, tilt, scale, look, java.util.Map.of(), java.util.Arrays.asList(still, f));
                if (pk != null) packets.add(pk);
            }
            if (packets.isEmpty()) return;
            stopFlip(player.getUuid());
            var huds = p.getHudManager();
            if (huds.getCustomHud(CompareHud.KEY) != null) huds.removeCustomHud(player, CompareHud.KEY);
            if (huds.getCustomHud(PreviewHud.KEY) != null) huds.removeCustomHud(player, PreviewHud.KEY);
            huds.addCustomHud(player, new CompareHud(player, picture, look.pic()));
            int gen = HUD_GEN.merge(player.getUuid(), 1, Integer::sum);
            player.getPacketHandler().write(packets.get(0));
            long every = Math.max(16, 1000L / Math.max(1, look.fps()));
            int[] at = {0};
            java.util.UUID id = player.getUuid();
            FLIPS.put(id, HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
                if (!player.isValid()) {
                    stopFlip(id);
                    return;
                }
                at[0] = (at[0] + 1) % packets.size();
                player.getPacketHandler().write(packets.get(at[0]));
            }, every, every, TimeUnit.MILLISECONDS));
            int secs = look.hud() > 0 ? look.hud() : 30;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
                if (HUD_GEN.getOrDefault(id, 0) != gen || !me.isValid()) return;
                stopFlip(id);
                Player pl = store.getComponent(me, Player.getComponentType());
                if (pl != null && pl.getHudManager().getCustomHud(CompareHud.KEY) != null) pl.getHudManager().removeCustomHud(player, CompareHud.KEY);
                clearPreview(player);
            }), secs, TimeUnit.SECONDS);
        }), BAKE_WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** Most mobs in the scene test. */
    private static final int SCENE_MAX = 40;
    /** Mobs per row of a side (across the screen's depth). */
    private static final int SCENE_COLS = 5;

    /**
     * scene (2026-10-16, user: "a VS in 3D, let's try"): every NPC within look.scene() blocks (up to SCENE_MAX) in two
     * sides facing each other in ONE 3D scene, in a HUD across the screen (PreviewScene.ui). Sides = their battle's
     * sides when the first one fights in a battle (side 0 or the first side left, the rest right), else dealt in turn
     * by height. Each side: tallest at the back, rows of SCENE_COLS. Each mob in its VS picture's pose, baked once per
     * model (one asset reload), shown BAKE_WAIT_MS later for look.hud() seconds (default 30).
     */
    private static void scene(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, int tilt, int scale, Look look) {
        org.joml.Vector3d center = ChaosWand.positionOf(me, store);
        if (center == null) return;
        List<Ref<EntityStore>> near = new ArrayList<>(ChaosWand.npcsInBand(center, look.scene(), SCENE_MAX, store));
        near.removeIf(r -> store.getComponent(r, ModelComponent.getComponentType()) == null);
        if (near.size() < 2) {
            TestTools.say(ctx.sender(), "Fewer than 2 mobs within " + look.scene() + " blocks.", ERR);
            return;
        }
        // sides
        List<Ref<EntityStore>> left = new ArrayList<>(), right = new ArrayList<>();
        BattleManager.Battle battle = BattleManager.battleOf(near.get(0));
        if (battle != null) {
            int firstSide = battle.team(near.get(0));
            for (Ref<EntityStore> r : near) {
                if (BattleManager.battleOf(r) != battle) continue;
                (battle.team(r) == firstSide ? left : right).add(r);
            }
        }
        if (left.isEmpty() || right.isEmpty()) {
            left.clear();
            right.clear();
            List<Ref<EntityStore>> byHeight = new ArrayList<>(near);
            byHeight.sort((a, b) -> Double.compare(sizeOf(b, store)[0], sizeOf(a, store)[0]));
            for (int i = 0; i < byHeight.size(); i++) (i % 2 == 0 ? left : right).add(byHeight.get(i));
        }
        // one bake per model (its pieces), the VS picture's first pose
        java.util.Map<String, com.hypixel.hytale.protocol.Model> bakedByKey = new java.util.HashMap<>();
        java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked = new java.util.HashMap<>();
        for (Ref<EntityStore> r : near) {
            ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
            var model = mc.getModel();
            if (model == null) continue;
            String key = model.getModelAssetId() + "|" + ThumbnailCache.piecesOf(model);
            com.hypixel.hytale.protocol.Model m = bakedByKey.get(key);
            if (m == null && !bakedByKey.containsKey(key)) {
                List<ThumbnailCache.PoseChoice> vs = VsScreen.variants(model, VsScreen.flyingModel(model.getModelAssetId()));
                ThumbnailCache.PoseChoice pc = vs.isEmpty() ? null : vs.get(0);
                try {
                    m = pc != null ? bakeModel(r, store, pc.set(), pc.t()) : null;
                } catch (Exception e) {
                    m = null;
                }
                bakedByKey.put(key, m);
            }
            if (m != null) baked.put(r, m);
        }
        com.hypixel.hytale.server.core.universe.Universe.get().broadcastPacketNoCache(
                new com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild());
        int secs = look.hud() > 0 ? look.hud() : 30;
        TestTools.say(ctx.sender(), "Scene: " + left.size() + " vs " + right.size() + (battle != null ? " (their battle's sides)" : " (dealt by height)")
                + ", " + bakedByKey.size() + " model(s) baked. Showing in " + BAKE_WAIT_MS / 1000.0 + " s for " + secs + " s.", OK);
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid()) return;
            PlayerRef player = store.getComponent(me, PlayerRef.getComponentType());
            Player p = store.getComponent(me, Player.getComponentType());
            if (player == null || p == null) return;
            BuilderToolPrefabPreview packet = scenePacket(left, right, store, tilt, scale, look, baked);
            if (packet == null) return;
            stopFlip(player.getUuid());
            var huds = p.getHudManager();
            for (String k : new String[] {SceneHud.KEY, CompareHud.KEY, PreviewHud.KEY}) {
                if (huds.getCustomHud(k) != null) huds.removeCustomHud(player, k);
            }
            huds.addCustomHud(player, new SceneHud(player));
            int gen = HUD_GEN.merge(player.getUuid(), 1, Integer::sum);
            player.getPacketHandler().write(packet);
            // sent again a moment later, as the page does (the HUD may not exist yet on the client)
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (player.isValid()) player.getPacketHandler().write(packet);
            }, RESEND_MS, TimeUnit.MILLISECONDS);
            java.util.UUID id = player.getUuid();
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> store.getExternalData().getWorld().execute(() -> {
                if (HUD_GEN.getOrDefault(id, 0) != gen || !me.isValid()) return;
                Player pl = store.getComponent(me, Player.getComponentType());
                if (pl != null && pl.getHudManager().getCustomHud(SceneHud.KEY) != null) pl.getHudManager().removeCustomHud(player, SceneHud.KEY);
                clearPreview(player);
            }), secs, TimeUnit.SECONDS);
        }), BAKE_WAIT_MS, TimeUnit.MILLISECONDS);
    }

    /** The two sides placed facing each other across x = 0.5: rows away from the middle, tallest at the back. */
    private static BuilderToolPrefabPreview scenePacket(List<Ref<EntityStore>> left, List<Ref<EntityStore>> right,
                                                        Store<EntityStore> store, int tilt, int scale, Look look,
                                                        java.util.Map<Ref<EntityStore>, com.hypixel.hytale.protocol.Model> baked) {
        List<ClipboardEntityChange> changes = new ArrayList<>();
        double[] box = {Double.MAX_VALUE, -Double.MAX_VALUE, 0, 1, Double.MAX_VALUE, -Double.MAX_VALUE}; // minX maxX minY maxY minZ maxZ
        for (int side = 0; side < 2; side++) {
            List<Ref<EntityStore>> mobs = new ArrayList<>(side == 0 ? left : right);
            double yaw = side == 0 ? Math.atan2(-1, 0) : Math.atan2(1, 0);
            double sign = side == 0 ? -1 : 1;
            // The viewer looks along z (user test, 2026-10-16: rows set along x piled every side's mobs on top of each
            // other), so a side spreads across x from the middle outward and its rows go back along -z: shortest in
            // the front row, tallest at the back.
            mobs.sort((a, b) -> Double.compare(sizeOf(a, store)[0], sizeOf(b, store)[0]));
            double[][] s = new double[mobs.size()][];
            double wide = 0.5, deep = 0.5;
            for (int i = 0; i < mobs.size(); i++) {
                s[i] = visualOf(mobs.get(i), store, yaw);
                wide = Math.max(wide, s[i][1]);
                deep = Math.max(deep, s[i][2]);
            }
            for (int i = 0; i < mobs.size(); i++) {
                int row = i / SCENE_COLS, col = i % SCENE_COLS;
                double px = 0.5 + sign * (1.0 + wide + col * (wide * 2 + 0.3));
                double pz = 0.5 - row * (deep * 2 + 0.6);
                ClipboardEntityChange c = change(mobs.get(i), store);
                if (c == null) continue;
                if (baked.containsKey(mobs.get(i))) c.model = baked.get(mobs.get(i));
                c.x = (float) (px - s[i][3]);
                c.y = (float) -s[i][5];
                c.z = (float) (pz - s[i][4]);
                c.bodyOrientation = new Direction((float) yaw, 0f, 0f);
                c.lookOrientation = new Direction((float) yaw, 0f, 0f);
                changes.add(c);
                box[0] = Math.min(box[0], px - s[i][1]);
                box[1] = Math.max(box[1], px + s[i][1]);
                box[3] = Math.max(box[3], s[i][0]);
                box[4] = Math.min(box[4], pz - s[i][2]);
                box[5] = Math.max(box[5], pz + s[i][2]);
            }
        }
        if (changes.isEmpty()) return null;
        BuilderToolPrefabPreview p = new BuilderToolPrefabPreview();
        p.tilt = tilt;
        p.spinSpeed = 0;
        p.previewScale = scale;
        p.blocksOmitted = false;
        p.blocksChange = new BlockChange[0];
        p.fluidsChange = new FluidChange[0];
        p.entityChanges = changes.toArray(new ClipboardEntityChange[0]);
        EditorSelection bounds = new EditorSelection();
        bounds.minX = (int) Math.floor(box[0]) - look.pad();
        bounds.maxX = (int) Math.ceil(box[1]) - 1 + look.pad();
        bounds.minZ = (int) Math.floor(box[4]) - look.pad();
        bounds.maxZ = (int) Math.ceil(box[5]) - 1 + look.pad();
        bounds.minY = 0;
        bounds.maxY = Math.max(0, (int) Math.ceil(box[3]) - 1) + look.pad();
        p.bounds = bounds;
        if (look.box()) p.blocksChange = outline(bounds, BOX_BLOCK, false);
        else if (look.frame() != null) p.blocksChange = outline(bounds, look.frame(), true);
        return p;
    }

    /** The scene test's HUD (PreviewScene.ui). */
    private static final class SceneHud extends com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud {
        static final String KEY = "MobBattlePreviewScene";

        SceneHud(PlayerRef player) {
            super(player, KEY);
        }

        @Override
        protected void build(UICommandBuilder b) {
            b.append("MobBattle/PreviewScene.ui");
        }
    }

    /** The comparison HUD (PreviewCompare.ui): the VS picture and the 3D viewer. */
    private static final class CompareHud extends com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud {
        static final String KEY = "MobBattlePreviewCompare";
        static final int[] PIC_SIZES = {200, 260, 320, 400, 480, 560};
        private final String picture;
        private final int size;

        CompareHud(PlayerRef player, String picture, int size) {
            super(player, KEY);
            this.picture = picture;
            this.size = size;
        }

        @Override
        protected void build(UICommandBuilder b) {
            b.append("MobBattle/PreviewCompare.ui");
            // "pic N": the photo's size, to match the 3D mob's by eye (the viewer adds its own margin). Fixed sizes in
            // the .ui (#Pic200..#Pic560), the nearest one shown: an Anchor set at runtime is unproven here, and a set the
            // client can't apply disconnects the player.
            int best = PIC_SIZES[0];
            for (int s : PIC_SIZES) if (Math.abs(s - size) < Math.abs(best - size)) best = s;
            String path = picture != null ? picture : "UI/Custom/Pages/Memories/MissingIcon.png";
            for (int s : PIC_SIZES) {
                b.set("#Pic" + s + ".Visible", s == best);
                if (s == best) b.set("#Pic" + s + ".AssetPath", path);
            }
        }
    }

    /** The HUD experiment's run per player (a newer one makes an older removal skip). */
    private static final java.util.Map<java.util.UUID, Integer> HUD_GEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** An empty preview packet: the viewer drops what it showed. */
    private static void clearPreview(PlayerRef player) {
        BuilderToolPrefabPreview empty = new BuilderToolPrefabPreview();
        empty.blocksChange = new BlockChange[0];
        empty.fluidsChange = new FluidChange[0];
        empty.entityChanges = new ClipboardEntityChange[0];
        if (player.isValid()) player.getPacketHandler().write(empty);
    }

    /** The preview in a HUD (PreviewHud.ui), for the "hud" option. */
    private static final class PreviewHud extends com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud {
        static final String KEY = "MobBattlePreviewHud";

        PreviewHud(PlayerRef player) {
            super(player, KEY);
        }

        @Override
        protected void build(UICommandBuilder b) {
            b.append("MobBattle/PreviewHud.ui");
        }
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
            stopFlip(playerRef.getUuid());
            BuilderToolPrefabPreview empty = new BuilderToolPrefabPreview();
            empty.blocksChange = new BlockChange[0];
            empty.fluidsChange = new FluidChange[0];
            empty.entityChanges = new ClipboardEntityChange[0];
            if (playerRef.isValid()) playerRef.getPacketHandler().write(empty);
        }
    }
}
