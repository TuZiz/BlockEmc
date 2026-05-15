package com.blockemc.service;

import com.blockemc.compat.ServerScheduler;
import com.blockemc.config.MessageService;
import com.blockemc.config.ShopRegistry;
import com.blockemc.config.ValueRegistry;
import com.blockemc.gui.MenuHolder;
import com.blockemc.gui.MenuView;
import com.blockemc.model.DisplayTemplate;
import com.blockemc.model.IconTemplate;
import com.blockemc.model.LayoutTemplate;
import com.blockemc.model.MaterialValue;
import com.blockemc.model.PluginSettings;
import com.blockemc.model.ShopDefinition;
import com.blockemc.model.TradeResult;
import com.blockemc.util.ColorUtil;
import com.blockemc.util.ItemStackUtil;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class GuiService {

    private record PageSlice<T>(List<T> entries, int page, boolean hasPrevious, boolean hasNext) {
    }

    private record RecommendationEntry(MaterialValue value, String reason) {
    }

    private final ServerScheduler scheduler;
    private final MessageService messages;
    private final ValueRegistry valueRegistry;
    private final ShopRegistry shopRegistry;
    private final AccountService accountService;
    private final ExchangeService exchangeService;
    private final Map<UUID, Deque<Consumer<Player>>> history = new HashMap<>();
    private final Map<UUID, Consumer<Player>> currentOpeners = new HashMap<>();
    private final Map<UUID, MenuView> openViews = new HashMap<>();
    private final Set<UUID> suppressBulkReturn = ConcurrentHashMap.newKeySet();

    private LayoutTemplate mainLayout;
    private LayoutTemplate categoriesLayout;
    private LayoutTemplate bulkSellLayout;
    private LayoutTemplate adminLayout;
    private LayoutTemplate favoritesLayout;
    private LayoutTemplate confirmLayout;
    private PluginSettings settings;

    public GuiService(
            ServerScheduler scheduler,
            MessageService messages,
            ValueRegistry valueRegistry,
            ShopRegistry shopRegistry,
            AccountService accountService,
            ExchangeService exchangeService
    ) {
        this.scheduler = scheduler;
        this.messages = messages;
        this.valueRegistry = valueRegistry;
        this.shopRegistry = shopRegistry;
        this.accountService = accountService;
        this.exchangeService = exchangeService;
    }

    public void reloadLayouts(
            LayoutTemplate mainLayout,
            LayoutTemplate categoriesLayout,
            LayoutTemplate bulkSellLayout,
            LayoutTemplate adminLayout,
            LayoutTemplate favoritesLayout,
            LayoutTemplate confirmLayout,
            PluginSettings settings
    ) {
        this.mainLayout = mainLayout;
        this.categoriesLayout = categoriesLayout;
        this.bulkSellLayout = bulkSellLayout;
        this.adminLayout = adminLayout;
        this.favoritesLayout = favoritesLayout;
        this.confirmLayout = confirmLayout;
        this.settings = settings;
    }

    public void openMain(Player player) {
        resetHistory(player);
        openMainInternal(player, false);
    }

    public void openCategories(Player player) {
        resetHistory(player);
        openCategoriesInternal(player, 1, false);
    }

    public void openBulkSell(Player player) {
        resetHistory(player);
        openBulkSellInternal(player, false);
    }

    public void openAdmin(Player player) {
        resetHistory(player);
        openAdminInternal(player, 1, false);
    }

    public void openFavorites(Player player) {
        resetHistory(player);
        openFavoritesInternal(player, 1, false);
    }

    public void openHot(Player player) {
        resetHistory(player);
        openHotInternal(player, 1, false);
    }

    public void openRecommendations(Player player) {
        resetHistory(player);
        openRecommendationsInternal(player, 1, false);
    }

    public void handleClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        MenuView view = holder.view();
        if (view == null) {
            return;
        }
        view.closeHandler().accept(event);

        Player player = (Player) event.getPlayer();
        if (openViews.get(player.getUniqueId()) == view) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)) {
                openViews.remove(player.getUniqueId());
                currentOpeners.remove(player.getUniqueId());
            }
        }
    }

    public void handleQuit(Player player) {
        history.remove(player.getUniqueId());
        currentOpeners.remove(player.getUniqueId());
        openViews.remove(player.getUniqueId());
        suppressBulkReturn.remove(player.getUniqueId());
    }

    public void closeAll() {
        for (UUID uniqueId : new ArrayList<>(openViews.keySet())) {
            Player player = Bukkit.getPlayer(uniqueId);
            if (player != null) {
                player.closeInventory();
            }
        }
        history.clear();
        currentOpeners.clear();
        openViews.clear();
        suppressBulkReturn.clear();
    }

    private void openMainInternal(Player player, boolean pushHistory) {
        if (mainLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }
        MenuView view = createView(player, mainLayout);
        for (Map.Entry<Integer, IconTemplate> entry : mainLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "buildings", "categories" -> bindAction(view, slot, player, icon, click -> openCategoriesInternal(player, 1, true));
                case "recommend" -> bindAction(view, slot, player, icon, click -> openRecommendationsInternal(player, 1, true));
                case "hot" -> bindAction(view, slot, player, icon, click -> openHotInternal(player, 1, true));
                case "bulk_sell" -> bindAction(view, slot, player, icon, click -> openBulkSellInternal(player, true));
                case "favorites" -> bindAction(view, slot, player, icon, click -> openFavoritesInternal(player, 1, true));
                case "status" -> bindAction(view, slot, player, icon, click -> messages.send(player, "uemc-balance", accountService.getBalance(player.getUniqueId())));
                case "back", "close" -> bindAction(view, slot, player, icon, click -> player.closeInventory());
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }
        openMenu(player, reopened -> openMainInternal(reopened, false), view, pushHistory);
    }

    private void openCategoriesInternal(Player player, int requestedPage, boolean pushHistory) {
        if (categoriesLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }
        MenuView view = createView(player, categoriesLayout);
        PageSlice<ShopDefinition> page = paginate(shopRegistry.getCategoryShops(), requestedPage, categoriesLayout.itemSlots().size());
        ShopDefinition temporaryShop = shopRegistry.get("temporary");

        for (Map.Entry<Integer, IconTemplate> entry : categoriesLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openCategoriesInternal(player, page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openCategoriesInternal(player, page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> bindAction(view, slot, player, icon, click -> messages.send(player, "uemc-balance", accountService.getBalance(player.getUniqueId())));
                case "temporary" -> {
                    setItem(view.inventory(), slot, player, icon.resolve(false));
                    if (temporaryShop != null && !temporaryShop.materials().isEmpty()) {
                        view.onClick(slot, click -> openShopInternal(player, temporaryShop.id(), 1, true));
                    }
                }
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        for (int index = 0; index < categoriesLayout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = categoriesLayout.itemSlots().get(index);
            ShopDefinition shop = page.entries().get(index);
            view.inventory().setItem(slot, ItemStackUtil.createItem(shop.icon(), applyPlaceholders(player, shop.name()), applyPlaceholders(player, shop.lore())));
            view.onClick(slot, click -> openShopInternal(player, shop.id(), 1, true));
        }

        openMenu(player, reopened -> openCategoriesInternal(reopened, page.page(), false), view, pushHistory);
    }

    private void openFavoritesInternal(Player player, int requestedPage, boolean pushHistory) {
        if (favoritesLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        List<MaterialValue> values = accountService.getFavorites(player.getUniqueId()).stream()
                .map(valueRegistry::get)
                .flatMap(Optional::stream)
                .filter(value -> valueRegistry.isVisible(value.material()))
                .sorted(playerMaterialComparator(player))
                .toList();

        MenuView view = createView(player, favoritesLayout);
        PageSlice<MaterialValue> page = paginate(values, requestedPage, favoritesLayout.itemSlots().size());
        for (Map.Entry<Integer, IconTemplate> entry : favoritesLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openFavoritesInternal(player, page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openFavoritesInternal(player, page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> bindAction(view, slot, player, icon, click -> messages.send(player, "uemc-balance", accountService.getBalance(player.getUniqueId())));
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        Runnable reopen = () -> openFavoritesInternal(player, page.page(), false);
        for (int index = 0; index < favoritesLayout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = favoritesLayout.itemSlots().get(index);
            MaterialValue value = page.entries().get(index);
            view.inventory().setItem(slot, buildTradeItemLore(player, value));
            view.onClick(slot, click -> handleTradeClick(player, value, click.getClick(), reopen));
        }

        openMenu(player, reopened -> openFavoritesInternal(reopened, page.page(), false), view, pushHistory);
    }

    private void openHotInternal(Player player, int requestedPage, boolean pushHistory) {
        if (favoritesLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        List<MaterialValue> values = accountService.getHotMaterials(256).stream()
                .map(AccountService.HotMaterialEntry::material)
                .map(valueRegistry::get)
                .flatMap(Optional::stream)
                .filter(value -> valueRegistry.isVisible(value.material()))
                .filter(value -> value.mode().canBuy())
                .sorted(Comparator
                        .comparingInt((MaterialValue value) -> accountService.getPurchaseHeat(value.material())).reversed()
                        .thenComparing(playerMaterialComparator(player)))
                .toList();

        MenuView view = createView(player, favoritesLayout, "&0[ &6热卖榜 &0] &8热门建材");
        PageSlice<MaterialValue> page = paginate(values, requestedPage, favoritesLayout.itemSlots().size());
        for (Map.Entry<Integer, IconTemplate> entry : favoritesLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openHotInternal(player, page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openHotInternal(player, page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> {
                    setCustomItem(
                            view.inventory(),
                            slot,
                            player,
                            Material.BLAZE_POWDER,
                            "&6&l热卖榜说明",
                            List.of(
                                    "&7按全服累计购买次数排序",
                                    "&7越靠前代表建筑玩家越常买",
                                    "&7当前已记录热门方块: &f" + values.size()
                            )
                    );
                    view.onClick(slot, click -> openHotInternal(player, 1, false));
                }
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        Runnable reopen = () -> openHotInternal(player, page.page(), false);
        for (int index = 0; index < favoritesLayout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = favoritesLayout.itemSlots().get(index);
            MaterialValue value = page.entries().get(index);
            int heat = accountService.getPurchaseHeat(value.material());
            view.inventory().setItem(slot, buildCuratedTradeItem(player, value, List.of(
                    "&6热度次数: &f" + heat,
                    "&7全服建筑玩家常买建材"
            )));
            view.onClick(slot, click -> handleTradeClick(player, value, click.getClick(), reopen));
        }

        openMenu(player, reopened -> openHotInternal(reopened, page.page(), false), view, pushHistory);
    }
    private void openRecommendationsInternal(Player player, int requestedPage, boolean pushHistory) {
        if (favoritesLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        List<RecommendationEntry> recommendations = buildRecommendations(player);
        MenuView view = createView(player, favoritesLayout, "&0[ &d灵感推荐 &0] &8搭配建材");
        PageSlice<RecommendationEntry> page = paginate(recommendations, requestedPage, favoritesLayout.itemSlots().size());
        for (Map.Entry<Integer, IconTemplate> entry : favoritesLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openRecommendationsInternal(player, page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openRecommendationsInternal(player, page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> {
                    setCustomItem(
                            view.inventory(),
                            slot,
                            player,
                            Material.COMPASS,
                            "&d&l灵感推荐说明",
                            List.of(
                                    "&7优先参考你的收藏分类",
                                    "&7再结合全服热卖建材进行推荐",
                                    "&7当前推荐数量: &f" + recommendations.size()
                            )
                    );
                    view.onClick(slot, click -> openRecommendationsInternal(player, 1, false));
                }
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        Runnable reopen = () -> openRecommendationsInternal(player, page.page(), false);
        for (int index = 0; index < favoritesLayout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = favoritesLayout.itemSlots().get(index);
            RecommendationEntry entry = page.entries().get(index);
            view.inventory().setItem(slot, buildCuratedTradeItem(player, entry.value(), List.of(
                    "&d推荐理由: &f" + entry.reason(),
                    "&7适合继续补全你的建筑搭配"
            )));
            view.onClick(slot, click -> handleTradeClick(player, entry.value(), click.getClick(), reopen));
        }

        openMenu(player, reopened -> openRecommendationsInternal(reopened, page.page(), false), view, pushHistory);
    }
    private void openShopInternal(Player player, String shopId, int requestedPage, boolean pushHistory) {
        ShopDefinition shop = shopRegistry.get(shopId);
        if (shop == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        LayoutTemplate layout = shop.layout();
        MenuView view = createView(player, layout);
        List<MaterialValue> values = shop.materials().stream()
                .map(valueRegistry::get)
                .flatMap(Optional::stream)
                .sorted(playerMaterialComparator(player))
                .toList();
        PageSlice<MaterialValue> page = paginate(values, requestedPage, layout.itemSlots().size());

        for (Map.Entry<Integer, IconTemplate> entry : layout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openShopInternal(player, shop.id(), page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openShopInternal(player, shop.id(), page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> bindAction(view, slot, player, icon, click -> messages.send(player, "uemc-balance", accountService.getBalance(player.getUniqueId())));
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        for (int index = 0; index < layout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = layout.itemSlots().get(index);
            MaterialValue value = page.entries().get(index);
            view.inventory().setItem(slot, buildTradeItemLore(player, value));
            view.onClick(slot, click -> handleTradeClick(player, value, click.getClick(), () -> openShopInternal(player, shop.id(), page.page(), false)));
        }

        openMenu(player, reopened -> openShopInternal(reopened, shop.id(), page.page(), false), view, pushHistory);
    }

    private void openBulkSellInternal(Player player, boolean pushHistory) {
        if (bulkSellLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        MenuView view = createView(player, bulkSellLayout);
        view.allowBottomInteractions(true);
        view.inputValidator(exchangeService::isSellable);
        for (int slot : bulkSellLayout.inputSlots()) {
            view.addInputSlot(slot);
        }

        for (Map.Entry<Integer, IconTemplate> entry : bulkSellLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "sell" -> bindAction(view, slot, player, icon, click -> handleBulkSellClick(player, view));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "status" -> bindAction(view, slot, player, icon, click -> messages.send(player, "uemc-balance", accountService.getBalance(player.getUniqueId())));
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        view.onClose(event -> {
            if (suppressBulkReturn.remove(player.getUniqueId())) {
                return;
            }
            returnInputItems(player, event.getInventory(), bulkSellLayout.inputSlots());
        });
        openMenu(player, reopened -> openBulkSellInternal(reopened, false), view, pushHistory);
    }

    private void openAdminInternal(Player player, int requestedPage, boolean pushHistory) {
        if (adminLayout == null) {
            messages.send(player, "uemc-gui-config-error");
            return;
        }

        MenuView view = createView(player, adminLayout);
        List<MaterialValue> values = new ArrayList<>(valueRegistry.getAllSorted().stream().toList());
        PageSlice<MaterialValue> page = paginate(values, requestedPage, adminLayout.itemSlots().size());

        for (Map.Entry<Integer, IconTemplate> entry : adminLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "last" -> bindPageAction(view, slot, player, icon, page.hasPrevious(), click -> openAdminInternal(player, page.page() - 1, false));
                case "next" -> bindPageAction(view, slot, player, icon, page.hasNext(), click -> openAdminInternal(player, page.page() + 1, false));
                case "back" -> bindAction(view, slot, player, icon, click -> goBackOrClose(player));
                case "item", "input" -> {
                }
                default -> setItem(view.inventory(), slot, player, icon.resolve(false));
            }
        }

        for (int index = 0; index < adminLayout.itemSlots().size() && index < page.entries().size(); index++) {
            int slot = adminLayout.itemSlots().get(index);
            MaterialValue value = page.entries().get(index);
            view.inventory().setItem(slot, buildAdminLore(value));
            view.onClick(slot, click -> {
                if (click.getClick() == ClickType.CONTROL_DROP) {
                    valueRegistry.remove(value.material());
                    shopRegistry.reload();
                    messages.send(player, "uemc-admin-remove", value.material().name());
                    openAdminInternal(player, page.page(), false);
                    return;
                }
                if (click.getClick() == ClickType.DROP) {
                    boolean nextHidden = !valueRegistry.isHidden(value.material());
                    valueRegistry.setHidden(value.material(), nextHidden);
                    shopRegistry.reload();
                    messages.send(player, nextHidden ? "uemc-admin-hide" : "uemc-admin-show", value.material().name());
                    openAdminInternal(player, page.page(), false);
                    return;
                }
                if (click.getClick() == ClickType.MIDDLE || click.getClick() == ClickType.SWAP_OFFHAND) {
                    cycleMaterialCategory(player, value);
                    openAdminInternal(player, page.page(), false);
                    return;
                }

                long nextValue = switch (click.getClick()) {
                    case SHIFT_LEFT -> value.emc() + 10L;
                    case LEFT -> value.emc() + 1L;
                    case SHIFT_RIGHT -> Math.max(1L, value.emc() - 10L);
                    case RIGHT -> Math.max(1L, value.emc() - 1L);
                    default -> value.emc();
                };

                if (nextValue != value.emc()) {
                    valueRegistry.set(value.material(), nextValue, value.mode());
                    shopRegistry.reload();
                    messages.send(player, "uemc-admin-adjust", value.material().name(), nextValue);
                    openAdminInternal(player, page.page(), false);
                }
            });
        }

        openMenu(player, reopened -> openAdminInternal(reopened, page.page(), false), view, pushHistory);
    }

    private void handleTradeClick(Player player, MaterialValue value, ClickType clickType, Runnable reopenAction) {
        switch (clickType) {
            case LEFT -> handleQuickBuy(player, value.material(), 1, false, reopenAction);
            case RIGHT -> handleQuickBuy(player, value.material(), 16, false, reopenAction);
            case SHIFT_LEFT -> handleQuickBuy(player, value.material(), 64, false, reopenAction);
            case SHIFT_RIGHT -> handleQuickBuy(player, value.material(), settings.maxSingleTrade(), true, reopenAction);
            case MIDDLE, SWAP_OFFHAND -> {
                boolean added = accountService.toggleFavorite(player.getUniqueId(), value.material());
                messages.send(player, added ? "uemc-favorite-add" : "uemc-favorite-remove");
                reopenAction.run();
            }
            case DROP -> {
                TradeResult result = exchangeService.sell(player, value.material(), 1);
                messages.send(player, result.messageKey(), result.args());
                reopenAction.run();
            }
            case CONTROL_DROP -> {
                TradeResult result = exchangeService.sellAll(player, value.material());
                messages.send(player, result.messageKey(), result.args());
                reopenAction.run();
            }
            default -> {
            }
        }
    }

    private void handleQuickBuy(Player player, Material material, int amount, boolean maximum, Runnable reopenAction) {
        ExchangeService.BuyQuote quote = maximum
                ? exchangeService.quoteBuyMaximum(player, material, amount)
                : exchangeService.quoteBuy(player, material, amount);
        if (!quote.success()) {
            messages.send(player, quote.messageKey(), quote.args());
            reopenAction.run();
            return;
        }

        if (quote.cost() >= settings.highValueBuyConfirmEmc()) {
            openConfirmMenu(
                    player,
                    quote.material(),
                    "&6&l高价值购买确认",
                    List.of(
                            "&7本次将购买: &f" + quote.amount() + " &7个",
                            "&7预计消耗: &6" + quote.cost() + " EMC",
                            "&7当前余额: &f" + accountService.getBalance(player.getUniqueId()) + " EMC",
                            "",
                            "&c这是一次高价值交易，请确认后继续。"
                    ),
                    confirmed -> {
                        TradeResult result = maximum
                                ? exchangeService.buyMaximum(confirmed, material, amount)
                                : exchangeService.buy(confirmed, material, quote.amount());
                        messages.send(confirmed, result.messageKey(), result.args());
                        reopenAction.run();
                    },
                    cancelled -> reopenAction.run()
            );
            return;
        }

        TradeResult result = maximum
                ? exchangeService.buyMaximum(player, material, amount)
                : exchangeService.buy(player, material, quote.amount());
        messages.send(player, result.messageKey(), result.args());
        reopenAction.run();
    }
    private void handleBulkSellClick(Player player, MenuView view) {
        ExchangeService.BulkSellQuote quote = exchangeService.previewBulkSell(view.inventory(), bulkSellLayout.inputSlots());
        if (!quote.success()) {
            messages.send(player, quote.messageKey(), quote.args());
            refreshStatusItems(player, view.inventory(), bulkSellLayout);
            return;
        }

        if (quote.reward() >= settings.highValueBulkSellConfirmEmc()) {
            Material previewMaterial = quote.soldMaterials().keySet().stream().findFirst().orElse(Material.CHEST);
            List<String> summary = new ArrayList<>();
            summary.add("&7预计回收 EMC: &a" + quote.reward());
            summary.add("&7涉及种类: &f" + quote.soldMaterials().size() + " &7种");
            int totalAmount = quote.soldMaterials().values().stream().mapToInt(Integer::intValue).sum();
            summary.add("&7总出售数量: &f" + totalAmount);
            summary.add("");
            quote.soldMaterials().entrySet().stream()
                    .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                    .limit(3)
                    .forEach(entry -> summary.add("&8- &7" + entry.getKey().name() + " &fx" + entry.getValue()));
            summary.add("");
            summary.add("&c这是一次高价值批量出售，请确认后继续。");

            suppressBulkReturn.add(player.getUniqueId());
            openConfirmMenu(
                    player,
                    previewMaterial,
                    "&a&l批量出售确认",
                    summary,
                    confirmed -> {
                        TradeResult result = exchangeService.bulkSell(confirmed, view.inventory(), bulkSellLayout.inputSlots());
                        messages.send(confirmed, result.messageKey(), result.args());
                        openBulkSellInternal(confirmed, false);
                    },
                    cancelled -> reopenExistingView(cancelled, view, reopened -> openBulkSellInternal(reopened, false))
            );
            return;
        }

        TradeResult result = exchangeService.bulkSell(player, view.inventory(), bulkSellLayout.inputSlots());
        messages.send(player, result.messageKey(), result.args());
        refreshStatusItems(player, view.inventory(), bulkSellLayout);
    }
    private void cycleMaterialCategory(Player player, MaterialValue value) {
        List<String> shopIds = shopRegistry.getOrderedShopIdsForAdmin();
        if (shopIds.isEmpty()) {
            return;
        }

        String current = shopRegistry.getEffectiveShopId(value.material());
        int currentIndex = current == null ? -1 : shopIds.indexOf(current.toLowerCase());
        int nextIndex = (currentIndex + 1 + shopIds.size()) % shopIds.size();
        String nextId = shopIds.get(nextIndex);
        valueRegistry.setCategoryOverride(value.material(), nextId);
        if ("temporary".equalsIgnoreCase(nextId)) {
            valueRegistry.addTemporaryMaterial(value.material());
        }
        shopRegistry.reload();
        messages.send(player, "uemc-admin-category", value.material().name(), shopRegistry.getShopDisplayName(nextId));
    }

    private void openMenu(Player player, Consumer<Player> opener, MenuView view, boolean pushHistory) {
        UUID uniqueId = player.getUniqueId();
        if (pushHistory) {
            Consumer<Player> current = currentOpeners.get(uniqueId);
            if (current != null) {
                history.computeIfAbsent(uniqueId, ignored -> new ArrayDeque<>()).push(current);
            }
        }
        currentOpeners.put(uniqueId, opener);
        openViews.put(uniqueId, view);
        scheduler.executePlayer(player, () -> player.openInventory(view.inventory()));
    }

    private void reopenExistingView(Player player, MenuView view, Consumer<Player> opener) {
        currentOpeners.put(player.getUniqueId(), opener);
        openViews.put(player.getUniqueId(), view);
        scheduler.executePlayer(player, () -> player.openInventory(view.inventory()));
    }

    private void openConfirmMenu(
            Player player,
            Material previewMaterial,
            String title,
            List<String> previewLore,
            Consumer<Player> confirmAction,
            Consumer<Player> cancelAction
    ) {
        if (confirmLayout == null) {
            confirmAction.accept(player);
            return;
        }

        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, confirmLayout.size(), applyPlaceholders(player, title));
        MenuView view = new MenuView(inventory);
        holder.bind(view);

        final boolean[] resolved = {false};
        for (Map.Entry<Integer, IconTemplate> entry : confirmLayout.iconSlots().entrySet()) {
            int slot = entry.getKey();
            IconTemplate icon = entry.getValue();
            switch (icon.function()) {
                case "confirm" -> bindAction(view, slot, player, icon, click -> {
                    resolved[0] = true;
                    confirmAction.accept(player);
                });
                case "cancel", "back" -> bindAction(view, slot, player, icon, click -> {
                    resolved[0] = true;
                    cancelAction.accept(player);
                });
                case "preview" -> inventory.setItem(slot, ItemStackUtil.createItem(previewMaterial, null, previewLore));
                default -> setItem(inventory, slot, player, icon.resolve(false));
            }
        }

        view.onClose(event -> {
            if (!resolved[0]) {
                scheduler.executePlayer(player, () -> cancelAction.accept(player));
            }
        });
        openMenu(player, reopened -> {
        }, view, false);
    }

    private void goBackOrClose(Player player) {
        Deque<Consumer<Player>> stack = history.get(player.getUniqueId());
        if (stack == null || stack.isEmpty()) {
            player.closeInventory();
            return;
        }
        stack.pop().accept(player);
    }

    private void resetHistory(Player player) {
        history.remove(player.getUniqueId());
    }

    private MenuView createView(Player player, LayoutTemplate layout) {
        return createView(player, layout, layout.title());
    }

    private MenuView createView(Player player, LayoutTemplate layout, String title) {
        MenuHolder holder = new MenuHolder();
        Inventory inventory = Bukkit.createInventory(holder, layout.size(), applyPlaceholders(player, title));
        MenuView view = new MenuView(inventory);
        holder.bind(view);
        refreshStatusItems(player, inventory, layout);
        return view;
    }

    private void bindAction(MenuView view, int slot, Player player, IconTemplate icon, Consumer<InventoryClickEvent> handler) {
        setItem(view.inventory(), slot, player, icon.resolve(false));
        view.onClick(slot, handler);
    }

    private void bindPageAction(MenuView view, int slot, Player player, IconTemplate icon, boolean enabled, Consumer<InventoryClickEvent> handler) {
        setItem(view.inventory(), slot, player, icon.resolve(enabled));
        if (enabled) {
            view.onClick(slot, handler);
        }
    }

    private void setItem(Inventory inventory, int slot, Player player, DisplayTemplate display) {
        if (display == null) {
            return;
        }
        inventory.setItem(slot, ItemStackUtil.createItem(
                display.material(),
                applyPlaceholders(player, display.name()),
                applyPlaceholders(player, display.lore())
        ));
    }

    private void setCustomItem(Inventory inventory, int slot, Player player, Material material, String name, List<String> lore) {
        inventory.setItem(slot, ItemStackUtil.createItem(
                material,
                applyPlaceholders(player, name),
                applyPlaceholders(player, lore)
        ));
    }

    private Comparator<MaterialValue> playerMaterialComparator(Player player) {
        UUID uniqueId = player.getUniqueId();
        return Comparator
                .comparing((MaterialValue value) -> !accountService.isFavorite(uniqueId, value.material()))
                .thenComparing(Comparator.comparingInt((MaterialValue value) -> accountService.getPurchaseHeat(value.material())).reversed())
                .thenComparing(value -> value.material().name());
    }

    private List<RecommendationEntry> buildRecommendations(Player player) {
        UUID uniqueId = player.getUniqueId();
        Set<Material> favorites = new LinkedHashSet<>(accountService.getFavorites(uniqueId));
        Set<String> favoriteCategories = favorites.stream()
                .map(shopRegistry::getEffectiveShopId)
                .filter(Objects::nonNull)
                .filter(id -> !"temporary".equalsIgnoreCase(id))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        List<RecommendationEntry> recommendations = new ArrayList<>();
        Set<Material> added = new HashSet<>();

        for (AccountService.HotMaterialEntry hotEntry : accountService.getHotMaterials(256)) {
            Material material = hotEntry.material();
            if (favorites.contains(material) || !added.add(material)) {
                continue;
            }
            MaterialValue value = valueRegistry.get(material).orElse(null);
            if (value == null || !valueRegistry.isVisible(material) || !value.mode().canBuy()) {
                continue;
            }
            String categoryId = shopRegistry.getEffectiveShopId(material);
            if (!favoriteCategories.isEmpty() && favoriteCategories.contains(categoryId)) {
                recommendations.add(new RecommendationEntry(value, "涓庝綘鏀惰棌鐨勫悓鍒嗙被寤烘潗椋庢牸鎺ヨ繎"));
            }
        }

        for (String categoryId : favoriteCategories) {
            ShopDefinition shop = shopRegistry.get(categoryId);
            if (shop == null) {
                continue;
            }
            shop.materials().stream()
                    .sorted(Comparator
                            .comparingInt((Material material) -> accountService.getPurchaseHeat(material)).reversed()
                            .thenComparing(Material::name))
                    .forEach(material -> {
                        if (favorites.contains(material) || !added.add(material)) {
                            return;
                        }
                        MaterialValue value = valueRegistry.get(material).orElse(null);
                        if (value == null || !valueRegistry.isVisible(material) || !value.mode().canBuy()) {
                            return;
                        }
                        recommendations.add(new RecommendationEntry(value, "鏉ヨ嚜浣犲父閫涚殑寤虹瓚鍒嗙被"));
                    });
        }

        if (recommendations.isEmpty()) {
            for (AccountService.HotMaterialEntry hotEntry : accountService.getHotMaterials(256)) {
                Material material = hotEntry.material();
                if (!added.add(material)) {
                    continue;
                }
                MaterialValue value = valueRegistry.get(material).orElse(null);
                if (value == null || !valueRegistry.isVisible(material) || !value.mode().canBuy()) {
                    continue;
                }
                recommendations.add(new RecommendationEntry(value, "鍏ㄦ湇寤虹瓚鐜╁杩戞湡鐑棬閫夋嫨"));
            }
        }

        return recommendations;
    }

    private ItemStack buildCuratedTradeItem(Player player, MaterialValue value, List<String> highlightLore) {
        List<String> lore = new ArrayList<>();
        if (highlightLore != null && !highlightLore.isEmpty()) {
            lore.addAll(highlightLore);
            lore.add("");
        }
        lore.addAll(buildTradeLoreLines(player, value));
        return ItemStackUtil.createItem(value.material(), null, lore);
    }

    private ItemStack buildTradeItemLore(Player player, MaterialValue value) {
        return ItemStackUtil.createItem(value.material(), null, buildTradeLoreLines(player, value));
    }

    private List<String> buildTradeLoreLines(Player player, MaterialValue value) {
        boolean favorite = accountService.isFavorite(player.getUniqueId(), value.material());
        List<String> lore = new ArrayList<>();
        lore.add("&7单价: &6" + value.emc() + " EMC");
        lore.add(value.mode().canSell()
                ? "&7回收: &a" + exchangeService.calculateSellEmc(value.emc(), 1) + " EMC"
                : "&7回收: &c不可出售");
        lore.add("&7状态: &f" + formatMode(value));
        lore.add("&7收藏: " + (favorite ? "&d已收藏" : "&8未收藏"));
        lore.add("");
        if (value.mode().canBuy()) {
            lore.add("&e左键 &7购买 1 个");
            lore.add("&e右键 &7快速购买 16 个");
            lore.add("&eShift+左键 &7快速购买 64 个");
            lore.add("&eShift+右键 &7按余额与背包上限全买");
        }
        lore.add("&eF 键 &7收藏 / 取消收藏");
        if (value.mode().canSell()) {
            lore.add("&eQ 键 &7出售 1 个，&eCtrl+Q &7出售背包同类");
        } else {
            lore.add("&c该材料不可出售");
        }
        return lore;
    }

    private ItemStack buildAdminLore(MaterialValue value) {
        String categoryId = shopRegistry.getEffectiveShopId(value.material());
        String categoryName = categoryId == null ? "未分类" : shopRegistry.getShopDisplayName(categoryId);
        String hidden = valueRegistry.isHidden(value.material()) ? "&c已下架" : "&a上架中";
        return ItemStackUtil.createItem(value.material(), null, List.of(
                "&7当前 EMC: &6" + value.emc(),
                "&7交易模式: &f" + formatMode(value),
                "&7所在分类: &f" + categoryName,
                "&7显示状态: " + hidden,
                "",
                "&e左键 &7+1 EMC",
                "&eShift+左键 &7+10 EMC",
                "&e右键 &7-1 EMC",
                "&eShift+右键 &7-10 EMC",
                "&eF 键 &7切换到下一个分类",
                "&eQ 键 &7上架 / 下架切换",
                "&eCtrl+Q &7删除该物品 EMC"
        ));
    }

    private String formatMode(MaterialValue value) {
        if (value.mode().canBuy() && value.mode().canSell()) {
            return "可买可卖";
        }
        if (value.mode().canBuy()) {
            return "仅可购买";
        }
        if (value.mode().canSell()) {
            return "仅可出售";
        }
        return "不可交易";
    }
    private void refreshStatusItems(Player player, Inventory inventory, LayoutTemplate layout) {
        if (layout == null) {
            return;
        }
        for (Map.Entry<Integer, IconTemplate> entry : layout.iconSlots().entrySet()) {
            if ("status".equals(entry.getValue().function())) {
                setItem(inventory, entry.getKey(), player, entry.getValue().resolve(false));
            }
        }
    }

    private void returnInputItems(Player player, Inventory inventory, List<Integer> inputSlots) {
        for (int slot : inputSlots) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) {
                continue;
            }
            ItemStackUtil.giveOrDrop(player, stack);
            inventory.setItem(slot, null);
        }
    }

    private String applyPlaceholders(Player player, String text) {
        if (text == null) {
            return "";
        }
        AccountService.DailySaleSummary summary = accountService.getTodaySummary(player.getUniqueId());
        return ColorUtil.color(text.replace("%emc%", String.valueOf(accountService.getBalance(player.getUniqueId())))
                .replace("%player%", player.getName())
                .replace("%today_emc%", String.valueOf(summary.soldEmc()))
                .replace("%today_top_amount%", String.valueOf(summary.topMaterialAmount()))
                .replace("%favorite_count%", String.valueOf(summary.favoriteCount())));
    }

    private List<String> applyPlaceholders(Player player, List<String> lines) {
        return lines.stream().map(line -> applyPlaceholders(player, line)).toList();
    }

    private <T> PageSlice<T> paginate(List<T> entries, int requestedPage, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) safePageSize));
        int page = Math.min(Math.max(1, requestedPage), totalPages);
        int from = Math.min(entries.size(), (page - 1) * safePageSize);
        int to = Math.min(entries.size(), from + safePageSize);
        return new PageSlice<>(entries.subList(from, to), page, page > 1, page < totalPages);
    }
}

