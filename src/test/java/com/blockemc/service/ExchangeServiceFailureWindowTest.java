package com.blockemc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.blockemc.compat.SchedulerAdapter;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.ExchangeMode;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class ExchangeServiceFailureWindowTest {

    private ServerMock server;
    private PluginMock plugin;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcTest");
    }

    @AfterEach
    void tearDown() {
        if (accountService != null) {
            accountService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void purchaseSchedulerFailureRefundsDebit() {
        ExchangeService exchangeService = exchangeService(new FailingScheduler());
        PlayerMock player = server.addPlayer("Buyer");
        accountService.notePlayer(player);
        accountService.setBalance(player.getUniqueId(), player.getName(), 100L).join();

        assertFalse(exchangeService.buy(player, Material.STONE, 1).join().success());
        waitForAsyncCompensation();

        assertEquals(100L, accountService.getCachedBalance(player.getUniqueId()));
        assertEquals(0, count(player, Material.STONE));
    }

    @Test
    void sellSchedulerFailureKeepsItemAndRemovesTemporaryCredit() {
        ExchangeService exchangeService = exchangeService(new FailingScheduler());
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        accountService.notePlayer(player);

        assertFalse(exchangeService.sell(player, Material.STONE, 1).join().success());
        waitForAsyncCompensation();

        assertEquals(0L, accountService.getCachedBalance(player.getUniqueId()));
        assertEquals(1, count(player, Material.STONE));
    }

    private ExchangeService exchangeService(SchedulerAdapter scheduler) {
        PluginSettings settings = new PluginSettings(
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
        accountService = new AccountService(
                plugin,
                new StorageSettings(StorageSettings.Type.YAML, StorageSettings.MySqlSettings.defaults()),
                settings
        );
        ValueRegistry valueRegistry = new ValueRegistry(plugin, settings);
        valueRegistry.set(Material.STONE, 50L, ExchangeMode.BOTH);
        return new ExchangeService(plugin, settings, valueRegistry, accountService, scheduler);
    }

    private int count(Player player, Material material) {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(stack -> stack != null && stack.getType() == material)
                .mapToInt(ItemStack::getAmount)
                .sum();
    }

    private void waitForAsyncCompensation() {
        try {
            Thread.sleep(200L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FailingScheduler implements SchedulerAdapter {
        @Override
        public void runGlobal(Runnable task) {
            throw new IllegalStateException("scheduler failed");
        }

        @Override
        public void runAsync(Runnable task) {
            throw new IllegalStateException("scheduler failed");
        }

        @Override
        public void runForPlayer(Player player, Runnable task) {
            throw new IllegalStateException("scheduler failed");
        }

        @Override
        public void runLaterForPlayer(Player player, Runnable task, long delayTicks) {
            throw new IllegalStateException("scheduler failed");
        }
    }
}
