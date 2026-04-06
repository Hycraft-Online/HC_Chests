package com.hcchests.commands;

import com.hcchests.cache.CachedChest;
import com.hcchests.database.ChestRepository;
import com.hcchests.systems.ChestOpenSystem;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Admin command to scan all claimed chunks and import chest inventories into the database.
 * Usage: /chestscan [world]
 *
 * Queries fg_claims for all claimed chunk coordinates, loads each chunk via async chunk loading,
 * then scans BlockComponentChunk maps for ItemContainerState blocks (chests, barrels, etc.).
 * This means it works even if chunks are not currently loaded by nearby players.
 */
public class ChestScanCommand extends AbstractPlayerCommand {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests-Scan");

    private final ChestRepository repository;
    private final ChestOpenSystem.ClaimChecker claimChecker;

    private final OptionalArg<World> worldArg = this.withOptionalArg("world", "Target world to scan", ArgTypes.WORLD);

    public ChestScanCommand(ChestRepository repository, ChestOpenSystem.ClaimChecker claimChecker) {
        super("chestscan", "Scan all claimed chunks and import chest inventories to database");
        this.repository = repository;
        this.claimChecker = claimChecker;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Store<EntityStore> store,
                           @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef,
                           @Nonnull World playerWorld) {

        World argWorld = worldArg.get(ctx);
        World targetWorld = argWorld != null ? argWorld : playerWorld;
        String worldName = targetWorld.getName();
        LOGGER.at(Level.INFO).log("ChestScan: world name = '%s'", worldName);

        // Query claimed chunks from database (runs off world thread, which is fine for AbstractPlayerCommand)
        List<int[]> claimedChunks = repository.getClaimedChunks(worldName);
        LOGGER.at(Level.INFO).log("ChestScan: found %d claimed chunks", claimedChunks.size());
        if (claimedChunks.isEmpty()) {
            playerRef.sendMessage(Message.raw("No claimed chunks found in world: " + worldName).color("#FF5555"));
            return;
        }

        playerRef.sendMessage(Message.raw(
            "Scanning " + claimedChunks.size() + " claimed chunks in " + worldName + "..."
        ).color("#FFAA00"));

        // Process chunks in batches on the world thread, loading each via async
        ChunkStore chunkStore = targetWorld.getChunkStore();
        AtomicInteger scannedChunks = new AtomicInteger(0);
        AtomicInteger foundChests = new AtomicInteger(0);
        int totalClaimed = claimedChunks.size();

        // Chain async chunk loads sequentially to avoid overwhelming the server
        // Process in batches of 10 concurrent loads
        int batchSize = 10;
        processBatch(claimedChunks, 0, batchSize, chunkStore, targetWorld, worldName,
                     scannedChunks, foundChests, totalClaimed, playerRef);
    }

    /**
     * Process a batch of claimed chunks by loading them async, then scanning for chests.
     * Chains to the next batch when done.
     */
    private void processBatch(List<int[]> claimedChunks, int startIdx, int batchSize,
                              ChunkStore chunkStore, World world, String worldName,
                              AtomicInteger scannedChunks, AtomicInteger foundChests,
                              int totalClaimed, PlayerRef playerRef) {

        int endIdx = Math.min(startIdx + batchSize, claimedChunks.size());
        if (startIdx >= claimedChunks.size()) {
            // All done — report results
            HytaleServer.SCHEDULED_EXECUTOR.execute(() -> {
                playerRef.sendMessage(Message.raw(
                    "Chest scan complete: " + foundChests.get() + " chests found across "
                    + scannedChunks.get() + "/" + totalClaimed + " claimed chunks in " + worldName
                ).color("#55FF55"));
            });
            return;
        }

        // Launch async loads for this batch
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = startIdx; i < endIdx; i++) {
            int[] coords = claimedChunks.get(i);
            int chunkX = coords[0];
            int chunkZ = coords[1];
            long chunkIndex = ChunkUtil.indexChunk(chunkX, chunkZ);

            CompletableFuture<Void> future = chunkStore.getChunkReferenceAsync(chunkIndex)
                .thenAcceptAsync(chunkRef -> {
                    if (chunkRef == null || !chunkRef.isValid()) {
                        LOGGER.at(Level.FINE).log("ChestScan: chunk %d,%d ref null/invalid", chunkX, chunkZ);
                        scannedChunks.incrementAndGet();
                        return;
                    }

                    WorldChunk worldChunk = chunkStore.getStore().getComponent(chunkRef, WorldChunk.getComponentType());
                    if (worldChunk == null) {
                        LOGGER.at(Level.FINE).log("ChestScan: chunk %d,%d WorldChunk null", chunkX, chunkZ);
                        scannedChunks.incrementAndGet();
                        return;
                    }

                    int chestsInChunk = scanChunk(worldChunk, worldName, chunkX, chunkZ);
                    foundChests.addAndGet(chestsInChunk);
                    scannedChunks.incrementAndGet();
                }, world)
                .exceptionally(ex -> {
                    LOGGER.at(Level.WARNING).log("ChestScan: exception loading chunk %d,%d: %s", chunkX, chunkZ, ex.getMessage());
                    scannedChunks.incrementAndGet();
                    return null;
                });

            futures.add(future);
        }

