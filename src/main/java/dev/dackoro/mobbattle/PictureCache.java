package dev.dackoro.mobbattle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Where the generated pictures live (thumbnails, VS pictures, holograms, icons, saved player skins): ONE folder per
 * machine shared by every world and server, not one per world (playtest 2026-10-16: each new single-player world redrew
 * everything for 1-2 minutes, and the test server's copy reached ~300 MB). Every file's key (render version + asset
 * hashes) or name (version folders) already says what drew it, so sharing is safe; two servers may use it at once, so
 * every write goes to a temp file first and is renamed into place.
 *
 * <p>Location, first that can be written: the {@code MOBBATTLE_CACHE} environment variable or the
 * {@code mobbattle.cache} system property; the game's own data folder holding {@code Saves} (single player:
 * {@code UserData}, or {@code data/pre-release}) found above the plugin data folder, plus {@code MobBattleCache}; on
 * Windows {@code %APPDATA%\Hytale\UserData\MobBattleCache} (a dedicated server on a PC with the game shares it); else
 * {@code ~/.cache/mobbattle}; last, the plugin data folder's own {@code thumbnails} (the old per-world place).
 */
final class PictureCache {
    private PictureCache() {
    }

    private static volatile Path root;

    /** The cache folder, chosen once (null before the plugin data folder is known). */
    static Path root() {
        Path r = root;
        if (r != null) return r;
        synchronized (PictureCache.class) {
            if (root != null) return root;
            Path data = FxSettings.dataDirectory();
            if (data == null) return null;
            Path chosen = null;
            for (Path c : candidates(data)) {
                if (c != null && writable(c)) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) chosen = data.resolve("thumbnails");
            root = chosen;
            MobBattlePlugin.log("Picture cache: " + chosen);
            noteOldFolder(data.resolve("thumbnails"), chosen);
            return chosen;
        }
    }

    private static Path[] candidates(Path data) {
        String env = System.getenv("MOBBATTLE_CACHE"), prop = System.getProperty("mobbattle.cache");
        Path game = null;
        for (Path p = data.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("Saves"))) {
                game = p.resolve("MobBattleCache");
                break;
            }
        }
        Path windows = null;
        String appData = System.getenv("APPDATA");
        if (appData != null && Files.isDirectory(Path.of(appData, "Hytale", "UserData"))) {
            windows = Path.of(appData, "Hytale", "UserData", "MobBattleCache");
        }
        String home = System.getProperty("user.home");
        return new Path[] {
                env != null && !env.isBlank() ? Path.of(env) : null,
                prop != null && !prop.isBlank() ? Path.of(prop) : null,
                game, windows,
                home != null ? Path.of(home, ".cache", "mobbattle") : null,
        };
    }

    private static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, ".probe", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The world's old per-world cache is left alone (not migrated: its files are redrawn into the shared folder only
     * when missing there, and old ones held stale crops); its size is logged so it can be deleted by hand.
     */
    private static void noteOldFolder(Path old, Path chosen) {
        if (old.equals(chosen) || !Files.isDirectory(old)) return;
        // its size is measured in the background: walking 120 MB on the boot thread added ~2 s to every server start
        java.util.concurrent.CompletableFuture.runAsync(() -> MobBattlePlugin.log("Picture cache: the old per-world folder "
                + old + " is no longer used (" + MobBattlePlugin.size(MobBattlePlugin.sizeOf(old)) + "); it can be deleted."));
    }

    // ---- writes: temp file + rename, so another process never reads half a file ----

    /** One file is never written by two threads of this process at once (striped by path). */
    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    /** Tries of the final rename: on Windows it fails while the target is open elsewhere (read, registered, another process). */
    private static final int MOVE_TRIES = 6;

    static void write(Path file, byte[] bytes) throws IOException {
        Object lock = LOCKS[Math.floorMod(file.toAbsolutePath().normalize().toString().hashCode(), LOCKS.length)];
        synchronized (lock) {
            // already there with these bytes (another thread or process made it): nothing to replace
            if (Files.exists(file) && sameBytes(file, bytes)) return;
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + "." + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".tmp");
            try {
                Files.write(tmp, bytes);
                for (int i = 1; ; i++) {
                    try {
                        try {
                            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        } catch (AtomicMoveNotSupportedException e) {
                            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                        }
                        return;
                    } catch (java.nio.file.FileSystemException e) {
                        // AccessDenied: the target is open without delete sharing (being read): wait and try again;
                        // in the end a target that exists with our bytes (another process won) is fine too
                        if (i >= MOVE_TRIES) {
                            if (Files.exists(file) && sameBytes(file, bytes)) return;
                            throw e;
                        }
                        try {
                            Thread.sleep(40L * i);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw e;
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }

    private static boolean sameBytes(Path file, byte[] bytes) {
        try {
            return Files.size(file) == bytes.length && java.util.Arrays.equals(Files.readAllBytes(file), bytes);
        } catch (IOException e) {
            return false;
        }
    }

    /** Deletes the temp files a stopped server left behind (older than ten minutes: a running one may be writing). */
    static int cleanTemps(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        long limit = System.currentTimeMillis() - 10 * 60_000L;
        int[] n = {0};
        try (var files = Files.walk(dir)) {
            // only ours ("<file>.<hex>.tmp" from write, ".probe….tmp" from writable): MOBBATTLE_CACHE may point at a
            // folder other programs use too (audit 2026-10-17)
            files.filter(f -> f.getFileName().toString().matches(".+[.][0-9a-f]{1,16}[.]tmp|[.]probe.*[.]tmp")
                            && f.toFile().lastModified() < limit)
                    .forEach(f -> {
                        try {
                            if (Files.deleteIfExists(f)) n[0]++;
                        } catch (IOException ignored) {
                            // next time
                        }
                    });
        } catch (IOException | java.io.UncheckedIOException e) {
            MobBattlePlugin.log("Picture cache: temp files not cleaned: " + e);
        }
        return n[0];
    }

    static void writePng(Path file, BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // a memory-cached stream (a plain OutputStream made ImageIO use a temp file in %TEMP% per picture)
        try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(img, "png", stream)) throw new IOException("no PNG writer");
        }
        write(file, out.toByteArray());
    }

    static void writeText(Path file, String text) throws IOException {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
