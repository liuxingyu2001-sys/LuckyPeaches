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
        
        licenseManager = new LicenseManager(this);
        licenseManager.loadConfig();
        
        PeachCommand cmd = new PeachCommand(this);
        getCommand("luckypeach").setExecutor(cmd);
        getCommand("luckypeach").setTabCompleter(cmd);
        
        licenseValid = true;
        getLogger().info("授权验证已跳过（开发模式）");
        initializePlugin();
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
    
    private void saveAllOnlinePlayers() {
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

    public void updateHealthScale(org.bukkit.entity.Player player) {
        boolean healthScalingEnabled = getConfig().getBoolean("settings.health_scaling.enable", true);
        if (healthScalingEnabled) {
            double scale = getConfig().getDouble("settings.health_scaling.scale", 40.0);
            player.setHealthScaled(true);
            player.setHealthScale(scale);
        }
        // 关闭时不触碰setHealthScaled，避免覆盖其他插件的血条缩放设置
    }
}
