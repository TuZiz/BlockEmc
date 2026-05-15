package com.blockemc.service;

import com.blockemc.config.ShopRegistry;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.MaterialValue;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShopAuditService {

    private static final int SAMPLE_LIMIT = 25;

    private final JavaPlugin plugin;
    private final ValueRegistry valueRegistry;
    private final ShopRegistry shopRegistry;

    public ShopAuditService(JavaPlugin plugin, ValueRegistry valueRegistry, ShopRegistry shopRegistry) {
        this.plugin = plugin;
        this.valueRegistry = valueRegistry;
        this.shopRegistry = shopRegistry;
    }

    public List<String> buildBlockAuditReport() {
        List<MaterialValue> priced = valueRegistry.getAllSorted().stream().toList();
        List<Material> forbiddenPriced = priced.stream()
                .map(MaterialValue::material)
                .filter(this::isForbiddenMaterial)
                .sorted(Comparator.comparing(Material::name))
                .toList();
        List<Material> riskSellable = priced.stream()
                .filter(value -> value.mode().canSell())
                .map(MaterialValue::material)
                .filter(this::requiresBuyOnly)
                .sorted(Comparator.comparing(Material::name))
                .toList();
        List<Material> pricedButUnlisted = priced.stream()
                .map(MaterialValue::material)
                .filter(material -> !valueRegistry.isHidden(material))
                .filter(material -> shopRegistry.getEffectiveShopId(material) == null)
                .sorted(Comparator.comparing(Material::name))
                .toList();
        List<Material> shopRefsWithoutPrice = readExplicitShopRefs().stream()
                .filter(material -> !valueRegistry.has(material))
                .sorted(Comparator.comparing(Material::name))
                .toList();
        List<Material> missingCandidates = Arrays.stream(Material.values())
                .filter(this::isBuildingCandidate)
                .filter(material -> !valueRegistry.has(material))
                .sorted(Comparator.comparing(Material::name))
                .toList();

        List<String> lines = new ArrayList<>();
        lines.add("&6&lBlockEmc 建筑商品审计");
        lines.add("&7定价项: &f" + priced.size() + " &8| &7分类: &f" + shopRegistry.getCategoryShops().size());
        appendSection(lines, "超标仍有价格", forbiddenPriced);
        appendSection(lines, "可复制/重力风险但仍可出售", riskSellable);
        appendSection(lines, "有价格但未进入分类", pricedButUnlisted);
        appendSection(lines, "商店引用但未定价", shopRefsWithoutPrice);
        appendSection(lines, "26.1 建筑候选未覆盖", missingCandidates);
        return lines;
    }

    public boolean requiresBuyOnly(Material material) {
        String name = material.name();
        return name.endsWith("_CARPET") || "RAIL".equals(name) || material.hasGravity();
    }

    public boolean isForbiddenMaterial(Material material) {
        String name = material.name();
        if (!material.isBlock() || material.isLegacy() || !material.isItem()) {
            return true;
        }
        if (name.equals("AIR") || name.endsWith("_AIR") || name.startsWith("POTTED_") || name.endsWith("_WALL_SIGN")
                || name.endsWith("_WALL_HANGING_SIGN") || name.endsWith("_WALL_BANNER")
                || name.endsWith("_WALL_TORCH") || name.endsWith("_WALL_FAN")) {
            return true;
        }
        if (name.endsWith("_CHEST") || name.endsWith("_SHULKER_BOX") || name.endsWith("_SHELF")
                || name.endsWith("_GOLEM_STATUE")) {
            return true;
        }
        if (name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")) {
            return true;
        }
        if (name.contains("_ORE") || name.startsWith("RAW_") || name.endsWith("_INGOT") || name.endsWith("_NUGGET")
                || name.contains("SPAWNER") || name.contains("VAULT")) {
            return true;
        }
        if (name.endsWith("_SWORD") || name.endsWith("_SHOVEL") || name.endsWith("_PICKAXE") || name.endsWith("_AXE")
                || name.endsWith("_HOE") || name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.endsWith("_SPAWN_EGG")) {
            return true;
        }

        return Set.of(
                "ANVIL", "CHIPPED_ANVIL", "DAMAGED_ANVIL", "DRAGON_EGG",
                "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL",
                "BEDROCK", "BARRIER", "STRUCTURE_VOID", "STRUCTURE_BLOCK", "JIGSAW",
                "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "LIGHT",
                "BARREL", "CHEST", "TRAPPED_CHEST", "ENDER_CHEST", "SHULKER_BOX",
                "CHISELED_BOOKSHELF", "DECORATED_POT", "COPPER_CHEST",
                "COPPER_GOLEM_STATUE", "BELL", "LECTERN",
                "BOOKSHELF", "ENCHANTING_TABLE", "TNT", "END_CRYSTAL",
                "OBSIDIAN", "CRYING_OBSIDIAN",
                "COAL_BLOCK", "IRON_BLOCK", "GOLD_BLOCK", "DIAMOND_BLOCK",
                "EMERALD_BLOCK", "LAPIS_BLOCK", "NETHERITE_BLOCK",
                "RAW_IRON_BLOCK", "RAW_GOLD_BLOCK", "RAW_COPPER_BLOCK",
                "BONE_BLOCK", "DRIED_KELP_BLOCK", "HAY_BLOCK", "GILDED_BLACKSTONE",
                "WITHER_ROSE", "DRIED_GHAST", "MAGMA_BLOCK", "SCULK", "SCULK_VEIN",
                "CRAFTING_TABLE", "FURNACE", "BLAST_FURNACE", "SMOKER",
                "CARTOGRAPHY_TABLE", "FLETCHING_TABLE", "SMITHING_TABLE", "LOOM",
                "GRINDSTONE", "STONECUTTER", "CAULDRON", "COMPOSTER", "CRAFTER",
                "BEE_NEST", "BEEHIVE", "LODESTONE", "RESPAWN_ANCHOR",
                "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
                "REDSTONE_BLOCK", "REDSTONE_TORCH", "REPEATER", "COMPARATOR",
                "PISTON", "STICKY_PISTON", "OBSERVER", "HOPPER", "DROPPER",
                "DISPENSER", "NOTE_BLOCK", "JUKEBOX", "TARGET", "DAYLIGHT_DETECTOR",
                "TRIPWIRE_HOOK", "LEVER", "CALIBRATED_SCULK_SENSOR", "SCULK_SENSOR",
                "SCULK_SHRIEKER", "SCULK_CATALYST", "COPPER_BULB",
                "COPPER_BLOCK", "EXPOSED_COPPER", "WEATHERED_COPPER", "OXIDIZED_COPPER",
                "WAXED_COPPER_BLOCK", "WAXED_EXPOSED_COPPER",
                "WAXED_WEATHERED_COPPER", "WAXED_OXIDIZED_COPPER",
                "EXPOSED_COPPER_BULB", "WEATHERED_COPPER_BULB", "OXIDIZED_COPPER_BULB",
                "WAXED_COPPER_BULB", "WAXED_EXPOSED_COPPER_BULB",
                "WAXED_WEATHERED_COPPER_BULB", "WAXED_OXIDIZED_COPPER_BULB"
        ).contains(name);
    }

    private boolean isBuildingCandidate(Material material) {
        if (isForbiddenMaterial(material)) {
            return false;
        }
        String name = material.name();
        if (name.equals("WATER") || name.equals("LAVA") || name.equals("FIRE") || name.equals("SOUL_FIRE")
                || name.equals("MOVING_PISTON") || name.startsWith("ATTACHED_")) {
            return false;
        }
        return name.contains("STONE") || name.contains("BRICK") || name.contains("TUFF")
                || name.contains("DEEPSLATE") || name.contains("BLACKSTONE") || name.contains("BASALT")
                || name.contains("SANDSTONE") || name.contains("PRISMARINE") || name.contains("PURPUR")
                || name.contains("QUARTZ") || name.contains("COPPER") || name.contains("RESIN")
                || name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_HYPHAE") || name.endsWith("_STEM") || name.startsWith("STRIPPED_")
                || name.contains("BAMBOO") || name.endsWith("_SLAB") || name.endsWith("_STAIRS")
                || name.endsWith("_WALL") || name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE")
                || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR") || name.endsWith("_SIGN")
                || name.endsWith("_HANGING_SIGN") || name.contains("GLASS") || name.contains("PANE")
                || name.contains("CONCRETE") || name.contains("TERRACOTTA") || name.endsWith("_WOOL")
                || name.endsWith("_CARPET") || name.endsWith("_BANNER") || name.endsWith("_BED")
                || name.contains("CANDLE") || name.contains("LANTERN") || name.contains("TORCH")
                || name.endsWith("_CHAIN") || name.endsWith("_BARS") || name.endsWith("_GRATE")
                || name.contains("LIGHT") || name.contains("FROGLIGHT") || name.contains("ROD")
                || name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || name.contains("FLOWER")
                || name.contains("BUSH") || name.contains("MOSS") || name.contains("VINE")
                || name.contains("CORAL") || name.contains("MUSHROOM") || name.contains("DIRT")
                || name.contains("MUD") || name.contains("SAND") || name.contains("GRAVEL")
                || name.contains("CLAY") || name.contains("SNOW") || name.contains("ICE")
                || name.contains("KELP") || name.contains("SEAGRASS") || name.equals("RAIL")
                || name.equals("FLOWER_POT") || name.equals("BOOKSHELF") || name.equals("SCAFFOLDING")
                || name.equals("LADDER") || name.equals("CAMPFIRE") || name.equals("SOUL_CAMPFIRE");
    }

    private Collection<Material> readExplicitShopRefs() {
        Set<Material> refs = new LinkedHashSet<>();
        File shopsDirectory = new File(plugin.getDataFolder(), "shops");
        File[] files = shopsDirectory.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return refs;
        }
        for (File file : files) {
            YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
            ConfigurationSection shopSection = configuration.getConfigurationSection("Shop");
            if (shopSection == null) {
                continue;
            }
            for (String raw : shopSection.getStringList("Materials")) {
                if (raw.contains("*")) {
                    continue;
                }
                Material material = Material.matchMaterial(raw.trim().toUpperCase(Locale.ROOT));
                if (material != null) {
                    refs.add(material);
                }
            }
        }
        return refs;
    }

    private void appendSection(List<String> lines, String title, List<Material> materials) {
        lines.add("&e" + title + ": &f" + materials.size());
        if (!materials.isEmpty()) {
            lines.add("&7  " + formatSample(materials));
        }
    }

    private String formatSample(List<Material> materials) {
        String sample = materials.stream()
                .limit(SAMPLE_LIMIT)
                .map(Material::name)
                .toList()
                .toString();
        if (materials.size() > SAMPLE_LIMIT) {
            return sample + " ... +" + (materials.size() - SAMPLE_LIMIT);
        }
        return sample;
    }
}
