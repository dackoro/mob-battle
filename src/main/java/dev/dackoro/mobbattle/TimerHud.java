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
    /** Characters per subtitle line (12 px bold uppercase in the 218 px wide body). */
    private static final int LINE_CHARS = 28;

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
        builder.set("#Title.Text", title);
    }

    /**
     * The subtitle on 1 to 3 lines: split at " | " first, then at spaces so no line passes LINE_CHARS; the
     * third line is cut with "..." if it still doesn't fit.
     */
    static java.util.List<String> wrap(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : text.split(" \\| ")) {
            StringBuilder line = new StringBuilder();
            for (String word : part.split(" ")) {
                if (line.length() > 0 && line.length() + 1 + word.length() > LINE_CHARS) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            if (line.length() > 0) out.add(line.toString());
        }
        if (out.isEmpty()) out.add("");
        if (out.size() > 3) {
            String last = String.join(" ", out.subList(2, out.size()));
            out = new java.util.ArrayList<>(out.subList(0, 2));
            out.add(last.length() > LINE_CHARS ? last.substring(0, LINE_CHARS - 3) + "..." : last);
        }
        return out;
    }

    /** Shows or refreshes the box for {@code player} (world thread of their world). */
    static void show(PlayerRef player, Store<EntityStore> store, String titleKey, String sub, int secondsLeft) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        java.util.List<String> lines = wrap(sub);
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
            b.set(i == 0 ? "#Sub.Text" : "#Sub" + (i + 1) + ".Text", lines.get(i));
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
