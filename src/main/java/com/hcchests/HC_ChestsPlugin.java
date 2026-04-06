package com.hcchests;

import com.hcchests.cache.ChestCache;
import com.hcchests.commands.ChestScanCommand;
import com.hcchests.config.ChestConfig;
import com.hcchests.config.DatabaseConfig;
import com.hcchests.database.ChestRepository;
import com.hcchests.database.DatabaseManager;
import com.hcchests.systems.ChestOpenSystem;
import com.hcchests.systems.WorldgenChestFilterSystem;
import com.hcchests.tasks.CacheFlushTask;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;

import java.io.File;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class HC_ChestsPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests");

    private static volatile HC_ChestsPlugin instance;

    private DatabaseManager databaseManager;
    private ChestCache chestCache;
    private ChestRepository chestRepository;
    private CacheFlushTask flushTask;
    private ScheduledFuture<?> flushFuture;
    private WorldgenChestFilterSystem worldgenChestFilterSystem;

    public HC_ChestsPlugin(JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HC_ChestsPlugin getInstance() {
        return instance;
    }

    @Override
    public void setup() {
        LOGGER.at(Level.INFO).log("HC_Chests setting up...");

        // ── Settings ────────────────────────────────────────────────
        ChestConfig.registerDefaults();

        // ── Database ──────────────────────────────────────────────
        File configDir = new File("mods/.hc_config/HC_Chests");
        DatabaseConfig dbConfig = DatabaseConfig.load(configDir);

        try {
            databaseManager = new DatabaseManager(
                    dbConfig.getUrl(),
                    dbConfig.getUsername(),
                    dbConfig.getPassword(),
                    dbConfig.getPoolSize()
            );
        } catch (Exception e) {
            LOGGER.at(Level.SEVERE).log("Failed to initialize database: " + e.getMessage());
            return;
        }

        // ── Cache & Repository ────────────────────────────────────
        chestCache = new ChestCache();
        chestRepository = new ChestRepository(databaseManager);

        // ── Claim Checker ─────────────────────────────────────────
        ChestOpenSystem.ClaimChecker claimChecker = buildClaimChecker();

        // ── Chunk/Worldgen Filtering (authoritative path) ────────
        worldgenChestFilterSystem = new WorldgenChestFilterSystem(
                BlockStateModule.get().getComponentType(ItemContainerState.class),
                claimChecker
        );
        getChunkStoreRegistry().registerSystem(worldgenChestFilterSystem);
        getEventRegistry().registerGlobal(
                EventPriority.LAST,
                ChunkPreLoadProcessEvent.class,
                worldgenChestFilterSystem::onChunkPreLoadProcess
        );
        LOGGER.at(Level.INFO).log("Registered WorldgenChestFilterSystem (newly generated wilderness chunks)");

        // ── Commands ────────────────────────────────────────────────
        getCommandRegistry().registerCommand(new ChestScanCommand(chestRepository, claimChecker));
        LOGGER.at(Level.INFO).log("Registered /chestscan command");

        // ── Flush Task ────────────────────────────────────────────
        flushTask = new CacheFlushTask(chestCache, chestRepository);
        int flushInterval = ChestConfig.getFlushIntervalSeconds();
        flushFuture = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                flushTask,
                flushInterval,
                flushInterval,
                TimeUnit.SECONDS
        );

        LOGGER.at(Level.INFO).log("HC_Chests setup complete (flush every %ds, evict after %ds idle)",
                flushInterval, ChestConfig.getEvictionAgeMs() / 1000);
    }

    @Override
    public void start() {
        // ── WildernessRegen Integration ───────────────────────────
        registerWildernessRegenListener();
        LOGGER.at(Level.INFO).log("HC_Chests started");
    }

    @Override
    public void shutdown() {
        // Cancel periodic flush
        if (flushFuture != null) {
            flushFuture.cancel(false);
        }

        // Final flush of dirty entries
        if (flushTask != null) {
            flushTask.shutdown();
        }

        // Close DB pool
        if (databaseManager != null) {
            databaseManager.close();
        }

        instance = null;
        LOGGER.at(Level.INFO).log("HC_Chests shutdown");
    }

    /**
     * Build claim checker - tries HC_Factions, falls back to "all wilderness".
     */
    private ChestOpenSystem.ClaimChecker buildClaimChecker() {
        try {
            Class.forName("com.hcfactions.HC_FactionsPlugin");
            LOGGER.at(Level.INFO).log("HC_Factions detected, using faction claims for chest type determination");
            return (worldName, chunkX, chunkZ) -> {
                try {
                    var plugin = com.hcfactions.HC_FactionsPlugin.getInstance();
                    if (plugin == null) return false;
                    var claimManager = plugin.getClaimManager();
                    if (claimManager == null) return false;
                    return claimManager.isClaimed(worldName, chunkX, chunkZ);
                } catch (Exception e) {
                    return false;
                }
            };
        } catch (ClassNotFoundException e) {
            LOGGER.at(Level.INFO).log("HC_Factions not found, all chests treated as wilderness");
            return (worldName, chunkX, chunkZ) -> false;
        }
    }

    /**
     * Register with WildernessRegen to invalidate cache on chunk regeneration.
     */
    private void registerWildernessRegenListener() {
        try {
            Class<?> regenClass = Class.forName("com.wildernessregen.WildernessRegenPlugin");
            Object regenPlugin = regenClass.getMethod("getInstance").invoke(null);
            if (regenPlugin != null) {
                // Create a proxy for the RegenListener functional interface
                Class<?> listenerClass = Class.forName("com.wildernessregen.WildernessRegenPlugin$RegenListener");
                Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[]{ listenerClass },
                    (proxy, method, args) -> {
                        if ("onChunkRegenerated".equals(method.getName())) {
                            String worldName = (String) args[0];
                            int chunkX = (int) args[1];
                            int chunkZ = (int) args[2];
                            chestCache.invalidateChunk(worldName, chunkX, chunkZ);
                            chestRepository.deleteByChunk(worldName, chunkX, chunkZ);
                        }
                        return null;
                    }
                );
                regenClass.getMethod("addRegenListener", listenerClass).invoke(regenPlugin, listener);
                LOGGER.at(Level.INFO).log("Registered WildernessRegen listener for cache invalidation");
            }
        } catch (ClassNotFoundException e) {
            LOGGER.at(Level.INFO).log("HC_WildernessRegen not found, skipping regen listener");
        } catch (Exception e) {
            LOGGER.at(Level.WARNING).log("Failed to register WildernessRegen listener: " + e.getMessage());
        }
    }
}
