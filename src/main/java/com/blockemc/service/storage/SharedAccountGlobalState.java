package com.blockemc.service.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record SharedAccountGlobalState(
        Map<UUID, Long> balances,
        Map<UUID, String> names,
        Map<UUID, AccountSnapshot.DailySaleSnapshot> dailySales,
        Map<String, Integer> purchaseHeat
) {

    public SharedAccountGlobalState {
        balances = balances == null ? Map.of() : Map.copyOf(new HashMap<>(balances));
        names = names == null ? Map.of() : Map.copyOf(new HashMap<>(names));
        dailySales = dailySales == null ? Map.of() : Map.copyOf(new HashMap<>(dailySales));
        purchaseHeat = purchaseHeat == null ? Map.of() : Map.copyOf(new HashMap<>(purchaseHeat));
    }
}
