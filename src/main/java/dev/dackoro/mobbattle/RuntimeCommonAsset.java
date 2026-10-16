package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.asset.common.CommonAsset;

import java.util.concurrent.CompletableFuture;

/**
 * A common asset made at runtime (PNG bytes in memory). The base class keeps only a WeakReference to the future, so the
 * bytes stay here (as EyeRecolor's and MorphHologram's own copies).
 */
final class RuntimeCommonAsset extends CommonAsset {
    private final byte[] bytes;

    RuntimeCommonAsset(String name, byte[] bytes) {
        super(name, bytes);
        this.bytes = bytes;
    }

    @Override
    protected CompletableFuture<byte[]> getBlob0() {
        return CompletableFuture.completedFuture(bytes);
    }
}
