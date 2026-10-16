package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;
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
 * Pictures reach the client as common assets of the mod ({@code UI/Custom/MobBattle/Vs/MobBattle_Vs_<model>_L|R.png},
 * the right side mirrored so both look at the middle, padded square, feet at the bottom). Without an asset rebuild
 * (the hitch the user saw with the hologram) they must be registered before anyone joins: every model ever shown is
 * remembered in {@code thumbnails/vs/used.txt} and registered at boot ({@link #registerAtBoot}, from ThumbnailCache).
 * A model shown for the first time is registered live and every client reloads its assets once (one hitch); the
 * screen then waits {@link #LIVE_DELAY_MS} for the pictures. So only mobs that were ever shown cost clients anything.
 * ❓ Untested in game: layout (LayoutMode Right/Middle, Visible collapsing), AssetImage with runtime assets, timing.
 */
final class VsScreen extends CustomUIHud {

    static final String KEY = "MobBattleVsScreen";
    private static final String UI = "MobBattle/VsScreen.ui";
    private static final String OK = "#7dff8a";
    private static final String ERR = "#ff6b6b";

    static final String FOLDER = "UI/Custom/MobBattle/Vs/";
    private static final String MISSING = "UI/Custom/Pages/Memories/MissingIcon.png";
    /** Mobs this far around the player take part. */
    static final double RANGE = 48;
    private static final int MAX_MOBS = 200;
    /** Big pictures per side; the rest is "+N". */
    private static final int SHOWN = 3;
    private static final String LEFT_COLOR = "#e03030";
    private static final String RIGHT_COLOR = "#3070e0";

    private static final long STEP_MS = 50;
    private static final long SLIDE_IN_MS = 350;
    private static final long SLIDE_OUT_MS = 350;
    private static final long FLASH_MS = 120;
    private static final long LIVE_DELAY_MS = 1500;
    /** Half the screen, in the thousandths the halves' FlexWeights use. */
    private static final int HALF = 500;

    /** Model ids whose two pictures are registered. */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();
    /** Player -> their newest screen (an older one's steps stop). */
    private static final Map<UUID, Long> RUNS = new ConcurrentHashMap<>();
    private static final AtomicLong RUN_IDS = new AtomicLong();

    record Fighter(String model, String name, double health, int variant) {
        Fighter withVariant(int v) {
            return new Fighter(model, name, health, v);
        }
    }

    /** Pictures per model: 0 = its most "epic" pose (each side's strongest gets it), 1-2 = others for repeats. */
    static final int VARIANTS = 3;
    /** Sets tried for variant 0, most dramatic first (vanilla names; mods often reuse them). */
    private static final String[] EPIC = {"Roar", "Taunt", "Threaten", "Howl", "Angry", "SawCharge", "Swipe", "Throw",
            "Rally", "LaughEvil", "JumpRun", "Sprint", "Run"};
    /** How many EPIC entries are real show-off sets; after them, attack-like set names are tried first. */
    private static final int EPIC_NAMED = 10;
    /** Words in set names that hint at an attack (mods' bosses). */
    private static final String[] ATTACKY = {"attack", "charge", "slam", "swing", "smash", "strike", "slash", "lunge", "bite"};
    /** Players who had the game's HUD hidden by a VS screen (it gives it back at the end). */
    private static final Set<UUID> HID = ConcurrentHashMap.newKeySet();

    record Side(Message label, String color, List<Fighter> shown, int more) {
    }

    record Plan(Side left, Side right) {
    }

    private final Plan plan;

    private VsScreen(PlayerRef player, Plan plan) {
        super(player, KEY);
        this.plan = plan;
    }

    @Override
    protected void build(UICommandBuilder b) {
        b.append(UI);
        fill(b, "L", plan.left(), false);
        fill(b, "R", plan.right(), true);
        weights(b, 0);
    }

    private static void fill(UICommandBuilder b, String s, Side side, boolean right) {
        I18n.text(b, "#" + s + "Team.Text", side.label());
        b.setObject("#" + s + "Half.Background", new PatchStyle().setColor(Value.of(darken(side.color(), 0.32))));
        b.setObject("#" + s + "Edge.Background", new PatchStyle().setColor(Value.of(side.color())));
        for (int i = 0; i < SHOWN; i++) {
            String slot = "#" + s + i;
            if (i < side.shown().size()) {
                Fighter f = side.shown().get(i);
                b.set(slot + ".Visible", true);
                b.set(slot + " #Img.AssetPath", f.model() != null && REGISTERED.contains(key(f.model(), f.variant()))
                        ? pathOf(f.model(), f.variant(), right) : MISSING);
                b.set(slot + " #Name.Text", f.name());
            } else {
                b.set(slot + ".Visible", false);
            }
        }
        b.set("#" + s + "More.Text", side.more() > 0 ? "+" + side.more() : "");
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
        for (int i = 0; i < s.shown().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(s.shown().get(i).name());
        }
        if (s.more() > 0) sb.append(", +").append(s.more());
        return sb.append(')').toString();
    }

    /**
     * Shows {@code plan} to {@code player} for {@code totalMs}, once its pictures are ready (a model never shown
     * before costs one asset rebuild and {@link #LIVE_DELAY_MS}). Any thread.
     */
    static void present(PlayerRef player, Plan plan, long totalMs) {
        List<String> models = new ArrayList<>();
        for (Side s : List.of(plan.left(), plan.right())) {
            for (Fighter f : s.shown()) {
                if (f.model() != null && !models.contains(key(f.model(), f.variant()))) models.add(key(f.model(), f.variant()));
            }
        }
        CompletableFuture.runAsync(() -> {
            int fresh = prepare(models);
            long delay = 0;
            if (fresh > 0) {
                Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
                MobBattlePlugin.log("VS screen: " + fresh + " new model picture(s) registered live (clients reloaded their assets once).");
                delay = LIVE_DELAY_MS;
            }
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> run(player, plan, totalMs), delay, TimeUnit.MILLISECONDS);
        });
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
        return plan == null ? null : withVariants(plan);
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
                if (f.model() == null) {
                    out.add(f);
                    continue;
                }
                int used = next.getOrDefault(f.model(), 0);
                int v = i == 0 ? 0 : used % VARIANTS;
                next.put(f.model(), Math.max(used, v) + 1);
                out.add(f.withVariant(v));
            }
            sides[s] = new Side(sides[s].label(), sides[s].color(), out, sides[s].more());
        }
        return new Plan(sides[0], sides[1]);
    }

    private static Plan fromBattle(BattleManager.Battle battle, Store<EntityStore> store) {
        Map<Integer, List<Ref<EntityStore>>> bySide = new LinkedHashMap<>();
        for (Ref<EntityStore> r : battle.members()) {
            if (r == null || !r.isValid() || r.getStore() != store || !BattleManager.isAlive(r, store)) continue;
            bySide.computeIfAbsent(battle.team(r), k -> new ArrayList<>()).add(r);
        }
        if (bySide.size() < 2) return null;
        List<Map.Entry<Integer, List<Ref<EntityStore>>>> sides = new ArrayList<>(bySide.entrySet());
        sides.sort(Comparator.comparingDouble((Map.Entry<Integer, List<Ref<EntityStore>>> e) -> -power(e.getValue(), store)));
        Map.Entry<Integer, List<Ref<EntityStore>>> first = sides.get(0);
        Side left = side(first.getValue(), labelOf(battle, first.getKey(), first.getValue(), store),
                colorOf(battle, first.getKey(), LEFT_COLOR), store);
        Side right;
        if (sides.size() == 2) {
            Map.Entry<Integer, List<Ref<EntityStore>>> second = sides.get(1);
            right = side(second.getValue(), labelOf(battle, second.getKey(), second.getValue(), store),
                    colorOf(battle, second.getKey(), RIGHT_COLOR), store);
        } else {
            List<Ref<EntityStore>> rest = new ArrayList<>();
            for (int i = 1; i < sides.size(); i++) rest.addAll(sides.get(i).getValue());
            right = side(rest, I18n.tr("hud.vs.everyoneElse"), RIGHT_COLOR, store);
        }
        return new Plan(left, right);
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

    private static Side side(List<Ref<EntityStore>> mobs, Message label, String color, ComponentAccessor<EntityStore> store) {
        List<Fighter> all = new ArrayList<>();
        for (Ref<EntityStore> r : mobs) all.add(fighter(r, store));
        all.sort(Comparator.comparingDouble(f -> -f.health()));
        List<Fighter> shown = new ArrayList<>(all.subList(0, Math.min(SHOWN, all.size())));
        return new Side(label, color, shown, all.size() - shown.size());
    }

    private static Fighter fighter(Ref<EntityStore> r, ComponentAccessor<EntityStore> store) {
        ModelComponent mc = store.getComponent(r, ModelComponent.getComponentType());
        Model m = mc != null ? mc.getModel() : null;
        boolean npc = store.getComponent(r, NPCEntity.getComponentType()) != null;
        // a player's model is their skin, which the thumbnail renderer does not draw: no picture
        String model = npc && m != null ? m.getModelAssetId() : null;
        return new Fighter(model, pretty(ChaosWand.nameOf(r, store)), health(r, store), 0);
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
     * draft), a royale (one list) its strongest against everyone else; 3+ sides = the strongest vs the rest.
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
            // royale: its strongest against everyone else
            List<Ref<EntityStore>> all = new ArrayList<>(live.get(0));
            if (all.size() < 2) return null;
            all.sort(Comparator.comparingDouble(r -> -health(r, acc)));
            List<Ref<EntityStore>> rest = new ArrayList<>(all.subList(1, all.size()));
            return withVariants(new Plan(side(List.of(all.get(0)), kindLabel(List.of(all.get(0)), acc, I18n.tr("hud.vs.champion")), LEFT_COLOR, acc),
                    side(rest, I18n.tr("hud.vs.everyoneElse"), RIGHT_COLOR, acc)));
        }
        BattleManager.Draft draft = kind == BattleManager.Kind.TEAMS && owner != null ? BattleManager.existingDraft(owner) : null;
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
        return withVariants(new Plan(left, right));
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
            if (huds.getCustomHud(KEY) != null) huds.removeCustomHud(player, KEY);
            huds.addCustomHud(player, new VsScreen(player, plan));
            // the game's HUD (compass, boss bar, hotbar, abilities) goes while it shows (user, 2026-10-16)
            if (!CameraHud.hidden(player)) {
                HID.add(player.getUuid());
                CameraHud.hideAlways(player);
            }
        });
        int inSteps = (int) Math.max(1, SLIDE_IN_MS / STEP_MS);
        for (int i = 1; i <= inSteps; i++) {
            double f = (double) i / inSteps;
            double eased = 1 - (1 - f) * (1 - f) * (1 - f);   // ease out: fast in, soft landing
            step(player, id, i * STEP_MS, b -> weights(b, (int) Math.round(HALF * eased)));
        }
        long hit = inSteps * STEP_MS;
        step(player, id, hit, b -> {
            b.set("#Flash.Visible", true);
            b.set("#Center.Visible", true);
        });
        step(player, id, hit + FLASH_MS, b -> b.set("#Flash.Visible", false));
        long outAt = Math.max(hit + FLASH_MS + STEP_MS, totalMs - SLIDE_OUT_MS);
        step(player, id, outAt, b -> b.set("#Center.Visible", false));
        int outSteps = (int) Math.max(1, SLIDE_OUT_MS / STEP_MS);
        for (int i = 1; i <= outSteps; i++) {
            double f = (double) i / outSteps;
            double eased = f * f;   // ease in: leaves slowly, then fast
            step(player, id, outAt + i * STEP_MS, b -> weights(b, (int) Math.round(HALF * (1 - eased))));
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, () -> {
            if (!RUNS.remove(player.getUuid(), id)) return;
            Player p = playerOf(player);
            if (p != null && p.getHudManager().getCustomHud(KEY) != null) p.getHudManager().removeCustomHud(player, KEY);
            // give the HUD back unless a camera that hides it too is filming now
            if (HID.remove(player.getUuid()) && !FightCamera.busy(player) && !FilmCamera.isFilming(player)) {
                CameraHud.restore(player);
            }
        }), outAt + (outSteps + 1) * STEP_MS, TimeUnit.MILLISECONDS);
    }

    private static void step(PlayerRef player, long id, long at, java.util.function.Consumer<UICommandBuilder> change) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, () -> {
            if (!Long.valueOf(id).equals(RUNS.get(player.getUuid()))) return;
            Player p = playerOf(player);
            CustomUIHud hud = p != null ? p.getHudManager().getCustomHud(KEY) : null;
            if (!(hud instanceof VsScreen)) return;
            UICommandBuilder b = new UICommandBuilder();
            change.accept(b);
            hud.update(false, b);
        }), at, TimeUnit.MILLISECONDS);
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

    static String pathOf(String model, int variant, boolean right) {
        return FOLDER + "MobBattle_Vs_" + safe(model) + "_v" + variant + (right ? "_R" : "_L") + ".png";
    }

    /**
     * BootEvent (ThumbnailCache.startup), before anyone joins: registers, with no asset rebuild, the pictures of every
     * model shown before (thumbnails/vs/used.txt) and every variant of the summon list's mobs (the ones most likely
     * to fight). Skipped when someone is online already.
     */
    static void registerAtBoot() {
        if (!Universe.get().getPlayers().isEmpty()) return;
        List<String> keys = new ArrayList<>();
        Path used = usedFile();
        if (used != null && Files.exists(used)) {
            try {
                for (String line : Files.readAllLines(used, StandardCharsets.UTF_8)) {
                    String k = line.trim();
                    if (k.isEmpty()) continue;
                    if (!k.contains("|")) k = key(k, 0);
                    if (!keys.contains(k)) keys.add(k);
                }
            } catch (IOException e) {
                MobBattlePlugin.log("VS screen: " + used.getFileName() + " unreadable: " + e);
            }
        }
        try {
            for (String entry : SummonList.snapshot().keySet()) {
                String model = RoleAppearance.of(SummonList.roleOf(entry));
                if (model == null) continue;
                for (int v = 0; v < VARIANTS; v++) if (!keys.contains(key(model, v))) keys.add(key(model, v));
            }
        } catch (RuntimeException e) {
            MobBattlePlugin.log("VS screen: summon list not read at boot: " + e);
        }
        long t0 = System.nanoTime();
        int n = 0;
        for (String k : keys) {
            try {
                if (register(k)) n++;
            } catch (Exception e) {
                MobBattlePlugin.log("VS screen: picture " + k + " not registered: " + e);
            }
        }
        if (n > 0) MobBattlePlugin.log("VS screen: " + n + " picture pairs registered at boot in " + (System.nanoTime() - t0) / 1_000_000 + " ms.");
    }

    /** Background thread: registers the keys not registered yet; returns how many were new. */
    private static int prepare(List<String> keys) {
        int fresh = 0;
        for (String k : keys) {
            if (REGISTERED.contains(k)) continue;
            try {
                if (register(k)) {
                    fresh++;
                    remember(k);
                }
            } catch (Exception e) {
                MobBattlePlugin.log("VS screen: picture " + k + " failed: " + e);
            }
        }
        return fresh;
    }

    /** Both pictures of a model in one pose (left as drawn, right mirrored), from disk or drawn now. */
    private static synchronized boolean register(String key) throws Exception {
        if (REGISTERED.contains(key)) return false;
        int bar = key.lastIndexOf('|');
        String model = key.substring(0, bar);
        int variant = Integer.parseInt(key.substring(bar + 1));
        BufferedImage shot = picture(model, variant);
        if (shot == null) return false;
        BufferedImage left = square(shot, false), right = square(shot, true);
        String prefix = MorphHologram.registryPrefix();
        CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK, new RuntimeCommonAsset(prefix + pathOf(model, variant, false), MorphHologram.encode(left)), false);
        CommonAssetModule.get().addCommonAsset(EyeRecolor.PACK, new RuntimeCommonAsset(prefix + pathOf(model, variant, true), MorphHologram.encode(right)), false);
        REGISTERED.add(key);
        return true;
    }

    /** One posed picture, kept in thumbnails/vs/ (a changed model or pose file is not noticed: delete the file). */
    private static BufferedImage picture(String id, int variant) throws Exception {
        Path used = usedFile();
        Path dir = used != null ? used.getParent() : null;
        Path file = dir != null ? dir.resolve(safe(id) + "_v" + variant + ".png") : null;
        if (file != null && Files.exists(file)) {
            BufferedImage img = ImageIO.read(file.toFile());
            if (img != null) return img;
        }
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(id);
        if (asset == null) return null;
        Model model = Model.createScaledModel(asset, 1f, Map.of());
        ThumbnailCache.PoseChoice pose = variants(model).get(variant % VARIANTS);
        BufferedImage img = ThumbnailCache.render(ThumbnailCache.piecesOf(model), ThumbnailCache.load(pose),
                ModelThumbnail.THREE_QUARTER, ThumbnailCache.SIZE, true);
        if (img != null && file != null) {
            Files.createDirectories(dir);
            ImageIO.write(img, "png", file.toFile());
        }
        return img;
    }

    /**
     * The model's VARIANTS poses: 0 = the first EPIC set it has (else an attack-like one, else run), then its auto pose,
     * a jump and an idle frame; when it has fewer, other frames of those (t shifted), so repeats never look the same.
     * Null entries = bind pose (a model with no animations).
     */
    static List<ThumbnailCache.PoseChoice> variants(Model model) {
        List<ThumbnailCache.PoseChoice> out = new ArrayList<>();
        ThumbnailCache.PoseChoice epic = null;
        for (int i = 0; i < EPIC_NAMED && epic == null; i++) epic = ThumbnailCache.poseOf(model, EPIC[i], 0.45);
        Map<String, ModelAsset.AnimationSet> sets = model.getAnimationSetMap();
        if (epic == null && sets != null) {
            for (String name : sets.keySet()) {
                String low = name.toLowerCase(Locale.ROOT);
                for (String w : ATTACKY) {
                    if (epic == null && low.contains(w)) epic = ThumbnailCache.poseOf(model, name, 0.45);
                }
            }
        }
        for (int i = EPIC_NAMED; i < EPIC.length && epic == null; i++) epic = ThumbnailCache.poseOf(model, EPIC[i], null);
        add(out, epic);
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
        int s = Math.max(src.getWidth(), src.getHeight());
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        int ox = (s - src.getWidth()) / 2, oy = s - src.getHeight();
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int sx = mirror ? src.getWidth() - 1 - x : x;
                out.setRGB(ox + x, oy + y, src.getRGB(sx, y));
            }
        }
        return out;
    }

    private static void remember(String id) {
        Path used = usedFile();
        if (used == null) return;
        try {
            Files.createDirectories(used.getParent());
            Files.writeString(used, id + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MobBattlePlugin.log("VS screen: could not remember " + id + ": " + e);
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
