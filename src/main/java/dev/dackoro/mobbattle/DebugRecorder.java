package dev.dackoro.mobbattle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Debug recorder (/mobbattle debugRecord, default off): with it on, every plugin log line and every [debug] decision
 * (even with debugLog off) is kept in memory for the last {@link #KEEP_MS}, without touching the disk.
 * /mobbattle debugDump writes them to debug/record_&lt;time&gt;.txt, so a bug noticed late still has what led to it
 * (user, 2026-10-12: bugs showed up while the debug log was off). The setting is saved; the lines are not (memory only).
 */
final class DebugRecorder {

    /** How far back the recorder keeps lines. */
    static final long KEEP_MS = 5 * 60_000;
    /** Hard cap, in case something logs in a loop. */
    private static final int MAX_LINES = 20_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private record Line(long at, String text) {
    }

    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();

    private DebugRecorder() {
    }

    /** Keeps {@code text} when the recorder is on. Any thread. */
    static void add(String text) {
        if (!FxSettings.debugRecord || text == null) return;
        long now = System.currentTimeMillis();
        synchronized (LINES) {
            LINES.addLast(new Line(now, text));
            trim(now);
        }
    }

    /** Drops everything (the recorder was turned off). */
    static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    private static void trim(long now) {
        while (!LINES.isEmpty() && (LINES.size() > MAX_LINES || now - LINES.peekFirst().at() > KEEP_MS)) {
            LINES.removeFirst();
        }
    }

    /** Result of a dump: the file name (null if not written), the line count, or why it failed. */
    record Dump(String file, int lines, String error) {
    }

    /** Writes the kept lines to debug/record_&lt;time&gt;.txt in the plugin data folder. Any thread. */
    static Dump dump(String by) {
        List<Line> copy;
        long now = System.currentTimeMillis();
        synchronized (LINES) {
            trim(now);
            copy = new ArrayList<>(LINES);
        }
        if (copy.isEmpty()) return new Dump(null, 0, FxSettings.debugRecord ? "nothing recorded yet" : "the recorder is off (debugRecord on)");
        Path dir = FxSettings.dataDirectory();
        if (dir == null) return new Dump(null, copy.size(), "the plugin data folder is not known yet");
        String name = "record_" + LocalDateTime.now().format(STAMP) + ".txt";
        StringBuilder sb = new StringBuilder();
        sb.append("Mob Battle debug record, last ").append(KEEP_MS / 60_000).append(" min, ").append(copy.size())
                .append(" lines, saved by ").append(by).append('\n').append("Settings:\n");
        for (String s : FxSettings.describeLines()) sb.append("  ").append(s).append('\n');
        sb.append('\n');
        for (Line l : copy) {
            sb.append(LocalDateTime.ofInstant(Instant.ofEpochMilli(l.at()), ZoneId.systemDefault()).format(TIME))
                    .append(' ').append(l.text()).append('\n');
        }
        try {
            Path folder = dir.resolve("debug");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(name), sb.toString(), StandardCharsets.UTF_8);
            return new Dump(name, copy.size(), null);
        } catch (IOException | RuntimeException ex) {
            return new Dump(null, copy.size(), ex.toString());
        }
    }
}
