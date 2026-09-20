package com.luckypeaches;

import org.bukkit.plugin.java.JavaPlugin;

public class LuckyPeaches extends JavaPlugin {
    private static LuckyPeaches instance;
    private PeachManager peachManager;
    /**
     * volatile：热切换数据库时由主线程替换该引用，而吃桃/退出保存等异步线程也在读它，
     * 非 volatile 时异步线程可能长期看到已被关闭的旧实例。
     */
    private volatile DatabaseManager databaseManager;
    private BackupManager backupManager;
    private MessageManager messageManager;
    private PeachListener peachListener;
    private PeachPlaceholder placeholder;
    private boolean pluginInitialized = false;
    private boolean debug = false;

    // ══════ 群组服共享配置 ══════
    private java.io.File sharedConfigDir;
    private long configPollInterval;
    private org.bukkit.scheduler.BukkitTask configPollTask;
    private final java.util.Map<String, Long> lastConfigMtimes = new java.util.HashMap<>();
    private static final java.util.List<String> CONFIG_FILES = java.util.List.of("config.yml", "messages.yml");
    /** 共享配置；volatile 保证异步线程（备份、指令）能看到主线程热重载后的新实例 */
    private volatile org.bukkit.configuration.file.FileConfiguration sharedConfig;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // 共享配置目录（多端共用，替代 Proxy 同步）
        String sharedDir = getConfig().getString("shared_config_dir", "");
        if (sharedDir != null && !sharedDir.isEmpty()) {
            sharedConfigDir = new java.io.File(sharedDir);
            if (!sharedConfigDir.exists() && !sharedConfigDir.mkdirs()) {
                getLogger().warning("[ConfigSync] 共享配置目录创建失败: " + sharedConfigDir.getAbsolutePath());
            }
            // 确保共享目录包含默认配置文件
            ensureResourceInDir("config.yml", new java.io.File(sharedConfigDir, "config.yml"));
            ensureResourceInDir("messages.yml", new java.io.File(sharedConfigDir, "messages.yml"));
            // 从共享目录加载配置
            reloadConfig();
            getLogger().info("[ConfigSync] 共享配置目录: " + sharedConfigDir.getAbsolutePath());
        }
        configPollInterval = getConfig().getLong("config_poll_interval", 0);

        mergeDefaultConfig();

        org.bukkit.command.PluginCommand pluginCommand = getCommand("luckypeach");
        if (pluginCommand == null) {
            getLogger().severe("plugin.yml 中未定义 luckypeach 指令，管理员指令不可用。");
        } else {
            PeachCommand cmd = new PeachCommand(this);
            pluginCommand.setExecutor(cmd);
            pluginCommand.setTabCompleter(cmd);
        }

