package dev.dackoro.mobbattle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Saved summon lists (2026-10-16, user: "save my list 1, 2 with counts, colors, teams"): the whole team and summon list
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
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                p.store(out, "Mob Battle summon list " + name + " (/mobbattle summon load " + name + ")");
            }
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
        List<String> out = new ArrayList<>();
        Path dir = dir();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString())
                    .filter(f -> f.endsWith(".properties"))
                    .map(f -> f.substring(0, f.length() - ".properties".length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Letters, digits, - and _, up to 32 (same as preset names). */
    static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,32}");
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
