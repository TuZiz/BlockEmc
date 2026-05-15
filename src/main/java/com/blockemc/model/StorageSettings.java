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
                        configuration.getString("storage.mysql.username", "root"),
                        configuration.getString("storage.mysql.password", ""),
                        configuration.getBoolean("storage.mysql.use-ssl", false),
                        configuration.getString("storage.mysql.table-prefix", "blockemc_"),
                        configuration.getBoolean("storage.mysql.import-from-yaml-on-first-run", true),
                        Math.max(5, configuration.getInt("storage.mysql.refresh-seconds", 30))
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
            String tablePrefix,
            boolean importFromYamlOnFirstRun,
            int refreshSeconds
    ) {

        public MySqlSettings {
            host = defaultIfBlank(host, "127.0.0.1");
            port = Math.max(1, port);
            database = defaultIfBlank(database, "blockemc");
            username = defaultIfBlank(username, "root");
            password = password == null ? "" : password;
            tablePrefix = sanitizeTablePrefix(tablePrefix);
            refreshSeconds = Math.max(5, refreshSeconds);
        }

        public static MySqlSettings defaults() {
            return new MySqlSettings("127.0.0.1", 3306, "blockemc", "root", "", false, "blockemc_", true, 30);
        }

        public String jdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl
                    + "&allowPublicKeyRetrieval=true"
                    + "&useUnicode=true"
                    + "&characterEncoding=UTF-8"
                    + "&serverTimezone=UTC"
                    + "&rewriteBatchedStatements=true";
        }

        public String describe() {
            return host + ":" + port + "/" + database;
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
