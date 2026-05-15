package com.blockemc.model;

import java.util.List;
import org.bukkit.Material;

public record DisplayTemplate(Material material, String name, List<String> lore) {
}
