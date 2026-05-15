package com.blockemc.model;

import java.util.List;
import java.util.Map;

public final class LayoutTemplate {

    private final String title;
    private final int size;
    private final List<Integer> itemSlots;
    private final List<Integer> inputSlots;
    private final Map<Integer, IconTemplate> iconSlots;

    public LayoutTemplate(String title, int size, List<Integer> itemSlots, List<Integer> inputSlots, Map<Integer, IconTemplate> iconSlots) {
        this.title = title;
        this.size = size;
        this.itemSlots = List.copyOf(itemSlots);
        this.inputSlots = List.copyOf(inputSlots);
        this.iconSlots = Map.copyOf(iconSlots);
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public List<Integer> itemSlots() {
        return itemSlots;
    }

    public List<Integer> inputSlots() {
        return inputSlots;
    }

    public Map<Integer, IconTemplate> iconSlots() {
        return iconSlots;
    }
}
