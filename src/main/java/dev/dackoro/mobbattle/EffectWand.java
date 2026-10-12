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
