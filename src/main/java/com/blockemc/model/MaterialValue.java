package com.blockemc.model;

import org.bukkit.Material;

public record MaterialValue(Material material, long emc, ExchangeMode mode) {
}
