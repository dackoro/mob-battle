package dev.dackoro.mobbattle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the picture run is drawing now (thumbnails, then VS pictures), for /mobbattle pictures (2026-10-17, user:
 * progress in chat while it redraws). Written by the run's threads, read by the command's progress timer.
 */
final class PictureProgress {

    /** "thumbnails", "vs", or "" when nothing is being drawn. */
    private static volatile String phase = "";
    private static final AtomicInteger DONE = new AtomicInteger();
    private static final AtomicInteger TOTAL = new AtomicInteger();

    private PictureProgress() {
    }

    static void start(String name, int total) {
        TOTAL.set(total);
        DONE.set(0);
        phase = name;
    }

    static void step() {
        DONE.incrementAndGet();
    }

    static void end() {
        phase = "";
    }

    record Now(String phase, int done, int total) {
        int percent() {
            return total <= 0 ? 100 : Math.min(100, done * 100 / total);
        }
    }

    static Now now() {
        return new Now(phase, DONE.get(), TOTAL.get());
    }
}
