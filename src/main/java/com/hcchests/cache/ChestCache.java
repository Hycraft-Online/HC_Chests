package com.hcchests.cache;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Thread-safe in-memory cache for chest states.
 * Key format: "worldName:x:y:z"
 */
public class ChestCache {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests-Cache");

    private final ConcurrentHashMap<String, CachedChest> cache = new ConcurrentHashMap<>();

    /**
     * Get a cached chest by position. Returns null on cache miss.
     */
    public CachedChest get(String worldName, int x, int y, int z) {
        String key = worldName + ":" + x + ":" + y + ":" + z;
        CachedChest chest = cache.get(key);
        if (chest != null) {
            chest.touch();
        }
        return chest;
    }

    /**
     * Put a chest into the cache.
     */
    public void put(CachedChest chest) {
        cache.put(chest.getKey(), chest);
    }

    /**
     * Invalidate all cached chests in a chunk (used on WildernessRegen callback).
     * Does NOT flush dirty entries -- they're stale from a regenerated chunk.
     *
     * @return number of entries invalidated
     */
    public int invalidateChunk(String worldName, int chunkX, int chunkZ) {
        int sizeBefore = cache.size();
        cache.entrySet().removeIf(entry -> {
            CachedChest chest = entry.getValue();
            return chest.getWorldName().equals(worldName)
                    && chest.getChunkX() == chunkX
                    && chest.getChunkZ() == chunkZ;
        });
        int removed = sizeBefore - cache.size();
        if (removed > 0) {
            LOGGER.at(Level.FINE).log("Invalidated %d chests in chunk %d,%d", removed, chunkX, chunkZ);
        }
        return removed;
    }

    /**
     * Get all dirty entries for flushing to DB.
     */
    public List<CachedChest> getDirtyEntries() {
        List<CachedChest> dirty = new ArrayList<>();
        for (CachedChest chest : cache.values()) {
            if (chest.isDirty()) {
                dirty.add(chest);
            }
        }
        return dirty;
    }

    /**
     * Mark a chest as clean (after successful DB flush).
     */
    public void markClean(String key) {
        CachedChest chest = cache.get(key);
        if (chest != null) {
            chest.setDirty(false);
        }
    }

    /**
     * Mark a chest as clean only if no new writes occurred since the flush started.
     * Uses generation counter to avoid clearing dirty flag when new modifications
     * happened during the in-flight DB write.
     *
     * @param key the cache key
     * @param flushedGeneration the dirty generation that was flushed
     */
    public void markCleanIfUnchanged(String key, long flushedGeneration) {
        CachedChest chest = cache.get(key);
        if (chest != null && chest.getDirtyGeneration() == flushedGeneration) {
            chest.setDirty(false);
        }
    }

    /**
     * Evict stale entries that haven't been accessed within maxAgeMs.
     * ONLY clean entries are evicted. Dirty entries are never removed from cache
     * without being flushed first -- the caller must flush dirty entries before
     * calling this method. Any remaining dirty stale entries are skipped and
     * returned so the caller can flush them on the next cycle.
     *
     * @return list of dirty entries that could not be evicted (still need flush)
     */
    public List<CachedChest> evictStale(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        List<CachedChest> dirtyStale = new ArrayList<>();

        Iterator<Map.Entry<String, CachedChest>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            CachedChest chest = it.next().getValue();
            if (chest.getLastAccessed() < cutoff) {
                if (chest.isDirty()) {
                    // Do NOT evict dirty entries -- they have unflushed data.
                    // Collect them so the caller knows to flush on next cycle.
                    dirtyStale.add(chest);
                } else {
                    it.remove();
                }
            }
        }

        return dirtyStale;
    }

    /**
     * Get all dirty entries and clear the cache (for shutdown flush).
     */
    public List<CachedChest> drainDirty() {
        List<CachedChest> dirty = getDirtyEntries();
        cache.clear();
        return dirty;
    }

    public int size() {
        return cache.size();
    }
}
