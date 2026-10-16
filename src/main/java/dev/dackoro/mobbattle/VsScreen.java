package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.component.BoundingBox;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Smash-like VS screen (prototype, 2026-10-16, user: "la pantalla VS"; /mobbattle test vs [seconds]): a full-screen
 * HUD ({@code Common/UI/Custom/MobBattle/VsScreen.ui}) with two sides, each in its team color, the posed thumbnail
 * (ThumbnailCache) of its 1-3 strongest fighters (max health) and "+N" for the rest, and a big VS. The two halves
 * slide in from the screen edges as FlexWeights (as CinemaBars' bands), meet in the middle with a white flash, hold,
 * then slide out.
 * <p>
 * Who: the battle most of the nearby mobs (within {@link #RANGE}) are in, by side (more than two sides: the strongest
 * side vs everyone else); with no battle, the nearby mobs split into two sides by kind.
 * <p>
 * Look (2026-10-16, user: like the VS card made for the clip): baked halves with the slant, streaks and particles per
 * palette color and the VS logo (scripts/gen-vs-assets.py), the name bar, and the fighters big and overlapping in
 * slots of VsScreen.ui (scripts/gen-vs-ui.py): 1-3 named, 4-9 medium, 10+ a mound, all at their slot's full size; only
 * the 3+ team columns size pictures by their softened height (tierOf).
 * <p>
 * Pictures reach the client as common assets of the mod ({@code UI/Custom/MobBattle/Vs/MobBattle_Vs_<model>_v<k>_L|R.png},
 * the right side mirrored so both look at the middle, padded square, feet at the bottom, 256-color palette PNGs).
 * Never registered while players are online (user: the asset reload hitch, never again): the epic pose of every mob
 * model, every pose of the summon list's mobs and the poses wanted before (used.txt) are drawn by the thumbnail boot
 * run and registered at boot (registerAtBoot / prepareInBackground). Anything else falls back to the epic pose, then
 * the panel icon (picture()).
 * ❓ Untested in game: layout (LayoutMode Right/Middle, Visible collapsing), AssetImage with runtime assets, timing.
 */
final class VsScreen extends CustomUIHud {

    static final String KEY = "MobBattleVsScreen";
    private static final String UI = "MobBattle/VsScreen.ui";
    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";

    /**
     * Not under UI/Custom (2026-10-16, user's client log: "Texture atlas rejected image" for 515 images, vanilla UI
     * broke): the client packs every PNG under UI/Custom into one UI texture atlas, and our pictures filled it.
     * AssetImage loads other paths on their own, as the Memories page shows Icons/ModelsGenerated.
     */
    static final String FOLDER = "Icons/MobBattle/Vs/";
    /** The mob pictures made at runtime. */
    static final String PICTURES = "Icons/MobBattle/VsPictures/";
    private static final String MISSING = "UI/Custom/Pages/Memories/MissingIcon.png";
    /**
     * A player's picture key: their skin's id (PlayerSkins.idOf) after this prefix, used where a mob's model id goes
     * (2026-10-16, user: players in the VS with their real skin, cached per skin).
     */
    static final String PLAYER_PREFIX = "MobBattlePlayer_";
    /** Player skins registered at boot, newest first (each a picture pair + a column picture, ~40 KB). */
    private static final int MAX_PLAYER_SKINS = 100;
    private static final Map<String, com.hypixel.hytale.protocol.PlayerSkin> SKINS = new ConcurrentHashMap<>();
    /** Mobs this far around the player take part. */
    static final double RANGE = 48;
    private static final int MAX_MOBS = 200;
    /** Big pictures per side; the rest is "+N". */
    private static final int SHOWN = 3;
    /**
     * Crowd slots of VsScreen.ui (scripts/gen-vs-ui.py ROWS / SKY_SLOTS): 24 on the ground in a mound (rows narrower
     * toward the back, peaking in the middle; user 2026-10-16: flat rows looked like a line-up), 4 in the air.
     */
    private static final int CROWD = 28;
    private static final int GROUND_SLOTS = 24;
    /**
     * A crowd grows like a huddle from the ground (user, 2026-10-16: ground mobs in the upper rows looked like they
     * floated): GROUND = the five rows, front first, center first; SKY = the slots high in the air, for flying mobs only.
     * Fliers take the sky first, then the ground rows; ground mobs only ever take the ground rows.
     */
    private static final int[] GROUND = java.util.stream.IntStream.range(0, GROUND_SLOTS).toArray();
    private static final int[] SKY = java.util.stream.IntStream.range(GROUND_SLOTS, CROWD).toArray();
    /**
     * A side's layout by how many fight on it (user, 2026-10-16: small crowds looked tiny; only very big crowds should
     * shrink): NAMED = 1-3 big with names, MEDIUM = 4-9 big and spread over the half (#xM, gen-vs-ui.py MEDIUM),
     * MOUND = 10+ in the small mound (#xC). Every picture of a half is the same size for its slot (no height tiers).
     */
    static final int NAMED = 0, MEDIUM = 1, MOUND = 2;
    private static final int MEDIUM_SLOTS = 9;
    /**
     * Slots from the back row to the front (scripts/gen-vs-ui.py --depth): the tallest fighters shown take the back
     * slots, so their heads show over the smaller ones in front (user, 2026-10-16: faces were covered).
     */
    private static final int[] NAMED_BACK_FIRST = {0, 1, 2};
    private static final int[] MEDIUM_BACK_FIRST = {8, 7, 3, 4, 5, 6, 0, 1, 2};
    private static final int[] GROUND_BACK_FIRST = {23, 22, 21, 16, 20, 19, 18, 12, 13, 17, 15, 9, 14, 8, 11, 10, 1, 5, 4, 7, 0, 2, 3, 6};
    /** The name bar fits about this many characters at its font size; past it, kinds go into "+N". */
    private static final int NAMES_CHARS = 28;
    private static final String LEFT_COLOR = "#e03030";
    private static final String RIGHT_COLOR = "#3070e0";

    private static final long STEP_MS = 50;
    private static final long SLIDE_IN_MS = 350;
    private static final long SLIDE_OUT_MS = 350;
    private static final long FLASH_MS = 120;
    /** A half's width at rest, in the thousandths the halves' FlexWeights use (they overlap at the slant). */
    private static final int HALF = 580;
    /**
     * Picture sizes of a slot (VsScreen.ui, scripts/gen-vs-ui.py TIERS): 100%, 85%, 72%, 60%. The size follows
     * (height / tallest) ^ SIZE_POWER, like the reference card (user, 2026-10-16: a 2.5-block mob next to a 6-block
     * one at 70%, not 40%; heights shown but not exaggerated).
     */
    private static final double[] TIER_AT = {0.93, 0.79, 0.66};
    /**
     * Softer than the reference card's 0.35 (user, 2026-10-16: sizes even like the columns, but a tiny Kweebec seedling
     * looked as big as a boss): a mob at 40% of the tallest keeps 80% of the size, one at 12% shrinks to 60%.
     */
    private static final double SIZE_POWER = 0.25;
    private static final int TIERS = 4;
    static final String LOGO = FOLDER + "VsLogo.png";
    /** Longest side of a VS picture (px): 320 looked like 384 blown up on screen and weighs ~12% less. */
    private static final int PICTURE = 384;
    /**
     * At most this many picture pairs are registered: enough for the epic pose of every mob model (~410 with the
     * user's mods) plus the summon list's and used.txt's poses. A budget of 200 left mobs on their panel icon, and the
     * game's icons face any way (user, 2026-10-16: a Scarak looked away from the VS). In order: the summon list's mobs
     * (every pose), the poses VS screens wanted (used.txt), then the epic pose of the other mob models.
     */
    static final int MAX_PAIRS = 2400;   // keys: each mob model = its pose pair + 3 column crops (2026-10-16)
    /** Threads drawing the boot pictures (fewer when the machine has fewer cores). */
    private static final int MAX_THREADS = 4;

    /** Poses shown with a fallback picture this session: written to used.txt, registered at the next boot. */
    private static final Set<String> WANTED = ConcurrentHashMap.newKeySet();
    /** Model ids whose two pictures are registered. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    /** Player -> their newest screen (an older one's steps stop). */
    private static final Map<UUID, Long> RUNS = new ConcurrentHashMap<>();
    private static final AtomicLong RUN_IDS = new AtomicLong();

    /** {@code height}: its hitbox height (size buffs included), for its size on screen against the tallest one. */
    record Fighter(String model, String name, double health, int variant, double height, boolean flies) {
        Fighter withVariant(int v) {
            return new Fighter(model, name, health, v, height, flies);
        }
    }

    /** Pictures per model: 0 = its most "epic" pose (each side's strongest gets it), 1-2 = others for repeats. */
    static final int VARIANTS = 3;
    /** Sets tried for variant 0, most dramatic first (vanilla names; mods often reuse them). */
    private static final String[] EPIC = {"Roar", "Taunt", "Threaten", "Howl", "Angry", "SawCharge", "Swipe", "Throw",
            "Rally", "LaughEvil"};
    private static final int EPIC_NAMED = EPIC.length;
    /** Players who had the game's HUD hidden by a VS screen (it gives it back at the end). */
    private static final Set<UUID> HID = ConcurrentHashMap.newKeySet();

    /** {@code names}: the name bar's line ("GOBLITERATOR · COSMIC WRAITH", "SKELETON x5 · ZOMBIE x3"). */
    /** {@code order}: every fighter of the side, variety first (to lay it out again as a crowd: {@link #balanced}). */
    record Side(Message label, String color, List<Fighter> shown, int more, int layout, String names, List<Fighter> order) {
    }

    /**
     * {@code kind}: the battle kind titled under the VS (DUEL, BATTLE ROYALE...), null = none. {@code columns}: 3+
     * teams, shown as one column per team instead of the two halves (null = halves).
     */
    record Plan(Side left, Side right, BattleManager.Kind kind, List<Column> columns) {
        Plan(Side left, Side right) {
            this(left, right, null, null);
        }

        Plan(Side left, Side right, BattleManager.Kind kind) {
            this(left, right, kind, null);
        }

        Plan withKind(BattleManager.Kind k) {
            return new Plan(left, right, k, columns);
        }
    }

    /** One team of a 3+ team screen: its name, color, its representative (picked at random by strength), the others. */
    /** {@code first}: the team's first column (it carries the team tag); {@code team}: the team's number from 1. */
    record Column(Message label, String color, Fighter rep, int others, int team, boolean first) {
    }

    /** The 3+ team screen shows at most this many columns (VsScreen.ui #Cols3..#Cols8), the strongest teams. */
    private static final int MAX_COLUMNS = 8;
    /** Columns are revealed one by one, this far apart. */
    private static final long COLUMN_GAP_MS = 90;
    private static final Side NO_SIDE = new Side(Message.raw(""), LEFT_COLOR, List.of(), 0, NAMED, "", List.of());

    private final Plan plan;

    private VsScreen(PlayerRef player, Plan plan) {
        super(player, KEY);
        this.plan = plan;
    }

    @Override
    protected void build(UICommandBuilder b) {
        b.append(UI);
        if (plan.columns() != null) {
            fillColumns(b, plan.columns());
            // the kind's title under the column headers (no VS logo: it covered the columns; user 2026-10-16)
            if (plan.kind() != null) b.set("#ColKindTitle.AssetPath", titlePicture(plan.kind(), getPlayerRef()));
            return;
        }
        double tallest = 0.1;
        for (Side side : List.of(plan.left(), plan.right())) {
            for (Fighter f : side.shown()) if (f != null) tallest = Math.max(tallest, f.height());
        }
        // a plain battle royale has no sides: one background over the whole screen, not the split halves (user,
        // 2026-10-16); royale() is the only plan with both labels empty (an army royale has named sides)
        boolean oneBg = plan.kind() == BattleManager.Kind.ROYALE && isEmpty(plan.left().label()) && isEmpty(plan.right().label());
        if (oneBg) {
            b.set("#RoyaleBg.AssetPath", FOLDER + "Bg_Royale.png");
            b.set("#RoyaleBg.Visible", true);
        }
        fill(b, "L", plan.left(), false, tallest, oneBg);
        fill(b, "R", plan.right(), true, tallest, oneBg);
        b.set("#VsLogo.AssetPath", LOGO);
        if (plan.kind() != null) b.set("#KindTitle.AssetPath", titlePicture(plan.kind(), getPlayerRef()));
        weights(b, 0);
    }

    /**
     * The 3+ team screen (user, 2026-10-16: like Smash's 4-8 player screen): #Cols{n} with one panel per team, its
     * color under ColumnOverlay.png, "P1 · TEAM · +N" in the team color, the representative's name and picture (the
     * right half's columns mirrored, so they face the middle), sized against the tallest representative. Panels stay
     * hidden until run() reveals them one by one.
     */
    private void fillColumns(UICommandBuilder b, List<Column> columns) {
        int n = columns.size();
        b.set("#LeftLayer.Visible", false);
        b.set("#RightLayer.Visible", false);
        b.set("#Cols.Visible", true);
        b.set("#Cols" + n + ".Visible", true);
        int font = Math.max(24, Math.min(44, Math.round(1920f / n / 11)));
        int chars = Math.max(5, (int) ((1920.0 / n - 24) / (font * 0.6)));
        for (int i = 0; i < n; i++) {
            Column c = columns.get(i);
            String p = "#C" + n + "P" + i;
            b.setObject(p + "In.Background", new com.hypixel.hytale.server.core.ui.PatchStyle()
                    .setColor(com.hypixel.hytale.server.core.ui.Value.of(darken(c.color(), 0.7))));
            b.set(p + "Over.AssetPath", FOLDER + "ColumnOverlay.png");
            // the label may be a translation ("Red team", "Everyone else"): joined as a Message, never its key. A raw
            // name is cut to the width of its team's columns (labels do not shrink in game; user 2026-10-16: long
            // names ran into the next team)
            Message tag = Message.raw("");
            if (c.first()) {
                String head = "P" + c.team() + " \u00b7 ", tail = c.others() > 0 ? " \u00b7 +" + c.others() : "";
                Message label = c.label();
                if (label.getRawText() != null) {
                    long cols = columns.stream().filter(o -> o.team() == c.team()).count();
                    int fit = (int) ((1920.0 / n * cols - 24) / TAG_CHAR_PX) - head.length() - tail.length();
                    String raw = label.getRawText();
                    label = Message.raw(raw.length() > Math.max(4, fit) ? raw.substring(0, Math.max(3, fit - 1)) + "." : raw);
                }
                tag = Message.join(Message.raw(head).color(c.color()), label.color(c.color()), Message.raw(tail).color(c.color()));
            }
            b.set(p + "Tag.TextSpans", tag);
            b.set(p + "Name.Text", shortName(c.rep().name(), chars));
            // every portrait the same size, so the faces sit on one line (user, 2026-10-16: small ones sat low)
            int tier = 0;
            boolean right = i >= (n + 1) / 2;
            for (int t = 0; t < TIERS; t++) {
                b.set(p + "T" + t + ".Visible", t == tier);
                if (t == tier) b.set(p + "T" + t + ".AssetPath", columnPicture(c.rep(), right, n));
            }
        }
    }

    /**
     * A name that fits {@code chars}: technical endings dropped first ("PHASE1", "MODEL"), then leading words
     * ("GOBLIN GOBLITERATOR PHASE1" -> "GOBLITERATOR"), then cut with a dot (user, 2026-10-16: "GOBLIN GOBLITERAT").
     */
    static String shortName(String name, int chars) {
        String n = name.trim();
        // technical endings first ("PHASE1", "MODEL", "2"), then leading words
        while (n.length() > chars && n.indexOf(' ') > 0
                && n.substring(n.lastIndexOf(' ') + 1).matches("(?i)(phase[0-9]*|model|v?[0-9]+)")) {
            n = n.substring(0, n.lastIndexOf(' '));
        }
        while (n.length() > chars && n.indexOf(' ') > 0) n = n.substring(n.indexOf(' ') + 1);
        return n.length() > chars ? n.substring(0, Math.max(1, chars - 1)) + "." : n;
    }

    /**
     * One half (2026-10-16, user: the look of the VS card made for the clip): its background in the side's palette
     * color, the name bar, and each fighter in its slot of the side's layout, all at the slot's full size (user,
     * 2026-10-16: like the columns; height tiers made small crowds tiny).
     */
    private static boolean isEmpty(Message m) {
        return m.getRawText() != null && m.getRawText().isEmpty();
    }

    private static void fill(UICommandBuilder b, String s, Side side, boolean right, double tallest, boolean oneBg) {
        b.set("#" + s + "Bg.AssetPath", FOLDER + "Bg_" + Teams.nearestPalette(side.color()) + "_" + s + ".png");
        b.set("#" + s + "Bg.Visible", !oneBg);
        b.set("#" + s + "Team.TextSpans", side.label().color(side.color()));
        b.set("#" + s + "Names.Text", side.names());
        b.set("#" + s + "Named.Visible", side.layout() == NAMED);
        b.set("#" + s + "Medium.Visible", side.layout() == MEDIUM);
        b.set("#" + s + "Crowd.Visible", side.layout() == MOUND);
        slots(b, "#" + s + "N", SHOWN, side.layout() == NAMED ? side.shown() : List.of(), right, tallest);
        slots(b, "#" + s + "M", MEDIUM_SLOTS, side.layout() == MEDIUM ? side.shown() : List.of(), right, tallest);
        slots(b, "#" + s + "C", CROWD, side.layout() == MOUND ? side.shown() : List.of(), right, tallest);
        b.set("#" + s + "MoreBox.Visible", side.more() > 0);
        if (side.more() > 0) {
            b.set("#" + s + "More.Text", "+" + side.more());
            b.set("#" + s + "MoreBadge.AssetPath", FOLDER + "MoreBadge.png");
        }
    }

    private static void slots(UICommandBuilder b, String prefix, int count, List<Fighter> fighters, boolean right, double tallest) {
        for (int i = 0; i < count; i++) {
            Fighter f = i < fighters.size() ? fighters.get(i) : null;
            int tier = f != null ? tierOf(f.height(), tallest) : -1;
            b.set(prefix + i + "G.Visible", f != null);
            if (f != null) b.set(prefix + i + "G.AssetPath", FOLDER + (right ? "Glow_R.png" : "Glow_L.png"));
            for (int t = 0; t < TIERS; t++) {
                String slot = prefix + i + "T" + t;
                b.set(slot + ".Visible", t == tier);
                if (t == tier) b.set(slot + ".AssetPath", picture(f, right));
            }
        }
    }

    /** 0 = full size .. 3 = 60%: the fighter's height against the tallest one on screen, softened. */
    static int tierOf(double height, double tallest) {
        double f = Math.pow(Math.min(1, height / Math.max(0.1, tallest)), SIZE_POWER);
        for (int t = 0; t < TIER_AT.length; t++) if (f >= TIER_AT[t]) return t;
        return TIER_AT.length;
    }

    /**
     * The picture of a fighter: its pose if registered, else its epic pose (variant 0, registered at boot for every
     * mob model), else its panel icon (the game's or ours), else the "?" icon. Never a live registration: that made
     * every client reload its assets (user: no hitch, ever). A wanted pose that is missing is remembered, and the next
     * boot registers it.
     */
    /** A team column's picture: the face-framed one when registered, else the halves' picture. */
    private static String columnPicture(Fighter f, boolean right, int n) {
        if (f.model() != null) {
            int want = columnVariant(n);
            if (REGISTERED.contains(key(f.model(), want))) return pathOf(f.model(), want, false);
            // another width (slightly stretched) beats the square halves' picture (very stretched)
            for (int v = -1; v >= -COLUMN_CROP.length; v--) {
                if (REGISTERED.contains(key(f.model(), v))) return pathOf(f.model(), v, false);
            }
        }
        return picture(f, right);
    }

    private static String picture(Fighter f, boolean right) {
        if (f.model() == null) return MISSING;
        if (REGISTERED.contains(key(f.model(), f.variant()))) return pathOf(f.model(), f.variant(), right);
        WANTED.add(key(f.model(), f.variant()));
        if (REGISTERED.contains(key(f.model(), 0))) return pathOf(f.model(), 0, right);
        String icon = ConfigPage.iconOf(f.model());
        return icon != null ? icon : MISSING;
    }

    private static void weights(UICommandBuilder b, int half) {
        int h = Math.max(0, Math.min(HALF, half));
        b.set("#LHalf.FlexWeight", h);
        b.set("#LRest.FlexWeight", 1000 - h);
        b.set("#RHalf.FlexWeight", h);
        b.set("#RRest.FlexWeight", 1000 - h);
    }

    // ---- command ----

    static void command(CommandContext ctx, String[] a) {
        if (!ctx.isPlayer()) {
            TestTools.say(ctx.sender(), "Only a player can see the VS screen.", ERR);
            return;
        }
        double seconds = 2.5;
        if (a.length > 1) {
            try {
                seconds = Math.max(1.2, Math.min(15, Double.parseDouble(a[1])));
            } catch (NumberFormatException e) {
                TestTools.say(ctx.sender(), "Usage: /mobbattle test vs [seconds]", ERR);
                return;
            }
        }
        Ref<EntityStore> me = ctx.senderAsPlayerRef();
        if (me == null || !me.isValid()) return;
        Store<EntityStore> store = me.getStore();
        long total = Math.round(seconds * 1000);
        store.getExternalData().getWorld().execute(() -> {
            if (!me.isValid()) return;
            PlayerRef pr = store.getComponent(me, PlayerRef.getComponentType());
            Plan plan = planAround(me, store);
            if (pr == null) return;
            if (plan == null) {
                TestTools.say(ctx.sender(), "Need at least two mobs within " + (int) RANGE + " blocks.", ERR);
                return;
            }
            TestTools.say(ctx.sender(), "VS: " + describe(plan.left()) + "  vs  " + describe(plan.right()), OK);
            present(pr, plan, total);
        });
    }

    private static String describe(Side s) {
        StringBuilder sb = new StringBuilder(textOf(s.label())).append(" (");
        boolean first = true;
        for (Fighter f : s.shown()) {
            if (f == null) continue;
            if (!first) sb.append(", ");
            sb.append(f.name());
            first = false;
        }
        if (s.more() > 0) sb.append(", +").append(s.more());
        return sb.append(')').toString();
    }

    /**
     * Shows {@code plan} to {@code player} for {@code totalMs}, right away: pictures are only the ones registered at
     * boot (no asset rebuild, no hitch); poses that were missing are noted for the next boot. Any thread.
     */
    static void present(PlayerRef player, Plan plan, long totalMs) {
        run(player, plan, totalMs);
        CompletableFuture.runAsync(VsScreen::rememberWanted);
    }

    // ---- who fights ----

    /** World thread: the two sides around the player, or null with fewer than two mobs. */
    static Plan planAround(Ref<EntityStore> me, Store<EntityStore> store) {
        Vector3d at = ChaosWand.positionOf(me, store);
        if (at == null) return null;
        List<Ref<EntityStore>> near = ChaosWand.npcsInBand(at, RANGE, MAX_MOBS, store);
        // the battle most of them are in
        Map<BattleManager.Battle, Integer> counts = new HashMap<>();
        for (Ref<EntityStore> r : near) {
            BattleManager.Battle b = BattleManager.battleOf(r);
            if (b != null) counts.merge(b, 1, Integer::sum);
        }
        BattleManager.Battle battle = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        Plan plan = battle != null ? fromBattle(battle, store) : near.size() < 2 ? null : fromKinds(near, store);
        if (plan != null && battle != null && plan.kind() == null) plan = plan.withKind(battle.kind);
        return plan == null ? null : withVariants(balanced(plan));
    }

    /**
     * The kind's title under the VS (user, 2026-10-16: in the VS's own style, right under it; it replaces the
     * countdown's title while the VS shows): Kind_<kind>_<en|es>.png, baked by scripts/gen-vs-assets.py (a Label can't
     * be slanted or outlined), in the viewer's language (Spanish for es-*, else English).
     */
    static String titlePicture(BattleManager.Kind kind, PlayerRef viewer) {
        String lang = viewer != null ? viewer.getLanguage() : null;
        String suffix = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("es") ? "es" : "en";
        return FOLDER + "Kind_" + kind.key + "_" + suffix + ".png";
    }

    /**
     * Gives repeated mobs different pictures (user, 2026-10-16: three Gobliterators looked identical): each side's
     * strongest gets variant 0 (the epic pose), every other fighter of a model already shown the next variant.
     */
    private static Plan withVariants(Plan plan) {
        Map<String, Integer> next = new HashMap<>();
        Side[] sides = {plan.left(), plan.right()};
        for (int s = 0; s < 2; s++) {
            List<Fighter> out = new ArrayList<>();
            for (int i = 0; i < sides[s].shown().size(); i++) {
                Fighter f = sides[s].shown().get(i);
                if (f == null || f.model() == null) {
                    out.add(f);
                    continue;
                }
                int used = next.getOrDefault(f.model(), 0);
                int v = i == 0 ? 0 : used % VARIANTS;
                next.put(f.model(), Math.max(used, v) + 1);
                out.add(f.withVariant(v));
            }
            sides[s] = new Side(sides[s].label(), sides[s].color(), out, sides[s].more(), sides[s].layout(), sides[s].names(), sides[s].order());
        }
        return new Plan(sides[0], sides[1], plan.kind(), plan.columns());
    }

    private static Plan fromBattle(BattleManager.Battle battle, Store<EntityStore> store) {
        Map<Integer, List<Ref<EntityStore>>> bySide = new LinkedHashMap<>();
        for (Ref<EntityStore> r : battle.members()) {
            if (r == null || !r.isValid() || r.getStore() != store || !BattleManager.isAlive(r, store)) continue;
            bySide.computeIfAbsent(battle.team(r), k -> new ArrayList<>()).add(r);
        }
        if (bySide.size() < 2) return null;
        if (battle.royale) {
            Plan armies = armyRoyale(battle, bySide, store);
            if (armies != null) return armies;
            List<Ref<EntityStore>> all = new ArrayList<>();
            bySide.values().forEach(all::addAll);
            return royale(all, store);
        }
        if (battle.isTeams() && bySide.size() >= 3) {
            List<Side> teams = new ArrayList<>();
            for (Map.Entry<Integer, List<Ref<EntityStore>>> e : bySide.entrySet()) {
                teams.add(side(e.getValue(), labelOf(battle, e.getKey(), e.getValue(), store),
                        colorOf(battle, e.getKey(), RIGHT_COLOR), store));
            }
            return columnsPlan(teams, battle.kind);
        }
        List<Map.Entry<Integer, List<Ref<EntityStore>>>> sides = new ArrayList<>(bySide.entrySet());
        sides.sort(Comparator.comparingDouble((Map.Entry<Integer, List<Ref<EntityStore>>> e) -> -power(e.getValue(), store)));
        Map.Entry<Integer, List<Ref<EntityStore>>> first = sides.get(0);
        Side left = side(first.getValue(), labelOf(battle, first.getKey(), first.getValue(), store),
                colorOf(battle, first.getKey(), LEFT_COLOR), store);
        Side right;
        if (sides.size() == 2) {
            Map.Entry<Integer, List<Ref<EntityStore>>> second = sides.get(1);
            right = battle.focusName != null && second.getValue().size() > 1
                    ? side(second.getValue(), I18n.tr("hud.vs.everyone"), RIGHT_COLOR, store)
                    : side(second.getValue(), labelOf(battle, second.getKey(), second.getValue(), store),
                    colorOf(battle, second.getKey(), RIGHT_COLOR), store);
        } else {
            List<Ref<EntityStore>> rest = new ArrayList<>();
            for (int i = 1; i < sides.size(); i++) rest.addAll(sides.get(i).getValue());
            right = side(rest, I18n.tr("hud.vs.everyoneElse"), RIGHT_COLOR, store);
        }
        return new Plan(left, right);
    }

    /** Width of one character of a column tag (VsScreen.ui #C..Tag, font 24, bold, uppercase), for cutting names. */
    private static final double TAG_CHAR_PX = 15;

    /** Side colors when a battle has none of its own (armies in a royale): red, blue, then other palette colors. */
    private static final String[] SIDE_COLORS = {LEFT_COLOR, RIGHT_COLOR, "#3fbf4f", "#e0c030", "#a050e0", "#ff8c1a", "#ff6bd5", "#3ef0ff"};

    /**
     * A royale with armies (2026-10-16, user: how does the VS show an army?): BattleManager.startRoyale gives each army
     * chain one side, so each army (2+ fighters) is a side named "&lt;leader&gt;'s army" and every free fighter goes into
     * "Everyone else". One army: army vs everyone else; two with nobody free: army vs army; more = team columns.
     * Null when no army fights (the usual royale halves).
     */
    private static Plan armyRoyale(BattleManager.Battle battle, Map<Integer, List<Ref<EntityStore>>> bySide, Store<EntityStore> store) {
        List<Side> armies = new ArrayList<>();
        List<Ref<EntityStore>> free = new ArrayList<>();
        for (List<Ref<EntityStore>> mobs : bySide.values()) {
            String army = mobs.size() > 1 ? BattleManager.armyName(mobs.get(0), store) : null;
            if (army == null) {
                free.addAll(mobs);
                continue;
            }
            armies.add(side(mobs, Message.raw(army), SIDE_COLORS[armies.size() % SIDE_COLORS.length], store));
        }
        if (armies.isEmpty()) return null;
        // halves have room for "<name>'s army"; team columns keep just the leader's name ("P1 · DACKORO · +4")
        if (armies.size() + (free.isEmpty() ? 0 : 1) < 3) {
            List<Side> named = new ArrayList<>();
            for (Side a : armies) {
                named.add(new Side(I18n.tr("battle.side.army").param("name", a.label().getRawText()), a.color(), a.shown(),
                        a.more(), a.layout(), a.names(), a.order()));
            }
            armies = named;
        }
        armies.sort(Comparator.comparingDouble((Side s) -> -s.order().stream().mapToDouble(Fighter::health).sum()));
        if (armies.size() == 1 || armies.size() == 2 && free.isEmpty()) {
            Side right = armies.size() == 2 ? armies.get(1) : side(free, I18n.tr("hud.vs.everyoneElse"), RIGHT_COLOR, store);
            return withVariants(balanced(new Plan(armies.get(0), right, BattleManager.Kind.ROYALE)));
        }
        List<Side> sides = new ArrayList<>(armies);
        if (!free.isEmpty()) sides.add(side(free, I18n.tr("hud.vs.everyoneElse"), SIDE_COLORS[sides.size() % SIDE_COLORS.length], store));
        return columnsPlan(sides, BattleManager.Kind.ROYALE);
    }

    /** No battle: groups by kind (role), each group to the side with fewer mobs; one kind only = split in two. */
    private static Plan fromKinds(List<Ref<EntityStore>> near, Store<EntityStore> store) {
        Map<String, List<Ref<EntityStore>>> byRole = new LinkedHashMap<>();
        for (Ref<EntityStore> r : near) byRole.computeIfAbsent(ChaosWand.nameOf(r, store), k -> new ArrayList<>()).add(r);
        List<Ref<EntityStore>> a = new ArrayList<>(), b = new ArrayList<>();
        if (byRole.size() == 1) {
            for (int i = 0; i < near.size(); i++) (i % 2 == 0 ? a : b).add(near.get(i));
        } else {
            List<List<Ref<EntityStore>>> groups = new ArrayList<>(byRole.values());
            groups.sort(Comparator.comparingInt(g -> -g.size()));
            for (List<Ref<EntityStore>> g : groups) (a.size() <= b.size() ? a : b).addAll(g);
        }
        if (a.isEmpty() || b.isEmpty()) return null;
        return new Plan(side(a, kindLabel(a, store, I18n.tr("hud.vs.red")), LEFT_COLOR, store),
                side(b, kindLabel(b, store, I18n.tr("hud.vs.blue")), RIGHT_COLOR, store));
    }

    /** A side laid out by how many fight on it (layoutFor): named, medium or a mound, "+N" for the rest. */
    private static Side side(List<Ref<EntityStore>> mobs, Message label, String color, ComponentAccessor<EntityStore> store) {
        List<Fighter> all = new ArrayList<>();
        for (Ref<EntityStore> r : mobs) all.add(fighter(r, store));
        all.sort(Comparator.comparingDouble(f -> -f.health()));
        List<Fighter> order = varietyFirst(all);
        // the small label would repeat the names line (a duel: "SKELETON" over "SKELETON"): left empty then
        String names = namesOf(all);
        if (label.getRawText() != null && label.getRawText().equalsIgnoreCase(names)) label = Message.raw("");
        return laidOut(new Side(label, color, List.of(), 0, NAMED, names, order), layoutFor(order.size()));
    }

    /** NAMED for 1-3, MEDIUM for 4-9, MOUND for more. */
    static int layoutFor(int count) {
        return count <= SHOWN ? NAMED : count <= MEDIUM_SLOTS ? MEDIUM : MOUND;
    }

    /** The side's fighters put into the slots of {@code layout}; the rest are "+N". */
    private static Side laidOut(Side side, int layout) {
        List<Fighter> order = side.order();
        if (layout == MOUND) {
            Fighter[] slots = new Fighter[CROWD];
            int placed = placed(order, slots);
            tallestBack(slots, GROUND_BACK_FIRST);
            return new Side(side.label(), side.color(), java.util.Arrays.asList(slots), order.size() - placed, MOUND,
                    side.names(), order);
        }
        int max = layout == MEDIUM ? MEDIUM_SLOTS : SHOWN;
        Fighter[] slots = order.subList(0, Math.min(max, order.size())).toArray(new Fighter[0]);
        tallestBack(slots, layout == MEDIUM ? MEDIUM_BACK_FIRST : NAMED_BACK_FIRST);
        List<Fighter> shown = new ArrayList<>(java.util.Arrays.asList(slots));
        return new Side(side.label(), side.color(), shown, order.size() - shown.size(), layout, side.names(), order);
    }

    /** The fighters in {@code slots} swapped around so the tallest sit in the slots furthest back. */
    private static void tallestBack(Fighter[] slots, int[] backFirst) {
        List<Integer> used = new ArrayList<>();
        for (int i : backFirst) if (i < slots.length && slots[i] != null) used.add(i);
        List<Fighter> byHeight = new ArrayList<>();
        for (int i : used) byHeight.add(slots[i]);
        byHeight.sort(Comparator.comparingDouble((Fighter f) -> -f.height()));
        for (int k = 0; k < used.size(); k++) slots[used.get(k)] = byHeight.get(k);
    }

    /**
     * One of each kind first, strongest kind first, then the repeats (user, 2026-10-16: with 100 skeletons, show the
     * unique mobs before more skeletons).
     */
    /**
     * Close scales on both halves (user, 2026-10-16: three mobs in the big named slots beside a crowd looked twice as
     * big as the same mobs in the crowd): the halves' layouts are at most one step apart, the smaller side moving up
     * (named -> medium) when the other is a mound. A lone fighter (all vs one's target) always stays big.
     */
    private static Plan balanced(Plan plan) {
        Side l = plan.left(), r = plan.right();
        if (l.layout() == NAMED && r.layout() == MOUND && l.order().size() > 1) l = laidOut(l, MEDIUM);
        if (r.layout() == NAMED && l.layout() == MOUND && r.order().size() > 1) r = laidOut(r, MEDIUM);
        return new Plan(l, r, plan.kind(), plan.columns());
    }

    private static List<Fighter> varietyFirst(List<Fighter> strongestFirst) {
        List<Fighter> firsts = new ArrayList<>(), repeats = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Fighter f : strongestFirst) {
            String kind = f.model() != null ? f.model() : f.name();
            (seen.add(kind) ? firsts : repeats).add(f);
        }
        firsts.addAll(repeats);
        return firsts;
    }

    /**
     * Puts a crowd into its slots in {@code order} (variety first): fliers in the sky, then the ground rows; ground mobs
     * in the ground rows only. Returns how many found a slot (the rest are "+N").
     */
    private static int placed(List<Fighter> order, Fighter[] slots) {
        int sky = 0, ground = 0, placed = 0;
        for (Fighter f : order) {
            int slot = -1;
            if (f.flies() && sky < SKY.length) slot = SKY[sky++];
            else if (ground < GROUND.length) slot = GROUND[ground++];
            if (slot < 0) continue;
            slots[slot] = f;
            placed++;
        }
        return placed;
    }

    /**
     * A royale (2026-10-16, user: "strongest vs everyone else" read as all vs one): the fighters dealt by strength into
     * the two halves in turn, both shown as crowds, "Battle royale" over the left one, so it reads as a free-for-all.
     */
    private static Plan royale(List<Ref<EntityStore>> all, ComponentAccessor<EntityStore> acc) {
        if (all.size() < 2) return null;
        List<Ref<EntityStore>> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(r -> -health(r, acc)));
        List<Ref<EntityStore>> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) (i % 2 == 0 ? a : b).add(sorted.get(i));
        return new Plan(side(a, Message.raw(""), LEFT_COLOR, acc), side(b, Message.raw(""), RIGHT_COLOR, acc),
                BattleManager.Kind.ROYALE);
    }

    /**
     * The name bar's line: each kind once, strongest first, "x3" for repeats, as many as fit in NAMES_CHARS (user,
     * 2026-10-16: names ran off the screen; the label does not shrink in game), the rest as "· +N". A single kind
     * longer than the bar is cut with "...".
     */
    private static String namesOf(List<Fighter> all) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (Fighter f : all) kinds.merge(f.name(), 1, Integer::sum);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) parts.add(e.getKey() + (e.getValue() > 1 ? " x" + e.getValue() : ""));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String sep = i == 0 ? "" : " \u00b7 ";
            String rest = i + 1 < parts.size() ? " \u00b7 +" + (parts.size() - i - 1) : "";
            if (i > 0 && sb.length() + sep.length() + parts.get(i).length() + rest.length() > NAMES_CHARS) {
                sb.append(" \u00b7 +").append(parts.size() - i);
                break;
            }
            sb.append(sep).append(parts.get(i));
        }
        if (sb.length() > NAMES_CHARS + 6) sb.setLength(NAMES_CHARS + 3);
        return sb.length() > NAMES_CHARS + 3 ? sb.substring(0, NAMES_CHARS) + "..." : sb.toString();
    }

    private static Fighter fighter(Ref<EntityStore> r, ComponentAccessor<EntityStore> store) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        Model m = mc != null ? mc.getModel() : null;
        boolean npc = store.getComponent(r, NPCEntity.getComponentType()) != null;
        // a player in their own body: their skin's picture (PlayerLook + gradients); morphed into a mob: that mob
        String model = m != null && (npc || !MorphWand.PLAYER_MODEL.equals(m.getModelAssetId())) ? m.getModelAssetId() : null;
        if (!npc && model == null) model = playerModelOf(r, store);
        BoundingBox bb = store.getComponent(r, BoundingBox.getComponentType());
        double height = bb != null && bb.getBoundingBox() != null ? bb.getBoundingBox().max.y - bb.getBoundingBox().min.y : 2;
        boolean flies = false;
        try {
            flies = npc && Medium.of(r, store).flies();
        } catch (RuntimeException ignored) {
            // no role data: a ground mob
        }
        return new Fighter(model, pretty(ChaosWand.nameOf(r, store)), health(r, store), 0, height, flies);
    }

    /** The picture id of a player's skin (remembered for drawing), or null without a skin. */
    private static String playerModelOf(Ref<EntityStore> r, ComponentAccessor<EntityStore> store) {
        var sc = store.getComponent(r, com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent.getComponentType());
        if (sc == null || sc.getPlayerSkin() == null) return null;
        String id = PLAYER_PREFIX + PlayerSkins.idOf(sc.getPlayerSkin());
        SKINS.putIfAbsent(id, sc.getPlayerSkin());
        return id;
    }

    /** A player picture id's skin: seen this session, else saved on disk; null when unknown. */
    private static com.hypixel.hytale.protocol.PlayerSkin skinOf(String id) {
        var skin = SKINS.get(id);
        if (skin != null) return skin;
        Path dir = playersDir();
        skin = dir != null ? PlayerSkins.load(dir, id.substring(PLAYER_PREFIX.length())) : null;
        if (skin != null) SKINS.put(id, skin);
        return skin;
    }

    private static Path playersDir() {
        Path used = usedFile();
        return used == null ? null : used.getParent().resolve("players");
    }

    /**
     * PlayerReadyEvent: a player's skin is saved and their pictures (pose 0 pair + team column) are drawn and registered
     * in the background when missing, so the next VS shows them. Registered live (silently, no asset rebuild): ❓
     * whether players already online get a picture registered after they joined (if not, they see the fallback until
     * the next boot, which registers every saved skin before anyone joins).
     */
    static void onPlayerReady(Ref<EntityStore> player) {
        if (player == null || !player.isValid()) return;
        Store<EntityStore> store = player.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!player.isValid()) return;
            String id = playerModelOf(player, store);
            if (id == null) return;
            var skin = SKINS.get(id);
            CompletableFuture.runAsync(() -> {
                try {
                    Path dir = playersDir();
                    if (dir == null) return;
                    String hash = id.substring(PLAYER_PREFIX.length());
                    if (!Files.exists(dir.resolve(hash + ".properties"))) PlayerSkins.save(dir, hash, skin);
                    else PlayerSkins.touch(dir, hash);
                    int drawn = 0, registered = 0;
                    List<String> ks = new ArrayList<>(List.of(key(id, 0)));
                    for (int v = -1; v >= -COLUMN_CROP.length; v--) ks.add(key(id, v));
                    for (String k : ks) {
                        if (!onDisk(k) && make(k)) drawn++;
                        if (register(k)) registered++;
                    }
                    if (drawn + registered > 0) {
                        MobBattlePlugin.log("VS screen: player skin " + hash + ": " + drawn + " drawn, " + registered + " registered.");
                    }
                } catch (Exception e) {
                    MobBattlePlugin.log("VS screen: player picture not made: " + e);
                }
            });
        });
    }

    private static double health(Ref<EntityStore> r, ComponentAccessor<EntityStore> store) {
        try {
            EntityStatMap stats = store.getComponent(r, EntityStatMap.getComponentType());
            var h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
            return h != null ? h.getMax() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double power(List<Ref<EntityStore>> mobs, ComponentAccessor<EntityStore> store) {
        double sum = 0;
        for (Ref<EntityStore> r : mobs) sum += health(r, store);
        return sum;
    }

    private static Message labelOf(BattleManager.Battle battle, int team, List<Ref<EntityStore>> mobs, Store<EntityStore> store) {
        if (battle.isTeams() && team >= 0 && team < battle.names.length && battle.names[team] != null) return Message.raw(battle.names[team]);
        return kindLabel(mobs, store, I18n.tr("hud.vs.side").param("n", String.valueOf(team + 1)));
    }

    private static String colorOf(BattleManager.Battle battle, int team, String fallback) {
        if (battle.isTeams() && team >= 0 && team < battle.colors.length && battle.colors[team] != null) return battle.colors[team];
        return fallback;
    }

    /** One kind: its name (x count); mixed: the fallback. */
    private static Message kindLabel(List<Ref<EntityStore>> mobs, ComponentAccessor<EntityStore> store, Message fallback) {
        String kind = null;
        for (Ref<EntityStore> r : mobs) {
            String n = ChaosWand.nameOf(r, store);
            if (kind == null) kind = n;
            else if (!kind.equals(n)) return fallback;
        }
        if (kind == null) return fallback;
        return Message.raw(pretty(kind) + (mobs.size() > 1 ? " x" + mobs.size() : ""));
    }

    /** Chat text of a label (raw names as they are, translated ones by their key: dev tool output). */
    private static String textOf(Message m) {
        return m.getRawText() != null ? m.getRawText() : m.getMessageId();
    }

    static String pretty(String role) {
        return role == null ? "?" : role.replace('_', ' ').trim();
    }

    // ---- in the countdown ----

    /**
     * How long the VS screen takes, in whole seconds so the countdown can grow by exactly that much (2026-10-16, user:
     * the VS must add its time to the countdown, not eat the intro's).
     */
    static final long COUNTDOWN_MS = 3000;

    /**
     * Milliseconds the VS screen adds in front of this kind's countdown (Countdown.run makes the countdown that much
     * longer; FightCamera.intro starts the intro camera after it with its full time), 0 = none (vsScreen off, or no
     * countdown).
     */
    static long countdownMs(BattleManager.Kind kind) {
        if (kind == null || !KindFx.vsScreen(kind) || KindFx.countdown(kind) <= 0) return 0;
        return COUNTDOWN_MS;
    }

    /**
     * The two sides of a battle about to start (world thread; {@code sides} as FightCamera.intro gets them): a duel
     * one against the other, all vs one the target against everyone, teams with their names and colors (the owner's
     * draft), a royale (one list) dealt into two crowds; 3+ sides = the strongest vs the rest (a crowd).
     */
    static Plan planOf(BattleManager.Kind kind, UUID owner, List<List<Ref<EntityStore>>> sides, ComponentAccessor<EntityStore> acc) {
        List<List<Ref<EntityStore>>> live = new ArrayList<>();
        for (List<Ref<EntityStore>> side : sides) {
            List<Ref<EntityStore>> ok = new ArrayList<>();
            for (Ref<EntityStore> r : side) if (r != null && r.isValid() && BattleManager.isAlive(r, acc)) ok.add(r);
            if (!ok.isEmpty()) live.add(ok);
        }
        if (live.isEmpty()) return null;
        if (live.size() == 1) {
            // royale (one list): dealt into two crowds
            Plan plan = royale(live.get(0), acc);
            return plan == null ? null : withVariants(balanced(plan));
        }
        BattleManager.Draft draft = kind == BattleManager.Kind.TEAMS && owner != null ? BattleManager.existingDraft(owner) : null;
        if (kind == BattleManager.Kind.TEAMS && live.size() >= 3) {
            List<Side> teams = new ArrayList<>();
            for (int i = 0; i < live.size(); i++) teams.add(sideOf(live.get(i), i, draft, acc));
            return columnsPlan(teams, kind);
        }
        List<List<Ref<EntityStore>>> order = new ArrayList<>(live);
        if (order.size() > 2) order.sort(Comparator.comparingDouble((List<Ref<EntityStore>> s) -> -power(s, acc)));
        Side left = sideOf(order.get(0), 0, draft, acc);
        Side right;
        if (order.size() == 2) {
            right = kind == BattleManager.Kind.ALL_VS_ONE ? side(order.get(1), I18n.tr("hud.vs.everyone"), RIGHT_COLOR, acc) : sideOf(order.get(1), 1, draft, acc);
        } else {
            List<Ref<EntityStore>> rest = new ArrayList<>();
            for (int i = 1; i < order.size(); i++) rest.addAll(order.get(i));
            right = side(rest, I18n.tr("hud.vs.everyoneElse"), RIGHT_COLOR, acc);
        }
        return withVariants(balanced(new Plan(left, right, kind)));
    }

    /**
     * Up to {@code k} columns of one team: representatives picked at random, weighted by max health (the strongest
     * shows up most, but not always), a different model each when the team has several kinds, in their first pose.
     */
    private static List<Column> columnsOf(Side side, int k, int team) {
        List<Fighter> pool = new ArrayList<>(side.order());
        List<Fighter> picked = new ArrayList<>();
        java.util.Set<String> kinds = new java.util.HashSet<>();
        while (picked.size() < k && !pool.isEmpty()) {
            List<Fighter> fresh = new ArrayList<>();
            for (Fighter f : pool) if (!kinds.contains(String.valueOf(f.model()))) fresh.add(f);
            List<Fighter> from = fresh.isEmpty() ? pool : fresh;
            double total = 0;
            for (Fighter f : from) total += Math.max(1, f.health());
            double roll = java.util.concurrent.ThreadLocalRandom.current().nextDouble(total);
            Fighter pick = from.get(0);
            for (Fighter f : from) {
                roll -= Math.max(1, f.health());
                if (roll <= 0) {
                    pick = f;
                    break;
                }
            }
            pool.remove(pick);
            kinds.add(String.valueOf(pick.model()));
            picked.add(pick);
        }
        List<Column> out = new ArrayList<>();
        int others = side.order().size() - picked.size();
        for (int i = 0; i < picked.size(); i++) {
            out.add(new Column(side.label(), side.color(), picked.get(i).withVariant(0), others, team, i == 0));
        }
        return out;
    }

    /**
     * The 3+ team screen: the MAX_COLUMNS strongest teams in their team order; with 4 teams or fewer each gets up to 2
     * columns side by side in its color (user, 2026-10-16: like Smash's team battles), never more than MAX_COLUMNS.
     */
    private static Plan columnsPlan(List<Side> teams, BattleManager.Kind kind) {
        List<Side> kept = new ArrayList<>(teams);
        if (kept.size() > MAX_COLUMNS) {
            List<Side> byStrength = new ArrayList<>(kept);
            byStrength.sort(Comparator.comparingDouble((Side t) -> {
                double sum = 0;
                for (Fighter f : t.order()) sum += f.health();
                return -sum;
            }));
            java.util.Set<Side> top = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            top.addAll(byStrength.subList(0, MAX_COLUMNS));
            kept.removeIf(t -> !top.contains(t));
        }
        // vsTeamReps (user, 2026-10-16: some want one representative per team even with room for two)
        int k = kept.size() <= 4 ? Math.min(FxSettings.vsTeamReps, MAX_COLUMNS / kept.size()) : 1;
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) cols.addAll(columnsOf(kept.get(i), k, i + 1));
        return new Plan(NO_SIDE, NO_SIDE, kind, cols);
    }

    /** One side: its team's name and color when it is a Team Wand team, else its kind (red left, blue right). */
    private static Side sideOf(List<Ref<EntityStore>> mobs, int index, BattleManager.Draft draft, ComponentAccessor<EntityStore> acc) {
        String fallbackColor = index == 0 ? LEFT_COLOR : RIGHT_COLOR;
        if (draft != null) {
            int team = draft.teamOf(mobs.get(0));
            if (team >= 0 && Teams.exists(team)) return side(mobs, Message.raw(Teams.name(team)), Teams.color(team), acc);
        }
        return side(mobs, kindLabel(mobs, acc, index == 0 ? I18n.tr("hud.vs.red") : I18n.tr("hud.vs.blue")), fallbackColor, acc);
    }

    // ---- the animation ----

    /** Adds the HUD, slides the halves in, flashes, holds, slides them out and removes it. */
    private static void run(PlayerRef player, Plan plan, long totalMs) {
        long id = RUN_IDS.incrementAndGet();
        RUNS.put(player.getUuid(), id);
        onWorld(player, () -> {
            Player p = playerOf(player);
            if (p == null) return;
            var huds = p.getHudManager();
            if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            if (huds.getCustomHud(KEY) != null) huds.removeCustomHud(player, KEY);
            VsScreen hud = new VsScreen(player, plan);
            huds.addCustomHud(player, hud);
            LIVE.put(player.getUuid(), hud);
            // the game's HUD (compass, boss bar, hotbar, abilities) goes while it shows (user, 2026-10-16)
            if (!CameraHud.hidden(player)) {
                HID.add(player.getUuid());
                CameraHud.hideAlways(player);
            }
            // the animation is timed from here, once the screen exists on the client (a busy world thread added it
            // late and the steps queued behind it arrived all at once: no slide, user 2026-10-16), START_DELAY_MS later:
            // a client still loading the battle's freshly spawned models froze through the whole slide
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> animate(player, id, plan, totalMs), START_DELAY_MS, TimeUnit.MILLISECONDS);
        });
    }

    /** The VS screen each player sees now (its updates are packets, sent from the timer thread, not the world's). */
    private static final Map<UUID, VsScreen> LIVE = new ConcurrentHashMap<>();
    private static final long START_DELAY_MS = 300;

    /** Whether a VS screen covers this player now. */
    static boolean showing(PlayerRef player) {
        return player != null && LIVE.containsKey(player.getUuid());
    }

    private static void animate(PlayerRef player, long id, Plan plan, long totalMs) {
        if (plan.columns() != null) {
            runColumns(player, id, plan, totalMs);
            return;
        }
        int inSteps = (int) Math.max(1, SLIDE_IN_MS / STEP_MS);
        // a click as each half comes in, the clash when they meet (user, 2026-10-16)
        sound(player, id, 0, CLICK_SOUND, 1f, 1f);
        sound(player, id, CLICK_GAP_MS, CLICK_SOUND, 1f, 1.12f);
        for (int i = 1; i <= inSteps; i++) {
            double f = (double) i / inSteps;
            double eased = 1 - (1 - f) * (1 - f) * (1 - f);   // ease out: fast in, soft landing
            step(player, id, i * STEP_MS, b -> weights(b, (int) Math.round(HALF * eased)));
        }
        long hit = inSteps * STEP_MS;
        sound(player, id, hit, IMPACT_SOUND, 1f, 1f);
        step(player, id, hit, b -> {
            b.set("#Flash.Visible", true);
            b.set("#Center.Visible", true);
            b.set("#Title.Visible", plan.kind() != null);
        });
        step(player, id, hit + FLASH_MS, b -> b.set("#Flash.Visible", false));
        long outAt = Math.max(hit + FLASH_MS + STEP_MS, totalMs - SLIDE_OUT_MS);
        step(player, id, outAt, b -> {
            b.set("#Center.Visible", false);
            b.set("#Title.Visible", false);
        });
        int outSteps = (int) Math.max(1, SLIDE_OUT_MS / STEP_MS);
        for (int i = 1; i <= outSteps; i++) {
            double f = (double) i / outSteps;
            double eased = f * f;   // ease in: leaves slowly, then fast
            step(player, id, outAt + i * STEP_MS, b -> weights(b, (int) Math.round(HALF * (1 - eased))));
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, () -> {
            if (!RUNS.remove(player.getUuid(), id)) return;
            LIVE.remove(player.getUuid());
            Player p = playerOf(player);
            if (p != null && p.getHudManager().getCustomHud(KEY) != null) p.getHudManager().removeCustomHud(player, KEY);
            // give the HUD back unless a camera that hides it too is filming now
            boolean ours = HID.remove(player.getUuid());
            if ((CameraHud.takeDeferred(player) || ours) && !FightCamera.busy(player) && !FilmCamera.isFilming(player)) {
                CameraHud.restore(player);
            }
        }), outAt + (outSteps + 1) * STEP_MS, TimeUnit.MILLISECONDS);
    }

    /** 3+ teams: the columns appear one by one from the left, then a flash with the VS and the title, hold, all go. */
    private static void runColumns(PlayerRef player, long id, Plan plan, long totalMs) {
        int n = plan.columns().size();
        for (int i = 0; i < n; i++) {
            String p = "#C" + n + "P" + i + "In.Visible";
            step(player, id, STEP_MS + i * COLUMN_GAP_MS, b -> b.set(p, true));
            sound(player, id, STEP_MS + i * COLUMN_GAP_MS, CLICK_SOUND, 1f, 1f + 0.05f * i);   // a click per column, rising
        }
        long hit = STEP_MS + n * COLUMN_GAP_MS;
        sound(player, id, hit, IMPACT_SOUND, 1f, 1f);
        step(player, id, hit, b -> {
            b.set("#Flash.Visible", true);
            // no VS logo over 3+ team columns (user, 2026-10-16: it covered them); the kind title sits right under the
            // column headers (#ColTitle), not over the faces
            b.set("#ColTitle.Visible", plan.kind() != null);
        });
        step(player, id, hit + FLASH_MS, b -> b.set("#Flash.Visible", false));
        long outAt = Math.max(hit + FLASH_MS + STEP_MS, totalMs - STEP_MS);
        step(player, id, outAt, b -> {
            b.set("#Cols.Visible", false);
            b.set("#ColTitle.Visible", false);
        });
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, () -> {
            if (!RUNS.remove(player.getUuid(), id)) return;
            LIVE.remove(player.getUuid());
            Player p = playerOf(player);
            if (p != null && p.getHudManager().getCustomHud(KEY) != null) p.getHudManager().removeCustomHud(player, KEY);
            boolean ours = HID.remove(player.getUuid());
            if ((CameraHud.takeDeferred(player) || ours) && !FightCamera.busy(player) && !FilmCamera.isFilming(player)) {
                CameraHud.restore(player);
            }
        }), outAt + STEP_MS, TimeUnit.MILLISECONDS);
    }

    /** Game sounds of the screen (2026-10-16, user: a click as each column/half shows, a clash at the VS); change here. */
    private static final String CLICK_SOUND = "SFX_Creative_Play_Selection_Place";
    private static final String IMPACT_SOUND = "SFX_Gobliterator_Club_Slam_Impact";
    private static final long CLICK_GAP_MS = 90;

    /** A 2D (UI) sound for this viewer only, at {@code at} ms into the run, sent from the timer thread like the steps. */
    private static void sound(PlayerRef player, long id, long at, String sound, float volume, float pitch) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!player.isValid() || !Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            try {
                int index = com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent.getAssetMap().getIndex(sound);
                if (index < 0) return;
                com.hypixel.hytale.server.core.universe.world.SoundUtil.playSoundEvent2dToPlayer(player, index,
                        com.hypixel.hytale.protocol.SoundCategory.UI, volume, pitch);
            } catch (RuntimeException e) {
                MobBattlePlugin.log("VS screen sound failed: " + e);
            }
        }, at, TimeUnit.MILLISECONDS);
    }

    /**
     * One animation step, sent straight from the timer thread: CustomUIHud.update only writes a packet to the player's
     * connection (javap 0.7.2), so a busy world thread (a royale of 100+ mobs) no longer bunches the slide into one jump.
     */
    private static void step(PlayerRef player, long id, long at, java.util.function.Consumer<UICommandBuilder> change) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (!player.isValid()) {   // left mid-screen: nothing else would drop these
                LIVE.remove(player.getUuid());
                RUNS.remove(player.getUuid(), id);
                return;
            }
            if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            VsScreen hud = LIVE.get(player.getUuid());
            if (hud == null) return;
            try {
                UICommandBuilder b = new UICommandBuilder();
                change.accept(b);
                hud.update(false, b);
            } catch (RuntimeException e) {
                MobBattlePlugin.log("VS screen step failed: " + e);
            }
        }, at, TimeUnit.MILLISECONDS);
    }

    private static void onWorld(PlayerRef player, Runnable task) {
        if (!player.isValid()) return;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                MobBattlePlugin.log("VS screen failed: " + e);
            }
        });
    }

    private static Player playerOf(PlayerRef player) {
        if (!player.isValid()) return null;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getComponent(ref, Player.getComponentType());
    }

    // ---- pictures ----

    /** "model|variant", the key of one picture pair (used.txt lines; a plain model id = variant 0). */
    static String key(String model, int variant) {
        return model + "|" + variant;
    }

    /**
     * A registered VS picture (left, epic pose) of a role or model, for the settings panel's mob icons when the game
     * has none; null when it is not registered (only boot registration reaches every client without a rebuild).
     */
    static String iconFor(String name) {
        if (name == null) return null;
        for (String model : new String[] {name, RoleAppearance.of(name)}) {
            if (model != null && REGISTERED.contains(key(model, 0))) return pathOf(model, 0, false);
        }
        return null;
    }

    /**
     * The "variant" of a model's team column picture: its first pose framed on the face (columnFrame), one picture
     * (not mirrored: every column faces the same way, as Smash's).
     */
    static final int COLUMN = -1;
    /**
     * Team column pictures come pre-cut to their column's width (2026-10-16, user: the scrolling-group clip did not clip
     * in game, pictures spilled into the next columns): variants -1..-3 keep this share of the face-framed square's
     * width, centered on the face. gen-vs-ui.py COLUMN_CROP shows each one at that width, so it never stretches.
     */
    static final double[] COLUMN_CROP = {0.415, 0.304, 0.228};
    /**
     * The crops before 2026-10-16 (exactly the column's width: UI scaling let a few pixels spill onto the next column,
     * user screenshot), still on disk as "_crop<k>.png": the narrower ones are cut from them until drawn again.
     */
    private static final double[] OLD_COLUMN_CROP = {0.436, 0.32, 0.24};

    /** The crop variant for an n-column screen: 3-4 -> -1, 5-6 -> -2, 7-8 -> -3 (3 columns: a margin each side). */
    static int columnVariant(int n) {
        return n <= 4 ? -1 : n <= 6 ? -2 : -3;
    }

    static String pathOf(String model, int variant, boolean right) {
        if (variant < 0) return PICTURES + "MobBattle_Vs_" + safe(model) + "_colc" + (-variant) + ".png";
        return PICTURES + "MobBattle_Vs_" + safe(model) + "_v" + variant + (right ? "_R" : "_L") + ".png";
    }

    /** The finished picture on disk (palette PNG, registered as it is): thumbnails/vs/&lt;model&gt;_v&lt;k&gt;_L|R.png. */
    private static Path fileOf(String model, int variant, boolean right) {
        Path used = usedFile();
        // own folder per picture size: a size change draws them again
        if (used == null) return null;
        Path dir = used.getParent().resolve("p" + PICTURE + "r" + ThumbnailCache.RENDER_VERSION);
        // column crops have their own names: "_v-1_L.png" was the old full-square column picture, and registered as a
        // crop it came out squashed (user, 2026-10-16)
        // "_cut": 5 % narrower than the column (see COLUMN_CROP); the old full-width "_crop" files are not reused
        if (variant < 0) return dir.resolve(safe(model) + "_cut" + (-variant) + ".png");
        return dir.resolve(safe(model) + "_v" + variant + (right ? "_R" : "_L") + ".png");
    }

    /** Every model a mob role wears (the panel's role list -> each role's Appearance): what a VS screen can show. */
    static List<String> fighterModels() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            for (PickLists.Entry e : PickLists.roles()) {
                String model = RoleAppearance.of(e.value());
                if (model != null && ModelAsset.getAssetMap().getAsset(model) != null) out.add(model);
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("VS screen: role list not read: " + e);
        }
        return new ArrayList<>(out);
    }

    /**
     * The pictures to have from boot (2026-10-16, user: no hitch, ever), at most MAX_PAIRS, in this order: every pose
     * of the summon list's mobs, the poses a VS screen wanted before (used.txt), the epic pose of the other mob models.
     */
    private static List<String> bootKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        // players seen before, newest first: their own skin in the VS from the first battle of the session
        for (String hash : PlayerSkins.saved(playersDir(), MAX_PLAYER_SKINS)) {
            keys.add(key(PLAYER_PREFIX + hash, 0));
            for (int v = -1; v >= -COLUMN_CROP.length; v--) keys.add(key(PLAYER_PREFIX + hash, v));
        }
        try {
            for (String entry : SummonList.snapshot().keySet()) {
                String model = RoleAppearance.of(SummonList.roleOf(entry));
                if (model == null) continue;
                for (int v = 0; v < VARIANTS; v++) keys.add(key(model, v));
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("VS screen: summon list not read: " + e);
        }
        Path used = usedFile();
        if (used != null && Files.exists(used)) {
            try {
                for (String line : Files.readAllLines(used, StandardCharsets.UTF_8)) {
                    String k = line.trim();
                    if (!k.isEmpty()) keys.add(k.contains("|") ? k : key(k, 0));
                }
            } catch (IOException e) {
                MobBattlePlugin.log("VS screen: " + used.getFileName() + " unreadable: " + e);
            }
        }
        for (String model : fighterModels()) {
            if (keys.size() >= MAX_PAIRS) break;
            keys.add(key(model, 0));
            for (int v = -1; v >= -COLUMN_CROP.length; v--) keys.add(key(model, v));
        }
        List<String> out = new ArrayList<>(keys);
        return out.size() > MAX_PAIRS ? new ArrayList<>(out.subList(0, MAX_PAIRS)) : out;
    }

    /**
     * BootEvent (ThumbnailCache.startup), before anyone joins: registers the boot pictures already on disk, silently
     * (no asset rebuild: nobody is connected). Skipped when someone is online already.
     */
    static void registerAtBoot() {
        if (!Universe.get().getPlayers().isEmpty()) return;
        long t0 = System.nanoTime();
        int n = 0;
        for (String k : bootKeys()) {
            try {
                if (register(k)) n++;
            } catch (Exception e) {
                MobBattlePlugin.log("VS screen: picture " + k + " not registered: " + e);
            }
        }
        if (n > 0) MobBattlePlugin.log("VS screen: " + n + " picture pairs registered in " + (System.nanoTime() - t0) / 1_000_000 + " ms.");
    }

    /**
     * ThumbnailCache's boot run (background thread), after the thumbnails: draws the boot pictures missing on disk,
     * then registers them if nobody is online yet (else at the next boot).
     */
    static void prepareInBackground() {
        long t0 = System.nanoTime();
        List<String> missing = new ArrayList<>();
        for (String k : bootKeys()) if (!onDisk(k)) missing.add(k);
        if (!missing.isEmpty()) {
            // several low-priority threads (the server keeps the rest of the cores); each render + encode is independent
            int threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 2));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
                Thread t = new Thread(r, "MobBattle-VsPictures");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            java.util.concurrent.atomic.AtomicInteger made = new java.util.concurrent.atomic.AtomicInteger();
            try {
                List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                for (String k : missing) {
                    jobs.add(pool.submit(() -> {
                        try {
                            if (make(k)) made.incrementAndGet();
                        } catch (Exception e) {
                            MobBattlePlugin.log("VS screen: picture " + k + " not drawn: " + e);
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> job : jobs) job.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                MobBattlePlugin.log("VS screen: drawing failed: " + e.getCause());
            } finally {
                pool.shutdownNow();
            }
            MobBattlePlugin.log("VS screen: " + made.get() + " picture pairs drawn on " + threads + " threads in "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms.");
        }
        registerAtBoot();
        // every boot picture of this render version is on disk: older versions' folders (stand-ins until now) go
        Path used = usedFile();
        boolean complete = true;
        for (String k : bootKeys()) {
            if (!onDisk(k) && !k.startsWith(PLAYER_PREFIX)) {
                complete = false;
                break;
            }
        }
        if (complete && used != null) ThumbnailCache.deleteOtherFolders(used.getParent(), "p", "p" + PICTURE + "r" + ThumbnailCache.RENDER_VERSION);
    }

    private static boolean onDisk(String key) {
        int bar = key.lastIndexOf('|');
        String model = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        Path left = fileOf(model, variant, false), right = fileOf(model, variant, true);
        return left != null && Files.exists(left) && (variant < 0 || Files.exists(right));
    }

    /**
     * The file to register for a picture: this render version's, else the newest older one still on disk (2026-10-16,
     * user: after a render change the first boot had no VS pictures until the long background run ended, and the
     * screen fell back to the game's small front-facing icons). The new file is registered at the next boot.
     */
    private static Path existing(String model, int variant, boolean right) {
        Path now = fileOf(model, variant, right);
        if (now == null || Files.exists(now)) return now;
        Path root = now.getParent().getParent();
        for (int v = ThumbnailCache.RENDER_VERSION - 1; v >= 1; v--) {
            Path old = root.resolve("p" + PICTURE + "r" + v).resolve(now.getFileName());
            if (Files.exists(old)) return old;
        }
        Path oldest = root.resolve("p" + PICTURE).resolve(now.getFileName());
        return Files.exists(oldest) ? oldest : now;
    }

    /** Registers both files of a key when they are on disk (an older render version's as a stand-in). */
    private static synchronized boolean register(String key) throws IOException {
        if (REGISTERED.contains(key)) return false;
        int bar = key.lastIndexOf('|');
        String model = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        Path left = existing(model, variant, false), right = existing(model, variant, true);
        byte[] leftBytes = left != null && Files.exists(left) ? Files.readAllBytes(left) : null;
        if (leftBytes == null && variant < 0) leftBytes = cropFromSquare(model, variant);
        if (leftBytes == null && variant < 0) leftBytes = cropFromOldCrop(model, variant);
        if (leftBytes == null || variant >= 0 && !Files.exists(right)) return false;
        String prefix = MorphHologram.registryPrefix();
        CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK, new RuntimeCommonAsset(prefix + pathOf(model, variant, false), leftBytes), false);
        if (variant >= 0) {
            CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK, new RuntimeCommonAsset(prefix + pathOf(model, variant, true), Files.readAllBytes(right)), false);
        }
        REGISTERED.add(key);
        return true;
    }

    /**
     * A column crop cut now from the face-framed square column picture ("_v-1_L.png") of this or an older render
     * version, or null when there is none: the first boot after the crops came shows them right away.
     */
    private static byte[] cropFromSquare(String model, int variant) {
        Path used = usedFile();
        if (used == null) return null;
        Path root = used.getParent();
        String name = safe(model) + "_v-1_L.png";
        for (int v = ThumbnailCache.RENDER_VERSION; v >= 1; v--) {
            Path f = root.resolve("p" + PICTURE + "r" + v).resolve(name);
            if (!Files.exists(f)) continue;
            try {
                BufferedImage sq = ImageIO.read(f.toFile());
                if (sq == null) continue;
                return IndexedPng.encode(crop(sq, variant));
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** A new crop cut from the middle of the old wider "_crop<k>" file of any version (both are centered on the face). */
    private static byte[] cropFromOldCrop(String model, int variant) {
        Path used = usedFile();
        if (used == null) return null;
        Path root = used.getParent();
        int k = -variant;
        String name = safe(model) + "_crop" + k + ".png";
        for (int v = ThumbnailCache.RENDER_VERSION; v >= 1; v--) {
            Path f = root.resolve("p" + PICTURE + "r" + v).resolve(name);
            if (!Files.exists(f)) continue;
            try {
                BufferedImage old = ImageIO.read(f.toFile());
                if (old == null) continue;
                int w = old.getWidth(), cw = Math.max(1, (int) Math.round(w * COLUMN_CROP[k - 1] / OLD_COLUMN_CROP[k - 1]));
                return IndexedPng.encode(old.getSubimage((w - cw) / 2, 0, cw, old.getHeight()));
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    /** The middle of a face-framed square, COLUMN_CROP of its width (the face is mid-square). */
    private static BufferedImage crop(BufferedImage square, int variant) {
        int s = square.getWidth(), cw = Math.max(1, (int) Math.round(s * COLUMN_CROP[-variant - 1]));
        return square.getSubimage((s - cw) / 2, 0, cw, square.getHeight());
    }

    /**
     * Draws a key's two files when missing: the model in that pose (variants), on a square canvas with its feet at the
     * bottom, the right one mirrored so both sides look at the middle, as palette PNGs (IndexedPng: 128 colors, ~14 KB each).
     * A changed model is not noticed: delete thumbnails/vs/ to draw them again.
     */
    private static boolean make(String key) throws Exception {
        int bar = key.lastIndexOf('|');
        String id = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        Path left = fileOf(id, variant, false), right = fileOf(id, variant, true);
        if (left == null || Files.exists(left) && (variant < 0 || Files.exists(right))) return false;
        Model model;
        List<ThumbnailCache.Piece> pieces;
        boolean flier;
        if (id.startsWith(PLAYER_PREFIX)) {
            // a player: the bare body for the animations, every skin part (body first) tinted by its gradient
            var skin = skinOf(id);
            if (skin == null) return false;
            model = com.hypixel.hytale.server.core.cosmetics.CosmeticsModule.get().createModel(skin, 1f);
            pieces = new ArrayList<>();
            for (var a : PlayerLook.attachments(skin)) ThumbnailCache.addPiece(pieces, a);
            if (model == null || pieces.isEmpty()) return false;
            flier = false;
        } else {
            ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
            if (asset == null) return false;
            model = ThumbnailCache.modelOf(asset);
            pieces = ThumbnailCache.piecesOf(model);
            flier = flyingModel(id);
        }
        List<ThumbnailCache.PoseChoice> poses = variants(model, flier);
        // players run (user, 2026-10-16: their idle looked stiff next to the mobs' show-off poses)
        if (id.startsWith(PLAYER_PREFIX)) {
            ThumbnailCache.PoseChoice run = ThumbnailCache.poseOf(model, "run", null);
            if (run != null) poses.set(0, run);
        }
        ThumbnailCache.PoseChoice pose = poses.get(variant < 0 ? 0 : variant % VARIANTS);
        BufferedImage img = ThumbnailCache.render(pieces, ThumbnailCache.load(pose), ModelThumbnail.THREE_QUARTER, PICTURE, true);
        if (img == null) return false;
        if (variant < 0) {
            // one render, every crop width
            BufferedImage framed = columnFrame(img, ModelThumbnail.LAST_HEAD.get(), flier);
            Files.createDirectories(left.getParent());
            // the face-framed square too (not registered): a later crop change is cut from it (cropFromSquare)
            Path square = left.getParent().resolve(safe(id) + "_v-1_L.png");
            if (!Files.exists(square)) Files.write(square, IndexedPng.encode(framed));
            for (int v = -1; v >= -COLUMN_CROP.length; v--) {
                Path f = fileOf(id, v, false);
                if (f != null && !Files.exists(f)) Files.write(f, IndexedPng.encode(crop(framed, v)));
            }
            return true;
        }
        // framed on the body only in a flying pose (a flier's ground pose, the Nightlord's sword run, kept whole)
        boolean flying = flier && pose != null && pose.set() != null && pose.set().startsWith("Fly");
        byte[][] pair = IndexedPng.encodePair(square(img, false, flying));   // one quantization, the right one mirrored
        Files.createDirectories(left.getParent());
        Files.write(left, pair[0]);
        Files.write(right, pair[1]);
        return true;
    }

    /**
     * A team column picture (user, 2026-10-16, after comparing framings): the same square and scale as the halves'
     * picture, but the face centered across; a ground mob keeps its feet on the bottom edge unless its face would be
     * above COLUMN_FACE_MIN of the height (then it moves down and the feet are cut); a flying mob has its face at
     * COLUMN_FACE_FLIER whatever its size, level with the others'. What goes past the edges is cut.
     */
    static BufferedImage columnFrame(BufferedImage src, double[] head, boolean flier) {
        int w = src.getWidth(), h = src.getHeight(), s = Math.max(w, h);
        double hx = head != null ? head[0] : w / 2.0;
        double hy = head != null ? head[1] : h * 0.25;
        int ox = (int) Math.round(s / 2.0 - hx);
        int oy = flier ? (int) Math.round(s * COLUMN_FACE_FLIER - hy) : s - h;
        if (!flier && oy + hy < s * COLUMN_FACE_MIN) oy = (int) Math.round(s * COLUMN_FACE_MIN - hy);
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(src, ox, oy, null);
        g.dispose();
        return out;
    }

    // the face at least this far down the picture (user, 2026-10-16 previews: at 0.30 tall mobs had their face under the header)
    private static final double COLUMN_FACE_MIN = 0.40;
    private static final double COLUMN_FACE_FLIER = 0.40;

    /**
     * The model's VARIANTS poses (2026-10-16, user chose after comparing poses on many mobs): 0 = a show-off set if it
     * has one (Roar, Taunt, Angry, SawCharge...), else standing ready (Alerted; a flying mob: hovering, FlyIdle/Fly),
     * else at rest (Idle), else its auto pose. Running looked bent and off-center in the team columns. Then its auto
     * (run) pose and a jump or idle frame for repeated mobs; when it has fewer, other frames of those (t shifted), so
     * repeats never look the same. Null entries = bind pose (a model with no animations).
     */
    static List<ThumbnailCache.PoseChoice> variants(Model model, boolean flier) {
        List<ThumbnailCache.PoseChoice> out = new ArrayList<>();
        ThumbnailCache.PoseChoice first = null;
        // fliers hover first (user, 2026-10-16: a flier in its ground roar looked stranded in the sky slots)
        if (flier) first = ThumbnailCache.poseOf(model, "FlyIdle", 0.3);
        if (first == null && flier) first = ThumbnailCache.poseOf(model, "Fly", 0.3);
        for (int i = 0; i < EPIC_NAMED && first == null; i++) first = ThumbnailCache.poseOf(model, EPIC[i], 0.45);
        if (first == null) first = ThumbnailCache.poseOf(model, "Alerted", 0.3);
        if (first == null) first = ThumbnailCache.poseOf(model, "Idle", 0.3);
        if (first == null) first = ThumbnailCache.poseOf(model, "auto", null);
        add(out, first);
        add(out, ThumbnailCache.poseOf(model, "auto", null));
        add(out, ThumbnailCache.poseOf(model, "jump", null));
        add(out, ThumbnailCache.poseOf(model, "idle", 0.6));
        List<ThumbnailCache.PoseChoice> base = new ArrayList<>(out);
        for (double shift : new double[] {0.35, 0.65}) {
            for (ThumbnailCache.PoseChoice p : base) {
                add(out, new ThumbnailCache.PoseChoice(p.set(), p.file(), (p.t() + shift) % 1.0));
            }
        }
        while (out.size() < VARIANTS) out.add(out.isEmpty() ? null : out.get(out.size() - 1));
        return out;
    }

    private static volatile java.util.Set<String> flyingModels;

    /** Models worn by a role that flies (PickLists.flies): they pose hovering and keep their face level in columns. */
    static boolean flyingModel(String model) {
        java.util.Set<String> set = flyingModels;
        if (set == null) {
            set = new java.util.HashSet<>();
            try {
                for (PickLists.Entry e : PickLists.roles()) {
                    if (PickLists.flies(e.value())) {
                        String m = RoleAppearance.of(e.value());
                        if (m != null) set.add(m);
                    }
                }
            } catch (RuntimeException e) {
                MobBattlePlugin.log("VS screen: flying roles not read: " + e);
            }
            flyingModels = set;
        }
        return set.contains(model);
    }

    private static void add(List<ThumbnailCache.PoseChoice> out, ThumbnailCache.PoseChoice p) {
        if (p == null || out.size() >= VARIANTS) return;
        for (ThumbnailCache.PoseChoice q : out) {
            if (q.set().equals(p.set()) && Math.abs(q.t() - p.t()) < 0.05) return;
        }
        out.add(p);
    }

    /**
     * Square canvas (the AssetImage box is square), the picture centered with its feet on the bottom edge; mirrored
     * for the right side, so both sides look at the middle (thumbnails look toward the right of the picture).
     */
    static BufferedImage square(BufferedImage src, boolean mirror) {
        return square(src, mirror, false);
    }

    /**
     * The picture's square (user, 2026-10-16: the dragon looked tiny and off to one side): centered on the body (the
     * middle of where most of its pixels are, so thin wings, tails and weapons count little) and zoomed in on a wide
     * model, its sides cut (the square is never narrower than WIDE_FIT of its width). A ground mob keeps its feet on
     * the bottom edge; a flier is framed on its body's dense part (BODY_SHARE of its pixels on each axis), padded.
     */
    static BufferedImage square(BufferedImage src, boolean mirror, boolean flier) {
        int w = src.getWidth(), h = src.getHeight();
        int[] xs = new int[w], ys = new int[h];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((src.getRGB(x, y) >>> 24) < 128) continue;
                xs[x]++;
                ys[y]++;
                count++;
            }
        }
        double cx = count > 0 ? mid(xs, count, 0.5) : w / 2.0;
        int s, oy;
        // only a flier spread wide (wings open: the dragon) is framed on its body; a tall one keeps the whole picture
        // (user, 2026-10-16: the Nightlord, whose role can fly, lost its crown and swords)
        if (flier && count > 0 && w > h * WIDE_FLIER) {
            double x0 = mid(xs, count, (1 - BODY_SHARE) / 2), x1 = mid(xs, count, (1 + BODY_SHARE) / 2);
            double y0 = mid(ys, count, (1 - BODY_SHARE) / 2), y1 = mid(ys, count, (1 + BODY_SHARE) / 2);
            s = (int) Math.round(Math.max(Math.max(x1 - x0, y1 - y0) * FLIER_PAD, h * 0.6));
            oy = (int) Math.round(s / 2.0 - mid(ys, count, 0.5));
        } else {
            s = Math.max(h, (int) Math.round(w * WIDE_FIT));
            oy = s - h;
        }
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        int ox = (int) Math.round(s / 2.0 - cx);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int tx = ox + x;
                if (tx < 0 || tx >= s || oy + y < 0 || oy + y >= s) continue;
                int sx = mirror ? src.getWidth() - 1 - x : x;
                out.setRGB(tx, oy + y, src.getRGB(sx, y));
            }
        }
        return out;
    }

    private static final double WIDE_FIT = 0.72;
    private static final double BODY_SHARE = 0.8;
    private static final double WIDE_FLIER = 1.15;
    private static final double FLIER_PAD = 1.25;

    /** The position below which {@code share} of the counted pixels lie (counts per column or row). */
    private static double mid(int[] counts, int total, double share) {
        double want = total * share, run = 0;
        for (int i = 0; i < counts.length; i++) {
            run += counts[i];
            if (run >= want) return i + 0.5;
        }
        return counts.length;
    }

    /** Appends the poses shown with a fallback picture to used.txt (once each), for the next boot. */
    private static synchronized void rememberWanted() {
        if (WANTED.isEmpty()) return;
        Path used = usedFile();
        if (used == null) return;
        try {
            java.util.Set<String> known = new java.util.HashSet<>();
            if (Files.exists(used)) known.addAll(Files.readAllLines(used, StandardCharsets.UTF_8));
            StringBuilder add = new StringBuilder();
            for (String k : new ArrayList<>(WANTED)) {
                WANTED.remove(k);
                if (known.add(k)) add.append(k).append(System.lineSeparator());
            }
            if (add.length() == 0) return;
            Files.createDirectories(used.getParent());
            Files.writeString(used, add, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: used.txt not written: " + e);
        }
    }

    private static Path usedFile() {
        Path d = FxSettings.dataDirectory();
        return d == null ? null : d.resolve("thumbnails").resolve("vs").resolve("used.txt");
    }

    /** "#rrggbb" times {@code k} (a dark half behind the bright edge). */
    private static String darken(String hex, double k) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            int v = Integer.parseInt(h.substring(0, 6), 16);
            int r = (int) (((v >> 16) & 255) * k), g = (int) (((v >> 8) & 255) * k), b = (int) ((v & 255) * k);
            return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
        } catch (RuntimeException e) {
            return "#202020";
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
