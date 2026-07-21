package com.luckypeaches;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

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

    private final LuckyPeaches plugin;
    private final boolean useMysql;
    private final String tableName;
    private final File sqliteFile;
    private Connection sqliteConnection;
    private HikariDataSource hikariPool;
    private final Object dbLock = new Object();

    public DatabaseManager(LuckyPeaches plugin) {
        this.plugin = plugin;
        this.useMysql = "mysql".equalsIgnoreCase(
            plugin.getConfig().getString("settings.database.type", "sqlite"));
        String prefix = plugin.getConfig().getString("settings.database.mysql.table_prefix", "lp_");
        this.tableName = prefix + "player_peach_health";
        this.sqliteFile = new File(plugin.getDataFolder(), "data.db");
    }

    public void initialize() {
        if (useMysql) {
            initMySQL();
        } else {
            initSQLite();
        }
    }

    // ========== SQLite ==========

    private void initSQLite() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            String url = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();

            sqliteConnection = DriverManager.getConnection(url);
            createTables();
            migrateDatabase();
        } catch (SQLException e) {
            plugin.getLogger().severe("SQLite 连接失败: " + e.getMessage());
        }
    }

    // ========== MySQL ==========

    private void initMySQL() {
        try {
            String host = plugin.getConfig().getString("settings.database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("settings.database.mysql.port", 3306);
            String database = plugin.getConfig().getString("settings.database.mysql.database", "luckypeaches");
            String username = plugin.getConfig().getString("settings.database.mysql.username", "root");
            String password = plugin.getConfig().getString("settings.database.mysql.password", "");
            int maxConnections = plugin.getConfig().getInt("settings.database.mysql.max_connections", 10);

            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8mb4");
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(maxConnections);
            hikariConfig.setMinimumIdle(2);
            hikariConfig.setConnectionTimeout(5000);
            hikariConfig.setPoolName("LuckyPeaches-Hikari");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            hikariPool = new HikariDataSource(hikariConfig);

            try (Connection conn = hikariPool.getConnection()) {
                plugin.getLogger().info("MySQL 连接成功: " + host + ":" + port + "/" + database);
            }

            createTables();
        } catch (SQLException e) {
            plugin.getLogger().severe("MySQL 连接失败: " + e.getMessage());
        }
    }

    // ========== 连接获取 ==========

    private Connection getConnection() throws SQLException {
        if (useMysql) {
            return hikariPool.getConnection();
        }
        return sqliteConnection;
    }

    // ========== 建表 ==========

    private void createTables() throws SQLException {
        String sql;
        if (useMysql) {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                  "uuid VARCHAR(36) PRIMARY KEY, " +
                  "username VARCHAR(16), " +
                  "peach_bonus DOUBLE, " +
                  "current_health DOUBLE, " +
                  "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                  ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                  "uuid TEXT PRIMARY KEY, " +
                  "username TEXT, " +
                  "peach_bonus REAL, " +
                  "current_health REAL, " +
                  "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                  ")";
        }

        if (useMysql) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        } else {
            try (Statement stmt = sqliteConnection.createStatement()) {
                stmt.execute(sql);
            }
        }
    }

    // ========== 迁移 ==========

    private void migrateDatabase() throws SQLException {
        if (useMysql) {
            migrateMySQL();
        } else {
            migrateSQLite();
        }
    }

    private void migrateSQLite() throws SQLException {
        String checkColumnSql = "PRAGMA table_info(" + tableName + ")";
        boolean hasCurrentHealthColumn = false;

        try (Statement stmt = sqliteConnection.createStatement();
             ResultSet rs = stmt.executeQuery(checkColumnSql)) {
            while (rs.next()) {
                if ("current_health".equals(rs.getString("name"))) {
                    hasCurrentHealthColumn = true;
                    break;
                }
            }
        }

        if (!hasCurrentHealthColumn) {
            try (Statement stmt = sqliteConnection.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN current_health REAL DEFAULT 20.0");
            }
        }
    }

    private void migrateMySQL() throws SQLException {
        try (Connection conn = getConnection();
             ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "current_health")) {
            if (!rs.next()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN current_health DOUBLE DEFAULT 20.0");
                }
            }
        }
    }

    // ========== SQL 方言 ==========

    private String upsertSQL() {
        if (useMysql) {
            return "INSERT INTO " + tableName + " (uuid, username, peach_bonus, current_health, last_updated) " +
                   "VALUES (?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE " +
                   "username=VALUES(username), peach_bonus=VALUES(peach_bonus), " +
                   "current_health=VALUES(current_health), last_updated=NOW()";
        }
        return "INSERT OR REPLACE INTO " + tableName +
               " (uuid, username, peach_bonus, current_health, last_updated) " +
               "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
    }

    // ========== 保存 ==========

    public void savePlayerData(UUID uuid, String username, double peachBonus, double currentHealth) {
        synchronized (dbLock) {
            String sql = upsertSQL();
            try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
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

    public void savePlayerData(UUID uuid, String username, double peachBonus) {
        PlayerHealthData currentData = loadCompletePlayerData(uuid);
        savePlayerData(uuid, username, peachBonus, currentData.getCurrentHealth());
    }

    // ========== 加载 ==========

    public PlayerHealthData loadCompletePlayerData(UUID uuid) {
        synchronized (dbLock) {
            String sql = "SELECT peach_bonus, current_health FROM " + tableName + " WHERE uuid = ?";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerHealthData(rs.getDouble("peach_bonus"), rs.getDouble("current_health"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("加载玩家数据失败: " + e.getMessage());
            }

            return new PlayerHealthData(0.0, 0.0);
        }
    }

    public double loadPlayerData(UUID uuid) {
        return loadCompletePlayerData(uuid).getPeachBonus();
    }

    // ========== 排行榜 ==========

    public List<PlayerRankData> getTopPlayers(int limit) {
        synchronized (dbLock) {
            List<PlayerRankData> result = new ArrayList<>();
            String sql = "SELECT uuid, username, peach_bonus FROM " + tableName +
                         " WHERE peach_bonus > 0 ORDER BY peach_bonus DESC LIMIT ?";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
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

    public int getPlayerRank(UUID uuid) {
        synchronized (dbLock) {
            String sql = "SELECT COUNT(*) as rank FROM " + tableName + " WHERE peach_bonus > 0 " +
                         "AND peach_bonus > (SELECT COALESCE(peach_bonus, 0) FROM " + tableName + " WHERE uuid = ?)";

            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
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

    public int getTotalPlayersWithPeachBonus() {
        synchronized (dbLock) {
            String sql = "SELECT COUNT(*) as total FROM " + tableName + " WHERE peach_bonus > 0";

            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
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

    // ========== 备份 ==========

    public boolean backupToFile(File backupFile) {
        if (useMysql) {
            plugin.getLogger().info("MySQL 模式下备份请使用 mysqldump 工具");
            return false;
        }

        synchronized (dbLock) {
            try (Statement stmt = sqliteConnection.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupFile.getAbsolutePath() + "'");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
                return false;
            }
        }
    }

    // ========== 关闭 ==========

    public void close() {
        if (useMysql) {
            if (hikariPool != null && !hikariPool.isClosed()) {
                hikariPool.close();
            }
        } else {
            synchronized (dbLock) {
                try {
                    if (sqliteConnection != null && !sqliteConnection.isClosed()) {
                        sqliteConnection.close();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
                }
            }
        }
    }

    // ========== 数据库类型查询 ==========

    public boolean isUseMysql() {
        return useMysql;
    }

    // ========== 热切换数据库 ==========

    /**
     * 读取当前数据库全部数据（用于迁移）
     */
    public List<Object[]> readAllDataForMigration() {
        List<Object[]> data = new ArrayList<>();
        String sql = "SELECT uuid, username, peach_bonus, current_health FROM " + tableName;
        synchronized (dbLock) {
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    data.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("username"),
                        rs.getDouble("peach_bonus"),
                        rs.getDouble("current_health")
                    });
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("读取数据失败: " + e.getMessage());
            }
        }
        return data;
    }

    /**
     * 将数据写入当前数据库（用于迁移后的写入）
     */
    public void writeAllData(List<Object[]> data) {
        String sql = upsertSQL();
        synchronized (dbLock) {
            try (Connection conn = getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                for (Object[] row : data) {
                    pstmt.setString(1, (String) row[0]);
                    pstmt.setString(2, (String) row[1]);
                    pstmt.setDouble(3, (double) row[2]);
                    pstmt.setDouble(4, (double) row[3]);
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().severe("写入数据失败: " + e.getMessage());
            }
        }
    }

    // ========== 迁移旧数据 ==========

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
