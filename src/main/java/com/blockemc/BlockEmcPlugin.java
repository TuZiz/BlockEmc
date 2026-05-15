package com.blockemc;

import com.blockemc.command.BlockEmcCommand;
import com.blockemc.compat.ServerScheduler;
import com.blockemc.config.GuiConfigLoader;
import com.blockemc.config.MessageService;
import com.blockemc.config.ShopRegistry;
import com.blockemc.config.ValueRegistry;
import com.blockemc.listener.MenuListener;
import com.blockemc.model.LayoutTemplate;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.StorageSettings;
import com.blockemc.service.AccountService;
import com.blockemc.service.ExchangeService;
import com.blockemc.service.GuiService;
import java.io.File;
import java.util.List;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlockEmcPlugin extends JavaPlugin {

    private PluginSettings settings;
    private StorageSettings storageSettings;
    private MessageService messages;
    private GuiConfigLoader guiConfigLoader;
    private ValueRegistry valueRegistry;
    private ShopRegistry shopRegistry;
    private AccountService accountService;
    private ExchangeService exchangeService;
    private GuiService guiService;
    private ServerScheduler scheduler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        applyConfigDefaults();
        saveBundledResources();
        try {
            this.scheduler = new ServerScheduler(this);
        } catch (IllegalStateException exception) {
            getLogger().severe("Failed to initialize server scheduler safely: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.settings = PluginSettings.load(getConfig());
        this.storageSettings = StorageSettings.load(getConfig());
        this.messages = new MessageService(this);
        this.guiConfigLoader = new GuiConfigLoader();
        this.valueRegistry = new ValueRegistry(this, settings);
        this.valueRegistry.reload();
        this.shopRegistry = new ShopRegistry(this, guiConfigLoader, valueRegistry);
        this.shopRegistry.reload();
        this.accountService = new AccountService(this, storageSettings, settings);
        this.accountService.reload();
        this.accountService.startAutoSave();
        this.accountService.recoverPendingSells();

        this.exchangeService = new ExchangeService(this, settings, valueRegistry, accountService, scheduler);
        this.guiService = new GuiService(scheduler, messages, valueRegistry, shopRegistry, accountService, exchangeService);
        reloadGuiLayouts();

        BlockEmcCommand command = new BlockEmcCommand(this, messages, valueRegistry, shopRegistry, accountService, guiService, scheduler);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("uemc"), "uemc command missing in plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new MenuListener(guiService, accountService), this);

        getLogger().info("BlockEmc enabled on " + (scheduler.isFolia() ? "Folia" : "Spigot/Paper")
                + ", storage=" + accountService.getStorageDescription());
    }

    @Override
    public void onDisable() {
        if (guiService != null) {
            guiService.closeAll();
        }
        if (valueRegistry != null) {
            valueRegistry.shutdown();
        }
        if (accountService != null) {
            accountService.shutdown();
        }
    }

    public void reloadPluginState() {
        reloadConfig();
        applyConfigDefaults();
        messages.reload();
        settings = PluginSettings.load(getConfig());
        storageSettings = StorageSettings.load(getConfig());
        valueRegistry.flushSaves();
        valueRegistry.updateSettings(settings);
        valueRegistry.reload();
        shopRegistry.reload();
        accountService.applyPluginSettings(settings);
        accountService.applyStorageSettings(storageSettings);
        accountService.reload();
        exchangeService.updateSettings(settings);
        reloadGuiLayouts();
    }

    private void reloadGuiLayouts() {
        LayoutTemplate mainLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/main.yml"));
        LayoutTemplate categoriesLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/categories.yml"));
        LayoutTemplate bulkSellLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/bulk-sell.yml"));
        LayoutTemplate adminLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/admin-edit.yml"));
        LayoutTemplate favoritesLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/favorites.yml"));
        LayoutTemplate confirmLayout = guiConfigLoader.load(new File(getDataFolder(), "gui/confirm.yml"));
        guiService.reloadLayouts(mainLayout, categoriesLayout, bulkSellLayout, adminLayout, favoritesLayout, confirmLayout, settings);
    }

    private void saveBundledResources() {
        for (String resource : List.of(
                "values.yml",
                "accounts.yml",
                "temporary-materials.yml",
                "gui/main.yml",
                "gui/categories.yml",
                "gui/bulk-sell.yml",
                "gui/admin-edit.yml",
                "gui/favorites.yml",
                "gui/confirm.yml",
                "lang/zh_CN.yml",
                "shops/wool.yml",
                "shops/wood.yml",
                "shops/temporary.yml",
                "shops/stone.yml",
                "shops/special.yml",
                "shops/redstone.yml",
                "shops/prismarine.yml",
                "shops/polished.yml",
                "shops/nether.yml",
                "shops/nature.yml",
                "shops/modern.yml",
                "shops/glazed.yml",
                "shops/glass.yml",
                "shops/decoration.yml",
                "shops/copper.yml",
                "shops/colored.yml"
        )) {
            saveResourceIfMissing(resource);
        }
    }

    private void applyConfigDefaults() {
        getConfig().addDefault("security.sell-custom-items", false);
        getConfig().addDefault("security.strict-item-match", true);
        getConfig().addDefault("limits.max-single-price", PluginSettings.DEFAULT_MAX_SINGLE_PRICE);
        getConfig().addDefault("limits.max-transaction-amount", PluginSettings.DEFAULT_MAX_TRANSACTION_AMOUNT);
        getConfig().addDefault("limits.max-balance", PluginSettings.DEFAULT_MAX_BALANCE);
        getConfig().addDefault("storage.mysql.username", "blockemc");
        getConfig().addDefault("storage.mysql.password", "CHANGE_ME");
        getConfig().addDefault("storage.mysql.use-ssl", true);
        getConfig().addDefault("storage.mysql.allow-public-key-retrieval", false);
        getConfig().addDefault("storage.mysql.pool.maximum-pool-size", 10);
        getConfig().addDefault("storage.mysql.pool.minimum-idle", 2);
        getConfig().addDefault("storage.mysql.pool.connection-timeout-ms", 10_000L);
        getConfig().addDefault("storage.mysql.pool.idle-timeout-ms", 600_000L);
        getConfig().addDefault("storage.mysql.pool.max-lifetime-ms", 1_800_000L);
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private void saveResourceIfMissing(String path) {
        File target = new File(getDataFolder(), path);
        if (!target.exists()) {
            saveResource(path, false);
        }
    }
}
