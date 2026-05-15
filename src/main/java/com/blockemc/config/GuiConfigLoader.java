package com.blockemc.config;

import com.blockemc.model.DisplayTemplate;
import com.blockemc.model.IconTemplate;
import com.blockemc.model.LayoutTemplate;
import com.blockemc.util.ItemStackUtil;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class GuiConfigLoader {

    public LayoutTemplate load(File file) {
        return load(YamlConfiguration.loadConfiguration(file));
    }

    public LayoutTemplate load(YamlConfiguration configuration) {
        String title = configuration.getString("Title", "&0Menu");
        List<String> plain = configuration.getStringList("GuiPlain");
        ConfigurationSection keySection = configuration.getConfigurationSection("GuiKey");

        Map<Integer, IconTemplate> iconSlots = new HashMap<>();
        List<Integer> itemSlots = new ArrayList<>();
        List<Integer> inputSlots = new ArrayList<>();

        for (int row = 0; row < plain.size(); row++) {
            String line = plain.get(row);
            for (int column = 0; column < Math.min(9, line.length()); column++) {
                char token = line.charAt(column);
                if (keySection == null) {
                    continue;
                }
                ConfigurationSection tokenSection = keySection.getConfigurationSection(String.valueOf(token));
                if (tokenSection == null) {
                    continue;
                }
                IconTemplate iconTemplate = parseIcon(tokenSection);
                int slot = row * 9 + column;
                iconSlots.put(slot, iconTemplate);
                String function = iconTemplate.function();
                if ("item".equals(function)) {
                    itemSlots.add(slot);
                } else if ("input".equals(function)) {
                    inputSlots.add(slot);
                }
            }
        }

        return new LayoutTemplate(title, plain.size() * 9, itemSlots, inputSlots, iconSlots);
    }

    private IconTemplate parseIcon(ConfigurationSection section) {
        String function = section.getString("IconFunction", "").toLowerCase(Locale.ROOT);
        DisplayTemplate display = parseDisplay(section);
        DisplayTemplate hasDisplay = section.isConfigurationSection("has")
                ? parseDisplay(section.getConfigurationSection("has"))
                : null;
        DisplayTemplate normalDisplay = section.isConfigurationSection("normal")
                ? parseDisplay(section.getConfigurationSection("normal"))
                : null;
        return new IconTemplate(function, display, hasDisplay, normalDisplay);
    }

    private DisplayTemplate parseDisplay(ConfigurationSection section) {
        if (section == null) {
            return new DisplayTemplate(Material.STONE, "", List.of());
        }
        Material material = ItemStackUtil.safeMaterial(section.getString("Material"), Material.STONE);
        String name = section.getString("Name", "");
        List<String> lore = section.getStringList("Lore");
        return new DisplayTemplate(material, name, lore);
    }
}
