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
    private final String configTableName;
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
        this.configTableName = prefix + "config";
        this.sqliteFile = new File(plugin.getDataFolder(), "data.db");
    }

    public void initialize() {
        if (useMysql) {
            initMySQL();
        } else {
            initSQLite();
        }
        // 迁移对 SQLite 和 MySQL 都需要执行
        try {
            migrateDatabase();
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库迁移失败: " + e.getMessage());
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

            // 先连接到 MySQL 服务器（不指定数据库），尝试自动创建数据库
            String serverUrl = "jdbc:mysql://" + host + ":" + port
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8";
            try (Connection serverConn = DriverManager.getConnection(serverUrl, username, password);
                 Statement stmt = serverConn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                plugin.getLogger().info("MySQL 数据库 " + database + " 已就绪");
            }

            // 再连接到目标数据库
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8");
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
            if (hikariPool == null || hikariPool.isClosed()) {
                throw new SQLException("MySQL 连接池未初始化或已关闭");
            }
            return hikariPool.getConnection();
        }
        if (sqliteConnection == null || sqliteConnection.isClosed()) {
            String url = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            sqliteConnection = DriverManager.getConnection(url);
        }
        return sqliteConnection;
    }

    /**
     * 执行数据库操作的回调接口
     */
    @FunctionalInterface
    private interface DBAction<T> {
        T execute(Connection conn) throws SQLException;
    }

    /**
     * 统一执行数据库操作，自动处理连接生命周期
     * MySQL: 用 try-with-resources 关闭连接归还池
     * SQLite: 复用单连接，不关闭
     */
    private <T> T executeQuery(DBAction<T> action) throws SQLException {
        Connection conn = getConnection();
        if (useMysql) {
            try (conn) {
                return action.execute(conn);
            }
        } else {
            return action.execute(conn);
        }
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

        executeQuery(conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
            return null;
        });

        // 创建配置同步表（MySQL 模式）
        if (useMysql) {
            String configSql = "CREATE TABLE IF NOT EXISTS " + configTableName + " (" +
                              "id INT PRIMARY KEY DEFAULT 1, " +
                              "config_data LONGTEXT, " +
                              "server_name VARCHAR(64), " +
                              "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                              ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            executeQuery(conn -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(configSql);
                }
                return null;
            });
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
        if (sqliteConnection == null || sqliteConnection.isClosed()) {
            return;
        }
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
        executeQuery(conn -> {
            try (ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, "current_health")) {
                if (!rs.next()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN current_health DOUBLE DEFAULT 20.0");
                    }
                }
            }
            return null;
        });
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
            try {
                executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, uuid.toString());
                        pstmt.setString(2, username);
                        pstmt.setDouble(3, peachBonus);
                        pstmt.setDouble(4, currentHealth);
                        pstmt.executeUpdate();
                    }
                    return null;
                });
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

            try {
                return executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, uuid.toString());
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                return new PlayerHealthData(rs.getDouble("peach_bonus"), rs.getDouble("current_health"));
                            }
                        }
                    }
                    return new PlayerHealthData(0.0, 0.0);
                });
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

            try {
                executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
                    }
                    return null;
                });
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

            try {
                return executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setString(1, uuid.toString());
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                return rs.getInt("rank") + 1;
                            }
                        }
                    }
                    return -1;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("获取玩家排名失败: " + e.getMessage());
            }

            return -1;
        }
    }

    public int getTotalPlayersWithPeachBonus() {
        synchronized (dbLock) {
            String sql = "SELECT COUNT(*) as total FROM " + tableName + " WHERE peach_bonus > 0";

            try {
                return executeQuery(conn -> {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        if (rs.next()) {
                            return rs.getInt("total");
                        }
                    }
                    return 0;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("获取玩家总数失败: " + e.getMessage());
            }

            return 0;
        }
    }

    // ========== 配置同步 ==========

    /**
     * 从 MySQL 加载配置（代理端同步）
     * @return 配置数据字符串，失败返回 null
     */
    public String loadConfigFromDatabase() {
        if (!useMysql) return null;

        String sql = "SELECT config_data, server_name FROM " + configTableName + " WHERE id = 1";

        synchronized (dbLock) {
            try {
                return executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql);
                         ResultSet rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            String data = rs.getString("config_data");
                            String source = rs.getString("server_name");
                            plugin.getLogger().info("从代理端加载配置（来源: " + source + "）");
                            return data;
                        }
                    }
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("从代理端加载配置失败: " + e.getMessage());
            }
        }
        return null;
    }

    // ========== 备份 ==========

    public boolean backupToFile(File backupFile) {
        if (useMysql) {
            return false;
        }

        synchronized (dbLock) {
            if (sqliteConnection == null) {
                plugin.getLogger().severe("数据库备份失败: SQLite 连接未初始化");
                return false;
            }
            try (Statement stmt = sqliteConnection.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupFile.getAbsolutePath() + "'");
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * 导出所有数据（用于 YML/JSON 备份）
     */
    public List<java.util.Map<String, Object>> exportAllData() {
        List<java.util.Map<String, Object>> result = new ArrayList<>();
        String sql = "SELECT uuid, username, peach_bonus, current_health, last_updated FROM " + tableName;

        synchronized (dbLock) {
            try {
                executeQuery(conn -> {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        while (rs.next()) {
                            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("uuid", rs.getString("uuid"));
                            row.put("username", rs.getString("username"));
                            row.put("peach_bonus", rs.getDouble("peach_bonus"));
                            row.put("current_health", rs.getDouble("current_health"));
                            row.put("last_updated", rs.getString("last_updated"));
                            result.add(row);
                        }
                    }
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("导出数据失败: " + e.getMessage());
            }
        }
        return result;
    }

    public boolean isMysql() {
        return useMysql;
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
            try {
                executeQuery(conn -> {
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        while (rs.next()) {
                            data.add(new Object[]{
                                rs.getString("uuid"),
                                rs.getString("username"),
                                rs.getDouble("peach_bonus"),
                                rs.getDouble("current_health")
                            });
                        }
                    }
                    return null;
                });
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
            try {
                executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        for (Object[] row : data) {
                            pstmt.setString(1, (String) row[0]);
                            pstmt.setString(2, (String) row[1]);
                            pstmt.setDouble(3, (double) row[2]);
                            pstmt.setDouble(4, (double) row[3]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                    }
                    return null;
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("写入数据失败: " + e.getMessage());
            }
        }
    }

    /**
     * 从 SQLite 文件导入数据到当前数据库（仅 MySQL 模式可用）
     * @param sqliteFile SQLite 数据库文件
     * @return 导入的记录数，失败返回 -1
     */
    public int importFromSQLite(File sqliteFile) {
        if (!sqliteFile.exists()) {
            plugin.getLogger().severe("SQLite 文件不存在: " + sqliteFile.getAbsolutePath());
            return -1;
        }

        if (!useMysql) {
            plugin.getLogger().severe("当前不是 MySQL 模式，无法导入");
            return -1;
        }

        List<Object[]> data = new ArrayList<>();
        String url = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();

        // 尝试不同的表名（兼容旧版本）
        String[] possibleTables = {tableName, "player_peach_health", "peach_health"};

        try (Connection sqliteConn = DriverManager.getConnection(url)) {
            // 先查找实际存在的表
            String actualTable = null;
            try (Statement stmt = sqliteConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    for (String possible : possibleTables) {
                        if (possible.equals(name)) {
                            actualTable = name;
                            break;
                        }
                    }
                    if (actualTable != null) break;
                }
            }

            if (actualTable == null) {
                plugin.getLogger().severe("SQLite 中未找到蟠桃数据表，尝试过的表名: " + String.join(", ", possibleTables));
                return -1;
            }

            plugin.getLogger().info("找到 SQLite 表: " + actualTable);

            // 读取数据
            try (Statement stmt = sqliteConn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT uuid, username, peach_bonus, current_health FROM " + actualTable)) {
                while (rs.next()) {
                    data.add(new Object[]{
                        rs.getString("uuid"),
                        rs.getString("username"),
                        rs.getDouble("peach_bonus"),
                        rs.getDouble("current_health")
                    });
                }
            }
            plugin.getLogger().info("从 SQLite 读取了 " + data.size() + " 条记录");
        } catch (SQLException e) {
            plugin.getLogger().severe("读取 SQLite 数据失败: " + e.getMessage());
            return -1;
        }

        if (data.isEmpty()) {
            plugin.getLogger().info("SQLite 中无数据需要导入");
            return 0;
        }

        // 写入 MySQL
        writeAllData(data);
        plugin.getLogger().info("成功导入 " + data.size() + " 条记录到 MySQL");
        return data.size();
    }

}