        // When all futures in this batch complete, process next batch
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .whenCompleteAsync((v, err) -> {
                if (err != null) {
                    HytaleServer.SCHEDULED_EXECUTOR.execute(() -> {
                        playerRef.sendMessage(Message.raw(
                            "Warning: batch error during scan: " + err.getMessage()
                        ).color("#FF5555"));
                    });
                }

                // Progress update every 50 chunks
                int done = scannedChunks.get();
                if (done > 0 && done % 50 == 0) {
                    HytaleServer.SCHEDULED_EXECUTOR.execute(() -> {
                        playerRef.sendMessage(Message.raw(
                            "Progress: " + done + "/" + totalClaimed + " chunks scanned, "
                            + foundChests.get() + " chests found..."
                        ).color("#AAAAAA"));
                    });
                }

                // Next batch
                processBatch(claimedChunks, endIdx, batchSize, chunkStore, world, worldName,
                             scannedChunks, foundChests, totalClaimed, playerRef);
            }, world);
    }

    /**
     * Scan a single loaded chunk for container blocks and upsert to DB.
     * Must be called on the world thread.
     */
    private int scanChunk(WorldChunk worldChunk, String worldName, int chunkX, int chunkZ) {
        BlockComponentChunk blockCompChunk = worldChunk.getBlockComponentChunk();
        if (blockCompChunk == null) {
            LOGGER.at(Level.FINE).log("ChestScan: chunk %d,%d has null BlockComponentChunk", chunkX, chunkZ);
            return 0;
        }

        List<CachedChest> batch = new ArrayList<>();

        // Check entityReferences (ticking chunks — blocks already in store)
        Int2ObjectMap<Ref<ChunkStore>> refs = blockCompChunk.getEntityReferences();
        LOGGER.at(Level.FINE).log("ChestScan: chunk %d,%d has %d entityRefs, %d entityHolders",
            chunkX, chunkZ, refs.size(), blockCompChunk.getEntityHolders().size());
        for (Int2ObjectMap.Entry<Ref<ChunkStore>> entry : refs.int2ObjectEntrySet()) {
            Ref<ChunkStore> blockRef = entry.getValue();
            if (blockRef == null || !blockRef.isValid()) continue;

            BlockState state = BlockState.getBlockState(blockRef, blockRef.getStore());
            if (state instanceof ItemContainerState containerState) {
                CachedChest chest = snapshotChest(containerState, worldName, chunkX, chunkZ, entry.getIntKey());
                if (chest != null) batch.add(chest);
            }
        }

        // Check entityHolders (non-ticking chunks — blocks not yet in store)
        Int2ObjectMap<Holder<ChunkStore>> holders = blockCompChunk.getEntityHolders();
        for (Int2ObjectMap.Entry<Holder<ChunkStore>> entry : holders.int2ObjectEntrySet()) {
            Holder<ChunkStore> holder = entry.getValue();
            if (holder == null) continue;

            BlockState state = BlockState.getBlockState(holder);
            if (state instanceof ItemContainerState containerState) {
                CachedChest chest = snapshotChest(containerState, worldName, chunkX, chunkZ, entry.getIntKey());
                if (chest != null) batch.add(chest);
            }
        }

        if (!batch.isEmpty()) {
            LOGGER.at(Level.INFO).log("ChestScan: upserting %d chests from chunk %d,%d", batch.size(), chunkX, chunkZ);
            repository.batchUpsert(batch);
        }

        return batch.size();
    }

    /**
     * Snapshot a container block into a CachedChest for DB insertion.
     */
    private CachedChest snapshotChest(ItemContainerState containerState, String worldName,
                                       int chunkX, int chunkZ, int blockIndex) {
        ItemContainer container = containerState.getItemContainer();
        if (container == null) return null;

        int localX = blockIndex & 0x1F;
        int localZ = (blockIndex >> 5) & 0x1F;
        int y = ChunkUtil.yFromBlockInColumn(blockIndex);
        int worldX = (chunkX << 5) + localX;
        int worldZ = (chunkZ << 5) + localZ;

        short capacity = container.getCapacity();
        ItemStack[] items = new ItemStack[capacity];
        container.forEach((slot, stack) -> {
            items[slot] = stack;
        });

        // All chunks we scan are claimed (we only query fg_claims)
        CachedChest chest = new CachedChest(worldName, worldX, y, worldZ);
        chest.setChestType("player");
        chest.updateItems(items);
        chest.setDirty(true);

        return chest;
    }
}