        initializePlugin();
    }

    // ══════ 调度器辅助（插件已禁用时静默跳过，避免关服竞态抛 IllegalStateException）══════

    /** 异步执行任务 */
    public void runAsync(Runnable task) {
        runTask(task, true, -1L);
    }

    /** 延迟异步执行任务 */
    public void runAsyncLater(Runnable task, long delayTicks) {
        runTask(task, true, Math.max(0L, delayTicks));
    }

    /** 主线程执行任务 */
    public void runSync(Runnable task) {
        runTask(task, false, -1L);
    }

    /** 延迟主线程执行任务 */
    public void runSyncLater(Runnable task, long delayTicks) {
        runTask(task, false, Math.max(0L, delayTicks));
    }

    private void runTask(Runnable task, boolean async, long delayTicks) {
        if (!isEnabled()) {
            return; // 插件正在关闭：任务要么会被 Bukkit 取消，要么依赖的服务已关闭，直接跳过
        }
        try {
            if (delayTicks < 0) {
                if (async) {
                    getServer().getScheduler().runTaskAsynchronously(this, task);
                } else {
                    getServer().getScheduler().runTask(this, task);
                }
            } else if (async) {
                getServer().getScheduler().runTaskLaterAsynchronously(this, task, delayTicks);
            } else {
                getServer().getScheduler().runTaskLater(this, task, delayTicks);
            }
        } catch (IllegalStateException e) {
            getLogger().fine("插件正在关闭，已跳过调度任务");
        }
    }

    // ══════ 共享配置（重写 Bukkit 配置读写，透明重定向到共享目录）══════

    @Override
    public org.bukkit.configuration.file.FileConfiguration getConfig() {
        if (sharedConfigDir != null) {
            if (sharedConfig == null) reloadConfig();
            return sharedConfig;
        }
        return super.getConfig();
    }

    @Override
    public void reloadConfig() {
        if (sharedConfigDir != null) {
            java.io.File configFile = new java.io.File(sharedConfigDir, "config.yml");
            org.bukkit.configuration.file.YamlConfiguration config =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            try (java.io.InputStream defStream = getResource("config.yml")) {
                if (defStream != null) {
                    try (java.io.InputStreamReader reader = new java.io.InputStreamReader(defStream)) {
                        config.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader));
                    }
                }
            } catch (java.io.IOException e) {
                getLogger().warning("读取默认配置失败: " + e.getMessage());
            }
            sharedConfig = config;
            refreshDebugFlag();
            return;
        }
        super.reloadConfig();
        sharedConfig = null;
        refreshDebugFlag();
    }

    @Override
    public void saveConfig() {
        if (sharedConfigDir != null) {
            try {
                getConfig().save(new java.io.File(sharedConfigDir, "config.yml"));
            } catch (java.io.IOException e) {
                getLogger().severe("保存配置失败: " + e.getMessage());
            }
            return;
        }
        super.saveConfig();
    }

    /**
     * 获取配置文件目录：共享目录优先，否则用插件数据目录
     */
    public java.io.File getConfigDir() {
        return sharedConfigDir != null ? sharedConfigDir : getDataFolder();
    }

    /**
     * 确保资源文件存在于目标目录（支持自定义共享目录）
     */
    void ensureResourceInDir(String resourceName, java.io.File targetFile) {
        if (targetFile == null || targetFile.exists()) return;
        try (java.io.InputStream in = getResource(resourceName)) {
            if (in == null) return;
            java.io.File parent = targetFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            java.nio.file.Files.copy(in, targetFile.toPath());
        } catch (java.io.IOException e) {
            getLogger().warning("复制资源 " + resourceName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 合并默认配置，自动补全缺失的配置键。
     *
     * <p>必须用 {@code contains(key, true)}（忽略 defaults），因为 Bukkit 的
     * {@code contains(key)} 会回退到 defaults 判定，配置一旦 setDefaults 过就永远返回 true，
     * 导致缺键补全功能完全失效。</p>
     */
    public void mergeDefaultConfig() {
        org.bukkit.configuration.file.YamlConfiguration defaultConfig;
        try (java.io.InputStream in = getResource("config.yml")) {
            if (in == null) return;
            try (java.io.InputStreamReader reader = new java.io.InputStreamReader(in)) {
                defaultConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(reader);
            }
        } catch (java.io.IOException e) {
            getLogger().warning("读取默认配置失败: " + e.getMessage());
            return;
        }

        org.bukkit.configuration.ConfigurationSection currentConfig = getConfig();
        boolean changed = false;

        for (String key : defaultConfig.getKeys(true)) {
            if (!currentConfig.contains(key, true)) {
                currentConfig.set(key, defaultConfig.get(key));
                changed = true;
            }
        }

        if (changed) {
            try {
                getConfig().save(new java.io.File(getConfigDir(), "config.yml"));
                getLogger().info("已自动补全缺失的配置键");
            } catch (java.io.IOException e) {
                getLogger().severe("保存配置失败: " + e.getMessage());
            }
        }
    }

    // ══════ 共享配置轮询 ══════

    /** 记录配置文件修改时间 */
    private void recordConfigMtimes() {
        for (String name : CONFIG_FILES) {
            java.io.File f = new java.io.File(getConfigDir(), name);
            lastConfigMtimes.put(name, f.exists() ? f.lastModified() : 0L);
        }
    }

    /** 启动配置文件变更轮询（多端共享目录场景） */
    private void startConfigPollTask() {
        if (configPollInterval <= 0) return;
        recordConfigMtimes();
        long periodTicks = configPollInterval * 20L;
        configPollTask = getServer().getScheduler().runTaskTimer(this, () -> {
            // 必须整体捕获异常：Bukkit 中循环任务抛异常会被自动取消，一次坏配置就永久失去热重载能力
            try {
                pollConfigChanges();
            } catch (Exception e) {
                getLogger().severe("[ConfigSync] 配置热重载失败: " + e.getMessage());
            }
        }, periodTicks, periodTicks);
        getLogger().info("[ConfigSync] 配置变更检测已启用，间隔: " + configPollInterval + " 秒");
    }

    private void pollConfigChanges() {
        for (String name : CONFIG_FILES) {
            java.io.File f = new java.io.File(getConfigDir(), name);
            long mtime = f.exists() ? f.lastModified() : 0L;
            if (mtime != lastConfigMtimes.getOrDefault(name, 0L)) {
                getLogger().info("[ConfigSync] 检测到 " + name + " 变更，自动重载...");
                reloadConfig();
                mergeDefaultConfig();
                messageManager.reloadMessages();
                peachManager.loadPeaches();
                // 先修正世界相关状态（屏蔽世界/世界最大生命值），再重新套用蟠桃加成
                peachListener.refreshWorldStateForOnlinePlayers();
                reapplyModifiersForOnlinePlayers();
                warnIfDatabaseTypeMismatch();
                recordConfigMtimes();
                return;
            }
        }
    }

    /**
     * 热重载不会重建数据库连接：配置里的 type 被改动时给出明确提示，
     * 避免服务器以为换库成功（真正换库要走 /luckypeach db，会迁移数据）。
     */
    private void warnIfDatabaseTypeMismatch() {
        if (databaseManager == null) {
            return;
        }
        boolean configuredMysql = "mysql".equalsIgnoreCase(getConfig().getString("settings.database.type", "sqlite"));
        if (configuredMysql != databaseManager.isMysql()) {
            getLogger().warning("配置中的数据库类型为 " + (configuredMysql ? "mysql" : "sqlite")
                + "，但当前运行在 " + (databaseManager.isMysql() ? "MySQL" : "SQLite")
                + " 上；热重载不会迁移数据，请使用 /luckypeach db <mysql|sqlite> 切换。");
        }
    }

    /** 刷新 debug 开关（配置热重载后也能生效） */
    private void refreshDebugFlag() {
        try {
            this.debug = getConfig().getBoolean("settings.debug", false);
        } catch (RuntimeException e) {
            // 配置尚未就绪时忽略
        }
    }

    private void initializePlugin() {
        if (pluginInitialized) {
            return;
        }
        // 先置位：初始化中途抛异常时 onDisable 仍然要执行清理（否则任务/连接泄漏）
        pluginInitialized = true;

        this.messageManager = new MessageManager(this);

        this.databaseManager = new DatabaseManager(this);
        if (!this.databaseManager.initialize()) {
            getLogger().severe("数据库初始化失败，插件将自动禁用（请检查配置中的数据库连接信息）。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.backupManager = new BackupManager(this);
        this.backupManager.initialize();

        this.peachManager = new PeachManager(this);
        this.peachManager.loadPeaches();

        this.peachListener = new PeachListener(this);
        getServer().getPluginManager().registerEvents(peachListener, this);

        // 注册 PlaceholderAPI 扩展
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            this.placeholder = new PeachPlaceholder(this);
            this.placeholder.register();
            getLogger().info("已检测到 PlaceholderAPI，已注册占位符扩展。");
        } else {
            getLogger().info("未检测到 PlaceholderAPI，占位符功能不可用。");
        }

        // 启动共享配置变更检测
        startConfigPollTask();

        getLogger().info("LuckyPeaches 插件已启用！");
    }

    @Override
    public void onDisable() {
        // 注意：此时 isEnabled() 已经是 false，不能在方法开头依赖任何“已初始化”标记提前 return，
        // 否则初始化中途失败时会漏掉任务取消和数据库关闭。
        if (configPollTask != null) {
            configPollTask.cancel();
            configPollTask = null;
        }

        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            try { PeachIntegrationAPI.setPeachBonusSuppressed(player, false); }
            catch (RuntimeException error) { getLogger().warning("恢复蟠桃血量失败: " + player.getUniqueId() + " - " + error.getMessage()); }
        }
        saveAllOnlinePlayers();

        if (backupManager != null) {
            backupManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }

        // 注销 PlaceholderAPI 扩展，避免插件卸载后仍被 PAPI 持有
        if (placeholder != null) {
            try {
                placeholder.unregister();
            } catch (RuntimeException e) {
                getLogger().fine("注销占位符扩展失败: " + e.getMessage());
            }
        }

        // 清理静态运行时状态与静态实例引用，避免插件重载后残留（内存泄漏 / 状态错乱）
        PeachListener.clearRuntimeState();
        PeachIntegrationAPI.clearAllBattleStatus();

        databaseManager = null;
        backupManager = null;
        messageManager = null;
        peachManager = null;
        peachListener = null;
        placeholder = null;
        sharedConfig = null;
        lastConfigMtimes.clear();
        pluginInitialized = false;
        instance = null;

        getLogger().info("LuckyPeaches 插件已禁用。");
    }

    public void setDatabaseManager(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void setBackupManager(BackupManager backupManager) {
        this.backupManager = backupManager;
    }

    public void saveAllOnlinePlayers() {
        if (databaseManager == null) return;

        getLogger().info("开始保存所有在线玩家数据...");

        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            try {
                final java.util.UUID playerId = player.getUniqueId();
                final double currentHealth = player.getHealth();

                if (debug) {
                    getLogger().info("保存玩家 " + player.getName() + " 的血量: " + currentHealth);
                }

                // 只更新血量列：蟠桃加成在发生变化时（吃桃/死亡惩罚/sethealth）就已入库，
                // 这里再"读出来写回去"只会在数据库卡顿时把并发写入覆盖成旧值
                databaseManager.updateCurrentHealth(playerId, currentHealth);
            } catch (Exception e) {
                getLogger().severe("保存玩家 " + player.getName() + " 数据失败: " + e.getMessage());
            }
        }
    }

    public static LuckyPeaches getInstance() {
        return instance;
    }

    public PeachManager getPeachManager() {
        return peachManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public BackupManager getBackupManager() {
        return backupManager;
    }

    public PeachListener getPeachListener() {
        return peachListener;
    }

    public boolean isDebug() {
        return debug;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * 使用预加载的数据重新对所有在线玩家应用 modifier（主线程调用，不阻塞数据库）
     */
    public void reapplyModifiersForOnlinePlayers(java.util.Map<java.util.UUID, Double> preloadedBonuses) {
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            java.util.UUID playerId = player.getUniqueId();

            org.bukkit.attribute.AttributeInstance maxHealthAttr =
                player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHealthAttr == null) continue;

            // 屏蔽世界：不能重新套用蟠桃加成（配置变更/数据库切换后也要保持屏蔽）
            if (PeachListener.isPlayerInDisabledWorld(playerId)) {
                if (HealthModifierUtil.getPeachBonus(maxHealthAttr) != 0) {
                    double healthBefore = player.getHealth();
                    HealthModifierUtil.clearPeachBonus(maxHealthAttr);
                    player.setHealth(Math.min(healthBefore, maxHealthAttr.getValue()));
                }
                updateHealthScale(player);
                continue;
            }

            Double peachBonus = preloadedBonuses.get(playerId);
            if (peachBonus == null) continue;

            double currentModifierValue = HealthModifierUtil.getPeachBonus(maxHealthAttr);
            if (!PeachIntegrationAPI.isPeachBonusSuppressed(playerId)
                    && Math.abs(currentModifierValue - peachBonus) < 0.001) {
                updateHealthScale(player);
                continue;
            }

            double healthBefore = player.getHealth();
            HealthModifierUtil.applyPeachBonus(player, maxHealthAttr, peachBonus);
            player.setHealth(Math.min(healthBefore, maxHealthAttr.getValue()));
            updateHealthScale(player);
        }
    }

    /**
     * 异步加载数据后重新对所有在线玩家应用 modifier
     */
    public void reapplyModifiersForOnlinePlayers() {
        java.util.Set<java.util.UUID> onlineIds = new java.util.LinkedHashSet<>();
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            onlineIds.add(player.getUniqueId());
        }
        if (onlineIds.isEmpty()) {
            return;
        }

        runAsync(() -> {
            DatabaseManager db = databaseManager;
            if (db == null) return;

            java.util.Map<java.util.UUID, Double> bonuses = new java.util.LinkedHashMap<>();
            for (java.util.UUID id : onlineIds) {
                bonuses.put(id, db.loadPlayerData(id));
            }

            runSync(() -> reapplyModifiersForOnlinePlayers(bonuses));
        });
    }

    public void updateHealthScale(org.bukkit.entity.Player player) {
        boolean healthScalingEnabled = getConfig().getBoolean("settings.health_scaling.enable", true);
        if (healthScalingEnabled) {
            double scale = getConfig().getDouble("settings.health_scaling.scale", 40.0);
            // 防止 scale ≤ 0 导致客户端血条渲染异常（假死）
            if (scale <= 0) {
                scale = 20.0;
            }
            player.setHealthScaled(true);
            player.setHealthScale(scale);
        }
        // 关闭时不触碰setHealthScaled，避免覆盖其他插件的血条缩放设置
    }
}
