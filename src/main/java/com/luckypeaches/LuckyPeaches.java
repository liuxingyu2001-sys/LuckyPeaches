package com.luckypeaches;

import org.bukkit.plugin.java.JavaPlugin;

public class LuckyPeaches extends JavaPlugin {
    private static LuckyPeaches instance;
    private PeachManager peachManager;
    private DatabaseManager databaseManager;
    private BackupManager backupManager;
    private MessageManager messageManager;
    private boolean pluginInitialized = false;
    private boolean debug = false;

    // ══════ 群组服共享配置 ══════
    private java.io.File sharedConfigDir;
    private long configPollInterval;
    private org.bukkit.scheduler.BukkitTask configPollTask;
    private final java.util.Map<String, Long> lastConfigMtimes = new java.util.HashMap<>();
    private static final java.util.List<String> CONFIG_FILES = java.util.List.of("config.yml", "messages.yml");
    private org.bukkit.configuration.file.FileConfiguration sharedConfig;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // 共享配置目录（多端共用，替代 Proxy 同步）
        String sharedDir = getConfig().getString("shared_config_dir", "");
        if (sharedDir != null && !sharedDir.isEmpty()) {
            sharedConfigDir = new java.io.File(sharedDir);
            if (!sharedConfigDir.exists()) sharedConfigDir.mkdirs();
            // 确保共享目录包含默认配置文件
            ensureResourceInDir("config.yml", new java.io.File(sharedConfigDir, "config.yml"));
            ensureResourceInDir("messages.yml", new java.io.File(sharedConfigDir, "messages.yml"));
            // 从共享目录加载配置
            reloadConfig();
            getLogger().info("[ConfigSync] 共享配置目录: " + sharedConfigDir.getAbsolutePath());
        }
        configPollInterval = getConfig().getLong("config_poll_interval", 0);

        mergeDefaultConfig();

        PeachCommand cmd = new PeachCommand(this);
        getCommand("luckypeach").setExecutor(cmd);
        getCommand("luckypeach").setTabCompleter(cmd);

        initializePlugin();
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
            sharedConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);
            try (java.io.InputStream defStream = getResource("config.yml")) {
                if (defStream != null) {
                    sharedConfig.setDefaults(org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(defStream)));
                }
            } catch (java.io.IOException ignored) {
            }
            return;
        }
        super.reloadConfig();
        sharedConfig = null;
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
    private void ensureResourceInDir(String resourceName, java.io.File targetFile) {
        if (targetFile.exists()) return;
        try (java.io.InputStream in = getResource(resourceName)) {
            if (in == null) return;
            targetFile.getParentFile().mkdirs();
            java.nio.file.Files.copy(in, targetFile.toPath());
        } catch (java.io.IOException e) {
            getLogger().warning("复制资源 " + resourceName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 合并默认配置，自动补全缺失的配置键
     */
    private void mergeDefaultConfig() {
        org.bukkit.configuration.file.YamlConfiguration defaultConfig =
            org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.InputStreamReader(getResource("config.yml")));

        org.bukkit.configuration.ConfigurationSection currentConfig = getConfig();
        boolean changed = false;

        for (String key : defaultConfig.getKeys(true)) {
            if (!currentConfig.contains(key)) {
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
            for (String name : CONFIG_FILES) {
                java.io.File f = new java.io.File(getConfigDir(), name);
                long mtime = f.exists() ? f.lastModified() : 0L;
                if (mtime != lastConfigMtimes.getOrDefault(name, 0L)) {
                    getLogger().info("[ConfigSync] 检测到 " + name + " 变更，自动重载...");
                    reloadConfig();
                    mergeDefaultConfig();
                    messageManager.reloadMessages();
                    peachManager.loadPeaches();
                    reapplyModifiersForOnlinePlayers();
                    recordConfigMtimes();
                    return;
                }
            }
        }, periodTicks, periodTicks);
        getLogger().info("[ConfigSync] 配置变更检测已启用，间隔: " + configPollInterval + " 秒");
    }
    
    private void initializePlugin() {
        if (pluginInitialized) {
            return;
        }

        this.debug = getConfig().getBoolean("settings.debug", false);

        this.messageManager = new MessageManager(this);

        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        this.backupManager = new BackupManager(this);
        this.backupManager.initialize();

        this.peachManager = new PeachManager();
        this.peachManager.loadPeaches();

        getServer().getPluginManager().registerEvents(new PeachListener(this), this);

        // 注册 PlaceholderAPI 扩展
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new PeachPlaceholder(this).register();
            getLogger().info("已检测到 PlaceholderAPI，已注册占位符扩展。");
        } else {
            getLogger().info("未检测到 PlaceholderAPI，占位符功能不可用。");
        }

        // 启动共享配置变更检测
        startConfigPollTask();

        getLogger().info("LuckyPeaches 插件已启用！");
        pluginInitialized = true;
    }

    @Override
    public void onDisable() {
        if (!pluginInitialized) {
            return;
        }

        if (configPollTask != null) {
            configPollTask.cancel();
            configPollTask = null;
        }

        saveAllOnlinePlayers();
        
        if (backupManager != null) {
            backupManager.shutdown();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
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
                final String playerName = player.getName();
                final double currentHealth = player.getHealth();
                
                DatabaseManager.PlayerHealthData healthData = getDatabaseManager().loadCompletePlayerData(playerId);
                double peachBonus = healthData.getPeachBonus();
                
                if (debug) {
                    getLogger().info("保存玩家 " + playerName + " 的数据: " +
                        "数据库蟠桃加成=" + peachBonus + ", " +
                        "当前血量=" + currentHealth);
                }
                
                getDatabaseManager().savePlayerData(playerId, playerName, peachBonus, currentHealth);
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
            Double peachBonus = preloadedBonuses.get(player.getUniqueId());
            if (peachBonus == null) continue;

            org.bukkit.attribute.AttributeInstance maxHealthAttr =
                player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHealthAttr == null) continue;

            double currentModifierValue = 0;
            for (org.bukkit.attribute.AttributeModifier mod : maxHealthAttr.getModifiers()) {
                if (mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID)) {
                    currentModifierValue = mod.getAmount();
                    break;
                }
            }

            if (Math.abs(currentModifierValue - peachBonus) < 0.001) {
                updateHealthScale(player);
                continue;
            }

            double healthBefore = player.getHealth();

            maxHealthAttr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                .forEach(maxHealthAttr::removeModifier);

            if (peachBonus > 0) {
                org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                    PeachListener.PEACH_MODIFIER_UUID,
                    "LuckyPeaches",
                    peachBonus,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                );
                maxHealthAttr.addModifier(modifier);
            }

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

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            java.util.Map<java.util.UUID, Double> bonuses = new java.util.LinkedHashMap<>();
            for (java.util.UUID id : onlineIds) {
                bonuses.put(id, databaseManager.loadPlayerData(id));
            }

            getServer().getScheduler().runTask(this, () -> {
                reapplyModifiersForOnlinePlayers(bonuses);
            });
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
