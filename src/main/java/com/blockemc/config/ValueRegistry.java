package com.blockemc.config;

import com.blockemc.model.ExchangeMode;
import com.blockemc.model.MaterialValue;
import com.blockemc.model.PluginSettings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ValueRegistry {

    private final JavaPlugin plugin;
    private final File valuesFile;
    private final File temporaryFile;
    private final Map<Material, MaterialValue> values = new LinkedHashMap<>();
    private final Set<Material> temporaryMaterials = EnumSet.noneOf(Material.class);
    private final Set<Material> hiddenMaterials = EnumSet.noneOf(Material.class);
    private final Map<Material, String> categoryOverrides = new LinkedHashMap<>();
    private PluginSettings settings;

    public ValueRegistry(JavaPlugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.valuesFile = new File(plugin.getDataFolder(), "values.yml");
        this.temporaryFile = new File(plugin.getDataFolder(), "temporary-materials.yml");
    }

    public void updateSettings(PluginSettings settings) {
        this.settings = settings;
    }

    public void reload() {
        values.clear();
        temporaryMaterials.clear();
        hiddenMaterials.clear();
        categoryOverrides.clear();

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(valuesFile);
        ConfigurationSection valueSection = configuration.getConfigurationSection("values");
        ConfigurationSection modeSection = configuration.getConfigurationSection("modes");
        if (valueSection != null) {
            for (String key : valueSection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("跳过未知材质: " + key);
                    continue;
                }
                long emc = valueSection.getLong(key);
                if (emc <= 0L) {
                    plugin.getLogger().warning("Skipping non-positive EMC value for material " + material.name() + ": " + emc);
                    continue;
                }
                if (settings != null && emc > settings.maxSinglePrice()) {
                    plugin.getLogger().warning("Skipping material " + material.name() + " because EMC value " + emc
                            + " exceeds limits.max-single-price=" + settings.maxSinglePrice());
                    continue;
                }
                ExchangeMode mode = modeSection == null
                        ? ExchangeMode.BOTH
                        : ExchangeMode.fromConfig(modeSection.getString(key));
                values.put(material, new MaterialValue(material, emc, mode));
            }
        }

        for (String value : configuration.getStringList("hidden")) {
            Material material = Material.matchMaterial(value);
            if (material != null) {
                hiddenMaterials.add(material);
            }
        }

        ConfigurationSection categorySection = configuration.getConfigurationSection("categories");
        if (categorySection != null) {
            for (String key : categorySection.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null) {
                    plugin.getLogger().warning("分类覆盖中存在未知材质: " + key);
                    continue;
                }
                String category = categorySection.getString(key, "").trim();
                if (!category.isEmpty()) {
                    categoryOverrides.put(material, category.toLowerCase());
                }
            }
        }

        YamlConfiguration temporaryConfiguration = YamlConfiguration.loadConfiguration(temporaryFile);
        for (String value : temporaryConfiguration.getStringList("materials")) {
            Material material = Material.matchMaterial(value);
            if (material != null) {
                temporaryMaterials.add(material);
            }
        }
    }

    public Optional<MaterialValue> get(Material material) {
        return Optional.ofNullable(values.get(material));
    }

    public boolean has(Material material) {
        return values.containsKey(material);
    }

    public boolean isHidden(Material material) {
        return hiddenMaterials.contains(material);
    }

    public boolean isVisible(Material material) {
        return has(material) && !isHidden(material);
    }

    public Collection<MaterialValue> getAllSorted() {
        List<MaterialValue> entries = new ArrayList<>(values.values());
        entries.sort(Comparator.comparing(value -> value.material().name()));
        return entries;
    }

    public List<Material> getTemporaryMaterials() {
        return temporaryMaterials.stream().sorted(Comparator.comparing(Material::name)).toList();
    }

    public Optional<String> getCategoryOverride(Material material) {
        return Optional.ofNullable(categoryOverrides.get(material));
    }

    public synchronized void set(Material material, long emc, ExchangeMode mode) {
        if (emc <= 0L) {
            throw new IllegalArgumentException("EMC price must be greater than zero");
        }
        if (settings != null && emc > settings.maxSinglePrice()) {
            throw new IllegalArgumentException("EMC price exceeds limits.max-single-price");
        }
        values.put(material, new MaterialValue(material, emc, mode));
        saveValues();
    }

    public synchronized void remove(Material material) {
        values.remove(material);
        hiddenMaterials.remove(material);
        categoryOverrides.remove(material);
        temporaryMaterials.remove(material);
        saveValues();
        saveTemporaryMaterials();
    }

    public synchronized void addTemporaryMaterial(Material material) {
        if (temporaryMaterials.add(material)) {
            saveTemporaryMaterials();
        }
    }

    public synchronized void setHidden(Material material, boolean hidden) {
        if (hidden) {
            hiddenMaterials.add(material);
        } else {
            hiddenMaterials.remove(material);
        }
        saveValues();
    }

    public synchronized void setCategoryOverride(Material material, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            categoryOverrides.remove(material);
        } else {
            categoryOverrides.put(material, categoryId.trim().toLowerCase());
        }
        saveValues();
    }

    private void saveValues() {
        YamlConfiguration configuration = new YamlConfiguration();
        ConfigurationSection valueSection = configuration.createSection("values");
        ConfigurationSection modeSection = configuration.createSection("modes");
        for (MaterialValue value : getAllSorted()) {
            valueSection.set(value.material().name(), value.emc());
            if (value.mode() != ExchangeMode.BOTH) {
                modeSection.set(value.material().name(), value.mode().configValue());
            }
        }

        configuration.set(
                "hidden",
                hiddenMaterials.stream()
                        .sorted(Comparator.comparing(Material::name))
                        .map(Material::name)
                        .toList()
        );

        ConfigurationSection categorySection = configuration.createSection("categories");
        categoryOverrides.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Material::name)))
                .forEach(entry -> categorySection.set(entry.getKey().name(), entry.getValue()));

        configuration.set("custom-items", new LinkedHashMap<>());
        try {
            configuration.save(valuesFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "保存 values.yml 失败", exception);
        }
    }

    private void saveTemporaryMaterials() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("materials", getTemporaryMaterials().stream().map(Material::name).toList());
        try {
            configuration.save(temporaryFile);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "保存 temporary-materials.yml 失败", exception);
        }
    }
}
