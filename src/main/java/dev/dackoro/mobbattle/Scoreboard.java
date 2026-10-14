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
 * /mobbattle scoreboard &lt;kind|all&gt; on|off (default: teams on): a HUD under the timer box with one row per side,
 * refreshed once per second for the battle's title viewers (titleScope).
 * <ul>
 *   <li>teams: "RED 4/6 · 3 kills" in the team color;</li>
 *   <li>everyone vs one: the target and its health, then "Attackers 12/20";</li>
 *   <li>royale: "15/40 left", then the top killers;</li>
 *   <li>duel: each fighter and its health.</li>
 * </ul>
 * A CustomUIHud like {@link TimerHud}, layout in Common/UI/Custom/MobBattle/ScoreHud.ui (rows hidden with Visible).
 */
final class Scoreboard extends CustomUIHud {

    static final String KEY = "MobBattleScore";
    private static final String UI = "MobBattle/ScoreHud.ui";
    private static final int ROWS = 8;

    /** Player -> the battle whose board they see (one board per player: their newest battle). */
    private static final Map<UUID, UUID> SHOWN = new ConcurrentHashMap<>();

    private Scoreboard(PlayerRef player) {
        super(player, KEY);
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(UI);
    }

    static boolean any() {
        return !SHOWN.isEmpty() || BattleManager.hasBattles();
    }

    /**
     * A 1v1 duel with the duel card, or an all vs one with the focus card: the card takes the board's place on the right
     * and says the same and more, so the board is not shown (user, 2026-10-13).
     */
    private static boolean cardShown(BattleManager.Battle b) {
        return (b.kind == BattleManager.Kind.DUEL && FxSettings.duelCard && b.teamOf.size() <= 2)
                || (b.kind == BattleManager.Kind.ALL_VS_ONE && FxSettings.focusCard);
    }

    /** Once per second per world (BattleLifecycleSystem, world thread). */
    static void tick(Store<EntityStore> store) {
        if (store.isShutdown()) return;
        Map<UUID, BattleManager.Battle> want = new HashMap<>();
        for (BattleManager.Battle b : BattleManager.battles()) {
            if (b.over || b.store != store || !FxSettings.scoreboard(b.kind) || cardShown(b)) continue;
            for (PlayerRef p : FightFx.titleViewers(b)) {
                if (p.isValid()) want.put(p.getUuid(), b);
            }
        }
        // Boards of battles that ended (or of players who left their range): removed.
        for (Map.Entry<UUID, UUID> e : new ArrayList<>(SHOWN.entrySet())) {
            BattleManager.Battle b = want.get(e.getKey());
            if (b != null && b.id.equals(e.getValue())) continue;
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            if (p == null || !p.isValid()) {
                SHOWN.remove(e.getKey());
            } else if (onStore(p, store)) {
                hide(p, store);
                SHOWN.remove(e.getKey());
            }
        }
        for (Map.Entry<UUID, BattleManager.Battle> e : want.entrySet()) {
            PlayerRef p = Universe.get().getPlayer(e.getKey());
            if (p == null || !onStore(p, store)) continue;
            show(p, store, e.getValue());
            SHOWN.put(e.getKey(), e.getValue().id);
        }
    }

