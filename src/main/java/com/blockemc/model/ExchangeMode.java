package com.blockemc.model;

import java.util.Locale;

public enum ExchangeMode {
    BOTH,
    SELL_ONLY,
    BUY_ONLY;

    public boolean canBuy() {
        return this != SELL_ONLY;
    }

    public boolean canSell() {
        return this != BUY_ONLY;
    }

    public String configValue() {
        return switch (this) {
            case SELL_ONLY -> "sell_only";
            case BUY_ONLY -> "buy_only";
            default -> "both";
        };
    }

    public static ExchangeMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return BOTH;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "SELL_ONLY", "FALSE" -> SELL_ONLY;
            case "BUY_ONLY", "BUY", "PURCHASE_ONLY" -> BUY_ONLY;
            default -> BOTH;
        };
    }
}
