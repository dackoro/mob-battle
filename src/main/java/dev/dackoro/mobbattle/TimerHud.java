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
    /** Most subtitle lines the box has a layout for. */
    static final int MAX_LINES = 3;

    private final Message title;
    private final int lines;

    private TimerHud(PlayerRef player, Message title, int lines) {
        super(player, KEY);
        this.title = title;
        this.lines = lines;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI[lines - 1]);
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
        java.util.List<Message> lines = sub.isEmpty() ? java.util.List.of(Message.empty())
                : sub.size() > MAX_LINES ? sub.subList(0, MAX_LINES) : sub;
        CustomUIHud hud = huds.getCustomHud(KEY);
        // A layout per line count: when the count changes, the box is rebuilt with the right height.
        if (hud instanceof TimerHud old && old.lines != lines.size()) {
            huds.removeCustomHud(player, KEY);
            hud = null;
        }
        if (!(hud instanceof TimerHud)) {
            hud = new TimerHud(player, Message.translation(titleKey), lines.size());
            huds.addCustomHud(player, hud);
        }
        UICommandBuilder b = new UICommandBuilder();
        for (int i = 0; i < lines.size(); i++) {
            b.set(i == 0 ? "#Sub.TextSpans" : "#Sub" + (i + 1) + ".TextSpans", lines.get(i));
        }
        b.set("#Time.Text", MobBattleCommand.clock(secondsLeft));
        hud.update(false, b);
    }

    /** Removes the box (world thread of their world). */
    static void hide(PlayerRef player, Store<EntityStore> store) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
        }
    }
}
