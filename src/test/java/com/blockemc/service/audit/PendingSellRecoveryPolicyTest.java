package com.blockemc.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PendingSellRecoveryPolicyTest {

    @Test
    void pendingRemovalRecoveryFailsSafely() {
        assertEquals(
                PendingSellRecoveryAction.FAIL_SAFE_BEFORE_REMOVAL,
                PendingSellRecoveryPolicy.actionFor(PendingSellStatus.PENDING_REMOVAL)
        );
    }

    @Test
    void removingItemsRecoveryRequiresManualReview() {
        assertEquals(
                PendingSellRecoveryAction.MANUAL_REVIEW_ITEM_REMOVAL_UNCERTAIN,
                PendingSellRecoveryPolicy.actionFor(PendingSellStatus.REMOVING_ITEMS)
        );
    }

    @Test
    void removedItemsRecoveryMayCreditOnce() {
        assertEquals(
                PendingSellRecoveryAction.CREDIT_REMOVED_ITEMS,
                PendingSellRecoveryPolicy.actionFor(PendingSellStatus.ITEMS_REMOVED)
        );
    }

    @Test
    void creditingRecoveryRequiresManualReview() {
        assertEquals(
                PendingSellRecoveryAction.MANUAL_REVIEW_CREDIT_UNCERTAIN,
                PendingSellRecoveryPolicy.actionFor(PendingSellStatus.CREDITING)
        );
    }

    @Test
    void terminalStatusesAreIgnored() {
        assertEquals(PendingSellRecoveryAction.IGNORE_TERMINAL, PendingSellRecoveryPolicy.actionFor(PendingSellStatus.SUCCESS));
        assertEquals(PendingSellRecoveryAction.IGNORE_TERMINAL, PendingSellRecoveryPolicy.actionFor(PendingSellStatus.FAILED));
        assertEquals(PendingSellRecoveryAction.IGNORE_TERMINAL, PendingSellRecoveryPolicy.actionFor(PendingSellStatus.MANUAL_REVIEW));
        assertFalse(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.SUCCESS));
        assertFalse(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.FAILED));
        assertFalse(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.MANUAL_REVIEW));
    }

    @Test
    void openStatusesAreLoadedForRecovery() {
        assertTrue(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.PENDING_REMOVAL));
        assertTrue(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.REMOVING_ITEMS));
        assertTrue(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.ITEMS_REMOVED));
        assertTrue(PendingSellRecoveryPolicy.isOpen(PendingSellStatus.CREDITING));
    }
}
