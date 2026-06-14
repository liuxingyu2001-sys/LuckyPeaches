package com.luckypeaches;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public class DatabaseManager {
    // 用于存储玩家健康数据的简单类
    public static class PlayerHealthData {
        private double peachBonus;
        private double currentHealth;
        
        public PlayerHealthData(double peachBonus, double currentHealth) {
            this.peachBonus = peachBonus;
            this.currentHealth = currentHealth;
        }
        
        public double getPeachBonus() {
            return peachBonus;
        }
        
        public double getCurrentHealth() {
            return currentHealth;
        }
    }
    private final LuckyPeaches plugin;
    private Connection connection;

    public DatabaseManager(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "data.db");
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

            connection = DriverManager.getConnection(url);
            createTables();
            
            // 数据库迁移：检查并添加缺失的current_health列
            migrateDatabase();
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库连接失败: " + e.getMessage());
        }
    }

    private void createTables() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS player_peach_health (" +
                "uuid TEXT PRIMARY KEY, " +
                "username TEXT, " +
                "peach_bonus REAL, " +
                "current_health REAL, " +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                ")";

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void migrateDatabase() throws SQLException {
        // 检查current_health列是否存在
        String checkColumnSql = "PRAGMA table_info(player_peach_health)";
        boolean hasCurrentHealthColumn = false;
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(checkColumnSql)) {
            
            while (rs.next()) {
                String columnName = rs.getString("name");
                if ("current_health".equals(columnName)) {
                    hasCurrentHealthColumn = true;
                    break;
                }
            }
        }
        
        // 如果不存在，添加current_health列
        if (!hasCurrentHealthColumn) {
            String addColumnSql = "ALTER TABLE player_peach_health ADD COLUMN current_health REAL DEFAULT 20.0";
            try (Statement stmt = connection.createStatement()) {
                stmt.execute(addColumnSql);
            }
        }
    }

    public void savePlayerData(UUID uuid, String username, double peachBonus, double currentHealth) {
        String sql = "INSERT OR REPLACE INTO player_peach_health " +
                "(uuid, username, peach_bonus, current_health, last_updated) " +
                "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, username);
            pstmt.setDouble(3, peachBonus);
            pstmt.setDouble(4, currentHealth);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("保存玩家数据失败: " + e.getMessage());
        }
    }
    
    // 兼容旧版本的savePlayerData方法
    public void savePlayerData(UUID uuid, String username, double peachBonus) {
        // 如果只提供peachBonus，不修改currentHealth
        PlayerHealthData currentData = loadCompletePlayerData(uuid);
        savePlayerData(uuid, username, peachBonus, currentData.getCurrentHealth());
    }

    // 加载完整的玩家健康数据
    public PlayerHealthData loadCompletePlayerData(UUID uuid) {
        String sql = "SELECT peach_bonus, current_health FROM player_peach_health WHERE uuid = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                double peachBonus = rs.getDouble("peach_bonus");
                double currentHealth = rs.getDouble("current_health");
                return new PlayerHealthData(peachBonus, currentHealth);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("加载玩家数据失败: " + e.getMessage());
        }

        return new PlayerHealthData(0.0, 0.0);
    }
    
    // 兼容旧版本的loadPlayerData方法
    public double loadPlayerData(UUID uuid) {
        return loadCompletePlayerData(uuid).getPeachBonus();
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
        }
    }

    public void migrateFromPersistentData(org.bukkit.entity.Player player) {
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin, "peach_health");
        Double oldBonus = player.getPersistentDataContainer().get(key, org.bukkit.persistence.PersistentDataType.DOUBLE);
        
        if (oldBonus != null && oldBonus > 0) {
            double currentBonus = loadPlayerData(player.getUniqueId());
            
            if (currentBonus == 0.0) {
                savePlayerData(player.getUniqueId(), player.getName(), oldBonus);
                player.getPersistentDataContainer().remove(key);
            }
        }
    }
}
