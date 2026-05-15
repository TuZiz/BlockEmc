package com.blockemc.service.audit;

public enum PendingSellRecoveryAction {
    FAIL_SAFE_BEFORE_REMOVAL,
    MANUAL_REVIEW_ITEM_REMOVAL_UNCERTAIN,
    CREDIT_REMOVED_ITEMS,
    MANUAL_REVIEW_CREDIT_UNCERTAIN,
    IGNORE_TERMINAL
}
