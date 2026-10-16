package dev.dackoro.mobbattle;

import com.hypixel.hytale.protocol.PlayerSkin;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Player skins for the VS screen's player pictures (2026-10-16, user: players in the VS with their real skin, cached
 * per skin): a skin's id is a short hash of its parts, and each skin seen is kept as a small properties file
 * (thumbnails/vs/players/&lt;id&gt;.properties, one line per PlayerSkin part) so its pictures can be drawn again after a
 * restart or a render change without the player online.
 */
final class PlayerSkins {

    /** The PlayerSkin parts (its public String fields: bodyCharacteristic, haircut, overtop, ...), in name order. */
    private static final Field[] PARTS = Arrays.stream(PlayerSkin.class.getFields())
            .filter(f -> f.getType() == String.class && !Modifier.isStatic(f.getModifiers()))
            .sorted(Comparator.comparing(Field::getName))
            .toArray(Field[]::new);

    private PlayerSkins() {
    }

    /** 12 hex characters of the SHA-1 of every part (same skin = same id). */
    static String idOf(PlayerSkin skin) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            for (Field f : PARTS) {
                Object v = f.get(skin);
                sha.update((f.getName() + "=" + (v == null ? "" : v) + ";").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 12);
        } catch (Exception e) {
            return Integer.toHexString(skin.hashCode());
        }
    }

    static void save(Path dir, String id, PlayerSkin skin) throws IOException {
        Properties p = new Properties();
        for (Field f : PARTS) {
            try {
                Object v = f.get(skin);
                if (v != null) p.setProperty(f.getName(), v.toString());
            } catch (IllegalAccessException ignored) {
                // public fields
            }
        }
        Files.createDirectories(dir);
        try (Writer w = Files.newBufferedWriter(dir.resolve(id + ".properties"), StandardCharsets.UTF_8)) {
            p.store(w, "Mob Battle VS screen: a player skin seen on this server");
        }
    }

    /** The saved skin, or null. */
    static PlayerSkin load(Path dir, String id) {
        Path file = dir.resolve(id + ".properties");
        if (!Files.exists(file)) return null;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            return null;
        }
        PlayerSkin skin = new PlayerSkin();
        for (Field f : PARTS) {
            try {
                f.set(skin, p.getProperty(f.getName()));
            } catch (IllegalAccessException ignored) {
                // public fields
            }
        }
        return skin;
    }

    /** Saved skin ids, newest first, at most {@code max}. */
    static List<String> saved(Path dir, int max) {
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparingLong((Path f) -> f.toFile().lastModified()).reversed())
                    .limit(max)
                    .forEach(f -> {
                        String n = f.getFileName().toString();
                        out.add(n.substring(0, n.length() - ".properties".length()));
                    });
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: player skins not listed: " + e);
        }
        return out;
    }

    /** Marks a skin as seen now (its file's date), so the newest stay within the boot limit. */
    static void touch(Path dir, String id) {
        try {
            Path f = dir.resolve(id + ".properties");
            if (Files.exists(f)) Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // only the order
        }
    }
}
