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
import java.util.concurrent.CompletableFuture;
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

class ExchangeServiceTradingLockTest {

    private ServerMock server;
    private PluginMock plugin;
    private AccountService accountService;
    private ControlledScheduler scheduler;
    private ExchangeService exchangeService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcLockTest");
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
        scheduler = new ControlledScheduler();
        exchangeService = new ExchangeService(plugin, settings, valueRegistry, accountService, scheduler);
    }

    @AfterEach
    void tearDown() {
        if (accountService != null) {
            accountService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void concurrentSellForSamePlayerIsRejectedAndSuccessReleasesLock() {
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 2));
        accountService.notePlayer(player);
        scheduler.pausePlayerTasks();

        CompletableFuture<TradeResult> first = exchangeService.sell(player, Material.STONE, 1);
        waitUntilQueued(scheduler);

        TradeResult second = exchangeService.sell(player, Material.STONE, 1).join();
        assertFalse(second.success());
        assertEquals("uemc-trade-in-progress", second.messageKey());

        scheduler.resumePlayerTasks();
        assertTrue(first.join().success());
        assertEquals(50L, accountService.getCachedBalance(player.getUniqueId()));

        assertTrue(exchangeService.sell(player, Material.STONE, 1).join().success());
        assertEquals(100L, accountService.getCachedBalance(player.getUniqueId()));
    }

    @Test
    void failedSellReleasesLock() {
        PlayerMock player = server.addPlayer("Seller");
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        accountService.notePlayer(player);
        scheduler.pausePlayerTasks();

        CompletableFuture<TradeResult> first = exchangeService.sell(player, Material.STONE, 1);
        waitUntilQueued(scheduler);
        player.getInventory().clear();
        scheduler.resumePlayerTasks();

        assertFalse(first.join().success());
        player.getInventory().addItem(new ItemStack(Material.STONE, 1));
        assertTrue(exchangeService.sell(player, Material.STONE, 1).join().success());
    }

    private void waitUntilQueued(ControlledScheduler scheduler) {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (scheduler.queuedPlayerTasks() == 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class ControlledScheduler implements SchedulerAdapter {
        private final java.util.Queue<Runnable> playerTasks = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private volatile boolean pausePlayerTasks;

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            new Thread(task).start();
        }

        @Override
        public void runForPlayer(Player player, Runnable task) {
            if (pausePlayerTasks) {
                playerTasks.add(task);
                return;
            }
            task.run();
        }

        @Override
        public void runLaterForPlayer(Player player, Runnable task, long delayTicks) {
            runForPlayer(player, task);
        }

        private void pausePlayerTasks() {
            pausePlayerTasks = true;
        }

        private void resumePlayerTasks() {
            pausePlayerTasks = false;
            Runnable task;
            while ((task = playerTasks.poll()) != null) {
                task.run();
            }
        }

        private int queuedPlayerTasks() {
            return playerTasks.size();
        }
    }
}
