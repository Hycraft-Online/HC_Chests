package com.hcchests.database;

import com.hypixel.hytale.server.core.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight serializer for ItemStack arrays to/from JSON strings.
 * Format: [{"slot":0,"itemId":"Stone","qty":5}, ...]
 *
 * We hand-roll JSON to avoid pulling in Gson/Jackson as a dependency,
 * since the format is simple and fixed.
 */
public class ItemStackSerializer {

    private static final Pattern ENTRY_PATTERN = Pattern.compile(
        "\\{\"slot\":(\\d+),\"itemId\":\"([^\"]+)\",\"qty\":(\\d+)(?:,\"dur\":([\\d.]+),\"maxDur\":([\\d.]+))?\\}"
    );

    private ItemStackSerializer() {}

    /**
     * Serialize a container's items to a JSON array string.
     * Only non-null slots are serialized.
     *
     * @param items array of ItemStacks (may contain nulls)
     * @return JSON string like [{"slot":0,"itemId":"Stone","qty":5}]
     */
    public static String toJson(ItemStack[] items) {
        if (items == null || items.length == 0) return "[]";

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        for (int i = 0; i < items.length; i++) {
            ItemStack stack = items[i];
            if (stack == null || stack.isEmpty()) continue;

            if (!first) sb.append(",");
            first = false;

            sb.append("{\"slot\":").append(i);
            sb.append(",\"itemId\":\"").append(escapeJson(stack.getItemId())).append("\"");
            sb.append(",\"qty\":").append(stack.getQuantity());

            double durability = stack.getDurability();
            if (durability > 0) {
                sb.append(",\"dur\":").append(durability);
                sb.append(",\"maxDur\":").append(stack.getMaxDurability());
            }

            sb.append("}");
        }

        sb.append("]");
        return sb.toString();
    }

    /**
     * Deserialize a JSON array string back into an ItemStack array.
     *
     * @param json the JSON string
     * @param capacity the container capacity (array size)
     * @return array of ItemStacks (with nulls for empty slots)
     */
    public static ItemStack[] fromJson(String json, int capacity) {
        ItemStack[] items = new ItemStack[capacity];
        if (json == null || json.equals("[]") || json.isBlank()) return items;

        // Simple regex-based parsing for our known fixed format
        Matcher matcher = ENTRY_PATTERN.matcher(json);

        while (matcher.find()) {
            try {
                int slot = Integer.parseInt(matcher.group(1));
                String itemId = unescapeJson(matcher.group(2));
                int qty = Integer.parseInt(matcher.group(3));

                if (slot < 0 || slot >= capacity) continue;

                if (matcher.group(4) != null) {
                    double dur = Double.parseDouble(matcher.group(4));
                    double maxDur = Double.parseDouble(matcher.group(5));
                    items[slot] = new ItemStack(itemId, qty, dur, maxDur, null);
                } else {
                    items[slot] = new ItemStack(itemId, qty);
                }
            } catch (NumberFormatException e) {
                // Skip malformed entry rather than failing entire deserialization
                continue;
            }
        }

        return items;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
