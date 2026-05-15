package com.blockemc.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

public final class SellableItemMatcher {

    private SellableItemMatcher() {
    }

    public static boolean isPlainSellable(ItemStack stack, Material expectedType) {
        return isPlainSellable(stack, expectedType, true, false);
    }

    public static boolean isPlainSellable(
            ItemStack stack,
            Material expectedType,
            boolean strictItemMatch,
            boolean sellCustomItems
    ) {
        if (stack == null || expectedType == null || expectedType == Material.AIR) {
            return false;
        }
        if (stack.getType() != expectedType || stack.getType() == Material.AIR) {
            return false;
        }
        if (sellCustomItems || !strictItemMatch) {
            return true;
        }
        if (!stack.hasItemMeta()) {
            return true;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelData()) {
            return false;
        }
        if (!meta.getPersistentDataContainer().isEmpty()) {
            return false;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage() && damageable.getDamage() > 0) {
            return false;
        }
        return !hasExtraItemMeta(stack, meta);
    }

    private static boolean hasExtraItemMeta(ItemStack stack, ItemMeta meta) {
        ItemStack plain = new ItemStack(stack.getType(), stack.getAmount());
        ItemMeta plainMeta = plain.getItemMeta();
        return plainMeta != null && !meta.equals(plainMeta);
    }
}
