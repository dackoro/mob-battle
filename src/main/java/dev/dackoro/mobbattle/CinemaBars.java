package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * /mobbattle cameraBars (2026-10-16, playtest: the black bands of a film, as the AFK Experience mod shows): black bands
 * at the top and the bottom of the screen while an intro or end camera films (FightCamera), sliding in when the
 * shot starts and out when the camera glides back to the player. A CustomUIHud
 * (Common/UI/Custom/MobBattle/CinemaBars.ui) whose band heights are FlexWeights in thousandths of the screen.
 * Everything that touches the HUD runs on the player's world thread.
 */
final class CinemaBars extends CustomUIHud {

    static final String KEY = "MobBattleCinemaBars";
    private static final String UI = "MobBattle/CinemaBars.ui";

    /** Height of each band per cameraBars size, in thousandths of the screen (classic is about a 2.35:1 frame on 16:9). */
    private static final int SLIM = 80;
    private static final int CLASSIC = 125;
    private static final int WIDE = 170;
    /** The bands slide in and out over this long, one HUD update per step. */
    private static final long SLIDE_MS = 400;
    private static final long STEP_MS = 50;

    /** Per player: the band height shown now and the newest slide (an older slide's steps stop). */
    private static final class State {
        volatile int shown;
        volatile long slide;
        /** debugLog: the shot name shown in the bottom band ("" = none). */
        volatile String shot = "";
        /** bandTitle: the battle's title while a film runs (null = none). */
        volatile Message title;
        /** bandTimer: the time left, as the timer box shows it ("" = none). */
        volatile String clock = "";
        /** bandInfo: the timer box's other lines joined ("2 fighters left"; null = none). */
        volatile Message info;
    }

    /**
     * Our HUD cards that move out of the bands' way (2026-10-16, playtest: the timer and score cards covered the top band):
     * their .ui wraps the card in #Shift (see TimerHud.ui), and {@link #shift} puts it just under the top band
     * (PassiveAlert: just over the bottom one) while the bands show, back in its usual spot without them.
     */
    private static final String[] SHIFTED = {TimerHud.KEY, Scoreboard.KEY, DuelCard.KEY, FocusCard.KEY, PassiveAlert.KEY};

    private static final Map<UUID, State> STATE = new ConcurrentHashMap<>();
    private static final AtomicLong SLIDES = new AtomicLong();

    private CinemaBars(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
    }

    /** Band height for a cameraBars value (0 = off). */
    static int heightOf(String size) {
        return switch (size == null ? "off" : size) {
            case "slim" -> SLIM;
            case "classic" -> CLASSIC;
            case "wide" -> WIDE;
            default -> 0;
        };
    }

    /** Slides the bands in to {@code size} (cameraBars value) for this player; off takes them away. Any thread. */
    static void in(PlayerRef player, String size) {
        if (player == null) return;
        int h = heightOf(size);
        if (h <= 0) out(player);
        else slide(player, h, false);
    }

    /** Slides the bands out and removes the HUD; no-op when the player has none. Any thread. */
    static void out(PlayerRef player) {
        if (player == null || !STATE.containsKey(player.getUuid())) return;
        slide(player, 0, true);
    }

