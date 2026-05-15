package com.blockemc.model;

import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

public record StorageSettings(
        Type type,
        MySqlSettings mysql
) {

    public enum Type {
        YAML,
        MYSQL;

        public static Type fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return YAML;
            }
            try {
                return Type.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return YAML;
            }
        }
    }

    public StorageSettings {
        type = type == null ? Type.YAML : type;
        mysql = mysql == null ? MySqlSettings.defaults() : mysql;
    }

    public static StorageSettings load(FileConfiguration configuration) {
        return new StorageSettings(
                Type.fromConfig(configuration.getString("storage.type", "YAML")),
                new MySqlSettings(
                        configuration.getString("storage.mysql.host", "127.0.0.1"),
                        Math.max(1, configuration.getInt("storage.mysql.port", 3306)),
                        configuration.getString("storage.mysql.database", "blockemc"),
                        configuration.getString("storage.mysql.username", "blockemc"),
                        configuration.getString("storage.mysql.password", "CHANGE_ME"),
                        configuration.getBoolean("storage.mysql.use-ssl", true),
                        configuration.getBoolean("storage.mysql.allow-public-key-retrieval", false),
                        configuration.getString("storage.mysql.table-prefix", "blockemc_"),
                        configuration.getBoolean("storage.mysql.import-from-yaml-on-first-run", true),
                        Math.max(5, configuration.getInt("storage.mysql.refresh-seconds", 30)),
                        new MySqlPoolSettings(
                                configuration.getInt("storage.mysql.pool.maximum-pool-size", 10),
                                configuration.getInt("storage.mysql.pool.minimum-idle", 2),
                                configuration.getLong("storage.mysql.pool.connection-timeout-ms", 10_000L),
                                configuration.getLong("storage.mysql.pool.idle-timeout-ms", 600_000L),
                                configuration.getLong("storage.mysql.pool.max-lifetime-ms", 1_800_000L)
                        )
                )
        );
    }

    public String describe() {
        return switch (type) {
            case YAML -> "YAML(accounts.yml)";
            case MYSQL -> "MySQL(" + mysql.describe() + ")";
        };
    }

    public record MySqlSettings(
            String host,
            int port,
            String database,
            String username,
            String password,
            boolean useSsl,
            boolean allowPublicKeyRetrieval,
            String tablePrefix,
            boolean importFromYamlOnFirstRun,
            int refreshSeconds,
            MySqlPoolSettings pool
    ) {

        public MySqlSettings {
            host = defaultIfBlank(host, "127.0.0.1");
            port = Math.max(1, port);
            database = defaultIfBlank(database, "blockemc");
            username = defaultIfBlank(username, "blockemc");
            password = password == null ? "CHANGE_ME" : password;
            tablePrefix = sanitizeTablePrefix(tablePrefix);
            refreshSeconds = Math.max(5, refreshSeconds);
            pool = pool == null ? MySqlPoolSettings.defaults() : pool;
        }

        public static MySqlSettings defaults() {
            return new MySqlSettings(
                    "127.0.0.1",
                    3306,
                    "blockemc",
                    "blockemc",
                    "CHANGE_ME",
                    true,
                    false,
                    "blockemc_",
                    true,
                    30,
                    MySqlPoolSettings.defaults()
            );
        }

        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl
                    + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                    + "&useUnicode=true"
                    + "&characterEncoding=UTF-8"
                    + "&serverTimezone=UTC"
                    + "&rewriteBatchedStatements=true";
        }

        public String describe() {
            return host + ":" + port + "/" + database;
        }
    }

    public record MySqlPoolSettings(
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs,
            long idleTimeoutMs,
            long maxLifetimeMs
    ) {

        public MySqlPoolSettings {
            maximumPoolSize = Math.max(1, maximumPoolSize);
            minimumIdle = Math.max(0, Math.min(minimumIdle, maximumPoolSize));
            connectionTimeoutMs = Math.max(250L, connectionTimeoutMs);
            idleTimeoutMs = Math.max(10_000L, idleTimeoutMs);
            maxLifetimeMs = Math.max(30_000L, maxLifetimeMs);
        }

        public static MySqlPoolSettings defaults() {
            return new MySqlPoolSettings(10, 2, 10_000L, 600_000L, 1_800_000L);
        }
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sanitizeTablePrefix(String value) {
        String normalized = defaultIfBlank(value, "blockemc_").replaceAll("[^A-Za-z0-9_]", "_");
        return normalized.isBlank() ? "blockemc_" : normalized;
    }
}
