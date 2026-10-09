package dev.dackoro.mobbattle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a role has an attack at all, read from its role JSON (the NPC builder's file): the "Attack" value set
 * by the role ("Modify") or by the template it references ("Parameters"). Template_Animal_Neutral defaults it to
 * "" (Bunny, Rabbit: no attack; Boar sets Root_NPC_Attack_Melee). Unknown (no Attack parameter, unreadable) = null.
 */
final class RoleAttack {

    private static final Map<String, Optional<Boolean>> CACHE = new ConcurrentHashMap<>();

    private RoleAttack() {
    }

    /** False only when the role surely has no attack. */
    static boolean mayAttack(Ref<EntityStore> npc, ComponentAccessor<EntityStore> accessor) {
        NPCEntity e = accessor.getComponent(npc, NPCEntity.getComponentType());
        if (e == null || e.getRoleName() == null) return true;
        Boolean has = CACHE.computeIfAbsent(e.getRoleName(), n -> Optional.ofNullable(resolve(n, 0))).orElse(null);
        return has == null || has;
    }

    private static Boolean resolve(String name, int depth) {
        if (name == null || depth > 8) return null;
        try {
            var bm = NPCPlugin.get().getBuilderManager();
            int index = bm.getIndex(name);
            var info = index >= 0 ? bm.tryGetBuilderInfo(index) : null;
            if (info == null || info.getPath() == null) return null;
            JsonObject json = JsonParser.parseString(Files.readString(info.getPath())).getAsJsonObject();
            if (json.has("Modify") && json.getAsJsonObject("Modify").has("Attack")) {
                return isSet(json.getAsJsonObject("Modify").get("Attack"));
            }
            if (json.has("Reference")) {
                Boolean r = resolve(json.get("Reference").getAsString(), depth + 1);
                if (r != null) return r;
            }
            if (json.has("Parameters") && json.getAsJsonObject("Parameters").has("Attack")) {
                JsonElement p = json.getAsJsonObject("Parameters").get("Attack");
                return isSet(p.isJsonObject() && p.getAsJsonObject().has("Value") ? p.getAsJsonObject().get("Value") : p);
            }
        } catch (Exception ex) {
            MobBattlePlugin.debug("could not read the attack of role " + name + ": " + ex);
        }
        return null;
    }

    private static boolean isSet(JsonElement v) {
        if (v == null || v.isJsonNull()) return false;
        if (v.isJsonPrimitive()) return !v.getAsString().isBlank();
        return true; // { "Compute": ... } or an inline attack
    }

    /** Assets reloaded: forget. */
    static void clear() {
        CACHE.clear();
    }
}