    private static void slide(PlayerRef player, int to, boolean remove) {
        UUID id = player.getUuid();
        State state = STATE.computeIfAbsent(id, k -> new State());
        long slide = SLIDES.incrementAndGet();
        state.slide = slide;
        int from = state.shown;
        int steps = (int) Math.max(1, SLIDE_MS / STEP_MS);
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            double eased = f * f * (3 - 2 * f);   // smoothstep: starts and ends gently
            int value = (int) Math.round(from + (to - from) * eased);
            boolean last = i == steps;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> onWorld(player, () -> {
                if (state.slide != slide) return;
                if (last && remove) {
                    hide(player);
                    STATE.remove(id, state);
                    return;
                }
                show(player, value);
                state.shown = value;
            }), (i - 1) * STEP_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Runs {@code task} on the player's world thread, if they are still in a world. */
    private static void onWorld(PlayerRef player, Runnable task) {
        if (!player.isValid()) return;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return;
        ref.getStore().getExternalData().getWorld().execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                MobBattlePlugin.log("Cinema bars failed: " + e);
            }
        });
    }

    /** Bands at {@code height} thousandths each (world thread); adds the HUD the first time. */
    private static void show(PlayerRef player, int height) {
        Player p = playerOf(player);
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof CinemaBars)) {
            if (hud != null) huds.removeCustomHud(player, KEY);
            hud = new CinemaBars(player);
            huds.addCustomHud(player, hud);
        }
        int h = Math.max(0, Math.min(500, height));
        UICommandBuilder b = new UICommandBuilder();
        b.set("#BarTop.FlexWeight", h);
        b.set("#TopRest.FlexWeight", 1000 - h);
        b.set("#BarBottom.FlexWeight", h);
        b.set("#BottomRest.FlexWeight", 1000 - h);
        State state = STATE.get(player.getUuid());
        shotRow(b, state != null && showShots() ? state.shot : "");
        bandText(b, state);
        hud.update(false, b);
        shiftCards(p, player, h);
    }

    /**
     * bandTitle / bandTimer (2026-10-29, playtest: the black bands of the film could hold the timer and the battle's
     * title): what each band says, the title row above the time row when both share a band.
     */
    private static void bandText(UICommandBuilder b, State state) {
        bandRows(b, state, "top", "#BandTop");
        bandRows(b, state, "bottom", "#BandBottom");
    }

    private static void bandRows(UICommandBuilder b, State state, String band, String id) {
        boolean title = state != null && state.title != null && band.equals(FxSettings.bandTitle);
        boolean clock = state != null && !state.clock.isEmpty() && band.equals(FxSettings.bandTimer);
        boolean info = state != null && state.info != null && band.equals(FxSettings.bandInfo);
        b.set(id + "Info.Visible", info);
        b.set(id + "Info.TextSpans", info ? state.info : Message.empty());
        if (title && clock && FxSettings.bandRows == 1) {
            // bandRows 1: one line, the title then the time
            b.set(id + "Title.Visible", true);
            b.set(id + "Title.TextSpans", Message.join(state.title, Message.raw("   " + state.clock).color("#ffffff")));
            b.set(id + "Clock.Visible", false);
            b.set(id + "Clock.Text", "");
            return;
        }
        b.set(id + "Title.Visible", title);
        b.set(id + "Title.TextSpans", title ? state.title : Message.empty());
        b.set(id + "Clock.Visible", clock);
        b.set(id + "Clock.Text", clock ? state.clock : "");
    }

    /** The film started for this player: the battle's title for bandTitle. Any thread. */
    static void filmTitle(PlayerRef player, Message title) {
        if (player == null) return;
        State state = STATE.computeIfAbsent(player.getUuid(), k -> new State());
        state.title = title;
        refreshBands(player);
    }

    /**
     * TimerHud each second (world thread): with bandTimer on and a film running for this player, the time goes in its
     * band and the timer box stays away (true); otherwise false and the box shows as usual.
     */
    static boolean takeTimer(PlayerRef player, Message title, String clock, Message info) {
        if (player == null) return false;
        if ("off".equals(FxSettings.bandTimer) || !FilmCamera.isFilming(player)) {
            // another camera took over from the film (an intro, the end camera): its bands must not keep the film's
            // title and a frozen clock while the timer box shows the live one (audit 2026-10-29)
            State left = STATE.get(player.getUuid());
            if (left != null && (left.title != null || !left.clock.isEmpty() || left.info != null)) {
                left.title = null;
                left.clock = "";
                left.info = null;
                refreshBands(player);
            }
            return false;
        }
        State state = STATE.get(player.getUuid());
        if (state == null || state.shown <= 0) return false;
        if (state.title == null) state.title = title;
        // bandInfo box/off: nothing of the box's lines in the bands
        Message wanted = "box".equals(FxSettings.bandInfo) || "off".equals(FxSettings.bandInfo) ? null : info;
        boolean changed = !clock.equals(state.clock) || !sameText(wanted, state.info);
        state.clock = clock;
        state.info = wanted;
        if (changed) refreshBands(player);
        return true;
    }

    /** Whether two band lines read the same (a fresh Message each second would re-send the band every second). */
    private static boolean sameText(Message a, Message b) {
        if (a == null || b == null) return a == b;
        return java.util.Objects.equals(a.getFormattedMessage(), b.getFormattedMessage());
    }

    /** The timer box went (battle over, /mobbattle stop): no time in the bands any more. Any thread. */
    static void timerGone(PlayerRef player) {
        State state = player == null ? null : STATE.get(player.getUuid());
        if (state == null || (state.clock.isEmpty() && state.info == null)) return;
        state.clock = "";
        state.info = null;
        refreshBands(player);
    }

    /** Re-sends the band texts (on the player's world thread). */
    private static void refreshBands(PlayerRef player) {
        onWorld(player, () -> {
            Player p = playerOf(player);
            CustomUIHud hud = p != null ? p.getHudManager().getCustomHud(KEY) : null;
            if (!(hud instanceof CinemaBars)) return;
            UICommandBuilder b = new UICommandBuilder();
            bandText(b, STATE.get(player.getUuid()));
            hud.update(false, b);
        });
    }

    /**
     * debugLog on (2026-10-16, playtest: "which shot is playing?"): {@code name} written in the bottom band while the bands
     * show; "" clears it. Any thread.
     */
    static void shotName(PlayerRef player, String name) {
        if (player == null) return;
        State state = STATE.get(player.getUuid());
        if (state == null) return;
        String text = showShots() && name != null ? name : "";
        state.shot = text;
        onWorld(player, () -> {
            Player p = playerOf(player);
            CustomUIHud hud = p != null ? p.getHudManager().getCustomHud(KEY) : null;
            if (!(hud instanceof CinemaBars)) return;
            UICommandBuilder b = new UICommandBuilder();
            shotRow(b, text);
            hud.update(false, b);
        });
    }

    /** The shot's name as the bottom band's last row, centred ("" hides the row). */
    private static void shotRow(UICommandBuilder b, String text) {
        b.set("#BandBottomShot.Visible", !text.isEmpty());
        b.set("#BandBottomShot.Text", text);
    }

    /** The shot's name in the bottom band: debugLog / debugRecord, or /mobbattle test shotnames on (no log spam). */
    static volatile boolean shotNames;

    private static boolean showShots() {
        return shotNames || MobBattlePlugin.debugging();
    }

    /** The band height shown now for this player, in thousandths of the screen (0 = no bands). */
    private static int shownFor(PlayerRef player) {
        State state = player == null ? null : STATE.get(player.getUuid());
        return state == null ? 0 : Math.max(0, Math.min(500, state.shown));
    }

    /** Called from a shifted card's build(): lays it out for the bands shown now. */
    static void shift(UICommandBuilder b, PlayerRef player) {
        shift(b, shownFor(player));
    }

    private static void shift(UICommandBuilder b, int height) {
        b.set("#ShiftBar.FlexWeight", height);
        b.set("#ShiftRest.FlexWeight", 1000 - height);
        b.set("#ShiftPad.Visible", height <= 0);
    }

    /** Moves the cards this player has now (world thread). */
    private static void shiftCards(Player p, PlayerRef player, int height) {
        var huds = p.getHudManager();
        for (String key : SHIFTED) {
            CustomUIHud card = huds.getCustomHud(key);
            if (card == null) continue;
            UICommandBuilder b = new UICommandBuilder();
            shift(b, height);
            card.update(false, b);
        }
    }

    private static void hide(PlayerRef player) {
        Player p = playerOf(player);
        if (p == null) return;
        if (p.getHudManager().getCustomHud(KEY) != null) p.getHudManager().removeCustomHud(player, KEY);
        shiftCards(p, player, 0);
    }

    private static Player playerOf(PlayerRef player) {
        if (!player.isValid()) return null;
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return null;
        return ref.getStore().getComponent(ref, Player.getComponentType());
    }

    /** FightCamera.prune (BattleManager.maintain): forgets players who left (their HUD left with them). */
    static void prune() {
        if (STATE.isEmpty()) return;
        STATE.keySet().removeIf(id -> Universe.get().getPlayer(id) == null);
    }
}
