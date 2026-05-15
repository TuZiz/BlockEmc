package com.blockemc.compat;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ServerScheduler {

    private final JavaPlugin plugin;
    private final boolean folia;

    public ServerScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = isClassPresent("io.papermc.paper.threadedregions.RegionizedServer");
    }

    public boolean isFolia() {
        return folia;
    }

    public void executePlayer(Player player, Runnable runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            execute.invoke(scheduler, plugin, runnable, null, 1L);
        } catch (ReflectiveOperationException exception) {
            runnable.run();
        }
    }

    private boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
