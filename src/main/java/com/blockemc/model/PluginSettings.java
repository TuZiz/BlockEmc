package com.blockemc.model;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        double recycleRate,
        int maxSingleTrade,
        long highValueBuyConfirmEmc,
        long highValueBulkSellConfirmEmc,
        long maxSinglePrice,
        int maxTransactionAmount,
        long maxBalance,
        boolean sellCustomItems,
        boolean strictItemMatch
) {

    public static final long DEFAULT_MAX_SINGLE_PRICE = 1_000_000_000_000L;
    public static final int DEFAULT_MAX_TRANSACTION_AMOUNT = 2304;
    public static final long DEFAULT_MAX_BALANCE = 900_000_000_000_000_000L;

    public PluginSettings {
        recycleRate = Math.max(0D, recycleRate);
        maxTransactionAmount = Math.max(1, maxTransactionAmount);
        maxSingleTrade = Math.max(1, Math.min(maxSingleTrade, maxTransactionAmount));
        highValueBuyConfirmEmc = Math.max(1L, highValueBuyConfirmEmc);
        highValueBulkSellConfirmEmc = Math.max(1L, highValueBulkSellConfirmEmc);
        maxSinglePrice = Math.max(1L, maxSinglePrice);
        maxBalance = Math.max(1L, maxBalance);
    }

    public static PluginSettings load(FileConfiguration configuration) {
        int maxTransactionAmount = Math.max(
                1,
                configuration.getInt("limits.max-transaction-amount", DEFAULT_MAX_TRANSACTION_AMOUNT)
        );
        return new PluginSettings(
                configuration.getDouble("exchange.recycle-rate", 0.75D),
                Math.max(1, configuration.getInt("exchange.max-single-trade", 64)),
                Math.max(1L, configuration.getLong("confirm.high-value-buy-emc", 4096L)),
                Math.max(1L, configuration.getLong("confirm.high-value-bulk-sell-emc", 4096L)),
                Math.max(1L, configuration.getLong("limits.max-single-price", DEFAULT_MAX_SINGLE_PRICE)),
                maxTransactionAmount,
                Math.max(1L, configuration.getLong("limits.max-balance", DEFAULT_MAX_BALANCE)),
                configuration.getBoolean("security.sell-custom-items", false),
                configuration.getBoolean("security.strict-item-match", true)
        );
    }
}
