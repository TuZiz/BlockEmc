package com.blockemc.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemStackUtil {

    private ItemStackUtil() {
    }

    public static Material safeMaterial(String value, Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim());
        return material == null ? fallback : material;
    }

    public static ItemStack createItem(Material material, String name, List<String> lore) {
        Material target = Objects.requireNonNullElse(material, Material.STONE);
        if (!target.isItem()) {
            target = Material.BARRIER;
        }
        ItemStack stack = new ItemStack(target);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (name != null && !name.isBlank()) {
                meta.setDisplayName(ColorUtil.color(name));
            }
            if (lore != null && !lore.isEmpty()) {
                meta.setLore(ColorUtil.color(lore));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static String prettifyMaterial(Material material) {
        String[] parts = material.name().toLowerCase(Locale.ROOT).split("_");
        List<String> words = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            words.add(Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return String.join(" ", words);
    }

    public static boolean canFit(Inventory inventory, ItemStack stack) {
        return maxFit(inventory, stack.getType(), stack.getAmount()) >= stack.getAmount();
    }

    public static int maxFit(Inventory inventory, Material material, int cap) {
        int remaining = Math.max(0, cap);
        int maxStack = material.getMaxStackSize();
        for (ItemStack content : inventory.getStorageContents()) {
            if (remaining <= 0) {
                break;
            }
            if (content == null || content.getType() == Material.AIR) {
                remaining -= maxStack;
                continue;
            }
            if (content.getType() != material) {
                continue;
            }
            remaining -= Math.max(0, maxStack - content.getAmount());
        }
        return Math.max(0, cap - Math.max(remaining, 0));
    }

    public static int countMatching(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.getType() != material) {
                continue;
            }
            total += content.getAmount();
        }
        return total;
    }

    public static boolean removeMatching(Inventory inventory, Material material, int amount) {
        if (countMatching(inventory, material) < amount) {
            return false;
        }
        int remaining = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack content = contents[i];
            if (content == null || content.getType() != material) {
                continue;
            }
            int removed = Math.min(content.getAmount(), remaining);
            content.setAmount(content.getAmount() - removed);
            remaining -= removed;
            if (content.getAmount() <= 0) {
                contents[i] = null;
            }
            if (remaining <= 0) {
                break;
            }
        }
        inventory.setStorageContents(contents);
        return true;
    }

    public static void giveOrDrop(Player player, ItemStack stack) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
        if (leftovers.isEmpty()) {
            return;
        }
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }
}
