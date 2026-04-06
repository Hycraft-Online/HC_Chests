package com.hcchests.systems;

import com.hcchests.cache.CachedChest;
import com.hcchests.cache.ChestCache;
import com.hcchests.database.ChestRepository;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

/**
 * Intercepts UseBlockEvent.Pre on container blocks to apply level-aware filtering
 * for wilderness chests and DB-backed persistence for claimed-land chests.
 *
 * Flow:
 * 1. Cache hit → update cache with current container state, return
 * 2. Cache miss → determine wilderness/claimed, generate/load items, cache
 */
public class ChestOpenSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests");

    private final ChestCache cache;
    private final ChestRepository repository;
    private final ClaimChecker claimChecker;

    // Cached reflection handles for optional plugin APIs (resolved once at construction)
    private final Method levelingIsAvailable;
    private final Method levelingGetLevel;
    private final Method dropListsIsAvailable;
    private final Method dropListsGenerateDrops;
    private final Method dropListsGetAllowedItems;

    public ChestOpenSystem(ChestCache cache, ChestRepository repository, ClaimChecker claimChecker) {
        super(UseBlockEvent.Pre.class);
        this.cache = cache;
        this.repository = repository;
        this.claimChecker = claimChecker;

        // Resolve HC_Leveling API methods once
        Method lvlAvail = null, lvlGet = null;
        try {
            Class<?> apiClass = Class.forName("com.hcleveling.api.HC_LevelingAPI");
            lvlAvail = apiClass.getMethod("isAvailable");
            lvlGet = apiClass.getMethod("getNPCLevelAtPosition", double.class, double.class, double.class);
        } catch (Exception e) {
            LOGGER.at(Level.INFO).log("HC_Leveling API not available, chests will default to level 1");
        }
        this.levelingIsAvailable = lvlAvail;
        this.levelingGetLevel = lvlGet;

        // Resolve HC_DropLists API methods once
        Method dlAvail = null, dlGenerate = null, dlAllowed = null;
        try {
            Class<?> apiClass = Class.forName("com.hcdroplists.api.HC_DropListsAPI");
            dlAvail = apiClass.getMethod("isAvailable");
            dlGenerate = apiClass.getMethod("generateLeveledDrops", String.class, int.class);
            try {
                dlAllowed = apiClass.getMethod("getAllowedItemIdsForLevel", String.class, int.class);
            } catch (NoSuchMethodException ignored) {
                // Older HC_DropLists build without this method
            }
        } catch (Exception e) {
            LOGGER.at(Level.INFO).log("HC_DropLists API not available, chests will not generate drops");
        }
        this.dropListsIsAvailable = dlAvail;
        this.dropListsGenerateDrops = dlGenerate;
        this.dropListsGetAllowedItems = dlAllowed;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(new Query[]{Player.getComponentType()});
    }

    @Override
    public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                       CommandBuffer<EntityStore> commandBuffer, UseBlockEvent.Pre event) {

        InteractionContext context = event.getContext();
        if (context == null) return;

        Player player = (Player) store.getComponent(context.getEntity(), Player.getComponentType());
        if (player == null) return;

        World world = player.getWorld();
        if (world == null) return;

        Vector3i pos = event.getTargetBlock();
        if (pos == null) return;

        // Get the block state - check if it's a container
        BlockState blockState = world.getState(pos.x, pos.y, pos.z, true);
        if (!(blockState instanceof ItemContainerState containerState)) return;

        ItemContainer container = containerState.getItemContainer();
        if (container == null) return;

        String worldName = world.getName();
        int x = pos.x, y = pos.y, z = pos.z;

        // ── CACHE HIT ────────────────────────────────────────────────────
        CachedChest cached = cache.get(worldName, x, y, z);
        if (cached != null) {
            // Re-snapshot the live container state into cache (player may have changed items)
            snapshotContainerToCache(container, cached);
            return;
        }

        // ── CACHE MISS ───────────────────────────────────────────────────

        // Instance worlds: clear all chest contents to prevent contaminated loot.
        // Instance template region files may contain pre-baked items from previous
        // HC_Chests runs, and the overworld level formula is meaningless here.
        if (worldName.startsWith("instance-")) {
            clearContainer(container);
            LOGGER.at(Level.INFO).log("Cleared instance world chest at %s %d,%d,%d",
                    worldName, x, y, z);
            return;
        }

        int chunkX = x >> 5;
        int chunkZ = z >> 5;
        boolean claimed = claimChecker.isClaimed(worldName, chunkX, chunkZ);

        if (claimed) {
            handleClaimedChest(worldName, x, y, z, container);
        } else {
            handleWildernessChest(worldName, x, y, z, containerState, container, event.getBlockType().getId());
        }
    }

    /**
     * Wilderness chest:
     * - restore persisted state if present
     * - otherwise apply level filtering to existing contents
     * - if empty, generate level-gated drops from DB-managed droplists
     */
    private void handleWildernessChest(String worldName, int x, int y, int z,
                                        ItemContainerState containerState,
                                        ItemContainer container,
                                        String blockTypeId) {
        // Check DB first (server may have restarted)
        CachedChest dbChest = repository.loadByPosition(worldName, x, y, z);
        if (dbChest != null) {
            // Restore from DB
            applyItemsToContainer(container, dbChest.getItems(container.getCapacity()));
            cache.put(dbChest);
            LOGGER.at(Level.FINE).log("Restored wilderness chest from DB at %d,%d,%d", x, y, z);
            return;
        }

        // Calculate level at position
        int level = getLevelAtPosition(x, y, z);
        String resolvedDropListId = resolveDropListId(containerState, blockTypeId);

        CachedChest cached = new CachedChest(worldName, x, y, z);
        cached.setChestType("wilderness");
        cached.setGeneratedLevel(level);

        if (hasAnyItems(container)) {
            int removed = filterContainerToAllowedItems(resolvedDropListId, level, container);
            if (removed > 0) {
                LOGGER.at(Level.INFO).log(
                        "Filtered %d high-level items from chest at %d,%d,%d (level %d, droplist=%s)",
                        removed, x, y, z, level, resolvedDropListId
                );
            }
        } else {
            // Empty container: generate level-gated drops.
            List<ItemStack> drops = generateLeveledDrops(resolvedDropListId, blockTypeId, level);
            if (drops != null && !drops.isEmpty()) {
                populateContainer(container, drops);
                LOGGER.at(Level.INFO).log(
                        "Generated %d level-gated items for chest at %d,%d,%d (level %d, droplist=%s)",
                        drops.size(), x, y, z, level, resolvedDropListId
                );
            } else {
                LOGGER.at(Level.FINE).log(
                        "No level-gated drops configured for chest at %d,%d,%d (level %d, droplist=%s, block=%s)",
                        x, y, z, level, resolvedDropListId, blockTypeId
                );
            }
        }

        // Snapshot whatever is in the container now and cache it
        snapshotContainerToCache(container, cached);
        cached.setDirty(true);
        cache.put(cached);
    }

    /**
     * Claimed land chest: load from DB or snapshot current contents for persistence.
     */
    private void handleClaimedChest(String worldName, int x, int y, int z, ItemContainer container) {
        // Check DB for persisted contents
        CachedChest dbChest = repository.loadByPosition(worldName, x, y, z);
        if (dbChest != null) {
            // Restore from DB
            applyItemsToContainer(container, dbChest.getItems(container.getCapacity()));
            cache.put(dbChest);
            LOGGER.at(Level.FINE).log("Restored player chest from DB at %d,%d,%d", x, y, z);
            return;
        }

        // First time seeing this chest on claimed land - snapshot current contents
        CachedChest cached = new CachedChest(worldName, x, y, z);
        cached.setChestType("player");
        snapshotContainerToCache(container, cached);
        cached.setDirty(true);
        cache.put(cached);
        LOGGER.at(Level.FINE).log("Cached new player chest at %d,%d,%d", x, y, z);
    }

    // ── Helper Methods ───────────────────────────────────────────────

    private void snapshotContainerToCache(ItemContainer container, CachedChest cached) {
        short capacity = container.getCapacity();
        ItemStack[] items = new ItemStack[capacity];
        container.forEach((slot, stack) -> {
            items[slot] = stack;
        });
        cached.updateItems(items);
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

    private void clearContainer(ItemContainer container) {
        short capacity = container.getCapacity();
        for (short i = 0; i < capacity; i++) {
            if (container.getItemStack(i) != null) {
                container.removeItemStackFromSlot(i);
            }
        }
    }

    private void populateContainer(ItemContainer container, List<ItemStack> items) {
        short capacity = container.getCapacity();
        if (items.size() <= capacity) {
            // Scatter items into random slots (same pattern as StashSystem)
            short[] slots = new short[capacity];
            for (short i = 0; i < capacity; i++) slots[i] = i;
            // Fisher-Yates shuffle
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (int i = capacity - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                short tmp = slots[i];
                slots[i] = slots[j];
                slots[j] = tmp;
            }
            for (int i = 0; i < items.size() && i < capacity; i++) {
                container.addItemStackToSlot(slots[i], items.get(i));
            }
        } else {
            // More items than slots - just fill sequentially
            for (int i = 0; i < capacity && i < items.size(); i++) {
                container.addItemStackToSlot((short) i, items.get(i));
            }
        }
    }

    private void applyItemsToContainer(ItemContainer container, ItemStack[] items) {
        clearContainer(container);
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null && !items[i].isEmpty()) {
                container.addItemStackToSlot((short) i, items[i]);
            }
        }
    }

    /**
     * Get level at position using HC_Leveling API (cached reflection to avoid hard dep).
     */
    private int getLevelAtPosition(double x, double y, double z) {
        if (levelingIsAvailable == null || levelingGetLevel == null) return 1;
        try {
            if (!(Boolean) levelingIsAvailable.invoke(null)) return 1;
            return (int) levelingGetLevel.invoke(null, x, y, z);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Generate level-gated drops using HC_DropLists API (cached reflection to avoid hard dep).
     */
    @SuppressWarnings("unchecked")
    private List<ItemStack> generateLeveledDrops(String resolvedDropListId, String blockTypeId, int level) {
        if (dropListsIsAvailable == null || dropListsGenerateDrops == null) return null;
        try {
            if (!(Boolean) dropListsIsAvailable.invoke(null)) return null;

            for (String candidateId : buildDropListCandidates(resolvedDropListId, blockTypeId)) {
                List<ItemStack> result = (List<ItemStack>) dropListsGenerateDrops.invoke(null, candidateId, level);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            }

            return null;
        } catch (Exception e) {
            LOGGER.at(Level.FINE).log("HC_DropLists API not available: " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> getAllowedItemIdsForLevel(String resolvedDropListId, int level) {
        if (dropListsIsAvailable == null || dropListsGetAllowedItems == null) return Collections.emptySet();
        try {
            if (!(Boolean) dropListsIsAvailable.invoke(null)) return Collections.emptySet();

            Object raw = dropListsGetAllowedItems.invoke(null, resolvedDropListId, level);
            if (!(raw instanceof Set<?> set)) {
                return Collections.emptySet();
            }

            LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object item : set) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s);
                }
            }
            return result;
        } catch (Exception e) {
            LOGGER.at(Level.FINE).log("Failed to query allowed item IDs from HC_DropLists API: %s", e.getMessage());
            return Collections.emptySet();
        }
    }

    private int filterContainerToAllowedItems(String resolvedDropListId, int level, ItemContainer container) {
        if (resolvedDropListId == null || resolvedDropListId.isBlank()) {
            return 0;
        }

        Set<String> allowedItemIds = getAllowedItemIdsForLevel(resolvedDropListId, level);
        if (allowedItemIds.isEmpty()) {
            // No level-gated rule set for this list at this level.
            return 0;
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
        return removed;
    }

    private String resolveDropListId(ItemContainerState containerState, String blockTypeId) {
        String explicit = containerState.getDroplist();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (blockTypeId != null && !blockTypeId.isBlank()) {
            return "Loot_" + blockTypeId;
        }
        return "Loot_Chest";
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

    /**
     * Functional interface for checking if a chunk is claimed.
     */
    @FunctionalInterface
    public interface ClaimChecker {
        boolean isClaimed(String worldName, int chunkX, int chunkZ);
    }
}
