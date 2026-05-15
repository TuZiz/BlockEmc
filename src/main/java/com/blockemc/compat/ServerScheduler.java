package com.blockemc.compat;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ServerScheduler implements SchedulerAdapter {

    private final JavaPlugin plugin;
    private final boolean folia;

    public ServerScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = isClassPresent("io.papermc.paper.threadedregions.RegionizedServer");
        if (folia) {
            validateFoliaScheduler();
        }
    }

    public boolean isFolia() {
        return folia;
    }

    public void executePlayer(Player player, Runnable runnable) {
        runForPlayer(player, runnable);
    }

    @Override
    public void runGlobal(Runnable task) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class);
            execute.invoke(scheduler, plugin, task);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia global scheduler invocation failed", exception);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        if (!folia) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        try {
            Object scheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            Method runNow = scheduler.getClass().getMethod("runNow", Plugin.class, java.util.function.Consumer.class);
            runNow.invoke(scheduler, plugin, (java.util.function.Consumer<Object>) ignored -> task.run());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia async scheduler invocation failed", exception);
        }
    }

    @Override
    public void runForPlayer(Player player, Runnable runnable) {
        if (!folia) {
            Bukkit.getScheduler().runTask(plugin, runnable);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method execute = scheduler.getClass().getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
            execute.invoke(scheduler, plugin, runnable, null, 1L);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia player scheduler invocation failed", exception);
        }
    }

    @Override
    public void runLaterForPlayer(Player player, Runnable task, long delayTicks) {
        long safeDelay = Math.max(1L, delayTicks);
        if (!folia) {
            Bukkit.getScheduler().runTaskLater(plugin, task, safeDelay);
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            Method runDelayed = scheduler.getClass().getMethod("runDelayed", Plugin.class, java.util.function.Consumer.class, Runnable.class, long.class);
            runDelayed.invoke(scheduler, plugin, (java.util.function.Consumer<Object>) ignored -> task.run(), null, safeDelay);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia delayed player scheduler invocation failed", exception);
        }
    }

    private void validateFoliaScheduler() {
        try {
            Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler");
            Bukkit.getServer().getClass().getMethod("getAsyncScheduler");
            Class.forName("org.bukkit.entity.Entity").getMethod("getScheduler");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Folia scheduler API is not available", exception);
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
