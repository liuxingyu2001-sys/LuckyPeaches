package com.luckypeaches;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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
    private final Object dbLock = new Object();

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
        synchronized (dbLock) {
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
    }
    
    // 兼容旧版本的savePlayerData方法
    public void savePlayerData(UUID uuid, String username, double peachBonus) {
        // 如果只提供peachBonus，不修改currentHealth
        PlayerHealthData currentData = loadCompletePlayerData(uuid);
        savePlayerData(uuid, username, peachBonus, currentData.getCurrentHealth());
    }

    // 加载完整的玩家健康数据
    public PlayerHealthData loadCompletePlayerData(UUID uuid) {
        synchronized (dbLock) {
            String sql = "SELECT peach_bonus, current_health FROM player_peach_health WHERE uuid = ?";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        double peachBonus = rs.getDouble("peach_bonus");
                        double currentHealth = rs.getDouble("current_health");
                        return new PlayerHealthData(peachBonus, currentHealth);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家数据失败: " + e.getMessage());
            }

            return new PlayerHealthData(0.0, 0.0);
        }
    }
    
    // 兼容旧版本的loadPlayerData方法
    public double loadPlayerData(UUID uuid) {
        return loadCompletePlayerData(uuid).getPeachBonus();
    }

    // 排行榜数据类
    public static class PlayerRankData {
        private final String uuid;
        private final String username;
        private final double peachBonus;

        public PlayerRankData(String uuid, String username, double peachBonus) {
            this.uuid = uuid;
            this.username = username;
            this.peachBonus = peachBonus;
        }

        public String getUuid() { return uuid; }
        public String getUsername() { return username; }
        public double getPeachBonus() { return peachBonus; }
    }

    /**
     * 获取蟠桃加成排行榜前N名玩家
     */
    public List<PlayerRankData> getTopPlayers(int limit) {
        synchronized (dbLock) {
            List<PlayerRankData> result = new ArrayList<>();
            String sql = "SELECT uuid, username, peach_bonus FROM player_peach_health WHERE peach_bonus > 0 ORDER BY peach_bonus DESC LIMIT ?";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setInt(1, limit);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(new PlayerRankData(
                            rs.getString("uuid"),
                            rs.getString("username"),
                            rs.getDouble("peach_bonus")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取排行榜数据失败: " + e.getMessage());
            }

            return result;
        }
    }

    /**
     * 获取玩家在蟠桃排行榜中的排名（1-based）
     */
    public int getPlayerRank(UUID uuid) {
        synchronized (dbLock) {
            String sql = "SELECT COUNT(*) as rank FROM player_peach_health WHERE peach_bonus > 0 " +
                         "AND peach_bonus > (SELECT COALESCE(peach_bonus, 0) FROM player_peach_health WHERE uuid = ?)";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rank") + 1;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取玩家排名失败: " + e.getMessage());
            }

            return -1;
        }
    }

    /**
     * 获取拥有蟠桃加成的玩家总数
     */
    public int getTotalPlayersWithPeachBonus() {
        synchronized (dbLock) {
            String sql = "SELECT COUNT(*) as total FROM player_peach_health WHERE peach_bonus > 0";

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt("total");
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取玩家总数失败: " + e.getMessage());
            }

            return 0;
        }
    }

    /**
     * 使用 VACUUM INTO 创建一致的数据库备份
     * @param backupFile 备份目标文件
     * @return 备份是否成功
     */
    public boolean backupToFile(File backupFile) {
        synchronized (dbLock) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupFile.getAbsolutePath() + "'");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
                return false;
            }
        }
    }

    public void close() {
        synchronized (dbLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
            }
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
