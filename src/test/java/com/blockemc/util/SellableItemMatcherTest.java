package com.blockemc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class SellableItemMatcherTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void plainSameMaterialIsSellable() {
        assertTrue(SellableItemMatcher.isPlainSellable(new ItemStack(Material.STONE), Material.STONE));
    }

    @Test
    void displayNameBlocksSale() {
        ItemStack stack = new ItemStack(Material.STONE);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName("Quest Stone");
        stack.setItemMeta(meta);
        assertFalse(SellableItemMatcher.isPlainSellable(stack, Material.STONE));
    }

    @Test
    void loreBlocksSale() {
        ItemStack stack = new ItemStack(Material.STONE);
        ItemMeta meta = stack.getItemMeta();
        meta.setLore(java.util.List.of("special"));
        stack.setItemMeta(meta);
        assertFalse(SellableItemMatcher.isPlainSellable(stack, Material.STONE));
    }

    @Test
    void enchantBlocksSale() {
        ItemStack stack = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = stack.getItemMeta();
        meta.addEnchant(Enchantment.EFFICIENCY, 1, true);
        stack.setItemMeta(meta);
        assertFalse(SellableItemMatcher.isPlainSellable(stack, Material.DIAMOND_PICKAXE));
    }

    @Test
    void persistentDataBlocksSale() {
        ItemStack stack = new ItemStack(Material.STONE);
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(NamespacedKey.minecraft("blockemc_test"), PersistentDataType.STRING, "x");
        stack.setItemMeta(meta);
        assertFalse(SellableItemMatcher.isPlainSellable(stack, Material.STONE));
    }

    @Test
    void countAndRemoveUseSameStrictMatcher() {
        Inventory inventory = Bukkit.createInventory(null, 9);
        inventory.setItem(0, new ItemStack(Material.STONE, 16));
        ItemStack named = new ItemStack(Material.STONE, 16);
        ItemMeta meta = named.getItemMeta();
        meta.setDisplayName("Keep");
        named.setItemMeta(meta);
        inventory.setItem(1, named);

        assertEquals(16, ItemStackUtil.countMatching(inventory, Material.STONE, true, false));
        assertTrue(ItemStackUtil.removeMatching(inventory, Material.STONE, 16, true, false));
        assertEquals(0, ItemStackUtil.countMatching(inventory, Material.STONE, true, false));
        assertEquals(16, inventory.getItem(1).getAmount());
    }

    @Test
    void nonItemMaterialNeverMatchesOrFits() {
        Inventory inventory = Bukkit.createInventory(null, 9);

        assertFalse(Material.CAVE_VINES.isItem());
        assertEquals(0, ItemStackUtil.maxFit(inventory, Material.CAVE_VINES, 64));
        assertEquals(0, ItemStackUtil.countMatching(inventory, Material.CAVE_VINES, true, false));
        assertFalse(ItemStackUtil.removeMatching(inventory, Material.CAVE_VINES, 1, true, false));
        assertFalse(SellableItemMatcher.isPlainSellable(new ItemStack(Material.STONE), Material.CAVE_VINES));
    }
}
