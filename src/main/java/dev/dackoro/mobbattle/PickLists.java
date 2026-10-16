package dev.dackoro.mobbattle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.npc.NPCPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The settings panel's long pick lists (summon mobs, Morph Wand models, Effect Wand effects) with what their filters
 * need (2026-10-13, user: filter by mod and by kind of mob instead of a "[mod]" tag on every name):
 * <ul>
 *   <li>each entry's mod (null = the game's own assets), from the asset pack its file lives in;</li>
 *   <li>for mobs, a {@link Kind} read from the role JSON and the templates it references (attack, default attitude
 *   to players, invulnerable). A guess from the definitions, not a test: a mod's odd role can land in the wrong kind.</li>
 * </ul>
 * Built once, the first time a list is asked for, and kept until the game reloads those assets: the NPC builder's
 * reload listener (a role file changed), LoadedAssetsEvent / RemovedAssetsEvent for models and effects (registered in
 * MobBattlePlugin), /mobbattle reload (the panel's Reload button), or a different number of entries. Opening the panel
 * never scans again (user: "que cargue una única vez"; first build of 887 roles took 53 ms). Any thread; a rebuild just
 * replaces the list.
 */
final class PickLists {

    /** What a mob does, for the panel's Type filter. */
    enum Kind {
        FIGHTS("Fighters"), DEFENDS("Defend only"), NO_ATTACK("No attack"), AVOID("Avoid");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** One pick: its id, its label ("Category - Name"), its mod (null = the game's) and, for mobs, its kind. */
    record Entry(String value, String label, String mod, Kind kind) {
    }

    /** Filter values: every mod / the game's own assets. */
    static final String ALL = "all";
    static final String HYTALE = "hytale";
    /** Marks a modded entry while every mod is shown. */
    // "*", not a star: the game's font has no ★ and drew "?" (user, 2026-10-16).
    private static final String MOD_MARK = "* ";

    private record Cached(int signature, List<Entry> entries) {
    }

    private static volatile Cached roles, models, effects;

    private PickLists() {
    }

    // ---- the lists ----

    private static volatile boolean roleListener;

    static List<Entry> roles() {
        List<String> names;
        NPCPlugin npc;
        try {
            npc = NPCPlugin.get();
            names = npc.getRoleTemplateNames(true);
            if (!roleListener) {
                // A role file reloaded (asset monitor, a mod's hot reload): build again next time.
                roleListener = true;
                npc.getBuilderManager().addBuilderReloadListener(info -> rolesChanged());
            }
        } catch (RuntimeException e) {
            // Seen once right after a restart (summon fill found no mobs, 2026-10-14): say why.
            MobBattlePlugin.log("Mob list unavailable: " + e);
            return List.of();
        }
        Cached c = roles;
        if (c != null && c.signature() == names.size()) return c.entries();
        long t0 = System.nanoTime();
        Map<String, JsonObject> files = new HashMap<>();
        List<Entry> out = new ArrayList<>();
        for (String r : names) {
            String category = null, mod = null;
            Path path = null;
            try {
                var info = npc.getRoleBuilderInfo(npc.getIndex(r));
                path = info != null ? info.getPath() : null;
                category = ConfigSchema.folderAfter(path, "Roles");
                mod = modOf(path);
            } catch (RuntimeException ignored) {
            }
            // The game's own test roles (Server/NPC/Roles/_Core/Tests/...): Static..Static4 are hitbox-test Trorks that
            // only play an animation (no walk, no attack), yet fell to the Fighters guess (user, 2026-10-13).
            boolean coreTest = path != null && path.toString().replace('\\', '/').contains("/_Core/Tests/");
            Kind seen = mod == null ? observed().get(r) : null;
            out.add(new Entry(r, ConfigSchema.categoryLabel(category, r, null), mod,
                    coreTest ? Kind.AVOID : seen != null ? seen : kindOf(r, files)));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label(), b.label()));
        List<Entry> done = List.copyOf(out);
        roles = new Cached(names.size(), done);
        MobBattlePlugin.log("Panel mob list built: " + done.size() + " roles in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        return done;
    }

    /** Role files reloaded: the mob list (and the attack cache) is built again when next asked for. */
    static void rolesChanged() {
        roles = null;
        MAX_HEALTH.clear();
        RoleAttack.clear();
        ChaseSupport.clear();
        Medium.rolesChanged();
    }

    /** Models or effects (re)loaded or removed (asset events): that list is built again when next asked for. */
    static void assetsChanged(Class<?> assetClass) {
        if (assetClass == ModelAsset.class) models = null;
        else if (assetClass == EntityEffect.class) effects = null;
    }

    /** /mobbattle reload: every list is built again when next asked for. */
    static void clearAll() {
        roles = null;
        models = null;
        effects = null;
        hostileGroups = null;
        RoleAttack.clear();
    }

    static List<Entry> models() {
        int size = assetCount(ModelAsset.getAssetMap());
        Cached c = models;
        if (c != null && c.signature() == size) return c.entries();
        List<Entry> out = fromAssets(ModelAsset.getAssetMap(), MorphWand.search("", 5000), "Models");
        models = new Cached(size, out);
        return out;
    }

    static List<Entry> effects() {
        int size = assetCount(EntityEffect.getAssetMap());
        Cached c = effects;
        if (c != null && c.signature() == size) return c.entries();
        Set<String> ids = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            ids.addAll(EntityEffect.getAssetMap().getAssetMap().keySet());
        } catch (RuntimeException ignored) {
        }
        // Look twins are LookKeeper's internal copies, not choices; nor are the wands' own feedback effects and the sized
        // crowns, lights and buff looks (audit 2026-10-14: ~1450 of the ~2000 entries sent with every Wands page).
        ids.removeIf(id -> id.startsWith("MobBattle_LookTwin_") || id.startsWith("MobBattle_Fx_")
                || id.startsWith("MobBattle_") && id.matches(".*_[SH]\\d+$"));
        Collections.addAll(ids, EffectWand.PRESETS);
        List<Entry> out = fromAssets(EntityEffect.getAssetMap(), ids, "Effects");
        effects = new Cached(size, out);
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int assetCount(com.hypixel.hytale.assetstore.AssetMap map) {
        try {
            return map.getAssetMap().size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Entry> fromAssets(com.hypixel.hytale.assetstore.AssetMap map, java.util.Collection<String> ids, String root) {
        List<Entry> out = new ArrayList<>();
        for (String id : ids) {
            String category = null, mod = null;
            try {
                Path path = map.getPath(id);
                if (path != null) category = ConfigSchema.folderAfter(path, root);
                mod = modName(map.getAssetPack(id));
            } catch (RuntimeException ignored) {
            }
            out.add(new Entry(id, ConfigSchema.categoryLabel(category, id, null), mod, null));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.label(), b.label()));
        return List.copyOf(out);
    }

    // ---- filtering ----

    /** The entries a mod / kind filter keeps, as dropdown options; modded ones get a mark while every mod shows. */
    static List<ConfigSchema.Opt> filter(List<Entry> all, String mod, String kind) {
        return filter(all, mod, kind, null);
    }

    /** Same, also kept to one group ({@link #groupOf}; {@link #OTHER} = entries with none; null / ALL = every group). */
    static List<ConfigSchema.Opt> filter(List<Entry> all, String mod, String kind, String group) {
        List<ConfigSchema.Opt> out = new ArrayList<>();
        boolean every = mod == null || ALL.equals(mod);
        // One group picked: the "Group - " part would say it again on every line (user, 2026-10-13), so it goes.
        boolean oneGroup = group != null && !ALL.equals(group) && !OTHER.equals(group);
        for (Entry e : all) {
            if (!keeps(e, mod, kind) || !inGroup(e, group)) continue;
            String g = oneGroup ? groupOf(e) : null;
            String label = g != null ? e.label().substring(g.length() + 3) : e.label();
            out.add(new ConfigSchema.Opt(e.value(), every && e.mod() != null ? MOD_MARK + label : label));
        }
        return out;
    }

    private static boolean keeps(Entry e, String mod, String kind) {
        boolean every = mod == null || ALL.equals(mod);
        if (!every && !(HYTALE.equals(mod) ? e.mod() == null : mod.equalsIgnoreCase(e.mod()))) return false;
        return kind == null || ALL.equals(kind) || (e.kind() != null && e.kind().name().equalsIgnoreCase(kind));
    }

    /** Group filter value for entries without a group. */
    static final String OTHER = "other";

    /** An entry's group: the "Category" of its "Category - Name" label (Aquatic, Undead...), or null. */
    static String groupOf(Entry e) {
        int i = e.label().indexOf(" - ");
        return i > 0 ? e.label().substring(0, i) : null;
    }

    private static boolean inGroup(Entry e, String group) {
        if (group == null || ALL.equals(group)) return true;
        String g = groupOf(e);
        return OTHER.equals(group) ? g == null : group.equalsIgnoreCase(g);
    }

    /**
     * Group filter choices (2026-10-13, user): every group, then each group with entries left by the mod and type
     * filters (so it depends on them), then "Other" when some have no group.
     */
    static List<ConfigSchema.Opt> groupChoices(List<Entry> all, String mod, String kind) {
        Set<String> groups = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        boolean other = false;
        for (Entry e : all) {
            if (!keeps(e, mod, kind)) continue;
            String g = groupOf(e);
            if (g == null) other = true;
            else groups.add(g);
        }
        List<ConfigSchema.Opt> out = new ArrayList<>();
        out.add(new ConfigSchema.Opt(ALL, "Every group"));
        for (String g : groups) out.add(new ConfigSchema.Opt(g, g));
        if (other) out.add(new ConfigSchema.Opt(OTHER, "Other"));
        return out;
    }

    /** Mod filter choices: every mod, the game's assets, then each mod that has entries in this list. */
    static List<ConfigSchema.Opt> modChoices(List<Entry> all) {
        Set<String> mods = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry e : all) if (e.mod() != null) mods.add(e.mod());
        List<ConfigSchema.Opt> out = new ArrayList<>();
        out.add(new ConfigSchema.Opt(ALL, "Every mod"));
        out.add(new ConfigSchema.Opt(HYTALE, "Hytale only"));
        for (String m : mods) out.add(new ConfigSchema.Opt(m, m));
        return out;
    }

    /** "fighters", "defend", "noattack", "avoid" (or the enum names), case-insensitive; null if none. */
    static Kind kindOf(String word) {
        if (word == null) return null;
        String w = word.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (w) {
            case "fighters", "fighter", "fights", "fight" -> Kind.FIGHTS;
            case "defend", "defends", "defendonly" -> Kind.DEFENDS;
            case "noattack", "passive" -> Kind.NO_ATTACK;
            case "avoid" -> Kind.AVOID;
            default -> null;
        };
    }

    /**
     * Type filter choices for mobs: every type, then only the types the mod filter has mobs of (user, 2026-10-16: a
     * mod with no passive mobs still offered "No attack", and the list below came out empty).
     */
    static List<ConfigSchema.Opt> kindChoices(List<Entry> all, String mod) {
        Set<Kind> present = java.util.EnumSet.noneOf(Kind.class);
        for (Entry e : all) if (e.kind() != null && keeps(e, mod, null)) present.add(e.kind());
        List<ConfigSchema.Opt> out = new ArrayList<>();
        out.add(new ConfigSchema.Opt(ALL, "Every type"));
        for (Kind k : Kind.values()) {
            if (present.contains(k)) out.add(new ConfigSchema.Opt(k.name().toLowerCase(Locale.ROOT), k.label));
        }
        return out;
    }

    // ---- where an asset comes from ----

    /** The mod a file belongs to (the asset pack holding it), or null for the game's own assets / unknown. */
    private static String modOf(Path path) {
        if (path == null) return null;
        try {
            var pack = AssetModule.get().findAssetPackForPath(path);
            if (pack == null || pack == AssetModule.get().getBaseAssetPack()) return null;
            return modName(pack.getName());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** "Group:Name" pack id -> "Name"; null for the game's packs ("Hytale..."). */
    private static String modName(String pack) {
        if (pack == null || pack.isBlank() || pack.startsWith("Hytale")) return null;
        int colon = pack.indexOf(':');
        return colon >= 0 ? pack.substring(colon + 1) : pack;
    }

    // ---- what a mob does: observed (game roles) ----

    private static volatile Map<String, Kind> observed;
    private static volatile Set<String> observedPassive = Set.of();

    /**
     * Kinds seen in /mobbattle test verify runs for the game's own roles (resource observed-kinds.properties, made by
     * scripts/gen-observed-kinds.py from tests/verify_*.txt, 2026-10-14): a role that never attacked even when ordered
     * at a copy of itself is No attack, one that did keeps its guessed Fighters/Defend only. The JSON guess below
     * called 63 roles that cannot attack (fish, birds, carriers, crystals) and ~45 that never do (cows, frogs, tamed
     * animals) fighters. Roles from mods are not in it and keep the guess. A value with ",passive" (e.g.
     * {@code Cow=DEFENDS,passive}) is a kicker: Template_Animal_Neutral with AttackWhenStartled only kicks an attacker
     * standing behind it within AttackRange and otherwise flees (user, 2026-10-14: cows kick players who hit them), so
     * it shows as Defend only yet never starts a fight.
     */
    /** A game role that never starts a fight, even ordered at a copy of itself (verify data; kickers included). */
    static boolean observedNoAttack(String role) {
        if (role == null) return false;
        observed();
        return observedPassive.contains(role);
    }

    private static Map<String, Kind> observed() {
        Map<String, Kind> m = observed;
        if (m != null) return m;
        Map<String, Kind> out = new HashMap<>();
        Set<String> passive = new HashSet<>();
        try (var in = PickLists.class.getResourceAsStream("observed-kinds.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                for (String role : p.stringPropertyNames()) {
                    String[] parts = p.getProperty(role).split(",");
                    try {
                        Kind kind = Kind.valueOf(parts[0].trim());
                        out.put(role, kind);
                        if (kind == Kind.NO_ATTACK || (parts.length > 1 && parts[1].trim().equalsIgnoreCase("passive"))) passive.add(role);
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        observedPassive = Set.copyOf(passive);
        observed = Map.copyOf(out);
        return observed;
    }

    // ---- what a mob does (role JSON) ----

    private static Kind kindOf(String role, Map<String, JsonObject> files) {
        String low = role.toLowerCase(Locale.ROOT);
        if (low.startsWith("test_dummy")) return Kind.NO_ATTACK;
        List<JsonObject> chain = chain(role, files);
        // Invulnerable roles never end a battle; temple, cinematic and test roles are scenery or tools.
        if (isTrue(lookup(chain, "Invulnerable")) || low.startsWith("temple_") || low.startsWith("cinematic_")
                || low.startsWith("test_") || low.contains("_spectator")) return Kind.AVOID;
        // No AI at all (the game's Empty_Role: one empty instruction, a mannequin): it would stand there, so Avoid
        // (user, 2026-10-13: it showed under Fighters through the "else" guess below).
        if (noInstructions(lookup(chain, "Instructions"))) return Kind.AVOID;
        JsonElement attack = lookup(chain, "Attack");
        if (attack != null && !isSet(attack)) return Kind.NO_ATTACK;
        JsonElement attitude = lookup(chain, "DefaultPlayerAttitude");
        String a = attitude != null && attitude.isJsonPrimitive() ? attitude.getAsString() : "";
        if (a.equalsIgnoreCase("Hostile")) return Kind.FIGHTS;
        // Neutral to players but hostile to other groups attacks them on its own: a Kweebec Razorleaf goes for Trorks
        // (user, 2026-10-13: it showed under Defend only). Prey animals have hostile groups too, but their template
        // (Template_Animal_Neutral) flees from them, so they stay Defend only.
        JsonElement group = lookup(chain, "AttitudeGroup");
        boolean flees = chainNames(role, files).stream().anyMatch(n -> n.contains("Animal_Neutral"));
        if (!flees && group != null && group.isJsonPrimitive() && hostileGroups().getOrDefault(group.getAsString(), false)) return Kind.FIGHTS;
        if (a.equalsIgnoreCase("Neutral")) return Kind.DEFENDS;
        return Kind.FIGHTS;
    }

    /** An Instructions list with nothing in it (absent = unknown, so false: templates may give the AI). */
    private static boolean noInstructions(JsonElement instructions) {
        if (instructions == null || !instructions.isJsonArray()) return false;
        for (JsonElement i : instructions.getAsJsonArray()) {
            if (!i.isJsonObject() || !i.getAsJsonObject().entrySet().isEmpty()) return false;
        }
        return true;
    }

    /** The role and the templates it references, by name. */
    private static List<String> chainNames(String role, Map<String, JsonObject> files) {
        List<String> out = new ArrayList<>();
        String name = role;
        while (name != null && out.size() < 8 && !out.contains(name)) {
            JsonObject json = read(name, files);
            if (json == null) break;
            out.add(name);
            name = json.has("Reference") && json.get("Reference").isJsonPrimitive() ? json.get("Reference").getAsString() : null;
        }
        return out;
    }

    private static volatile Map<String, Boolean> hostileGroups;

    /**
     * Attitude group -> whether it is hostile to some other group (Server/NPC/Attitude/Roles/.../&lt;group&gt;.json, "Groups"
     * -> "Hostile" not empty), from every asset pack in load order (a mod's file replaces the game's). Read once.
     */
    private static Map<String, Boolean> hostileGroups() {
        Map<String, Boolean> map = hostileGroups;
        if (map != null) return map;
        map = new HashMap<>();
        try {
            for (var pack : AssetModule.get().getAssetPacks()) {
                Path dir = pack.getRoot() != null ? pack.getRoot().resolve("Server").resolve("NPC").resolve("Attitude").resolve("Roles") : null;
                if (dir == null || !Files.isDirectory(dir)) continue;
                try (var walk = Files.walk(dir)) {
                    for (Path f : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".json"))::iterator) {
                        try {
                            JsonObject json = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                            JsonObject groups = json.has("Groups") && json.get("Groups").isJsonObject() ? json.getAsJsonObject("Groups") : null;
                            boolean hostile = groups != null && groups.has("Hostile") && groups.get("Hostile").isJsonArray()
                                    && groups.getAsJsonArray("Hostile").size() > 0;
                            String name = f.getFileName().toString();
                            map.put(name.substring(0, name.length() - 5), hostile);
                        } catch (Exception ignored) {
                            // unreadable group file: not counted
                        }
                    }
                }
            }
        } catch (Exception e) {
            MobBattlePlugin.debug("could not read the attitude groups: " + e);
        }
        hostileGroups = map;
        return map;
    }

    /** The role's JSON, then each template it references (up to 8 deep). */
    private static final Map<String, Double> MAX_HEALTH = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A role's MaxHealth from its JSON chain (summon fill matches mobs of similar strength), cached; -1 if the role does
     * not say (a mod's role built in code, a computed value).
     */
    static double maxHealthOf(String role) {
        return MAX_HEALTH.computeIfAbsent(role, r -> {
            JsonElement v = lookup(chain(r, new HashMap<>()), "MaxHealth");
            try {
                return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsDouble() : -1.0;
            } catch (RuntimeException e) {
                return -1.0;
            }
        });
    }

    private static List<JsonObject> chain(String role, Map<String, JsonObject> files) {
        List<JsonObject> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String name = role;
        while (name != null && out.size() < 8 && seen.add(name)) {
            JsonObject json = read(name, files);
            if (json == null) break;
            out.add(json);
            name = json.has("Reference") && json.get("Reference").isJsonPrimitive() ? json.get("Reference").getAsString() : null;
        }
        return out;
    }

    private static JsonObject read(String name, Map<String, JsonObject> files) {
        if (files.containsKey(name)) return files.get(name);
        JsonObject json = null;
        try {
            var bm = NPCPlugin.get().getBuilderManager();
            int index = bm.getIndex(name);
            var info = index >= 0 ? bm.tryGetBuilderInfo(index) : null;
            if (info != null && info.getPath() != null) {
                json = JsonParser.parseString(Files.readString(info.getPath())).getAsJsonObject();
            }
        } catch (Exception ignored) {
            // unreadable: no information
        }
        files.put(name, json);
        return json;
    }

    /**
     * A value as the role sees it: the top-level key of the nearest file that has it (a {"Compute": p} points to
     * parameter p), else the parameter of that name. Parameters: the nearest "Modify", else the nearest "Parameters"
     * default. Null if nobody sets it.
     */
    private static JsonElement lookup(List<JsonObject> chain, String key) {
        for (JsonObject json : chain) {
            if (!json.has(key)) continue;
            JsonElement v = json.get(key);
            if (v.isJsonObject() && v.getAsJsonObject().has("Compute") && v.getAsJsonObject().get("Compute").isJsonPrimitive()) {
                return param(chain, v.getAsJsonObject().get("Compute").getAsString());
            }
            return v;
        }
        return param(chain, key);
    }

    private static JsonElement param(List<JsonObject> chain, String name) {
        for (JsonObject json : chain) {
            if (json.has("Modify") && json.get("Modify").isJsonObject() && json.getAsJsonObject("Modify").has(name)) {
                return json.getAsJsonObject("Modify").get(name);
            }
        }
        for (JsonObject json : chain) {
            if (json.has("Parameters") && json.get("Parameters").isJsonObject() && json.getAsJsonObject("Parameters").has(name)) {
                JsonElement p = json.getAsJsonObject("Parameters").get(name);
                return p.isJsonObject() && p.getAsJsonObject().has("Value") ? p.getAsJsonObject().get("Value") : p;
            }
        }
        return null;
    }

    private static boolean isTrue(JsonElement v) {
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    /** As RoleAttack: an empty string or null is "no attack"; anything else (an id, a Compute, an inline attack) is one. */
    private static boolean isSet(JsonElement v) {
        if (v == null || v.isJsonNull()) return false;
        if (v.isJsonPrimitive()) return !v.getAsString().isBlank();
        return true;
    }
}
