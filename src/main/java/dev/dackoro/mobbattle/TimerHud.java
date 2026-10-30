package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The fight timer box (/mobbattle timerStyle custom, the default): our own HUD, top right, with the battle kind,
 * who is fighting and the time left. A CustomUIHud like vanilla SpectatingHud, its layout in
 * Common/UI/Custom/MobBattle/TimerHud.ui (UICommandBuilder.append), added with HudManager.addCustomHud as GameModeTypeState does, updated with
 * {@link CustomUIHud#update} (one CustomHud packet a second) and removed with HudManager.removeCustomHud.
 */
final class TimerHud extends CustomUIHud {

    static final String KEY = "MobBattleTimer";

    /**
     * Common/UI/Custom/MobBattle/TimerHud.ui (an asset: editable and hot-reloaded with the dev pack), and
     * TimerHud2.ui / TimerHud3.ui: the same box, taller, for a subtitle on 2 or 3 lines.
     */
    private static final String[] UI = {"MobBattle/TimerHud.ui", "MobBattle/TimerHud2.ui", "MobBattle/TimerHud3.ui"};
    /** The same boxes without the clock, while the film's black band shows the time (bandTimer). */
    private static final String[] UI_BAND = {"MobBattle/TimerHudBand.ui", "MobBattle/TimerHudBand2.ui", "MobBattle/TimerHudBand3.ui"};
    /** Most subtitle lines the box has a layout for. */
    static final int MAX_LINES = 3;

    private final Message title;
    private final int lines;
    /** Built without the clock (the band has it). */
    private final boolean band;

    private TimerHud(PlayerRef player, Message title, int lines, boolean band) {
        super(player, KEY);
        this.title = title;
        this.lines = lines;
        this.band = band;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append((band ? UI_BAND : UI)[lines - 1]);
        CinemaBars.shift(builder, getPlayerRef());
        builder.set("#Title.Text", title);
    }

    /**
     * Shows or refreshes the box for {@code player} (world thread of their world). {@code lines} is the subtitle,
     * one Message per line, already split by the caller (a translated line can't be wrapped by characters on the
     * server): 1 to {@link #MAX_LINES}, the rest is dropped.
     */
    static void show(PlayerRef player, Store<EntityStore> store, String titleKey, java.util.List<Message> sub, int secondsLeft) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        // bandTimer: during a film the time sits in the black band; the box stays with its lines, without the clock
        // (2026-10-29, playtest: the whole card went away, it should only not show the time twice)
        Message info = null;
        for (Message line : sub.size() > MAX_LINES ? sub.subList(0, MAX_LINES) : sub) {
            info = info == null ? line : Message.join(info, Message.raw("  ·  "), line);
        }
        boolean band = CinemaBars.takeTimer(player, Message.translation(titleKey), MobBattleCommand.clock(secondsLeft), info);
        // with the clock in the band the box goes: its lines are in a band too (bandInfo top/bottom), not wanted (off),
        // or a scoreboard / duel card / all vs one card on the right already says who is left (2026-10-29, playtest: two
        // cards on the right, one of them half empty; then: a half empty card alone too); bandInfo box keeps it
        if (band && (!"box".equals(FxSettings.bandInfo) || huds.getCustomHud(Scoreboard.KEY) != null
                || huds.getCustomHud(DuelCard.KEY) != null || huds.getCustomHud(FocusCard.KEY) != null)) {
            if (huds.getCustomHud(KEY) != null) {
                huds.removeCustomHud(player, KEY);
                placeCards(huds, false);
            }
            return;
        }
        java.util.List<Message> lines = sub.isEmpty() ? java.util.List.of(Message.empty())
                : sub.size() > MAX_LINES ? sub.subList(0, MAX_LINES) : sub;
        CustomUIHud hud = huds.getCustomHud(KEY);
        // A layout per line count and clock: when either changes, the box is rebuilt with the right height.
        if (hud instanceof TimerHud old && (old.lines != lines.size() || old.band != band)) {
            huds.removeCustomHud(player, KEY);
            hud = null;
        }
        if (!(hud instanceof TimerHud)) {
            hud = new TimerHud(player, Message.translation(titleKey), lines.size(), band);
            huds.addCustomHud(player, hud);
            placeCards(huds, true);
        }
        UICommandBuilder b = new UICommandBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.set(i == 0 ? "#Sub.TextSpans" : "#Sub" + (i + 1) + ".TextSpans", lines.get(i));
        }
        if (!band) b.set("#Time.Text", MobBattleCommand.clock(secondsLeft));
        hud.update(false, b);
    }

    /** Removes the box (world thread of their world). */
    static void hide(PlayerRef player, Store<EntityStore> store) {
        CinemaBars.timerGone(player);
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
            placeCards(p.getHudManager(), false);
        }
    }

    /** The cards on the right (scoreboard, duel card, all vs one card) whose .ui leaves room over them for this box. */
    private static final String[] UNDER_BOX = {Scoreboard.KEY, DuelCard.KEY, FocusCard.KEY};

    /**
     * Those cards keep the box's room over them only while the box shows; without it they move up into its spot
     * (2026-10-29, playtest: with the clock in the film band the box went and left a hole over the scoreboard).
     */
    private static void placeCards(com.hypixel.hytale.server.core.entity.entities.player.hud.HudManager huds, boolean box) {
        for (String key : UNDER_BOX) {
            CustomUIHud card = huds.getCustomHud(key);
            if (card == null) continue;
            UICommandBuilder b = new UICommandBuilder();
            b.set("#TimerGap.Visible", box);
            card.update(false, b);
        }
    }

    /** For a right card's build(): whether this player has the box now (world thread). */
    static boolean boxShown(PlayerRef player) {
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) return true;
        Player p = ref.getStore().getComponent(ref, Player.getComponentType());
        return p == null || p.getHudManager().getCustomHud(KEY) != null;
    }
}
