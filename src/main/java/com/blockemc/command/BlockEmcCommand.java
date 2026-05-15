package com.blockemc.command;

import com.blockemc.BlockEmcPlugin;
import com.blockemc.config.MessageService;
import com.blockemc.config.ShopRegistry;
import com.blockemc.config.ValueRegistry;
import com.blockemc.model.ExchangeMode;
import com.blockemc.model.ShopDefinition;
import com.blockemc.service.AccountService;
import com.blockemc.service.GuiService;
import com.blockemc.service.ShopAuditService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class BlockEmcCommand implements CommandExecutor, TabCompleter {

    private final BlockEmcPlugin plugin;
    private final MessageService messages;
    private final ValueRegistry valueRegistry;
    private final ShopRegistry shopRegistry;
    private final AccountService accountService;
    private final GuiService guiService;
    private final ShopAuditService auditService;

    public BlockEmcCommand(
            BlockEmcPlugin plugin,
            MessageService messages,
            ValueRegistry valueRegistry,
            ShopRegistry shopRegistry,
            AccountService accountService,
            GuiService guiService
    ) {
        this.plugin = plugin;
        this.messages = messages;
        this.valueRegistry = valueRegistry;
        this.shopRegistry = shopRegistry;
        this.accountService = accountService;
        this.guiService = guiService;
        this.auditService = new ShopAuditService(plugin, valueRegistry, shopRegistry);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sendHelp(sender);
                return true;
            }
            accountService.notePlayer(player);
            guiService.openMain(player);
            return true;
        }

        String subCommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subCommand) {
            case "gui" -> handleGui(sender, args);
            case "balance" -> handleBalance(sender, args);
            case "top" -> handleTop(sender, args);
            case "reload" -> handleReload(sender);
            case "admin" -> handleAdmin(sender);
            case "audit" -> handleAudit(sender, args);
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender);
            case "give", "set", "take" -> handlePlayerBalance(sender, subCommand, args);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleGui(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&c该命令只能由玩家执行。");
            return true;
        }
        if (args.length == 1 || "main".equalsIgnoreCase(args[1])) {
            guiService.openMain(player);
            return true;
        }
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "categories", "shop" -> {
                guiService.openCategories(player);
                yield true;
            }
            case "bulk", "sell" -> {
                guiService.openBulkSell(player);
                yield true;
            }
            case "favorites", "favorite" -> {
                guiService.openFavorites(player);
                yield true;
            }
            case "hot", "popular" -> {
                guiService.openHot(player);
                yield true;
            }
            case "recommend", "inspire", "idea" -> {
                guiService.openRecommendations(player);
                yield true;
            }
            case "admin" -> {
                if (!player.hasPermission("blockemc.admin.gui")) {
                    messages.send(player, "uemc-no-permission");
                    yield true;
                }
                guiService.openAdmin(player);
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    private boolean handleBalance(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                messages.sendRaw(sender, "&c控制台请使用 /uemc balance <玩家名>。");
                return true;
            }
            messages.send(sender, "uemc-balance", accountService.getBalance(player.getUniqueId()));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "uemc-player-not-found", args[1]);
            return true;
        }
        messages.send(sender, "uemc-balance", accountService.getBalance(target.getUniqueId()));
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        if (args.length > 1 && List.of("today", "daily", "sale", "sold").contains(args[1].toLowerCase(Locale.ROOT))) {
            int page = parsePositiveInt(args.length > 2 ? args[2] : "1", 1);
            int totalPages = accountService.getDailyLeaderboardTotalPages(10);
            List<AccountService.DailyLeaderboardEntry> entries = accountService.getDailyLeaderboardPage(page, 10);
            if (entries.isEmpty()) {
                messages.send(sender, "uemc-top-daily-empty");
                return true;
            }
            messages.send(sender, "uemc-top-daily-header", page, totalPages);
            int index = (page - 1) * 10 + 1;
            for (AccountService.DailyLeaderboardEntry entry : entries) {
                messages.send(sender, "uemc-top-daily-entry", index++, entry.name(), entry.soldEmc(), entry.topMaterialAmount());
            }
            return true;
        }

        int page = parsePositiveInt(args.length > 1 ? args[1] : "1", 1);
        int totalPages = accountService.getLeaderboardTotalPages(10);
        List<AccountService.LeaderboardEntry> entries = accountService.getLeaderboardPage(page, 10);
        if (entries.isEmpty()) {
            messages.send(sender, "uemc-top-empty");
            return true;
        }
        messages.send(sender, "uemc-top-header", page, totalPages);
        int index = (page - 1) * 10 + 1;
        for (AccountService.LeaderboardEntry entry : entries) {
            messages.send(sender, "uemc-top-entry", index++, entry.name(), entry.balance());
        }
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("blockemc.admin.reload")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }
        plugin.reloadPluginState();
        messages.send(sender, "uemc-reload");
        return true;
    }

    private boolean handleAdmin(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&c该命令只能由玩家执行。");
            return true;
        }
        if (!sender.hasPermission("blockemc.admin.gui")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }
        guiService.openAdmin(player);
        return true;
    }

    private boolean handleAudit(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockemc.admin.audit")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }
        if (args.length < 2 || !"blocks".equalsIgnoreCase(args[1])) {
            messages.sendRaw(sender, "&c用法: /uemc audit blocks");
            return true;
        }
        for (String line : auditService.buildBlockAuditReport()) {
            messages.sendRaw(sender, line);
        }
        return true;
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&c该命令只能由玩家执行。");
            return true;
        }
        if (!sender.hasPermission("blockemc.admin.edit")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "uemc-admin-invalid-price");
            return true;
        }

        long price = parsePositiveLong(args[1], -1L);
        if (price < 0L) {
            messages.send(sender, "uemc-admin-invalid-price");
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            messages.send(sender, "uemc-mainhand-empty");
            return true;
        }

        ExchangeMode mode = args.length > 2 ? ExchangeMode.fromConfig(args[2]) : ExchangeMode.BOTH;
        valueRegistry.set(hand.getType(), price, mode);
        if (!isInConfiguredShop(hand.getType())) {
            valueRegistry.addTemporaryMaterial(hand.getType());
        }
        shopRegistry.reload();
        messages.send(sender, "uemc-admin-set-hand", hand.getType().name(), price, mode.configValue());
        return true;
    }

    private boolean handleRemove(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendRaw(sender, "&c该命令只能由玩家执行。");
            return true;
        }
        if (!sender.hasPermission("blockemc.admin.edit")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            messages.send(sender, "uemc-mainhand-empty");
            return true;
        }

        valueRegistry.remove(hand.getType());
        shopRegistry.reload();
        messages.send(sender, "uemc-admin-remove", hand.getType().name());
        return true;
    }

    private boolean handlePlayerBalance(CommandSender sender, String action, String[] args) {
        if (!sender.hasPermission("blockemc.admin.balance")) {
            messages.send(sender, "uemc-no-permission");
            return true;
        }
        if (args.length < 3) {
            messages.send(sender, "uemc-admin-invalid-player-amount");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "uemc-player-not-found", args[1]);
            return true;
        }
        long amount = parsePositiveLong(args[2], -1L);
        if (amount < 0L) {
            messages.send(sender, "uemc-admin-invalid-player-amount");
            return true;
        }

        switch (action) {
            case "give" -> {
                long current = accountService.addBalance(target.getUniqueId(), target.getName(), amount);
                messages.send(sender, "uemc-admin-add-player", target.getName(), amount, current);
            }
            case "set" -> {
                accountService.setBalance(target.getUniqueId(), target.getName(), amount);
                messages.send(sender, "uemc-admin-set-player", target.getName(), amount);
            }
            case "take" -> {
                long current = accountService.takeBalance(target.getUniqueId(), target.getName(), amount);
                messages.send(sender, "uemc-admin-take-player", target.getName(), amount, current);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean isInConfiguredShop(Material material) {
        for (ShopDefinition shop : shopRegistry.getAll()) {
            if (shop.materials().contains(material)) {
                return true;
            }
        }
        return false;
    }

    private long parsePositiveLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void sendHelp(CommandSender sender) {
        messages.sendRaw(sender, "&6/uemc &7- 打开主菜单");
        messages.sendRaw(sender, "&6/uemc gui <main|categories|bulk|favorites|admin> &7- 打开指定界面");
        messages.sendRaw(sender, "&6/uemc balance [玩家名] &7- 查看 EMC 余额");
        messages.sendRaw(sender, "&6/uemc top [页码] &7- 查看总 EMC 排行");
        messages.sendRaw(sender, "&6/uemc top today [页码] &7- 查看今日出售排行");
        if (sender.hasPermission("blockemc.admin.reload")) {
            messages.sendRaw(sender, "&6/uemc reload &7- 重载插件配置");
        }
        if (sender.hasPermission("blockemc.admin.audit")) {
            messages.sendRaw(sender, "&6/uemc audit blocks &7- 审计建筑商品表");
        }
        if (sender.hasPermission("blockemc.admin.edit")) {
            messages.sendRaw(sender, "&6/uemc add <价格> [both|sell_only|buy_only] &7- 设置手持物品 EMC");
            messages.sendRaw(sender, "&6/uemc remove &7- 删除手持物品 EMC");
        }
        if (sender.hasPermission("blockemc.admin.balance")) {
            messages.sendRaw(sender, "&6/uemc give/set/take <玩家名> <数量> &7- 调整玩家 EMC");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("gui", "balance", "top", "reload", "admin", "audit", "add", "remove", "give", "set", "take"), args[0]);
        }
        if (args.length == 2 && "gui".equalsIgnoreCase(args[0])) {
            return filter(List.of("main", "categories", "bulk", "favorites", "hot", "recommend", "admin"), args[1]);
        }
        if (args.length == 2 && "top".equalsIgnoreCase(args[0])) {
            return filter(List.of("today"), args[1]);
        }
        if (args.length == 2 && "audit".equalsIgnoreCase(args[0])) {
            return filter(List.of("blocks"), args[1]);
        }
        if (args.length == 2 && List.of("give", "set", "take", "balance").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && "add".equalsIgnoreCase(args[0])) {
            return filter(List.of("both", "sell_only", "buy_only"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        List<String> matches = new ArrayList<>();
        String lowered = prefix.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                matches.add(value);
            }
        }
        return matches;
    }
}
