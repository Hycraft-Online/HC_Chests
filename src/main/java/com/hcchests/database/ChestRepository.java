package com.hcchests.database;

import com.hcchests.cache.CachedChest;
import com.hypixel.hytale.logger.HytaleLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class ChestRepository {

    private static final HytaleLogger LOGGER = HytaleLogger.getLogger().getSubLogger("HC_Chests-DB");

    private final DatabaseManager databaseManager;

    public ChestRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    /**
     * Load a single chest state by position. Returns null if not found.
     */
    public CachedChest loadByPosition(String worldName, int x, int y, int z) {
        String sql = "SELECT chest_type, items, generated_level FROM chest_states WHERE world_name = ? AND x = ? AND y = ? AND z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            stmt.setInt(2, x);
            stmt.setInt(3, y);
            stmt.setInt(4, z);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    CachedChest chest = new CachedChest(worldName, x, y, z);
                    chest.setChestType(rs.getString("chest_type"));
                    chest.setItemsJson(rs.getString("items"));
                    chest.setGeneratedLevel(rs.getInt("generated_level"));
                    chest.setDirty(false);
                    return chest;
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to load chest at " + worldName + ":" + x + "," + y + "," + z + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Batch upsert dirty cache entries to DB.
     *
     * @return true if the DB write succeeded, false on failure (entries should NOT be marked clean)
     */
    public boolean batchUpsert(List<CachedChest> entries) {
        if (entries.isEmpty()) return true;

        String sql = """
            INSERT INTO chest_states (world_name, x, y, z, chunk_x, chunk_z, chest_type, items, generated_level, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, NOW())
            ON CONFLICT (world_name, x, y, z)
            DO UPDATE SET chest_type = EXCLUDED.chest_type,
                          items = EXCLUDED.items,
                          generated_level = EXCLUDED.generated_level,
                          updated_at = NOW()
        """;

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            try {
                for (CachedChest chest : entries) {
                    stmt.setString(1, chest.getWorldName());
                    stmt.setInt(2, chest.getX());
                    stmt.setInt(3, chest.getY());
                    stmt.setInt(4, chest.getZ());
                    stmt.setInt(5, chest.getChunkX());
                    stmt.setInt(6, chest.getChunkZ());
                    stmt.setString(7, chest.getChestType());
                    stmt.setString(8, chest.getItemsJson());
                    stmt.setInt(9, chest.getGeneratedLevel());
                    stmt.addBatch();
                }

                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }

            LOGGER.at(Level.FINE).log("Flushed " + entries.size() + " chest states to database");
            return true;
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to batch upsert chest states: " + e.getMessage());
            return false;
        }
    }

    /**
     * Query all distinct claimed chunk coordinates for a given world from fg_claims.
     * Returns a list of int[2] arrays: {chunk_x, chunk_z}.
     */
    public List<int[]> getClaimedChunks(String worldName) {
        String sql = "SELECT DISTINCT chunk_x, chunk_z FROM fg_claims WHERE world = ?";
        List<int[]> chunks = new ArrayList<>();

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    chunks.add(new int[]{rs.getInt("chunk_x"), rs.getInt("chunk_z")});
                }
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to query claimed chunks for %s: %s", worldName, e.getMessage());
        }
        return chunks;
    }

    /**
     * Delete all chest states in a chunk (used when WildernessRegen regenerates a chunk).
     */
    public void deleteByChunk(String worldName, int chunkX, int chunkZ) {
        String sql = "DELETE FROM chest_states WHERE world_name = ? AND chunk_x = ? AND chunk_z = ?";

        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, worldName);
            stmt.setInt(2, chunkX);
            stmt.setInt(3, chunkZ);
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                LOGGER.at(Level.FINE).log("Deleted " + deleted + " chest states in chunk " + chunkX + "," + chunkZ);
            }
        } catch (SQLException e) {
            LOGGER.at(Level.WARNING).log("Failed to delete chunk chest states: " + e.getMessage());
        }
    }
}
