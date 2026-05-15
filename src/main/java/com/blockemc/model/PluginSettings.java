package com.blockemc.model;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginSettings(
        double recycleRate,
        int maxSingleTrade,
        long highValueBuyConfirmEmc,
        long highValueBulkSellConfirmEmc
) {

    public static PluginSettings load(FileConfiguration configuration) {
        return new PluginSettings(
                configuration.getDouble("exchange.recycle-rate", 0.75D),
                Math.max(1, configuration.getInt("exchange.max-single-trade", 64)),
                Math.max(1L, configuration.getLong("confirm.high-value-buy-emc", 4096L)),
                Math.max(1L, configuration.getLong("confirm.high-value-bulk-sell-emc", 4096L))
        );
    }
}
