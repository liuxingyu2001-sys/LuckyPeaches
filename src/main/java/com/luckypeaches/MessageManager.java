package com.luckypeaches;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class MessageManager {
    private final LuckyPeaches plugin;
    private File messagesFile;
    private FileConfiguration messagesConfig;

    public MessageManager(LuckyPeaches plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reloadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public String getMessage(String key) {
        return messagesConfig.getString(key, "");
    }

    public String getMessage(String key, String defaultValue) {
        return messagesConfig.getString(key, defaultValue);
    }

    public List<String> getMessageList(String key) {
        return messagesConfig.getStringList(key);
    }

    public String getPrefix() {
        return ChatColor.translateAlternateColorCodes('&', getMessage("prefix", ""));
    }

    public String getColoredMessage(String key) {
        return ChatColor.translateAlternateColorCodes('&', getMessage(key));
    }

    public String getColoredMessage(String key, String defaultValue) {
        return ChatColor.translateAlternateColorCodes('&', getMessage(key, defaultValue));
    }

    public String getPrefixedMessage(String key) {
        return getPrefix() + getColoredMessage(key);
    }

    public String getPrefixedMessage(String key, String defaultValue) {
        return getPrefix() + getColoredMessage(key, defaultValue);
    }

    public void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(getPrefixedMessage(key));
    }

    public void sendMessage(CommandSender sender, String key, String defaultValue) {
        sender.sendMessage(getPrefix() + getColoredMessage(key, defaultValue));
    }

    public void sendMessageWithoutPrefix(CommandSender sender, String key) {
        sender.sendMessage(getColoredMessage(key));
    }

    public void sendReplacedMessage(CommandSender sender, String key, String... replacements) {
        String message = getMessage(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        sender.sendMessage(getPrefix() + ChatColor.translateAlternateColorCodes('&', message));
    }

    public String getReplacedMessage(String key, String... replacements) {
        String message = getMessage(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String getPrefixedReplacedMessage(String key, String... replacements) {
        return getPrefix() + getReplacedMessage(key, replacements);
    }

    public void sendHelpMessage(CommandSender sender) {
        List<String> helpLines = getMessageList("help");
        for (String line : helpLines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    public void saveMessages() {
        try {
            messagesConfig.save(messagesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("无法保存 messages.yml: " + e.getMessage());
        }
    }
}
