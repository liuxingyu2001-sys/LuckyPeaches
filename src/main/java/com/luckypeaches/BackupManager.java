package com.luckypeaches;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

public class BackupManager {
    private final LuckyPeaches plugin;
    private int backupTaskId = -1;

    public BackupManager(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    /**
     * 初始化备份任务
     */
    public void initialize() {
        if (plugin.getConfig().getBoolean("settings.database_backup.enabled", true)) {
            startBackupTask();
        }
    }

    /**
     * 启动定时备份任务
     */
    private void startBackupTask() {
        int intervalHours = plugin.getConfig().getInt("settings.database_backup.backup_interval_hours", 24);
        long intervalTicks = intervalHours * 60 * 60 * 20L; // 转换为tick（1小时=3600秒=72000tick）

        backupTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            backupDatabase();
        }, intervalTicks, intervalTicks);

        plugin.getLogger().info("数据库自动备份已启用，间隔: " + intervalHours + " 小时");
    }

    /**
     * 停止备份任务
     */
    public void shutdown() {
        if (backupTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(backupTaskId);
            backupTaskId = -1;
        }
    }

    /**
     * 执行数据库备份
     * @return 备份是否成功
     */
    public boolean backupDatabase() {
        File dataFolder = plugin.getDataFolder();
        File dbFile = new File(dataFolder, "data.db");

        if (!dbFile.exists()) {
            plugin.getLogger().warning("数据库文件不存在，无法备份");
            return false;
        }

        File backupFolder = new File(dataFolder, plugin.getConfig().getString("settings.database_backup.backup_folder", "backups"));
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String timestamp = dateFormat.format(new Date());
        String backupFileName = "backup_" + timestamp + ".db";
        File backupFile = new File(backupFolder, backupFileName);

        try {
            copyFile(dbFile, backupFile);
            plugin.getLogger().info("数据库备份成功: " + backupFileName);
            
            // 清理旧备份
            cleanupOldBackups(backupFolder);
            
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("数据库备份失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 复制文件
     */
    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(destination);
             FileChannel sourceChannel = fis.getChannel();
             FileChannel destChannel = fos.getChannel()) {
            destChannel.transferFrom(sourceChannel, 0, sourceChannel.size());
        }
    }

    /**
     * 清理旧备份文件
     */
    private void cleanupOldBackups(File backupFolder) {
        int maxBackups = plugin.getConfig().getInt("settings.database_backup.max_backups", 7);
        
        File[] backupFiles = backupFolder.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".db"));
        if (backupFiles == null || backupFiles.length <= maxBackups) {
            return;
        }

        List<File> files = new ArrayList<>();
        for (File file : backupFiles) {
            files.add(file);
        }

        // 按修改时间排序（最旧的在前面）
        Collections.sort(files, Comparator.comparingLong(File::lastModified));

        // 删除超出数量限制的旧备份
        int filesToDelete = files.size() - maxBackups;
        for (int i = 0; i < filesToDelete; i++) {
            files.get(i).delete();
        }
    }

    /**
     * 获取备份文件列表
     */
    public List<String> getBackupList() {
        File backupFolder = new File(plugin.getDataFolder(), plugin.getConfig().getString("settings.database_backup.backup_folder", "backups"));
        List<String> backupList = new ArrayList<>();

        if (!backupFolder.exists()) {
            return backupList;
        }

        File[] backupFiles = backupFolder.listFiles((dir, name) -> name.startsWith("backup_") && name.endsWith(".db"));
        if (backupFiles == null) {
            return backupList;
        }

        for (File file : backupFiles) {
            backupList.add(file.getName());
        }

        return backupList;
    }

    /**
     * 重启备份任务（用于配置重载）
     */
    public void restartBackupTask() {
        shutdown();
        if (plugin.getConfig().getBoolean("settings.database_backup.enabled", true)) {
            startBackupTask();
        }
    }
}
