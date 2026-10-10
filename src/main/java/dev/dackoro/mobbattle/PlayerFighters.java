package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Players inside battles (/mobbattle playerFights <kind> on). A player is a fighter like a mob, except that nobody
 * controls them: the mobs of the other sides attack them and their own side's mobs ignore them. Ways in:
 * <ul>
 *   <li>duel: Battle Wand on players like on mobs; crouching, on a mob (that mob vs you) or on the air after
 *       selecting one;</li>
 *   <li>everyone vs one: Chaos Wand left click on a player, or /mobbattle chaos me;</li>
 *   <li>royale: Chaos Wand right click takes in the players in the area too;</li>
 *   <li>teams: Team Wand left click on a player, or /mobbattle teams join &lt;team&gt; (and teams leave).</li>
 * </ul>
 * Players in Creative never count: they can't be put in a battle, and switching to Creative takes a player out.
 * Their hits: PlayerBattleDamageSystem (taken) and FightDamageSystem (dealt to mobs). Their death: PlayerDeathSystem.
 */
final class PlayerFighters {

    private static final String WARN = "#ffcc55";

    private PlayerFighters() {
    }

    /** True if the entity (a player) is crouching. */
    static boolean crouching(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (ref == null || !ref.isValid()) return false;
        MovementStatesComponent states = accessor.getComponent(ref, MovementStatesComponent.getComponentType());
        return states != null && states.getMovementStates() != null && states.getMovementStates().crouching;
    }

    /** True if {@code ref} is a player (any game mode). */
    static boolean isPlayer(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        return ref != null && ref.isValid() && accessor.getComponent(ref, PlayerRef.getComponentType()) != null;
    }

    /** True if {@code ref} is a player in Creative or spectating (/spectate, /mobbattle watch): they never fight. */
    static boolean isCreative(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        Player p = ref != null && ref.isValid() ? accessor.getComponent(ref, Player.getComponentType()) : null;
        return p != null && (p.getGameMode() == GameMode.Creative
                || com.hypixel.hytale.server.core.modules.entity.component.Spectating.isSpectating(ref, accessor));
    }

    /**
     * Can {@code target} (a player) fight in a {@code kind} battle? playerFights must be on for it, and they must
     * be alive and not in Creative. Tells {@code asker} why not.
     */
    static boolean eligible(PlayerRef asker, Ref<EntityStore> target, BattleManager.Kind kind,
                            ComponentAccessor<EntityStore> accessor) {
        if (!FxSettings.playerFights(kind)) {
            asker.sendMessage(Message.raw("Players can't fight in " + kind.key + " battles. Turn it on with /mobbattle playerFights "
                    + kind.key + " on").color(WARN));
            return false;
        }
        String name = ChaosWand.nameOf(target, accessor);
        boolean self = asker.getReference() == target;
        if (isCreative(target, accessor)) {
            asker.sendMessage(Message.raw((self ? "You are" : name + " is") + " in Creative: Creative players don't fight. "
                    + "Switch to Adventure (/gamemode adventure).").color(WARN));
            return false;
        }
        if (!BattleManager.isAlive(target, accessor)) {
            asker.sendMessage(Message.raw((self ? "You are" : name + " is") + " not alive.").color(WARN));
            return false;
        }
        return true;
    }

    /** /mobbattle chaos me: every mob within areaRadius attacks you (world thread). */
    static void chaosMe(PlayerRef player, Ref<EntityStore> me, Store<EntityStore> store) {
        if (!eligible(player, me, BattleManager.Kind.ALL_VS_ONE, store)) return;
        BattleManager.markPlayer(me, player.getUuid());
        ChaosWand.focus(player, me, me, store);
    }

    /** /mobbattle teams join &lt;team&gt;: you fight with that team (world thread). */
    static void joinTeam(PlayerRef player, Ref<EntityStore> me, String teamName, Store<EntityStore> store) {
        if (!eligible(player, me, BattleManager.Kind.TEAMS, store)) return;
        int team = teamName != null ? BattleManager.teamIndex(teamName) : -1;
        if (team < 0) {
            player.sendMessage(Message.raw("Usage: /mobbattle teams join <team> (see /mobbattle teams list)").color(WARN));
            return;
        }
        BattleManager.markPlayer(me, player.getUuid());
        BattleManager.assign(BattleManager.draft(player), me, team);
        player.sendMessage(Message.raw("You joined the " + Teams.name(team) + " team: its mobs won't attack you. "
                + "Start the battle with the Team Wand or /mobbattle teams start.").color(Teams.color(team)));
    }

