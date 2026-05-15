package com.blockemc.config;

import com.blockemc.model.LayoutTemplate;
import com.blockemc.model.DisplayTemplate;
import com.blockemc.model.IconTemplate;
import com.blockemc.model.MaterialValue;
import com.blockemc.model.ShopDefinition;
import com.blockemc.util.ItemStackUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopRegistry {

    private record ShopMeta(
            String id,
            String name,
            Material icon,
            List<String> lore,
            int order,
            LayoutTemplate layout
    ) {
    }

    private record ShopPattern(String shopId, String pattern) {
    }

    private final JavaPlugin plugin;
    private final GuiConfigLoader guiConfigLoader;
    private final ValueRegistry valueRegistry;
    private final File shopsDirectory;
    private final Map<String, ShopMeta> shopMetas = new LinkedHashMap<>();
    private final Map<String, ShopDefinition> shops = new LinkedHashMap<>();
    private final Map<Material, String> defaultShopByMaterial = new HashMap<>();
    private final List<ShopPattern> defaultShopPatterns = new ArrayList<>();

    public ShopRegistry(JavaPlugin plugin, GuiConfigLoader guiConfigLoader, ValueRegistry valueRegistry) {
        this.plugin = plugin;
        this.guiConfigLoader = guiConfigLoader;
        this.valueRegistry = valueRegistry;
        this.shopsDirectory = new File(plugin.getDataFolder(), "shops");
    }

    public void reload() {
        shopMetas.clear();
        shops.clear();
        defaultShopByMaterial.clear();
        defaultShopPatterns.clear();

        File[] files = shopsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }

        List<File> sortedFiles = List.of(files).stream().sorted(Comparator.comparing(File::getName)).toList();
        for (File file : sortedFiles) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection shopSection = configuration.getConfigurationSection("Shop");
            if (shopSection == null) {
                continue;
            }

            String id = shopSection.getString("Id", file.getName().replace(".yml", "")).toLowerCase();
            Material icon = ItemStackUtil.safeMaterial(shopSection.getString("Icon"), Material.CHEST);
            int order = shopSection.getInt("Order", 0);
            LayoutTemplate layout = normalizeLayout(id, guiConfigLoader.load(configuration));
            shopMetas.put(id, new ShopMeta(
                    id,
                    defaultShopName(id),
                    icon,
                    defaultShopLore(id),
                    order,
                    layout
            ));

            for (String value : shopSection.getStringList("Materials")) {
                if (value.contains("*")) {
                    defaultShopPatterns.add(new ShopPattern(id, value.trim().toUpperCase(Locale.ROOT)));
                    continue;
                }
                Material material = Material.matchMaterial(value);
                if (material != null) {
                    defaultShopByMaterial.put(material, id);
                }
            }
        }

        Map<String, List<Material>> effectiveMaterials = new LinkedHashMap<>();
        for (String shopId : shopMetas.keySet()) {
            effectiveMaterials.put(shopId, new ArrayList<>());
        }

        Set<Material> temporarySet = new HashSet<>(valueRegistry.getTemporaryMaterials());
        for (MaterialValue value : valueRegistry.getAllSorted()) {
            Material material = value.material();
            String targetShopId = valueRegistry.getCategoryOverride(material)
                    .orElseGet(() -> resolveDefaultShopId(material));
            if (targetShopId == null && temporarySet.contains(material)) {
                targetShopId = "temporary";
            }
            if (targetShopId == null || valueRegistry.isHidden(material)) {
                continue;
            }

            ShopMeta meta = shopMetas.get(targetShopId.toLowerCase());
            if (meta != null) {
                effectiveMaterials.computeIfAbsent(meta.id(), ignored -> new ArrayList<>()).add(material);
            }
        }

        for (ShopMeta meta : shopMetas.values()) {
            List<Material> materials = effectiveMaterials.getOrDefault(meta.id(), List.of()).stream()
                    .sorted(Comparator.comparing(Material::name))
                    .toList();
            shops.put(meta.id(), new ShopDefinition(
                    meta.id(),
                    meta.name(),
                    meta.icon(),
                    meta.lore(),
                    meta.order(),
                    materials,
                    meta.layout()
            ));
        }
    }

    public ShopDefinition get(String id) {
        return id == null ? null : shops.get(id.toLowerCase());
    }

    public Collection<ShopDefinition> getAll() {
        return shops.values().stream()
                .sorted(Comparator.comparingInt(ShopDefinition::order).thenComparing(ShopDefinition::id))
                .toList();
    }

    public List<ShopDefinition> getCategoryShops() {
        return getAll().stream()
                .filter(shop -> !"temporary".equalsIgnoreCase(shop.id()))
                .filter(shop -> !shop.materials().isEmpty())
                .toList();
    }

    public List<String> getOrderedShopIdsForAdmin() {
        return getAll().stream()
                .map(ShopDefinition::id)
                .toList();
    }

    public String getDefaultShopId(Material material) {
        return resolveDefaultShopId(material);
    }

    public String getEffectiveShopId(Material material) {
        return valueRegistry.getCategoryOverride(material)
                .orElseGet(() -> {
                    String configured = resolveDefaultShopId(material);
                    if (configured != null) {
                        return configured;
                    }
                    return valueRegistry.getTemporaryMaterials().contains(material) ? "temporary" : null;
                });
    }

    public String getShopDisplayName(String id) {
        ShopDefinition definition = get(id);
        return definition == null ? id : definition.name();
    }

    private String resolveDefaultShopId(Material material) {
        String exact = defaultShopByMaterial.get(material);
        if (exact != null) {
            return exact;
        }
        String materialName = material.name().toUpperCase(Locale.ROOT);
        for (ShopPattern pattern : defaultShopPatterns) {
            if (matchesWildcard(materialName, pattern.pattern())) {
                return pattern.shopId();
            }
        }
        return null;
    }

    private boolean matchesWildcard(String materialName, String pattern) {
        int nameIndex = 0;
        int patternIndex = 0;
        int lastStar = -1;
        int backtrackIndex = -1;

        while (nameIndex < materialName.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == materialName.charAt(nameIndex)) {
                nameIndex++;
                patternIndex++;
                continue;
            }
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                lastStar = patternIndex++;
                backtrackIndex = nameIndex;
                continue;
            }
            if (lastStar != -1) {
                patternIndex = lastStar + 1;
                nameIndex = ++backtrackIndex;
                continue;
            }
            return false;
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }

    private String defaultShopName(String id) {
        return switch (id.toLowerCase()) {
            case "colored" -> "&d&l彩色建材";
            case "copper" -> "&6&l铜艺构件";
            case "decoration" -> "&a&l建筑装饰";
            case "glass" -> "&b&l玻璃幕墙";
            case "glazed" -> "&e&l釉彩陶瓦";
            case "modern" -> "&d&l26.1 装饰建材";
            case "nature" -> "&2&l自然景观";
            case "nether" -> "&c&l下界风格";
            case "polished" -> "&8&l磨制石材";
            case "prismarine" -> "&3&l海洋系列";
            case "redstone" -> "&4&l灯光预留";
            case "special" -> "&5&l末地与特殊";
            case "stone" -> "&6&l石材建材";
            case "temporary" -> "&e&l临时分类";
            case "utility" -> "&9&l功能方块";
            case "wood" -> "&6&l木材系列";
            case "wool" -> "&f&l羊毛与地毯";
            default -> id;
        };
    }

    private List<String> defaultShopLore(String id) {
        return switch (id.toLowerCase()) {
            case "temporary" -> List.of("&7这里存放临时加入、尚未正式归类的方块");
            case "utility" -> List.of("&7功能型方块，默认会隐藏大部分非建筑向物品");
            case "redstone" -> List.of("&7红石机器已移除，本分类仅作兼容预留");
            case "modern" -> List.of("&7苍白橡木、树脂、铜装饰和 26.1 景观建材");
            default -> List.of("&7浏览这一分类下的建筑向方块");
        };
    }

    private LayoutTemplate normalizeLayout(String id, LayoutTemplate layout) {
        Map<Integer, IconTemplate> icons = new LinkedHashMap<>();
        for (Map.Entry<Integer, IconTemplate> entry : layout.iconSlots().entrySet()) {
            IconTemplate icon = entry.getValue();
            IconTemplate normalized = switch (icon.function()) {
                case "last" -> new IconTemplate(
                        icon.function(),
                        icon.display(),
                        new DisplayTemplate(icon.hasDisplay() == null ? Material.ARROW : icon.hasDisplay().material(), "&a上一页", List.of()),
                        new DisplayTemplate(Material.GRAY_STAINED_GLASS_PANE, " ", List.of())
                );
                case "next" -> new IconTemplate(
                        icon.function(),
                        icon.display(),
                        new DisplayTemplate(icon.hasDisplay() == null ? Material.ARROW : icon.hasDisplay().material(), "&a下一页", List.of()),
                        new DisplayTemplate(Material.GRAY_STAINED_GLASS_PANE, " ", List.of())
                );
                case "back" -> new IconTemplate(icon.function(), new DisplayTemplate(Material.BARRIER, "&c返回", List.of()), icon.hasDisplay(), icon.normalDisplay());
                case "status" -> new IconTemplate(icon.function(), new DisplayTemplate(Material.SUNFLOWER, "&e&l当前余额", List.of("&7账户: &f%emc% EMC")), icon.hasDisplay(), icon.normalDisplay());
                default -> icon;
            };
            icons.put(entry.getKey(), normalized);
        }

        return new LayoutTemplate(defaultShopTitle(id), layout.size(), layout.itemSlots(), layout.inputSlots(), icons);
    }

    private String defaultShopTitle(String id) {
        return switch (id.toLowerCase()) {
            case "colored" -> "&0[ &d彩色建材 &0] &8建筑商店";
            case "copper" -> "&0[ &6铜艺构件 &0] &8建筑商店";
            case "decoration" -> "&0[ &a建筑装饰 &0] &8建筑商店";
            case "glass" -> "&0[ &b玻璃幕墙 &0] &8建筑商店";
            case "glazed" -> "&0[ &e釉彩陶瓦 &0] &8建筑商店";
            case "modern" -> "&0[ &d26.1 装饰建材 &0] &8建筑商店";
            case "nature" -> "&0[ &2自然景观 &0] &8建筑商店";
            case "nether" -> "&0[ &c下界风格 &0] &8建筑商店";
            case "polished" -> "&0[ &8磨制石材 &0] &8建筑商店";
            case "prismarine" -> "&0[ &3海洋系列 &0] &8建筑商店";
            case "redstone" -> "&0[ &4灯光预留 &0] &8建筑商店";
            case "special" -> "&0[ &5末地特殊 &0] &8建筑商店";
            case "stone" -> "&0[ &6石材建材 &0] &8建筑商店";
            case "temporary" -> "&0[ &e临时分类 &0] &8待归档方块";
            case "utility" -> "&0[ &9功能方块 &0] &8功能商店";
            case "wood" -> "&0[ &6木材系列 &0] &8建筑商店";
            case "wool" -> "&0[ &f羊毛地毯 &0] &8建筑商店";
            default -> layoutTitleFallback(id);
        };
    }

    private String layoutTitleFallback(String id) {
        return "&0[ &6" + id + " &0]";
    }
}
