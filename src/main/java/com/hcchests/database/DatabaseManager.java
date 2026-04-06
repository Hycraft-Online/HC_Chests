package com.hcchests.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private final HikariDataSource dataSource;

    public DatabaseManager(String url, String username, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setDriverClassName("org.postgresql.Driver");
        config.setPoolName("HC_Chests-Pool");
        config.setConnectionTimeout(5000);
        this.dataSource = new HikariDataSource(config);
        createTables();
    }

    private void createTables() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS chest_states (
                    id SERIAL PRIMARY KEY,
                    world_name VARCHAR(64) NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    chunk_x INTEGER NOT NULL,
                    chunk_z INTEGER NOT NULL,
                    chest_type VARCHAR(16) NOT NULL DEFAULT 'wilderness',
                    items JSONB NOT NULL DEFAULT '[]'::jsonb,
                    generated_level INTEGER,
                    created_at TIMESTAMP DEFAULT NOW(),
                    updated_at TIMESTAMP DEFAULT NOW(),
                    UNIQUE(world_name, x, y, z)
                )
            """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_chest_states_chunk
                    ON chest_states(world_name, chunk_x, chunk_z)
            """);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create HC_Chests tables", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
