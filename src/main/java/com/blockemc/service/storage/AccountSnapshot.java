package com.blockemc.service.storage;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;

public record AccountSnapshot(
        Map<UUID, Long> balances,
        Map<UUID, String> names,
        Map<UUID, Set<Material>> favorites,
        Map<UUID, DailySaleSnapshot> dailySales,
        Map<String, Integer> purchaseHeat
) {

    public AccountSnapshot {
        balances = Map.copyOf(copyBalances(balances));
        names = Map.copyOf(copyNames(names));
        favorites = Map.copyOf(copyFavorites(favorites));
        dailySales = Map.copyOf(copyDailySales(dailySales));
        purchaseHeat = Map.copyOf(copyPurchaseHeat(purchaseHeat));
    }

    public static AccountSnapshot empty() {
        return new AccountSnapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public boolean isEmpty() {
        return balances.isEmpty()
                && names.isEmpty()
                && favorites.isEmpty()
                && dailySales.isEmpty()
                && purchaseHeat.isEmpty();
    }

    public record DailySaleSnapshot(
            String date,
            long soldEmc,
            Map<String, Integer> soldAmounts
    ) {

        public DailySaleSnapshot {
            date = (date == null || date.isBlank()) ? LocalDate.now().toString() : date;
            soldEmc = Math.max(0L, soldEmc);
            soldAmounts = Map.copyOf(copySoldAmounts(soldAmounts));
        }
    }

    private static Map<UUID, Long> copyBalances(Map<UUID, Long> source) {
        Map<UUID, Long> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((uuid, value) -> {
            if (uuid != null) {
                copied.put(uuid, Math.max(0L, value == null ? 0L : value));
            }
        });
        return copied;
    }

    private static Map<UUID, String> copyNames(Map<UUID, String> source) {
        Map<UUID, String> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((uuid, name) -> {
            if (uuid != null && name != null && !name.isBlank()) {
                copied.put(uuid, name);
            }
        });
        return copied;
    }

    private static Map<UUID, Set<Material>> copyFavorites(Map<UUID, Set<Material>> source) {
        Map<UUID, Set<Material>> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((uuid, materials) -> {
            if (uuid != null && materials != null) {
                copied.put(uuid, Set.copyOf(materials));
            }
        });
        return copied;
    }

    private static Map<UUID, DailySaleSnapshot> copyDailySales(Map<UUID, DailySaleSnapshot> source) {
        Map<UUID, DailySaleSnapshot> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((uuid, sale) -> {
            if (uuid != null && sale != null) {
                copied.put(uuid, new DailySaleSnapshot(sale.date(), sale.soldEmc(), sale.soldAmounts()));
            }
        });
        return copied;
    }

    private static Map<String, Integer> copyPurchaseHeat(Map<String, Integer> source) {
        Map<String, Integer> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((material, amount) -> {
            if (material != null && !material.isBlank() && amount != null && amount > 0) {
                copied.put(material, amount);
            }
        });
        return copied;
    }

    private static Map<String, Integer> copySoldAmounts(Map<String, Integer> source) {
        Map<String, Integer> copied = new HashMap<>();
        if (source == null) {
            return copied;
        }
        source.forEach((material, amount) -> {
            if (material != null && !material.isBlank() && amount != null && amount > 0) {
                copied.put(material, amount);
            }
        });
        return copied;
    }
}
