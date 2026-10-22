package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
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
    /** A plain battle royale: VsScreen.ui with the halves not cut at the slant (gen-vs-ui.py, 2026-10-18). */
    private static final String UI_ROYALE = "MobBattle/VsRoyale.ui";
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
    /**
     * A mob with no picture registered yet: a big "?" in the VS style, the size of a mob, on a transparent square so
     * the team color shows behind it (2026-10-17, user: never the game's small red X or a 64 px icon blown up).
     * scripts/gen-vs-assets.py --unknown. The settings panel uses it too (ConfigPage.icon).
     */
    static final String UNKNOWN = FOLDER + "Unknown.png";
    /** A player with no picture registered yet: the bare player body running, all black, facing the middle. */
    private static final String SILHOUETTE_L = FOLDER + "PlayerSilhouette_L.png";
    private static final String SILHOUETTE_R = FOLDER + "PlayerSilhouette_R.png";
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
    /** 1 px picture slots that load the screen's pictures before the slide (VsScreen.ui #Pre0.., gen-vs-ui.py PRELOAD). */
    private static final int PRELOAD = 64;
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
    /**
     * The layouts' slots (positions, sizes, drawing order), written by scripts/gen-vs-ui.py next to VsScreen.ui, and the
     * generic slots each half has (2026-10-17: one picture and one halo per slot, placed by Anchor here instead of
     * 4 sizes x 40 slots of nested groups in the document; that was ~1300 elements re-laid out on every slide step).
     */
    private static final Slots LAYOUTS = Slots.load();
    private static final int GENERIC_SLOTS = 28;
    private static final int[] NAMED_BACK_FIRST = LAYOUTS.namedDraw;
    private static final int[] MEDIUM_BACK_FIRST = LAYOUTS.mediumDraw;
    // the slots' drawing order in VsScreen.ui (gen-vs-ui.py --depth): the tallest take the first drawn, the smallest end
    // up on top (user, 2026-10-17: big mobs behind, small ones in front; by row only, the tallest of a row was on top)
    private static final int[] GROUND_BACK_FIRST = LAYOUTS.groundDraw();
    /** The name bar fits about this many characters at its font size; past it, kinds go into "+N". */
    private static final int NAMES_CHARS = 24;   // 28 still ran over the slant on the right half (user, 2026-10-16)
    private static final String LEFT_COLOR = "#e03030";
    private static final String RIGHT_COLOR = "#3070e0";

    // a frame every 20 ms (50 per second) over a 450 ms slide (user video, 2026-10-16: 6 frames at 50 ms arrived 33-67 ms
    // apart and the slide looked jerky); each frame is one tiny HUD packet to that viewer
    private static final long STEP_MS = 20;
    /**
     * The halves' slide steps (2026-10-17, recordings with frame timestamps: at 20 ms the client drew no frame for the
     * whole 450 ms slide, every update re-lays out the ~1300 elements, so the halves popped in). Fewer, larger steps.
     */
    private static final long SLIDE_STEP_MS = 50;
    private static final long SLIDE_IN_MS = 450;
    private static final long SLIDE_OUT_MS = 400;
    private static final long FLASH_MS = 120;
    /** A half's width at rest, in the thousandths the halves' FlexWeights use (they overlap at the slant). */
    private static final int HALF = 580;
    /**
     * Picture sizes of a slot (VsScreen.ui, scripts/gen-vs-ui.py TIERS): 100%, 85%, 72%, 60%. The size follows
     * (height / tallest) ^ SIZE_POWER, like the reference card (user, 2026-10-16: a 2.5-block mob next to a 6-block
     * one at 70%, not 40%; heights shown but not exaggerated).
     */
    private static final double[] TIER_AT = {0.875, 0.65, 0.465};
    /**
     * Softer than the reference card's 0.35 (user, 2026-10-16: sizes even like the columns, but a tiny Kweebec seedling
     * looked as big as a boss): a mob at 40% of the tallest keeps 80% of the size, one at 12% shrinks to 60%.
     */
    private static final double SIZE_POWER = 0.5;
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
    static final int MAX_PAIRS = 3600;   // keys: each mob model = its pose pair + its column square (2026-10-17)
    /** Threads drawing the boot pictures (fewer when the machine has fewer cores). */
    private static final int MAX_THREADS = 4;

    /** Poses shown with a fallback picture this session: written to used.txt, registered at the next boot. */
    private static final Set<String> WANTED = ConcurrentHashMap.newKeySet();
    /** Model ids whose two pictures are registered. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    /** Registered keys whose files came from an older version's folder -> the bytes registered (register()). */
    private static final Map<String, Long> STAND_INS = new ConcurrentHashMap<>();
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
    /** Column blocks (a team's columns, or neighbouring teams) rise this far apart. */
    private static final long BLOCK_GAP_MS = 140;
    /** At most this many blocks rise one after another; more teams share a block. */
    private static final int MAX_RISE_BLOCKS = 4;
    /** A column's rise from the bottom of the screen. */
    private static final long RISE_MS = 300;
    /** One update for all rising columns every this many ms (30 a second; 20 ms per column froze 8 columns). */
    private static final long COLUMN_STEP_MS = 33;
    private static final Side NO_SIDE = new Side(Message.raw(""), LEFT_COLOR, List.of(), 0, NAMED, "", List.of());

    private final Plan plan;

    private VsScreen(PlayerRef player, Plan plan) {
        super(player, KEY);
        this.plan = plan;
    }

    @Override
    protected void build(UICommandBuilder b) {
        // only the document this plan shows (user, 2026-10-16: one file with the halves and all six column screens was
        // ~570 pictures' worth of elements built for every VS, and the client froze through the animation)
        // a plain royale's fighters cross the middle (no seam to cut them at)
        b.append(plan.columns() != null ? "MobBattle/VsCols" + plan.columns().size() + ".ui" : plainRoyale(plan) ? UI_ROYALE : UI);
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
        scaleScreen();
        // a plain battle royale has no sides: one background over the whole screen, not the split halves (user,
        // 2026-10-16); royale() is the only plan with both labels empty (an army royale has named sides)
        boolean oneBg = plainRoyale(plan);
        if (oneBg) {
            b.set("#RoyaleBg.AssetPath", FOLDER + "Bg_Royale.png");
            b.set("#RoyaleBg.Visible", true);
            // one centered title instead of the halves' names (shown with the clash, animate)
            I18n.text(b, "#RoyaleHeadText.Text", I18n.tr("battle.label.royale"));
        }
        fill(b, "L", plan.left(), false, tallest, oneBg);
        fill(b, "R", plan.right(), true, tallest, oneBg);
        // every picture also 1 px behind everything, so the client loads them while the screen waits to slide (a
        // royale's ~48 loaded at the first frame of the slide froze it 0.8 s: the crowds popped in; 2026-10-17)
        java.util.LinkedHashSet<String> pre = new java.util.LinkedHashSet<>();
        for (Fighter f : plan.left().shown()) if (f != null) pre.add(picture(f, false));
        for (Fighter f : plan.right().shown()) if (f != null) pre.add(picture(f, true));
        // and the screen's own art, shown later (logo, title, glows, badge): first drawn at the clash, they froze it too
        pre.add(LOGO);
        if (plan.kind() != null) pre.add(titlePicture(plan.kind(), getPlayerRef()));
        pre.add(FOLDER + "Glow_L.png");
        pre.add(FOLDER + "Glow_R.png");
        pre.add(FOLDER + "MoreBadge.png");
        int k = 0;
        for (String pic : pre) {
            if (k >= PRELOAD) break;
            b.set("#Pre" + k + ".AssetPath", pic);
            b.set("#Pre" + k + ".Visible", true);
            k++;
        }
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
        b.set("#Cols.Visible", true);
        b.set("#Cols" + n + ".Visible", true);
        int font = Math.max(24, Math.min(44, Math.round(1920f / n / 11)));
        int chars = Math.max(5, (int) ((1920.0 / n - 24) / (font * 0.6)));
        for (int i = 0; i < n; i++) {
            Column c = columns.get(i);
            String p = "#C" + n + "P" + i;
            // shown from the start, still below the screen (Body at weight 1): the client loads every picture before
            // the columns rise (user, 2026-10-16: with 8 columns the game froze and the rise was never seen)
            b.set(p + "In.Visible", true);
            b.setObject(p + "Body.Background", new com.hypixel.hytale.server.core.ui.PatchStyle()
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
            // shown when the column lands (runColumns), with its picture
            b.set(p + "Tag.Visible", false);
            b.set(p + "Name.Visible", false);
            // one face-framed square per mob, wider than the column and cut to it by the column's mask (#..Clip,
            // MaskTexturePath: a Group with a mask hides what its children draw outside it; user test 2026-10-17),
            // every portrait the same size so the faces sit on one line
            boolean right = i >= (n + 1) / 2;
            String pic = columnPicture(c.rep());
            b.set(p + "Pic.Visible", pic != null);
            if (pic != null) b.set(p + "Pic.AssetPath", pic);
            b.set(p + "Sq.Visible", pic == null);
            if (pic == null) b.set(p + "Sq.AssetPath", picture(c.rep(), right));
        }
    }

    /**
     * A name that fits {@code chars}: technical endings dropped first ("PHASE1", "MODEL"), then leading words
     * ("GOBLIN GOBLITERATOR PHASE1" -> "GOBLITERATOR"), then cut with a dot (user, 2026-10-16: "GOBLIN GOBLITERAT").
     */
    static String shortName(String name, int chars) {
        String n = name.trim();
        // "Name, Title" (mods' names: "Barón, General de la Plata"): the name before the comma, not the title after it
        // (user screenshot 2026-10-17: the VS showed "GENERAL DE LA PLATA")
        int comma = n.indexOf(',');
        if (n.length() > chars && comma > 0 && comma <= chars) n = n.substring(0, comma).trim();
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
    /** A battle royale with no named sides (royale(); an army royale names its sides): one background, one title. */
    private static boolean plainRoyale(Plan plan) {
        return plan.columns() == null && plan.kind() == BattleManager.Kind.ROYALE && isEmpty(plan.left().label())
                && isEmpty(plan.right().label());
    }

    private static boolean isEmpty(Message m) {
        return m.getRawText() != null && m.getRawText().isEmpty();
    }

    private void fill(UICommandBuilder b, String s, Side side, boolean right, double tallest, boolean oneBg) {
        b.set("#" + s + "Bg.AssetPath", FOLDER + "Bg_" + Teams.nearestPalette(side.color()) + "_" + s + ".png");
        b.set("#" + s + "Bg.Visible", !oneBg);
        b.set("#" + s + "Team.TextSpans", side.label().color(side.color()));
        b.set("#" + s + "Names.Text", side.names());
        // the texts come in at the clash, with the pictures (user, 2026-10-16: they showed long before the pictures
        // had loaded); shown in the impact step, so no extra update
        b.set("#" + s + "Team.Visible", false);
        b.set("#" + s + "Names.Visible", false);
        place(b, s, side, right, tallest);
        b.set("#" + s + "MoreBox.Visible", false);
        if (side.more() > 0) {
            b.set("#" + s + "More.Text", "+" + side.more());
            b.set("#" + s + "MoreBadge.AssetPath", FOLDER + "MoreBadge.png");
        }
    }

    /**
     * The side's fighters into the half's generic slots, in the layout's drawing order (slot 0 furthest back): each
     * picture at its layout slot, sized by its height against the tallest on screen (tierOf), feet on the slot's
     * feet line, hanging on the half's inner edge (Right in the left half, Left in the right one) so it slides in with
     * the half; its halo behind it. Anchor set as vanilla MemoriesPage does (setObject).
     */
    /*
     * Sizes (2026-10-18, user, after comparing every screen offline with scripts/vs-preview.py --rule scale): the slots
     * only say where a fighter stands, no more per-slot sizes (with 9 a side the tallest went to a 490 px back slot and
     * the smallest to a 760 px front one: a fire golem looked smaller than a hawk; with 3 it was the other way round).
     * One scale for the whole screen: each body is measured on its own picture (its square in blocks = ratio x the
     * fighter's real hitbox height, so a mob a mod scaled at spawn counts big, times the share of the square its
     * opaque rows fill), the reference height is the tallest body but at most GIANT x the median one and never under
     * MIN_REF blocks (a screen of fish is not blown up to a human's size), a body's share of the target is
     * (body / reference) ^ SIZE_POWER (order and differences kept, small ones not crushed), at least FLOOR; a giant
     * above the reference keeps growing up to GIANT_MAX (GIANT_MAX_FEW with 1-3 a side) and towers over the rest, its
     * head kept under TOP. No picture above PICTURE_ZOOM x its own pixels (a 276 px koi drawn 1000 px was a blur).
     */
    private static final double GIANT = 1.5, MIN_REF = 1.6, FLOOR = 0.15, GIANT_MAX = 1.6, GIANT_MAX_FEW = 1.45,
            PICTURE_ZOOM = 3.0, TARGET_SHARE = 0.85, TOP = 150;
    /**
     * The split between the halves at the top and the bottom of the screen (gen-vs-assets.py TOP / BOTTOM). At the
     * floor the left half has 820 px and the right 1100 (user, 2026-10-18: the left side's inner mob was always cut
     * by the split, the right one had empty room by it): each half lays its slots out over the room it really has at
     * the height of each body, and a body still over the split, or past the screen's outer edge, is pushed back until
     * at most EDGE_CUT of its width is cut.
     */
    private static final double SPLIT_TOP = 1100, SPLIT_BOTTOM = 820, SCREEN_W = 1920, SCREEN_H = 1080,
            EDGE_CUT = 0.12, EDGE_GAP = 8;

    /** This screen's reference body height (blocks) and the size (px) a body that tall gets; few = 1-3 a side. */
    private double screenRef = MIN_REF, screenTarget = 600;
    private boolean screenFew;

    /** Fills screenRef, screenTarget and screenFew from both halves (build()). */
    private void scaleScreen() {
        List<Double> bodies = new ArrayList<>();
        double target = Double.MAX_VALUE;
        int most = 0;
        for (Side side : List.of(plan.left(), plan.right())) {
            double[][] slots = slotsOf(side);
            List<Double> sizes = new ArrayList<>();
            List<Fighter> shown = side.shown();
            int count = 0;
            for (int i = 0; i < shown.size(); i++) {
                Fighter f = shown.get(i);
                if (f == null) continue;
                count++;
                bodies.add(bodyBlocks(f));
                // the ground slots only (the crowd's sky slots are small on purpose)
                if (i < slots.length && (side.layout() != MOUND || i < LAYOUTS.groundCount())) sizes.add(slots[i][2]);
            }
            most = Math.max(most, count);
            if (!sizes.isEmpty()) {
                java.util.Collections.sort(sizes);
                target = Math.min(target, sizes.get(sizes.size() / 2) * TARGET_SHARE);
            }
        }
        if (bodies.isEmpty()) return;
        java.util.Collections.sort(bodies);
        double median = bodies.get(bodies.size() / 2);
        screenRef = Math.max(MIN_REF, Math.min(bodies.get(bodies.size() - 1), GIANT * median));
        screenTarget = target == Double.MAX_VALUE ? 600 : target;
        screenFew = most <= 3;
    }

    private static double[][] slotsOf(Side side) {
        return side.layout() == NAMED ? LAYOUTS.named : side.layout() == MEDIUM ? LAYOUTS.medium : LAYOUTS.crowd;
    }

    /** How tall the fighter's body is drawn, in blocks: its square in blocks times the share its opaque rows fill. */
    private static double bodyBlocks(Fighter f) {
        Body b = bodyOf(f);
        return Math.max(0.05, ratioOf(f) * f.height() * (b.bottom() - b.top()));
    }

    /** Where the split is at screen height y. */
    private static double splitX(double y) {
        return SPLIT_TOP + (SPLIT_BOTTOM - SPLIT_TOP) * Math.max(0, Math.min(1, y / SCREEN_H));
    }

    private void place(UICommandBuilder b, String s, Side side, boolean right, double tallest) {
        double[][] slots = slotsOf(side);
        int[] draw = side.layout() == NAMED ? LAYOUTS.namedDraw : side.layout() == MEDIUM ? LAYOUTS.mediumDraw : LAYOUTS.crowdDraw;
        List<Fighter> shown = side.shown();
        int k = 0;
        for (int idx : draw) {
            if (k >= GENERIC_SLOTS) break;
            Fighter f = idx < shown.size() ? shown.get(idx) : null;
            if (f == null || idx >= slots.length) continue;
            double cx = slots[idx][0], feet = slots[idx][1];
            Body bd = bodyOf(f);
            double sq = ratioOf(f) * f.height(), body = bodyBlocks(f);
            double rel = body / screenRef;
            double share = Math.max(FLOOR, Math.min(screenFew ? GIANT_MAX_FEW : GIANT_MAX,
                    Math.pow(Math.min(1, rel), SIZE_POWER) * Math.max(1, rel)));
            double size = Math.min(screenTarget * share * sq / body, bd.px() * PICTURE_ZOOM);
            // its feet on the slot's feet line, the head never above TOP (a giant, a flier in the sky slots)
            double top = feet - size;
            if (top + bd.top() * size < TOP) top = TOP - bd.top() * size;
            double x = screenX(cx, top, size, bd, right);
            b.setObject("#" + s + "P" + k + ".Anchor", anchorAt(x, top, size, right));
            b.set("#" + s + "P" + k + ".AssetPath", picture(f, right));
            b.set("#" + s + "P" + k + ".Visible", true);
            // the halo behind each fighter, not in a crowd (24 overlapping mobs hide it, and each picture on screen
            // makes every slide step slower: recordings 2026-10-17)
            if (side.layout() != MOUND) {
                double g = size * 0.95, gy = top + size * 0.55;
                b.setObject("#" + s + "G" + k + ".Anchor", anchorAt(x + (size - g) / 2, gy - g / 2, g, right));
                b.set("#" + s + "G" + k + ".AssetPath", FOLDER + (right ? "Glow_R.png" : "Glow_L.png"));
                b.set("#" + s + "G" + k + ".Visible", true);
            }
            k++;
        }
    }

    /**
     * Screen x of the picture's left edge: the slot (cx in a 1113 px half box, the right half mirrored) spread over
     * the room the half has at the body's height, then pushed off the split and the screen's outer edge (the right
     * picture is the left one mirrored, so its body spans 1 - right .. 1 - left).
     */
    private static double screenX(double cx, double top, double size, Body bd, boolean right) {
        double edge = splitX(top + size * 0.6);   // a little below the middle of the body: where it is widest
        double bw = bd.right() - bd.left();
        double x;
        if (!right) {
            x = cx * edge / LAYOUTS.box - size / 2;
            double inner = x + size * (bd.right() - EDGE_CUT * bw);
            if (inner > edge - EDGE_GAP) x -= inner - (edge - EDGE_GAP);
            double outer = x + size * (bd.left() + EDGE_CUT * bw);
            if (outer < 0) x -= outer;
        } else {
            x = SCREEN_W - cx * (SCREEN_W - edge) / LAYOUTS.box - size / 2;
            double inner = x + size * (1 - bd.right() + EDGE_CUT * bw);
            if (inner < edge + EDGE_GAP) x += edge + EDGE_GAP - inner;
            double outer = x + size * (1 - bd.left() - EDGE_CUT * bw);
            if (outer > SCREEN_W) x -= outer - SCREEN_W;
        }
        return x;
    }

    /** A square of {@code size} with its left edge at screen x {@code x}, hung on the half's inner edge. */
    private static com.hypixel.hytale.server.core.ui.Anchor anchorAt(double x, double top, double size, boolean right) {
        var a = new com.hypixel.hytale.server.core.ui.Anchor();
        int px = (int) Math.round(size);
        a.setWidth(com.hypixel.hytale.server.core.ui.Value.of(px));
        a.setHeight(com.hypixel.hytale.server.core.ui.Value.of(px));
        a.setTop(com.hypixel.hytale.server.core.ui.Value.of((int) Math.round(top)));
        // the left half's box runs from the screen's left edge, the right half's ends at its right edge (at rest)
        if (right) a.setLeft(com.hypixel.hytale.server.core.ui.Value.of((int) Math.round(x - (SCREEN_W - LAYOUTS.box))));
        else a.setRight(com.hypixel.hytale.server.core.ui.Value.of((int) Math.round(LAYOUTS.box - (x + size))));
        return a;
    }

    /** Where the body sits in a picture (fractions of its side) and the picture's own pixels. */
    record Body(double top, double bottom, double left, double right, int px) {
    }

    private static final Body FULL_BODY = new Body(0, 1, 0, 1, PICTURE);

    /** key -> its picture's body (saved next to the ratio when drawn, else read from the picture once). */
    private static final Map<String, Body> BODIES = new ConcurrentHashMap<>();

    /** The opaque part of a picture (alpha over 40). */
    static Body bodyIn(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) <= 40) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return new Body(0, 1, 0, 1, w);
        return new Body(y0 / (double) h, (y1 + 1) / (double) h, x0 / (double) w, (x1 + 1) / (double) w, w);
    }

    /** The body of the picture a fighter shows (its pose, else its variant 0, as picture() picks). */
    private static Body bodyOf(Fighter f) {
        if (f == null || f.model() == null) return FULL_BODY;
        String k = usable(key(f.model(), f.variant())) ? key(f.model(), f.variant()) : key(f.model(), 0);
        if (!usable(k)) return FULL_BODY;   // the fallback icon / "?": a full square
        return BODIES.computeIfAbsent(k, key -> {
            try {
                int bar = key.lastIndexOf('|');
                Path left = existing(key.substring(0, bar), Integer.parseInt(key.substring(bar + 1)), false);
                BufferedImage img = left != null && Files.exists(left) ? javax.imageio.ImageIO.read(left.toFile()) : null;
                return img != null ? bodyIn(img) : FULL_BODY;
            } catch (Exception e) {
                return FULL_BODY;
            }
        });
    }

    /** The layouts written by gen-vs-ui.py (src/main/resources/dev/dackoro/mobbattle/vs-slots.json). */
    private record Slots(double box, double[] tiers, double[][] named, int[] namedDraw, double[][] medium, int[] mediumDraw,
                         double[][] crowd, int[] crowdDraw, int groundCount) {

        int[] groundDraw() {
            return java.util.Arrays.stream(crowdDraw).filter(i -> i < groundCount).toArray();
        }

        static Slots load() {
            try (var in = VsScreen.class.getResourceAsStream("vs-slots.json")) {
                var o = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                return new Slots(o.get("box").getAsDouble(), doubles(o.getAsJsonArray("tiers")),
                        table(o.getAsJsonArray("named")), ints(o.getAsJsonArray("namedDraw")),
                        table(o.getAsJsonArray("medium")), ints(o.getAsJsonArray("mediumDraw")),
                        table(o.getAsJsonArray("crowd")), ints(o.getAsJsonArray("crowdDraw")), o.get("groundCount").getAsInt());
            } catch (Exception e) {
                throw new IllegalStateException("VS screen: vs-slots.json not read", e);
            }
        }

        private static double[] doubles(com.google.gson.JsonArray a) {
            double[] out = new double[a.size()];
            for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
            return out;
        }

        private static int[] ints(com.google.gson.JsonArray a) {
            int[] out = new int[a.size()];
            for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsInt();
            return out;
        }

        private static double[][] table(com.google.gson.JsonArray a) {
            double[][] out = new double[a.size()][];
            for (int i = 0; i < out.length; i++) out[i] = doubles(a.get(i).getAsJsonArray());
            return out;
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
    /**
     * A team column's picture (its face-framed square, any column count: the column's mask cuts it), or null: the
     * column then shows the square fallback (#..Sq: the halves' picture, the "?" or a player's silhouette, as wide as
     * the column on its bottom). A missing one is remembered and drawn at the next boot.
     */
    private static String columnPicture(Fighter f) {
        if (f.model() == null) return null;
        if (usable(key(f.model(), COLUMN))) return pathOf(f.model(), COLUMN, false);
        WANTED.add(key(f.model(), COLUMN));
        return null;
    }

    private static String picture(Fighter f, boolean right) {
        if (f.model() == null) return UNKNOWN;
        if (usable(key(f.model(), f.variant()))) return pathOf(f.model(), f.variant(), right);
        WANTED.add(key(f.model(), f.variant()));
        if (usable(key(f.model(), 0))) return pathOf(f.model(), 0, right);
        // No picture yet (drawn and registered at the next boot): a player's black silhouette, else the big "?". Not
        // the mob's own 64 px icon any more: blown up to a fighter's size it looked broken (user, 2026-10-17).
        if (f.model().startsWith(PLAYER_PREFIX)) return right ? SILHOUETTE_R : SILHOUETTE_L;
        return UNKNOWN;
    }

    private static void weights(UICommandBuilder b, int half) {
        // never 0: a FlexWeight of 0 does not collapse a group, it takes its content's size (user video, 2026-10-16:
        // the halves showed half open for 130 ms, jumped back and then slid in)
        int h = Math.max(1, Math.min(HALF, half));
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
        if (!FxSettings.serverVs) return;   // server feature off (/mobbattle serverVs)
        run(player, plan, totalMs);
    }

    /** A VS shown in this tick (FightCamera.withVs), for the countdown right after it (Countdown.run). */
    private record Shown(long at, java.util.Set<Ref<EntityStore>> fighters, long ms) {
    }

    private static final ThreadLocal<Shown> JUST_SHOWN = new ThreadLocal<>();

    /** FightCamera.withVs: a VS screen was shown to someone for these fighters (same thread, same tick). */
    static void markShown(java.util.Set<Ref<EntityStore>> fighters, long ms) {
        JUST_SHOWN.set(new Shown(System.currentTimeMillis(), fighters, ms));
    }

    /**
     * Countdown.run: whether a VS screen was just shown for any of {@code mobs} (then the countdown grows by its time).
     * Clears the mark. A countdown without one (the Battle Wand's "mob vs you", tests, nobody to show it to) keeps its
     * own length (audit 2026-10-17: 3 silent seconds were added to them).
     */
    static long takeShown(java.util.Collection<Ref<EntityStore>> mobs) {
        Shown s = JUST_SHOWN.get();
        JUST_SHOWN.remove();
        boolean ours = s != null && System.currentTimeMillis() - s.at() < 2000 && !java.util.Collections.disjoint(s.fighters(), mobs);
        return ours ? s.ms() : 0;
    }

    /** Takes this player's VS screen off now (its countdown fell, was replaced, skipped or stopped). */
    static boolean cancel(PlayerRef player) {
        if (player == null) return false;
        Long id = RUNS.get(player.getUuid());
        return id != null && end(player, id);
    }

    /** /mobbattle stop and plugin shutdown: every VS screen goes. */
    static void stopAll() {
        for (Map.Entry<UUID, Long> e : RUNS.entrySet()) {
            PlayerRef p = com.hypixel.hytale.server.core.universe.Universe.get().getPlayer(e.getKey());
            if (p != null) {
                end(p, e.getValue());
            } else {
                RUNS.remove(e.getKey(), e.getValue());
                LIVE.remove(e.getKey());
                HID.remove(e.getKey());
                ENDS.remove(e.getKey());
                CameraHud.forgetChat(e.getKey());
            }
        }
    }

    /**
     * The end of run {@code id}: the bookkeeping first, on the calling thread, so {@link #showing} is false even when
     * the world task below never runs (the player changed world, the world stopped; audit 2026-10-17: the game's HUD
     * then stayed hidden, every later restore deferred to a VS that never ended). Then the HUD comes off on the
     * player's world and the game's HUD comes back, unless a camera that hides it too is filming or about to.
     */
    private static boolean end(PlayerRef player, long id) {
        UUID uuid = player.getUuid();
        if (!RUNS.remove(uuid, id)) return false;
        ENDS.remove(uuid);
        VsScreen hud = LIVE.remove(uuid);
        boolean ours = HID.remove(uuid);
        onWorld(player, () -> {
            Player p = playerOf(player);
            if (p != null && hud != null && p.getHudManager().getCustomHud(KEY) == hud) p.getHudManager().removeCustomHud(player, KEY);
            boolean camera = FightCamera.busy(player) || FightCamera.introComing(player) || FilmCamera.isFilming(player);
            // a camera keeps the HUD hidden only with cameraHud hide; with show it comes back now
            if ((CameraHud.takeDeferred(player) || ours) && (!camera || !"hide".equals(FxSettings.cameraHud))) {
                CameraHud.restore(player);
            }
            CameraHud.showChat(player);
        });
        return true;
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
            Plan armies = armyRoyale(bySide, store);
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
    private static Plan armyRoyale(Map<Integer, List<Ref<EntityStore>>> bySide, ComponentAccessor<EntityStore> store) {
        List<Side> armies = new ArrayList<>();
        List<Ref<EntityStore>> free = new ArrayList<>();
        for (List<Ref<EntityStore>> mobs : bySide.values()) {
            String army = mobs.size() > 1 ? BattleManager.armyName(mobs.get(0), store) : null;
            if (army == null) {
                free.addAll(mobs);
                continue;
            }
            // a leader mob by its game name ("Goblin Feastmaster", not "GOBLIN_FEASTMASTER")
            armies.add(side(mobs, Message.raw(MobNames.shared(army)), SIDE_COLORS[armies.size() % SIDE_COLORS.length], store));
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

    /** A royale's fighters by army chain (Army.topOf), each free fighter alone. */
    private static Map<Integer, List<Ref<EntityStore>>> byArmy(List<Ref<EntityStore>> all, ComponentAccessor<EntityStore> acc) {
        Map<Integer, List<Ref<EntityStore>>> out = new LinkedHashMap<>();
        Map<UUID, Integer> index = new HashMap<>();
        int free = -1;
        for (Ref<EntityStore> r : all) {
            UUID top = Army.topOf(r, Army.keyOf(r, acc));
            int k = top != null ? index.computeIfAbsent(top, t -> index.size()) : free--;
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(r);
        }
        return out;
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
        // also when the names line holds a shortened copy of it (user, 2026-10-16: a duel of a long-named mob showed
        // "ARCANEPOWER SKELETON THUNDER WRAITH" over "... THUNDER WR")
        String raw = label.getRawText();
        boolean oneKind = all.stream().map(Fighter::name).distinct().count() == 1;
        if (raw != null && (raw.equalsIgnoreCase(names) || oneKind && raw.equalsIgnoreCase(all.get(0).name()))) {
            label = Message.raw("");
        }
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
        // health read once per mob (the comparator read two components on every comparison: n log n reads on the
        // world thread for a royale of thousands; audit 2026-10-17)
        Map<Ref<EntityStore>, Double> hp = new HashMap<>();
        for (Ref<EntityStore> r : all) hp.put(r, health(r, acc));
        List<Ref<EntityStore>> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(r -> -hp.get(r)));
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
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            // a long name loses its leading words before it is cut ("SKELETON THUNDER WRAITH", not "ARCANEPOWER SKE...")
            String count = e.getValue() > 1 ? " x" + e.getValue() : "";
            parts.add(shortName(e.getKey(), NAMES_CHARS - count.length()) + count);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String sep = i == 0 ? "" : " \u00b7 ";
            String rest = i + 1 < parts.size() ? " \u00b7 +" + (parts.size() - i - 1) : "";
            if (i == 0 && !rest.isEmpty() && parts.get(0).length() + rest.length() > NAMES_CHARS) {
                // the first kind shortened so its "\u00b7 +N" still fits (it was cut off with "..." after it)
                sb.append(shortName(parts.get(0), Math.max(6, NAMES_CHARS - rest.length()))).append(rest);
                break;
            }
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

    /** The shared skin folder (vs/players): ThumbnailCache's 30-day clean-up removes skins nobody joined with. */
    static Path playersDir() {
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
        if (!FxSettings.serverVs) return;
        if (player == null || !player.isValid()) return;
        Store<EntityStore> store = player.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!player.isValid()) return;
            String id = playerModelOf(player, store);
            if (id == null) return;
            var skin = SKINS.get(id);
            PlayerRef who = store.getComponent(player, PlayerRef.getComponentType());
            String name = who != null ? who.getUsername() : "?";
            CompletableFuture.runAsync(() -> {
                try {
                    Path dir = playersDir();
                    if (dir == null) return;
                    String hash = id.substring(PLAYER_PREFIX.length());
                    if (!Files.exists(dir.resolve(hash + ".properties"))) PlayerSkins.save(dir, hash, skin);
                    else PlayerSkins.touch(dir, hash);
                    markSeenHere(hash);
                    int drawn = 0, registered = 0;
                    List<String> ks = List.of(key(id, 0), key(id, COLUMN));
                    for (String k : ks) {
                        if (!onDisk(k) && make(k)) drawn++;
                        if (register(k)) registered++;
                    }
                    // always, with the name (2026-10-17, user: test that a new player's skin is drawn live)
                    MobBattlePlugin.log("VS screen: " + name + "'s skin " + hash + ": " + drawn + " drawn, " + registered
                            + " registered" + (registered == 0 && !REGISTERED.contains(key(id, 0))
                            ? " (sent when they are picked for a battle, or from the next restart)" : "") + ".");
                } catch (Exception e) {
                    MobBattlePlugin.log("VS screen: player picture not made: " + e);
                }
            });
        });
    }

    /**
     * A player picked for a battle (BattleManager.markPlayer): they are about to be in a VS, so their skin picture is
     * drawn if missing and registered now, even with people online (user, 2026-10-16: the toast is accepted here), then
     * one asset rebuild. Mobs and morphed players use the mob pictures.
     */
    static void playerPicked(Ref<EntityStore> player) {
        if (!FxSettings.serverVs) return;
        if (player == null || !player.isValid()) return;
        Store<EntityStore> store = player.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!player.isValid()) return;
            String id = playerModelOf(player, store);
            if (id == null || !id.startsWith(PLAYER_PREFIX) || REGISTERED.contains(key(id, 0))) return;
            CompletableFuture.runAsync(() -> {
                try {
                    int registered = 0;
                    List<String> ks = List.of(key(id, 0), key(id, COLUMN));
                    for (String k : ks) {
                        if (!onDisk(k)) make(k);
                        if (register(k, true)) registered++;
                    }
                    if (registered > 0) ThumbnailCache.lateDone("player pictures");
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
        return MobNames.shared(role).trim();
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
            // royale (one list): its armies are sides (BattleManager.startRoyale gives each army chain one; grouped here
            // the same way, the battle does not exist yet during its countdown; audit 2026-10-17: only /mobbattle test vs
            // showed them), else dealt into two crowds
            if (Army.any()) {
                Plan armies = armyRoyale(byArmy(live.get(0), acc), acc);
                if (armies != null) return armies;
            }
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
        ENDS.put(player.getUuid(), System.currentTimeMillis() + totalMs + 300);
        onWorld(player, () -> {
            Player p = playerOf(player);
            if (p == null) return;
            var huds = p.getHudManager();
            if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            if (huds.getCustomHud(KEY) != null) huds.removeCustomHud(player, KEY);
            VsScreen hud = new VsScreen(player, plan);
            huds.addCustomHud(player, hud);
            LIVE.put(player.getUuid(), hud);
            // a title still on screen (an earlier battle's start or win) would sit over the VS (user, 2026-10-17); the
            // ones that come while it shows wait for its end (FightFx.flushTitle, endsAt)
            com.hypixel.hytale.server.core.util.EventTitleUtil.hideEventTitleFromPlayer(player, 0.15f);
            // build() just noted the poses it showed with a stand-in (WANTED): save them for the next boot now (they
            // used to wait for the next VS, and the session's last one never got saved)
            CompletableFuture.runAsync(VsScreen::rememberWanted);
            // the game's HUD and the chat go when the screen shows (hideHud, at the slide start), not now: the screen
            // stays hidden while the client builds it
            // the animation is timed from here, once the screen exists on the client (a busy world thread added it
            // late and the steps queued behind it arrived all at once: no slide, user 2026-10-16), START_DELAY_MS later:
            // a client still loading the battle's freshly spawned models froze through the whole slide
            // The whole screen fits the VS time: the start delay and the last step come out of it (audit 2026-10-17:
            // it ran ~320 ms past it, under the intro camera and the first countdown title).
            long delay = startDelay(plan);
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
                hideHud(player);
                animate(player, id, plan, Math.max(SLIDE_IN_MS + SLIDE_OUT_MS + 300, totalMs - delay - STEP_MS));
            }, delay, TimeUnit.MILLISECONDS);
        });
    }

    /**
     * The game's HUD (compass, boss bar, hotbar, abilities; user, 2026-10-16) and the chat (user, 2026-10-17: the
     * countdown lines covered the VS) go while the screen shows; both come back at its end. Any thread.
     */
    private static void hideHud(PlayerRef player) {
        if (!CameraHud.hidden(player)) {
            HID.add(player.getUuid());
            CameraHud.hideAlways(player);
        }
        CameraHud.hideChat(player);
    }

    /** The VS screen each player sees now (its updates are packets, sent from the timer thread, not the world's). */
    private static final Map<UUID, VsScreen> LIVE = new ConcurrentHashMap<>();
    /**
     * Wait before the slide (recordings 2026-10-17, frame by frame: the client takes ~0.5 s to build a duel's screen and
     * ~1.2 s a royale's, ~1300 elements, and only then applies the updates that came meanwhile, all at once: the halves
     * popped in with the clash already on, even with every step sent on time). Until then the screen is hidden and the
     * player keeps their own view and HUD.
     */
    private static final long START_DELAY_MS = 900;
    /** Extra wait per picture past the first two (each one loads before the screen can show). */
    private static final long DELAY_PER_PICTURE_MS = 30;
    private static final long MAX_START_DELAY_MS = 2200;
    /** The start delay the VS time (COUNTDOWN_MS) was made for; a longer one adds whole seconds (timeFor). */
    private static final long BASE_START_DELAY_MS = 300;

    /**
     * How long this plan's VS screen lasts: COUNTDOWN_MS, plus the extra load wait of a screen with many pictures
     * rounded up to whole seconds (the countdown grows by the same seconds, Countdown.run / takeShown).
     */
    static long timeFor(Plan plan) {
        long extra = Math.max(0, startDelay(plan) - BASE_START_DELAY_MS);
        return COUNTDOWN_MS + (extra + 999) / 1000 * 1000;
    }

    /** START_DELAY_MS plus DELAY_PER_PICTURE_MS for every distinct picture past the fourth, at most MAX_START_DELAY_MS. */
    private static long startDelay(Plan plan) {
        java.util.Set<String> pictures = new java.util.HashSet<>();
        if (plan.columns() != null) {
            for (Column c : plan.columns()) pictures.add(String.valueOf(c.rep().model()));
        } else {
            for (Fighter f : plan.left().shown()) if (f != null) pictures.add("L" + f.model() + f.variant());
            for (Fighter f : plan.right().shown()) if (f != null) pictures.add("R" + f.model() + f.variant());
        }
        return Math.min(MAX_START_DELAY_MS, START_DELAY_MS + DELAY_PER_PICTURE_MS * Math.max(0, pictures.size() - 2));
    }

    /** Per player: when their VS screen ends (FightFx holds titles back until then). */
    private static final Map<UUID, Long> ENDS = new ConcurrentHashMap<>();

    /** When this player's VS screen ends, or 0 when none shows (or it is past its time). */
    static long endsAt(UUID player) {
        Long t = player != null ? ENDS.get(player) : null;
        if (t == null) return 0;
        if (t < System.currentTimeMillis()) {
            ENDS.remove(player, t);
            return 0;
        }
        return t;
    }

    /** Whether a VS screen covers this player now. */
    static boolean showing(PlayerRef player) {
        return player != null && LIVE.containsKey(player.getUuid());
    }

    private static void animate(PlayerRef player, long id, Plan plan, long totalMs) {
        if (plan.columns() != null) {
            runColumns(player, id, plan, totalMs);
            return;
        }
        int inSteps = (int) Math.max(1, SLIDE_IN_MS / SLIDE_STEP_MS);
        // the screen shows now, its pictures loaded meanwhile through #Preload (hidden until here)
        // and the preload copies go: drawn under their mask every frame, they doubled the cost of each slide step
        step(player, id, 0, b -> {
            b.set("#Show.Visible", true);
            b.set("#Preload.Visible", false);
            b.set("#Loading.Visible", false);
        });
        // a click as each half comes in, the clash when they meet (user, 2026-10-16)
        sound(player, id, 0, CLICK_SOUND, 1f, 1f);
        sound(player, id, CLICK_GAP_MS, CLICK_SOUND, 1f, 1.12f);
        for (int i = 1; i <= inSteps; i++) {
            double f = (double) i / inSteps;
            double eased = 1 - (1 - f) * (1 - f) * (1 - f);   // ease out: fast in, soft landing
            step(player, id, i * SLIDE_STEP_MS, b -> weights(b, (int) Math.round(HALF * eased)));
        }
        long hit = inSteps * SLIDE_STEP_MS;
        sound(player, id, hit, IMPACT_SOUND, 1f, 1f);
        step(player, id, hit, b -> {
            b.set("#Flash.Visible", true);
            b.set("#Center.Visible", true);
            b.set("#Title.Visible", plan.kind() != null);
            // names and "+N" with the clash (hidden in fill); a plain royale has one centered title instead
            boolean royaleHead = plainRoyale(plan);
            b.set("#RoyaleHead.Visible", royaleHead);
            for (String s : List.of("L", "R")) {
                b.set("#" + s + "Team.Visible", !royaleHead);
                b.set("#" + s + "Names.Visible", !royaleHead);
            }
            b.set("#LMoreBox.Visible", plan.left().more() > 0);
            b.set("#RMoreBox.Visible", plan.right().more() > 0);
        });
        step(player, id, hit + FLASH_MS, b -> b.set("#Flash.Visible", false));
        long outAt = Math.max(hit + FLASH_MS + STEP_MS, totalMs - SLIDE_OUT_MS);
        step(player, id, outAt, b -> {
            b.set("#Center.Visible", false);
            b.set("#Title.Visible", false);
        });
        int outSteps = (int) Math.max(1, SLIDE_OUT_MS / SLIDE_STEP_MS);
        for (int i = 1; i <= outSteps; i++) {
            double f = (double) i / outSteps;
            double eased = f * f;   // ease in: leaves slowly, then fast
            step(player, id, outAt + i * SLIDE_STEP_MS, b -> weights(b, (int) Math.round(HALF * (1 - eased))));
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> end(player, id), outAt + (outSteps + 1) * SLIDE_STEP_MS, TimeUnit.MILLISECONDS);
    }

    /** 3+ teams: the columns appear one by one from the left, then a flash with the VS and the title, hold, all go. */
    private static void runColumns(PlayerRef player, long id, Plan plan, long totalMs) {
        step(player, id, 0, b -> b.set("#Loading.Visible", false));
        int n = plan.columns().size();
        // columns rise in blocks, not one by one (user, 2026-10-16: with many columns animate each team as one block):
        // a team's columns together, and with more than MAX_RISE_BLOCKS teams neighbouring teams share a block
        int[] block = new int[n];
        int teams = 0;
        for (int i = 0; i < n; i++) {
            if (i > 0 && plan.columns().get(i).team() != plan.columns().get(i - 1).team()) teams++;
            block[i] = teams;
        }
        teams++;
        int per = (teams + MAX_RISE_BLOCKS - 1) / MAX_RISE_BLOCKS;
        int blocks = 0;
        for (int i = 0; i < n; i++) {
            block[i] /= per;
            blocks = Math.max(blocks, block[i] + 1);
        }
        for (int k = 0; k < blocks; k++) {
            // each block thumps as it lands, a little higher each time; the mace clash comes when the title shows
            // (user, 2026-10-16: with several columns one sound each, "tum-tum-tum" then the impact)
            sound(player, id, STEP_MS + k * BLOCK_GAP_MS + RISE_MS, LAND_SOUND, 0.7f, 1f + 0.08f * k);
        }
        // the columns rise from the bottom (user, 2026-10-16: they popped in): body grows, pad shrinks, never 0. ONE
        // update per frame for every column moving (user, 2026-10-16: 8 columns sent ~4 updates per 20 ms and the game
        // froze), at COLUMN_STEP_MS
        long riseEnd = STEP_MS + (blocks - 1) * BLOCK_GAP_MS + RISE_MS;
        for (long t = STEP_MS + COLUMN_STEP_MS; t < riseEnd + COLUMN_STEP_MS; t += COLUMN_STEP_MS) {
            long at = Math.min(t, riseEnd);
            int[] bodies = new int[n];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                double f = Math.max(0, Math.min(1, (at - STEP_MS - block[i] * BLOCK_GAP_MS) / (double) RISE_MS));
                double prev = Math.max(0, Math.min(1, (at - COLUMN_STEP_MS - STEP_MS - block[i] * BLOCK_GAP_MS) / (double) RISE_MS));
                double eased = 1 - (1 - f) * (1 - f) * (1 - f);   // ease out
                bodies[i] = f <= 0 || prev >= 1 ? -1 : (int) Math.max(1, Math.round(1000 * eased));
                any |= bodies[i] > 0;
            }
            if (!any) continue;
            step(player, id, at, b -> {
                for (int i = 0; i < n; i++) {
                    if (bodies[i] < 0) continue;
                    String p = "#C" + n + "P" + i;
                    b.set(p + "Body.FlexWeight", bodies[i]);
                    b.set(p + "Pad.FlexWeight", Math.max(1, 1000 - bodies[i]));
                    if (bodies[i] >= 1000) {   // landed: its header texts come in
                        b.set(p + "Tag.Visible", true);
                        b.set(p + "Name.Visible", true);
                    }
                }
            });
        }
        long hit = riseEnd + STEP_MS;
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
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> end(player, id), outAt + STEP_MS, TimeUnit.MILLISECONDS);
    }

    /** Game sounds of the screen (2026-10-16, user: a click as each column/half shows, a clash at the VS); change here. */
    private static final String CLICK_SOUND = "SFX_Creative_Play_Selection_Place";
    private static final String IMPACT_SOUND = "SFX_Gobliterator_Club_Slam_Impact";
    private static final long CLICK_GAP_MS = 90;
    private static final String LAND_SOUND = "SFX_Golem_Earth_Stomp_Impact";

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
        long due = System.currentTimeMillis() + at;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            long late = System.currentTimeMillis() - due;
            if (late > 60 && MobBattlePlugin.debugging()) MobBattlePlugin.debug("VS step " + at + " ms ran " + late + " ms late");
            if (!player.isValid()) {   // left mid-screen: nothing else would drop these
                if (RUNS.remove(player.getUuid(), id)) {
                    LIVE.remove(player.getUuid());
                    HID.remove(player.getUuid());
                    ENDS.remove(player.getUuid());
                }
                return;
            }
            if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            VsScreen hud = LIVE.get(player.getUuid());
            if (hud == null) return;
            try {
                UICommandBuilder b = new UICommandBuilder();
                change.accept(b);
                hud.update(false, b);
                // out now: queued packets only leave with the world's tick flush (PlayerConnectionFlushSystem), so a
                // busy or slow tick sent the whole slide in one go and the halves popped in (recordings 2026-10-17)
                player.getPacketHandler().tryFlush();
            } catch (RuntimeException e) {
                MobBattlePlugin.log("VS screen step failed: " + e);
            }
        }, at, TimeUnit.MILLISECONDS);
    }

    private static void onWorld(PlayerRef player, Runnable task) {
        onWorld(player, task, 3);
    }

    /**
     * {@code task} on the player's world thread. A player who changed world meanwhile (their entity is in another
     * store now) gets it on the new world instead, a few times at most; never on the wrong thread (playerOf reads the
     * current reference's store).
     */
    private static void onWorld(PlayerRef player, Runnable task, int tries) {
        if (tries <= 0 || !player.isValid()) return;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            // between two worlds: try again a moment later
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, task, tries - 1), 500, TimeUnit.MILLISECONDS);
            return;
        }
        try {
            ref.getStore().getExternalData().getWorld().execute(() -> {
                if (player.getReference() != ref) {
                    onWorld(player, task, tries - 1);
                    return;
                }
                try {
                    task.run();
                } catch (RuntimeException e) {
                    MobBattlePlugin.log("VS screen failed: " + e);
                }
            });
        } catch (RuntimeException e) {   // the world stopped taking tasks
            MobBattlePlugin.log("VS screen: world task refused: " + e);
        }
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
    /** A registered VS picture to test with (UiTests): a few common mobs first, else any. Null when none is. */
    static String anyPicture() {
        for (String m : new String[] {"Skeleton_Soldier", "Trork_Warrior", "Goblin_Scrapper", "Zombie", "Kweebec_Sapling"}) {
            if (usable(key(m, 0))) return pathOf(m, 0, false);
        }
        for (String k : REGISTERED) {
            if (k.endsWith("|0")) return pathOf(k.substring(0, k.lastIndexOf('|')), 0, false);
        }
        return null;
    }

    static String iconFor(String name) {
        if (name == null) return null;
        for (String model : new String[] {name, RoleAppearance.of(name)}) {
            if (model != null && usable(key(model, 0))) return pathOf(model, 0, false);
        }
        return null;
    }

    /**
     * The "variant" of a model's team column picture: its first pose framed on the face (columnFrame), one square,
     * shown wider than its column and cut to it by the column's mask (2026-10-17: it replaced six pre-cut widths per
     * mob, ~38 MB of every client's download). Not mirrored: every column faces the same way, as Smash's.
     */
    static final int COLUMN = -1;

    static String pathOf(String model, int variant, boolean right) {
        if (variant < 0) return PICTURES + "MobBattle_Vs_" + safe(model) + "_sq.png";
        return PICTURES + "MobBattle_Vs_" + safe(model) + "_v" + variant + (right ? "_R" : "_L") + ".png";
    }

    /** The finished picture on disk (palette PNG, registered as it is): thumbnails/vs/&lt;model&gt;_v&lt;k&gt;_L|R.png. */
    private static Path fileOf(String model, int variant, boolean right) {
        Path used = usedFile();
        // own folder per picture size: a size change draws them again
        if (used == null) return null;
        Path dir = used.getParent().resolve(vsFolder());
        // the column picture: the face-framed square (masked to its column; the pre-cut "_k<n>"/"_fit<n>"/"_crop<n>"
        // widths before 2026-10-17 are deleted by deleteOldCrops)
        if (variant < 0) return dir.resolve(safe(model) + "_sq.png");
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
        // players seen before IN THIS WORLD, newest first: their own skin in the VS from the first battle of the session.
        // The skin folder is shared by every world on the machine (PictureCache); another world's players are not sent.
        java.util.Set<String> here = seenHere();
        for (String hash : PlayerSkins.saved(playersDir(), Integer.MAX_VALUE)) {
            if (!here.contains(hash)) continue;
            if (keys.size() >= 2 * MAX_PLAYER_SKINS) break;
            keys.add(key(PLAYER_PREFIX + hash, 0));
            keys.add(key(PLAYER_PREFIX + hash, COLUMN));
        }
        try {
            for (String entry : SummonList.snapshot().keySet()) {
                String model = RoleAppearance.of(SummonList.roleOf(entry));
                if (model == null) continue;
                for (int v = 0; v < VARIANTS; v++) keys.add(key(model, v));
                keys.add(key(model, COLUMN));
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("VS screen: summon list not read: " + e);
        }
        Path used = usedFile();
        if (used != null && Files.exists(used)) {
            try {
                for (String line : Files.readAllLines(used, StandardCharsets.UTF_8)) {
                    String k = line.trim();
                    if (k.isEmpty()) continue;
                    if (!k.contains("|")) k = key(k, 0);
                    // a torn line (two processes appending at once) used to throw later and stop every VS picture;
                    // other worlds' players never come from here (audit 2026-10-17)
                    if (!k.matches("[A-Za-z0-9_.\\-]+\\|-?\\d{1,2}")) continue;
                    if (k.startsWith(PLAYER_PREFIX) && !here.contains(k.substring(PLAYER_PREFIX.length(), k.indexOf('|')))) continue;
                    // the old pre-cut column widths (-2..-6) are all the one column square now: one key, registered
                    // and counted once
                    if (k.contains("|-")) k = key(k.substring(0, k.lastIndexOf('|')), COLUMN);
                    keys.add(k);
                }
            } catch (IOException e) {
                MobBattlePlugin.log("VS screen: " + used.getFileName() + " unreadable: " + e);
            }
        }
        for (String model : fighterModels()) {
            if (keys.size() >= MAX_PAIRS) break;
            keys.add(key(model, 0));
            keys.add(key(model, COLUMN));
        }
        // only models this world has loaded now: the shared cache (and used.txt) also holds other worlds' mods, and
        // their pictures must never be sent to this world's players (user, 2026-10-16)
        keys.removeIf(k -> {
            String model = k.substring(0, k.lastIndexOf('|'));
            return !model.startsWith(PLAYER_PREFIX) && ModelAsset.getAssetMap().getAsset(model) == null;
        });
        List<String> out = new ArrayList<>(keys);
        return out.size() > MAX_PAIRS ? new ArrayList<>(out.subList(0, MAX_PAIRS)) : out;
    }

    /** {@code <world data dir>/vs-players.txt}: skin ids of the players seen in this world (one per line). */
    private static Path seenFile() {
        Path d = FxSettings.dataDirectory();
        return d == null ? null : d.resolve("vs-players.txt");
    }

    private static java.util.Set<String> seenHere() {
        java.util.Set<String> out = new java.util.HashSet<>();
        Path f = seenFile();
        if (f == null || !Files.exists(f)) return out;
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!line.isBlank()) out.add(line.trim());
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: " + f.getFileName() + " unreadable: " + e);
        }
        return out;
    }

    private static synchronized void markSeenHere(String hash) {
        Path f = seenFile();
        if (f == null || seenHere().contains(hash)) return;
        try {
            Files.writeString(f, hash + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: " + f.getFileName() + " not written: " + e);
        }
    }

    /**
     * BootEvent (ThumbnailCache.startup), before anyone joins: registers the boot pictures already on disk, silently
     * (no asset rebuild: nobody is connected). Skipped when someone is online already.
     */
    static void registerAtBoot() {
        registerAtBoot(false);
    }

    /** {@code late}: the boot run's end, also after someone connected (ThumbnailCache.lateDone sends the rebuild). */
    static void registerAtBoot(boolean late) {
        if (!FxSettings.serverVs) return;   // server feature off: nothing sent to joining players
        if (!ThumbnailCache.mayRegister() && !late) return;
        long t0 = System.nanoTime(), bytes0 = REGISTERED_BYTES.get();
        int n = 0;
        for (String k : bootKeys()) {
            try {
                if (register(k, late)) n++;
            } catch (Exception e) {
                MobBattlePlugin.log("VS screen: picture " + k + " not registered: " + e);
            }
        }
        long sent = REGISTERED_BYTES.get() - bytes0;
        if (n > 0) MobBattlePlugin.log("VS screen: " + n + " pictures registered (" + MobBattlePlugin.size(sent)
                + ", what each joining client downloads) in " + MobBattlePlugin.took(t0) + ".");
    }

    /**
     * ThumbnailCache's boot run (background thread), after the thumbnails: draws the boot pictures missing on disk,
     * then registers them if nobody is online yet (else at the next boot).
     */
    /** What prepareInBackground drew: pictures, their bytes, how long it took (for /mobbattle pictures). */
    record Prepared(int drawn, long bytes, long millis) {
    }

    static Prepared prepareInBackground() {
        if (!FxSettings.serverVs) {
            MobBattlePlugin.log("VS screen: off on this server (/mobbattle serverVs): no pictures drawn or sent.");
            return new Prepared(0, 0, 0);
        }
        long t0 = System.nanoTime();
        int drawn = 0;
        long drawnBytes = 0;
        List<String> missing = new ArrayList<>();
        for (String k : bootKeys()) {
            if (onDisk(k)) continue;
            // a model whose thumbnail drew nothing (markers, VFX): every pose was tried again each boot, for nothing
            String model = k.substring(0, k.lastIndexOf('|'));
            if (!model.startsWith(PLAYER_PREFIX) && ThumbnailCache.drawsNothing(model)) continue;
            missing.add(k);
        }
        // every boot (user, 2026-10-16: also when nothing is drawn, to know when they were last generated)
        Path picturesDir = usedFile() == null ? null
                : usedFile().getParent().resolve(vsFolder());
        if (missing.isEmpty()) {
            MobBattlePlugin.log("VS screen: all pictures up to date, " + MobBattlePlugin.onDisk(picturesDir) + ".");
        }
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
            java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
            PictureProgress.start("vs", missing.size());
            // keys that stay missing with no error (no skin, model gone, nothing visible): named in the log
            java.util.Set<String> notMade = ConcurrentHashMap.newKeySet();
            try {
                List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
                for (String k : missing) {
                    jobs.add(pool.submit(() -> {
                        try {
                            if (make(k)) {
                                made.incrementAndGet();
                                written.addAndGet(bytesOnDisk(k));
                            } else if (!onDisk(k)) {
                                notMade.add(k);
                            }
                        } catch (Exception e) {
                            MobBattlePlugin.log("VS screen: picture " + k + " not drawn: " + e);
                        } finally {
                            PictureProgress.step();
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> job : jobs) job.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Prepared(made.get(), written.get(), (System.nanoTime() - t0) / 1_000_000);
            } catch (java.util.concurrent.ExecutionException e) {
                MobBattlePlugin.log("VS screen: drawing failed: " + e.getCause());
            } finally {
                pool.shutdownNow();
            }
            drawn = made.get();
            drawnBytes = written.get();
            MobBattlePlugin.log("VS screen: " + made.get() + " pictures drawn (" + MobBattlePlugin.size(written.get())
                    + ") on " + threads + " threads in " + MobBattlePlugin.took(t0) + "; " + MobBattlePlugin.onDisk(picturesDir) + ".");
            if (!notMade.isEmpty()) {
                List<String> names = new ArrayList<>(notMade);
                java.util.Collections.sort(names);
                MobBattlePlugin.log("VS screen: " + names.size() + " pictures could not be drawn: "
                        + String.join(", ", names.subList(0, Math.min(12, names.size()))) + (names.size() > 12 ? ", ..." : ""));
            }
        }
        registerAtBoot(true);
        // every boot picture of this render version is on disk: older versions' folders (stand-ins until now) go
        Path used = usedFile();
        boolean complete = true;
        for (String k : bootKeys()) {
            if (!onDisk(k) && !k.startsWith(PLAYER_PREFIX)) {
                complete = false;
                break;
            }
        }
        if (complete && used != null) {
            ThumbnailCache.deleteOtherFolders(used.getParent(), "p", vsFolder());
            deleteOldCrops(used.getParent().resolve(vsFolder()));
        }
        return new Prepared(drawn, drawnBytes, (System.nanoTime() - t0) / 1_000_000);
    }

    /** VS pictures registered now (their keys). */
    static int registeredCount() {
        return REGISTERED.size();
    }

    /** This render version's VS picture folder in the shared cache, or null. */
    static Path picturesFolder() {
        Path used = usedFile();
        return used == null ? null : used.getParent().resolve(vsFolder());
    }

    /**
     * Column crops of earlier crop rules in this version's folder ("_crop<k>", "_cut<k>"; now "_fit<k>"), never registered
     * any more (2026-10-16, user: 123 MB on disk for 59 MB sent, 53 MB of them old crops). Only once every boot picture
     * is on disk, so the new crops no longer need cutting from them.
     */
    private static void deleteOldCrops(Path dir) {
        if (!Files.isDirectory(dir)) return;
        long files = 0, bytes = 0;
        try (var list = Files.list(dir)) {
            for (Path f : (Iterable<Path>) list::iterator) {
                if (!f.getFileName().toString().matches(".*_(crop|cut|fit|k)[0-9]+[.]png|.*_v-1_L[.]png")) continue;
                long size = f.toFile().length();
                if (Files.deleteIfExists(f)) {
                    files++;
                    bytes += size;
                }
            }
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: old column crops not cleaned: " + e);
            return;
        }
        if (files > 0) MobBattlePlugin.log("VS screen: " + files + " old column crops deleted (" + MobBattlePlugin.size(bytes) + ").");
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
        // an earlier version's face-framed square was "_v-1_L.png"
        String[] names = variant < 0 ? new String[] {now.getFileName().toString(), safe(model) + "_v-1_L.png"}
                : new String[] {now.getFileName().toString()};
        for (Path dir : olderFolders()) {
            for (String n : names) {
                Path old = dir.resolve(n);
                if (Files.exists(old)) return old;
            }
        }
        return now;
    }

    /**
     * ThumbnailCache redrew this model because it changed (a mod update): its VS files in this version's folder go, so
     * the boot run draws them again (their names carry no key). The ones registered this session stay until the next boot.
     */
    static void dropPictures(String model) {
        Path used = usedFile();
        if (used == null) return;
        Path dir = used.getParent().resolve(vsFolder());
        if (!Files.isDirectory(dir)) return;
        String prefix = safe(model) + "_";
        try (var list = Files.list(dir)) {
            for (Path f : (Iterable<Path>) list::iterator) {
                String n = f.getFileName().toString();
                if (n.startsWith(prefix) && n.substring(prefix.length()).matches("(v-?[0-9]+_[LR]|k[0-9]+|sq)[.]png")) {
                    Files.deleteIfExists(f);
                }
            }
        } catch (IOException | RuntimeException e) {
            MobBattlePlugin.log("VS screen: old pictures of " + model + " not deleted: " + e);
        }
    }

    /** vs/ folders of earlier versions, newest first, listed once per boot (a stand-in can be in any of them). */
    private static volatile List<Path> OLDER;

    /**
     * Every earlier picture folder ("p384r7", "p384r6s1", "p384"...), newest render version first, then newest style.
     * It used to try "p384r6".."p384r1" only: after the VS_STYLE change ("p384r7s2") the "p384r7" pictures were never
     * found, nothing was registered at boot and everything was drawn and registered late again (audit 2026-10-17).
     */
    private static List<Path> olderFolders() {
        List<Path> known = OLDER;
        if (known != null) return known;
        List<Path> out = new ArrayList<>();
        Path used = usedFile();
        if (used != null && Files.isDirectory(used.getParent())) {
            java.util.regex.Pattern name = java.util.regex.Pattern.compile("p" + PICTURE + "(?:r([0-9]+)(?:s([0-9]+))?)?");
            Map<Path, int[]> order = new HashMap<>();
            try (var list = Files.list(used.getParent())) {
                for (Path d : (Iterable<Path>) list::iterator) {
                    java.util.regex.Matcher m = name.matcher(d.getFileName().toString());
                    if (!m.matches() || !Files.isDirectory(d) || d.getFileName().toString().equals(vsFolder())) continue;
                    order.put(d, new int[] {m.group(1) != null ? Integer.parseInt(m.group(1)) : 0,
                            m.group(2) != null ? Integer.parseInt(m.group(2)) : 0});
                    out.add(d);
                }
            } catch (IOException | RuntimeException e) {
                MobBattlePlugin.log("VS screen: older picture folders not listed: " + e);
            }
            out.sort((a, b) -> {
                int[] x = order.get(a), y = order.get(b);
                return x[0] != y[0] ? Integer.compare(y[0], x[0]) : Integer.compare(y[1], x[1]);
            });
        }
        OLDER = out;
        return out;
    }

    /** Registers both files of a key when they are on disk (an older render version's as a stand-in). */
    private static synchronized boolean register(String key) throws IOException {
        return register(key, false);
    }

    /** {@code late}: allowed after a client connected (ThumbnailCache.mayRegister(boolean)), with its toast. */
    private static synchronized boolean register(String key, boolean late) throws IOException {
        // a key registered from an older version's file (a stand-in) is registered again once this version's is drawn
        // (2026-10-18: after a VS_STYLE bump the boot registered every old picture, drew the new ones 20 s later and
        // the clients got the old ones, without their ratios, until the next restart)
        if (REGISTERED.contains(key) && !STAND_INS.containsKey(key)) return false;
        // never with anyone online: each registered file showed a "Reloaded assets for Common" toast on their screen,
        // even without an asset rebuild (user screenshot, 2026-10-16). Pictures made after someone joined (boot run
        // still drawing, a new player's skin) wait for the next boot; the fallback shows until then.
        if (!ThumbnailCache.mayRegister() && !late) return false;
        int bar = key.lastIndexOf('|');
        String model = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        Path left = existing(model, variant, false), right = existing(model, variant, true);
        boolean standIn = left != null && !left.equals(fileOf(model, variant, false));
        if (REGISTERED.contains(key) && standIn) return false;   // still only the old one
        byte[] leftBytes = left != null && Files.exists(left) ? Files.readAllBytes(left) : null;
        if (leftBytes == null || variant >= 0 && !Files.exists(right)) return false;
        if (!ThumbnailCache.mayRegister(late)) return false;
        String prefix = MorphHologram.registryPrefix();
        ThumbnailCache.addAsset(new RuntimeCommonAsset(prefix + pathOf(model, variant, false), leftBytes));
        if (variant >= 0) {
            ThumbnailCache.addAsset(new RuntimeCommonAsset(prefix + pathOf(model, variant, true), Files.readAllBytes(right)));
        }
        Long before = STAND_INS.remove(key);
        if (before != null) REGISTERED_BYTES.addAndGet(-before);
        if (standIn) STAND_INS.put(key, leftBytes.length + (variant >= 0 ? Files.size(right) : 0));
        REGISTERED.add(key);
        if (variant >= 0) {
            Path rf = ratioFile(left.getParent(), model, variant);
            try {
                if (Files.exists(rf)) {
                    // "ratio" (VS_STYLE 4) or "ratio top bottom left right px" (5 on)
                    String[] t = Files.readString(rf).trim().split("\\s+");
                    RATIOS.put(key, Double.parseDouble(t[0]));
                    if (t.length >= 6) {
                        BODIES.put(key, new Body(Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]),
                                Double.parseDouble(t[4]), Integer.parseInt(t[5])));
                    } else {
                        BODIES.remove(key);   // a stand-in's body is read from its own picture when shown
                    }
                }
            } catch (IOException | NumberFormatException ignored) {
                // no ratio: DEFAULT_RATIO
            }
        }
        REGISTERED_BYTES.addAndGet(leftBytes.length + (variant >= 0 ? Files.size(right) : 0));
        return true;
    }

    /**
     * The square's side in heights of the model's hitbox: {@code squarePx} pixels of a render at {@code renderScale}
     * pixels per model unit (64 units a block), seen from the three-quarter view; 0 when unknown.
     */
    private static double bodyRatio(Model model, int squarePx, Double renderScale) {
        try {
            var box = model.getBoundingBox();
            if (box == null || renderScale == null || renderScale <= 0) return 0;
            double h = box.max.y - box.min.y;
            if (h <= 0) return 0;
            double blocks = squarePx / (renderScale * 64 * ModelThumbnail.upFactor(ModelThumbnail.THREE_QUARTER));
            return blocks / h;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Path ratioFile(Path dir, String model, int variant) {
        return dir.resolve(safe(model) + "_v" + variant + ".ratio");
    }

    /** key -> its picture's square in body heights (bodyRatio), read when the picture is registered. */
    private static final Map<String, Double> RATIOS = new ConcurrentHashMap<>();

    /** A picture's square in body heights when nothing was saved (a picture drawn before 2026-10-18). */
    private static final double DEFAULT_RATIO = 1.15;

    /** The ratio of the picture a fighter shows (its pose, else its variant 0, as picture() picks). */
    private static double ratioOf(Fighter f) {
        if (f == null || f.model() == null) return DEFAULT_RATIO;
        String k = usable(key(f.model(), f.variant())) ? key(f.model(), f.variant()) : key(f.model(), 0);
        return RATIOS.getOrDefault(k, DEFAULT_RATIO);
    }

    /** Registered (at a boot, before anyone joined: every client has it). */
    private static boolean usable(String key) {
        return REGISTERED.contains(key);
    }

    /** Bytes of the VS pictures registered so far (what every joining client downloads). */
    static long registeredBytes() {
        return REGISTERED_BYTES.get();
    }

    /** "1612 registered, 122.4 MB on disk, last drawn ..." for ThumbnailCache's boot summary. */
    static String cacheSummary() {
        Path used = usedFile();
        Path dir = used == null ? null : used.getParent().resolve(vsFolder());
        return REGISTERED.size() + " registered, " + MobBattlePlugin.onDisk(dir);
    }

    /** Bytes of everything registered so far (pictures sent to every client that joins), for the boot log lines. */
    private static final java.util.concurrent.atomic.AtomicLong REGISTERED_BYTES = new java.util.concurrent.atomic.AtomicLong();

    /** Bytes on disk of one key's files (both sides of a pose picture, or one column crop). */
    private static long bytesOnDisk(String key) {
        int bar = key.lastIndexOf('|');
        String model = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        long n = 0;
        Path[] files = variant >= 0 ? new Path[] {fileOf(model, variant, false), fileOf(model, variant, true)}
                : new Path[] {fileOf(model, variant, false)};
        for (Path f : files) {
            if (f != null && Files.exists(f)) n += f.toFile().length();
        }
        return n;
    }

    /**
     * Draws a key's two files when missing: the model in that pose (variants), on a square canvas with its feet at the
     * bottom, the right one mirrored so both sides look at the middle, as palette PNGs (IndexedPng: 128 colors, ~14 KB each).
     * A changed model is not noticed: delete thumbnails/vs/ to draw them again.
     */
    /**
     * Draws a key's files. One make per model's crops at a time (every crop key draws all of them: six threads drew
     * the same crops at once, and the parallel renames failed with AccessDenied on Windows, 165 pictures not drawn,
     * 2026-10-16); the files are checked again inside the lock.
     */
    private static boolean make(String key) throws Exception {
        Object lock = MAKE_LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            return makeNow(key);
        }
    }

    private static final Map<String, Object> MAKE_LOCKS = new ConcurrentHashMap<>();

    private static boolean makeNow(String key) throws Exception {
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
        if (img == null) {
            // nothing visible in that pose (user, 2026-10-16: Snake_Marsh never got a VS picture, its column showed the
            // icon every boot): the other poses, then the thumbnail's own pose, then the bind pose
            List<ThumbnailCache.PoseChoice> tries = new ArrayList<>(poses);
            tries.add(ThumbnailCache.poseOf(model, "auto", null));
            tries.add(null);
            for (ThumbnailCache.PoseChoice other : tries) {
                if (other == pose) continue;
                img = ThumbnailCache.render(pieces, ThumbnailCache.load(other), ModelThumbnail.THREE_QUARTER, PICTURE, true);
                if (img != null) {
                    pose = other;
                    MobBattlePlugin.log("VS screen: " + id + " shows nothing in its first pose, drawn in "
                            + (other == null || other.set() == null ? "its bind pose" : other.set()) + ".");
                    break;
                }
            }
            if (img == null) {
                MobBattlePlugin.log("VS screen: " + id + " shows nothing in any pose, no picture.");
                return false;
            }
        }
        if (variant < 0) {
            // the column picture: the face-framed square (the column's mask cuts it to any column width)
            BufferedImage framed = columnFrame(img, ModelThumbnail.LAST_HEAD.get(), flier, columnScale(model));
            Files.createDirectories(left.getParent());
            PictureCache.write(left, IndexedPng.encode(framed));
            return true;
        }
        // framed on the body only in a flying pose (a flier's ground pose, the Nightlord's sword run, kept whole)
        boolean flying = flier && pose != null && pose.set() != null && pose.set().startsWith("Fly");
        Double renderScale = ModelThumbnail.LAST_SCALE.get();
        BufferedImage sq = square(img, false, flying);
        byte[][] pair = IndexedPng.encodePair(sq);   // one quantization, the right one mirrored
        Files.createDirectories(left.getParent());
        // the square's side in body heights (2026-10-18, user: a Cult Knight with its banner looked small next to a
        // werewolf of the same height: every picture filled its own square), read by place() to put every fighter at
        // one scale
        double ratio = bodyRatio(model, sq.getWidth(), renderScale);
        // and where the body sits in the square, with the square's pixels (place(): one scale, kept off the split)
        Body body = bodyIn(sq);
        if (ratio > 0) PictureCache.writeText(ratioFile(left.getParent(), id, variant), String.format(java.util.Locale.ROOT,
                "%.4f %.4f %.4f %.4f %.4f %d", ratio, body.top(), body.bottom(), body.left(), body.right(), body.px()));
        PictureCache.write(right, pair[1]);
        PictureCache.write(left, pair[0]);   // left last: onDisk checks it first
        return true;
    }

    /**
     * A team column picture (user, 2026-10-16, after comparing framings): the same square and scale as the halves'
     * picture, but the face centered across; a ground mob keeps its feet on the bottom edge unless its face would be
     * above COLUMN_FACE_MIN of the height (then it moves down and the feet are cut); a flying mob has its face at
     * COLUMN_FACE_FLIER whatever its size, level with the others'. What goes past the edges is cut.
     */
    static BufferedImage columnFrame(BufferedImage src, double[] head, boolean flier) {
        return columnFrame(src, head, flier, 1);
    }

    /**
     * How big a model stands in its team column: 1 from SMALL_MOB (0.8) blocks tall up, smaller below (the square root of
     * its share of SMALL_MOB, at least SMALL_MIN). Every picture fills its square, so without this a silk larva or a slime
     * filled the whole column like a boss and its texture pixels showed as big blocks (user, 2026-10-16).
     */
    static double columnScale(Model model) {
        try {
            var box = model.getBoundingBox();
            if (box == null) return 1;
            double h = box.max.y - box.min.y;
            return h <= 0 || h >= SMALL_MOB ? 1 : Math.max(SMALL_MIN, Math.sqrt(h / SMALL_MOB));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // only really small mobs (user, 2026-10-16: the snakes, 0.9 blocks, look right filling the column)
    private static final double SMALL_MOB = 0.8, SMALL_MIN = 0.5;

    static BufferedImage columnFrame(BufferedImage src, double[] head, boolean flier, double scale) {
        if (scale < 0.999) {
            // drawn smaller in the same square, so it stands on the bottom (or floats) like the others
            int s0 = Math.max(src.getWidth(), src.getHeight());
            int nw = Math.max(1, (int) Math.round(src.getWidth() * scale)), nh = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage small = MorphHologram.shrink(src, nw, nh);
            BufferedImage padded = new BufferedImage(Math.max(s0, nw), Math.max(s0, nh), BufferedImage.TYPE_INT_ARGB);
            int px = (padded.getWidth() - nw) / 2, py = padded.getHeight() - nh;
            java.awt.Graphics2D g0 = padded.createGraphics();
            g0.drawImage(small, px, py, null);
            g0.dispose();
            double[] h2 = head == null ? null : new double[] {px + head[0] * scale, py + head[1] * scale};
            return columnFrame(padded, h2 != null ? h2 : new double[] {padded.getWidth() / 2.0, py + nh * 0.25}, flier, 1);
        }
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

    // ---- body framing (test, 2026-10-17) ----

    /** Units of a .blockymodel per block (the renderer draws at scale 1). */
    private static final double UNITS_PER_BLOCK = 64;
    /** Body framing: the model's collision box fills this share of the picture's height. */
    private static final double BODY_FIT = 0.92;
    /** Never closer than this (a hitbox far smaller than the drawing would show only a piece of it). */
    private static final double MAX_ZOOM = 2.5;

    /**
     * How much closer the camera must get so the body (the model's collision box, what the game hits) fills BODY_FIT
     * of {@code span} pixels of the last render; 1 when the drawing is mostly body (user, 2026-10-17: the Shadow Knight's
     * floating swords and the frost dragon's wings made the body small). Needs the render's scale (LAST_SCALE).
     */
    static double bodyZoom(Model model, double span) {
        Double sc = ModelThumbnail.LAST_SCALE.get();
        var box = model.getBoundingBox();
        if (sc == null || box == null) return 1;
        double blocks = box.max.y - box.min.y;
        if (blocks <= 0) return 1;
        double bodyPx = blocks * UNITS_PER_BLOCK * sc * ModelThumbnail.upFactor(ModelThumbnail.THREE_QUARTER);
        return Math.max(1, Math.min(MAX_ZOOM, BODY_FIT * span / bodyPx));
    }

    /**
     * A {@code size} x {@code size} window of a closer render: centered on the head, feet on the bottom edge (a flier: its
     * face at COLUMN_FACE_FLIER), the face never above COLUMN_FACE_MIN (then the feet are cut).
     */
    static BufferedImage columnWindow(BufferedImage big, double[] head, boolean flier, int size) {
        double hx = head != null ? head[0] : big.getWidth() / 2.0, hy = head != null ? head[1] : big.getHeight() * 0.25;
        int left = (int) Math.round(hx - size / 2.0);
        int top = flier ? (int) Math.round(hy - size * COLUMN_FACE_FLIER) : big.getHeight() - size;
        if (!flier && hy - top < size * COLUMN_FACE_MIN) top = (int) Math.round(hy - size * COLUMN_FACE_MIN);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(big, -left, -top, null);
        g.dispose();
        return out;
    }

    /** The halves' window of a closer render: centered across on the body, feet on the bottom edge. */
    static BufferedImage halfWindow(BufferedImage big, int size) {
        int w = big.getWidth(), h = big.getHeight();
        int[] xs = new int[w];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((big.getRGB(x, y) >>> 24) < 128) continue;
                xs[x]++;
                count++;
            }
        }
        double cx = count > 0 ? mid(xs, count, 0.5) : w / 2.0;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.drawImage(big, (int) Math.round(size / 2.0 - cx), size - h, null);
        g.dispose();
        return out;
    }

    /**
     * /mobbattle test vsframes &lt;current|body&gt; [text] (2026-10-17, user: try framing by the body before changing the real
     * pictures): every mob model's first VS pose, as the column square and the halves pair, framed the current way or by
     * the body, written to {@code dir} (outside the VS folder: nothing in the game changes). Returns {done, failed, total}.
     */
    static int[] testFrames(boolean body, String only, Path dir, java.util.function.IntConsumer progress) throws Exception {
        return testFrames(body, only, dir, WIDE_FIT, progress);
    }

    static int[] testFrames(boolean body, String only, Path dir, double fit, java.util.function.IntConsumer progress) throws Exception {
        Files.createDirectories(dir);
        List<String> ids = new ArrayList<>(fighterModels());
        if (only != null) {
            String o = only.toLowerCase(java.util.Locale.ROOT);
            ids.removeIf(m -> !m.toLowerCase(java.util.Locale.ROOT).contains(o));
        }
        java.util.Collections.sort(ids);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        int threads = Math.max(1, Math.min(MAX_THREADS, Runtime.getRuntime().availableProcessors() - 2));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "MobBattle-VsFrameTest");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        try {
            List<java.util.concurrent.Future<?>> jobs = new ArrayList<>();
            for (String id : ids) {
                jobs.add(pool.submit(() -> {
                    try {
                        if (testFrame(id, body, dir, fit)) done.incrementAndGet();
                        else failed.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        MobBattlePlugin.log("[vsframes] " + id + ": " + e);
                    }
                    progress.accept(done.get() + failed.get());
                }));
            }
            for (var j : jobs) j.get();
        } finally {
            pool.shutdownNow();
        }
        return new int[] {done.get(), failed.get(), ids.size()};
    }

    private static boolean testFrame(String id, boolean body, Path dir, double fit) throws Exception {
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) return false;
        Model model = ThumbnailCache.modelOf(asset);
        List<ThumbnailCache.Piece> pieces = ThumbnailCache.piecesOf(model);
        boolean flier = flyingModel(id);
        ThumbnailCache.PoseChoice pose = variants(model, flier).get(0);
        ModelThumbnail.Pose p = ThumbnailCache.load(pose);
        BufferedImage img = ThumbnailCache.render(pieces, p, ModelThumbnail.THREE_QUARTER, PICTURE, true);
        if (img == null) return false;
        double[] head = ModelThumbnail.LAST_HEAD.get();
        int span = Math.max(img.getWidth(), img.getHeight());
        // read the zooms now, while LAST_SCALE belongs to this render
        double zc = bodyZoom(model, span);              // columns: the body fills the height whatever the width
        double zh = bodyZoom(model, img.getHeight());   // halves: only extras above or below count, a wide mob keeps its width
        boolean flying = flier && pose != null && pose.set() != null && pose.set().startsWith("Fly");
        BufferedImage column, half;
        if (!body) {
            column = columnFrame(img, head, flier, columnScale(model));
            half = square(img, false, flying, fit);
        } else {
            BufferedImage bigC = img;
            double[] headC = head;
            if (zc > 1.001) {
                bigC = ThumbnailCache.render(pieces, p, ModelThumbnail.THREE_QUARTER, (int) Math.round(PICTURE * zc), true);
                headC = ModelThumbnail.LAST_HEAD.get();
            }
            column = columnWindow(bigC, headC, flier, span);
            double small = columnScale(model);
            if (small < 0.999) column = columnFrame(column, new double[] {span / 2.0, span * COLUMN_FACE_MIN}, flier, small);
            if (zh > 1.001) {
                BufferedImage bigH = ThumbnailCache.render(pieces, p, ModelThumbnail.THREE_QUARTER, (int) Math.round(PICTURE * zh), true);
                half = square(halfWindow(bigH, span), false, flying);
            } else {
                half = square(img, false, flying);
            }
        }
        String name = safe(id);
        PictureCache.write(dir.resolve(name + "_sq.png"), IndexedPng.encode(column));
        byte[][] pair = IndexedPng.encodePair(half);
        PictureCache.write(dir.resolve(name + "_v0_R.png"), pair[1]);
        PictureCache.write(dir.resolve(name + "_v0_L.png"), pair[0]);
        return true;
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
        return square(src, mirror, flier, WIDE_FIT);
    }

    /** {@code fit}: the square is never narrower than this share of the mob's width (1 = never cut at the sides). */
    static BufferedImage square(BufferedImage src, boolean mirror, boolean flier, double fit) {
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
            s = Math.max(h, (int) Math.round(w * fit));
            oy = s - h;
        }
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        int ox = (int) Math.round(s / 2.0 - cx);
        // a square that holds the whole width shows all of it (centered); a narrower one cuts as little as it can,
        // never leaving an empty strip on one side while the other side is cut
        if (s >= w) ox = (s - w) / 2;
        else ox = Math.max(s - w, Math.min(0, ox));
        boolean cutLeft = false, cutRight = false;
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int tx = ox + x;
                if (oy + y < 0 || oy + y >= s) continue;
                int sx = mirror ? src.getWidth() - 1 - x : x;
                int argb = src.getRGB(sx, y);
                if (tx < 0 || tx >= s) {
                    if ((argb >>> 24) >= 128) {
                        if (tx < 0) cutLeft = true;
                        else cutRight = true;
                    }
                    continue;
                }
                out.setRGB(tx, oy + y, argb);
            }
        }
        // the soft edge only where the mob was really cut (2026-10-18, user: a Glutton's whole arm faded out)
        fadeSides(out, cutLeft, cutRight);
        return out;
    }

    /** Fades the alpha to 0 at the left and right edges over EDGE_FADE of the width (only where something is drawn). */
    private static void fadeSides(BufferedImage img, boolean left, boolean right) {
        if (!left && !right) return;
        int w = img.getWidth(), fade = Math.max(1, (int) Math.round(w * EDGE_FADE));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int i = 0; i < fade; i++) {
                double k = (i + 0.5) / fade;
                k = k * k * (3 - 2 * k);   // smooth
                for (int x : new int[] {left ? i : -1, right ? w - 1 - i : -1}) {
                    if (x < 0) continue;
                    int argb = img.getRGB(x, y);
                    int a = argb >>> 24;
                    if (a == 0) continue;
                    img.setRGB(x, y, ((int) Math.round(a * k) << 24) | (argb & 0xFFFFFF));
                }
            }
        }
    }

    // 1 = the whole width (2026-10-18, user: a slime's side, an Outlander's fist, a moray's tail were cut off in the
    // picture itself; with one scale for the screen the size comes from blocks, so a wide square no longer makes the
    // mob look small). It was 0.72: a wide mob zoomed in, its sides cut and faded.
    private static final double WIDE_FIT = 1.0;
    /**
     * Pictures are faded over this share of their width at the left and right edges, where a wide mob (a spider's
     * legs, a dragon's wings) is cut by its square (user, 2026-10-16: the cut showed as a hard straight line).
     */
    private static final double EDGE_FADE = 0.08;
    /** Version of how the VS pictures are made (here, not RENDER_VERSION: no full thumbnail redraw): 2 = edge fade. */
    static final int VS_STYLE = 5;   // 5: whole width, body box saved with the ratio (2026-10-18)

    /** The folder name of this render version's VS pictures in vs/: "p384r7s2". */
    static String vsFolder() {
        return "p" + PICTURE + "r" + ThumbnailCache.RENDER_VERSION + "s" + VS_STYLE;
    }
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
                // players' pictures come from the skins seen in each world (bootKeys), never from this shared file
                if (k.startsWith(PLAYER_PREFIX)) continue;
                if (known.add(k)) add.append(k).append(System.lineSeparator());
            }
            if (add.length() == 0) return;
            Files.createDirectories(used.getParent());
            Files.writeString(used, add, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: used.txt not written: " + e);
        }
    }

    /** {@code vs/used.txt} in the shared per-machine picture cache (PictureCache); its folder holds the VS pictures. */
    private static Path usedFile() {
        Path d = PictureCache.root();
        return d == null ? null : d.resolve("vs").resolve("used.txt");
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
