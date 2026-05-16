package com.blockemc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.blockemc.compat.SchedulerAdapter;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import com.blockemc.model.TradeResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class ExchangeServiceNonItemMaterialTest {

    private ServerMock server;
    private PluginMock plugin;
    private AccountService accountService;
    private ValueRegistry valueRegistry;
    private ExchangeService exchangeService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcNonItemMaterialTest");
        PluginSettings settings = settings();
        accountService = new AccountService(
                plugin,
                new StorageSettings(StorageSettings.Type.YAML, StorageSettings.MySqlSettings.defaults()),
                settings
        );
        valueRegistry = new ValueRegistry(plugin, settings);
        exchangeService = new ExchangeService(plugin, settings, valueRegistry, accountService, new ImmediateScheduler());
    }

    @AfterEach
    void tearDown() {
        if (valueRegistry != null) {
            valueRegistry.shutdown();
        }
        if (accountService != null) {
            accountService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void quoteBuyRejectsNonItemMaterialWithoutThrowing() {
        PlayerMock player = server.addPlayer("Buyer");
        accountService.notePlayer(player);

        ExchangeService.BuyQuote quote = exchangeService.quoteBuy(player, Material.CAVE_VINES, 1);

        assertFalse(quote.success());
        assertEquals("uemc-material-not-supported", quote.messageKey());
    }

    @Test
    void buyRejectsNonItemMaterialWithoutTakingBalance() {
        PlayerMock player = server.addPlayer("Buyer");
        accountService.notePlayer(player);
        accountService.setBalance(player.getUniqueId(), player.getName(), 100L).join();

        TradeResult result = exchangeService.buy(player, Material.CAVE_VINES, 1).join();

        assertFalse(result.success());
        assertEquals("uemc-material-not-supported", result.messageKey());
        assertEquals(100L, accountService.getCachedBalance(player.getUniqueId()));
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
