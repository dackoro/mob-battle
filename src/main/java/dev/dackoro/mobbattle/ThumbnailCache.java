package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Thumbnails of every model asset, made once and kept on disk (2026-10-16, user: generate them when the server
 * starts, cache them, regenerate in the background with a command; for a Smash-like VS screen with any mob).
 * <ul>
 *   <li>{@link #startup()} (BootEvent, before players join) queues every ModelAsset; a model whose key is unchanged and
 *   whose PNG exists is skipped, so after the first boot this costs one hash per model, no rendering.</li>
 *   <li>Key = renderer version + model id + the content hash (CommonAsset.getHash) of each file drawn (model, texture,
 *   attachments): a changed model, texture or mod version makes a new one.</li>
 *   <li>Everything runs on one low-priority daemon thread, never a world thread. Decoded textures sit in a small LRU,
 *   so a full rebuild does not keep every texture in memory.</li>
 *   <li>Files: {@code <data dir>/thumbnails/models/<model id>.png} and {@code thumbnails/index.properties}
 *   (model id = key).</li>
 * </ul>
 * Pose (2026-10-16): each is drawn in its "auto" animation frame ({@link #poseOf}: Sprint, Run, JumpRun... then
 * bind), three-quarter, looking toward the right of the picture; the pose set, its time and its file hash are in the
 * key. The other side of a VS screen can mirror the image.
 * Morph Wand hologram (2026-10-16): each thumbnail also gets its hologram texture ({@code thumbnails/holo/<id>.png},
 * {@link MorphHologram#hologram}), made from the cached thumbnail when only that one is missing. They are registered
 * as common assets of the mod at boot, before anyone joins ({@link #registerHolograms}), so clients get them with the
 * other assets and no asset rebuild (the hitch the user saw) is needed. Ones made later wait for the next boot unless
 * nobody is online. Cost: every joining client downloads them (~600 small PNGs). ❓ Untested in game.
 * Next step (not done): show the thumbnails in the VS screen.
 */
final class ThumbnailCache {

    /** Bump when the renderer's output changes, so every cached picture is made again (2 = posed). */
    static final int RENDER_VERSION = 2;
    /** Longest side of a cached thumbnail (px). */
    static final int SIZE = 384;
    /** Decoded textures kept between models (an LRU: big ones are 1024x1024, 4 MB each). */
    private static final int TEXTURE_CACHE = 24;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MobBattle-Thumbnails");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile Future<?> current;
    /** Settings panel icons (2026-10-16, user: every mob without a game icon gets one): side in px. */
    static final int ICON = 64;
    static final String ICON_FOLDER = "UI/Custom/MobBattle/Icons/";
    /** Model id -> plain path of its registered icon. */
    private static final Map<String, String> ICONS = new ConcurrentHashMap<>();
    /** Model id -> plain path of its registered hologram texture. */
    private static final Map<String, String> HOLOGRAMS = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> TEXTURES = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > TEXTURE_CACHE;
        }
    };

    /** One model file and its texture, as the Model holds them. */
    record Piece(String model, String texture) {
    }

    /** The animation frame a thumbnail is drawn in: the set's name, its file, the time (0-1). */
    record PoseChoice(String set, String file, double t) {
    }

    /**
     * Sets tried for "auto" (user, 2026-10-16: idle front views look odd, make them epic), most dynamic first. A model
     * with none of them is drawn in its bind pose.
     */
    static final String[] AUTO_POSES = {"Sprint", "Run", "JumpRun", "Jump", "Walk", "Idle"};

    /**
     * The frame to draw a model in: "auto" = the first of AUTO_POSES it has, "bind" = none, "idle" / "run" / "jump" /
     * "walk" (run falls back to Sprint, jump to JumpRun), or any set name it has (case-insensitive, e.g. Roar,
     * SawCharge, DashForward). {@code t} null = the default for that kind (run 0.35, jump 0.4, else 0.3). Null when
     * the model has no such set (or "bind").
     */
    static PoseChoice poseOf(Model model, String want, Double t) {
        Map<String, ModelAsset.AnimationSet> sets = model != null ? model.getAnimationSetMap() : null;
        if (sets == null || sets.isEmpty() || "bind".equalsIgnoreCase(want)) return null;
        String w = want == null ? "auto" : want.toLowerCase(java.util.Locale.ROOT);
        String[] tries = switch (w) {
            case "auto" -> AUTO_POSES;
            case "run" -> new String[] {"Run", "Sprint"};
            case "jump" -> new String[] {"JumpRun", "Jump", "JumpWalk"};
            case "idle" -> new String[] {"Idle"};
            case "walk" -> new String[] {"Walk"};
            default -> new String[] {want};
        };
        for (String name : tries) {
            for (Map.Entry<String, ModelAsset.AnimationSet> e : sets.entrySet()) {
                if (!e.getKey().equalsIgnoreCase(name) || e.getValue() == null) continue;
                ModelAsset.Animation[] anims = e.getValue().getAnimations();
                if (anims == null || anims.length == 0 || anims[0] == null || anims[0].getAnimation() == null) continue;
                String set = e.getKey();
                double time = t != null ? t : set.startsWith("Jump") ? 0.4 : set.equals("Run") || set.equals("Sprint") ? 0.35 : 0.3;
                return new PoseChoice(set, anims[0].getAnimation(), time);
            }
        }
        return null;
    }

    /** The frame as the renderer takes it (the animation file read), or null for the bind pose. */
    static ModelThumbnail.Pose load(PoseChoice choice) throws IOException {
        if (choice == null) return null;
        return new ModelThumbnail.Pose(new String(load(choice.file()), StandardCharsets.UTF_8), choice.t());
    }

    /** What a run did. */
    record Report(int total, int rendered, int cached, int failed, long millis, List<String> failures) {
    }

    private ThumbnailCache() {
    }

    // ---- entry points ----

    /** BootEvent: makes the missing or outdated thumbnails of every model, in the background. */
    static void startup() {
        List<String> all = new ArrayList<>(ModelAsset.getAssetMap().getAssetMap().keySet());
        registerHolograms(all);
        registerIcons(all);
        VsScreen.registerAtBoot();
        rebuild(id -> true, false, report -> {
            MobBattlePlugin.log("Thumbnails: " + describe(report));
            registerHolograms(all);
            registerIcons(all);
        });
    }

    /** The registered hologram texture of a model (plain path, as a ModelAttachment takes it), or null. */
    static String hologramTexture(String modelId) {
        return modelId == null ? null : HOLOGRAMS.get(modelId);
    }

    /** A hologram texture registered elsewhere (MorphHologram's live fallback). */
    static void markHologram(String modelId, String path) {
        HOLOGRAMS.put(modelId, path);
    }

    /**
     * Registers the hologram PNGs on disk of these models that are not registered yet, silently. Only while nobody is
     * online: a client already connected would not get them without an asset rebuild (MorphHologram falls back to a
     * live one for a model it needs).
     */
    static void registerHolograms(List<String> ids) {
        if (!Universe.get().getPlayers().isEmpty()) return;
        int n = 0;
        for (String id : ids) {
            if (HOLOGRAMS.containsKey(id)) continue;
            Path f = holoFileOf(id);
            if (f == null || !Files.exists(f)) continue;
            try {
                HOLOGRAMS.put(id, MorphHologram.register(id, Files.readAllBytes(f), false));
                n++;
            } catch (Exception e) {
                MobBattlePlugin.log("Hologram texture of " + id + " not registered: " + e);
            }
        }
        if (n > 0) MobBattlePlugin.log("Thumbnails: " + n + " hologram textures registered.");
    }

    /**
     * Our icon of a role or model (the role's Appearance tried too), when registered; null otherwise. Only models the
     * game has no icon for get one.
     */
    static String iconFor(String name) {
        if (name == null) return null;
        String path = ICONS.get(name);
        if (path != null) return path;
        String model = RoleAppearance.of(name);
        return model != null ? ICONS.get(model) : null;
    }

    /**
     * Registers the icons on disk of the models the game has no icon for, silently, while nobody is online (same rule
     * as the holograms: a connected client would need an asset rebuild). ~2 KB each at 64 px.
     */
    static void registerIcons(List<String> ids) {
        if (!Universe.get().getPlayers().isEmpty()) return;
        String prefix = MorphHologram.registryPrefix();
        int n = 0;
        for (String id : ids) {
            if (ICONS.containsKey(id) || ConfigPage.hasGameIcon(id)) continue;
            Path f = iconFileOf(id);
            if (f == null || !Files.exists(f)) continue;
            try {
                String plain = ICON_FOLDER + "MobBattle_Icon_" + safe(id) + ".png";
                com.hypixel.hytale.server.core.asset.common.CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK,
                        new RuntimeCommonAsset(prefix + plain, Files.readAllBytes(f)), false);
                ICONS.put(id, plain);
                n++;
            } catch (Exception e) {
                MobBattlePlugin.log("Icon of " + id + " not registered: " + e);
            }
        }
        if (n > 0) MobBattlePlugin.log("Thumbnails: " + n + " mob icons registered (models with no game icon).");
    }

    private static Path iconFileOf(String modelId) {
        Path dir = folder();
        return dir == null ? null : dir.resolve("icons").resolve(safe(modelId) + ".png");
    }

    /** A model's settings panel icon: its thumbnail on a square canvas, feet at the bottom, ICON px. */
    private static void writeIcon(String id, BufferedImage thumb) throws IOException {
        Path f = iconFileOf(id);
        if (f == null) return;
        Files.createDirectories(f.getParent());
        ImageIO.write(MorphHologram.shrink(VsScreen.square(thumb, false), ICON, ICON), "png", f.toFile());
        ICONS.remove(id);
    }

    private static Path holoFileOf(String modelId) {
        Path dir = folder();
        return dir == null ? null : dir.resolve("holo").resolve(safe(modelId) + ".png");
    }

    /** Writes a model's hologram texture next to its thumbnail; a newer one drops the registration (next boot). */
    private static void writeHologram(String id, BufferedImage thumb) throws IOException {
        Path f = holoFileOf(id);
        if (f == null) return;
        Files.createDirectories(f.getParent());
        Files.write(f, MorphHologram.encode(MorphHologram.hologram(thumb)));
        HOLOGRAMS.remove(id);
    }

    /**
     * Makes the thumbnails of the models {@code which} accepts, in the background; {@code force} redraws even the
     * up-to-date ones. False if a run is already going (the caller tells the user). {@code done} gets the report on
     * the background thread.
     */
    static boolean rebuild(Predicate<String> which, boolean force, Consumer<Report> done) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        current = EXEC.submit(() -> {
            try {
                done.accept(run(which, force));
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Thumbnails failed: " + e);
            } finally {
                synchronized (TEXTURES) {
                    TEXTURES.clear();
                }
                RUNNING.set(false);
            }
        });
        return true;
    }

    static boolean running() {
        return RUNNING.get();
    }

    /** Plugin shutdown: a run in progress stops at its next model. */
    static void stop() {
        Future<?> f = current;
        if (f != null) f.cancel(true);
    }

    static String describe(Report r) {
        return r.total() + " models, " + r.rendered() + " drawn, " + r.cached() + " up to date, " + r.failed()
                + " failed, " + r.millis() + " ms" + (r.failures().isEmpty() ? "" : " (first failures: "
                + String.join("; ", r.failures().subList(0, Math.min(3, r.failures().size()))) + ")");
    }

    /** The cached picture of a model asset, or null if there is none yet. */
    static Path fileOf(String modelId) {
        Path dir = folder();
        return dir == null ? null : dir.resolve("models").resolve(safe(modelId) + ".png");
    }

    // ---- the run ----

    private static Report run(Predicate<String> which, boolean force) {
        long t0 = System.nanoTime();
        Path dir = folder();
        if (dir == null) return new Report(0, 0, 0, 0, 0, List.of("plugin data folder not known"));
        Properties index = readIndex(dir);
        List<String> ids = new ArrayList<>();
        for (String id : ModelAsset.getAssetMap().getAssetMap().keySet()) if (which.test(id)) ids.add(id);
        ids.sort(String::compareTo);
        int rendered = 0, cached = 0, failed = 0, sinceSave = 0;
        List<String> failures = new ArrayList<>();
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) break;
            try {
                ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
                if (asset == null) continue;
                Model model = Model.createScaledModel(asset, 1f, Map.of());
                List<Piece> pieces = piecesOf(model);
                if (pieces.isEmpty()) continue;
                PoseChoice pose = poseOf(model, "auto", null);
                String key = keyOf(id, pieces, pose);
                Path file = dir.resolve("models").resolve(safe(id) + ".png");
                if (!force && key.equals(index.getProperty(id)) && Files.exists(file)) {
                    Path holo = holoFileOf(id), icon = iconFileOf(id);
                    boolean needHolo = holo != null && !Files.exists(holo), needIcon = icon != null && !Files.exists(icon);
                    if (needHolo || needIcon) {
                        BufferedImage thumb = ImageIO.read(file.toFile());
                        if (thumb != null && needHolo) writeHologram(id, thumb);
                        if (thumb != null && needIcon) writeIcon(id, thumb);
                    }
                    cached++;
                    continue;
                }
                BufferedImage img = render(pieces, load(pose), ModelThumbnail.THREE_QUARTER, SIZE, true);
                if (img == null) {
                    failed++;
                    failures.add(id + ": nothing visible");
                    continue;
                }
                Files.createDirectories(file.getParent());
                ImageIO.write(img, "png", file.toFile());
                writeHologram(id, img);
                writeIcon(id, img);
                index.setProperty(id, key);
                rendered++;
                // save the index now and then, so a stopped server keeps what it made
                if (++sinceSave >= 25) {
                    writeIndex(dir, index);
                    sinceSave = 0;
                }
            } catch (Exception e) {
                failed++;
                failures.add(id + ": " + e.getMessage());
            }
        }
        writeIndex(dir, index);
        return new Report(ids.size(), rendered, cached, failed, (System.nanoTime() - t0) / 1_000_000, failures);
    }

    /** The files a model draws: its own model and texture, then each attachment's (empty ones skipped). */
    static List<Piece> piecesOf(Model model) {
        List<Piece> pieces = new ArrayList<>();
        if (model == null || model.getModel() == null || model.getModel().isBlank()) return pieces;
        pieces.add(new Piece(model.getModel(), model.getTexture()));
        ModelAttachment[] atts = model.getAttachments();
        if (atts != null) {
            for (ModelAttachment a : atts) {
                if (a != null && a.getModel() != null && !a.getModel().isBlank()) pieces.add(new Piece(a.getModel(), a.getTexture()));
            }
        }
        return pieces;
    }

    /** Renders the pieces (base first) in the bind pose, three-quarter view. */
    static BufferedImage render(List<Piece> pieces, int size, boolean shade) throws IOException {
        return render(pieces, null, ModelThumbnail.THREE_QUARTER, size, shade);
    }

    /** Renders the pieces (base first): the model file is required, a missing attachment is left out. */
    static BufferedImage render(List<Piece> pieces, ModelThumbnail.Pose pose, double[] view, int size, boolean shade) throws IOException {
        List<ModelThumbnail.Part> parts = new ArrayList<>();
        for (Piece p : pieces) {
            try {
                String json = new String(load(p.model()), StandardCharsets.UTF_8);
                parts.add(new ModelThumbnail.Part(json, p.texture() == null ? null : texture(p.texture())));
            } catch (IOException | RuntimeException e) {
                if (parts.isEmpty()) throw e;
            }
        }
        return ModelThumbnail.render(parts, pose, size, view, shade);
    }

    // ---- keys and files ----

    private static String keyOf(String id, List<Piece> pieces, PoseChoice pose) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(("v" + RENDER_VERSION + "|" + SIZE + "|" + id).getBytes(StandardCharsets.UTF_8));
        if (pose != null) {
            sha.update(("|pose " + pose.set() + "@" + pose.t() + "=" + pose.file() + "=" + hashOf(pose.file())).getBytes(StandardCharsets.UTF_8));
        }
        for (Piece p : pieces) {
            sha.update(("|" + p.model() + "=" + hashOf(p.model()) + "|" + p.texture() + "=" + hashOf(p.texture()))
                    .getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** The content hash the asset registry already keeps for a file (no read), or "-" if it is not there. */
    private static String hashOf(String path) {
        if (path == null) return "-";
        CommonAsset a = find(path);
        return a != null ? a.getHash() : "-";
    }

    private static CommonAsset find(String path) {
        CommonAsset a = CommonAssetRegistry.getByName(path);
        return a != null ? a : CommonAssetRegistry.getByName("Common/" + path);
    }

    /** Bytes of a common asset: the path as the model writes it, then with the "Common/" prefix (as EyeRecolor). */
    static byte[] load(String path) throws IOException {
        CommonAsset asset = find(path);
        if (asset == null) throw new IOException("not in the common asset registry: " + path);
        try {
            return asset.getBlob().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IOException("could not read " + asset.getName() + ": " + e, e);
        }
    }

    static BufferedImage texture(String path) throws IOException {
        synchronized (TEXTURES) {
            BufferedImage cached = TEXTURES.get(path);
            if (cached != null) return cached;
        }
        BufferedImage img;
        try (ByteArrayInputStream in = new ByteArrayInputStream(load(path))) {
            img = ImageIO.read(in);
        }
        if (img == null) throw new IOException("not an image: " + path);
        synchronized (TEXTURES) {
            TEXTURES.put(path, img);
        }
        return img;
    }

    private static Path folder() {
        Path d = FxSettings.dataDirectory();
        return d == null ? null : d.resolve("thumbnails");
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Properties readIndex(Path dir) {
        Properties p = new Properties();
        Path f = dir.resolve("index.properties");
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                MobBattlePlugin.log("Thumbnails: index unreadable, starting over: " + e);
            }
        }
        return p;
    }

    private static void writeIndex(Path dir, Properties p) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(dir.resolve("index.properties"), StandardCharsets.UTF_8)) {
                p.store(w, "Mob Battle model thumbnails: model id = key (renderer version + file hashes)");
            }
        } catch (IOException e) {
            MobBattlePlugin.log("Thumbnails: index not saved: " + e);
        }
    }
}
