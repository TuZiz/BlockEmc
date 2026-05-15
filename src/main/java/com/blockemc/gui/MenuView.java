package com.blockemc.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class MenuView {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> clickHandlers = new HashMap<>();
    private final Set<Integer> inputSlots = new HashSet<>();
    private Predicate<ItemStack> inputValidator = item -> true;
    private boolean allowBottomInteractions;
    private Consumer<InventoryCloseEvent> closeHandler = event -> {
    };

    public MenuView(Inventory inventory) {
        this.inventory = inventory;
    }

    public Inventory inventory() {
        return inventory;
    }

    public void onClick(int slot, Consumer<InventoryClickEvent> handler) {
        clickHandlers.put(slot, handler);
    }

    public Consumer<InventoryClickEvent> handler(int slot) {
        return clickHandlers.get(slot);
    }

    public void addInputSlot(int slot) {
        inputSlots.add(slot);
    }

    public boolean isInputSlot(int slot) {
        return inputSlots.contains(slot);
    }

    public boolean hasInputSlots() {
        return !inputSlots.isEmpty();
    }

    public void inputValidator(Predicate<ItemStack> inputValidator) {
        this.inputValidator = inputValidator == null ? item -> true : inputValidator;
    }

    public boolean acceptsInput(ItemStack itemStack) {
        return itemStack == null || itemStack.getType() == Material.AIR || inputValidator.test(itemStack);
    }

    public void allowBottomInteractions(boolean allowBottomInteractions) {
        this.allowBottomInteractions = allowBottomInteractions;
    }

    public boolean allowBottomInteractions() {
        return allowBottomInteractions;
    }

    public void onClose(Consumer<InventoryCloseEvent> closeHandler) {
        this.closeHandler = closeHandler;
    }

    public Consumer<InventoryCloseEvent> closeHandler() {
        return closeHandler;
    }
}
