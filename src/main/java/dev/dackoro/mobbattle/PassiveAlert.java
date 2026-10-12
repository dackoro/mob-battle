package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;

/**
 * Bottom-left alert while only mobs that can't fight back are left against one side (/mobbattle passiveWin,
 * BattleManager.passiveEnd): who wins, or that it ends in a draw, and the seconds left. A CustomUIHud like TimerHud,
 * layout in Common/UI/Custom/MobBattle/PassiveAlert.ui, refreshed once a second from checkWinner (world thread).
 */
final class PassiveAlert extends CustomUIHud {

    static final String KEY = "MobBattlePassive";
    private static final String UI = "MobBattle/PassiveAlert.ui";

    private PassiveAlert(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
    }

    /** Shows or refreshes the alert for {@code player} (world thread of {@code store}). */
    static void show(PlayerRef player, Store<EntityStore> store, String title, String text, int secondsLeft) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof PassiveAlert)) {
            hud = new PassiveAlert(player);
            huds.addCustomHud(player, hud);
        }
        UICommandBuilder b = new UICommandBuilder();
        b.set("#Title.Text", title);
        b.set("#Text.Text", text);
        b.set("#Time.Text", secondsLeft + " s");
        hud.update(false, b);
    }

    /** Removes the alert from every player in {@code viewers}, each on their own world thread; clears the set. */
    static void hideAll(java.util.Set<UUID> viewers) {
        for (UUID id : viewers) {
            PlayerRef p = Universe.get().getPlayer(id);
            if (p == null || !p.isValid()) continue;
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) continue;
            Store<EntityStore> st = ref.getStore();
            st.getExternalData().getWorld().execute(() -> {
                Ref<EntityStore> now = p.getReference();
                if (now == null || !now.isValid() || now.getStore() != st) return;
                Player player = st.getComponent(now, Player.getComponentType());
                if (player != null && player.getHudManager().getCustomHud(KEY) != null) {
                    player.getHudManager().removeCustomHud(p, KEY);
                }
            });
        }
        viewers.clear();
    }
}
