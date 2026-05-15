package com.blockemc.service.audit;

public enum PendingSellStatus {
    PENDING_REMOVAL,
    REMOVING_ITEMS,
    ITEMS_REMOVED,
    CREDITING,
    SUCCESS,
    FAILED,
    MANUAL_REVIEW
}
