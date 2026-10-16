package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle duelCard on|off (default off): a read-only card with both fighters of a 1 vs 1 duel and their stats
 * (health now/max, attack, defense, size), from the countdown until the duel ends. The numbers are the buffs they
 * carry ({@link Buffs#totalOf}), so a Buff Wand click shows up on the next refresh (once per second). Only duels with
 * exactly two fighters; shown to the owner's title viewers. A CustomUIHud like {@link Scoreboard}, layout in
 * Common/UI/Custom/MobBattle/DuelCard.ui (on the right, in the scoreboard's place: Scoreboard.cardShown hides the board).
 */
final class DuelCard extends CustomUIHud {

    static final String KEY = "MobBattleDuelCard";
    private static final String UI = "MobBattle/DuelCard.ui";
    private static final String BUFFED = "#7dff8a";
    private static final String PLAIN = "#c8d4e0";

    /** Players that have the card up. */
    private static final java.util.Set<UUID> SHOWN = ConcurrentHashMap.newKeySet();

    private DuelCard(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
        CinemaBars.shift(builder, getPlayerRef());
    }

    /** Cheap check for BattleLifecycleSystem: anything to show or to take down? */
    static boolean any() {
        if (!SHOWN.isEmpty()) return true;
        return FxSettings.duelCard && (BattleManager.hasBattles() || Countdown.hasPending());
    }

    /** A duel and who sees its card (the owner's title viewers; for an ownerless server event, its audience). */
    private record Duel(List<PlayerRef> viewers, List<Ref<EntityStore>> fighters) {
    }

    /** Once per second per world (BattleLifecycleSystem, world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        Map<UUID, Duel> want = new HashMap<>();
        if (FxSettings.duelCard) {
            List<Duel> duels = new ArrayList<>();
            for (Countdown.PendingDuel d : Countdown.pendingDuels()) {
                if (d.store() == store) duels.add(new Duel(FightFx.titleViewers(d.player()), d.mobs()));
            }
            for (BattleManager.Battle b : BattleManager.battles()) {
                if (b.over || b.store != store || b.kind != BattleManager.Kind.DUEL) continue;
                List<Ref<EntityStore>> members = new ArrayList<>(b.members());
                if (members.size() == 2) duels.add(new Duel(FightFx.titleViewers(b), members));
            }
            for (Duel d : duels) {
                for (PlayerRef p : d.viewers()) {
                    // A /mobbattle watch spectator has the watch card in the same spot.
                    if (p.isValid() && !BattleWatch.isWatching(p)) want.put(p.getUuid(), d);
                }
            }
        }
        for (UUID id : new ArrayList<>(SHOWN)) {
            if (want.containsKey(id)) continue;
            PlayerRef p = Universe.get().getPlayer(id);
            if (p == null || !p.isValid()) {
                SHOWN.remove(id);
            } else if (onStore(p, store)) {
                hide(p, store);
                SHOWN.remove(id);
            }
        }
        for (Map.Entry<UUID, Duel> e : want.entrySet()) {
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            if (p == null || !onStore(p, store)) continue;
            show(p, store, e.getValue());
            SHOWN.add(e.getKey());
        }
    }

    /** /mobbattle stop: every card goes (on each player's world thread). */
    static void clearAll() {
        for (UUID id : new ArrayList<>(SHOWN)) {
            PlayerRef p = Universe.get().getPlayer(id);
            SHOWN.remove(id);
            if (p == null || !p.isValid()) continue;
            Ref<EntityStore> ref = p.getReference();
            if (ref == null || !ref.isValid()) continue;
            Store<EntityStore> st = ref.getStore();
            st.getExternalData().getWorld().execute(() -> hide(p, st));
        }
    }

    private static boolean onStore(PlayerRef p, Store<EntityStore> store) {
        Ref<EntityStore> ref = p.getReference();
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    private static void show(PlayerRef player, Store<EntityStore> store, Duel duel) {
        Ref<EntityStore> ref = player.getReference();
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof DuelCard)) {
            hud = new DuelCard(player);
            huds.addCustomHud(player, hud);
        }
        UICommandBuilder b = new UICommandBuilder();
        fill(b, "A", duel.fighters().get(0), store);
        fill(b, "B", duel.fighters().get(1), store);
        hud.update(false, b);
    }

    private static void fill(UICommandBuilder b, String side, Ref<EntityStore> mob, Store<EntityStore> store) {
        boolean up = mob.isValid() && BattleManager.isAlive(mob, store);
        String name = mob.isValid() ? ChaosWand.nameOf(mob, store) : null;
        line(b, "#Name" + side, shorten(name == null ? "?" : name.replace('_', ' ')), up ? "#ffd23e" : "#888888");
        line(b, "#Health" + side, healthText(mob, store, up), up ? "#ff6b5e" : "#888888");
        line(b, "#Attack" + side, I18n.tr("hud.attack").param("v", stat(mob, Buffs.Stat.DAMAGE)), color(mob, Buffs.Stat.DAMAGE));
        line(b, "#Defense" + side, I18n.tr("hud.defense").param("v", stat(mob, Buffs.Stat.DEFENSE)), color(mob, Buffs.Stat.DEFENSE));
        float scale = scaleOf(mob, store);
        boolean scaled = Math.abs(scale - 1f) > 0.01f;
        line(b, "#Size" + side, I18n.tr("hud.size").param("v", sizeValue(scale)), scaled ? BUFFED : PLAIN);
    }

    /** A colored Message goes in TextSpans (.Text only takes a plain string; see Scoreboard). */
    private static void line(UICommandBuilder b, String id, String text, String color) {
        b.set(id + ".TextSpans", Message.raw(text).color(color));
    }

    static void line(UICommandBuilder b, String id, Message text, String color) {
        b.set(id + ".TextSpans", text.color(color));
    }

    /** "x1.50", or "base" when the size is not buffed. */
    static Message sizeValue(float scale) {
        return Math.abs(scale - 1f) > 0.01f
                ? Message.raw("x" + String.format(java.util.Locale.ROOT, "%.2f", scale)) : I18n.tr("hud.base");
    }

    /** The real size (EntityScaleComponent), so a size buff from before a restart still shows. */
    static float scaleOf(Ref<EntityStore> mob, Store<EntityStore> store) {
        if (!mob.isValid()) return 1f;
        float scale = Buffs.sizeScale(mob, store);
        return scale > 0 ? scale : 1f;
    }

    /** Names can be very long: the card is 270 px wide. */
    static String shorten(String name) {
        return name.length() <= 16 ? name : name.substring(0, 15) + "...";
    }

    static Message stat(Ref<EntityStore> mob, Buffs.Stat st) {
        int pct = mob.isValid() ? Buffs.totalOf(mob, st) : 0;
        return pct > 0 ? Message.raw(Buffs.brief(st, pct)) : I18n.tr("hud.base");
    }

    static String color(Ref<EntityStore> mob, Buffs.Stat st) {
        return mob.isValid() && Buffs.totalOf(mob, st) > 0 ? BUFFED : PLAIN;
    }

    static Message healthText(Ref<EntityStore> mob, Store<EntityStore> store, boolean up) {
        if (!up) return I18n.tr("hud.health.down");
        EntityStatMap stats = store.getComponent(mob, EntityStatMap.getComponentType());
        EntityStatValue h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        if (h == null || h.getMax() <= 0) return I18n.tr("hud.health.unknown");
        int pct = Buffs.totalOf(mob, Buffs.Stat.HEALTH);
        // A living mob never shows 0: 0.3 HP left (fractional damage) read "0 / 90" on a winner (user, 2026-10-13).
        long now = h.get() > 0 ? Math.max(1, Math.round(h.get())) : 0;
        Message m = I18n.tr(pct > 0 ? "hud.health.buff" : "hud.health")
                .param("now", now).param("max", Math.round(h.getMax()));
        return pct > 0 ? m.param("buff", Buffs.brief(Buffs.Stat.HEALTH, pct)) : m;
    }

    private static void hide(PlayerRef player, Store<EntityStore> store) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
        }
    }
}
