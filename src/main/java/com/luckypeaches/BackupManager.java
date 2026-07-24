package com.luckypeaches;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

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
        long intervalTicks = intervalHours * 60 * 60 * 20L;

        backupTaskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, () -> {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::backupDatabase);
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

        File backupFolder = new File(dataFolder, plugin.getConfig().getString("settings.database_backup.backup_folder", "backups"));
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
        String timestamp = dateFormat.format(new Date());

        DatabaseManager dbManager = plugin.getDatabaseManager();
        boolean success;

        if (dbManager.isMysql()) {
            // MySQL 模式：导出为 YML 或 JSON
            String format = plugin.getConfig().getString("settings.database_backup.format", "yml");
            String ext = "json".equalsIgnoreCase(format) ? "json" : "yml";
            String backupFileName = "backup_" + timestamp + "." + ext;
            File backupFile = new File(backupFolder, backupFileName);
            success = backupToFile(backupFile, ext);
        } else {
            // SQLite 模式：VACUUM INTO
            String backupFileName = "backup_" + timestamp + ".db";
            File backupFile = new File(backupFolder, backupFileName);
            success = dbManager.backupToFile(backupFile);
        }

        if (success) {
            plugin.getLogger().info("数据库备份成功");
            cleanupOldBackups(backupFolder);
        }
        return success;
    }

    /**
     * MySQL 备份：导出数据到文件
     */
    private boolean backupToFile(File backupFile, String format) {
        List<Map<String, Object>> data = plugin.getDatabaseManager().exportAllData();

        try (FileWriter writer = new FileWriter(backupFile)) {
            if ("json".equalsIgnoreCase(format)) {
                writer.write(toJson(data));
            } else {
                writer.write(toYml(data));
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("备份写入失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 转换为 YML 格式
     */
    private String toYml(List<Map<String, Object>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# LuckyPeaches 数据库备份\n");
        sb.append("# 备份时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\n");
        sb.append("# 记录数: ").append(data.size()).append("\n\n");
        sb.append("players:\n");

        for (Map<String, Object> row : data) {
            sb.append("  - uuid: \"").append(row.get("uuid")).append("\"\n");
            sb.append("    username: \"").append(row.get("username")).append("\"\n");
            sb.append("    peach_bonus: ").append(row.get("peach_bonus")).append("\n");
            sb.append("    current_health: ").append(row.get("current_health")).append("\n");
            sb.append("    last_updated: \"").append(row.get("last_updated")).append("\"\n");
        }

        return sb.toString();
    }

    /**
     * 转换为 JSON 格式
     */
    private String toJson(List<Map<String, Object>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"backup_time\": \"").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())).append("\",\n");
        sb.append("  \"record_count\": ").append(data.size()).append(",\n");
        sb.append("  \"players\": [\n");

        for (int i = 0; i < data.size(); i++) {
            Map<String, Object> row = data.get(i);
            sb.append("    {\n");
            sb.append("      \"uuid\": \"").append(row.get("uuid")).append("\",\n");
            sb.append("      \"username\": \"").append(row.get("username")).append("\",\n");
            sb.append("      \"peach_bonus\": ").append(row.get("peach_bonus")).append(",\n");
            sb.append("      \"current_health\": ").append(row.get("current_health")).append(",\n");
            sb.append("      \"last_updated\": \"").append(row.get("last_updated")).append("\"\n");
            sb.append("    }").append(i < data.size() - 1 ? "," : "").append("\n");
        }

        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 清理旧备份文件
     */
    private void cleanupOldBackups(File backupFolder) {
        int maxBackups = plugin.getConfig().getInt("settings.database_backup.max_backups", 7);

        File[] backupFiles = backupFolder.listFiles((dir, name) -> name.startsWith("backup_"));
        if (backupFiles == null || backupFiles.length <= maxBackups) {
            return;
        }

        List<File> files = new ArrayList<>();
        for (File file : backupFiles) {
            files.add(file);
        }

        Collections.sort(files, Comparator.comparingLong(File::lastModified));

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

        File[] backupFiles = backupFolder.listFiles((dir, name) -> name.startsWith("backup_"));
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
