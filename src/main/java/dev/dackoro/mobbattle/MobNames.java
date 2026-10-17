package dev.dackoro.mobbattle;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.npc.NPCPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mob names as players see them (2026-10-16, user: the win title said "Goblin_Burner WINS!" while the VS said "GOBLIN
 * BURNER"; show the game's own name, not the ID). A role's name is its NameTranslationKey (role JSON chain:
 * "server.npcRoles.Goblin_Burner.name" = "Goblin Burner" / "Quemador Goblin"); a role without one reads as its ID
 * with spaces ("Test Dummy"). Anything that is not a role (a player's name, a team name) stays as it is.
 * <p>
 * Battle code keeps passing role IDs around (logs, keys, commands, searches use them); only the text a player sees
 * goes through here: {@link #message} for chat and titles (the client translates it into each player's language),
 * {@link #text} for HUD labels built for one viewer.
 */
final class MobNames {

    private MobNames() {
    }

    /** Role ID -> full translation key that exists ("" = none). */
    private static final Map<String, String> KEYS = new ConcurrentHashMap<>();

    static void clear() {
        KEYS.clear();
    }

    /** The name in chat and titles: the game's translation of a role, else the ID with spaces, else the text itself. */
    static Message message(String idOrName) {
        if (idOrName == null) return Message.raw("?");
        String key = keyOf(idOrName);
        if (!key.isEmpty()) return Message.translation(key);
        return Message.raw(isRole(idOrName) ? pretty(idOrName) : idOrName);
    }

    /** The name in {@code lang} (a player's language, null = English) for HUD text. */
    static String text(String idOrName, String lang) {
        if (idOrName == null) return "?";
        String key = keyOf(idOrName);
        if (!key.isEmpty()) {
            String v = translated(lang, key);
            if (v.isEmpty() && lang != null) v = translated(null, key);
            if (!v.isEmpty()) return v;
        }
        return isRole(idOrName) ? pretty(idOrName) : idOrName;
    }

    /** As {@link #text} for a viewer. */
    static String text(String idOrName, com.hypixel.hytale.server.core.universe.PlayerRef viewer) {
        return text(idOrName, viewer != null ? viewer.getLanguage() : null);
    }

    /**
     * As {@link #text} for a HUD built once and shown to several players (scoreboard, timer box, focus card, VS
     * screen): the language of the first player online (a local or small server mostly has one), else English.
     */
    static String shared(String idOrName) {
        String lang = null;
        try {
            for (var p : com.hypixel.hytale.server.core.universe.Universe.get().getPlayers()) {
                if (p != null && p.isValid() && p.getLanguage() != null) {
                    lang = p.getLanguage();
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return text(idOrName, lang);
    }

    /**
     * For pick lists that show the ID (several roles share one game name: Goblin_Burner, Dungeon_Goblin_Burner and
     * Coffer_Goblin_Burner are all "Goblin Burner"): " (Game Name)" when the game's name differs from the ID, else "".
     */
    static String alsoKnownAs(String role) {
        return alsoKnownAs(role, null);
    }

    /** As {@link #alsoKnownAs(String)} in {@code lang} (null = the shared language). */
    static String alsoKnownAs(String role, String lang) {
        String name = lang == null ? shared(role) : text(role, lang);
        return name.equalsIgnoreCase(pretty(role)) ? "" : " (" + name + ")";
    }

    /** "Goblin_Burner" -> "Goblin Burner". */
    static String pretty(String id) {
        return id == null ? "?" : id.replace('_', ' ').trim();
    }

    static boolean isRole(String id) {
        try {
            NPCPlugin npc = NPCPlugin.get();
            return npc != null && id != null && npc.hasRoleName(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The role's translation key when the game has a text for it, else "". */
    private static String keyOf(String id) {
        return KEYS.computeIfAbsent(id, r -> {
            if (!isRole(r)) return "";
            String key = PickLists.nameKeyOf(r);
            if (key.isEmpty()) key = "server.npcRoles." + r + ".name";
            if (!key.startsWith("server.")) key = "server." + key;
            return translated(null, key).isEmpty() ? "" : key;
        });
    }

    /** A translation as the game's I18nModule holds it ("" if none); null lang = English. */
    private static String translated(String lang, String key) {
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n == null) return "";
            String l = lang == null || lang.isEmpty() ? "en-US" : lang;
            String v = i18n.getMessage(l, key);
            if (v == null && key.startsWith("server.")) v = i18n.getMessage(l, key.substring("server.".length()));
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
