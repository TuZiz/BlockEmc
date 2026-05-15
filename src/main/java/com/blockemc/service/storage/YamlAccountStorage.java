package com.blockemc.service.storage;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class YamlAccountStorage implements AccountStorage {

    private final JavaPlugin plugin;
    private final File accountsFile;

    public YamlAccountStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.accountsFile = new File(plugin.getDataFolder(), "accounts.yml");
    }

    public boolean exists() {
        return accountsFile.isFile();
    }

    @Override
    public AccountSnapshot load() {
        if (!accountsFile.isFile()) {
            return AccountSnapshot.empty();
        }

        Map<UUID, Long> balances = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Set<Material>> favorites = new HashMap<>();
        Map<UUID, AccountSnapshot.DailySaleSnapshot> dailySales = new HashMap<>();
        Map<String, Integer> purchaseHeat = new HashMap<>();

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(accountsFile);
        loadBalances(configuration, balances);
        loadNames(configuration, names);
        loadFavorites(configuration, favorites);
        loadDailySales(configuration, dailySales);
        loadPurchaseHeat(configuration, purchaseHeat);

        return new AccountSnapshot(balances, names, favorites, dailySales, purchaseHeat);
    }

    @Override
    public void save(AccountSnapshot snapshot) throws AccountStorageException {
        YamlConfiguration configuration = new YamlConfiguration();

        ConfigurationSection balanceSection = configuration.createSection("balances");
        snapshot.balances().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> balanceSection.set(entry.getKey().toString(), entry.getValue()));

        ConfigurationSection nameSection = configuration.createSection("names");
        snapshot.names().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> nameSection.set(entry.getKey().toString(), entry.getValue()));

        ConfigurationSection favoriteSection = configuration.createSection("favorites");
        snapshot.favorites().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> favoriteSection.set(
                        entry.getKey().toString(),
                        entry.getValue().stream().map(Material::name).sorted().toList()
                ));

        ConfigurationSection dailySection = configuration.createSection("daily-sales");
        snapshot.dailySales().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    AccountSnapshot.DailySaleSnapshot data = entry.getValue();
                    ConfigurationSection playerSection = dailySection.createSection(entry.getKey().toString());
                    playerSection.set("date", data.date());
                    playerSection.set("sold-emc", data.soldEmc());
                    ConfigurationSection materialSection = playerSection.createSection("materials");
                    data.soldAmounts().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(materialEntry -> materialSection.set(materialEntry.getKey(), materialEntry.getValue()));
                });

        ConfigurationSection heatSection = configuration.createSection("purchase-heat");
        snapshot.purchaseHeat().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .forEach(entry -> heatSection.set(entry.getKey(), entry.getValue()));

        File parent = accountsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new AccountStorageException("Failed to create plugin data directory for accounts.yml");
        }

        try {
            configuration.save(accountsFile);
        } catch (IOException exception) {
            throw new AccountStorageException("Failed to save accounts.yml", exception);
        }
    }

    private void loadBalances(YamlConfiguration configuration, Map<UUID, Long> balances) {
        ConfigurationSection section = configuration.getConfigurationSection("balances");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                balances.put(UUID.fromString(key), Math.max(0L, section.getLong(key)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid UUID in accounts.yml balances: " + key);
            }
        }
    }

    private void loadNames(YamlConfiguration configuration, Map<UUID, String> names) {
        ConfigurationSection section = configuration.getConfigurationSection("names");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                names.put(UUID.fromString(key), configuration.getString("names." + key, key));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid UUID in accounts.yml names: " + key);
            }
        }
    }

    private void loadFavorites(YamlConfiguration configuration, Map<UUID, Set<Material>> favorites) {
        ConfigurationSection section = configuration.getConfigurationSection("favorites");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uniqueId = UUID.fromString(key);
                Set<Material> materials = EnumSet.noneOf(Material.class);
                for (String value : section.getStringList(key)) {
                    Material material = Material.matchMaterial(value);
                    if (material != null) {
                        materials.add(material);
                    }
                }
                favorites.put(uniqueId, materials);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid UUID in accounts.yml favorites: " + key);
            }
        }
    }

    private void loadDailySales(
            YamlConfiguration configuration,
            Map<UUID, AccountSnapshot.DailySaleSnapshot> dailySales
    ) {
        ConfigurationSection section = configuration.getConfigurationSection("daily-sales");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uniqueId = UUID.fromString(key);
                ConfigurationSection playerSection = section.getConfigurationSection(key);
                if (playerSection == null) {
                    continue;
                }
                Map<String, Integer> soldAmounts = new HashMap<>();
                ConfigurationSection amountSection = playerSection.getConfigurationSection("materials");
                if (amountSection != null) {
                    for (String materialKey : amountSection.getKeys(false)) {
                        soldAmounts.put(materialKey, Math.max(0, amountSection.getInt(materialKey)));
                    }
                }
                dailySales.put(
                        uniqueId,
                        new AccountSnapshot.DailySaleSnapshot(
                                playerSection.getString("date"),
                                playerSection.getLong("sold-emc"),
                                soldAmounts
                        )
                );
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid UUID in accounts.yml daily-sales: " + key);
            }
        }
    }

    private void loadPurchaseHeat(YamlConfiguration configuration, Map<String, Integer> purchaseHeat) {
        ConfigurationSection section = configuration.getConfigurationSection("purchase-heat");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                continue;
            }
            int amount = Math.max(0, section.getInt(key));
            if (amount > 0) {
                purchaseHeat.put(material.name(), amount);
            }
        }
    }
}
