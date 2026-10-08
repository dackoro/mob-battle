package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;

/** Effect Wand settings: the quick-pick list and case-insensitive effect lookup. */
public final class EffectWand {

    /** Effects the right click cycles through (vanilla Server/Entity/Effects). */
    public static final String[] PRESETS = {
            "Burn", "Poison_T2", "Freeze", "Slow", "Stun", "Root", "Vulnerable",
            "Ability_Enrage_Buff", "Potion_Health_Regen", "Immune"
    };

    private EffectWand() {
    }

    /** The loaded effect whose ID matches {@code name} ignoring case (any mod's effects too), or null. */
    public static EntityEffect find(String name) {
        if (name == null) {
            return null;
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
