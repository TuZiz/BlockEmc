package com.blockemc.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockemc.model.PluginSettings;
import java.io.IOException;
import java.nio.file.Files;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class ValueRegistryNonItemMaterialTest {

    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcValueRegistryTest");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void reloadSkipsNonItemMaterialsFromValuesYaml() throws IOException {
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.writeString(
                plugin.getDataFolder().toPath().resolve("values.yml"),
                """
                values:
                  CAVE_VINES: 50
                  STONE: 25
                """
        );
        ValueRegistry registry = new ValueRegistry(plugin, settings());

        registry.reload();

        assertFalse(registry.has(Material.CAVE_VINES));
        assertTrue(registry.has(Material.STONE));
        registry.shutdown();
    }

    @Test
    void setRejectsNonItemMaterials() {
        ValueRegistry registry = new ValueRegistry(plugin, settings());

        assertThrows(IllegalArgumentException.class, () -> registry.set(
                Material.CAVE_VINES,
                50L,
                com.blockemc.model.ExchangeMode.BOTH
        ));

        registry.shutdown();
    }

    private PluginSettings settings() {
        return new PluginSettings(
                1.0D,
                64,
                4096L,
                4096L,
                1_000_000L,
                2304,
                1_000_000L,
                false,
                true
        );
    }
}
