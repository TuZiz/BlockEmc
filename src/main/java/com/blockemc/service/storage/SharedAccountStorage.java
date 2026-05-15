package com.blockemc.service.storage;

import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;

public interface SharedAccountStorage extends AccountStorage {

    PlayerAccountState loadPlayer(UUID uniqueId, String fallbackName) throws AccountStorageException;

    SharedAccountGlobalState loadGlobalState() throws AccountStorageException;

    void setBalance(UUID uniqueId, String name, long amount) throws AccountStorageException;

    void addBalance(UUID uniqueId, String name, long amount) throws AccountStorageException;

    void takeBalance(UUID uniqueId, String name, long amount) throws AccountStorageException;

    void setFavorite(UUID uniqueId, String name, Material material, boolean favorite) throws AccountStorageException;

    void recordSale(
            UUID uniqueId,
            String name,
            Map<Material, Integer> soldMaterials,
            long soldEmc,
            String saleDate
    ) throws AccountStorageException;

    void recordPurchase(UUID uniqueId, String name, Material material, int amount) throws AccountStorageException;

    void importFromYamlIfNeeded() throws AccountStorageException;
}
