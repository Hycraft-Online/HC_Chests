package com.hcchests.systems;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockComponentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Applies HC_Leveling + HC_DropLists loot filtering for newly-generated wilderness chests.
 *
 * Flow:
 * 1) During ChunkPreLoadProcessEvent(newlyGenerated), collect container block indexes + original droplist IDs.
 * 2) When ItemContainerState refs are added for that chunk, filter rolled container contents once.
 *
 * This runs at generation/load time and does not intercept chest open interactions.
 */
public class WorldgenChestFilterSystem extends RefSystem<ChunkStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests-WorldgenFilter");

    private final ComponentType<ChunkStore, ItemContainerState> containerStateType;
    private final ChestOpenSystem.ClaimChecker claimChecker;
    private final Set<Dependency<ChunkStore>> dependencies;

    /**
     * key: "world:chunkX:chunkZ" -> (blockIndex -> metadata)
     */
    private final Map<String, Map<Integer, PendingChestMetadata>> pendingChunkChestMetadata = new ConcurrentHashMap<>();

    public WorldgenChestFilterSystem(ComponentType<ChunkStore, ItemContainerState> containerStateType,
                                     ChestOpenSystem.ClaimChecker claimChecker) {
        this.containerStateType = containerStateType;
        this.claimChecker = claimChecker;
        this.dependencies = Set.of(
                new SystemDependency<>(Order.AFTER, BlockStateModule.LegacyBlockStateRefSystem.class)
        );
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return containerStateType;
    }

    @Override
    public void onEntityAdded(@Nonnull Ref<ChunkStore> ref,
                              @Nonnull AddReason reason,
                              @Nonnull Store<ChunkStore> store,
                              @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        ItemContainerState containerState = store.getComponent(ref, containerStateType);
        if (containerState == null) return;

        WorldChunk chunk = containerState.getChunk();
        if (chunk == null || chunk.getWorld() == null) return;

        String chunkKey = chunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        Map<Integer, PendingChestMetadata> metadataByIndex = pendingChunkChestMetadata.get(chunkKey);
        if (metadataByIndex == null || metadataByIndex.isEmpty()) return;

        Integer blockIndex = resolveBlockIndex(containerState);
        if (blockIndex == null) return;

        PendingChestMetadata metadata;
        synchronized (metadataByIndex) {
            metadata = metadataByIndex.remove(blockIndex);
            if (metadataByIndex.isEmpty()) {
                pendingChunkChestMetadata.remove(chunkKey);
            }
        }
        if (metadata == null) return;

        filterNowOrOnFirstMutation(containerState, metadata);
    }

    @Override
    public void onEntityRemove(@Nonnull Ref<ChunkStore> ref,
                               @Nonnull RemoveReason reason,
                               @Nonnull Store<ChunkStore> store,
                               @Nonnull CommandBuffer<ChunkStore> commandBuffer) {
        // no-op
    }

    @Override
    @Nonnull
    public Set<Dependency<ChunkStore>> getDependencies() {
        return dependencies;
    }

    /**
     * Track chest metadata for newly generated wilderness chunks.
     * Called from plugin event registration.
     */
    public void onChunkPreLoadProcess(@Nonnull ChunkPreLoadProcessEvent event) {
        if (!event.isNewlyGenerated()) return;

        WorldChunk chunk = event.getChunk();
        if (chunk == null || chunk.getWorld() == null) return;

        String worldName = chunk.getWorld().getName();
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // Skip instance worlds — their loot is handled by the vanilla StashPlugin
        // from JSON drop lists, not by overworld level-gated generation.
        if (worldName.startsWith("instance-")) {
            return;
        }

        if (claimChecker.isClaimed(worldName, chunkX, chunkZ)) {
            return;
        }

        Holder<ChunkStore> holder = event.getHolder();
        if (holder == null) return;

        BlockComponentChunk blockComponentChunk = holder.getComponent(BlockComponentChunk.getComponentType());
        if (blockComponentChunk == null) return;

        Map<Integer, PendingChestMetadata> metadataByIndex = new ConcurrentHashMap<>();
        collectMetadataFromHolders(blockComponentChunk.getEntityHolders(), metadataByIndex);
        collectMetadataFromReferences(blockComponentChunk.getEntityReferences(), metadataByIndex);

        if (metadataByIndex.isEmpty()) {
            return;
        }

        String chunkKey = chunkKey(worldName, chunkX, chunkZ);
        pendingChunkChestMetadata.put(chunkKey, metadataByIndex);
    }

    private void collectMetadataFromHolders(Int2ObjectMap<Holder<ChunkStore>> holders,
                                            Map<Integer, PendingChestMetadata> out) {
        for (Int2ObjectMap.Entry<Holder<ChunkStore>> entry : holders.int2ObjectEntrySet()) {
            Holder<ChunkStore> holder = entry.getValue();
            if (holder == null) continue;

            BlockState state = BlockState.getBlockState(holder);
            if (!(state instanceof ItemContainerState containerState)) continue;

            out.put(entry.getIntKey(), new PendingChestMetadata(containerState.getDroplist()));
        }
    }

    private void collectMetadataFromReferences(Int2ObjectMap<Ref<ChunkStore>> refs,
                                               Map<Integer, PendingChestMetadata> out) {
        for (Int2ObjectMap.Entry<Ref<ChunkStore>> entry : refs.int2ObjectEntrySet()) {
            Ref<ChunkStore> ref = entry.getValue();
            if (ref == null || !ref.isValid()) continue;

            BlockState state = BlockState.getBlockState(ref, ref.getStore());
            if (!(state instanceof ItemContainerState containerState)) continue;

            out.put(entry.getIntKey(), new PendingChestMetadata(containerState.getDroplist()));
        }
    }

    private void filterNowOrOnFirstMutation(ItemContainerState containerState, PendingChestMetadata metadata) {
        ItemContainer container = containerState.getItemContainer();
        if (container == null) return;

        Vector3i blockPos = containerState.getBlockPosition();
        if (blockPos == null) return;

        String worldName = (containerState.getChunk() != null && containerState.getChunk().getWorld() != null)
                ? containerState.getChunk().getWorld().getName()
                : "unknown";

        int level = getLevelAtPosition(blockPos.x, blockPos.y, blockPos.z);
        String blockTypeId = containerState.getBlockType() != null ? containerState.getBlockType().getId() : null;
        String dropListId = resolveDropListId(metadata.originalDropListId, containerState, blockTypeId);

        AtomicBoolean filtered = new AtomicBoolean(false);
        Runnable runFilter = () -> {
            if (!filtered.compareAndSet(false, true)) {
                return;
            }
            FilterDecision decision = filterContainerToAllowedItems(dropListId, blockTypeId, level, container);
            if (!decision.ruleFound()) {
                LOGGER.at(Level.INFO).log(
                        "Worldgen chest filter skipped at %s %d,%d,%d (level=%d, requestedDropList=%s, reason=no level-gated entries)",
                        worldName, blockPos.x, blockPos.y, blockPos.z, level, dropListId
                );
                return;
            }

            LOGGER.at(Level.INFO).log(
                    "Worldgen chest filter applied at %s %d,%d,%d (level=%d, requestedDropList=%s, matchedDropList=%s, allowedItems=%d, removed=%d, before=%d, after=%d)",
                    worldName, blockPos.x, blockPos.y, blockPos.z, level, dropListId,
                    decision.matchedDropListId(), decision.allowedItemCount(),
                    decision.removed(), decision.before(), decision.after()
            );
        };

        // If stash has already rolled, filter now. Otherwise filter on first container mutation.
        if (hasAnyItems(container) || metadata.originalDropListId == null || metadata.originalDropListId.isBlank()) {
            runFilter.run();
            return;
        }

        container.registerChangeEvent(EventPriority.LAST, event -> runFilter.run());
        if (hasAnyItems(container)) {
            runFilter.run();
        }
    }

    private Integer resolveBlockIndex(ItemContainerState containerState) {
        Vector3i localPos = containerState.getPosition();
        if (localPos != null) {
            return ChunkUtil.indexBlockInColumn(localPos.x, localPos.y, localPos.z);
        }

        Vector3i worldPos = containerState.getBlockPosition();
        if (worldPos == null) return null;
        int localX = worldPos.x & 31;
        int localZ = worldPos.z & 31;
        return ChunkUtil.indexBlockInColumn(localX, worldPos.y, localZ);
    }

    private static String chunkKey(String worldName, int chunkX, int chunkZ) {
        return worldName + ":" + chunkX + ":" + chunkZ;
    }

    private boolean hasAnyItems(ItemContainer container) {
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private FilterDecision filterContainerToAllowedItems(String resolvedDropListId,
                                                         String blockTypeId,
                                                         int level,
                                                         ItemContainer container) {
        int before = countNonEmptySlots(container);
        AllowedItemLookup lookup = getAllowedItemIdsForLevel(resolvedDropListId, blockTypeId, level);
        Set<String> allowedItemIds = lookup.allowedItemIds();
        if (allowedItemIds.isEmpty()) {
            return new FilterDecision(0, before, before, lookup.matchedDropListId(), 0, false);
        }

        int removed = 0;
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) continue;
            if (allowedItemIds.contains(stack.getItemId())) continue;
            container.removeItemStackFromSlot(i);
            removed++;
        }
        int after = countNonEmptySlots(container);
        return new FilterDecision(removed, before, after, lookup.matchedDropListId(), allowedItemIds.size(), true);
    }

    private AllowedItemLookup getAllowedItemIdsForLevel(String resolvedDropListId, String blockTypeId, int level) {
        try {
            Class<?> apiClass = Class.forName("com.hcdroplists.api.HC_DropListsAPI");
            var isAvailable = apiClass.getMethod("isAvailable");
            if (!(Boolean) isAvailable.invoke(null)) return AllowedItemLookup.empty();

            var method = apiClass.getMethod("getAllowedItemIdsForLevel", String.class, int.class);
            for (String candidateId : buildDropListCandidates(resolvedDropListId, blockTypeId)) {
                Object raw = method.invoke(null, candidateId, level);
                if (!(raw instanceof Set<?> set) || set.isEmpty()) {
                    continue;
                }

                LinkedHashSet<String> result = new LinkedHashSet<>();
                for (Object item : set) {
                    if (item instanceof String s && !s.isBlank()) {
                        result.add(s);
                    }
                }
                if (!result.isEmpty()) {
                    return new AllowedItemLookup(candidateId, result);
                }
            }

            return AllowedItemLookup.empty();
        } catch (NoSuchMethodException ignored) {
            // Older HC_DropLists build - skip filtering safely.
            return AllowedItemLookup.empty();
        } catch (Exception e) {
            LOGGER.at(Level.FINE).log("Failed to query allowed item IDs from HC_DropLists API: %s", e.getMessage());
            return AllowedItemLookup.empty();
        }
    }

    private LinkedHashSet<String> buildDropListCandidates(String resolvedDropListId, String blockTypeId) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (resolvedDropListId != null && !resolvedDropListId.isBlank()) {
            candidates.add(resolvedDropListId);
        }
        if (blockTypeId != null && !blockTypeId.isBlank()) {
            candidates.add("Loot_" + blockTypeId);
        }
        candidates.add("Loot_Chest");
        return candidates;
    }

    private String resolveDropListId(String originalDropListId, ItemContainerState containerState, String blockTypeId) {
        if (originalDropListId != null && !originalDropListId.isBlank()) {
            return originalDropListId;
        }

        String currentDropListId = containerState.getDroplist();
        if (currentDropListId != null && !currentDropListId.isBlank()) {
            return currentDropListId;
        }

        if (blockTypeId != null && !blockTypeId.isBlank()) {
            return "Loot_" + blockTypeId;
        }
        return "Loot_Chest";
    }

    private int getLevelAtPosition(double x, double y, double z) {
        try {
            Class<?> apiClass = Class.forName("com.hcleveling.api.HC_LevelingAPI");
            var isAvailable = apiClass.getMethod("isAvailable");
            if (!(Boolean) isAvailable.invoke(null)) return 1;

            var method = apiClass.getMethod("getNPCLevelAtPosition", double.class, double.class, double.class);
            return (int) method.invoke(null, x, y, z);
        } catch (Exception e) {
            return 1;
        }
    }

    private int countNonEmptySlots(ItemContainer container) {
        int count = 0;
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private record AllowedItemLookup(String matchedDropListId, Set<String> allowedItemIds) {
        private static AllowedItemLookup empty() {
            return new AllowedItemLookup(null, Collections.emptySet());
        }
    }

    private record FilterDecision(int removed,
                                  int before,
                                  int after,
                                  String matchedDropListId,
                                  int allowedItemCount,
                                  boolean ruleFound) {
    }

    private record PendingChestMetadata(String originalDropListId) {
    }
}