    /** /mobbattle teams leave: out of your team, or out of the team battle you are in. */
    static void leaveTeam(PlayerRef player, Ref<EntityStore> me) {
        boolean left = BattleManager.leave(me);
        player.sendMessage(Message.raw(left ? "You left your team." : "You are not in a team.").color(left ? "#aaaaaa" : WARN));
    }

    /**
     * Royale (playerFights royale on): the players within {@code radius} of {@code center} who may fight (not in
     * Creative, alive), marked as fighters of {@code owner}'s battle. Empty with the setting off.
     */
    static List<Ref<EntityStore>> playersAround(PlayerRef owner, Vector3d center, double radius,
                                                ComponentAccessor<EntityStore> accessor) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        if (!FxSettings.playerFights(BattleManager.Kind.ROYALE)) return out;
        // getAllEntitiesInSphere returns a thread-local list: read it right away.
        for (Ref<EntityStore> r : TargetUtil.getAllEntitiesInSphere(center, radius, accessor)) {
            if (isPlayer(r, accessor) && !isCreative(r, accessor) && BattleManager.isAlive(r, accessor)) {
                out.add(r);
            }
        }
        for (Ref<EntityStore> r : out) BattleManager.markPlayer(r, owner.getUuid());
        return out;
    }

    /**
     * Every FightFx.TEAM_RING_REFRESH_MS per world (BattleLifecycleSystem, world thread, outside the systems):
     * what FightTickSystem does for NPCs, which it never ticks for players: Creative check, timeUp kill/judge,
     * snaps, their queued team look and their team ring.
     */
    static void tick(Store<EntityStore> store) {
        // Queued with world.execute: the world may have closed since.
        if (store.isShutdown()) return;
        long now = System.currentTimeMillis();
        TeamFx.applyPlayers(store);
        Snap.applyPlayers(store, now);
        for (Ref<EntityStore> p : new ArrayList<>(BattleManager.playerFighters())) {
            if (!p.isValid() || p.getStore() != store) continue;
            BattleManager.Battle battle = BattleManager.battleOf(p);
            if (isCreative(p, store)) {
                out(p, battle, store);
                continue;
            }
            if (battle != null && battle.killAll && battle.team(p) != battle.survivorTeam
                    && BattleManager.isAlive(p, store) && !WandFx.isPlayerDying(p)) {
                // timeUp kill / judge: players fall like everyone else on the losing sides.
                WandFx.killPlayer(p, WandFx.SOUL_RIP, FxSettings.killDelayMs, store);
            }
            if (battle == null || battle.isTeams()) {
                FightTickSystem.drawTeamRing(p, battle, store);
            }
        }
    }

    /**
     * When a countdown's battle starts: a mob, or a player still marked as a fighter and not in Creative. A player
     * who died or switched to Creative during the countdown was unmarked, so they stay out.
     */
    static boolean canStart(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        if (!isPlayer(ref, accessor)) return true;
        return BattleManager.isPlayerFighter(ref) && !isCreative(ref, accessor);
    }

    /** A fighter switched to Creative: out of their battle (their side may lose) or their team. */
    private static void out(Ref<EntityStore> p, BattleManager.Battle battle, Store<EntityStore> store) {
        PlayerRef player = store.getComponent(p, PlayerRef.getComponentType());
        // Waiting in a countdown (no battle, no team yet): unmarked, so canStart keeps them out when it starts,
        // and this runs (and tells them) only once.
        if (!BattleManager.leave(p)) BattleManager.unmarkPlayer(p);
        if (player != null) {
            player.sendMessage(Message.raw("Creative: you don't count as a fighter any more.").color(WARN));
            MobBattlePlugin.log("Battle: " + player.getUsername() + " switched to Creative and left the fight");
        }
    }
}
