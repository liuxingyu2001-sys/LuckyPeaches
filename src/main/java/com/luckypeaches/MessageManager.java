package com.luckypeaches;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class MessageManager {
    private final LuckyPeaches plugin;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private boolean showPrefix = true;
    /** 颜色代码转换结果缓存，避免每次发消息都重新翻译前缀 */
    private String coloredPrefix = "";

    public MessageManager(LuckyPeaches plugin) {
        this.plugin = plugin;
        loadMessages();
    }

    public void loadMessages() {
        messagesFile = new File(plugin.getConfigDir(), "messages.yml");
        if (!messagesFile.exists()) {
            // 注意：必须写到 getConfigDir()（可能是共享目录），
            // 直接用 saveResource 会写到插件数据目录，导致共享目录下依然缺文件
            plugin.ensureResourceInDir("messages.yml", messagesFile);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
        mergeDefaultMessages();
        cachePrefix();
    }

    public void reloadMessages() {
        loadMessages();
    }

    private void cachePrefix() {
        showPrefix = messagesConfig.getBoolean("show_prefix", true);
        coloredPrefix = ChatColor.translateAlternateColorCodes('&', getMessage("prefix", ""));
    }

    /**
     * 合并默认消息，自动补全缺失的消息键。
     *
     * <p>使用 {@code contains(key, true)}（忽略 defaults）：Bukkit 的 {@code contains(key)}
     * 会把 defaults 中的键也算作"存在"，一旦将来给 messagesConfig 设置 defaults 就会静默失效。</p>
     */
    private void mergeDefaultMessages() {
        YamlConfiguration defaultMessages;
        try (java.io.InputStream in = plugin.getResource("messages.yml")) {
            if (in == null) return;
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(in)) {
                defaultMessages = YamlConfiguration.loadConfiguration(reader);
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("读取默认消息文件失败: " + e.getMessage());
            return;
        }

        boolean changed = false;
        for (String key : defaultMessages.getKeys(true)) {
            if (!messagesConfig.contains(key, true)) {
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

    public String getMessage(String key) {
        return getMessage(key, "");
    }

    public String getMessage(String key, String defaultValue) {
        return commandHelp(key, messagesConfig.getString(key, defaultValue));
    }

    public List<String> getMessageList(String key) {
        return messagesConfig.getStringList(key).stream().map(line -> commandHelp(key, line)).toList();
    }

    /** Old shared messages.yml may still contain the retired alias; do not overwrite custom text. */
    private static String commandHelp(String key, String text) {
        if (text == null || !(key.equals("help") || key.equals("db_help") || key.equals("db_type_mismatch"))) return text;
        return text.replaceAll("/lp(?=\\s|$)", "/luckypeach");
    }

    public String getPrefix() {
        return coloredPrefix;
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
        return (showPrefix ? coloredPrefix : "") + getColoredMessage(key);
    }

    public String getPrefixedMessage(String key, String defaultValue) {
        return (showPrefix ? coloredPrefix : "") + getColoredMessage(key, defaultValue);
    }

    public void sendMessage(CommandSender sender, String key) {
        sender.sendMessage(getPrefixedMessage(key));
    }

    public void sendMessage(CommandSender sender, String key, String defaultValue) {
        sender.sendMessage(getPrefixedMessage(key, defaultValue));
    }

    public void sendMessageWithoutPrefix(CommandSender sender, String key) {
        sender.sendMessage(getColoredMessage(key));
    }

    public void sendReplacedMessage(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(getPrefixedReplacedMessage(key, replacements));
    }

    public String getReplacedMessage(String key, String... replacements) {
        String message = getMessage(key);
        if (replacements != null) {
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                if (replacements[i] != null && replacements[i + 1] != null) {
                    message = message.replace(replacements[i], replacements[i + 1]);
                }
            }
        }
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    public String getPrefixedReplacedMessage(String key, String... replacements) {
        return (showPrefix ? coloredPrefix : "") + getReplacedMessage(key, replacements);
    }

    public void sendHelpMessage(CommandSender sender) {
        sendColoredLines(sender, "help");
    }

    public void sendDatabaseHelpMessage(CommandSender sender) {
        sendColoredLines(sender, "db_help");
    }

    private void sendColoredLines(CommandSender sender, String key) {
        for (String line : getMessageList(key)) {
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
