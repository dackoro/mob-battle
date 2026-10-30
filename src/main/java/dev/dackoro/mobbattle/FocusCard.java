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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /mobbattle focusCard on|off (default off): the all vs one version of {@link DuelCard}. The lone target on top with
 * its stats (health, attack, defense, size with their buffs, kills), the attackers below (left / most seen at once,
 * their summed health, and the one that dealt the most damage). Shown to the owner's title viewers from the battle
 * start (the battle does not exist during the countdown) until it ends, refreshed once a second. Same spot as the
 * duel card (on the right, in the scoreboard's place); /mobbattle watch spectators get the watch card instead.
 */
final class FocusCard extends CustomUIHud {

    static final String KEY = "MobBattleFocusCard";
    private static final String UI = "MobBattle/FocusCard.ui";
    private static final String PLAIN = "#c8d4e0";

    private static final Set<UUID> SHOWN = ConcurrentHashMap.newKeySet();

    private FocusCard(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
        CinemaBars.shift(builder, getPlayerRef());
        builder.set("#TimerGap.Visible", TimerHud.boxShown(getPlayerRef()));
    }

    static boolean any() {
        if (!SHOWN.isEmpty()) return true;
        return FxSettings.focusCard && BattleManager.hasBattles();
    }

    /** Once per second per world (BattleLifecycleSystem, world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        Map<UUID, BattleManager.Battle> want = new HashMap<>();
        if (FxSettings.focusCard) {
            for (BattleManager.Battle b : BattleManager.battles()) {
                if (b.over || b.store != store || b.kind != BattleManager.Kind.ALL_VS_ONE) continue;
                for (PlayerRef p : FightFx.titleViewers(b)) {
                    if (p.isValid() && !BattleWatch.isWatching(p) && RightSlot.owns(p, b, store)) want.put(p.getUuid(), b);
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
        // One fill per battle, shared by its viewers (audit 2026-10-14: it walked every member once per viewer).
        // and language: the names are written in each viewer's (2026-10-30)
        Map<BattleManager.Battle, Map<String, UICommandBuilder>> filled = new HashMap<>();
        for (Map.Entry<UUID, BattleManager.Battle> e : want.entrySet()) {
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            if (p == null || !onStore(p, store)) continue;
            String lang = p.getLanguage();
            show(p, store, filled.computeIfAbsent(e.getValue(), b -> new HashMap<>())
                    .computeIfAbsent(String.valueOf(lang), k -> fill(e.getValue(), store, lang)));
            SHOWN.add(e.getKey());
        }
    }

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

    private static UICommandBuilder fill(BattleManager.Battle battle, Store<EntityStore> store, String lang) {
        // Side 1 = the lone target, side 0 = the attackers. The lone target leaves teamOf when it falls: keep it.
        Ref<EntityStore> lone = null;
        int attackers = 0;
        double hp = 0, hpMax = 0;
        Ref<EntityStore> top = null;
        double topDamage = 0;
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            Ref<EntityStore> r = e.getKey();
            if (!r.isValid() || !BattleManager.isAlive(r, store)) continue;
            if (e.getValue() == 1) {
                lone = r;
                continue;
            }
            attackers++;
            EntityStatMap stats = store.getComponent(r, EntityStatMap.getComponentType());
            EntityStatValue h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
            if (h != null) {
                hp += Math.max(0, h.get());
                hpMax += h.getMax();
            }
            double dealt = battle.damageOf.getOrDefault(r, 0.0);
            if (top == null || dealt > topDamage) {
                top = r;
                topDamage = dealt;
            }
        }
        if (lone != null) battle.cardLone = lone;
        else lone = battle.cardLone;
        if (attackers > battle.cardPeak) battle.cardPeak = attackers;

        UICommandBuilder b = new UICommandBuilder();
        boolean up = lone != null && lone.isValid() && BattleManager.isAlive(lone, store);
        String name = lone != null && lone.isValid() ? ChaosWand.nameOf(lone, store) : battle.focusName;
        line(b, "#Name", DuelCard.shorten(MobNames.text(name, lang)), up ? "#ffd23e" : "#888888");
        if (lone != null) {
            line(b, "#Health", DuelCard.healthText(lone, store, up), up ? "#ff6b5e" : "#888888");
            line(b, "#Attack", I18n.tr("hud.attack").param("v", DuelCard.stat(lone, Buffs.Stat.DAMAGE)), DuelCard.color(lone, Buffs.Stat.DAMAGE));
            line(b, "#Defense", I18n.tr("hud.defense").param("v", DuelCard.stat(lone, Buffs.Stat.DEFENSE)), DuelCard.color(lone, Buffs.Stat.DEFENSE));
            float scale = DuelCard.scaleOf(lone, store);
            boolean scaled = Math.abs(scale - 1f) > 0.01f;
            line(b, "#Size", I18n.tr("hud.size").param("v", DuelCard.sizeValue(scale)), scaled ? "#7dff8a" : PLAIN);
            BattleManager.Kills k = battle.kills.get(lone);
            line(b, "#Kills", I18n.tr("hud.kills").param("n", k == null ? 0 : k.count()), PLAIN);
        }
        line(b, "#Left", I18n.tr("hud.focus.attackers").param("n", attackers).param("max", Math.max(attackers, battle.cardPeak)), "#ffd23e");
        line(b, "#TeamHealth", hpMax > 0 ? I18n.tr("hud.health").param("now", Math.round(hp)).param("max", Math.round(hpMax))
                : I18n.tr("hud.health.none"), attackers > 0 ? "#ff6b5e" : "#888888");
        if (top != null && topDamage > 0) {
            String topName = ChaosWand.nameOf(top, store);
            line(b, "#TopName", I18n.tr("hud.focus.top").param("name", DuelCard.shorten(MobNames.text(topName, lang))), "#7dff8a");
            line(b, "#TopLine", I18n.tr("hud.focus.damage").param("n", Math.round(topDamage)), PLAIN);
        } else {
            line(b, "#TopName", I18n.tr("hud.focus.top.none"), PLAIN);
            line(b, "#TopLine", "", PLAIN);
        }
        return b;
    }

    private static void line(UICommandBuilder b, String id, String text, String color) {
        b.set(id + ".TextSpans", Message.raw(text).color(color));
    }

    private static void line(UICommandBuilder b, String id, Message text, String color) {
        DuelCard.line(b, id, text, color);
    }

    private static boolean onStore(PlayerRef p, Store<EntityStore> store) {
        Ref<EntityStore> ref = p.getReference();
        return ref != null && ref.isValid() && ref.getStore() == store;
    }

    private static void show(PlayerRef player, Store<EntityStore> store, UICommandBuilder fill) {
        Ref<EntityStore> ref = player.getReference();
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof FocusCard)) {
            hud = new FocusCard(player);
            huds.addCustomHud(player, hud);
        }
        hud.update(false, fill);
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
