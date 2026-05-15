package com.blockemc.service;

import com.blockemc.compat.SchedulerAdapter;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.MaterialValue;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.TradeResult;
import com.blockemc.service.audit.PendingSellStatus;
import com.blockemc.service.audit.PendingSellTransaction;
import com.blockemc.service.audit.TransactionAuditRecord;
import com.blockemc.util.AmountUtil;
import com.blockemc.util.ItemStackUtil;
import com.blockemc.util.SellableItemMatcher;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

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
    private final SchedulerAdapter scheduler;
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<UUID, AtomicBoolean> tradingLocks = new ConcurrentHashMap<>();

    public ExchangeService(
            JavaPlugin plugin,
            PluginSettings settings,
            ValueRegistry valueRegistry,
            AccountService accountService,
            SchedulerAdapter scheduler
    ) {
        this.plugin = plugin;
        this.settings = settings;
        this.valueRegistry = valueRegistry;
        this.accountService = accountService;
        this.scheduler = scheduler;
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
        long cost;
        try {
            cost = AmountUtil.checkedPurchaseCost(
                    value.emc(),
                    safeAmount,
                    settings.maxSinglePrice(),
                    settings.maxTransactionAmount()
            );
        } catch (ArithmeticException exception) {
            return BuyQuote.failure("uemc-amount-too-large");
        }
        if (accountService.getCachedBalance(player.getUniqueId()) < cost) {
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
        int balanceLimited = (int) Math.min(Integer.MAX_VALUE, accountService.getCachedBalance(player.getUniqueId()) / value.emc());
        if (balanceLimited <= 0) {
            return BuyQuote.failure("uemc-emc-not-enough", value.emc());
        }

        int inventoryLimited = ItemStackUtil.maxFit(player.getInventory(), material, desired);
        if (inventoryLimited <= 0) {
            return BuyQuote.failure("uemc-inventory-full");
        }

        int amount = Math.min(desired, Math.min(balanceLimited, inventoryLimited));
        try {
            return BuyQuote.success(
                    material,
                    amount,
                    AmountUtil.checkedPurchaseCost(value.emc(), amount, settings.maxSinglePrice(), settings.maxTransactionAmount())
            );
        } catch (ArithmeticException exception) {
            return BuyQuote.failure("uemc-amount-too-large");
        }
    }

    public CompletableFuture<TradeResult> buy(Player player, Material material, int amount) {
        BuyQuote quote = quoteBuy(player, material, amount);
        if (!quote.success()) {
            return CompletableFuture.completedFuture(TradeResult.failure(quote.messageKey(), quote.args()));
        }

        if (!acquireTradingLock(player.getUniqueId())) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-trade-in-progress"));
        }
        return accountService.tryTakeBalance(player.getUniqueId(), player.getName(), quote.cost()).thenCompose(taken -> {
            if (!taken) {
                releaseTradingLock(player.getUniqueId());
                return CompletableFuture.completedFuture(TradeResult.failure("uemc-emc-not-enough", quote.cost()));
            }
            CompletableFuture<TradeResult> result = new CompletableFuture<>();
            try {
                scheduler.runForPlayer(player, () -> finishPurchasedItemDelivery(player, quote, result));
            } catch (RuntimeException exception) {
                compensatePurchase(player, quote.cost(), "player scheduler failed: " + exception.getMessage());
                audit(player, "BUY", quote.material(), quote.amount(), unitPrice(quote), quote.cost(), false, "player scheduler failed");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-trade-storage-failed"));
            }
            return result;
        }).exceptionally(exception -> {
            releaseTradingLock(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Purchase failed before item delivery for " + player.getUniqueId(), exception);
            return TradeResult.failure("uemc-trade-storage-failed");
        });
    }

    public CompletableFuture<TradeResult> buyMaximum(Player player, Material material, int cap) {
        BuyQuote quote = quoteBuyMaximum(player, material, cap);
        if (!quote.success()) {
            return CompletableFuture.completedFuture(TradeResult.failure(quote.messageKey(), quote.args()));
        }
        return buy(player, material, quote.amount());
    }

    public CompletableFuture<TradeResult> sell(Player player, Material material, int amount) {
        MaterialValue value = valueRegistry.get(material).orElse(null);
        if (value == null) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-material-not-supported"));
        }
        if (!value.mode().canSell()) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-sell-disabled-buy-only"));
        }

        int safeAmount = Math.max(1, amount);
        long reward;
        try {
            AmountUtil.validateAmount(safeAmount, settings.maxTransactionAmount());
            reward = calculateSellEmc(value.emc(), safeAmount);
        } catch (ArithmeticException exception) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-amount-too-large"));
        }
        if (reward <= 0L) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-sell-zero-value"));
        }
        if (ItemStackUtil.countMatching(player.getInventory(), material, settings.strictItemMatch(), settings.sellCustomItems()) < safeAmount) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-item-not-enough-sell"));
        }
        if (!acquireTradingLock(player.getUniqueId())) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-trade-in-progress"));
        }
        PendingSellTransaction transaction = new PendingSellTransaction(
                UUID.randomUUID().toString(),
                player.getUniqueId(),
                player.getName(),
                "SELL",
                Map.of(material.name(), safeAmount),
                reward,
                PendingSellStatus.PENDING_REMOVAL,
                Instant.now(),
                Instant.now(),
                ""
        );
        return accountService.savePendingSell(transaction).thenCompose(ignored -> {
            CompletableFuture<TradeResult> result = new CompletableFuture<>();
            try {
                scheduler.runForPlayer(player, () -> finishSellRemoval(player, material, safeAmount, value.emc(), transaction, result));
            } catch (RuntimeException exception) {
                failPending(transaction, "player scheduler failed");
                audit(player, "SELL", material, safeAmount, value.emc(), reward, false, "player scheduler failed");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-trade-storage-failed"));
            }
            return result;
        }).exceptionally(exception -> {
            releaseTradingLock(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Failed to persist pending sell for " + player.getUniqueId(), exception);
            audit(player, "SELL", material, safeAmount, value.emc(), reward, false, exception.getMessage());
            return TradeResult.failure("uemc-trade-storage-failed");
        });
    }

    public CompletableFuture<TradeResult> sellAll(Player player, Material material) {
        int total = ItemStackUtil.countMatching(player.getInventory(), material, settings.strictItemMatch(), settings.sellCustomItems());
        if (total <= 0) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-item-not-enough-sell"));
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
            try {
                totalReward = Math.addExact(totalReward, calculateSellEmc(value.emc(), stack.getAmount()));
            } catch (ArithmeticException exception) {
                return BulkSellQuote.failure("uemc-amount-too-large");
            }
        }

        if (soldMaterials.isEmpty() || totalReward <= 0L) {
            return BulkSellQuote.failure("uemc-no-sellable-items");
        }
        return BulkSellQuote.success(totalReward, soldMaterials);
    }

    public CompletableFuture<TradeResult> bulkSell(Player player, Inventory inventory, Collection<Integer> inputSlots) {
        BulkSellQuote quote = previewBulkSell(inventory, inputSlots);
        if (!quote.success()) {
            return CompletableFuture.completedFuture(TradeResult.failure(quote.messageKey(), quote.args()));
        }

        if (!acquireTradingLock(player.getUniqueId())) {
            return CompletableFuture.completedFuture(TradeResult.failure("uemc-trade-in-progress"));
        }
        PendingSellTransaction transaction = new PendingSellTransaction(
                UUID.randomUUID().toString(),
                player.getUniqueId(),
                player.getName(),
                "BULK_SELL",
                materialNames(quote.soldMaterials()),
                quote.reward(),
                PendingSellStatus.PENDING_REMOVAL,
                Instant.now(),
                Instant.now(),
                ""
        );
        return accountService.savePendingSell(transaction).thenCompose(ignored -> {
            CompletableFuture<TradeResult> result = new CompletableFuture<>();
            try {
                scheduler.runForPlayer(player, () -> finishBulkRemoval(player, inventory, inputSlots, quote, transaction, result));
            } catch (RuntimeException exception) {
                failPending(transaction, "player scheduler failed");
                audit(player, "BULK_SELL", null, totalAmount(quote), 0L, quote.reward(), false, "player scheduler failed");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-trade-storage-failed"));
            }
            return result;
        }).exceptionally(exception -> {
            releaseTradingLock(player.getUniqueId());
            plugin.getLogger().log(Level.WARNING, "Failed to persist pending bulk sell for " + player.getUniqueId(), exception);
            audit(player, "BULK_SELL", null, totalAmount(quote), 0L, quote.reward(), false, exception.getMessage());
            return TradeResult.failure("uemc-trade-storage-failed");
        });
    }

    public long calculateSellEmc(long emc, int amount) {
        return AmountUtil.checkedSellReward(
                emc,
                amount,
                settings.recycleRate(),
                settings.maxSinglePrice(),
                settings.maxTransactionAmount()
        );
    }

    public boolean isSellable(ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR) {
            return false;
        }
        MaterialValue value = valueRegistry.get(itemStack.getType()).orElse(null);
        return value != null
                && value.mode().canSell()
                && SellableItemMatcher.isPlainSellable(
                        itemStack,
                        itemStack.getType(),
                        settings.strictItemMatch(),
                        settings.sellCustomItems()
                );
    }

    private void finishPurchasedItemDelivery(Player player, BuyQuote quote, CompletableFuture<TradeResult> result) {
        try {
            ItemStack stack = new ItemStack(quote.material(), quote.amount());
            if (!ItemStackUtil.canFit(player.getInventory(), stack)) {
                compensatePurchase(player, quote.cost(), "inventory changed before delivery");
                audit(player, "BUY", quote.material(), quote.amount(), unitPrice(quote), quote.cost(), false, "inventory full");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-inventory-full"));
                return;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) {
                compensatePurchase(player, quote.cost(), "inventory addItem returned leftovers");
                audit(player, "BUY", quote.material(), quote.amount(), unitPrice(quote), quote.cost(), false, "inventory leftovers");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-inventory-full"));
                return;
            }
            accountService.recordPurchase(player.getUniqueId(), player.getName(), quote.material(), quote.amount());
            audit(player, "BUY", quote.material(), quote.amount(), unitPrice(quote), quote.cost(), true, "");
            releaseTradingLock(player.getUniqueId());
            result.complete(TradeResult.success("uemc-buy-success", quote.cost()));
        } catch (RuntimeException exception) {
            compensatePurchase(player, quote.cost(), exception.getMessage());
            audit(player, "BUY", quote.material(), quote.amount(), unitPrice(quote), quote.cost(), false, exception.getMessage());
            releaseTradingLock(player.getUniqueId());
            result.complete(TradeResult.failure("uemc-trade-storage-failed"));
        }
    }

    private void compensatePurchase(Player player, long cost, String reason) {
        accountService.tryAddBalance(player.getUniqueId(), player.getName(), cost).thenAccept(refunded -> {
            if (!refunded) {
                plugin.getLogger().severe("Failed to compensate purchase for " + player.getUniqueId() + ": " + reason);
            }
        }).exceptionally(exception -> {
            plugin.getLogger().log(Level.SEVERE, "Failed to compensate purchase for " + player.getUniqueId() + ": " + reason, exception);
            return null;
        });
    }

    private void finishSellRemoval(
            Player player,
            Material material,
            int amount,
            long unitPrice,
            PendingSellTransaction transaction,
            CompletableFuture<TradeResult> result
    ) {
        try {
            boolean removed = ItemStackUtil.removeMatching(
                    player.getInventory(),
                    material,
                    amount,
                    settings.strictItemMatch(),
                    settings.sellCustomItems()
            );
            if (!removed) {
                failPending(transaction, "item removal failed");
                audit(player, "SELL", material, amount, unitPrice, transaction.reward(), false, "item removal failed");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-item-not-enough-sell"));
                return;
            }
            accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.ITEMS_REMOVED, "")
                    .thenCompose(ignored -> accountService.tryAddBalance(player.getUniqueId(), player.getName(), transaction.reward()))
                    .thenCompose(added -> {
                        if (!added) {
                            return accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.FAILED, "balance add rejected")
                                    .thenApply(ignored -> false);
                        }
                        return accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.SUCCESS, "")
                                .thenApply(ignored -> true);
                    })
                    .thenAccept(added -> scheduler.runForPlayer(player, () -> {
                        if (!added) {
                            ItemStackUtil.giveOrDrop(player, new ItemStack(material, amount));
                            audit(player, "SELL", material, amount, unitPrice, transaction.reward(), false, "balance add rejected");
                            result.complete(TradeResult.failure("uemc-trade-storage-failed"));
                        } else {
                            accountService.recordSale(player.getUniqueId(), player.getName(), Map.of(material, amount), transaction.reward());
                            audit(player, "SELL", material, amount, unitPrice, transaction.reward(), true, "");
                            result.complete(TradeResult.success("uemc-sell-success", transaction.reward()));
                        }
                        releaseTradingLock(player.getUniqueId());
                    }))
                    .exceptionally(exception -> {
                        plugin.getLogger().log(Level.SEVERE, "Pending sell failed after items were removed: " + transaction.transactionId(), exception);
                        result.complete(TradeResult.failure("uemc-trade-storage-failed"));
                        releaseTradingLock(player.getUniqueId());
                        return null;
                    });
        } catch (RuntimeException exception) {
            failPending(transaction, exception.getMessage());
            audit(player, "SELL", material, amount, unitPrice, transaction.reward(), false, exception.getMessage());
            releaseTradingLock(player.getUniqueId());
            result.complete(TradeResult.failure("uemc-trade-storage-failed"));
        }
    }

    private void finishBulkRemoval(
            Player player,
            Inventory inventory,
            Collection<Integer> inputSlots,
            BulkSellQuote originalQuote,
            PendingSellTransaction transaction,
            CompletableFuture<TradeResult> result
    ) {
        try {
            BulkSellQuote currentQuote = previewBulkSell(inventory, inputSlots);
            if (!currentQuote.success()
                    || currentQuote.reward() != originalQuote.reward()
                    || !currentQuote.soldMaterials().equals(originalQuote.soldMaterials())) {
                failPending(transaction, "input changed before removal");
                audit(player, "BULK_SELL", null, totalAmount(originalQuote), 0L, originalQuote.reward(), false, "input changed before removal");
                releaseTradingLock(player.getUniqueId());
                result.complete(TradeResult.failure("uemc-bulk-sell-input-changed"));
                return;
            }
            for (int slot : inputSlots) {
                ItemStack stack = inventory.getItem(slot);
                if (stack != null && stack.getType() != Material.AIR && isSellable(stack)) {
                    inventory.setItem(slot, null);
                }
            }
            accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.ITEMS_REMOVED, "")
                    .thenCompose(ignored -> accountService.tryAddBalance(player.getUniqueId(), player.getName(), originalQuote.reward()))
                    .thenCompose(added -> {
                        if (!added) {
                            return accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.FAILED, "balance add rejected")
                                    .thenApply(ignored -> false);
                        }
                        return accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.SUCCESS, "")
                                .thenApply(ignored -> true);
                    })
                    .thenAccept(added -> scheduler.runForPlayer(player, () -> {
                        if (!added) {
                            returnBulkMaterials(player, originalQuote);
                            audit(player, "BULK_SELL", null, totalAmount(originalQuote), 0L, originalQuote.reward(), false, "balance add rejected");
                            result.complete(TradeResult.failure("uemc-trade-storage-failed"));
                        } else {
                            accountService.recordSale(player.getUniqueId(), player.getName(), originalQuote.soldMaterials(), originalQuote.reward());
                            audit(player, "BULK_SELL", null, totalAmount(originalQuote), 0L, originalQuote.reward(), true, "");
                            result.complete(TradeResult.success("uemc-batch-sell-success", originalQuote.reward()));
                        }
                        releaseTradingLock(player.getUniqueId());
                    }))
                    .exceptionally(exception -> {
                        plugin.getLogger().log(Level.SEVERE, "Pending bulk sell failed after items were removed: " + transaction.transactionId(), exception);
                        result.complete(TradeResult.failure("uemc-trade-storage-failed"));
                        releaseTradingLock(player.getUniqueId());
                        return null;
                    });
        } catch (RuntimeException exception) {
            failPending(transaction, exception.getMessage());
            audit(player, "BULK_SELL", null, totalAmount(originalQuote), 0L, originalQuote.reward(), false, exception.getMessage());
            releaseTradingLock(player.getUniqueId());
            result.complete(TradeResult.failure("uemc-trade-storage-failed"));
        }
    }

    private void failPending(PendingSellTransaction transaction, String reason) {
        accountService.updatePendingSellStatus(transaction.transactionId(), PendingSellStatus.FAILED, reason)
                .exceptionally(exception -> {
                    plugin.getLogger().log(Level.SEVERE, "Failed to mark pending sell as failed: " + transaction.transactionId(), exception);
                    return null;
                });
    }

    private void returnBulkMaterials(Player player, BulkSellQuote quote) {
        for (Map.Entry<Material, Integer> entry : quote.soldMaterials().entrySet()) {
            ItemStackUtil.giveOrDrop(player, new ItemStack(entry.getKey(), entry.getValue()));
        }
    }

    private Map<String, Integer> materialNames(Map<Material, Integer> materials) {
        Map<String, Integer> named = new LinkedHashMap<>();
        materials.forEach((material, amount) -> named.put(material.name(), amount));
        return named;
    }

    private boolean acquireTradingLock(UUID playerUuid) {
        AtomicBoolean lock = tradingLocks.computeIfAbsent(playerUuid, ignored -> new AtomicBoolean(false));
        return lock.compareAndSet(false, true);
    }

    private void releaseTradingLock(UUID playerUuid) {
        AtomicBoolean lock = tradingLocks.get(playerUuid);
        if (lock != null) {
            lock.set(false);
        }
    }

    private int totalAmount(BulkSellQuote quote) {
        return quote.soldMaterials().values().stream().mapToInt(Integer::intValue).sum();
    }

    private void audit(
            Player player,
            String operation,
            Material material,
            int amount,
            long unitPrice,
            long totalPrice,
            boolean success,
            String failureReason
    ) {
        long afterBalance = accountService.getCachedBalance(player.getUniqueId());
        long beforeBalance = afterBalance;
        if (success) {
            if ("BUY".equals(operation)) {
                beforeBalance = safeAdd(afterBalance, totalPrice);
            } else if (operation.contains("SELL")) {
                beforeBalance = Math.max(0L, afterBalance - totalPrice);
            }
        }
        accountService.recordAudit(new TransactionAuditRecord(
                java.util.UUID.randomUUID().toString(),
                player.getUniqueId(),
                player.getName(),
                operation,
                material,
                amount,
                unitPrice,
                totalPrice,
                beforeBalance,
                afterBalance,
                accountService.getStorageDescription(),
                success,
                failureReason == null ? "" : failureReason,
                Instant.now(),
                plugin.getServer().getName()
        ));
    }

    private long unitPrice(BuyQuote quote) {
        return quote.amount() <= 0 ? 0L : quote.cost() / quote.amount();
    }

    private long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
