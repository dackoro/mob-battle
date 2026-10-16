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

    /**
     * Bump when the renderer's output changes, so every cached picture is made again (2 = posed, 3 = pieces not animated
     * twice, 4 = gradient tints and the standard-unwrap faces a layout leaves out: the Outlanders' faces, 5 = a Head bone
     * without boxes uses its subtree, fliers framed on the body only when spread wide, 6 = drawn at SUPERSAMPLE times the
     * size and averaged down: no texture noise, 7 = an inherited model gradient only on a mostly grey texture).
     */
    static final int RENDER_VERSION = 7;
    /**
     * Pictures are drawn this many times bigger and box-averaged down (2026-10-16, user: the VS pictures and the
     * hologram looked dithered: a big model's texels are smaller than a pixel, so nearest-texel sampling picked noise).
     */
    static final int SUPERSAMPLE = 2;
    /** Bump when MorphHologram.hologram's look changes (2 = green-scale, no corner brackets, faint scanlines, 3 = 256 px, brighter, 4 = palette PNG). */
    static final int HOLO_VERSION = 4;
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
    /** Not under UI/Custom: the client packs that folder into one UI atlas, which these filled (2026-10-16). */
    static final String ICON_FOLDER = "Icons/MobBattle/Panel/";
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

    /**
     * One model file and its texture, as the Model holds them; {@code gradientSet}/{@code gradientId} (both or none) tint
     * a greyscale texture (player skins, some mob clothes: ModelThumbnail.tint).
     */
    record Piece(String model, String texture, String gradientSet, String gradientId, boolean modelLevel) {
        Piece(String model, String texture) {
            this(model, texture, null, null, false);
        }

        Piece(String model, String texture, String gradientSet, String gradientId) {
            this(model, texture, gradientSet, gradientId, false);
        }

        boolean tinted() {
            return gradientSet != null && gradientId != null;
        }
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
        MorphHologram.startAtBoot();
        // holograms first, from the thumbnails already on disk (a few seconds), and registered before players join:
        // after a hologram look change (HOLO_VERSION) the long thumbnail run below made them one by one, and a player
        // who joined meanwhile got each copied mob live, with an asset reload for everyone (user, 2026-10-16)
        EXEC.submit(() -> {
            quickHolograms(all);
            registerHolograms(all);
        });
        rebuild(id -> true, false, report -> {
            MobBattlePlugin.log("Thumbnails: " + describe(report));
            // every hologram of this look is on disk now: older looks' folders go (they were the stand-ins until now)
            deleteOtherFolders(folder(), "holo", "holo" + HOLO_VERSION);
            registerHolograms(all);
            registerIcons(all);
            VsScreen.prepareInBackground();
        });
    }

    /**
     * Cache clean-up after a look or render change (2026-10-16, user: a server that already had pictures cached must
     * redraw them with the new rules, and the old ones should go): deletes the folders in {@code dir} named {@code prefix}
     * plus anything (holo, holo2, p384, p384r5...) except {@code keep}. Only our own cache folders, only called once the
     * current version's files are all on disk; a folder in use by another process is left for the next boot.
     */
    static void deleteOtherFolders(Path dir, String prefix, String keep) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (var list = Files.list(dir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String n = p.getFileName().toString();
                if (!Files.isDirectory(p) || n.equals(keep) || !n.startsWith(prefix)
                        || !n.substring(prefix.length()).matches("r?[0-9]*(r[0-9]+)?")) continue;
                long files = 0;
                try (var walk = Files.walk(p)) {
                    for (Path f : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                        if (Files.isRegularFile(f)) files++;
                        Files.deleteIfExists(f);
                    }
                } catch (IOException e) {
                    MobBattlePlugin.log("Cache folder " + n + " not fully deleted: " + e);
                    continue;
                }
                MobBattlePlugin.log("Cache: old folder " + n + " deleted (" + files + " files).");
            }
        } catch (IOException e) {
            MobBattlePlugin.log("Cache clean-up skipped: " + e);
        }
    }

    /** Writes the missing hologram textures from the thumbnails on disk (any render version: only the look matters). */
    private static void quickHolograms(List<String> ids) {
        long t0 = System.nanoTime();
        int n = 0;
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) return;
            Path holo = holoFileOf(id), thumb = fileOf(id);
            if (holo == null || Files.exists(holo) || thumb == null || !Files.exists(thumb)) continue;
            try {
                BufferedImage img = ImageIO.read(thumb.toFile());
                if (img == null) continue;
                writeHologram(id, img);
                n++;
            } catch (Exception e) {
                MobBattlePlugin.log("Hologram texture of " + id + " not made: " + e);
            }
        }
        if (n > 0) MobBattlePlugin.log("Thumbnails: " + n + " hologram textures made from the cache in " + (System.nanoTime() - t0) / 1_000_000 + " ms.");
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
        // own folder per hologram look: a new look (HOLO_VERSION) draws them all again at the next boot
        return dir == null ? null : dir.resolve("holo" + HOLO_VERSION).resolve(safe(modelId) + ".png");
    }

    /** Writes a model's hologram texture next to its thumbnail (registered at the next boot if one is registered now). */
    private static void writeHologram(String id, BufferedImage thumb) throws IOException {
        Path f = holoFileOf(id);
        if (f == null) return;
        Files.createDirectories(f.getParent());
        Files.write(f, MorphHologram.encode(MorphHologram.hologram(thumb)));
        // a registered texture stays in use (the new file is registered at the next boot): dropping it here made
        // MorphHologram make that model's texture live, with an asset reload for every client, during the boot run
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
                Model model = ThumbnailCache.modelOf(asset);
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

    /**
     * A model as a thumbnail draws it: unit scale, and for each random attachment set (goblin hats, trork gear,
     * kweebec leaves, animal coats: 56 vanilla models) its first option by name, so the picture always has the gear and
     * is the same every time (user, 2026-10-16: an empty map drew them without any of it).
     */
    static Model modelOf(ModelAsset asset) {
        Map<String, String> ids = new java.util.TreeMap<>();
        try {
            Map<String, Map<String, ModelAttachment>> sets = asset.getRandomAttachmentSets();
            if (sets != null) {
                for (Map.Entry<String, Map<String, ModelAttachment>> e : sets.entrySet()) {
                    if (e.getValue() == null || e.getValue().isEmpty()) continue;
                    ids.put(e.getKey(), new java.util.TreeSet<>(e.getValue().keySet()).first());
                }
            }
        } catch (RuntimeException ignored) {
            // no sets: the plain model
        }
        return Model.createScaledModel(asset, 1f, ids);
    }

    /** The files a model draws: its own model and texture, then each attachment's (empty ones skipped). */
    static List<Piece> piecesOf(Model model) {
        List<Piece> pieces = new ArrayList<>();
        if (model == null || model.getModel() == null || model.getModel().isBlank()) return pieces;
        pieces.add(new Piece(model.getModel(), model.getTexture(), model.getGradientSet(), model.getGradientId(), true));
        ModelAttachment[] atts = model.getAttachments();
        if (atts != null) for (ModelAttachment a : atts) addPiece(pieces, a);
        return pieces;
    }

    /** An attachment as a piece (skipped when it has no model). */
    static void addPiece(List<Piece> pieces, ModelAttachment a) {
        if (a != null && a.getModel() != null && !a.getModel().isBlank()) {
            pieces.add(new Piece(a.getModel(), a.getTexture(), a.getGradientSet(), a.getGradientId()));
        }
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
                parts.add(new ModelThumbnail.Part(json, p.texture() == null ? null : texture(p)));
            } catch (IOException | RuntimeException e) {
                if (parts.isEmpty()) throw e;
            }
        }
        BufferedImage big = ModelThumbnail.render(parts, pose, size * SUPERSAMPLE, view, shade);
        if (big == null || SUPERSAMPLE == 1) return big;
        int w = Math.max(1, (big.getWidth() + SUPERSAMPLE - 1) / SUPERSAMPLE), h = Math.max(1, (big.getHeight() + SUPERSAMPLE - 1) / SUPERSAMPLE);
        double[] head = ModelThumbnail.LAST_HEAD.get();
        if (head != null) ModelThumbnail.LAST_HEAD.set(new double[] {head[0] / SUPERSAMPLE, head[1] / SUPERSAMPLE});
        return MorphHologram.shrink(big, w, h);
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
            if (p.tinted()) sha.update(("@" + p.gradientSet() + "/" + p.gradientId()).getBytes(StandardCharsets.UTF_8));
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

    /**
     * A piece's texture, tinted by its gradient when it has one (the gradient strip from the cosmetics registry's
     * GradientSets; an unknown set or id leaves it grey). Cached per texture + gradient.
     */
    static BufferedImage texture(Piece p) throws IOException {
        if (!p.tinted()) return texture(p.texture());
        String key = p.texture() + "@" + p.gradientSet() + "/" + p.gradientId() + (p.modelLevel() ? "#model" : "");
        synchronized (TEXTURES) {
            BufferedImage cached = TEXTURES.get(key);
            if (cached != null) return cached;
        }
        BufferedImage grey = texture(p.texture());
        // a model's own gradient can be inherited (MajorDungeons' Baron has Parent Player, so Skin/02 came with it) on
        // a colored texture the game does not tint (user, 2026-10-16: the silver Baron came out gold): only a mostly
        // grey texture takes it (the player body 94 %, the Baron 25 %). Attachment gradients are always applied.
        if (p.modelLevel() && greyShare(grey) < MODEL_TINT_GREY) return grey;
        String strip = gradientTexture(p.gradientSet(), p.gradientId());
        BufferedImage out = strip != null ? ModelThumbnail.tint(grey, texture(strip)) : grey;
        synchronized (TEXTURES) {
            TEXTURES.put(key, out);
        }
        return out;
    }

    private static final double MODEL_TINT_GREY = 0.8;

    /** Share of the opaque pixels that are grey (r, g, b within 6 of each other). */
    static double greyShare(BufferedImage img) {
        long grey = 0, all = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                int r = c >> 16 & 255, g = c >> 8 & 255, b = c & 255;
                all++;
                if (Math.abs(r - g) <= 6 && Math.abs(g - b) <= 6) grey++;
            }
        }
        return all == 0 ? 0 : (double) grey / all;
    }

    /** The strip of a gradient (e.g. Hair/Black -> TintGradients/Hair/Black.png), or null when unknown. */
    static String gradientTexture(String set, String id) {
        try {
            var sets = com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get().getRegistry().getGradientSets();
            var gs = sets != null ? sets.get(set) : null;
            var g = gs != null && gs.getGradients() != null ? gs.getGradients().get(id) : null;
            return g != null ? g.getTexture() : null;
        } catch (RuntimeException e) {
            return null;
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
