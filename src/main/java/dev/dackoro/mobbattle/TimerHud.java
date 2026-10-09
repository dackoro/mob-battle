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

    /** Common/UI/Custom/MobBattle/TimerHud.ui (an asset: editable and hot-reloaded with the dev pack). */
    private static final String UI = "MobBattle/TimerHud.ui";

    private final Message title;

    private TimerHud(PlayerRef player, Message title) {
        super(player, KEY);
        this.title = title;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
        builder.set("#Title.Text", title);
    }

    /** Shows or refreshes the box for {@code player} (world thread of their world). */
    static void show(PlayerRef player, Store<EntityStore> store, String titleKey, String sub, int secondsLeft) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof TimerHud)) {
            hud = new TimerHud(player, Message.translation(titleKey));
            huds.addCustomHud(player, hud);
        }
        UICommandBuilder b = new UICommandBuilder();
        b.set("#Sub.Text", sub);
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
