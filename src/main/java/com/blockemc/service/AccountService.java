package com.blockemc.service;

import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import com.blockemc.service.audit.PendingSellStatus;
import com.blockemc.service.audit.PendingSellTransaction;
import com.blockemc.service.audit.PendingSellRecoveryAction;
import com.blockemc.service.audit.PendingSellRecoveryPolicy;
import com.blockemc.service.audit.TransactionAuditRecord;
import com.blockemc.service.storage.AccountSnapshot;
import com.blockemc.service.storage.AccountStorage;
import com.blockemc.service.storage.AccountStorageException;
import com.blockemc.service.storage.MySqlAccountStorage;
import com.blockemc.service.storage.PendingCreditResult;
import com.blockemc.service.storage.PlayerAccountState;
import com.blockemc.service.storage.SharedAccountGlobalState;
import com.blockemc.service.storage.SharedAccountStorage;
import com.blockemc.service.storage.YamlAccountStorage;
import com.blockemc.util.AmountUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class AccountService {

    public record LeaderboardEntry(String name, long balance) {
    }

    public record DailyLeaderboardEntry(String name, long soldEmc, Material topMaterial, int topMaterialAmount) {
    }

    public record DailySaleSummary(long soldEmc, Material topMaterial, int topMaterialAmount, int favoriteCount) {
    }

    public record HotMaterialEntry(Material material, int purchases) {
    }

    private interface SharedStorageAction {

        void execute(SharedAccountStorage storage) throws AccountStorageException;
    }

    private static final class DailySaleData {
        private String date;
        private long soldEmc;
        private final Map<String, Integer> soldAmounts = new HashMap<>();

        private DailySaleData(String date) {
            this.date = date;
        }

        private void reset(String date) {
            this.date = date;
            this.soldEmc = 0L;
            this.soldAmounts.clear();
        }
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Material>> favorites = new ConcurrentHashMap<>();
    private final Map<UUID, DailySaleData> dailySales = new ConcurrentHashMap<>();
    private final Map<String, Integer> purchaseHeat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> globalBalances = new ConcurrentHashMap<>();
    private final Map<UUID, String> globalNames = new ConcurrentHashMap<>();
    private final Map<UUID, AccountSnapshot.DailySaleSnapshot> globalDailySales = new ConcurrentHashMap<>();
    private final Map<String, Integer> globalPurchaseHeat = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlockEmc-Account-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private volatile StorageSettings storageSettings;
    private volatile PluginSettings pluginSettings;
    private volatile AccountStorage storage;
    private volatile ScheduledFuture<?> backgroundTask;
    private volatile boolean backgroundTaskStarted;

    public AccountService(JavaPlugin plugin, StorageSettings storageSettings, PluginSettings pluginSettings) {
        this.plugin = plugin;
        this.storageSettings = storageSettings;
        this.pluginSettings = pluginSettings;
        this.storage = createStorage(storageSettings);
    }

    public void applyPluginSettings(PluginSettings pluginSettings) {
        this.pluginSettings = pluginSettings;
    }

    public synchronized void applyStorageSettings(StorageSettings newSettings) {
        if (newSettings == null || newSettings.equals(this.storageSettings)) {
            return;
        }

        cancelBackgroundTask();
        awaitExecutorIdle();

        AccountStorage previous = this.storage;
        StorageSettings previousSettings = this.storageSettings;

        if (!(previous instanceof SharedAccountStorage)) {
            saveSync();
        }

        AccountStorage candidate = createStorage(newSettings);
        try {
            if (candidate instanceof SharedAccountStorage sharedCandidate) {
                sharedCandidate.importFromYamlIfNeeded();
            } else if (previous instanceof SharedAccountStorage) {
                candidate.save(previous.load());
            } else {
                candidate.save(snapshotLocked());
            }
        } catch (AccountStorageException | RuntimeException exception) {
            closeQuietly(candidate);
            this.storage = previous;
            this.storageSettings = previousSettings;
            if (backgroundTaskStarted) {
                scheduleBackgroundTask();
            }
            throw new IllegalStateException("Failed to switch account storage to " + newSettings.describe(), exception);
        }

        this.storage = candidate;
        this.storageSettings = newSettings;
        this.dirty.set(false);
        closeQuietly(previous);

        if (backgroundTaskStarted) {
            scheduleBackgroundTask();
        }
    }

    public String getStorageDescription() {
        return storageSettings.describe();
    }

    public void reload() {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage != null) {
            reloadShared(sharedStorage);
            return;
        }
        reloadLocal();
    }

    public void startAutoSave() {
        backgroundTaskStarted = true;
        scheduleBackgroundTask();
    }

    public void shutdown() {
        cancelBackgroundTask();
        if (sharedStorage() == null) {
            saveSync();
        } else {
            awaitExecutorIdle();
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for account storage executor to stop cleanly.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(storage);
    }

    public synchronized void notePlayer(Player player) {
        UUID uniqueId = player.getUniqueId();
        String name = player.getName();
        names.put(uniqueId, name);
        globalNames.put(uniqueId, name);

        if (sharedStorage() != null) {
            balances.putIfAbsent(uniqueId, globalBalances.getOrDefault(uniqueId, 0L));
            favorites.computeIfAbsent(uniqueId, ignored -> EnumSet.noneOf(Material.class));
            dailySales.computeIfAbsent(uniqueId, ignored -> new DailySaleData(todayKey()));
            return;
        }

        balances.putIfAbsent(uniqueId, 0L);
        favorites.computeIfAbsent(uniqueId, ignored -> EnumSet.noneOf(Material.class));
        dailySales.computeIfAbsent(uniqueId, ignored -> new DailySaleData(todayKey()));
        copyLocalToGlobalLocked();
        dirty.set(true);
    }

    public void preloadPlayer(UUID uniqueId, String name) {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage == null || uniqueId == null) {
            return;
        }
        try {
            PlayerAccountState state = sharedStorage.loadPlayer(uniqueId, name);
            synchronized (this) {
                applySharedPlayerStateLocked(uniqueId, state, name);
            }
        } catch (AccountStorageException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to preload shared account data for " + uniqueId, exception);
            synchronized (this) {
                names.put(uniqueId, name);
                globalNames.put(uniqueId, name);
                balances.putIfAbsent(uniqueId, globalBalances.getOrDefault(uniqueId, 0L));
                favorites.computeIfAbsent(uniqueId, ignored -> EnumSet.noneOf(Material.class));
                dailySales.computeIfAbsent(uniqueId, ignored -> new DailySaleData(todayKey()));
            }
        }
    }

    public synchronized void handleQuit(UUID uniqueId) {
        if (uniqueId == null || sharedStorage() == null) {
            return;
        }
        balances.remove(uniqueId);
        names.remove(uniqueId);
        favorites.remove(uniqueId);
        dailySales.remove(uniqueId);
    }

    public CompletableFuture<Long> getBalance(UUID uniqueId) {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage == null) {
            return CompletableFuture.completedFuture(getCachedBalance(uniqueId));
        }
        return supplyAccountIO(() -> {
            try {
                long balance = sharedStorage.getBalance(uniqueId);
                synchronized (this) {
                    balances.put(uniqueId, balance);
                    globalBalances.put(uniqueId, balance);
                }
                return balance;
            } catch (AccountStorageException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public synchronized long getCachedBalance(UUID uniqueId) {
        return balances.getOrDefault(uniqueId, globalBalances.getOrDefault(uniqueId, 0L));
    }

    public CompletableFuture<Boolean> setBalance(UUID uniqueId, long amount) {
        String name = names.getOrDefault(uniqueId, globalNames.getOrDefault(uniqueId, uniqueId.toString()));
        return setBalance(uniqueId, name, amount);
    }

    public CompletableFuture<Boolean> setBalance(UUID uniqueId, String name, long amount) {
        long normalized = normalizeBalanceAmount(amount);
        return supplyAccountIO(() -> {
            SharedAccountStorage sharedStorage = sharedStorage();
            try {
                if (sharedStorage != null) {
                    sharedStorage.setBalance(uniqueId, name, normalized);
                }
                synchronized (this) {
                    AccountSnapshot before = sharedStorage == null ? snapshotLocked() : null;
                    names.put(uniqueId, name);
                    balances.put(uniqueId, normalized);
                    globalNames.put(uniqueId, name);
                    globalBalances.put(uniqueId, normalized);
                    if (sharedStorage == null) {
                        try {
                            copyLocalToGlobalLocked();
                            saveLocalSnapshotLocked();
                        } catch (AccountStorageException exception) {
                            populateLocalStateLocked(before);
                            copyLocalToGlobalLocked();
                            throw exception;
                        }
                    }
                }
                return true;
            } catch (AccountStorageException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Boolean> tryAddBalance(UUID uniqueId, long amount) {
        String name = names.getOrDefault(uniqueId, globalNames.getOrDefault(uniqueId, uniqueId.toString()));
        return tryAddBalance(uniqueId, name, amount);
    }

    public CompletableFuture<Boolean> tryAddBalance(UUID uniqueId, String name, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return supplyAccountIO(() -> {
            SharedAccountStorage sharedStorage = sharedStorage();
            try {
                boolean success;
                long next;
                if (sharedStorage != null) {
                    success = sharedStorage.tryAddBalance(uniqueId, name, amount, pluginSettings.maxBalance());
                    next = success ? sharedStorage.getBalance(uniqueId) : getCachedBalance(uniqueId);
                } else {
                    synchronized (this) {
                        AccountSnapshot before = snapshotLocked();
                        long current = balances.getOrDefault(uniqueId, globalBalances.getOrDefault(uniqueId, 0L));
                        next = AmountUtil.checkedAddBalance(current, amount, pluginSettings.maxBalance());
                        names.put(uniqueId, name);
                        balances.put(uniqueId, next);
                        globalNames.put(uniqueId, name);
                        globalBalances.put(uniqueId, next);
                        try {
                            copyLocalToGlobalLocked();
                            saveLocalSnapshotLocked();
                        } catch (AccountStorageException exception) {
                            populateLocalStateLocked(before);
                            copyLocalToGlobalLocked();
                            throw exception;
                        }
                    }
                    success = true;
                }
                if (success) {
                    synchronized (this) {
                        names.put(uniqueId, name);
                        balances.put(uniqueId, next);
                        globalNames.put(uniqueId, name);
                        globalBalances.put(uniqueId, next);
                    }
                }
                return success;
            } catch (AccountStorageException | ArithmeticException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Boolean> tryTakeBalance(UUID uniqueId, long amount) {
        String name = names.getOrDefault(uniqueId, globalNames.getOrDefault(uniqueId, uniqueId.toString()));
        return tryTakeBalance(uniqueId, name, amount);
    }

    public CompletableFuture<Boolean> tryTakeBalance(UUID uniqueId, String name, long amount) {
        if (amount <= 0L) {
            return CompletableFuture.completedFuture(false);
        }
        return supplyAccountIO(() -> {
            SharedAccountStorage sharedStorage = sharedStorage();
            try {
                boolean success;
                long next;
                if (sharedStorage != null) {
                    success = sharedStorage.tryTakeBalance(uniqueId, name, amount);
                    next = success ? sharedStorage.getBalance(uniqueId) : getCachedBalance(uniqueId);
                } else {
                    synchronized (this) {
                        AccountSnapshot before = snapshotLocked();
                        long current = balances.getOrDefault(uniqueId, globalBalances.getOrDefault(uniqueId, 0L));
                        next = AmountUtil.checkedSubtractBalance(current, amount);
                        names.put(uniqueId, name);
                        balances.put(uniqueId, next);
                        globalNames.put(uniqueId, name);
                        globalBalances.put(uniqueId, next);
                        try {
                            copyLocalToGlobalLocked();
                            saveLocalSnapshotLocked();
                        } catch (AccountStorageException exception) {
                            populateLocalStateLocked(before);
                            copyLocalToGlobalLocked();
                            throw exception;
                        }
                    }
                    success = true;
                }
                if (success) {
                    synchronized (this) {
                        names.put(uniqueId, name);
                        balances.put(uniqueId, next);
                        globalNames.put(uniqueId, name);
                        globalBalances.put(uniqueId, next);
                    }
                }
                return success;
            } catch (AccountStorageException | ArithmeticException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public synchronized boolean toggleFavorite(UUID uniqueId, Material material) {
        Set<Material> set = favorites.computeIfAbsent(uniqueId, ignored -> EnumSet.noneOf(Material.class));
        boolean added;
        if (set.contains(material)) {
            set.remove(material);
            added = false;
        } else {
            set.add(material);
            added = true;
        }
        String name = names.getOrDefault(uniqueId, globalNames.getOrDefault(uniqueId, uniqueId.toString()));
        submitStorageUpdate("update shared favorites", storage -> storage.setFavorite(uniqueId, name, material, added));
        return added;
    }

    public synchronized boolean isFavorite(UUID uniqueId, Material material) {
        return favorites.getOrDefault(uniqueId, Set.of()).contains(material);
    }

    public synchronized List<Material> getFavorites(UUID uniqueId) {
        return favorites.getOrDefault(uniqueId, Set.of()).stream()
                .sorted(Comparator.comparing(Material::name))
                .toList();
    }

    public synchronized int getFavoriteCount(UUID uniqueId) {
        return favorites.getOrDefault(uniqueId, Set.of()).size();
    }

    public synchronized void recordSale(UUID uniqueId, String name, Map<Material, Integer> soldMaterials, long soldEmc) {
        if (soldMaterials.isEmpty() || soldEmc <= 0L) {
            return;
        }
        names.put(uniqueId, name);
        globalNames.put(uniqueId, name);
        DailySaleData data = getOrCreateTodayData(uniqueId);
        try {
            data.soldEmc = Math.addExact(data.soldEmc, soldEmc);
        } catch (ArithmeticException exception) {
            data.soldEmc = Long.MAX_VALUE;
            plugin.getLogger().warning("Daily EMC sale total overflowed for " + uniqueId + "; clamped to Long.MAX_VALUE.");
        }
        for (Map.Entry<Material, Integer> entry : soldMaterials.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            data.soldAmounts.merge(entry.getKey().name(), entry.getValue(), Integer::sum);
        }
        globalDailySales.put(uniqueId, toSnapshot(data));
        submitStorageUpdate("record shared sale statistics", storage -> storage.recordSale(uniqueId, name, soldMaterials, soldEmc, todayKey()));
    }

    public synchronized void recordPurchase(UUID uniqueId, String name, Material material, int amount) {
        if (material == null || amount <= 0) {
            return;
        }
        names.put(uniqueId, name);
        globalNames.put(uniqueId, name);
        if (sharedStorage() == null) {
            purchaseHeat.merge(material.name(), amount, Integer::sum);
            copyLocalToGlobalLocked();
        } else {
            globalPurchaseHeat.merge(material.name(), amount, Integer::sum);
        }
        submitStorageUpdate("record shared purchase heat", storage -> storage.recordPurchase(uniqueId, name, material, amount));
    }

    public void recordAudit(TransactionAuditRecord record) {
        executor.execute(() -> {
            SharedAccountStorage sharedStorage = sharedStorage();
            try {
                if (sharedStorage != null) {
                    sharedStorage.recordAudit(record);
                } else {
                    writeLocalAudit(record);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.WARNING, "Failed to write transaction audit log", exception);
            }
        });
    }

    public CompletableFuture<Void> savePendingSell(PendingSellTransaction transaction) {
        return supplyAccountIO(() -> {
            try {
                SharedAccountStorage sharedStorage = sharedStorage();
                if (sharedStorage != null) {
                    sharedStorage.savePendingSell(transaction);
                } else {
                    saveLocalPendingSell(transaction);
                }
                return null;
            } catch (AccountStorageException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<Void> updatePendingSellStatus(String transactionId, PendingSellStatus status, String reason) {
        return supplyAccountIO(() -> {
            try {
                SharedAccountStorage sharedStorage = sharedStorage();
                if (sharedStorage != null) {
                    sharedStorage.updatePendingSellStatus(transactionId, status, reason);
                } else {
                    updateLocalPendingSellStatus(transactionId, status, reason);
                }
                return null;
            } catch (AccountStorageException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public CompletableFuture<PendingCreditResult> completePendingSellCredit(PendingSellTransaction transaction) {
        return supplyAccountIO(() -> {
            try {
                SharedAccountStorage sharedStorage = sharedStorage();
                PendingCreditResult result = sharedStorage == null
                        ? completeLocalPendingSellCredit(transaction)
                        : sharedStorage.completePendingSellCredit(transaction, pluginSettings.maxBalance());
                if (result == PendingCreditResult.SUCCESS) {
                    long balance = sharedStorage == null
                            ? balances.getOrDefault(transaction.playerUuid(), globalBalances.getOrDefault(transaction.playerUuid(), 0L))
                            : sharedStorage.getBalance(transaction.playerUuid());
                    synchronized (this) {
                        names.put(transaction.playerUuid(), transaction.playerName());
                        balances.put(transaction.playerUuid(), balance);
                        globalNames.put(transaction.playerUuid(), transaction.playerName());
                        globalBalances.put(transaction.playerUuid(), balance);
                    }
                }
                return result;
            } catch (AccountStorageException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    public PendingCreditResult completePendingSellCreditForTesting(PendingSellTransaction transaction) {
        try {
            return completeLocalPendingSellCredit(transaction);
        } catch (AccountStorageException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void recoverPendingSellForTesting(PendingSellTransaction transaction) {
        try {
            recoverPendingSell(transaction);
        } catch (AccountStorageException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public void recoverPendingSells() {
        executor.execute(() -> {
            try {
                SharedAccountStorage sharedStorage = sharedStorage();
                List<PendingSellTransaction> transactions = sharedStorage == null
                        ? loadLocalOpenPendingSells()
                        : sharedStorage.loadOpenPendingSells();
                for (PendingSellTransaction transaction : transactions) {
                    recoverPendingSell(transaction);
                }
            } catch (Exception exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to recover pending sell transactions", exception);
            }
        });
    }

    public synchronized DailySaleSummary getTodaySummary(UUID uniqueId) {
        DailySaleData data = dailySales.get(uniqueId);
        if (data == null) {
            AccountSnapshot.DailySaleSnapshot snapshot = globalDailySales.get(uniqueId);
            data = snapshot == null ? new DailySaleData(todayKey()) : toMutable(snapshot);
        }
        data = normalizeDailyData(data, todayKey());
        Map.Entry<String, Integer> topEntry = data.soldAmounts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                .orElse(null);
        Material topMaterial = topEntry == null ? null : Material.matchMaterial(topEntry.getKey());
        int topAmount = topEntry == null ? 0 : topEntry.getValue();
        return new DailySaleSummary(data.soldEmc, topMaterial, topAmount, getFavoriteCount(uniqueId));
    }

    public synchronized List<LeaderboardEntry> getLeaderboardPage(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, pageSize);
        Map<UUID, Long> sourceBalances = leaderboardBalances();
        Map<UUID, String> sourceNames = leaderboardNames();
        return sourceBalances.entrySet().stream()
                .filter(entry -> entry.getValue() > 0L)
                .sorted(Map.Entry.<UUID, Long>comparingByValue(Comparator.reverseOrder()))
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(entry -> new LeaderboardEntry(sourceNames.getOrDefault(entry.getKey(), entry.getKey().toString()), entry.getValue()))
                .toList();
    }

    public synchronized int getLeaderboardTotalPages(int pageSize) {
        Map<UUID, Long> sourceBalances = leaderboardBalances();
        long size = sourceBalances.values().stream().filter(value -> value > 0L).count();
        int safeSize = Math.max(1, pageSize);
        return Math.max(1, (int) Math.ceil(size / (double) safeSize));
    }

    public synchronized List<DailyLeaderboardEntry> getDailyLeaderboardPage(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, pageSize);
        Map<UUID, AccountSnapshot.DailySaleSnapshot> sourceDailySales = leaderboardDailySales();
        Map<UUID, String> sourceNames = leaderboardNames();
        String today = todayKey();
        return sourceDailySales.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), normalizeDailySnapshot(entry.getValue(), today)))
                .filter(entry -> entry.getValue().soldEmc() > 0L)
                .sorted((left, right) -> Long.compare(right.getValue().soldEmc(), left.getValue().soldEmc()))
                .skip((long) (safePage - 1) * safeSize)
                .limit(safeSize)
                .map(entry -> {
                    Map.Entry<String, Integer> topEntry = entry.getValue().soldAmounts().entrySet().stream()
                            .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry::getKey))
                            .orElse(null);
                    Material topMaterial = topEntry == null ? null : Material.matchMaterial(topEntry.getKey());
                    int topAmount = topEntry == null ? 0 : topEntry.getValue();
                    return new DailyLeaderboardEntry(
                            sourceNames.getOrDefault(entry.getKey(), entry.getKey().toString()),
                            entry.getValue().soldEmc(),
                            topMaterial,
                            topAmount
                    );
                })
                .toList();
    }

    public synchronized int getDailyLeaderboardTotalPages(int pageSize) {
        String today = todayKey();
        Map<UUID, AccountSnapshot.DailySaleSnapshot> sourceDailySales = leaderboardDailySales();
        long size = sourceDailySales.values().stream()
                .map(snapshot -> normalizeDailySnapshot(snapshot, today))
                .filter(snapshot -> snapshot.soldEmc() > 0L)
                .count();
        int safeSize = Math.max(1, pageSize);
        return Math.max(1, (int) Math.ceil(size / (double) safeSize));
    }

    public synchronized int getPurchaseHeat(Material material) {
        if (material == null) {
            return 0;
        }
        if (sharedStorage() != null) {
            return globalPurchaseHeat.getOrDefault(material.name(), 0);
        }
        return purchaseHeat.getOrDefault(material.name(), 0);
    }

    public synchronized List<HotMaterialEntry> getHotMaterials(int limit) {
        int safeLimit = Math.max(1, limit);
        Map<String, Integer> source = sharedStorage() != null ? globalPurchaseHeat : purchaseHeat;
        return source.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry::getKey))
                .limit(safeLimit)
                .map(entry -> {
                    Material material = Material.matchMaterial(entry.getKey());
                    return material == null ? null : new HotMaterialEntry(material, entry.getValue());
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void saveSync() {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage != null) {
            awaitExecutorIdle();
            return;
        }
        AccountSnapshot snapshot = snapshot();
        dirty.set(false);
        try {
            storage.save(snapshot);
        } catch (AccountStorageException exception) {
            dirty.set(true);
            plugin.getLogger().log(Level.SEVERE, "Failed to synchronously save account data", exception);
        }
    }

    private synchronized AccountSnapshot snapshotLocked() {
        String today = todayKey();
        Map<UUID, Set<Material>> favoriteSnapshot = new HashMap<>();
        favorites.forEach((uuid, materials) -> favoriteSnapshot.put(uuid, Set.copyOf(materials)));

        Map<UUID, AccountSnapshot.DailySaleSnapshot> dailySnapshot = new HashMap<>();
        dailySales.forEach((uuid, data) -> {
            DailySaleData normalized = normalizeDailyData(data, today);
            dailySnapshot.put(uuid, new AccountSnapshot.DailySaleSnapshot(normalized.date, normalized.soldEmc, normalized.soldAmounts));
        });

        return new AccountSnapshot(
                new HashMap<>(balances),
                new HashMap<>(names),
                favoriteSnapshot,
                dailySnapshot,
                new HashMap<>(purchaseHeat)
        );
    }

    private AccountSnapshot snapshot() {
        synchronized (this) {
            return snapshotLocked();
        }
    }

    private <T> CompletableFuture<T> supplyAccountIO(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor);
    }

    private long normalizeBalanceAmount(long amount) {
        if (amount < 0L) {
            return 0L;
        }
        return Math.min(amount, pluginSettings.maxBalance());
    }

    private void writeLocalAudit(TransactionAuditRecord record) throws IOException {
        java.io.File auditDirectory = new java.io.File(plugin.getDataFolder(), "audit");
        if (!auditDirectory.exists() && !auditDirectory.mkdirs()) {
            throw new IOException("Failed to create audit directory");
        }
        java.io.File auditFile = new java.io.File(auditDirectory, "transactions.log");
        String line = String.join("|",
                record.transactionId(),
                record.playerUuid() == null ? "" : record.playerUuid().toString(),
                nullToEmpty(record.playerName()),
                nullToEmpty(record.operationType()),
                record.material() == null ? "" : record.material().name(),
                String.valueOf(record.amount()),
                String.valueOf(record.unitPrice()),
                String.valueOf(record.totalPrice()),
                String.valueOf(record.beforeBalance()),
                String.valueOf(record.afterBalance()),
                nullToEmpty(record.storageType()),
                String.valueOf(record.success()),
                nullToEmpty(record.failureReason()),
                record.timestamp().toString(),
                nullToEmpty(record.serverName())
        ) + System.lineSeparator();
        Files.writeString(auditFile.toPath(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void saveLocalPendingSell(PendingSellTransaction transaction) throws AccountStorageException {
        YamlConfiguration configuration = loadPendingConfiguration();
        writePendingSection(configuration, transaction);
        savePendingConfiguration(configuration);
    }

    private void updateLocalPendingSellStatus(String transactionId, PendingSellStatus status, String reason) throws AccountStorageException {
        YamlConfiguration configuration = loadPendingConfiguration();
        ConfigurationSection section = configuration.getConfigurationSection("transactions." + transactionId);
        if (section == null) {
            throw new AccountStorageException("Pending sell transaction not found: " + transactionId);
        }
        section.set("status", status.name());
        section.set("updated-at", java.time.Instant.now().toString());
        section.set("failure-reason", reason == null ? "" : reason);
        savePendingConfiguration(configuration);
    }

    private List<PendingSellTransaction> loadLocalOpenPendingSells() {
        YamlConfiguration configuration = loadPendingConfiguration();
        ConfigurationSection root = configuration.getConfigurationSection("transactions");
        if (root == null) {
            return List.of();
        }
        java.util.ArrayList<PendingSellTransaction> transactions = new java.util.ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            PendingSellStatus status = parsePendingStatus(section.getString("status"));
            if (!PendingSellRecoveryPolicy.isOpen(status)) {
                continue;
            }
            try {
                UUID uuid = UUID.fromString(section.getString("player-uuid", ""));
                Map<String, Integer> materials = new HashMap<>();
                ConfigurationSection materialSection = section.getConfigurationSection("materials");
                if (materialSection != null) {
                    for (String material : materialSection.getKeys(false)) {
                        int amount = materialSection.getInt(material);
                        if (amount > 0 && Material.matchMaterial(material) != null) {
                            materials.put(material, amount);
                        }
                    }
                }
                transactions.add(new PendingSellTransaction(
                        id,
                        uuid,
                        section.getString("player-name", uuid.toString()),
                        section.getString("operation-type", "SELL"),
                        materials,
                        section.getLong("reward"),
                        status,
                        parseInstant(section.getString("created-at")),
                        parseInstant(section.getString("updated-at")),
                        section.getString("failure-reason", ""),
                        section.getString("server-name", "")
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid pending sell transaction in YAML: " + id);
            }
        }
        return transactions;
    }

    private void recoverPendingSell(PendingSellTransaction transaction) throws AccountStorageException {
        switch (PendingSellRecoveryPolicy.actionFor(transaction.status())) {
            case FAIL_SAFE_BEFORE_REMOVAL -> {
                updateRecoveredPending(transaction.transactionId(), PendingSellStatus.FAILED, "server stopped before item removal");
                plugin.getLogger().warning("Marked pending sell " + transaction.transactionId() + " as failed because item removal had not started.");
            }
            case MANUAL_REVIEW_ITEM_REMOVAL_UNCERTAIN -> {
                updateRecoveredPending(transaction.transactionId(), PendingSellStatus.MANUAL_REVIEW, "server stopped while removing items");
                logManualReview(transaction, "item removal may or may not have completed; no automatic credit or failure was applied");
            }
            case CREDIT_REMOVED_ITEMS -> {
                PendingCreditResult credited = creditRecoveredPending(transaction);
                if (credited == PendingCreditResult.SUCCESS) {
                    plugin.getLogger().warning("Recovered pending sell " + transaction.transactionId() + " by crediting EMC after restart.");
                } else {
                    updateRecoveredPending(transaction.transactionId(), PendingSellStatus.MANUAL_REVIEW, "recovery credit failed: " + credited);
                    logManualReview(transaction, "items were already removed but recovery credit failed: " + credited);
                }
            }
            case MANUAL_REVIEW_CREDIT_UNCERTAIN -> {
                updateRecoveredPending(transaction.transactionId(), PendingSellStatus.MANUAL_REVIEW, "server stopped while crediting balance");
                logManualReview(transaction, "balance may or may not have been credited; automatic duplicate credit is blocked");
            }
            case IGNORE_TERMINAL -> {
            }
        }
    }

    private PendingCreditResult creditRecoveredPending(PendingSellTransaction transaction) throws AccountStorageException {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage != null) {
            updateRecoveredPending(transaction.transactionId(), PendingSellStatus.CREDITING, "recovery credit started");
            PendingCreditResult result = sharedStorage.completePendingSellCredit(transaction.withStatus(PendingSellStatus.CREDITING, "recovery credit started"), pluginSettings.maxBalance());
            if (result == PendingCreditResult.SUCCESS) {
                long balance = sharedStorage.getBalance(transaction.playerUuid());
                synchronized (this) {
                    names.put(transaction.playerUuid(), transaction.playerName());
                    balances.put(transaction.playerUuid(), balance);
                    globalNames.put(transaction.playerUuid(), transaction.playerName());
                    globalBalances.put(transaction.playerUuid(), balance);
                }
            }
            return result;
        }
        updateRecoveredPending(transaction.transactionId(), PendingSellStatus.CREDITING, "recovery credit started");
        return completeLocalPendingSellCredit(transaction.withStatus(PendingSellStatus.CREDITING, "recovery credit started"));
    }

    private void updateRecoveredPending(String transactionId, PendingSellStatus status, String reason) throws AccountStorageException {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage != null) {
            sharedStorage.updatePendingSellStatus(transactionId, status, reason);
        } else {
            updateLocalPendingSellStatus(transactionId, status, reason);
        }
    }

    private PendingCreditResult completeLocalPendingSellCredit(PendingSellTransaction transaction) throws AccountStorageException {
        synchronized (this) {
            AccountSnapshot before = snapshotLocked();
            try {
                PendingSellStatus persistedStatus = loadLocalPendingSellStatus(transaction.transactionId());
                if (persistedStatus != PendingSellStatus.CREDITING) {
                    return PendingCreditResult.MANUAL_REVIEW_REQUIRED;
                }
                long current = balances.getOrDefault(transaction.playerUuid(), globalBalances.getOrDefault(transaction.playerUuid(), 0L));
                long next = AmountUtil.checkedAddBalance(current, transaction.reward(), pluginSettings.maxBalance());
                names.put(transaction.playerUuid(), transaction.playerName());
                balances.put(transaction.playerUuid(), next);
                globalNames.put(transaction.playerUuid(), transaction.playerName());
                globalBalances.put(transaction.playerUuid(), next);
                copyLocalToGlobalLocked();
                saveLocalSnapshotLocked();
                try {
                    updateLocalPendingSellStatus(transaction.transactionId(), PendingSellStatus.SUCCESS, "");
                    return PendingCreditResult.SUCCESS;
                } catch (AccountStorageException exception) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Local pending sell credit was applied but SUCCESS state failed to persist: " + transaction.transactionId(),
                            exception
                    );
                    return PendingCreditResult.MANUAL_REVIEW_REQUIRED;
                }
            } catch (ArithmeticException exception) {
                populateLocalStateLocked(before);
                copyLocalToGlobalLocked();
                return PendingCreditResult.REJECTED;
            } catch (AccountStorageException exception) {
                populateLocalStateLocked(before);
                copyLocalToGlobalLocked();
                throw exception;
            }
        }
    }

    private PendingSellStatus loadLocalPendingSellStatus(String transactionId) {
        YamlConfiguration configuration = loadPendingConfiguration();
        return parsePendingStatus(configuration.getString("transactions." + transactionId + ".status"));
    }

    private void logManualReview(PendingSellTransaction transaction, String reason) {
        plugin.getLogger().severe(
                "Pending sell requires manual review: transactionId=" + transaction.transactionId()
                        + ", playerUuid=" + transaction.playerUuid()
                        + ", playerName=" + transaction.playerName()
                        + ", operationType=" + transaction.operationType()
                        + ", materials=" + transaction.materials()
                        + ", reward=" + transaction.reward()
                        + ", status=" + transaction.status()
                        + ", serverName=" + transaction.serverName()
                        + ", reason=" + reason
        );
    }

    private void writePendingSection(YamlConfiguration configuration, PendingSellTransaction transaction) {
        ConfigurationSection section = configuration.createSection("transactions." + transaction.transactionId());
        section.set("player-uuid", transaction.playerUuid().toString());
        section.set("player-name", transaction.playerName());
        section.set("operation-type", transaction.operationType());
        ConfigurationSection materials = section.createSection("materials");
        transaction.materials().forEach(materials::set);
        section.set("reward", transaction.reward());
        section.set("status", transaction.status().name());
        section.set("created-at", transaction.createdAt().toString());
        section.set("updated-at", transaction.updatedAt().toString());
        section.set("failure-reason", transaction.failureReason());
        section.set("server-name", transaction.serverName());
    }

    private YamlConfiguration loadPendingConfiguration() {
        java.io.File file = pendingTransactionsFile();
        return file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private void savePendingConfiguration(YamlConfiguration configuration) throws AccountStorageException {
        java.io.File file = pendingTransactionsFile();
        java.io.File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new AccountStorageException("Failed to create pending transaction directory");
        }
        java.io.File temporaryFile = new java.io.File(parent, file.getName() + ".tmp");
        try {
            configuration.save(temporaryFile);
            try {
                Files.move(temporaryFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temporaryFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new AccountStorageException("Failed to save pending-transactions.yml", exception);
        }
    }

    private java.io.File pendingTransactionsFile() {
        return new java.io.File(plugin.getDataFolder(), "pending-transactions.yml");
    }

    private PendingSellStatus parsePendingStatus(String status) {
        try {
            return PendingSellStatus.valueOf(status == null ? "" : status);
        } catch (IllegalArgumentException exception) {
            return PendingSellStatus.FAILED;
        }
    }

    private java.time.Instant parseInstant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException exception) {
            return java.time.Instant.now();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.replace('|', '/');
    }

    private void saveLocalSnapshotLocked() throws AccountStorageException {
        AccountSnapshot snapshot = snapshotLocked();
        storage.save(snapshot);
        dirty.set(false);
    }

    private void reloadLocal() {
        AccountSnapshot snapshot = loadSnapshot();
        synchronized (this) {
            populateLocalStateLocked(snapshot);
            copyLocalToGlobalLocked();
            dirty.set(false);
        }
    }

    private void reloadShared(SharedAccountStorage sharedStorage) {
        try {
            sharedStorage.importFromYamlIfNeeded();
            SharedAccountGlobalState state = sharedStorage.loadGlobalState();
            synchronized (this) {
                replaceGlobalStateLocked(state);
                retainSharedActiveCachesLocked();
                dirty.set(false);
            }
            Bukkit.getOnlinePlayers().forEach(player -> executor.execute(() -> preloadPlayer(player.getUniqueId(), player.getName())));
        } catch (AccountStorageException exception) {
            throw new IllegalStateException("Failed to load account data from " + storageSettings.describe(), exception);
        }
    }

    private void populateLocalStateLocked(AccountSnapshot snapshot) {
        balances.clear();
        names.clear();
        favorites.clear();
        dailySales.clear();
        purchaseHeat.clear();

        balances.putAll(snapshot.balances());
        names.putAll(snapshot.names());
        snapshot.favorites().forEach((uuid, materials) -> {
            Set<Material> copied = EnumSet.noneOf(Material.class);
            copied.addAll(materials);
            favorites.put(uuid, copied);
        });
        snapshot.dailySales().forEach((uuid, sale) -> dailySales.put(uuid, toMutable(sale)));
        purchaseHeat.putAll(snapshot.purchaseHeat());
    }

    private void replaceGlobalStateLocked(SharedAccountGlobalState state) {
        globalBalances.clear();
        globalBalances.putAll(state.balances());
        globalNames.clear();
        globalNames.putAll(state.names());
        globalDailySales.clear();
        globalDailySales.putAll(state.dailySales());
        globalPurchaseHeat.clear();
        globalPurchaseHeat.putAll(state.purchaseHeat());
    }

    private void retainSharedActiveCachesLocked() {
        Set<UUID> onlinePlayers = Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        balances.keySet().retainAll(onlinePlayers);
        names.keySet().retainAll(onlinePlayers);
        favorites.keySet().retainAll(onlinePlayers);
        dailySales.keySet().retainAll(onlinePlayers);
    }

    private void applySharedPlayerStateLocked(UUID uniqueId, PlayerAccountState state, String fallbackName) {
        String resolvedName = state.name().isBlank() ? fallbackName : state.name();
        names.put(uniqueId, resolvedName);
        balances.put(uniqueId, state.balance());
        globalNames.put(uniqueId, resolvedName);
        globalBalances.put(uniqueId, state.balance());

        Set<Material> favoriteCopy = EnumSet.noneOf(Material.class);
        favoriteCopy.addAll(state.favorites());
        favorites.put(uniqueId, favoriteCopy);

        DailySaleData saleData = toMutable(state.dailySale());
        dailySales.put(uniqueId, saleData);
        globalDailySales.put(uniqueId, toSnapshot(saleData));
    }

    private synchronized void copyLocalToGlobalLocked() {
        globalBalances.clear();
        globalBalances.putAll(balances);
        globalNames.clear();
        globalNames.putAll(names);
        globalDailySales.clear();
        dailySales.forEach((uuid, data) -> globalDailySales.put(uuid, toSnapshot(data)));
        globalPurchaseHeat.clear();
        globalPurchaseHeat.putAll(purchaseHeat);
    }

    private void scheduleBackgroundTask() {
        cancelBackgroundTask();
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage == null) {
            backgroundTask = executor.scheduleAtFixedRate(this::saveIfDirty, 5, 5, TimeUnit.MINUTES);
            return;
        }
        long refreshSeconds = Math.max(5, storageSettings.mysql().refreshSeconds());
        backgroundTask = executor.scheduleAtFixedRate(this::refreshSharedGlobalCache, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    private void cancelBackgroundTask() {
        ScheduledFuture<?> task = backgroundTask;
        if (task != null) {
            task.cancel(false);
            backgroundTask = null;
        }
    }

    private void refreshSharedGlobalCache() {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage == null) {
            return;
        }
        try {
            SharedAccountGlobalState state = sharedStorage.loadGlobalState();
            synchronized (this) {
                replaceGlobalStateLocked(state);
            }
        } catch (AccountStorageException exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to refresh shared account cache", exception);
        }
    }

    private void saveIfDirty() {
        if (sharedStorage() != null) {
            return;
        }
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        AccountSnapshot snapshot = snapshot();
        try {
            storage.save(snapshot);
        } catch (AccountStorageException exception) {
            dirty.set(true);
            plugin.getLogger().log(Level.SEVERE, "Failed to asynchronously save account data", exception);
        }
    }

    private void submitStorageUpdate(String action, SharedStorageAction sharedAction) {
        SharedAccountStorage sharedStorage = sharedStorage();
        if (sharedStorage == null) {
            dirty.set(true);
            return;
        }
        executor.execute(() -> {
            try {
                sharedAction.execute(sharedStorage);
            } catch (AccountStorageException exception) {
                plugin.getLogger().log(Level.SEVERE, "Failed to " + action, exception);
            }
        });
    }

    private AccountSnapshot loadSnapshot() {
        try {
            return storage.load();
        } catch (AccountStorageException exception) {
            throw new IllegalStateException("Failed to load account data from " + storageSettings.describe(), exception);
        }
    }

    private AccountStorage createStorage(StorageSettings settings) {
        try {
            return switch (settings.type()) {
                case YAML -> new YamlAccountStorage(plugin);
                case MYSQL -> new MySqlAccountStorage(plugin, settings.mysql(), new YamlAccountStorage(plugin));
            };
        } catch (AccountStorageException exception) {
            throw new IllegalStateException("Failed to initialize account storage: " + settings.describe(), exception);
        }
    }

    private SharedAccountStorage sharedStorage() {
        return storage instanceof SharedAccountStorage sharedStorage ? sharedStorage : null;
    }

    private void closeQuietly(AccountStorage target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (AccountStorageException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to close account storage cleanly", exception);
        }
    }

    private void awaitExecutorIdle() {
        Future<?> future = executor.submit(() -> {
        });
        try {
            future.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            plugin.getLogger().warning("Timed out waiting for queued account storage work to finish.");
        } catch (Exception exception) {
            plugin.getLogger().log(Level.WARNING, "Failed while waiting for queued account storage work to finish", exception);
        }
    }

    private Map<UUID, Long> leaderboardBalances() {
        return sharedStorage() != null ? globalBalances : balances;
    }

    private Map<UUID, String> leaderboardNames() {
        return sharedStorage() != null ? globalNames : names;
    }

    private Map<UUID, AccountSnapshot.DailySaleSnapshot> leaderboardDailySales() {
        return sharedStorage() != null ? globalDailySales : dailySales.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> toSnapshot(entry.getValue())));
    }

    private DailySaleData getOrCreateTodayData(UUID uniqueId) {
        String today = todayKey();
        DailySaleData data = dailySales.computeIfAbsent(uniqueId, ignored -> new DailySaleData(today));
        return normalizeDailyData(data, today);
    }

    private DailySaleData normalizeDailyData(DailySaleData data, String today) {
        if (!today.equals(data.date)) {
            data.reset(today);
        }
        return data;
    }

    private AccountSnapshot.DailySaleSnapshot normalizeDailySnapshot(AccountSnapshot.DailySaleSnapshot snapshot, String today) {
        if (snapshot == null || !today.equals(snapshot.date())) {
            return new AccountSnapshot.DailySaleSnapshot(today, 0L, Map.of());
        }
        return snapshot;
    }

    private DailySaleData toMutable(AccountSnapshot.DailySaleSnapshot snapshot) {
        AccountSnapshot.DailySaleSnapshot normalized = normalizeDailySnapshot(snapshot, todayKey());
        DailySaleData data = new DailySaleData(normalized.date());
        data.soldEmc = normalized.soldEmc();
        data.soldAmounts.putAll(normalized.soldAmounts());
        return data;
    }

    private AccountSnapshot.DailySaleSnapshot toSnapshot(DailySaleData data) {
        DailySaleData normalized = normalizeDailyData(data, todayKey());
        return new AccountSnapshot.DailySaleSnapshot(normalized.date, normalized.soldEmc, normalized.soldAmounts);
    }

    private String todayKey() {
        return LocalDate.now().toString();
    }
}
