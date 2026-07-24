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
    private boolean showPrefix = true;

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
        mergeDefaultMessages();
        showPrefix = messagesConfig.getBoolean("show_prefix", true);
    }

    /**
     * 合并默认消息，自动补全缺失的消息键
     */
    private void mergeDefaultMessages() {
        YamlConfiguration defaultMessages = YamlConfiguration.loadConfiguration(
            new java.io.InputStreamReader(plugin.getResource("messages.yml")));

        boolean changed = false;
        for (String key : defaultMessages.getKeys(true)) {
            if (!messagesConfig.contains(key)) {
                messagesConfig.set(key, defaultMessages.get(key));
                changed = true;
            }
        }

        if (changed) {
            try {
                messagesConfig.save(messagesFile);
                plugin.getLogger().info("已自动补全缺失的消息键");
            } catch (java.io.IOException e) {
                plugin.getLogger().severe("保存消息文件失败: " + e.getMessage());
            }
        }
    }

    public void reloadMessages() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        showPrefix = messagesConfig.getBoolean("show_prefix", true);
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

    public boolean isShowPrefix() {
        return showPrefix;
    }

    public String getPrefixedMessage(String key) {
        return (showPrefix ? getPrefix() : "") + getColoredMessage(key);
    }

    public String getPrefixedMessage(String key, String defaultValue) {
        return (showPrefix ? getPrefix() : "") + getColoredMessage(key, defaultValue);
    }

    public void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(getPrefixedMessage(key));
    }

    public void sendMessage(CommandSender sender, String key, String defaultValue) {
        sender.sendMessage((showPrefix ? getPrefix() : "") + getColoredMessage(key, defaultValue));
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
        sender.sendMessage((showPrefix ? getPrefix() : "") + ChatColor.translateAlternateColorCodes('&', message));
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
        return (showPrefix ? getPrefix() : "") + getReplacedMessage(key, replacements);
    }

    public void sendHelpMessage(CommandSender sender) {
        List<String> helpLines = getMessageList("help");
        for (String line : helpLines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
    }

    public void sendDatabaseHelpMessage(CommandSender sender) {
        List<String> helpLines = getMessageList("db_help");
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
