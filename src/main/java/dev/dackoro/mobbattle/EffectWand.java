package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;

/** Effect Wand settings: the quick-pick list and case-insensitive effect lookup. */
public final class EffectWand {

    /** Effects the right click cycles through (vanilla Server/Entity/Effects). */
    public static final String[] PRESETS = {
            "Burn", "Poison_T2", "Freeze", "Slow", "Stun", "Root", "Vulnerable",
            "Ability_Enrage_Buff", "Potion_Health_Regen", "Immune", "jade", "shimmer"
    };

    /** Default colors of the look shortcuts: jade is green, shimmer purple. */
    static final String JADE_COLOR = "Green";
    static final String SHIMMER_COLOR = "Purple";

    private EffectWand() {
    }

    /**
     * The loaded effect whose ID matches {@code name} ignoring case (any mod's effects too), or null. Shortcuts:
     * "jade" / "jade:&lt;color&gt;" = MobBattle_Look_JadeAppear_&lt;Color&gt; (grows, then stays), "shimmer" /
     * "shimmer:&lt;color&gt;" = MobBattle_Look_Shimmer_&lt;Color&gt;, palette colors, Green and Purple by default.
     */
    public static EntityEffect find(String name) {
        if (name == null) {
            return null;
        }
        String look = lookId(name);
        if (look != null) {
            name = look;
        }
        var map = EntityEffect.getAssetMap().getAssetMap();
        EntityEffect exact = map.get(name);
        if (exact != null) {
            return exact;
        }
        for (var e : map.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** The look id of a "jade[:color]" / "shimmer[:color]" shortcut, or null when {@code name} is not one. */
    private static String lookId(String name) {
        String[] parts = name.split(":", 2);
        String kind = parts[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!kind.equals("jade") && !kind.equals("shimmer")) return null;
        boolean jade = kind.equals("jade");
        String color = jade ? JADE_COLOR : SHIMMER_COLOR;
        if (parts.length == 2) {
            color = null;
            String asked = parts[1].trim();
            // "gold" is how the crowns name it; the palette calls it Yellow.
            if (asked.equalsIgnoreCase("gold")) asked = "Yellow";
            for (String c : Teams.PALETTE.keySet()) {
                if (c.equalsIgnoreCase(asked)) color = c;
            }
            if (color == null) return null;
        }
        return (jade ? "MobBattle_Look_JadeAppear_" : "MobBattle_Look_Shimmer_") + color;
    }

    /** The effects of a setting: one id, or a chain "A>B>C" (each given when the previous one ends). Unknown = null. */
    public static java.util.List<EntityEffect> chain(String setting) {
        java.util.List<EntityEffect> list = new java.util.ArrayList<>();
        if (setting == null) return null;
        for (String part : setting.split(">")) {
            EntityEffect e = find(part.trim());
            if (e == null) return null;
            list.add(e);
        }
        return list;
    }

    /** Chat color per quick-list effect (the effect's own color), for the Effect Wand's messages. */
    private static final java.util.Map<String, String> COLORS = java.util.Map.ofEntries(
            java.util.Map.entry("burn", "#ff8a3d"),
            java.util.Map.entry("poison_t2", "#8cff5a"),
            java.util.Map.entry("freeze", "#9be8ff"),
            java.util.Map.entry("slow", "#a3b4ff"),
            java.util.Map.entry("stun", "#ffe45c"),
            java.util.Map.entry("root", "#d0a060"),
            java.util.Map.entry("vulnerable", "#ff6b6b"),
            java.util.Map.entry("ability_enrage_buff", "#ff4040"),
            java.util.Map.entry("potion_health_regen", "#ff8ac8"),
            java.util.Map.entry("immune", "#ffd94a"));

    /**
     * The chat color for an effect setting: a jade/shimmer shortcut in its palette color, a quick-list effect in its
     * own color, anything else (other mods' effects, chains) light grey. Only the first link of a chain counts.
     */
    static String colorOf(String setting) {
        if (setting == null) return "#dddddd";
        String first = setting.split(">", 2)[0].trim();
        String[] parts = first.split(":", 2);
        String kind = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (kind.equals("jade") || kind.equals("shimmer")) {
            String color = kind.equals("jade") ? JADE_COLOR : SHIMMER_COLOR;
            if (parts.length == 2) {
                for (String c : Teams.PALETTE.keySet()) {
                    if (c.equalsIgnoreCase(parts[1].trim()) || (parts[1].trim().equalsIgnoreCase("gold") && c.equals("Yellow"))) color = c;
                }
            }
            String hex = Teams.PALETTE.get(color);
            // Black would vanish on the dark chat background.
            return hex == null || color.equals("Black") ? "#bbbbbb" : hex;
        }
        String known = COLORS.get(kind);
        if (known != null) return known;
        String tint = tintOf(find(first));
        return tint != null ? tint : "#dddddd";
    }

    /**
     * Palette color of the burst on a mob that gets {@code effect} (Fx/Effect/MobBattle_Fx_Effect_<Color>,
     * scripts/gen-effectburst.py; 2026-10-13, playtest: chat and wave took the effect's color, the burst stayed purple):
     * a jade/shimmer look's own color, else the palette color nearest to its chat color (no tint = light grey).
     */
    static String burstColor(EntityEffect effect) {
        String id = effect.getId();
        if (id.startsWith("MobBattle_") && (id.contains("Jade") || id.contains("Shimmer"))) {
            String c = LookKeeper.colorOf(id);
            if (c != null && Teams.PALETTE.containsKey(c)) return c;
        }
        return Teams.nearestPalette(colorOf(id));
    }

    /**
     * The tint an effect paints the entity with (its ApplicationEffects EntityTopTint, else EntityBottomTint), brightened
     * so its strongest channel is at least 0xC0 to read on the chat, or null if it has none (2026-10-13, playtest: other
     * mods' effects were all grey). The fields have no getter: read by reflection once per effect.
     */
    private static String tintOf(EntityEffect effect) {
        if (effect == null || effect.getApplicationEffects() == null) return null;
        return TINTS.computeIfAbsent(effect.getId(), id -> {
            Object fx = effect.getApplicationEffects();
            for (String field : new String[] {"entityTopTint", "entityBottomTint"}) {
                try {
                    java.lang.reflect.Field f = fx.getClass().getDeclaredField(field);
                    f.setAccessible(true);
                    com.hypixel.hytale.protocol.Color c = (com.hypixel.hytale.protocol.Color) f.get(fx);
                    if (c == null) continue;
                    int r = c.red & 255, g = c.green & 255, b = c.blue & 255;
                    int max = Math.max(r, Math.max(g, b));
                    if (max == 0) continue;
                    double k = max < 0xC0 ? 0xC0 / (double) max : 1;
                    return String.format("#%02x%02x%02x", (int) Math.min(255, r * k), (int) Math.min(255, g * k), (int) Math.min(255, b * k));
                } catch (ReflectiveOperationException | ClassCastException e) {
                    continue; // the other field may still be there
                }
            }
            return "";
        }).transform(t -> t.isEmpty() ? null : t);
    }

    /** Effect id -> its chat tint ("" = none). One entry per effect id ever shown, tiny. */
    private static final java.util.Map<String, String> TINTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Next preset after the current effect. */
    public static String next(String current) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i].equalsIgnoreCase(current)) {
                return PRESETS[(i + 1) % PRESETS.length];
            }
        }
        return PRESETS[0];
    }
}
