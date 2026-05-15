package com.blockemc.util;

public final class AmountUtil {

    private AmountUtil() {
    }

    public static long checkedPurchaseCost(long unitPrice, int amount, long maxSinglePrice, int maxTransactionAmount) {
        validateUnitPrice(unitPrice, maxSinglePrice);
        validateAmount(amount, maxTransactionAmount);
        return Math.multiplyExact(unitPrice, amount);
    }

    public static long checkedSellReward(long unitPrice, int amount, double recycleRate, long maxSinglePrice, int maxTransactionAmount) {
        if (unitPrice <= 0L || recycleRate <= 0D) {
            return 0L;
        }
        long base = checkedPurchaseCost(unitPrice, amount, maxSinglePrice, maxTransactionAmount);
        double raw = base * recycleRate;
        if (!Double.isFinite(raw) || raw < 0D || raw > Long.MAX_VALUE) {
            throw new ArithmeticException("EMC reward is outside long range");
        }
        return Math.round(raw);
    }

    public static long checkedAddBalance(long balance, long delta, long maxBalance) {
        if (delta < 0L) {
            throw new ArithmeticException("Negative balance delta");
        }
        long next = Math.addExact(balance, delta);
        if (next > maxBalance) {
            throw new ArithmeticException("Balance limit exceeded");
        }
        return next;
    }

    public static long checkedSubtractBalance(long balance, long delta) {
        if (delta < 0L) {
            throw new ArithmeticException("Negative balance delta");
        }
        long next = Math.subtractExact(balance, delta);
        if (next < 0L) {
            throw new ArithmeticException("Insufficient balance");
        }
        return next;
    }

    public static void validateUnitPrice(long unitPrice, long maxSinglePrice) {
        if (unitPrice <= 0L) {
            throw new ArithmeticException("Unit price must be positive");
        }
        if (unitPrice > maxSinglePrice) {
            throw new ArithmeticException("Unit price exceeds configured limit");
        }
    }

    public static void validateAmount(int amount, int maxTransactionAmount) {
        if (amount <= 0) {
            throw new ArithmeticException("Amount must be positive");
        }
        if (amount > maxTransactionAmount) {
            throw new ArithmeticException("Amount exceeds configured limit");
        }
    }
}
