package com.blockemc.config;

import com.blockemc.util.ColorUtil;
import java.io.File;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MessageService {

    private final JavaPlugin plugin;
    private YamlConfiguration messages;

    public MessageService(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "lang/zh_CN.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String key, Object... args) {
        String template = messages.getString(key, "&cMissing message: " + key);
        for (int i = 0; i < args.length; i++) {
            template = template.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return ColorUtil.color(template);
    }

    public void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(get(key, args));
    }

    public void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(ColorUtil.color(message));
    }
}
