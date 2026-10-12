package dev.dackoro.mobbattle;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;

/**
 * A sound when a player switches to a wand in the hotbar (vanilla InventorySetActiveSlotEvent, the event the game's
 * own tool-slot system listens to). A system instead of the item's SwapTo interaction: vanilla Swap chains end in
 * ChangeActiveSlot, and a chain of our own could get in the way of the slot change itself.
 */
public class WandEquipSoundSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {

    /** Item id -> sound event played at the player when it becomes the active hotbar item. */
    private static final Map<String, String> SOUNDS = Map.of(
            "MobBattle_MorphWand", "SFX_MobBattle_OmnitrixOn");

    /** The equip sound plays at most once in this long per player (scrolling through the hotbar replayed it). */
    private static final long COOLDOWN_MS = 5000;
    /** Player UUID -> when its equip sound may play again. Tiny (one entry per player), entries expire on use. */
    private static final Map<java.util.UUID, Long> READY_AT = new java.util.concurrent.ConcurrentHashMap<>();

    public WandEquipSoundSystem() {
        super(InventorySetActiveSlotEvent.class);
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> buffer, InventorySetActiveSlotEvent event) {
        if (event.getInventorySectionId() != InventoryComponent.HOTBAR_SECTION_ID) return;
        int slot = event.getNewSlot();
        if (slot < 0 || slot == event.getPreviousSlot()) return;
        Ref<EntityStore> player = chunk.getReferenceTo(index);
        InventoryComponent.Hotbar hotbar = store.getComponent(player, InventoryComponent.Hotbar.getComponentType());
        if (hotbar == null || hotbar.getInventory() == null) return;
        ItemStack item = hotbar.getInventory().getItemStack((short) slot);
        String sound = item == null ? null : SOUNDS.get(item.getItemId());
        if (sound == null) return;
        PlayerRef who = store.getComponent(player, PlayerRef.getComponentType());
        long now = System.currentTimeMillis();
        if (who != null) {
            if (READY_AT.getOrDefault(who.getUuid(), 0L) > now) return;
            READY_AT.put(who.getUuid(), now + COOLDOWN_MS);
        }
        WandFx.burst(null, sound, ChaosWand.positionOf(player, store), buffer);
    }
}
