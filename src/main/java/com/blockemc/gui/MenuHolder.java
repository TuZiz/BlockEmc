package com.blockemc.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class MenuHolder implements InventoryHolder {

    private MenuView view;

    public void bind(MenuView view) {
        this.view = view;
    }

    public MenuView view() {
        return view;
    }

    @Override
    public Inventory getInventory() {
        return view == null ? null : view.inventory();
    }
}
