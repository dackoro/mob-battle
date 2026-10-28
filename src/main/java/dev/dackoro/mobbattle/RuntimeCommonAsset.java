package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;

import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * A common asset made at runtime (PNG bytes in memory). The base class keeps only a WeakReference to the future, so the
 * bytes stay here (as EyeRecolor's and MorphHologram's own copies).
 *
 * With a file (pictures from the picture cache) the bytes are only held softly: they are read again from disk when a
 * client asks after the GC dropped them (only at join), so hundreds of VS pictures don't sit in the heap all session.
 * The server joins on this future while sending a joining player's assets, so it never fails: a file that is gone or
 * changed since (another process redrew it) gets a blank picture, logged once.
 */
final class RuntimeCommonAsset extends CommonAsset {
    /** 1x1 transparent PNG. */
    private static final byte[] BLANK = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    private final byte[] bytes;
    private final Path file;
    private SoftReference<byte[]> soft;
    private boolean warned;

    RuntimeCommonAsset(String name, byte[] bytes) {
        super(name, bytes);
        this.bytes = bytes;
        this.file = null;
    }

    /** {@code bytes}: the file's content as read now (hashed here, then only held softly). */
    RuntimeCommonAsset(String name, byte[] bytes, Path file) {
        super(name, bytes);
        this.bytes = null;
        this.file = file;
        this.soft = new SoftReference<>(bytes);
    }

    @Override
    protected CompletableFuture<byte[]> getBlob0() {
        if (bytes != null) return CompletableFuture.completedFuture(bytes);
        return CompletableFuture.completedFuture(fromDisk());
    }

    private synchronized byte[] fromDisk() {
        byte[] held = soft.get();
        if (held != null) return held;
        try {
            byte[] read = Files.readAllBytes(file);
            if (CommonAsset.hash(read).equals(getHash())) {
                soft = new SoftReference<>(read);
                return read;
            }
            warn("changed on disk");
        } catch (Exception e) {
            warn(e.toString());
        }
        return BLANK;
    }

    private void warn(String why) {
        if (warned) return;
        warned = true;
        MobBattlePlugin.log("Picture " + getName() + " " + why + ": sent blank until the next restart.");
    }
}
