package com.blockemc.service.storage;

import com.blockemc.model.StorageSettings;
import com.blockemc.service.audit.PendingSellStatus;
import com.blockemc.service.audit.PendingSellTransaction;
import com.blockemc.service.audit.TransactionAuditRecord;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public final class MySqlAccountStorage implements SharedAccountStorage {

    private static final class MutableDailySaleData {
        private String date;
        private long soldEmc;
        private final Map<String, Integer> soldAmounts = new HashMap<>();

        private MutableDailySaleData(String date, long soldEmc) {
            this.date = date;
            this.soldEmc = Math.max(0L, soldEmc);
        }
    }

    private final JavaPlugin plugin;
    private final StorageSettings.MySqlSettings settings;
    private final YamlAccountStorage migrationSource;
    private final File importMarkerFile;
    private final String accountsTable;
    private final String favoritesTable;
    private final String dailySalesTable;
    private final String dailySaleMaterialsTable;
    private final String purchaseHeatTable;
    private final String auditTable;
    private final String pendingTransactionsTable;
    private final HikariDataSource dataSource;

    public MySqlAccountStorage(
            JavaPlugin plugin,
            StorageSettings.MySqlSettings settings,
            YamlAccountStorage migrationSource
    ) throws AccountStorageException {
        this.plugin = plugin;
        this.settings = settings;
        this.migrationSource = migrationSource;
        this.importMarkerFile = new File(new File(plugin.getDataFolder(), "storage"), "mysql-imported.marker");
        this.accountsTable = settings.tablePrefix() + "accounts";
        this.favoritesTable = settings.tablePrefix() + "favorites";
        this.dailySalesTable = settings.tablePrefix() + "daily_sales";
        this.dailySaleMaterialsTable = settings.tablePrefix() + "daily_sale_materials";
        this.purchaseHeatTable = settings.tablePrefix() + "purchase_heat";
        this.auditTable = settings.tablePrefix() + "audit_log";
        this.pendingTransactionsTable = settings.tablePrefix() + "pending_transactions";
        validateSettings();
        this.dataSource = createDataSource();
        try (Connection connection = openConnection()) {
            initializeSchema(connection);
        } catch (SQLException exception) {
            dataSource.close();
            throw new AccountStorageException("Failed to initialize MySQL schema", exception);
        }
    }

    @Override
    public AccountSnapshot load() throws AccountStorageException {
        importFromYamlIfNeeded();
        try (Connection connection = openConnection()) {
            return loadSnapshot(connection);
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to load account data from MySQL", exception);
        }
    }

    @Override
    public void save(AccountSnapshot snapshot) throws AccountStorageException {
        try (Connection connection = openConnection()) {
            writeSnapshot(connection, snapshot);
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to save account data to MySQL", exception);
        }
    }

    @Override
    public PlayerAccountState loadPlayer(UUID uniqueId, String fallbackName) throws AccountStorageException {
        try (Connection connection = openConnection()) {
            ensureAccountRow(connection, uniqueId, fallbackName);
            return loadPlayerState(connection, uniqueId, fallbackName);
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to load player account data from MySQL", exception);
        }
    }

    @Override
    public SharedAccountGlobalState loadGlobalState() throws AccountStorageException {
        importFromYamlIfNeeded();
        try (Connection connection = openConnection()) {
            return loadGlobalState(connection);
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to load shared account state from MySQL", exception);
        }
    }

    @Override
    public void setBalance(UUID uniqueId, String name, long amount) throws AccountStorageException {
        try (Connection connection = openConnection()) {
            String sql = "INSERT INTO " + quoted(accountsTable) + " (player_uuid, player_name, balance) VALUES (?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), balance = VALUES(balance)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uniqueId.toString());
                statement.setString(2, normalizeName(uniqueId, name));
                statement.setLong(3, Math.max(0L, amount));
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to set MySQL account balance", exception);
        }
    }

    @Override
    public boolean tryAddBalance(UUID uniqueId, String name, long amount, long maxBalance) throws AccountStorageException {
        if (amount <= 0L || amount > maxBalance) {
            return false;
        }
        try (Connection connection = openConnection()) {
            ensureAccountRow(connection, uniqueId, name);
            String sql = "UPDATE " + quoted(accountsTable)
                    + " SET player_name = ?, balance = balance + ? WHERE player_uuid = ? AND balance <= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalizeName(uniqueId, name));
                statement.setLong(2, amount);
                statement.setString(3, uniqueId.toString());
                statement.setLong(4, Math.max(0L, maxBalance - amount));
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to increment MySQL account balance", exception);
        }
    }

    @Override
    public boolean tryTakeBalance(UUID uniqueId, String name, long amount) throws AccountStorageException {
        if (amount <= 0L) {
            return false;
        }
        try (Connection connection = openConnection()) {
            ensureAccountRow(connection, uniqueId, name);
            String sql = "UPDATE " + quoted(accountsTable)
                    + " SET player_name = ?, balance = balance - ? WHERE player_uuid = ? AND balance >= ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalizeName(uniqueId, name));
                statement.setLong(2, amount);
                statement.setString(3, uniqueId.toString());
                statement.setLong(4, amount);
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to decrement MySQL account balance", exception);
        }
    }

    @Override
    public long getBalance(UUID uniqueId) throws AccountStorageException {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT balance FROM " + quoted(accountsTable) + " WHERE player_uuid = ?"
             )) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Math.max(0L, resultSet.getLong("balance")) : 0L;
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to read MySQL account balance", exception);
        }
    }

    @Override
    public void setFavorite(UUID uniqueId, String name, Material material, boolean favorite) throws AccountStorageException {
        if (material == null) {
            return;
        }
        try (Connection connection = openConnection()) {
            ensureAccountRow(connection, uniqueId, name);
            if (favorite) {
                String sql = "INSERT INTO " + quoted(favoritesTable) + " (player_uuid, material) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE material = VALUES(material)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, uniqueId.toString());
                    statement.setString(2, material.name());
                    statement.executeUpdate();
                }
                return;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + quoted(favoritesTable) + " WHERE player_uuid = ? AND material = ?"
            )) {
                statement.setString(1, uniqueId.toString());
                statement.setString(2, material.name());
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to update MySQL favorites", exception);
        }
    }

    @Override
    public void recordSale(
            UUID uniqueId,
            String name,
            Map<Material, Integer> soldMaterials,
            long soldEmc,
            String saleDate
    ) throws AccountStorageException {
        if (soldMaterials == null || soldMaterials.isEmpty() || soldEmc <= 0L) {
            return;
        }
        String normalizedDate = normalizeDate(saleDate);
        try (Connection connection = openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                ensureAccountRow(connection, uniqueId, name);
                String currentDate = selectDailySaleDate(connection, uniqueId);
                if (currentDate != null && !currentDate.equals(normalizedDate)) {
                    deleteDailySaleMaterials(connection, uniqueId);
                }
                upsertDailySale(connection, uniqueId, normalizedDate, soldEmc);
                insertDailySaleMaterials(connection, uniqueId, normalizedDate, soldMaterials);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to record MySQL sale statistics", exception);
        }
    }

    @Override
    public void recordPurchase(UUID uniqueId, String name, Material material, int amount) throws AccountStorageException {
        if (material == null || amount <= 0) {
            return;
        }
        try (Connection connection = openConnection()) {
            ensureAccountRow(connection, uniqueId, name);
            String sql = "INSERT INTO " + quoted(purchaseHeatTable) + " (material, purchases) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE purchases = purchases + VALUES(purchases)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, material.name());
                statement.setInt(2, amount);
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to record MySQL purchase heat", exception);
        }
    }

    @Override
    public void recordAudit(TransactionAuditRecord record) throws AccountStorageException {
        String sql = "INSERT INTO " + quoted(auditTable) + " ("
                + "transaction_id, player_uuid, player_name, operation_type, material, amount, unit_price, total_price, "
                + "before_balance, after_balance, storage_type, success, failure_reason, event_time, server_name"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.transactionId());
            statement.setString(2, record.playerUuid() == null ? "" : record.playerUuid().toString());
            statement.setString(3, record.playerName());
            statement.setString(4, record.operationType());
            statement.setString(5, record.material() == null ? "" : record.material().name());
            statement.setInt(6, record.amount());
            statement.setLong(7, record.unitPrice());
            statement.setLong(8, record.totalPrice());
            statement.setLong(9, record.beforeBalance());
            statement.setLong(10, record.afterBalance());
            statement.setString(11, record.storageType());
            statement.setBoolean(12, record.success());
            statement.setString(13, record.failureReason());
            statement.setString(14, record.timestamp().toString());
            statement.setString(15, record.serverName());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to write MySQL audit log", exception);
        }
    }

    @Override
    public void savePendingSell(PendingSellTransaction transaction) throws AccountStorageException {
        String sql = "INSERT INTO " + quoted(pendingTransactionsTable) + " ("
                + "transaction_id, player_uuid, player_name, operation_type, materials, reward, status, created_at, updated_at, failure_reason"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE status = VALUES(status), updated_at = VALUES(updated_at), failure_reason = VALUES(failure_reason)";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, transaction.transactionId());
            statement.setString(2, transaction.playerUuid().toString());
            statement.setString(3, normalizeName(transaction.playerUuid(), transaction.playerName()));
            statement.setString(4, transaction.operationType());
            statement.setString(5, serializeMaterials(transaction.materials()));
            statement.setLong(6, transaction.reward());
            statement.setString(7, transaction.status().name());
            statement.setString(8, transaction.createdAt().toString());
            statement.setString(9, transaction.updatedAt().toString());
            statement.setString(10, transaction.failureReason());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to save MySQL pending sell transaction", exception);
        }
    }

    @Override
    public void updatePendingSellStatus(String transactionId, PendingSellStatus status, String reason) throws AccountStorageException {
        String sql = "UPDATE " + quoted(pendingTransactionsTable)
                + " SET status = ?, updated_at = ?, failure_reason = ? WHERE transaction_id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, java.time.Instant.now().toString());
            statement.setString(3, reason == null ? "" : reason);
            statement.setString(4, transactionId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to update MySQL pending sell transaction", exception);
        }
    }

    @Override
    public List<PendingSellTransaction> loadOpenPendingSells() throws AccountStorageException {
        String sql = "SELECT transaction_id, player_uuid, player_name, operation_type, materials, reward, status, created_at, updated_at, failure_reason "
                + "FROM " + quoted(pendingTransactionsTable) + " WHERE status IN (?, ?)";
        List<PendingSellTransaction> transactions = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PendingSellStatus.PENDING_REMOVAL.name());
            statement.setString(2, PendingSellStatus.ITEMS_REMOVED.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uniqueId = parseUuid(resultSet.getString("player_uuid"));
                    if (uniqueId == null) {
                        continue;
                    }
                    transactions.add(new PendingSellTransaction(
                            resultSet.getString("transaction_id"),
                            uniqueId,
                            resultSet.getString("player_name"),
                            resultSet.getString("operation_type"),
                            deserializeMaterials(resultSet.getString("materials")),
                            resultSet.getLong("reward"),
                            PendingSellStatus.valueOf(resultSet.getString("status")),
                            parseInstant(resultSet.getString("created_at")),
                            parseInstant(resultSet.getString("updated_at")),
                            resultSet.getString("failure_reason")
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to load MySQL pending sell transactions", exception);
        }
        return transactions;
    }

    @Override
    public void importFromYamlIfNeeded() throws AccountStorageException {
        if (!settings.importFromYamlOnFirstRun() || hasImportMarker() || !migrationSource.exists()) {
            return;
        }

        AccountSnapshot snapshot = migrationSource.load();
        try (Connection connection = openConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                mergeImportedSnapshot(connection, snapshot);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new AccountStorageException("Failed to merge local accounts.yml into MySQL storage", exception);
        }

        writeImportMarker();
        if (snapshot.isEmpty()) {
            plugin.getLogger().info("Checked local accounts.yml for MySQL import and found no legacy account data.");
            return;
        }
        plugin.getLogger().info("Merged existing accounts.yml data into MySQL storage.");
    }

    private void validateSettings() throws AccountStorageException {
        if (settings.database().isBlank()) {
            throw new AccountStorageException("MySQL database name cannot be empty");
        }
        if (settings.username().isBlank()) {
            throw new AccountStorageException("MySQL username cannot be empty");
        }
        warnIfInsecureSettings();
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setPoolName("BlockEmc-MySQL");
        config.setMaximumPoolSize(settings.pool().maximumPoolSize());
        config.setMinimumIdle(settings.pool().minimumIdle());
        config.setConnectionTimeout(settings.pool().connectionTimeoutMs());
        config.setIdleTimeout(settings.pool().idleTimeoutMs());
        config.setMaxLifetime(settings.pool().maxLifetimeMs());
        config.setInitializationFailTimeout(-1L);
        return new HikariDataSource(config);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private void warnIfInsecureSettings() throws AccountStorageException {
        if ("root".equalsIgnoreCase(settings.username())) {
            plugin.getLogger().warning("MySQL username is root; use a dedicated least-privilege database user.");
        }
        if (settings.password().isBlank() || "CHANGE_ME".equals(settings.password())) {
            throw new AccountStorageException("MySQL password is empty or unchanged; refusing to connect.");
        }
        if (!settings.useSsl()) {
            plugin.getLogger().warning("MySQL use-ssl is false; enable TLS for production servers.");
        }
        if (settings.allowPublicKeyRetrieval()) {
            plugin.getLogger().warning("MySQL allow-public-key-retrieval is true; disable it unless strictly required.");
        }
    }

    private Connection openConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                        player_name VARCHAR(32) NOT NULL,
                        balance BIGINT NOT NULL
                    )
                    """.formatted(quoted(accountsTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        PRIMARY KEY (player_uuid, material)
                    )
                    """.formatted(quoted(favoritesTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                        sale_date VARCHAR(16) NOT NULL,
                        sold_emc BIGINT NOT NULL
                    )
                    """.formatted(quoted(dailySalesTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        player_uuid CHAR(36) NOT NULL,
                        sale_date VARCHAR(16) NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        amount INT NOT NULL,
                        PRIMARY KEY (player_uuid, sale_date, material)
                    )
                    """.formatted(quoted(dailySaleMaterialsTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        material VARCHAR(64) NOT NULL PRIMARY KEY,
                        purchases INT NOT NULL
                    )
                    """.formatted(quoted(purchaseHeatTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        operation_type VARCHAR(32) NOT NULL,
                        material VARCHAR(64) NOT NULL,
                        amount INT NOT NULL,
                        unit_price BIGINT NOT NULL,
                        total_price BIGINT NOT NULL,
                        before_balance BIGINT NOT NULL,
                        after_balance BIGINT NOT NULL,
                        storage_type VARCHAR(32) NOT NULL,
                        success BOOLEAN NOT NULL,
                        failure_reason VARCHAR(255) NOT NULL,
                        event_time VARCHAR(64) NOT NULL,
                        server_name VARCHAR(64) NOT NULL
                    )
                    """.formatted(quoted(auditTable)));
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        transaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        player_uuid CHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        operation_type VARCHAR(32) NOT NULL,
                        materials TEXT NOT NULL,
                        reward BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        created_at VARCHAR(64) NOT NULL,
                        updated_at VARCHAR(64) NOT NULL,
                        failure_reason VARCHAR(255) NOT NULL
                    )
                    """.formatted(quoted(pendingTransactionsTable)));
        }
    }

    private PlayerAccountState loadPlayerState(Connection connection, UUID uniqueId, String fallbackName) throws SQLException {
        String name = fallbackName;
        long balance = 0L;

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name, balance FROM " + quoted(accountsTable) + " WHERE player_uuid = ?"
        )) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    name = normalizeName(uniqueId, resultSet.getString("player_name"));
                    balance = Math.max(0L, resultSet.getLong("balance"));
                }
            }
        }

        Set<Material> favorites = loadFavorites(connection, uniqueId);
        AccountSnapshot.DailySaleSnapshot dailySale = loadDailySale(connection, uniqueId);
        return new PlayerAccountState(name, balance, favorites, dailySale);
    }

    private SharedAccountGlobalState loadGlobalState(Connection connection) throws SQLException {
        Map<UUID, Long> balances = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, MutableDailySaleData> dailySales = new HashMap<>();
        Map<String, Integer> purchaseHeat = new HashMap<>();

        loadAccounts(connection, balances, names);
        loadDailySales(connection, dailySales);
        loadDailySaleMaterials(connection, dailySales);
        loadPurchaseHeat(connection, purchaseHeat);

        Map<UUID, AccountSnapshot.DailySaleSnapshot> immutableDailySales = new HashMap<>();
        dailySales.forEach((uuid, data) -> immutableDailySales.put(
                uuid,
                new AccountSnapshot.DailySaleSnapshot(data.date, data.soldEmc, data.soldAmounts)
        ));
        return new SharedAccountGlobalState(balances, names, immutableDailySales, purchaseHeat);
    }

    private AccountSnapshot loadSnapshot(Connection connection) throws SQLException {
        Map<UUID, Long> balances = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Set<Material>> favorites = new HashMap<>();
        Map<UUID, MutableDailySaleData> dailySales = new HashMap<>();
        Map<String, Integer> purchaseHeat = new HashMap<>();

        loadAccounts(connection, balances, names);
        loadFavorites(connection, favorites);
        loadDailySales(connection, dailySales);
        loadDailySaleMaterials(connection, dailySales);
        loadPurchaseHeat(connection, purchaseHeat);

        Map<UUID, AccountSnapshot.DailySaleSnapshot> immutableDailySales = new HashMap<>();
        dailySales.forEach((uuid, data) -> immutableDailySales.put(
                uuid,
                new AccountSnapshot.DailySaleSnapshot(data.date, data.soldEmc, data.soldAmounts)
        ));
        return new AccountSnapshot(balances, names, favorites, immutableDailySales, purchaseHeat);
    }

    private void loadAccounts(Connection connection, Map<UUID, Long> balances, Map<UUID, String> names) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, player_name, balance FROM " + quoted(accountsTable)
        )) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uniqueId = parseUuid(resultSet.getString("player_uuid"));
                    if (uniqueId == null) {
                        continue;
                    }
                    balances.put(uniqueId, Math.max(0L, resultSet.getLong("balance")));
                    names.put(uniqueId, normalizeName(uniqueId, resultSet.getString("player_name")));
                }
            }
        }
    }

    private void loadFavorites(Connection connection, Map<UUID, Set<Material>> favorites) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, material FROM " + quoted(favoritesTable)
        )) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uniqueId = parseUuid(resultSet.getString("player_uuid"));
                    Material material = Material.matchMaterial(resultSet.getString("material"));
                    if (uniqueId == null || material == null) {
                        continue;
                    }
                    favorites.computeIfAbsent(uniqueId, ignored -> EnumSet.noneOf(Material.class)).add(material);
                }
            }
        }
    }

    private Set<Material> loadFavorites(Connection connection, UUID uniqueId) throws SQLException {
        Set<Material> favorites = EnumSet.noneOf(Material.class);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT material FROM " + quoted(favoritesTable) + " WHERE player_uuid = ?"
        )) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Material material = Material.matchMaterial(resultSet.getString("material"));
                    if (material != null) {
                        favorites.add(material);
                    }
                }
            }
        }
        return favorites;
    }

    private void loadDailySales(Connection connection, Map<UUID, MutableDailySaleData> dailySales) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, sale_date, sold_emc FROM " + quoted(dailySalesTable)
        )) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uniqueId = parseUuid(resultSet.getString("player_uuid"));
                    if (uniqueId == null) {
                        continue;
                    }
                    dailySales.put(
                            uniqueId,
                            new MutableDailySaleData(
                                    normalizeDate(resultSet.getString("sale_date")),
                                    resultSet.getLong("sold_emc")
                            )
                    );
                }
            }
        }
    }

    private AccountSnapshot.DailySaleSnapshot loadDailySale(Connection connection, UUID uniqueId) throws SQLException {
        String sql = "SELECT sale_date, sold_emc FROM " + quoted(dailySalesTable) + " WHERE player_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new AccountSnapshot.DailySaleSnapshot(LocalDate.now().toString(), 0L, Map.of());
                }
                String saleDate = normalizeDate(resultSet.getString("sale_date"));
                long soldEmc = Math.max(0L, resultSet.getLong("sold_emc"));
                Map<String, Integer> soldAmounts = loadDailySaleMaterials(connection, uniqueId, saleDate);
                return new AccountSnapshot.DailySaleSnapshot(saleDate, soldEmc, soldAmounts);
            }
        }
    }

    private void loadDailySaleMaterials(Connection connection, Map<UUID, MutableDailySaleData> dailySales) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, sale_date, material, amount FROM " + quoted(dailySaleMaterialsTable)
        )) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    UUID uniqueId = parseUuid(resultSet.getString("player_uuid"));
                    if (uniqueId == null) {
                        continue;
                    }
                    MutableDailySaleData data = dailySales.get(uniqueId);
                    if (data == null) {
                        continue;
                    }
                    String saleDate = normalizeDate(resultSet.getString("sale_date"));
                    if (!Objects.equals(data.date, saleDate)) {
                        continue;
                    }
                    String material = resultSet.getString("material");
                    if (Material.matchMaterial(material) == null) {
                        continue;
                    }
                    int amount = Math.max(0, resultSet.getInt("amount"));
                    if (amount > 0) {
                        data.soldAmounts.put(material, amount);
                    }
                }
            }
        }
    }

    private Map<String, Integer> loadDailySaleMaterials(Connection connection, UUID uniqueId, String saleDate) throws SQLException {
        Map<String, Integer> soldAmounts = new HashMap<>();
        String sql = "SELECT material, amount FROM " + quoted(dailySaleMaterialsTable)
                + " WHERE player_uuid = ? AND sale_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, saleDate);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String material = resultSet.getString("material");
                    if (Material.matchMaterial(material) == null) {
                        continue;
                    }
                    int amount = Math.max(0, resultSet.getInt("amount"));
                    if (amount > 0) {
                        soldAmounts.put(material, amount);
                    }
                }
            }
        }
        return soldAmounts;
    }

    private void loadPurchaseHeat(Connection connection, Map<String, Integer> purchaseHeat) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT material, purchases FROM " + quoted(purchaseHeatTable)
        )) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String material = resultSet.getString("material");
                    if (Material.matchMaterial(material) == null) {
                        continue;
                    }
                    int purchases = Math.max(0, resultSet.getInt("purchases"));
                    if (purchases > 0) {
                        purchaseHeat.put(material, purchases);
                    }
                }
            }
        }
    }

    private void mergeImportedSnapshot(Connection connection, AccountSnapshot snapshot) throws SQLException {
        for (Map.Entry<UUID, Long> entry : snapshot.balances().entrySet()) {
            upsertImportedBalance(connection, entry.getKey(), snapshot.names().get(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<UUID, String> entry : snapshot.names().entrySet()) {
            if (snapshot.balances().containsKey(entry.getKey())) {
                continue;
            }
            upsertImportedBalance(connection, entry.getKey(), entry.getValue(), 0L);
        }
        for (Map.Entry<UUID, Set<Material>> entry : snapshot.favorites().entrySet()) {
            for (Material material : entry.getValue()) {
                insertImportedFavorite(connection, entry.getKey(), material);
            }
        }
        for (Map.Entry<UUID, AccountSnapshot.DailySaleSnapshot> entry : snapshot.dailySales().entrySet()) {
            mergeImportedDailySale(connection, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : snapshot.purchaseHeat().entrySet()) {
            incrementPurchaseHeat(connection, entry.getKey(), entry.getValue());
        }
    }

    private void upsertImportedBalance(Connection connection, UUID uniqueId, String name, long balance) throws SQLException {
        String sql = "INSERT INTO " + quoted(accountsTable) + " (player_uuid, player_name, balance) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE "
                + "player_name = CASE WHEN player_name IS NULL OR player_name = '' THEN VALUES(player_name) ELSE player_name END, "
                + "balance = GREATEST(balance, VALUES(balance))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, normalizeName(uniqueId, name));
            statement.setLong(3, Math.max(0L, balance));
            statement.executeUpdate();
        }
    }

    private void insertImportedFavorite(Connection connection, UUID uniqueId, Material material) throws SQLException {
        if (material == null) {
            return;
        }
        String sql = "INSERT INTO " + quoted(favoritesTable) + " (player_uuid, material) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE material = VALUES(material)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, material.name());
            statement.executeUpdate();
        }
    }

    private void mergeImportedDailySale(Connection connection, UUID uniqueId, AccountSnapshot.DailySaleSnapshot snapshot) throws SQLException {
        if (snapshot == null || (snapshot.soldEmc() <= 0L && snapshot.soldAmounts().isEmpty())) {
            return;
        }
        String importedDate = normalizeDate(snapshot.date());
        String existingDate = selectDailySaleDate(connection, uniqueId);
        if (existingDate == null) {
            setImportedDailySale(connection, uniqueId, importedDate, snapshot.soldEmc(), snapshot.soldAmounts());
            return;
        }
        if (existingDate.equals(importedDate)) {
            upsertDailySale(connection, uniqueId, importedDate, snapshot.soldEmc());
            insertImportedDailySaleMaterials(connection, uniqueId, importedDate, snapshot.soldAmounts());
            return;
        }
        if (isDateAfter(importedDate, existingDate)) {
            deleteDailySaleMaterials(connection, uniqueId);
            setImportedDailySale(connection, uniqueId, importedDate, snapshot.soldEmc(), snapshot.soldAmounts());
        }
    }

    private void setImportedDailySale(
            Connection connection,
            UUID uniqueId,
            String saleDate,
            long soldEmc,
            Map<String, Integer> soldAmounts
    ) throws SQLException {
        String sql = "INSERT INTO " + quoted(dailySalesTable) + " (player_uuid, sale_date, sold_emc) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE sale_date = VALUES(sale_date), sold_emc = VALUES(sold_emc)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, saleDate);
            statement.setLong(3, Math.max(0L, soldEmc));
            statement.executeUpdate();
        }
        insertImportedDailySaleMaterials(connection, uniqueId, saleDate, soldAmounts);
    }

    private void insertImportedDailySaleMaterials(
            Connection connection,
            UUID uniqueId,
            String saleDate,
            Map<String, Integer> soldAmounts
    ) throws SQLException {
        if (soldAmounts == null || soldAmounts.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(dailySaleMaterialsTable)
                + " (player_uuid, sale_date, material, amount) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : soldAmounts.entrySet()) {
                if (Material.matchMaterial(entry.getKey()) == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                statement.setString(1, uniqueId.toString());
                statement.setString(2, saleDate);
                statement.setString(3, entry.getKey());
                statement.setInt(4, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void incrementPurchaseHeat(Connection connection, String material, int amount) throws SQLException {
        if (Material.matchMaterial(material) == null || amount <= 0) {
            return;
        }
        String sql = "INSERT INTO " + quoted(purchaseHeatTable) + " (material, purchases) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE purchases = purchases + VALUES(purchases)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, material);
            statement.setInt(2, amount);
            statement.executeUpdate();
        }
    }

    private void ensureAccountRow(Connection connection, UUID uniqueId, String name) throws SQLException {
        String sql = "INSERT INTO " + quoted(accountsTable) + " (player_uuid, player_name, balance) VALUES (?, ?, 0) "
                + "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, normalizeName(uniqueId, name));
            statement.executeUpdate();
        }
    }

    private void upsertDailySale(Connection connection, UUID uniqueId, String saleDate, long soldEmc) throws SQLException {
        String sql = "INSERT INTO " + quoted(dailySalesTable) + " (player_uuid, sale_date, sold_emc) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE sale_date = VALUES(sale_date), "
                + "sold_emc = CASE WHEN sale_date = VALUES(sale_date) THEN sold_emc + VALUES(sold_emc) ELSE VALUES(sold_emc) END";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId.toString());
            statement.setString(2, saleDate);
            statement.setLong(3, Math.max(0L, soldEmc));
            statement.executeUpdate();
        }
    }

    private void insertDailySaleMaterials(
            Connection connection,
            UUID uniqueId,
            String saleDate,
            Map<Material, Integer> soldMaterials
    ) throws SQLException {
        String sql = "INSERT INTO " + quoted(dailySaleMaterialsTable)
                + " (player_uuid, sale_date, material, amount) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<Material, Integer> entry : soldMaterials.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                    continue;
                }
                statement.setString(1, uniqueId.toString());
                statement.setString(2, saleDate);
                statement.setString(3, entry.getKey().name());
                statement.setInt(4, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private String selectDailySaleDate(Connection connection, UUID uniqueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sale_date FROM " + quoted(dailySalesTable) + " WHERE player_uuid = ?"
        )) {
            statement.setString(1, uniqueId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return normalizeDate(resultSet.getString("sale_date"));
            }
        }
    }

    private void deleteDailySaleMaterials(Connection connection, UUID uniqueId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + quoted(dailySaleMaterialsTable) + " WHERE player_uuid = ?"
        )) {
            statement.setString(1, uniqueId.toString());
            statement.executeUpdate();
        }
    }

    private void writeSnapshot(Connection connection, AccountSnapshot snapshot) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            clearTable(connection, dailySaleMaterialsTable);
            clearTable(connection, dailySalesTable);
            clearTable(connection, favoritesTable);
            clearTable(connection, purchaseHeatTable);
            clearTable(connection, accountsTable);
            insertAccounts(connection, snapshot);
            insertFavorites(connection, snapshot);
            insertDailySales(connection, snapshot);
            insertDailySaleMaterials(connection, snapshot);
            insertPurchaseHeat(connection, snapshot);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private void clearTable(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + quoted(table))) {
            statement.executeUpdate();
        }
    }

    private void insertAccounts(Connection connection, AccountSnapshot snapshot) throws SQLException {
        if (snapshot.balances().isEmpty() && snapshot.names().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(accountsTable) + " (player_uuid, player_name, balance) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            Map<UUID, String> names = snapshot.names();
            for (Map.Entry<UUID, Long> entry : snapshot.balances().entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setString(2, normalizeName(entry.getKey(), names.get(entry.getKey())));
                statement.setLong(3, Math.max(0L, entry.getValue()));
                statement.addBatch();
            }
            for (Map.Entry<UUID, String> entry : names.entrySet()) {
                if (snapshot.balances().containsKey(entry.getKey())) {
                    continue;
                }
                statement.setString(1, entry.getKey().toString());
                statement.setString(2, normalizeName(entry.getKey(), entry.getValue()));
                statement.setLong(3, 0L);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertFavorites(Connection connection, AccountSnapshot snapshot) throws SQLException {
        if (snapshot.favorites().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(favoritesTable) + " (player_uuid, material) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<UUID, Set<Material>> entry : snapshot.favorites().entrySet()) {
                for (Material material : entry.getValue()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setString(2, material.name());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void insertDailySales(Connection connection, AccountSnapshot snapshot) throws SQLException {
        if (snapshot.dailySales().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(dailySalesTable) + " (player_uuid, sale_date, sold_emc) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<UUID, AccountSnapshot.DailySaleSnapshot> entry : snapshot.dailySales().entrySet()) {
                statement.setString(1, entry.getKey().toString());
                statement.setString(2, normalizeDate(entry.getValue().date()));
                statement.setLong(3, entry.getValue().soldEmc());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertDailySaleMaterials(Connection connection, AccountSnapshot snapshot) throws SQLException {
        if (snapshot.dailySales().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(dailySaleMaterialsTable)
                + " (player_uuid, sale_date, material, amount) VALUES (?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<UUID, AccountSnapshot.DailySaleSnapshot> entry : snapshot.dailySales().entrySet()) {
                for (Map.Entry<String, Integer> materialEntry : entry.getValue().soldAmounts().entrySet()) {
                    if (Material.matchMaterial(materialEntry.getKey()) == null || materialEntry.getValue() <= 0) {
                        continue;
                    }
                    statement.setString(1, entry.getKey().toString());
                    statement.setString(2, normalizeDate(entry.getValue().date()));
                    statement.setString(3, materialEntry.getKey());
                    statement.setInt(4, materialEntry.getValue());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    private void insertPurchaseHeat(Connection connection, AccountSnapshot snapshot) throws SQLException {
        if (snapshot.purchaseHeat().isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + quoted(purchaseHeatTable) + " (material, purchases) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, Integer> entry : snapshot.purchaseHeat().entrySet()) {
                if (Material.matchMaterial(entry.getKey()) == null || entry.getValue() <= 0) {
                    continue;
                }
                statement.setString(1, entry.getKey());
                statement.setInt(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private boolean hasImportMarker() {
        return importMarkerFile.isFile();
    }

    private void writeImportMarker() throws AccountStorageException {
        File parent = importMarkerFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new AccountStorageException("Failed to create storage directory for MySQL import marker");
        }
        try {
            Files.writeString(importMarkerFile.toPath(), "imported=true");
        } catch (IOException exception) {
            throw new AccountStorageException("Failed to write MySQL import marker", exception);
        }
    }

    private boolean isDateAfter(String left, String right) {
        try {
            return LocalDate.parse(left).isAfter(LocalDate.parse(right));
        } catch (DateTimeParseException ignored) {
            return left.compareTo(right) > 0;
        }
    }

    private String normalizeDate(String value) {
        return (value == null || value.isBlank()) ? LocalDate.now().toString() : value.trim();
    }

    private String normalizeName(UUID uniqueId, String value) {
        if (value == null || value.isBlank()) {
            return uniqueId.toString();
        }
        String trimmed = value.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }

    private String serializeMaterials(Map<String, Integer> materials) {
        if (materials == null || materials.isEmpty()) {
            return "";
        }
        return materials.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }

    private Map<String, Integer> deserializeMaterials(String value) {
        Map<String, Integer> materials = new HashMap<>();
        if (value == null || value.isBlank()) {
            return materials;
        }
        for (String part : value.split(",")) {
            String[] pieces = part.split(":", 2);
            if (pieces.length != 2 || Material.matchMaterial(pieces[0]) == null) {
                continue;
            }
            try {
                int amount = Integer.parseInt(pieces[1]);
                if (amount > 0) {
                    materials.put(pieces[0], amount);
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Skipping invalid pending transaction material amount: " + part);
            }
        }
        return materials;
    }

    private java.time.Instant parseInstant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException exception) {
            return java.time.Instant.now();
        }
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Skipping invalid UUID from MySQL storage: " + value);
            return null;
        }
    }

    private String quoted(String table) {
        return "`" + table + "`";
    }
}
