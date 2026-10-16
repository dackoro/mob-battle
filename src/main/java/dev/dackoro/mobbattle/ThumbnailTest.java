package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * /mobbattle test thumbnail [pose ...] [t 0..1] [side ...] [size N] [flat] (dev prototype, 2026-10-16): renders the mob you look at with
 * {@link ModelThumbnail} to {@code <data dir>/thumbnails/<role>.png}, server-side, from its live Model (model file,
 * texture and the attachments that entity wears). Files are read through CommonAssetRegistry as EyeRecolor does, off
 * the world thread, through ThumbnailCache's loader. Gradient tints (GradientSet) are not applied.
 */
final class ThumbnailTest {

    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";
    private ThumbnailTest() {
    }

    static void command(CommandContext ctx, String[] a) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can pick a mob to render.", ERR);
            return;
        }
        int size = 512;
        boolean shade = true;
        String pose = "auto";
        Double t = null;
        String side = "left";
        for (int i = 1; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            try {
                switch (w) {
                    case "size" -> size = Math.max(32, Math.min(2048, Integer.parseInt(a[++i])));
                    case "flat" -> shade = false;
                    case "pose" -> pose = a[++i];
                    case "t" -> t = Math.max(0, Math.min(1, Double.parseDouble(a[++i])));
                    case "side" -> {
                        side = a[++i];
                        if (ModelThumbnail.viewOf(side) == null) throw new IllegalArgumentException();
                    }
                    default -> throw new IllegalArgumentException();
                }
            } catch (RuntimeException e) {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test thumbnail [pose auto|bind|idle|run|jump|walk|<set>] [t 0..1] [side left|right|front|profileLeft|profileRight] [size N] [flat]", ERR);
                return;
            }
        }
        Ref<EntityStore> me = ctx.senderAsPlayerRef();
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        Options opt = new Options(size, shade, pose, t, side);
        store.getExternalData().getWorld().execute(() -> pick(ctx, me, store, opt));
    }

    private record Options(int size, boolean shade, String pose, Double t, String side) {
    }

    /** World thread: what to draw, read from the looked-at entity's model (its animation sets for the pose). */
    private static void pick(CommandContext ctx, Ref<EntityStore> me, Store<EntityStore> store, Options opt) {
        if (!me.isValid()) return;
        Ref<EntityStore> target = ApplyCommand.lookedAt(me, store);
        if (target == null) {
            TestTools.say(ctx.sender(), "Look at a mob first (up to " + (int) ApplyCommand.LOOK_RANGE + " blocks).", ERR);
            return;
        }
        ModelComponent mc = store.getComponent(target, ModelComponent.getComponentType());
        Model model = mc != null ? mc.getModel() : null;
        if (model == null || model.getModel() == null) {
            TestTools.say(ctx.sender(), "That entity has no model file to render.", ERR);
            return;
        }
        List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
        NPCEntity npc = store.getComponent(target, NPCEntity.getComponentType());
        PlayerRef pr = store.getComponent(target, PlayerRef.getComponentType());
        String name = npc != null ? npc.getRoleName() : pr != null ? pr.getUsername() : model.getModelAssetId();
        boolean gradient = model.getGradientSet() != null;
        ThumbnailCache.PoseChoice pose = ThumbnailCache.poseOf(model, opt.pose(), opt.t());
        if (pose == null && !"bind".equalsIgnoreCase(opt.pose()) && !"auto".equalsIgnoreCase(opt.pose())) {
            TestTools.say(ctx.sender(), name + " has no animation set " + opt.pose() + " (drawn in its bind pose).", ERR);
        }
        CompletableFuture.runAsync(() -> renderAndSave(ctx, name, pieces, pose, opt, gradient));
    }

    /** Off the world thread: load and render through ThumbnailCache (same loader and texture cache), then write. */
    private static void renderAndSave(CommandContext ctx, String name, List<ThumbnailCache.Piece> pieces,
                                      ThumbnailCache.PoseChoice pose, Options opt, boolean gradient) {
        try {
            long t0 = System.nanoTime();
            BufferedImage img = ThumbnailCache.render(pieces, ThumbnailCache.load(pose), ModelThumbnail.viewOf(opt.side()), opt.size(), opt.shade());
            long t1 = System.nanoTime();
            if (img == null) {
                TestTools.say(ctx.sender(), "Nothing visible to render for " + name + ".", ERR);
                return;
            }
            Path dir = FxSettings.dataDirectory();
            if (dir == null) {
                TestTools.say(ctx.sender(), "The plugin data folder is not known yet.", ERR);
                return;
            }
            Path folder = dir.resolve("thumbnails");
            Files.createDirectories(folder);
            String poseName = pose == null ? "bind" : pose.set();
            Path file = folder.resolve(name.replaceAll("[^A-Za-z0-9_.-]", "_") + "_" + poseName + "_" + opt.side() + ".png");
            ImageIO.write(img, "png", file.toFile());
            TestTools.say(ctx.sender(), "Thumbnail of " + name + ": " + img.getWidth() + "x" + img.getHeight() + ", "
                    + pieces.size() + " part(s), pose " + (pose == null ? "bind" : pose.set() + " at " + pose.t())
                    + ", side " + opt.side() + ", load + render " + ms(t1 - t0) + " ms -> thumbnails/" + file.getFileName()
                    + (gradient ? " (its gradient tint is not applied)" : ""), OK);
            MobBattlePlugin.log("[test] thumbnail " + name + " " + img.getWidth() + "x" + img.getHeight() + " in " + ms(t1 - t0) + " ms");
        } catch (Exception e) {
            TestTools.say(ctx.sender(), "Thumbnail failed: " + e, ERR);
            MobBattlePlugin.log("[test] thumbnail failed: " + e);
        }
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }
}
