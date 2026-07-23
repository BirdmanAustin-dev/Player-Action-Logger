package com.playerlogger;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class LogUtils {
    private LogUtils() {}

    public static String formatItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return "AIR";
        StringBuilder sb = new StringBuilder();
        sb.append(item.getAmount()).append("x").append(item.getType());
        if (item.hasItemMeta() && item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            sb.append(" '").append(cleanOneLine(item.getItemMeta().getDisplayName())).append("'");
        }
        if (!item.getEnchantments().isEmpty()) {
            sb.append(" +").append(item.getEnchantments().size()).append(" ench");
        }
        return sb.toString();
    }

    public static String loc(org.bukkit.Location l) {
        if (l == null || l.getWorld() == null) return "unknown(?,?,?)";
        return String.format("%s(%d,%d,%d)", l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static String loc(org.bukkit.block.Block b) {
        if (b == null) return "unknown(?,?,?)";
        return String.format("%s(%d,%d,%d)", b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public static String cleanOneLine(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public static boolean isWaterOrLava(Material m) {
        return m == Material.WATER || m == Material.LAVA;
    }

    public static boolean isInteractive(Material m) {
        String name = m.name();
        return name.contains("DOOR") || name.contains("GATE") || name.contains("BUTTON") || name.contains("LEVER")
                || name.contains("CHEST") || name.contains("BARREL") || name.contains("SHULKER")
                || name.contains("FURNACE") || name.contains("HOPPER") || name.contains("DISPENSER")
                || name.contains("DROPPER") || name.contains("BEACON") || name.contains("BREWING")
                || name.contains("ANVIL") || name.contains("ENCHANT") || name.contains("BED")
                || name.contains("CAKE") || m == Material.REPEATER || m == Material.COMPARATOR
                || m == Material.DAYLIGHT_DETECTOR;
    }
}
