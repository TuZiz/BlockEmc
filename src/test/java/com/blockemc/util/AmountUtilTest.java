package com.blockemc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AmountUtilTest {

    @Test
    void purchaseSubtractsBalanceWhenEnough() {
        long cost = AmountUtil.checkedPurchaseCost(50L, 1, 1_000L, 64);
        assertEquals(50L, AmountUtil.checkedSubtractBalance(100L, cost));
    }

    @Test
    void purchaseFailsWhenBalanceInsufficient() {
        long cost = AmountUtil.checkedPurchaseCost(50L, 1, 1_000L, 64);
        assertThrows(ArithmeticException.class, () -> AmountUtil.checkedSubtractBalance(40L, cost));
    }

    @Test
    void zeroPriceCannotBeCalculatedForPurchase() {
        assertThrows(ArithmeticException.class, () -> AmountUtil.checkedPurchaseCost(0L, 1, 1_000L, 64));
    }

    @Test
    void zeroPriceSellRewardIsZero() {
        assertEquals(0L, AmountUtil.checkedSellReward(0L, 64, 0.75D, 1_000L, 64));
    }

    @Test
    void largeMultiplicationDoesNotWrapNegative() {
        assertThrows(ArithmeticException.class, () ->
                AmountUtil.checkedPurchaseCost(Long.MAX_VALUE, 2, Long.MAX_VALUE, 64)
        );
    }

    @Test
    void balanceAddHonorsMaxBalance() {
        assertThrows(ArithmeticException.class, () -> AmountUtil.checkedAddBalance(90L, 20L, 100L));
    }
}
