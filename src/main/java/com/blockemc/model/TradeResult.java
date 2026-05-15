package com.blockemc.model;

public record TradeResult(boolean success, String messageKey, Object[] args) {

    public static TradeResult success(String messageKey, Object... args) {
        return new TradeResult(true, messageKey, args);
    }

    public static TradeResult failure(String messageKey, Object... args) {
        return new TradeResult(false, messageKey, args);
    }
}
