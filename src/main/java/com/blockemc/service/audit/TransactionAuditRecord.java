package com.blockemc.service.audit;

import java.time.Instant;
import java.util.UUID;
import org.bukkit.Material;

public record TransactionAuditRecord(
        String transactionId,
        UUID playerUuid,
        String playerName,
        String operationType,
        Material material,
        int amount,
        long unitPrice,
        long totalPrice,
        long beforeBalance,
        long afterBalance,
        String storageType,
        boolean success,
        String failureReason,
        Instant timestamp,
        String serverName
) {
}
