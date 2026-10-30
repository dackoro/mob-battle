package dev.dackoro.mobbattle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Saved summon lists (2026-10-16, playtest: "save my list 1, 2 with counts, colors, teams"): the whole team and summon list
 * (every {@link Presets#isListKey} key: summon.list, summon.teams, teams.*) in
 * &lt;plugin data dir&gt;/summonlists/&lt;name&gt;.properties. Loading one replaces the current lists the way the panel's Undo
 * does (teams first, then the summon list), so team names and colors come back with it. Presets never hold these keys,
 * so the two don't mix.
 */
final class SummonLists {

    private SummonLists() {
    }

    /** Saves the current team and summon lists as {@code name}. Null if done, else the lang key of why not. */
    static String save(String name) {
        Path file = fileOf(name);
        if (file == null) return validName(name) ? "cmd.summon.saved.noload" : "cmd.summon.saved.badname";
        Properties all = FxSettings.toProperties();
        Properties p = new Properties();
        for (String k : all.stringPropertyNames()) if (Presets.isListKey(k)) p.setProperty(k, all.getProperty(k));
        try {
            FxSettings.storeAtomically(file, p, "Mob Battle summon list " + name + " (/mobbattle summon load " + name + ")");
        } catch (IOException e) {
            MobBattlePlugin.log("Could not save summon list " + name + ": " + e);
            return "cmd.summon.saved.ioerror";
        }
        return null;
    }

    /** Puts a saved list in place of the current one. Null if done, else the lang key of why not. */
    static String load(String name) {
        Path file = fileOf(name);
        if (file == null || !Files.isRegularFile(file)) return "cmd.summon.saved.none";
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            MobBattlePlugin.log("Could not read summon list " + name + ": " + e);
            return "cmd.summon.saved.ioerror";
        }
        ConfigPage.restoreLists(p);
        return null;
    }

    /** Deletes a saved list. Null if done, else the lang key of why not. */
    static String delete(String name) {
        Path file = fileOf(name);
        try {
            if (file == null || !Files.deleteIfExists(file)) return "cmd.summon.saved.none";
        } catch (IOException e) {
            return "cmd.summon.saved.ioerror";
        }
        return null;
    }

    /** Names of the saved lists, sorted. */
    static List<String> saved() {
        return saved(false);
    }

    /** The saved lists by name, or newest first ({@code newestFirst}: last saved on top; panel, 2026-10-29). */
    static List<String> saved(boolean newestFirst) {
        List<String> out = new ArrayList<>();
        Path dir = dir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        java.util.Map<String, Long> times = new java.util.HashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".properties")).forEach(f -> {
                String n = f.getFileName().toString();
                n = n.substring(0, n.length() - ".properties".length());
                long t = 0;
                try {
                    t = Files.getLastModifiedTime(f).toMillis();
                } catch (IOException ignored) {
                }
                times.put(n, t);
            });
        } catch (IOException ignored) {
        }
        out.addAll(times.keySet());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        if (newestFirst) out.sort((a, b) -> Long.compare(times.get(b), times.get(a)));
        return out;
    }

    /** Letters, digits, - and _, up to 32 (same as preset names). */
    static boolean validName(String name) {
        // same rule as presets: no Windows device names (CON, NUL, COM1...), which no file can have there
        return Presets.validName(name);
    }

    private static Path dir() {
        Path data = FxSettings.dataDirectory();
        return data == null ? null : data.resolve("summonlists");
    }

    private static Path fileOf(String name) {
        Path dir = dir();
        return dir == null || !validName(name) ? null : dir.resolve(name + ".properties");
    }
}
