package com.blockemc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.blockemc.compat.SchedulerAdapter;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.ExchangeMode;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import com.blockemc.model.TradeResult;
import com.blockemc.service.audit.PendingSellStatus;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class ExchangeServiceSellFlowTest {

    private ServerMock server;
    private PluginMock plugin;
    private AccountService accountService;
    private ExchangeService exchangeService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcSellFlowTest");
    }

    @AfterEach
    void tearDown() {
        if (accountService != null) {
            accountService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void notEnoughItemsDoesNotCreatePending() {
        exchangeService = exchangeService(settings(1_000_000L));
        PlayerMock player = server.addPlayer("Seller");
        accountService.notePlayer(player);

        TradeResult result = exchangeService.sell(player, Material.STONE, 1).join();

        assertFalse(result.success());
        assertEquals(0, pendingCount());
        assertEquals(0L, accountService.getCachedBalance(player.getUniqueId()));
    }

    @Test
    void savePendingFailureDoesNotRemoveItemsOrCredit() {
        exchangeService = exchangeService(settings(1_000_000L));
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        accountService.notePlayer(player);
        assertTrue(plugin.getDataFolder().toPath().resolve("pending-transactions.yml.tmp").toFile().mkdirs());

        TradeResult result = exchangeService.sell(player, Material.STONE, 1).join();

        assertFalse(result.success());
        assertEquals(1, count(player, Material.STONE));
        assertEquals(0L, accountService.getCachedBalance(player.getUniqueId()));
    }

    @Test
    void successfulSellRemovesItemsCreditsAndMarksSuccess() {
        exchangeService = exchangeService(settings(1_000_000L));
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        accountService.notePlayer(player);

        TradeResult result = exchangeService.sell(player, Material.STONE, 1).join();

        assertTrue(result.success());
        assertEquals(0, count(player, Material.STONE));
        assertEquals(50L, accountService.getCachedBalance(player.getUniqueId()));
        assertEquals(PendingSellStatus.SUCCESS, firstPendingStatus());
    }

    @Test
    void creditRejectedAfterRemovalReturnsItemsAndMarksFailed() {
        exchangeService = exchangeService(settings(40L));
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        accountService.notePlayer(player);

        TradeResult result = exchangeService.sell(player, Material.STONE, 1).join();

        assertFalse(result.success());
        assertEquals(1, count(player, Material.STONE));
        assertEquals(0L, accountService.getCachedBalance(player.getUniqueId()));
        assertEquals(PendingSellStatus.FAILED, firstPendingStatus());
    }

    private ExchangeService exchangeService(PluginSettings settings) {
        accountService = new AccountService(
                plugin,
                new StorageSettings(StorageSettings.Type.YAML, StorageSettings.MySqlSettings.defaults()),
                settings
        );
        ValueRegistry valueRegistry = new ValueRegistry(plugin, settings);
        valueRegistry.set(Material.STONE, 50L, ExchangeMode.BOTH);
        return new ExchangeService(plugin, settings, valueRegistry, accountService, new ImmediateScheduler());
    }

    private int count(Player player, Material material) {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(stack -> stack != null && stack.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    private int pendingCount() {
        YamlConfiguration configuration = pendingConfiguration();
        return configuration.getConfigurationSection("transactions") == null
                ? 0
                : configuration.getConfigurationSection("transactions").getKeys(false).size();
    }

    private PendingSellStatus firstPendingStatus() {
        YamlConfiguration configuration = pendingConfiguration();
        String id = configuration.getConfigurationSection("transactions").getKeys(false).iterator().next();
        return PendingSellStatus.valueOf(configuration.getString("transactions." + id + ".status"));
    }

    private YamlConfiguration pendingConfiguration() {
        return YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("pending-transactions.yml").toFile());
    }

    private PluginSettings settings(long maxBalance) {
        return new PluginSettings(
                1.0D,
                64,
                4096L,
                4096L,
                1_000_000L,
                2304,
                maxBalance,
                false,
                true
        );
    }

    private static final class ImmediateScheduler implements SchedulerAdapter {
        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public void runForPlayer(Player player, Runnable task) {
            task.run();
        }

        @Override
        public void runLaterForPlayer(Player player, Runnable task, long delayTicks) {
            task.run();
        }
    }
}
