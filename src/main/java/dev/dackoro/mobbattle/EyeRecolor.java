package dev.dackoro.mobbattle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * EXPERIMENTAL (/mobbattle eyeTest [#rrggbb|off]): recolors the eyes of the nearest NPC (within 8 blocks).
 * <ol>
 *   <li>World thread: pick the NPC, remember its model (only if not remembered yet).</li>
 *   <li>Background thread: read its .blockymodel and PNG (main model and attachments) from the common asset
 *       registry, recolor the pixels of the eye shapes and register each new PNG as a runtime common asset
 *       ({@link CommonAssetModule#addCommonAsset}), which sends it to the connected clients.</li>
 *   <li>After {@link #APPLY_DELAY_MS} (clients get the asset first): world thread, new Model with the new
 *       texture paths, {@code putComponent(ModelComponent)}.</li>
 * </ol>
 * {@code off} restores the original Model of every mob this command changed (in this world). The generated
 * assets stay registered (there is no removal call used here).
 */
final class EyeRecolor {

    /** Pack name of our assets (same as the mod's own pack). */
    static final String PACK = "Dackoro:Mob Battle";
    static final double RANGE = 8.0;
    static final int DEFAULT_COLOR = 0xA24BFF; // Teams.PALETTE Purple
    /** Clients need this long after the asset registration + rebuild request before the new texture exists. */
    static final long APPLY_DELAY_MS = 1500;

    private static final String OK = "#7fd3ff";
    private static final String ERR = "#ffcc55";
    private static final String USAGE = "Usage: /mobbattle eyeTest [<color>|off] (colors: " + String.join(", ", Teams.PALETTE.keySet()) + ")";
    private static final String SUFFIX = "_MobBattleEyes_";
    private static final Pattern SUFFIX_RE = Pattern.compile("_MobBattleEyes_[0-9a-f]{6}$");

    /** NPC -> model before its first eyeTest change (for off). Keyed by Ref: pruned in BattleManager.maintain. */
    private static final Map<Ref<EntityStore>, Model> ORIGINALS = new HashMap<>();
    /** Bumped by off: a recolor still waiting for its delay is dropped. */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /**
     * "texture path|hex" -> the recolored texture already registered this session (plain path + eye pixels). A new
     * eyeTest with the same texture and color reuses it: no PNG, no registration, no rebuild request.
     */
    private static final Map<String, Recolored> RECOLORED = new ConcurrentHashMap<>();

    private EyeRecolor() {
    }

    /** Drops originals of removed NPCs (BattleManager.maintain): each Ref keeps its whole world reachable. */
    static synchronized void prune() {
        ORIGINALS.keySet().removeIf(r -> !r.isValid());
    }

    /** Entry point of the command. {@code args} = the words after "eyeTest". */
    static void command(CommandContext ctx, String[] args) {
        boolean off = args.length == 1 && "off".equalsIgnoreCase(args[0]);
        Integer color = DEFAULT_COLOR;
        if (args.length > 1 || args.length == 1 && !off) {
            color = args.length == 1 ? parseColor(args[0]) : null;
            if (color == null) {
                err(ctx, USAGE);
                return;
            }
        }
        if (!ctx.isPlayer()) {
            err(ctx, "Only a player can use eyeTest (it works on the nearest mob).");
            return;
        }
        var ref = ctx.senderAsPlayerRef();
        if (ref == null || !ref.isValid()) {
            return;
        }
        var store = ref.getStore();
        int c = color;
        store.getExternalData().getWorld().execute(() -> {
            try {
                if (!ref.isValid() || store.isShutdown()) {
                    return;
                }
                if (off) {
                    off(ctx, store);
                } else {
                    start(ctx, ref, store, c);
                }
            } catch (Throwable t) {
                err(ctx, "eyeTest failed: " + t);
            }
        });
    }

    /** "#rrggbb" or "rrggbb" as an RGB int, or null. */
    static Integer parseColor(String text) {
        if (text == null) return null;
        // Palette colors only (free hex caused problems elsewhere in the mod).
        for (var e : Teams.PALETTE.entrySet()) {
            if (e.getKey().equalsIgnoreCase(text.trim())) return Integer.parseInt(e.getValue().substring(1), 16);
        }
        return null;
    }

    // ---- world thread ------------------------------------------------------------------------------------------

    private static void start(CommandContext ctx, Ref<EntityStore> player, Store<EntityStore> store, int color) {
        Vector3d pos = ChaosWand.positionOf(player, store);
        if (pos == null) {
            err(ctx, "eyeTest: cannot read your position.");
            return;
        }
        // getAllEntitiesInSphere returns a thread-local list: copy it right away.
        List<Ref<EntityStore>> near = new ArrayList<>(TargetUtil.getAllEntitiesInSphere(pos, RANGE, store));
        Ref<EntityStore> npc = null;
        double bestD = Double.MAX_VALUE;
        for (Ref<EntityStore> r : near) {
            if (!r.isValid() || r.getStore() != store) continue;
            if (store.getComponent(r, NPCEntity.getComponentType()) == null) continue;
            ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
            if (mc == null || mc.getModel() == null) continue;
            Vector3d p = ChaosWand.positionOf(r, store);
            if (p == null) continue;
            double d = p.distanceSquared(pos);
            if (d <= RANGE * RANGE && d < bestD) {
                bestD = d;
                npc = r;
            }
        }
        if (npc == null) {
            err(ctx, "eyeTest: no NPC with a model within " + (int) RANGE + " blocks.");
            return;
        }
        ModelComponent mc = store.getComponent(npc, ModelComponent.getComponentType());
        Model base = mc.getModel();
        NPCEntity ne = store.getComponent(npc, NPCEntity.getComponentType());
        String mob = ne != null && ne.getRoleName() != null ? ne.getRoleName() : "mob";
        String hex = String.format(Locale.ROOT, "%06x", color);
        ok(ctx, "eyeTest: " + mob + " (model " + base.getModel() + ", texture " + base.getTexture()
                + ", " + (base.getAttachments() == null ? 0 : base.getAttachments().length)
                + " attachments), color #" + hex + ". Generating the textures in the background...");

        Job job = new Job(ctx, npc, store, base, mob, hex, GENERATION.get());
        CompletableFuture.runAsync(() -> {
            try {
                Plan plan = build(ctx, base, color, hex);
                if (plan.pixels == 0) {
                    err(ctx, "eyeTest: no eye pixels in " + mob + "'s textures; nothing changed.");
                    return;
                }
                register(plan);
                ok(ctx, "eyeTest: " + plan.assets.size() + " texture(s) registered; the mob changes in "
                        + APPLY_DELAY_MS + " ms.");
                HytaleServer.SCHEDULED_EXECUTOR.schedule(
                        () -> store.getExternalData().getWorld().execute(() -> apply(job, plan)),
                        APPLY_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                err(ctx, "eyeTest failed while generating: " + t);
            }
        });
    }

    /** World thread: the new Model with the new texture paths, if the mob is still the same. */
    private static void apply(Job job, Plan plan) {
        try {
            if (GENERATION.get() != job.gen()) {
                err(job.ctx(), "eyeTest: cancelled by eyeTest off; nothing changed.");
                return;
            }
            if (job.store().isShutdown() || !job.npc().isValid()) {
                err(job.ctx(), "eyeTest: the mob is gone; nothing changed.");
                return;
            }
            ModelComponent mc = job.store().getComponent(job.npc(), ModelComponent.getComponentType());
            if (mc == null || mc.getModel() != job.base()) {
                err(job.ctx(), "eyeTest: " + job.mob() + "'s model changed meanwhile (morph?); nothing changed. Run eyeTest again.");
                return;
            }
            remember(job.npc(), job.base());
            job.store().putComponent(job.npc(), ModelComponent.getComponentType(),
                    new ModelComponent(withTextures(job.base(), plan)));
            ok(job.ctx(), "eyeTest: " + job.mob() + " now wears the recolored eyes (" + plan.pixels
                    + " pixels). eyeTest off puts its model back.");
        } catch (Throwable t) {
            err(job.ctx(), "eyeTest failed while applying: " + t);
        }
    }

    /** World thread: original models back for the mobs of this world. Other worlds keep theirs. */
    private static void off(CommandContext ctx, Store<EntityStore> store) {
        GENERATION.incrementAndGet();
        int[] counts = restoreAll(store);
        ok(ctx, "eyeTest off: " + counts[0] + " mob(s) got their original model back"
                + (counts[1] > 0 ? "; " + counts[1] + " in another world stay until you run off there." : "."));
    }

    private static synchronized void remember(Ref<EntityStore> npc, Model original) {
        ORIGINALS.putIfAbsent(npc, original);
    }

    /** {restored, kept for another world}. */
    private static synchronized int[] restoreAll(Store<EntityStore> store) {
        int restored = 0;
        int kept = 0;
        for (Ref<EntityStore> r : new ArrayList<>(ORIGINALS.keySet())) {
            if (!r.isValid()) {
                ORIGINALS.remove(r);
                continue;
            }
            if (r.getStore().getExternalData() != store.getExternalData()) {
                kept++;
                continue;
            }
            Model original = ORIGINALS.remove(r);
            store.putComponent(r, ModelComponent.getComponentType(), new ModelComponent(original));
            restored++;
        }
        return new int[] {restored, kept};
    }

    // ---- background thread -------------------------------------------------------------------------------------

    private record Job(CommandContext ctx, Ref<EntityStore> npc, Store<EntityStore> store, Model base,
                       String mob, String hex, int gen) {
    }

    /** A new PNG to register; {@code cacheKey}/{@code plain}/{@code pixels} go into RECOLORED once it is registered. */
    private record Asset(String name, byte[] png, String cacheKey, String plain, int pixels) {
    }

    private record Recolored(String plain, int pixels) {
    }

    private record Shape(JsonObject shape, boolean fullbright) {
    }

    private record Loaded(String name, byte[] bytes) {
    }

    /** Everything the apply step needs: new texture paths (null = unchanged) and the assets to register. */
    private static final class Plan {
        final CommandContext ctx;
        final List<Asset> assets = new ArrayList<>();
        /** "modelPath|texturePath" -> new plain texture path, or null when it had no eye pixels. */
        final Map<String, String> done = new HashMap<>();
        final Set<String> names = new HashSet<>();
        String mainTexture;
        String[] attachmentTextures = new String[0];
        int pixels;

        Plan(CommandContext ctx) {
            this.ctx = ctx;
        }
    }

    /** Off the world thread: recolors the main texture and every attachment texture. */
    private static Plan build(CommandContext ctx, Model base, int color, String hex) {
        Plan plan = new Plan(ctx);
        plan.mainTexture = safeRecolor(plan, "main", base.getModel(), base.getTexture(), color, hex);
        ModelAttachment[] atts = base.getAttachments();
        int n = atts == null ? 0 : atts.length;
        plan.attachmentTextures = new String[n];
        for (int i = 0; i < n; i++) {
            plan.attachmentTextures[i] = safeRecolor(plan, "attachment " + i, atts[i].getModel(), atts[i].getTexture(), color, hex);
        }
        return plan;
    }

    private static String safeRecolor(Plan plan, String label, String modelPath, String texPath, int color, String hex) {
        if (modelPath == null || texPath == null) return null;
        try {
            return recolorPair(plan, label, modelPath, texPath, color, hex);
        } catch (Throwable t) {
            err(plan.ctx, "eyeTest: " + label + " " + texPath + " skipped (" + t + ")");
            return null;
        }
    }

    /** The new plain texture path for this model+texture pair, or null if it has no eye pixels. */
    private static String recolorPair(Plan plan, String label, String modelPath, String texPath, int color, String hex)
            throws Exception {
        String key = modelPath + "|" + texPath;
        if (plan.done.containsKey(key)) {
            return plan.done.get(key);
        }
        String cacheKey = texPath + "|" + hex;
        Recolored known = RECOLORED.get(cacheKey);
        if (known != null) {
            plan.pixels += known.pixels();
            plan.done.put(key, known.plain());
            ok(plan.ctx, "eyeTest: " + label + " " + texPath + " already recolored this session (" + known.plain()
                    + ", " + known.pixels() + " eye pixels); not registered again");
            return known.plain();
        }
        String out = null;
        Loaded modelFile = load(modelPath);
        JsonObject root = JsonParser.parseString(new String(modelFile.bytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        List<Shape> eyes = eyeShapes(root);
        if (eyes.isEmpty()) {
            ok(plan.ctx, "eyeTest: " + label + " " + modelPath + ": no eye nodes");
        } else {
            Loaded texFile = load(texPath);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(texFile.bytes()));
            if (img == null) {
                throw new IOException("unreadable PNG " + texPath);
            }
            BufferedImage argb = toArgb(img);
            int painted = paint(argb, eyes, color);
            ok(plan.ctx, "eyeTest: " + label + " " + texPath + " (" + eyes.size() + " eye shapes, name form "
                    + (texFile.name().equals(texPath) ? "plain" : "Common/ prefix") + "): " + painted + " eye pixels");
            if (painted > 0) {
                String form = texFile.name().substring(0, texFile.name().length() - texPath.length());
                String name = unique(plan, form + eyeTexturePath(texPath, hex));
                out = name.substring(form.length());
                plan.assets.add(new Asset(name, encode(argb), cacheKey, out, painted));
                plan.pixels += painted;
                ok(plan.ctx, "eyeTest: asset " + name);
            }
        }
        plan.done.put(key, out);
        return out;
    }

    /** Bytes of a common asset: the name as the model writes it, then with the "Common/" prefix. */
    private static Loaded load(String path) throws Exception {
        for (String name : new String[] {path, "Common/" + path}) {
            CommonAsset asset = CommonAssetRegistry.getByName(name);
            if (asset != null) {
                return new Loaded(name, asset.getBlob().get(5, TimeUnit.SECONDS));
            }
        }
        throw new IOException("not in the common asset registry: " + path);
    }

    /** Eye shapes: nodes whose name contains "eye" (and their descendants). Fullbright ones if there are any. */
    private static List<Shape> eyeShapes(JsonObject root) {
        List<Shape> all = new ArrayList<>();
        if (root.has("nodes") && root.get("nodes").isJsonArray()) {
            collect(root.getAsJsonArray("nodes"), false, all);
        }
        boolean anyFullbright = all.stream().anyMatch(Shape::fullbright);
        if (!anyFullbright) return all;
        List<Shape> bright = new ArrayList<>();
        for (Shape s : all) if (s.fullbright()) bright.add(s);
        return bright;
    }

    private static void collect(JsonArray nodes, boolean inEye, List<Shape> out) {
        for (JsonElement e : nodes) {
            if (!e.isJsonObject()) continue;
            JsonObject node = e.getAsJsonObject();
            boolean eye = inEye || str(node, "name", "").toLowerCase(Locale.ROOT).contains("eye");
            JsonObject shape = obj(node, "shape");
            if (eye && shape != null && !"none".equals(str(shape, "type", ""))) {
                out.add(new Shape(shape, "fullbright".equalsIgnoreCase(str(shape, "shadingMode", ""))));
            }
            JsonArray children = node.has("children") && node.get("children").isJsonArray()
                    ? node.getAsJsonArray("children") : null;
            if (children != null) collect(children, eye, out);
        }
    }

    /** Rectangles {x, y, w, h} in texture pixels of every face of a quad or box. */
    private static List<int[]> faceRects(JsonObject shape) {
        List<int[]> rects = new ArrayList<>();
        String type = str(shape, "type", "");
        JsonObject settings = obj(shape, "settings");
        JsonObject layout = obj(shape, "textureLayout");
        JsonObject size = settings == null ? null : obj(settings, "size");
        if (size == null || layout == null) return rects;
        double sx = num(size, "x", 0), sy = num(size, "y", 0), sz = num(size, "z", 0);
        for (String face : layout.keySet()) {
            JsonObject f = obj(layout, face);
            JsonObject off = f == null ? null : obj(f, "offset");
            if (off == null) continue;
            double w;
            double h;
            if ("quad".equals(type)) {
                w = sx;
                h = sy;
            } else if ("box".equals(type) && (face.equals("front") || face.equals("back"))) {
                w = sx;
                h = sy;
            } else if ("box".equals(type) && (face.equals("left") || face.equals("right"))) {
                w = sz;
                h = sy;
            } else if ("box".equals(type) && (face.equals("top") || face.equals("bottom"))) {
                w = sx;
                h = sz;
            } else {
                continue;
            }
            int angle = (int) num(f, "angle", 0);
            if (angle == 90 || angle == 270) {
                double t = w;
                w = h;
                h = t;
            }
            rects.add(new int[] {(int) Math.round(num(off, "x", 0)), (int) Math.round(num(off, "y", 0)),
                    (int) Math.round(w), (int) Math.round(h)});
        }
        return rects;
    }

    /**
     * Recolors the opaque pixels of the eye rectangles: keeps each pixel's luminance, takes the hue of
     * {@code color} (target * (0.35 + 0.65 * L)), so dark pixels stay darker. Returns how many pixels changed.
     */
    private static int paint(BufferedImage img, List<Shape> shapes, int color) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] done = new boolean[w * h];
        int tr = (color >> 16) & 255;
        int tg = (color >> 8) & 255;
        int tb = color & 255;
        int count = 0;
        for (Shape s : shapes) {
            for (int[] r : faceRects(s.shape())) {
                for (int y = r[1]; y < r[1] + r[3]; y++) {
                    if (y < 0 || y >= h) continue;
                    for (int x = r[0]; x < r[0] + r[2]; x++) {
                        if (x < 0 || x >= w || done[y * w + x]) continue;
                        int argb = img.getRGB(x, y);
                        int a = argb >>> 24;
                        if (a == 0) continue;
                        done[y * w + x] = true;
                        int pr = (argb >> 16) & 255;
                        int pg = (argb >> 8) & 255;
                        int pb = argb & 255;
                        double lum = (0.299 * pr + 0.587 * pg + 0.114 * pb) / 255.0;
                        double f = 0.35 + 0.65 * lum;
                        img.setRGB(x, y, (a << 24) | (clamp(tr * f) << 16) | (clamp(tg * f) << 8) | clamp(tb * f));
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** New texture path: same folder, base name + "_MobBattleEyes_<hex>.png" (a previous suffix is replaced). */
    static String eyeTexturePath(String texPath, String hex) {
        int slash = texPath.lastIndexOf('/');
        String folder = texPath.substring(0, slash + 1);
        String file = texPath.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        String base = dot > 0 ? file.substring(0, dot) : file;
        base = SUFFIX_RE.matcher(base).replaceFirst("");
        return folder + base + SUFFIX + hex + ".png";
    }

    /** A name not used yet in this plan (same texture under two models): "_2", "_3"... before ".png". */
    private static String unique(Plan plan, String name) {
        String n = name;
        int i = 2;
        while (!plan.names.add(n)) {
            n = name.replaceFirst("\\.png$", "_" + i++ + ".png");
        }
        return n;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", bos)) {
            throw new IOException("no PNG writer");
        }
        return bos.toByteArray();
    }

    /**
     * Off the world thread: registers each new PNG as a common asset, silently (the flag false: no "Reloaded assets"
     * toast). Then, once for the whole plan, the rebuild request the game's asset monitor sends (clients rebuild their
     * texture atlas; without it the new eyes stayed empty). Each new PNG also goes to debug/eyes/ in the data folder.
     */
    private static void register(Plan plan) {
        if (plan.assets.isEmpty()) {
            return;
        }
        for (Asset a : plan.assets) {
            CommonAssetModule.get().addCommonAsset(PACK, new EyeAsset(a.name(), a.png()), false);
            boolean found = CommonAssetRegistry.getByName(a.name()) != null;
            ok(plan.ctx, "eyeTest: registered " + a.name() + " in pack " + PACK + " (" + a.png().length
                    + " bytes, " + (found ? "found" : "NOT found") + " in the registry)");
            if (found) {
                RECOLORED.put(a.cacheKey(), new Recolored(a.plain(), a.pixels()));
            }
            writeDebugPng(plan.ctx, a);
        }
        Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
        ok(plan.ctx, "eyeTest: asked the clients to rebuild their common assets.");
    }

    /** The PNG as sent to the clients, in &lt;plugin data dir&gt;/debug/eyes/&lt;file name&gt;.png, to open and check. */
    private static void writeDebugPng(CommandContext ctx, Asset a) {
        Path dir = FxSettings.dataDirectory();
        if (dir == null) {
            ok(ctx, "eyeTest: debug PNG not written (the plugin data folder is not known yet).");
            return;
        }
        try {
            String file = a.name().substring(a.name().lastIndexOf('/') + 1);
            Path folder = dir.resolve("debug").resolve("eyes");
            Files.createDirectories(folder);
            Path target = folder.resolve(file);
            Files.write(target, a.png());
            ok(ctx, "eyeTest: debug PNG " + target.toAbsolutePath());
        } catch (IOException | RuntimeException ex) {
            err(ctx, "eyeTest: debug PNG for " + a.name() + " not written (" + ex + ")");
        }
    }

    /** Runtime PNG. The base class keeps only a WeakReference to the future, so the bytes stay here. */
    private static final class EyeAsset extends CommonAsset {
        private final byte[] png;

        EyeAsset(String name, byte[] png) {
            super(name, png);
            this.png = png;
        }

        @Override
        protected CompletableFuture<byte[]> getBlob0() {
            return CompletableFuture.completedFuture(png);
        }
    }

    /** World thread: a Model equal to the base one with the new texture paths. */
    private static Model withTextures(Model base, Plan plan) {
        ModelAttachment[] atts = base.getAttachments();
        ModelAttachment[] next = atts;
        if (atts != null) {
            next = new ModelAttachment[atts.length];
            for (int i = 0; i < atts.length; i++) {
                ModelAttachment a = atts[i];
                String t = i < plan.attachmentTextures.length ? plan.attachmentTextures[i] : null;
                next[i] = t == null ? a : new ModelAttachment(a.getModel(), t, a.getGradientSet(), a.getGradientId(), a.getWeight());
            }
        }
        String main = plan.mainTexture != null ? plan.mainTexture : base.getTexture();
        return new Model(base.getModelAssetId(), base.getScale(), base.getRandomAttachmentIds(), next,
                base.getBoundingBox(), base.getModel(), main, base.getGradientSet(), base.getGradientId(),
                base.getEyeHeight(), base.getCrouchOffset(), base.getSittingOffset(), base.getSleepingOffset(),
                base.getAnimationSetMap(), base.getCamera(), base.getLight(), base.getParticles(), base.getTrails(),
                base.getPhysicsValues(), base.getDetailBoxes(), base.getPhobia(), base.getPhobiaModelAssetId());
    }

    // ---- small helpers -----------------------------------------------------------------------------------------

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static double num(JsonObject o, String key, double def) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsDouble() : def;
    }

    private static void ok(CommandContext ctx, String text) {
        say(ctx, text, OK);
    }

    private static void err(CommandContext ctx, String text) {
        say(ctx, text, ERR);
    }

    private static void say(CommandContext ctx, String text, String color) {
        MobBattlePlugin.log("[eyeTest] " + text);
        ctx.sendMessage(Message.raw(text).color(color));
    }
}
