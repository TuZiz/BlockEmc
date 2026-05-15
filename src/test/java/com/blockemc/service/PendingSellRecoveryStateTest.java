package com.blockemc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import com.blockemc.service.audit.PendingSellStatus;
import com.blockemc.service.audit.PendingSellTransaction;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PendingSellRecoveryStateTest {

    private ServerMock server;
    private PluginMock plugin;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("BlockEmcRecoveryTest");
        accountService = new AccountService(
                plugin,
                new StorageSettings(StorageSettings.Type.YAML, StorageSettings.MySqlSettings.defaults()),
                settings()
        );
        accountService.reload();
    }

    @AfterEach
    void tearDown() {
        if (accountService != null) {
            accountService.shutdown();
        }
        MockBukkit.unmock();
    }

    @Test
    void pendingRemovalRecoveryMarksFailed() {
        PendingSellTransaction transaction = save(PendingSellStatus.PENDING_REMOVAL, 50L);

        accountService.recoverPendingSellForTesting(transaction);

        assertEquals(PendingSellStatus.FAILED, pendingStatus(transaction));
        assertEquals(0L, accountService.getCachedBalance(transaction.playerUuid()));
    }

    @Test
    void removingItemsRecoveryMarksManualReview() {
        PendingSellTransaction transaction = save(PendingSellStatus.REMOVING_ITEMS, 50L);

        accountService.recoverPendingSellForTesting(transaction);

        assertEquals(PendingSellStatus.MANUAL_REVIEW, pendingStatus(transaction));
        assertEquals(0L, accountService.getCachedBalance(transaction.playerUuid()));
    }

    @Test
    void itemsRemovedRecoveryCreditsAndMarksSuccess() {
        PendingSellTransaction transaction = save(PendingSellStatus.ITEMS_REMOVED, 50L);

        accountService.recoverPendingSellForTesting(transaction);

        assertEquals(PendingSellStatus.SUCCESS, pendingStatus(transaction));
        assertEquals(50L, accountService.getCachedBalance(transaction.playerUuid()));
    }

    @Test
    void creditingRecoveryMarksManualReviewWithoutDuplicateCredit() {
        PendingSellTransaction transaction = save(PendingSellStatus.CREDITING, 50L);
        accountService.setBalance(transaction.playerUuid(), transaction.playerName(), 50L).join();

        accountService.recoverPendingSellForTesting(transaction);

        assertEquals(PendingSellStatus.MANUAL_REVIEW, pendingStatus(transaction));
        assertEquals(50L, accountService.getCachedBalance(transaction.playerUuid()));
    }

    @Test
    void terminalRecoveriesAreIgnored() {
        PendingSellTransaction success = save(PendingSellStatus.SUCCESS, 50L);
        PendingSellTransaction failed = save(PendingSellStatus.FAILED, 50L);
        PendingSellTransaction review = save(PendingSellStatus.MANUAL_REVIEW, 50L);

        accountService.recoverPendingSellForTesting(success);
        accountService.recoverPendingSellForTesting(failed);
        accountService.recoverPendingSellForTesting(review);

        assertEquals(PendingSellStatus.SUCCESS, pendingStatus(success));
        assertEquals(PendingSellStatus.FAILED, pendingStatus(failed));
        assertEquals(PendingSellStatus.MANUAL_REVIEW, pendingStatus(review));
        assertEquals(0L, accountService.getCachedBalance(success.playerUuid()));
        assertEquals(0L, accountService.getCachedBalance(failed.playerUuid()));
        assertEquals(0L, accountService.getCachedBalance(review.playerUuid()));
    }

    private PendingSellTransaction save(PendingSellStatus status, long reward) {
        PendingSellTransaction transaction = transaction(status, reward);
        accountService.savePendingSell(transaction).join();
        return transaction;
    }

    private PendingSellTransaction transaction(PendingSellStatus status, long reward) {
        UUID uuid = UUID.randomUUID();
        return new PendingSellTransaction(
                UUID.randomUUID().toString(),
                uuid,
                "Seller",
                "SELL",
                Map.of("STONE", 1),
                reward,
                status,
                Instant.now(),
                Instant.now(),
                "",
                server.getName()
        );
    }

    private PendingSellStatus pendingStatus(PendingSellTransaction transaction) {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(plugin.getDataFolder().toPath().resolve("pending-transactions.yml").toFile());
        return PendingSellStatus.valueOf(configuration.getString("transactions." + transaction.transactionId() + ".status"));
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
