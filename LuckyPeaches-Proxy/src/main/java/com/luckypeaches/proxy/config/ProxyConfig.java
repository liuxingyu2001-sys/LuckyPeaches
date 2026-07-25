package com.luckypeaches.proxy.config;

import com.luckypeaches.proxy.LuckyPeachesProxy;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProxyConfig {
    private final LuckyPeachesProxy plugin;
    private final Path dataDirectory;
    private Map<String, Object> config;

    // 数据库配置
    private String dbHost = "localhost";
    private int dbPort = 3306;
    private String dbDatabase = "luckypeaches";
    private String dbUsername = "root";
    private String dbPassword = "";
    private String tablePrefix = "lp_";

    public ProxyConfig(LuckyPeachesProxy plugin) {
        this.plugin = plugin;
        this.dataDirectory = plugin.getDataDirectory();
    }

    public void load() {
        try {
            // 创建数据目录
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve("config.yml");

            // 如果配置文件不存在，复制默认配置
            if (!Files.exists(configPath)) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configPath);
                    }
                }
            }

            // 加载配置
            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configPath)) {
                config = yaml.load(in);
            }

            if (config == null) {
                config = new LinkedHashMap<>();
            }

            // 读取数据库配置
            Object databaseObj = config.get("database");
            if (databaseObj instanceof Map) {
                Map<?, ?> database = (Map<?, ?>) databaseObj;
                dbHost = database.containsKey("host") ? database.get("host").toString() : dbHost;
                dbPort = database.get("port") instanceof Number ? ((Number) database.get("port")).intValue() : dbPort;
                dbDatabase = database.containsKey("database") ? database.get("database").toString() : dbDatabase;
                dbUsername = database.containsKey("username") ? database.get("username").toString() : dbUsername;
                dbPassword = database.containsKey("password") ? database.get("password").toString() : dbPassword;
                tablePrefix = database.containsKey("table_prefix") ? database.get("table_prefix").toString() : tablePrefix;
            }

            // 合并配置（将 settings 下的配置提升到顶层，供后端服务器同步）
            Object settingsObj = config.get("settings");
            if (settingsObj instanceof Map) {
                Map<?, ?> settings = (Map<?, ?>) settingsObj;
                for (Map.Entry<?, ?> entry : settings.entrySet()) {
                    String key = entry.getKey().toString();
                    if (!config.containsKey(key)) {
                        config.put(key, entry.getValue());
                    }
                }
            }

            plugin.getLogger().info("配置加载成功");
        } catch (IOException e) {
            plugin.getLogger().error("配置加载失败: " + e.getMessage());
        }
    }

    /**
     * 重新加载配置并同步到数据库
     * @return 同步是否成功
     */
    public boolean reload() {
        load();
        return plugin.getDatabase().syncConfigToDatabase();
    }

    public String getDbHost() {
        return dbHost;
    }

    public int getDbPort() {
        return dbPort;
    }

    public String getDbDatabase() {
        return dbDatabase;
    }

    public String getDbUsername() {
        return dbUsername;
    }

    public String getDbPassword() {
        return dbPassword;
    }

    public String getTablePrefix() {
        return tablePrefix;
    }

    public Map<String, Object> getConfig() {
        return config;
    }
}