    /** /mobbattle stop: every board goes (on each player's world thread). */
    static void clearAll() {
        for (UUID id : new ArrayList<>(SHOWN.keySet())) {
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

    private record Row(Message text, String color) {
    }

    private static void show(PlayerRef player, Store<EntityStore> store, BattleManager.Battle battle) {
        Ref<EntityStore> ref = player.getReference();
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p == null) return;
        var huds = p.getHudManager();
        CustomUIHud hud = huds.getCustomHud(KEY);
        if (!(hud instanceof Scoreboard)) {
            hud = new Scoreboard(player);
            huds.addCustomHud(player, hud);
        }
        List<Row> rows = rows(battle, store);
        UICommandBuilder b = new UICommandBuilder();
        b.set("#Title.TextSpans", title(battle));
        for (int i = 0; i < ROWS; i++) {
            boolean on = i < rows.size();
            b.set("#Row" + i + ".Visible", on);
            // A colored Message goes in TextSpans (as vanilla pages do): .Text only takes a plain string, and a
            // Message there makes the client drop the connection ("Failed to apply CustomUI HUD commands").
            if (on) b.set("#Text" + i + ".TextSpans", rows.get(i).text().color(rows.get(i).color()));
        }
        hud.update(false, b);
    }

    private static void hide(PlayerRef player, Store<EntityStore> store) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != store) return;
        Player p = store.getComponent(ref, Player.getComponentType());
        if (p != null && p.getHudManager().getCustomHud(KEY) != null) {
            p.getHudManager().removeCustomHud(player, KEY);
        }
    }

    private static Message title(BattleManager.Battle battle) {
        return I18n.tr("hud.score.title." + battle.kind.key);
    }

    /** The rows of a battle (at most ROWS). Counted, not collected: battles can hold hundreds of mobs. */
    private static List<Row> rows(BattleManager.Battle battle, Store<EntityStore> store) {
        List<Row> out = new ArrayList<>();
        switch (battle.kind) {
            case TEAMS -> {
                Map<Integer, int[]> sides = sides(battle, store);
                for (Map.Entry<Integer, int[]> e : sides.entrySet()) {
                    int team = e.getKey();
                    int kills = battle.killsBy.getOrDefault(team, 0);
                    out.add(new Row(I18n.tr("hud.score.team").param("name", battle.name(team))
                            .param("alive", e.getValue()[0]).param("total", e.getValue()[1]).param("kills", kills),
                            battle.color(team)));
                }
            }
            case ALL_VS_ONE -> {
                Map<Integer, int[]> sides = sides(battle, store);
                Ref<EntityStore> target = null;
                for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
                    if (e.getValue() == 1) target = e.getKey();
                }
                int[] one = sides.getOrDefault(1, new int[2]);
                out.add(new Row(one[0] > 0 && target != null
                        ? I18n.tr("hud.score.hp").param("name", pretty(battle.focusName)).param("pct", health(target, store))
                        : I18n.tr("hud.score.down").param("name", pretty(battle.focusName)), "#ffd23e"));
                int[] crowd = sides.getOrDefault(0, new int[2]);
                out.add(new Row(I18n.tr("hud.score.attackers").param("alive", crowd[0]).param("total", crowd[1]), "#ff6b5e"));
            }
            case ROYALE -> {
                int alive = 0;
                for (Ref<EntityStore> m : battle.members()) if (BattleManager.isAlive(m, store)) alive++;
                out.add(new Row(I18n.tr("hud.score.left").param("alive", alive).param("total", battle.teamOf.size()), "#ffffff"));
                java.util.List<BattleManager.Kills> top = battle.topKillers(ROWS - 1);
                int[] places = BattleManager.places(top, battle.sharedPlaces());
                for (int i = 0; i < top.size(); i++) {
                    BattleManager.Kills k = top.get(i);
                    out.add(new Row(I18n.tr("hud.score.place").param("place", places[i]).param("name", pretty(k.name()))
                            .param("kills", k.count()), places[i] == 1 ? "#ffd23e" : "#c8e6ff"));
                }
            }
            case DUEL -> {
                for (Ref<EntityStore> m : battle.members()) {
                    boolean up = BattleManager.isAlive(m, store);
                    String who = pretty(ChaosWand.nameOf(m, store));
                    out.add(new Row(up ? I18n.tr("hud.score.hp").param("name", who).param("pct", health(m, store))
                            : I18n.tr("hud.score.down").param("name", who), up ? "#ffffff" : "#888888"));
                    if (out.size() >= 2) break;
                }
            }
        }
        return out.size() > ROWS ? out.subList(0, ROWS) : out;
    }

    /** Side -> {alive, total}, in side order. */
    static Map<Integer, int[]> sides(BattleManager.Battle battle, Store<EntityStore> store) {
        Map<Integer, int[]> sides = new java.util.TreeMap<>();
        for (Map.Entry<Ref<EntityStore>, Integer> e : battle.teamOf.entrySet()) {
            int[] c = sides.computeIfAbsent(e.getValue(), k -> new int[2]);
            c[1]++;
            if (BattleManager.isAlive(e.getKey(), store)) c[0]++;
        }
        return sides;
    }

    private static int health(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (!ref.isValid()) return 0;
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue h = stats != null ? stats.get(DefaultEntityStatTypes.getHealth()) : null;
        if (h == null || h.getMax() <= 0) return 0;
        return (int) Math.round(Math.max(0, Math.min(1, h.get() / h.getMax())) * 100);
    }

    private static String pretty(String name) {
        return name == null ? "?" : name.replace('_', ' ');
    }
}
