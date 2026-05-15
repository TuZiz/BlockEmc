package com.blockemc.model;

public record IconTemplate(String function, DisplayTemplate display, DisplayTemplate hasDisplay, DisplayTemplate normalDisplay) {

    public DisplayTemplate resolve(boolean state) {
        if (hasDisplay == null && normalDisplay == null) {
            return display;
        }
        if (state && hasDisplay != null) {
            return hasDisplay;
        }
        if (!state && normalDisplay != null) {
            return normalDisplay;
        }
        return display;
    }
}
