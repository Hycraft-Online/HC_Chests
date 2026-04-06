package com.hcchests.tasks;

import com.hcchests.cache.CachedChest;
import com.hcchests.cache.ChestCache;
import com.hcchests.database.ChestRepository;
import com.hccore.api.HC_CoreAPI;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Periodic task that flushes dirty cache entries to the database
 * and evicts stale entries from memory.
 *
 * Ordering is critical: flush ALL dirty entries first, then evict stale clean entries.
 * Dirty entries are never evicted from cache until the DB write succeeds. A generation
 * counter prevents clearing the dirty flag when new modifications arrived during an
 * in-flight DB write.
 */
public class CacheFlushTask implements Runnable {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests-Flush");

    private final ChestCache cache;
    private final ChestRepository repository;
    private long lastEvictionRun = 0;

    public CacheFlushTask(ChestCache cache, ChestRepository repository) {
        this.cache = cache;
        this.repository = repository;
    }

    private long successCount = 0;

    @Override
    public void run() {
        try {
            // ── 1. FLUSH all dirty entries to DB FIRST ─────────────────────
            List<CachedChest> dirty = cache.getDirtyEntries();
            if (!dirty.isEmpty()) {
                // Snapshot the dirty generation for each entry BEFORE the DB write.
                // If new writes arrive during batchUpsert, the generation will be
                // higher and markCleanIfUnchanged will correctly leave it dirty.
                Map<String, Long> flushedGenerations = new HashMap<>(dirty.size());
                for (CachedChest chest : dirty) {
                    flushedGenerations.put(chest.getKey(), chest.getDirtyGeneration());
                }

                boolean dbSuccess = repository.batchUpsert(dirty);
                if (dbSuccess) {
                    // Mark clean ONLY if the DB write succeeded AND no new writes
                    // arrived during the flush (generation-aware).
                    for (CachedChest chest : dirty) {
                        Long gen = flushedGenerations.get(chest.getKey());
                        if (gen != null) {
                            cache.markCleanIfUnchanged(chest.getKey(), gen);
                        }
                    }
                    LOGGER.at(Level.FINE).log("Flushed %d chest states to database", dirty.size());
                } else {
                    // DB write failed -- leave entries dirty so they'll be retried next cycle.
                    LOGGER.at(Level.WARNING).log("DB flush failed for %d entries, will retry next cycle", dirty.size());
                }
            }

            // ── 2. EVICT stale clean entries AFTER flush ───────────────────
            long now = System.currentTimeMillis();
            int evictionIntervalSeconds = HC_CoreAPI.getSettingInt("HC_Chests", "cache.evictionIntervalSeconds", 60);
            long evictionAgeMs = HC_CoreAPI.getSettingInt("HC_Chests", "cache.evictionAgeMs", 600000);
            if (now - lastEvictionRun >= evictionIntervalSeconds * 1000L) {
                lastEvictionRun = now;
                // evictStale now only removes clean entries; dirty stale entries are
                // returned so we know to flush them urgently on the next cycle.
                List<CachedChest> dirtyStale = cache.evictStale(evictionAgeMs);
                if (!dirtyStale.isEmpty()) {
                    // These are stale AND dirty -- flush them immediately to DB so they
                    // can be evicted on the next cycle.
                    Map<String, Long> staleGenerations = new HashMap<>(dirtyStale.size());
                    for (CachedChest chest : dirtyStale) {
                        staleGenerations.put(chest.getKey(), chest.getDirtyGeneration());
                    }

                    boolean staleFlushOk = repository.batchUpsert(dirtyStale);
                    if (staleFlushOk) {
                        for (CachedChest chest : dirtyStale) {
                            Long gen = staleGenerations.get(chest.getKey());
                            if (gen != null) {
                                cache.markCleanIfUnchanged(chest.getKey(), gen);
                            }
                        }
                        LOGGER.at(Level.FINE).log("Urgently flushed %d stale dirty chests", dirtyStale.size());
                    } else {
                        LOGGER.at(Level.WARNING).log("Failed to flush %d stale dirty chests, will retry", dirtyStale.size());
                    }
                }
            }
            successCount++;
            if (successCount % 40 == 0) { // Every ~10 minutes at 15s interval
                LOGGER.at(Level.FINE).log("Flush task alive (cycle %d, cache size: %d)", successCount, cache.size());
            }
        } catch (Throwable t) {
            LOGGER.at(Level.SEVERE).log("Cache flush error (task will continue): " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * Final flush on shutdown - drain all dirty entries.
     */
    public void shutdown() {
        try {
            List<CachedChest> dirty = cache.drainDirty();
            if (!dirty.isEmpty()) {
                repository.batchUpsert(dirty);
                LOGGER.at(Level.INFO).log("Shutdown: flushed %d remaining chest states", dirty.size());
            }
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Shutdown flush error: " + e.getMessage());
        }
    }
}
