package com.blockemc.service.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PendingSellTransaction(
        String transactionId,
        UUID playerUuid,
        String playerName,
        String operationType,
        Map<String, Integer> materials,
        long reward,
        PendingSellStatus status,
        Instant createdAt,
        Instant updatedAt,
        String failureReason
) {

    public PendingSellTransaction {
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        failureReason = failureReason == null ? "" : failureReason;
    }

    public PendingSellTransaction withStatus(PendingSellStatus nextStatus, String reason) {
        return new PendingSellTransaction(
                transactionId,
                playerUuid,
                playerName,
                operationType,
                materials,
                reward,
                nextStatus,
                createdAt,
                Instant.now(),
                reason
        );
    }
}
