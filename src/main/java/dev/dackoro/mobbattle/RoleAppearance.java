package dev.dackoro.mobbattle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.npc.NPCPlugin;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The model (ModelAsset id) a role uses, read from its role JSON like {@link RoleAttack}: "Modify.Appearance"
 * (variants), a plain "Appearance", the referenced template, then "Parameters.Appearance.Value". Null if unknown.
 */
final class RoleAppearance {

    private static final Map<String, Optional<String>> CACHE = new ConcurrentHashMap<>();

    private RoleAppearance() {
    }

    static String of(String role) {
        if (role == null) return null;
        return CACHE.computeIfAbsent(role, n -> Optional.ofNullable(resolve(n, 0))).orElse(null);
    }

    private static String resolve(String name, int depth) {
        if (name == null || depth > 8) return null;
        try {
            var bm = NPCPlugin.get().getBuilderManager();
            int index = bm.getIndex(name);
            var info = index >= 0 ? bm.tryGetBuilderInfo(index) : null;
            if (info == null || info.getPath() == null) return null;
            JsonObject json = JsonParser.parseString(Files.readString(info.getPath())).getAsJsonObject();
            if (json.has("Modify") && json.getAsJsonObject("Modify").has("Appearance")) {
                String s = text(json.getAsJsonObject("Modify").get("Appearance"));
                if (s != null) return s;
            }
            String own = json.has("Appearance") ? text(json.get("Appearance")) : null;
            if (own != null) return own;
            if (json.has("Reference")) {
                String r = resolve(json.get("Reference").getAsString(), depth + 1);
                if (r != null) return r;
            }
            if (json.has("Parameters") && json.getAsJsonObject("Parameters").has("Appearance")) {
                JsonElement p = json.getAsJsonObject("Parameters").get("Appearance");
                return text(p.isJsonObject() && p.getAsJsonObject().has("Value") ? p.getAsJsonObject().get("Value") : p);
            }
        } catch (Exception ex) {
            MobBattlePlugin.debug("could not read the appearance of role " + name + ": " + ex);
        }
        return null;
    }

    /** A plain non-empty string; { "Compute": ... } and the rest are null. */
    private static String text(JsonElement v) {
        if (v == null || !v.isJsonPrimitive()) return null;
        String s = v.getAsString();
        return s.isBlank() ? null : s;
    }

    /** Assets reloaded: forget. */
    static void clear() {
        CACHE.clear();
    }
}
