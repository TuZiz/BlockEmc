package com.blockemc.listener;

import com.blockemc.gui.MenuHolder;
import com.blockemc.gui.MenuView;
import com.blockemc.service.AccountService;
import com.blockemc.service.GuiService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class MenuListener implements Listener {

    private final GuiService guiService;
    private final AccountService accountService;

    public MenuListener(GuiService guiService, AccountService accountService) {
        this.guiService = guiService;
        this.accountService = accountService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        MenuView view = holder.view();
        if (view == null || event.getClickedInventory() == null) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < topSize) {
            if (view.isInputSlot(rawSlot)) {
                ItemStack incoming = resolveIncomingItem(event);
                if (!view.acceptsInput(incoming)) {
                    event.setCancelled(true);
                    return;
                }
                event.setCancelled(false);
                return;
            }
            event.setCancelled(true);
            if (view.handler(rawSlot) != null) {
                view.handler(rawSlot).accept(event);
            }
            return;
        }

        if (!view.allowBottomInteractions()) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick() && view.hasInputSlots() && !view.acceptsInput(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        MenuView view = holder.view();
        if (view == null) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= topSize) {
                continue;
            }
            if (!view.isInputSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
            if (!view.acceptsInput(event.getNewItems().get(rawSlot))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        guiService.handleClose(event);
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        accountService.preloadPlayer(event.getUniqueId(), event.getName());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        accountService.notePlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guiService.handleQuit(event.getPlayer());
        accountService.handleQuit(event.getPlayer().getUniqueId());
    }

    private ItemStack resolveIncomingItem(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        if (action == InventoryAction.PLACE_ALL
                || action == InventoryAction.PLACE_ONE
                || action == InventoryAction.PLACE_SOME
                || action == InventoryAction.SWAP_WITH_CURSOR) {
            return event.getCursor();
        }
        if (event.getClick() == ClickType.NUMBER_KEY) {
            Player player = (Player) event.getWhoClicked();
            return player.getInventory().getItem(event.getHotbarButton());
        }
        return null;
    }
}
