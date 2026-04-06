package com.hcchests.config;

import com.hccore.api.HC_CoreAPI;
import com.hccore.models.SettingDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plugin configuration for HC_Chests.
 * Delegates to HC_CoreAPI for hot-reloadable settings.
 */
public class ChestConfig {

    private static final String P = "HC_Chests";

    /**
     * Registers all default settings with HC_Core.
     * Call once during plugin setup.
     */
    public static void registerDefaults() {
        Map<String, SettingDef> defaults = new LinkedHashMap<>();
        defaults.put("cache.flushIntervalSeconds", new SettingDef("15", "INT", "How often dirty cache entries are flushed to DB (seconds)"));
        defaults.put("cache.evictionAgeMs", new SettingDef("300000", "INT", "Cache entries not accessed in this many ms are evicted (5 min)"));
        defaults.put("cache.evictionIntervalSeconds", new SettingDef("30", "INT", "How often eviction runs (seconds)"));
        HC_CoreAPI.registerDefaults(P, defaults);
    }

    public static int getFlushIntervalSeconds() {
        return HC_CoreAPI.getSettingInt(P, "cache.flushIntervalSeconds", 15);
    }

    public static long getEvictionAgeMs() {
        return HC_CoreAPI.getSettingInt(P, "cache.evictionAgeMs", 300000);
    }

    public static int getEvictionIntervalSeconds() {
        return HC_CoreAPI.getSettingInt(P, "cache.evictionIntervalSeconds", 30);
    }

    private ChestConfig() {}
}
