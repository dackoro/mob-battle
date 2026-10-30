package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * /mobbattle test statics [all] (2026-10-29, leak hunt): every static Map/Collection of the mod's classes that holds
 * something, with how many of its keys/elements are Refs no longer valid, Stores already shut down or PlayerRefs of
 * players gone. Run it after battles have ended: per-battle state should be back to (near) empty, and any "stale"
 * count is a leak candidate. Classes are listed from the plugin jar; reading a field initializes its class (all of
 * them run sooner or later anyway). Dev tool, English only.
 */
final class StaticsReport {
    private StaticsReport() {
    }

    private record Row(String name, int size, int stale, String sample) {
    }

    static void run(CommandSender to, boolean all) {
        List<Row> rows = new ArrayList<>();
        int classes = 0;
        try {
            var src = StaticsReport.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                TestTools.say(to, "statics: no code source.", "#ff8080");
                return;
            }
            java.io.File file = new java.io.File(src.getLocation().toURI());
            String pkg = StaticsReport.class.getPackageName().replace('.', '/') + "/";
            ClassLoader loader = StaticsReport.class.getClassLoader();
            try (JarFile jar = new JarFile(file)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(pkg) || !name.endsWith(".class")) continue;
                    String cls = name.substring(0, name.length() - 6).replace('/', '.');
                    Class<?> c;
                    try {
                        c = Class.forName(cls, false, loader);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (c.isInterface() || c.isAnnotation()) continue;
                    classes++;
                    Field[] fields;
                    try {
                        fields = c.getDeclaredFields();
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Field f : fields) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (!Map.class.isAssignableFrom(f.getType()) && !Collection.class.isAssignableFrom(f.getType())
                                && !Object.class.equals(f.getType())) continue;
                        Object v;
                        try {
                            f.setAccessible(true);
                            v = f.get(null);
                        } catch (Throwable t) {
                            continue;
                        }
                        Row row = measure(c.getName().substring(c.getPackageName().length() + 1) + "." + f.getName(), v);
                        if (row != null && (all || row.size > 0)) rows.add(row);
                    }
                }
            }
        } catch (Throwable t) {
            TestTools.say(to, "statics: " + t, "#ff8080");
            return;
        }
        rows.sort((a, b) -> a.stale != b.stale ? Integer.compare(b.stale, a.stale) : Integer.compare(b.size, a.size));
        int stale = 0;
        for (Row r : rows) stale += r.stale;
        TestTools.say(to, "statics: " + rows.size() + " non-empty collections in " + classes + " classes, " + stale
                + " stale entries (invalid Ref, shut Store, offline PlayerRef).", stale > 0 ? "#ffd27a" : "#7dff8a");
        for (Row r : rows) {
            TestTools.say(to, "  " + r.name + " = " + r.size + (r.stale > 0 ? " (STALE " + r.stale + ")" : "")
                    + (r.sample != null ? " [" + r.sample + "]" : ""), r.stale > 0 ? "#ffd27a" : "#c8e6ff");
        }
    }

    private static Row measure(String name, Object v) {
        // a plain WeakHashMap cleans itself on every read (size too), which would change it under the world threads
        // that write it (audit 2026-10-29): not read
        if (v instanceof java.util.WeakHashMap<?, ?>) return new Row(name, 0, 0, "weak map, not read");
        if (v instanceof Map<?, ?> m) {
            List<Object> keys;
            List<Object> values;
            try {
                synchronized (m) {
                    keys = new ArrayList<>(m.keySet());
                    values = new ArrayList<>(m.values());
                }
            } catch (Throwable t) {
                return new Row(name, m.size(), 0, "unreadable");
            }
            int stale = 0;
            for (Object k : keys) if (stale(k)) stale++;
            for (Object val : values) if (stale(val)) stale++;
            return new Row(name, keys.size(), stale, sample(keys, values));
        }
        if (v instanceof Collection<?> c) {
            List<Object> items;
            try {
                synchronized (c) {
                    items = new ArrayList<>(c);
                }
            } catch (Throwable t) {
                return new Row(name, c.size(), 0, "unreadable");
            }
            int stale = 0;
            for (Object o : items) if (stale(o)) stale++;
            return new Row(name, items.size(), stale, sample(items, List.of()));
        }
        return null;
    }

    /** A Ref whose entity is gone, a Store whose world stopped, a PlayerRef of a player who left. */
    private static boolean stale(Object o) {
        try {
            if (o instanceof Ref<?> r) return !r.isValid();
            if (o instanceof Store<?> s) return s.isShutdown();
            if (o instanceof PlayerRef p) return !p.isValid();
        } catch (Throwable ignored) {
            // unreadable: not counted
        }
        return false;
    }

    private static String sample(List<Object> keys, List<Object> values) {
        Object k = keys.isEmpty() ? null : keys.get(0);
        Object v = values.isEmpty() ? null : values.get(0);
        if (k == null) return null;
        return k.getClass().getSimpleName() + (v != null ? " -> " + v.getClass().getSimpleName() : "");
    }
}
