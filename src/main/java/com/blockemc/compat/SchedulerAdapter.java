package com.blockemc.compat;

import org.bukkit.entity.Player;

public interface SchedulerAdapter {

    void runGlobal(Runnable task);

    void runAsync(Runnable task);

    void runForPlayer(Player player, Runnable task);

    void runLaterForPlayer(Player player, Runnable task, long delayTicks);
}
