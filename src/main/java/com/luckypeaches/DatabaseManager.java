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

    /** 分批写入的批大小，避免一次 executeBatch 持有过大的语句列表 */
    private static final int BATCH_SIZE = 1000;

    private final LuckyPeaches plugin;
    private final boolean useMysql;
    private final String tableName;
    private final File sqliteFile;
    /** upsert 语句与数据库类型绑定，构造时确定一次即可，避免每次保存都拼字符串 */
    private final String upsertSql;
    private Connection sqliteConnection;
    private HikariDataSource hikariPool;
    private final Object dbLock = new Object();
    /**
     * 已关闭标记：close() 之后任何迟到的调用都不应再懒重连，
     * 否则会打开一个再也没人关闭的连接（连接 + 文件锁泄漏）。
     */
    private volatile boolean closed = false;

    /** 使用当前配置中的数据库类型 */
    public DatabaseManager(LuckyPeaches plugin) {
        this(plugin, "mysql".equalsIgnoreCase(
            plugin.getConfig().getString("settings.database.type", "sqlite")));
    }

    /**
     * 显式指定数据库类型。
     *
     * <p>热切换时用这个构造函数：新管理器可以正常构建，而配置里的 type 要等迁移成功后才写入，
     * 避免迁移失败时配置与运行状态不一致。</p>
     */
    public DatabaseManager(LuckyPeaches plugin, boolean useMysql) {
        this.plugin = plugin;
        this.useMysql = useMysql;
        String prefix = plugin.getConfig().getString("settings.database.mysql.table_prefix", "lp_");
        this.tableName = prefix + "player_peach_health";
        this.sqliteFile = new File(plugin.getDataFolder(), "data.db");
        this.upsertSql = useMysql
            ? "INSERT INTO " + tableName + " (uuid, username, peach_bonus, current_health, last_updated) " +
              "VALUES (?, ?, ?, ?, NOW()) ON DUPLICATE KEY UPDATE " +
              "username=VALUES(username), peach_bonus=VALUES(peach_bonus), " +
              "current_health=VALUES(current_health), last_updated=NOW()"
            : "INSERT OR REPLACE INTO " + tableName +
              " (uuid, username, peach_bonus, current_health, last_updated) " +
              "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)";
    }

    /**
     * 初始化数据库连接与表结构
     *
     * @return 初始化是否成功；失败时调用方应停止使用本实例
     */
    public boolean initialize() {
        closed = false;
        if (useMysql) {
            initMySQL();
        } else {
            initSQLite();
        }

        if (!isConnected()) {
            plugin.getLogger().severe("数据库不可用（" + (useMysql ? "MySQL" : "SQLite") + "），玩家数据将无法读写。");
            return false;
        }

        // 迁移对 SQLite 和 MySQL 都需要执行
        try {
            migrateDatabase();
        } catch (SQLException e) {
            plugin.getLogger().severe("数据库迁移失败: " + e.getMessage());
        }

        return true;
    }

    /** 连接是否已就绪（供初始化校验与健康检查） */
    public boolean isConnected() {
        if (closed) {
            return false;
        }
        if (useMysql) {
            return hikariPool != null && !hikariPool.isClosed();
        }
        try {
            return sqliteConnection != null && !sqliteConnection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    // ========== SQLite ==========

    private void initSQLite() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists() && !dataFolder.mkdirs()) {
                plugin.getLogger().warning("插件数据目录创建失败: " + dataFolder.getAbsolutePath());
            }

            String url = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();

            sqliteConnection = DriverManager.getConnection(url);
            createTables();
        } catch (SQLException e) {
            plugin.getLogger().severe("SQLite 连接失败: " + e.getMessage());
            closeQuietly(sqliteConnection);
            sqliteConnection = null;
        } catch (RuntimeException e) {
            // 缺少 sqlite-jdbc 驱动时会抛 NoClassDefFoundError / 驱动未找到异常
            plugin.getLogger().severe("SQLite 初始化失败: " + e);
        }
    }

    // ========== MySQL ==========

    private void initMySQL() {
        // 一次性取配置快照：initMySQL 可能在异步迁移线程执行，
        // 逐项重复调用 getConfig() 时若正好发生热重载，连接参数可能来自不同的配置实例
        org.bukkit.configuration.file.FileConfiguration cfg = plugin.getConfig();
        try {
            String host = cfg.getString("settings.database.mysql.host", "localhost");
            int port = cfg.getInt("settings.database.mysql.port", 3306);
            String database = cfg.getString("settings.database.mysql.database", "luckypeaches");
            String username = cfg.getString("settings.database.mysql.username", "root");
            String password = cfg.getString("settings.database.mysql.password", "");
            int maxConnections = cfg.getInt("settings.database.mysql.max_connections", 10);

            // 库名会被拼进 SQL，做白名单校验避免配置写错导致语法错误/注入
            if (database == null || !database.matches("[A-Za-z0-9_$]+")) {
                plugin.getLogger().severe("MySQL 数据库名非法（只允许字母、数字、_ 和 $）: " + database);
                return;
            }

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
            hikariConfig.setMaximumPoolSize(Math.max(1, maxConnections));
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
            closePool();
        } catch (RuntimeException e) {
            // HikariCP 初始化失败抛的是 RuntimeException（PoolInitializationException）
            plugin.getLogger().severe("MySQL 初始化失败: " + e.getMessage());
            closePool();
        }
    }

    private void closePool() {
        if (hikariPool != null && !hikariPool.isClosed()) {
            hikariPool.close();
        }
        hikariPool = null;
    }

    private void closeQuietly(Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }

    // ========== 连接获取 ==========

    private synchronized Connection getConnection() throws SQLException {
        if (closed) {
            throw new SQLException("数据库已关闭");
        }
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
            boolean hasColumn = false;
            // 必须传 catalog，否则 MySQL 元数据查询可能查不到当前库的列
            try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, tableName, "current_health")) {
                hasColumn = rs.next();
            }
            if (!hasColumn) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN current_health DOUBLE DEFAULT 20.0");
                } catch (SQLException e) {
                    // 元数据不准或并发启动时列可能已存在，忽略"列重复"错误
                    if (!isDuplicateColumnError(e)) {
                        throw e;
                    }
                }
            }
            return null;
        });
    }

    /** 判断是否为"列已存在"错误（MySQL 1060 / SQLite duplicate column） */
    private boolean isDuplicateColumnError(SQLException e) {
        if (e.getErrorCode() == 1060) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("duplicate column");
    }

    // ========== 保存 ==========

    /**
     * 保存玩家数据
     *
     * @return 是否写入成功（失败时调用方可以进行补偿，例如退回消耗掉的蟠桃）
     */
    public boolean savePlayerData(UUID uuid, String username, double peachBonus, double currentHealth) {
        if (closed) {
            return false;
        }
        synchronized (dbLock) {
            try {
                executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
                        pstmt.setString(1, uuid.toString());
                        pstmt.setString(2, username);
                        pstmt.setDouble(3, peachBonus);
                        pstmt.setDouble(4, currentHealth);
                        pstmt.executeUpdate();
                    }
                    return null;
                });
                return true;
            } catch (SQLException e) {
                plugin.getLogger().severe("保存玩家数据失败: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * 保存蟠桃加成，保留数据库里已有的 current_health
     */
    public boolean savePlayerData(UUID uuid, String username, double peachBonus) {
        PlayerHealthData currentData = loadCompletePlayerData(uuid);
        return savePlayerData(uuid, username, peachBonus, currentData.getCurrentHealth());
    }

    /**
     * 只更新血量列，<b>绝不触碰 peach_bonus</b>。
     *
     * <p>退出保存 / 关服保存走这条路径。原来的实现是"读出 peach_bonus 再原样写回"，
     * 在数据库卡顿（例如备份正持有 dbLock 做 VACUUM）时，这个"读-写"之间可能夹进
     * 死亡惩罚或吃桃的写入，于是把已经扣掉/加上的加成覆盖回旧值 —— 惩罚会凭空失效。</p>
     *
     * @return 是否更新了记录（该玩家在库里还没有记录时返回 false，此时不会创建新记录）
     */
    public boolean updateCurrentHealth(UUID uuid, double currentHealth) {
        if (closed) {
            return false;
        }
        String sql = "UPDATE " + tableName + " SET current_health = ?, last_updated = "
            + (useMysql ? "NOW()" : "CURRENT_TIMESTAMP") + " WHERE uuid = ?";
        synchronized (dbLock) {
            try {
                return executeQuery(conn -> {
                    try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                        pstmt.setDouble(1, currentHealth);
                        pstmt.setString(2, uuid.toString());
                        return pstmt.executeUpdate() > 0;
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("更新玩家血量失败: " + e.getMessage());
                return false;
            }
        }
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

    /**
     * 获取玩家排名（从 1 开始）。
     *
     * <p>数据库中没有该玩家记录时返回 -1；有记录但没有加成时返回 -1，
     * 避免旧实现里"查不到 → 子查询为 NULL → 排名 1"的错误结果。</p>
     */
    public int getPlayerRank(UUID uuid) {
        synchronized (dbLock) {
            String ownSql = "SELECT peach_bonus FROM " + tableName + " WHERE uuid = ?";
            String rankSql = "SELECT COUNT(*) as rank FROM " + tableName +
                             " WHERE peach_bonus > 0 AND peach_bonus > ?";

            try {
                return executeQuery(conn -> {
                    double ownBonus;
                    try (PreparedStatement pstmt = conn.prepareStatement(ownSql)) {
                        pstmt.setString(1, uuid.toString());
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (!rs.next()) {
                                return -1; // 无记录
                            }
                            ownBonus = rs.getDouble("peach_bonus");
                        }
                    }

                    if (ownBonus <= 0) {
                        return -1; // 没有蟠桃加成，不参与排名
                    }

                    try (PreparedStatement pstmt = conn.prepareStatement(rankSql)) {
                        pstmt.setDouble(1, ownBonus);
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

    // ========== 备份 ==========

    public boolean backupToFile(File backupFile) {
        if (useMysql) {
            return false;
        }

        synchronized (dbLock) {
            if (!isConnected()) {
                plugin.getLogger().severe("数据库备份失败: SQLite 连接未初始化");
                return false;
            }
            // VACUUM INTO 的路径是字符串字面量，路径里的单引号必须转义，否则语句语法错误
            String escapedPath = backupFile.getAbsolutePath().replace("'", "''");
            try (Statement stmt = sqliteConnection.createStatement()) {
                stmt.execute("VACUUM INTO '" + escapedPath + "'");
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
        closed = true;
        if (useMysql) {
            closePool();
        } else {
            synchronized (dbLock) {
                try {
                    if (sqliteConnection != null && !sqliteConnection.isClosed()) {
                        sqliteConnection.close();
                    }
                } catch (SQLException e) {
                    plugin.getLogger().severe("关闭数据库连接失败: " + e.getMessage());
                } finally {
                    sqliteConnection = null;
                }
            }
        }
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
     *
     * <p>分批 + 事务：整表一次性 executeBatch 在数据量大时会把所有语句堆在内存里，
     * 分包提交既能降低内存峰值，也避免一次失败全部回滚。</p>
     *
     * @return 是否全部写入成功
     */
    public boolean writeAllData(List<Object[]> data) {
        if (data == null || data.isEmpty()) {
            return true;
        }
        synchronized (dbLock) {
            try {
                return executeQuery(conn -> {
                    boolean originalAutoCommit = conn.getAutoCommit();
                    try (PreparedStatement pstmt = conn.prepareStatement(upsertSql)) {
                        conn.setAutoCommit(false);
                        int batched = 0;
                        for (Object[] row : data) {
                            pstmt.setString(1, (String) row[0]);
                            pstmt.setString(2, (String) row[1]);
                            pstmt.setDouble(3, ((Number) row[2]).doubleValue());
                            pstmt.setDouble(4, ((Number) row[3]).doubleValue());
                            pstmt.addBatch();
                            if (++batched % BATCH_SIZE == 0) {
                                pstmt.executeBatch();
                            }
                        }
                        if (batched % BATCH_SIZE != 0) {
                            pstmt.executeBatch();
                        }
                        conn.commit();
                        return Boolean.TRUE;
                    } catch (SQLException e) {
                        try {
                            conn.rollback();
                        } catch (SQLException ignored) {
                        }
                        throw e;
                    } finally {
                        try {
                            conn.setAutoCommit(originalAutoCommit);
                        } catch (SQLException ignored) {
                        }
                    }
                });
            } catch (SQLException e) {
                plugin.getLogger().severe("写入数据失败: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * 从 SQLite 文件导入数据到当前数据库（仅 MySQL 模式可用）
     * @param sourceFile SQLite 数据库文件
     * @return 导入的记录数，失败返回 -1
     */
    public int importFromSQLite(File sourceFile) {
        if (sourceFile == null || !sourceFile.exists()) {
            plugin.getLogger().severe("SQLite 文件不存在: " + (sourceFile == null ? "null" : sourceFile.getAbsolutePath()));
            return -1;
        }

        if (!useMysql) {
            plugin.getLogger().severe("当前不是 MySQL 模式，无法导入");
            return -1;
        }

        List<Object[]> data = new ArrayList<>();
        String url = "jdbc:sqlite:" + sourceFile.getAbsolutePath();

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
        if (!writeAllData(data)) {
            plugin.getLogger().severe("导入失败：写入 MySQL 出错");
            return -1;
        }
        plugin.getLogger().info("成功导入 " + data.size() + " 条记录到 MySQL");
        return data.size();
    }

}
