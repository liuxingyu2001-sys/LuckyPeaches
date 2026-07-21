package com.luckypeaches;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class PeachCommand implements CommandExecutor, TabCompleter {
    private final LuckyPeaches plugin;

    public PeachCommand(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("luckypeach.admin")) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("no_permission"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                handleReload(sender, args);
                break;
            case "gethealth":
                handleGetHealth(sender, args);
                break;
            case "sethealth":
                handleSetHealth(sender, args);
                break;
            case "give":
                handleGive(sender, args);
                break;
            case "backup":
                handleBackup(sender, args);
                break;
            case "world":
                handleWorld(sender, args);
                break;
            case "db":
                handleDatabase(sender, args);
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    private void handleReload(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("license")) {
            reloadLicense(sender);
            return;
        }
        
        plugin.reloadConfig();
        plugin.getPeachManager().loadPeaches();
        plugin.getMessageManager().reloadMessages();
        sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("reload_success"));
    }
    
    private void reloadLicense(CommandSender sender) {
        sender.sendMessage(ChatColor.YELLOW + "正在重新验证授权...");
        
        boolean wasInvalid = !plugin.isLicenseValid();
        
        plugin.getLicenseManager().reloadConfig();
        plugin.getLicenseManager().verifyLicense().thenAccept(result -> {
            if (result.isSuccess()) {
                plugin.setLicenseValid(true);
                sender.sendMessage(ChatColor.GREEN + "✓ 授权验证成功！");
                sender.sendMessage(ChatColor.GREEN + "密钥: " + plugin.getLicenseManager().getLicenseKey());
                
                if (wasInvalid) {
                    sender.sendMessage(ChatColor.GREEN + "正在初始化插件功能...");
                    plugin.initializePluginAfterLicense();
                }
            } else {
                plugin.setLicenseValid(false);
                sender.sendMessage(ChatColor.RED + "✗ 授权验证失败！");
                sender.sendMessage(ChatColor.RED + "错误: " + result.getMessage());
            }
        }).exceptionally(throwable -> {
            sender.sendMessage(ChatColor.RED + "授权验证发生异常: " + throwable.getMessage());
            return null;
        });
    }

    private void handleGetHealth(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /lp gethealth <玩家>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "错误: 玩家 " + args[1] + " 不在线。");
            return;
        }

        final UUID targetId = target.getUniqueId();
        final String targetName = target.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            double peachBonus = plugin.getDatabaseManager().loadCompletePlayerData(targetId).getPeachBonus();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("get_health",
                    "%player%", targetName,
                    "%health%", String.format("%.1f", peachBonus)));
            });
        });
    }

    private void handleSetHealth(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /lp sethealth <玩家> <数值>");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "错误: 玩家 " + args[1] + " 不在线。");
            return;
        }

        double newBonus;
        try {
            newBonus = Double.parseDouble(args[2]);
            if (newBonus < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "错误: 数值必须是非负数字。");
            return;
        }

        final UUID targetId = target.getUniqueId();
        final String targetName = target.getName();
        final double currentHealth = target.getHealth();
        final double finalNewBonus = newBonus;

        // 先异步保存数据库，成功后再在主线程应用modifier
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getDatabaseManager().savePlayerData(targetId, targetName, finalNewBonus, currentHealth);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player onlineTarget = Bukkit.getPlayer(targetId);
                if (onlineTarget == null || !onlineTarget.isOnline()) return;

                AttributeInstance maxHealthAttr = onlineTarget.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (maxHealthAttr != null) {
                    maxHealthAttr.getModifiers().stream()
                        .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                        .forEach(maxHealthAttr::removeModifier);

                    org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                        PeachListener.PEACH_MODIFIER_UUID,
                        "LuckyPeaches",
                        finalNewBonus,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                    );
                    maxHealthAttr.addModifier(modifier);

                    double newHealth = maxHealthAttr.getValue();
                    if (currentHealth > newHealth) {
                        onlineTarget.setHealth(newHealth);
                    }
                }

                plugin.updateHealthScale(onlineTarget);

                sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("set_health_success",
                    "%player%", targetName,
                    "%health%", String.format("%.1f", finalNewBonus)));
            });
        });
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "用法: /lp give <玩家> <ID> [数量]");
            return;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "错误: 玩家 " + args[1] + " 不在线。");
            return;
        }

        String peachId = args[2];
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "错误: 数量必须是正整数。");
                return;
            }
        }

        ItemStack peach = plugin.getPeachManager().createPeachItem(peachId, amount);
        if (peach == null) {
            sender.sendMessage(ChatColor.RED + "错误: 找不到 ID 为 '" + peachId + "' 的蟠桃配置。");
            return;
        }

        java.util.Map<Integer, ItemStack> leftover = target.getInventory().addItem(peach);
        
        String peachName = peach.hasItemMeta() && peach.getItemMeta().hasDisplayName()
            ? peach.getItemMeta().getDisplayName() : peach.getType().name();
        
        if (!leftover.isEmpty()) {
            for (ItemStack drop : leftover.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), drop);
            }
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("give_dropped",
                "%peach%", peachName,
                "%amount%", String.valueOf(leftover.values().stream().mapToInt(ItemStack::getAmount).sum())));
        } else {
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("give_success",
                "%peach%", peachName,
                "%amount%", String.valueOf(amount),
                "%player%", target.getName()));
        }
    }

    private void handleBackup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /lp backup <now|list|enable|disable>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "now":
                boolean success = plugin.getBackupManager().backupDatabase();
                if (success) {
                    sender.sendMessage(ChatColor.GREEN + "数据库备份成功！");
                } else {
                    sender.sendMessage(ChatColor.RED + "数据库备份失败，请查看控制台日志。");
                }
                break;
            case "list":
                List<String> backups = plugin.getBackupManager().getBackupList();
                if (backups.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "暂无备份文件。");
                } else {
                    sender.sendMessage(ChatColor.GREEN + "备份文件列表：");
                    for (String backup : backups) {
                        sender.sendMessage(ChatColor.WHITE + "  - " + backup);
                    }
                }
                break;
            case "enable":
                plugin.getConfig().set("settings.database_backup.enabled", true);
                plugin.saveConfig();
                plugin.getBackupManager().restartBackupTask();
                sender.sendMessage(ChatColor.GREEN + "自动备份已启用。");
                break;
            case "disable":
                plugin.getConfig().set("settings.database_backup.enabled", false);
                plugin.saveConfig();
                plugin.getBackupManager().restartBackupTask();
                sender.sendMessage(ChatColor.YELLOW + "自动备份已禁用。");
                break;
            default:
                sender.sendMessage(ChatColor.RED + "用法: /lp backup <now|list|enable|disable>");
                break;
        }
    }

    private void handleWorld(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /lp world <add|remove|list|setmax|getmax|listmax|removemax> [世界名称] [数值]");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "add":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /lp world add <世界名称>");
                    return;
                }
                handleWorldAdd(sender, args[2]);
                break;
            case "remove":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /lp world remove <世界名称>");
                    return;
                }
                handleWorldRemove(sender, args[2]);
                break;
            case "list":
                handleWorldList(sender);
                break;
            case "setmax":
                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "用法: /lp world setmax <世界名称> <数值>");
                    return;
                }
                handleWorldSetMax(sender, args[2], args[3]);
                break;
            case "getmax":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /lp world getmax <世界名称>");
                    return;
                }
                handleWorldGetMax(sender, args[2]);
                break;
            case "listmax":
                handleWorldListMax(sender);
                break;
            case "removemax":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "用法: /lp world removemax <世界名称>");
                    return;
                }
                handleWorldRemoveMax(sender, args[2]);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "用法: /lp world <add|remove|list|setmax|getmax|listmax|removemax> [世界名称] [数值]");
                break;
        }
    }

    private void handleWorldAdd(CommandSender sender, String worldName) {
        List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
        
        if (disabledWorlds.contains(worldName)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_already_disabled",
                "%world%", worldName));
            return;
        }
        
        disabledWorlds.add(worldName);
        plugin.getConfig().set("world_integration.disabled_worlds", disabledWorlds);
        plugin.saveConfig();
        
        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_add",
            "%world%", worldName));
    }

    private void handleWorldRemove(CommandSender sender, String worldName) {
        List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
        
        if (!disabledWorlds.contains(worldName)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_not_disabled",
                "%world%", worldName));
            return;
        }
        
        disabledWorlds.remove(worldName);
        plugin.getConfig().set("world_integration.disabled_worlds", disabledWorlds);
        plugin.saveConfig();
        
        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_remove",
            "%world%", worldName));
    }

    private void handleWorldList(CommandSender sender) {
        List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
        
        if (disabledWorlds.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "当前没有屏蔽任何世界。");
        } else {
            String worldsList = String.join(", ", disabledWorlds);
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_list",
                "%worlds%", worldsList));
        }
    }

    private void handleWorldSetMax(CommandSender sender, String worldName, String healthValue) {
        if (!plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_max_health_not_enabled"));
            return;
        }

        double maxHealth;
        try {
            maxHealth = Double.parseDouble(healthValue);
            if (maxHealth <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "错误: 数值必须是正数。");
            return;
        }

        plugin.getConfig().set("world_max_health.worlds." + worldName, maxHealth);
        plugin.saveConfig();

        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_max_health_set",
            "%world%", worldName,
            "%health%", String.valueOf(maxHealth)));
    }

    private void handleWorldGetMax(CommandSender sender, String worldName) {
        if (!plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_max_health_not_enabled"));
            return;
        }

        if (plugin.getConfig().contains("world_max_health.worlds." + worldName)) {
            double maxHealth = plugin.getConfig().getDouble("world_max_health.worlds." + worldName);
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_max_health_get",
                "%world%", worldName,
                "%health%", String.valueOf(maxHealth)));
        } else {
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_max_health_not_set",
                "%world%", worldName));
        }
    }

    private void handleWorldListMax(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_max_health_not_enabled"));
            return;
        }

        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("world_max_health.worlds");
        if (section == null || section.getKeys(false).isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "当前没有设置任何世界的最大生命值。");
            return;
        }
        java.util.Map<String, Object> worldsMap = section.getValues(false);

        sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_max_health_list"));

        for (java.util.Map.Entry<String, Object> entry : worldsMap.entrySet()) {
            String world = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof Number)) continue;
            double health = ((Number) value).doubleValue();
            sender.sendMessage(plugin.getMessageManager().getReplacedMessage("world_max_health_list_item",
                    "%world%", world,
                    "%health%", String.valueOf(health)));
            }
    }

    private void handleWorldRemoveMax(CommandSender sender, String worldName) {
        if (!plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            sender.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_max_health_not_enabled"));
            return;
        }

        if (plugin.getConfig().contains("world_max_health.worlds." + worldName)) {
            plugin.getConfig().set("world_max_health.worlds." + worldName, null);
            plugin.saveConfig();

            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_max_health_removed",
                "%world%", worldName));
        } else {
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("world_max_health_not_set",
                "%world%", worldName));
        }
    }

    private void handleDatabase(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendDatabaseHelpMessage(sender);
            return;
        }

        switch (args[1].toLowerCase()) {
            case "status":
                String currentType = plugin.getDatabaseManager().isUseMysql() ? "MySQL" : "SQLite";
                sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_current_type",
                    "%type%", currentType));
                break;
            case "mysql":
            case "sqlite":
                handleDatabaseSwitch(sender, args[1].toLowerCase());
                break;
            default:
                plugin.getMessageManager().sendDatabaseHelpMessage(sender);
                break;
        }
    }

    private void handleDatabaseSwitch(CommandSender sender, String targetType) {
        boolean targetIsMysql = "mysql".equalsIgnoreCase(targetType);
        boolean currentIsMysql = plugin.getDatabaseManager().isUseMysql();

        if (targetIsMysql == currentIsMysql) {
            String typeName = targetIsMysql ? "MySQL" : "SQLite";
            sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_already_same_type",
                "%type%", typeName));
            return;
        }

        String typeName = targetIsMysql ? "MySQL" : "SQLite";
        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_switching",
            "%type%", typeName));

        // 异步执行迁移
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 1. 保存所有在线玩家数据到旧数据库
                plugin.saveAllOnlinePlayers();

                // 2. 读取当前全部数据
                java.util.List<Object[]> data = plugin.getDatabaseManager().readAllDataForMigration();
                plugin.getLogger().info("已读取 " + data.size() + " 条记录，准备迁移到 " + typeName + "...");

                // 3. 关闭旧数据库
                plugin.getDatabaseManager().close();

                // 4. 修改 config
                plugin.getConfig().set("settings.database.type", targetType);
                plugin.saveConfig();
                plugin.reloadConfig();

                // 5. 创建新的 DatabaseManager 并初始化
                com.luckypeaches.DatabaseManager newDbManager = new com.luckypeaches.DatabaseManager(plugin);
                newDbManager.initialize();

                // 6. 写入数据到新数据库
                if (!data.isEmpty()) {
                    newDbManager.writeAllData(data);
                    plugin.getLogger().info("数据迁移完成，共迁移 " + data.size() + " 条记录");
                }

                // 7. 替换 databaseManager
                plugin.setDatabaseManager(newDbManager);

                // 8. 重启 BackupManager
                plugin.getBackupManager().shutdown();
                plugin.setBackupManager(new com.luckypeaches.BackupManager(plugin));
                plugin.getBackupManager().initialize();

                // 9. 主线程重新应用 modifier
                final int count = data.size();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.reapplyModifiersForOnlinePlayers();
                    if (count > 0) {
                        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_switch_success",
                            "%type%", typeName,
                            "%count%", String.valueOf(count)));
                    } else {
                        sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_switch_success_empty",
                            "%type%", typeName));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().severe("数据库切换失败: " + e.getMessage());
                e.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("db_switch_failed",
                        "%error%", e.getMessage()));
                });
            }
        });
    }

    private void sendHelp(CommandSender sender) {
        plugin.getMessageManager().sendHelpMessage(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("luckypeach.admin")) return new ArrayList<>();

        if (args.length == 1) {
            return Arrays.asList("give", "reload", "help", "gethealth", "sethealth", "backup", "world", "db").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("gethealth") || args[0].equalsIgnoreCase("sethealth"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("backup")) {
            return Arrays.asList("now", "list", "enable", "disable").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return Arrays.asList("license").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("db")) {
            return Arrays.asList("status", "mysql", "sqlite").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return Arrays.asList("add", "remove", "list", "setmax", "getmax", "listmax", "removemax").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("world")) {
            if (args[1].equalsIgnoreCase("add")) {
                return Bukkit.getWorlds().stream()
                        .map(org.bukkit.World::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("remove")) {
                return plugin.getConfig().getStringList("world_integration.disabled_worlds").stream()
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[1].equalsIgnoreCase("setmax") || args[1].equalsIgnoreCase("getmax") || args[1].equalsIgnoreCase("removemax")) {
                return Bukkit.getWorlds().stream()
                        .map(org.bukkit.World::getName)
                        .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return plugin.getPeachManager().getPeachIds().stream()
                    .filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return Arrays.asList("1", "16", "32", "64");
        }

        return new ArrayList<>();
    }
}