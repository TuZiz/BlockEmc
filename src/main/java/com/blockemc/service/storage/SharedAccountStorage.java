package com.blockemc.service.storage;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import com.blockemc.service.audit.TransactionAuditRecord;
import com.blockemc.service.audit.PendingSellStatus;
import com.blockemc.service.audit.PendingSellTransaction;
import org.bukkit.Material;

public interface SharedAccountStorage extends AccountStorage {

    PlayerAccountState loadPlayer(UUID uniqueId, String fallbackName) throws AccountStorageException;

    SharedAccountGlobalState loadGlobalState() throws AccountStorageException;

    void setBalance(UUID uniqueId, String name, long amount) throws AccountStorageException;

    boolean tryAddBalance(UUID uniqueId, String name, long amount, long maxBalance) throws AccountStorageException;

    boolean tryTakeBalance(UUID uniqueId, String name, long amount) throws AccountStorageException;

    long getBalance(UUID uniqueId) throws AccountStorageException;

    void setFavorite(UUID uniqueId, String name, Material material, boolean favorite) throws AccountStorageException;

    void recordSale(
            UUID uniqueId,
            String name,
            Map<Material, Integer> soldMaterials,
            long soldEmc,
            String saleDate
    ) throws AccountStorageException;

    void recordPurchase(UUID uniqueId, String name, Material material, int amount) throws AccountStorageException;

    default void recordAudit(TransactionAuditRecord record) throws AccountStorageException {
    }

    default void savePendingSell(PendingSellTransaction transaction) throws AccountStorageException {
    }

    default void updatePendingSellStatus(String transactionId, PendingSellStatus status, String reason) throws AccountStorageException {
    }

    default List<PendingSellTransaction> loadOpenPendingSells() throws AccountStorageException {
        return List.of();
    }

    default PendingCreditResult completePendingSellCredit(
            PendingSellTransaction transaction,
            long maxBalance
    ) throws AccountStorageException {
        return PendingCreditResult.MANUAL_REVIEW_REQUIRED;
    }

    void importFromYamlIfNeeded() throws AccountStorageException;
}
