package com.blockemc.model;

import java.util.List;
import org.bukkit.Material;

public record ShopDefinition(
        String id,
        String name,
        Material icon,
        List<String> lore,
        int order,
        List<Material> materials,
        LayoutTemplate layout
) {
}
