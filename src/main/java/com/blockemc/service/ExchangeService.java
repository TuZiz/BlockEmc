package com.blockemc.service;

import com.blockemc.config.ValueRegistry;
import com.blockemc.model.MaterialValue;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.TradeResult;
import com.blockemc.util.ItemStackUtil;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class ExchangeService {

    public record BuyQuote(boolean success, String messageKey, Object[] args, Material material, int amount, long cost) {
        public static BuyQuote failure(String messageKey, Object... args) {
            return new BuyQuote(false, messageKey, args, null, 0, 0L);
        }

        public static BuyQuote success(Material material, int amount, long cost) {
            return new BuyQuote(true, null, new Object[0], material, amount, cost);
        }
    }

    public record BulkSellQuote(boolean success, String messageKey, Object[] args, long reward, Map<Material, Integer> soldMaterials) {
        public static BulkSellQuote failure(String messageKey, Object... args) {
            return new BulkSellQuote(false, messageKey, args, 0L, Map.of());
        }

        public static BulkSellQuote success(long reward, Map<Material, Integer> soldMaterials) {
            return new BulkSellQuote(true, null, new Object[0], reward, Map.copyOf(soldMaterials));
        }
    }

    private PluginSettings settings;
    private final ValueRegistry valueRegistry;
    private final AccountService accountService;

    public ExchangeService(PluginSettings settings, ValueRegistry valueRegistry, AccountService accountService) {
        this.settings = settings;
        this.valueRegistry = valueRegistry;
        this.accountService = accountService;
    }

    public void updateSettings(PluginSettings settings) {
        this.settings = settings;
    }

    public BuyQuote quoteBuy(Player player, Material material, int amount) {
        MaterialValue value = valueRegistry.get(material).orElse(null);
        if (value == null || valueRegistry.isHidden(material)) {
            return BuyQuote.failure("uemc-material-not-supported");
        }
        if (!value.mode().canBuy()) {
            return BuyQuote.failure("uemc-buy-disabled-sell-only");
        }

        int safeAmount = Math.max(1, Math.min(amount, settings.maxSingleTrade()));
        long cost = value.emc() * safeAmount;
        if (accountService.getBalance(player.getUniqueId()) < cost) {
            return BuyQuote.failure("uemc-emc-not-enough", cost);
        }

        ItemStack stack = new ItemStack(material, safeAmount);
        if (!ItemStackUtil.canFit(player.getInventory(), stack)) {
            return BuyQuote.failure("uemc-inventory-full");
        }
        return BuyQuote.success(material, safeAmount, cost);
    }

    public BuyQuote quoteBuyMaximum(Player player, Material material, int cap) {
        MaterialValue value = valueRegistry.get(material).orElse(null);
        if (value == null || valueRegistry.isHidden(material)) {
            return BuyQuote.failure("uemc-material-not-supported");
        }
        if (!value.mode().canBuy()) {
            return BuyQuote.failure("uemc-buy-disabled-sell-only");
        }

        int desired = Math.min(Math.max(1, cap), settings.maxSingleTrade());
        int balanceLimited = (int) Math.min(Integer.MAX_VALUE, accountService.getBalance(player.getUniqueId()) / Math.max(1L, value.emc()));
        if (balanceLimited <= 0) {
            return BuyQuote.failure("uemc-emc-not-enough", value.emc());
        }

        int inventoryLimited = ItemStackUtil.maxFit(player.getInventory(), material, desired);
        if (inventoryLimited <= 0) {
            return BuyQuote.failure("uemc-inventory-full");
        }

        int amount = Math.min(desired, Math.min(balanceLimited, inventoryLimited));
        return BuyQuote.success(material, amount, value.emc() * amount);
    }

    public TradeResult buy(Player player, Material material, int amount) {
        BuyQuote quote = quoteBuy(player, material, amount);
        if (!quote.success()) {
            return TradeResult.failure(quote.messageKey(), quote.args());
        }

        accountService.takeBalance(player.getUniqueId(), player.getName(), quote.cost());
        player.getInventory().addItem(new ItemStack(material, quote.amount()));
        accountService.recordPurchase(player.getUniqueId(), player.getName(), material, quote.amount());
        return TradeResult.success("uemc-buy-success", quote.cost());
    }

    public TradeResult buyMaximum(Player player, Material material, int cap) {
        BuyQuote quote = quoteBuyMaximum(player, material, cap);
        if (!quote.success()) {
            return TradeResult.failure(quote.messageKey(), quote.args());
        }
        return buy(player, material, quote.amount());
    }

    public TradeResult sell(Player player, Material material, int amount) {
        MaterialValue value = valueRegistry.get(material).orElse(null);
        if (value == null) {
            return TradeResult.failure("uemc-material-not-supported");
        }
        if (!value.mode().canSell()) {
            return TradeResult.failure("uemc-sell-disabled-buy-only");
        }

        int safeAmount = Math.max(1, amount);
        if (!ItemStackUtil.removeMatching(player.getInventory(), material, safeAmount)) {
            return TradeResult.failure("uemc-item-not-enough-sell");
        }

        long reward = calculateSellEmc(value.emc(), safeAmount);
        accountService.addBalance(player.getUniqueId(), player.getName(), reward);
        accountService.recordSale(player.getUniqueId(), player.getName(), Map.of(material, safeAmount), reward);
        return TradeResult.success("uemc-sell-success", reward);
    }

    public TradeResult sellAll(Player player, Material material) {
        int total = ItemStackUtil.countMatching(player.getInventory(), material);
        if (total <= 0) {
            return TradeResult.failure("uemc-item-not-enough-sell");
        }
        return sell(player, material, total);
    }

    public BulkSellQuote previewBulkSell(Inventory inventory, Collection<Integer> inputSlots) {
        long totalReward = 0L;
        Map<Material, Integer> soldMaterials = new LinkedHashMap<>();

        for (int slot : inputSlots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (!isSellable(stack)) {
                return BulkSellQuote.failure("uemc-bulk-sell-blocked-unsellable");
            }

            MaterialValue value = valueRegistry.get(stack.getType()).orElseThrow();
            soldMaterials.merge(stack.getType(), stack.getAmount(), Integer::sum);
            totalReward += calculateSellEmc(value.emc(), stack.getAmount());
        }

        if (soldMaterials.isEmpty()) {
            return BulkSellQuote.failure("uemc-no-sellable-items");
        }
        return BulkSellQuote.success(totalReward, soldMaterials);
    }

    public TradeResult bulkSell(Player player, Inventory inventory, Collection<Integer> inputSlots) {
        BulkSellQuote quote = previewBulkSell(inventory, inputSlots);
        if (!quote.success()) {
            return TradeResult.failure(quote.messageKey(), quote.args());
        }

        for (int slot : inputSlots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            if (isSellable(stack)) {
                inventory.setItem(slot, null);
            }
        }

        accountService.addBalance(player.getUniqueId(), player.getName(), quote.reward());
        accountService.recordSale(player.getUniqueId(), player.getName(), quote.soldMaterials(), quote.reward());
        return TradeResult.success("uemc-batch-sell-success", quote.reward());
    }

    public long calculateSellEmc(long emc, int amount) {
        long calculated = Math.round(emc * amount * settings.recycleRate());
        return Math.max(1L, calculated);
    }

    public boolean isSellable(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }
        MaterialValue value = valueRegistry.get(itemStack.getType()).orElse(null);
        return value != null && value.mode().canSell();
    }
}
