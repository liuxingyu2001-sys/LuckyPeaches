package com.luckypeaches.proxy.database;

import com.luckypeaches.proxy.LuckyPeachesProxy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;

public class ProxyDatabase {
    private final LuckyPeachesProxy plugin;
    private HikariDataSource hikariPool;
    private String configTableName;

    public ProxyDatabase(LuckyPeachesProxy plugin) {
        this.plugin = plugin;
        String prefix = plugin.getPluginConfig().getTablePrefix();
        this.configTableName = prefix + "config";
    }

    public void initialize() {
        try {
            // 加载 MySQL 驱动
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException e) {
                plugin.getLogger().error("MySQL 驱动未找到，请确保 mysql-connector-java 在 classpath 中");
                return;
            }

            String host = plugin.getPluginConfig().getDbHost();
            int port = plugin.getPluginConfig().getDbPort();
            String database = plugin.getPluginConfig().getDbDatabase();
            String username = plugin.getPluginConfig().getDbUsername();
            String password = plugin.getPluginConfig().getDbPassword();

            // 自动创建数据库（不指定数据库名，避免需要 mysql 系统库权限）
            String serverUrl = "jdbc:mysql://" + host + ":" + port
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
            try (Connection serverConn = DriverManager.getConnection(serverUrl, username, password);
                 Statement stmt = serverConn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                plugin.getLogger().info("MySQL 数据库 " + database + " 已就绪");
            }

            // 创建连接池
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(10);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setPoolName("LuckyPeaches-Proxy-Hikari");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            hikariPool = new HikariDataSource(hikariConfig);

            // 创建表
            createTables();

            plugin.getLogger().info("MySQL 连接成功: " + host + ":" + port + "/" + database);
        } catch (SQLException e) {
            plugin.getLogger().error("MySQL 连接失败: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        String configSql = "CREATE TABLE IF NOT EXISTS " + configTableName + " (" +
                          "id INT PRIMARY KEY DEFAULT 1, " +
                          "config_data LONGTEXT, " +
                          "server_name VARCHAR(64), " +
                          "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                          ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection conn = hikariPool.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(configSql);
        }
    }

    /**
     * 同步配置到 MySQL
     * @return 同步是否成功
     */
    public boolean syncConfigToDatabase() {
        if (hikariPool == null || hikariPool.isClosed()) {
            plugin.getLogger().error("同步配置失败: 数据库连接池不可用");
            return false;
        }

        try {
            java.nio.file.Path configPath = plugin.getDataDirectory().resolve("config.yml");
            if (!java.nio.file.Files.exists(configPath)) {
                return false;
            }

            String configData = new String(java.nio.file.Files.readAllBytes(configPath));
            String serverName = "Velocity";

            String sql = "INSERT INTO " + configTableName + " (id, config_data, server_name) " +
                         "VALUES (1, ?, ?) ON DUPLICATE KEY UPDATE config_data=VALUES(config_data), server_name=VALUES(server_name)";

            try (Connection conn = hikariPool.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, configData);
                pstmt.setString(2, serverName);
                pstmt.executeUpdate();
            }

            plugin.getLogger().info("配置已同步到 MySQL");
            return true;
        } catch (Exception e) {
            plugin.getLogger().error("同步配置失败: " + e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return hikariPool != null && !hikariPool.isClosed();
    }

    public void close() {
        if (hikariPool != null && !hikariPool.isClosed()) {
            hikariPool.close();
        }
    }
}
