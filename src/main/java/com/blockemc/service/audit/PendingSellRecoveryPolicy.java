package com.blockemc.service.audit;

public final class PendingSellRecoveryPolicy {

    private PendingSellRecoveryPolicy() {
    }

    public static PendingSellRecoveryAction actionFor(PendingSellStatus status) {
        if (status == null) {
            return PendingSellRecoveryAction.IGNORE_TERMINAL;
        }
        return switch (status) {
            case PENDING_REMOVAL -> PendingSellRecoveryAction.FAIL_SAFE_BEFORE_REMOVAL;
            case REMOVING_ITEMS -> PendingSellRecoveryAction.MANUAL_REVIEW_ITEM_REMOVAL_UNCERTAIN;
            case ITEMS_REMOVED -> PendingSellRecoveryAction.CREDIT_REMOVED_ITEMS;
            case CREDITING -> PendingSellRecoveryAction.MANUAL_REVIEW_CREDIT_UNCERTAIN;
            case SUCCESS, FAILED, MANUAL_REVIEW -> PendingSellRecoveryAction.IGNORE_TERMINAL;
        };
    }

    public static boolean isOpen(PendingSellStatus status) {
        return actionFor(status) != PendingSellRecoveryAction.IGNORE_TERMINAL;
    }
}
