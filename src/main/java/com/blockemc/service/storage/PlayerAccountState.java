package com.blockemc.service.storage;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;

public record PlayerAccountState(
        String name,
        long balance,
        Set<Material> favorites,
        AccountSnapshot.DailySaleSnapshot dailySale
) {

    public PlayerAccountState {
        name = (name == null || name.isBlank()) ? "" : name;
        balance = Math.max(0L, balance);
        favorites = favorites == null ? Set.of() : Set.copyOf(new HashSet<>(favorites));
        dailySale = dailySale == null
                ? new AccountSnapshot.DailySaleSnapshot(LocalDate.now().toString(), 0L, java.util.Map.of())
                : dailySale;
    }

    public static PlayerAccountState empty(String fallbackName) {
        return new PlayerAccountState(
                Objects.requireNonNullElse(fallbackName, ""),
                0L,
                Set.of(),
                new AccountSnapshot.DailySaleSnapshot(LocalDate.now().toString(), 0L, java.util.Map.of())
        );
    }
}
