package com.luckypeaches;

import org.bukkit.plugin.java.JavaPlugin;
import com.luckypeaches.license.LicenseManager;

public class LuckyPeaches extends JavaPlugin {
    private static LuckyPeaches instance;
    private PeachManager peachManager;
    private DatabaseManager databaseManager;
    private BackupManager backupManager;
    private LicenseManager licenseManager;
    private MessageManager messageManager;
    private boolean licenseValid = false;
    private boolean pluginInitialized = false;
    private boolean debug = false;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        mergeDefaultConfig();

        licenseManager = new LicenseManager(this);
        licenseManager.loadConfig();
        
        PeachCommand cmd = new PeachCommand(this);
        getCommand("luckypeach").setExecutor(cmd);
        getCommand("luckypeach").setTabCompleter(cmd);
        
        licenseValid = true;
        getLogger().info("授权验证已跳过（开发模式）");
        initializePlugin();
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
                getConfig().save(new java.io.File(getDataFolder(), "config.yml"));
                getLogger().info("已自动补全缺失的配置键");
            } catch (java.io.IOException e) {
                getLogger().severe("保存配置失败: " + e.getMessage());
            }
        }
    }
    
    private void initializePlugin() {
        if (pluginInitialized) {
            return;
        }
        
        this.debug = getConfig().getBoolean("settings.debug", false);
        
        this.messageManager = new MessageManager(this);
        
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // MySQL 模式：从代理端同步配置
        if (databaseManager.isMysql()) {
            getLogger().info("正在从代理端同步配置...");
            String configData = databaseManager.loadConfigFromDatabase();
            if (configData != null) {
                getLogger().info("已获取代理端配置数据，长度: " + configData.length());
                org.bukkit.configuration.file.YamlConfiguration mysqlConfig =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.StringReader(configData));
                // 检查 peaches 配置
                if (mysqlConfig.contains("peaches")) {
                    getLogger().info("代理端配置包含 peaches 节，键数量: " +
                        mysqlConfig.getConfigurationSection("peaches").getKeys(false).size());
                } else {
                    getLogger().warning("代理端配置不包含 peaches 节！");
                }
                // 保存本地数据库配置（完整的 settings.database 部分）
                org.bukkit.configuration.ConfigurationSection localDbSection =
                    getConfig().getConfigurationSection("settings.database");
                // 合并配置
                for (String key : mysqlConfig.getKeys(true)) {
                    // 跳过 database 相关配置，保留本地的
                    if (key.startsWith("database.") || key.equals("database")
                        || key.startsWith("settings.database.") || key.equals("settings.database")) {
                        continue;
                    }
                    getConfig().set(key, mysqlConfig.get(key));
                }
                // 恢复本地数据库配置
                if (localDbSection != null) {
                    getConfig().set("settings.database", localDbSection);
                }
                getLogger().info("已从代理端同步配置");
            } else {
                getLogger().warning("MySQL 中无配置数据，使用本地配置");
            }
        }
        
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

        getLogger().info("LuckyPeaches 插件已启用！");
        pluginInitialized = true;
    }
    
    public void initializePluginAfterLicense() {
        getServer().getScheduler().runTask(this, () -> {
            initializePlugin();
        });
    }

    @Override
    public void onDisable() {
        if (!licenseValid || !pluginInitialized) {
            return;
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
    
    public LicenseManager getLicenseManager() {
        return licenseManager;
    }
    
    public boolean isLicenseValid() {
        return licenseValid;
    }
    
    public void setLicenseValid(boolean valid) {
        this.licenseValid = valid;
    }
    
    public boolean isDebug() {
        return debug;
    }
    
    public MessageManager getMessageManager() {
        return messageManager;
    }

    /**
     * 重新对所有在线玩家应用 modifier
     */
    public void reapplyModifiersForOnlinePlayers() {
        for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
            DatabaseManager.PlayerHealthData data = databaseManager.loadCompletePlayerData(player.getUniqueId());
            double peachBonus = data.getPeachBonus();

            org.bukkit.attribute.AttributeInstance maxHealthAttr =
                player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (maxHealthAttr == null) continue;

            // 检查当前 modifier 值是否已正确
            double currentModifierValue = 0;
            for (org.bukkit.attribute.AttributeModifier mod : maxHealthAttr.getModifiers()) {
                if (mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID)) {
                    currentModifierValue = mod.getAmount();
                    break;
                }
            }

            // 值一致则跳过，避免不必要的 remove/add 触发受伤动画
            if (Math.abs(currentModifierValue - peachBonus) < 0.001) {
                updateHealthScale(player);
                continue;
            }

            // 保存当前血量，防止移除 modifier 时被 Minecraft 截断
            double healthBefore = player.getHealth();

            // 移除旧 modifier
            maxHealthAttr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                .forEach(maxHealthAttr::removeModifier);

            // 添加新 modifier
            if (peachBonus > 0) {
                org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                    PeachListener.PEACH_MODIFIER_UUID,
                    "LuckyPeaches",
                    peachBonus,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                );
                maxHealthAttr.addModifier(modifier);
            }

            // 恢复血量到新上限以内，避免受伤动画
            player.setHealth(Math.min(healthBefore, maxHealthAttr.getValue()));

            // 应用血量缩放
            updateHealthScale(player);
        }
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
