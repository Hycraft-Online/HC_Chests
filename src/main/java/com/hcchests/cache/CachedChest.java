package com.hcchests.cache;

import com.hcchests.database.ItemStackSerializer;
import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory representation of a chest's state.
 */
public class CachedChest {

    private final String worldName;
    private final int x, y, z;
    private final int chunkX, chunkZ;
    private final String key;

    private String chestType = "wilderness";
    private ItemStack[] items;
    private String itemsJson = "[]";
    private int generatedLevel;
    private volatile boolean dirty;
    private final AtomicLong dirtyGeneration = new AtomicLong();
    private volatile long lastAccessed;

    public CachedChest(String worldName, int x, int y, int z) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
        this.chunkX = x >> 5; // Hytale uses 32-block chunks
        this.chunkZ = z >> 5;
        this.key = worldName + ":" + x + ":" + y + ":" + z;
        this.lastAccessed = System.currentTimeMillis();
    }

    public String getKey() { return key; }
    public String getWorldName() { return worldName; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public int getChunkX() { return chunkX; }
    public int getChunkZ() { return chunkZ; }

    public String getChestType() { return chestType; }
    public void setChestType(String chestType) { this.chestType = chestType; }

    public int getGeneratedLevel() { return generatedLevel; }
    public void setGeneratedLevel(int generatedLevel) { this.generatedLevel = generatedLevel; }

    public boolean isDirty() { return dirty; }
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
        if (dirty) {
            this.dirtyGeneration.incrementAndGet();
        }
    }

    /**
     * Get the current dirty generation counter. Each time this entry becomes dirty,
     * the generation increments. Used for compare-and-swap style clean marking to
     * avoid clearing dirty flag when new writes arrived during an in-flight flush.
     */
    public long getDirtyGeneration() { return dirtyGeneration.get(); }

    public long getLastAccessed() { return lastAccessed; }
    public void touch() { this.lastAccessed = System.currentTimeMillis(); }

    /**
     * Get the serialized JSON of items (for DB persistence).
     */
    public String getItemsJson() { return itemsJson; }

    /**
     * Set items from a JSON string (when loading from DB).
     */
    public void setItemsJson(String json) {
        this.itemsJson = json != null ? json : "[]";
        this.items = null; // Invalidate parsed cache
    }

    /**
     * Update items from an ItemStack array and serialize to JSON.
     * Synchronized to prevent torn reads between items/itemsJson/dirty fields.
     */
    public synchronized void updateItems(ItemStack[] newItems) {
        this.items = newItems;
        this.itemsJson = ItemStackSerializer.toJson(newItems);
        this.dirty = true;
        this.dirtyGeneration.incrementAndGet();
        this.lastAccessed = System.currentTimeMillis();
    }

    /**
     * Get items as an ItemStack array, deserializing from JSON if needed.
     * Synchronized to prevent double-deserialization and torn reads with updateItems.
     */
    public synchronized ItemStack[] getItems(int capacity) {
        if (items == null) {
            items = ItemStackSerializer.fromJson(itemsJson, capacity);
        }
        this.lastAccessed = System.currentTimeMillis();
        return items;
    }
}
