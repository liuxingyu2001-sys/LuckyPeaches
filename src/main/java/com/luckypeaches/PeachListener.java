package com.luckypeaches;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public class PeachListener implements Listener {
    private final LuckyPeaches plugin;
    private final Random random = new Random();
    public static final UUID PEACH_MODIFIER_UUID = UUID.nameUUIDFromBytes("LuckyPeaches".getBytes());
    public static final UUID WORLD_MAX_HEALTH_MODIFIER_UUID = UUID.nameUUIDFromBytes("LuckyPeachesWorldMax".getBytes());

    private static final Set<UUID> playersInDisabledWorld = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, String> playersMaxHealthWorld = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> lastDeathTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<UUID> eatingPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingDeathPenalty = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    
    public PeachListener(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    private double getMaxHealthLimit(Player player) {
        double baseLimit = plugin.getConfig().getDouble("settings.max_health_limit", 100.0);
        double maxLimit = baseLimit;

        org.bukkit.configuration.ConfigurationSection vipSection = plugin.getConfig().getConfigurationSection("settings.vip_health_limits");
        if (vipSection != null) {
            for (String key : vipSection.getKeys(false)) {
                if (player.hasPermission("luckypeaches.maxhealth." + key)) {
                    double vipLimit = vipSection.getDouble(key);
                    if (vipLimit > maxLimit) {
                        maxLimit = vipLimit;
                    }
                }
            }
        }
        return maxLimit;
    }

    private double[] getPenaltyConfig(Player player) {
        org.bukkit.configuration.ConfigurationSection penaltyGroups = plugin.getConfig()
            .getConfigurationSection("settings.death_penalty.penalty_groups");
        
        if (penaltyGroups == null) {
            return new double[]{0.1, 0.5, 5.0};
        }
        
        double bestPenaltyPercentage = Double.MAX_VALUE;
        double bestMinPenalty = Double.MAX_VALUE;
        double bestMaxPenalty = Double.MAX_VALUE;
        boolean foundPermission = false;
        
        for (String key : penaltyGroups.getKeys(false)) {
            if (key.equals("default")) {
                continue;
            }
            
            if (player.hasPermission("luckypeaches.deathpenalty." + key)) {
                double penaltyPercentage = penaltyGroups.getDouble(key + ".penalty_percentage", 0.1);
                double minPenalty = penaltyGroups.getDouble(key + ".min_penalty", 0.5);
                double maxPenalty = penaltyGroups.getDouble(key + ".max_penalty", 5.0);
                
                if (penaltyPercentage < bestPenaltyPercentage) {
                    bestPenaltyPercentage = penaltyPercentage;
                    bestMinPenalty = minPenalty;
                    bestMaxPenalty = maxPenalty;
                    foundPermission = true;
                }
            }
        }
        
        if (foundPermission) {
            return new double[]{bestPenaltyPercentage, bestMinPenalty, bestMaxPenalty};
        }
        
        if (penaltyGroups.contains("default")) {
            double penaltyPercentage = penaltyGroups.getDouble("default.penalty_percentage", 0.1);
            double minPenalty = penaltyGroups.getDouble("default.min_penalty", 0.5);
            double maxPenalty = penaltyGroups.getDouble("default.max_penalty", 5.0);
            return new double[]{penaltyPercentage, minPenalty, maxPenalty};
        }
        
        return new double[]{0.1, 0.5, 5.0};
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 检查玩家是否在屏蔽世界中
        final boolean isInDisabledWorld;
        if (plugin.getConfig().getBoolean("world_integration.enabled", true)) {
            java.util.List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
            if (disabledWorlds.contains(player.getWorld().getName())) {
                isInDisabledWorld = true;
                playersInDisabledWorld.add(playerId);
            } else {
                isInDisabledWorld = false;
            }
        } else {
            isInDisabledWorld = false;
        }

        // 屏蔽世界：移除 modifier
        if (isInDisabledWorld) {
            AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (maxHealthAttr != null) {
                maxHealthAttr.getModifiers().stream()
                    .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                    .forEach(maxHealthAttr::removeModifier);
            }
            if (plugin.isDebug()) {
                plugin.getLogger().info("玩家 " + player.getName() + " 在屏蔽世界中，移除蟠桃加成");
            }
        }
        // 非屏蔽世界：信任 playerdata，不做任何操作

        // 更新血条显示
        plugin.updateHealthScale(player);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        final double currentHealth = player.getHealth();
        
        playersInDisabledWorld.remove(playerId);
        playersMaxHealthWorld.remove(playerId);
        eatingPlayers.remove(playerId);
        lastDeathTime.remove(playerId);
        PeachIntegrationAPI.clearBattleStatus(playerId);
        
        // 异步保存到数据库，延迟1秒执行以确保其他插件处理完成
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            // 如果死亡惩罚正在处理中，跳过保存以避免覆盖惩罚结果
            if (pendingDeathPenalty.contains(playerId)) {
                return;
            }
            // 直接读取数据库中的peach_bonus，不重新计算
            // 这样可以避免被其他插件的基础生命值修改影响
            DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
            double peachBonus = healthData.getPeachBonus();
            
            plugin.getDatabaseManager().savePlayerData(playerId, playerName, peachBonus, currentHealth);
        }, 20L); // 延迟1秒（20 ticks）
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 仅处理右键点击
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        
        ItemStack item = event.getItem();
        if (item == null) return;
        
        PeachManager.PeachConfig config = plugin.getPeachManager().getPeachFromItem(item);
        if (config == null) {
            if (plugin.isDebug()) {
                plugin.getLogger().info("[PeachListener] 未识别为蟠桃: " + item.getType());
            }
            return;
        }

        Player player = event.getPlayer();

        // 检查玩家是否在屏蔽世界中
        if (plugin.getConfig().getBoolean("world_integration.enabled", true)) {
            java.util.List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
            if (disabledWorlds.contains(player.getWorld().getName())) {
                player.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_disabled"));
                event.setCancelled(true);
                return;
            }
        }

        // 取消原版动作（如吃苹果）
        event.setCancelled(true);

        // 获取生命值属性
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttr == null) return;

        // 检查动态上限（使用总生命值，因为现在使用AttributeModifier）
        double limit = getMaxHealthLimit(player);
        double currentTotalHealth = maxHealthAttr.getValue();
        if (currentTotalHealth >= limit) {
            double currentPeachForMax = 0;
            for (org.bukkit.attribute.AttributeModifier mod : maxHealthAttr.getModifiers()) {
                if (mod.getUniqueId().equals(PEACH_MODIFIER_UUID)) {
                    currentPeachForMax = mod.getAmount();
                    break;
                }
            }
            String formattedPeachMax = String.format("%.1f", currentPeachForMax);
            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("max_health_reached",
                "%peach_health%", formattedPeachMax));
            return;
        }

        // 防止异步间隙重复吃桃绕过上限检查
        UUID playerId = player.getUniqueId();
        if (eatingPlayers.contains(playerId)) {
            return;
        }

        // 消耗一个物品
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            // 处理主手或副手
            if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) {
                player.getInventory().setItemInMainHand(null);
            } else {
                player.getInventory().setItemInOffHand(null);
            }
        }

        // 判定概率
        if (random.nextDouble() <= config.chance) {
            eatingPlayers.add(playerId);
            // 主线程捕获当前血量，异步保存数据库
            final double currentHealth = player.getHealth();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(player.getUniqueId());
                double currentPeachBonus = healthData.getPeachBonus();
                double newPeachBonus = currentPeachBonus + config.healthBonus;
                
                // 保存新的peach_bonus
                plugin.getDatabaseManager().savePlayerData(player.getUniqueId(), player.getName(), newPeachBonus, currentHealth);
                
                // 在主线程中应用peach_bonus
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        // 使用AttributeModifier应用peach_bonus，不修改基础生命值
                        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        if (attr != null && player.isOnline()) {
                            // 保存当前血量，防止移除 modifier 时被截断
                            double healthBefore = player.getHealth();

                            // 移除旧的蟠桃AttributeModifier
                            attr.getModifiers().stream()
                                .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                                .forEach(attr::removeModifier);

                            // 添加新的蟠桃AttributeModifier
                            org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                                PEACH_MODIFIER_UUID,
                                "LuckyPeaches",
                                newPeachBonus,
                                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                            );
                            attr.addModifier(modifier);

                            // 恢复血量到新上限以内
                            double newCurrentHealth = Math.min(healthBefore + config.healthBonus, attr.getValue());
                            player.setHealth(newCurrentHealth);

                            // 更新缩放
                            plugin.updateHealthScale(player);

                            // 粒子效果
                            if (plugin.getConfig().getBoolean("settings.enable_particles", true)) {
                                player.spawnParticle(Particle.HEART, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
                                player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
                            }

                            // 成功音效
                            try {
                                String soundName = plugin.getConfig().getString("settings.success_sound", "ENTITY_PLAYER_LEVELUP");
                                Sound sound = Sound.valueOf(soundName);
                                player.playSound(player.getLocation(), sound, 1.0f, 1.2f);
                            } catch (IllegalArgumentException e) {
                            }

                            // 格式化健康值显示，保留一位小数
                            String formattedBonus = String.format("%.1f", config.healthBonus);
                            String formattedPeachHealth = String.format("%.1f", newPeachBonus);
                            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("success",
                                "%bonus%", formattedBonus,
                                "%peach_health%", formattedPeachHealth));
                        }
                    } finally {
                        eatingPlayers.remove(playerId);
                    }
                });
                } catch (Exception e) {
                    plugin.getLogger().severe("吃桃处理失败: " + e.getMessage());
                    eatingPlayers.remove(playerId);
                }
            });
        } else {
            // 失败音效
            try {
                String soundName = plugin.getConfig().getString("settings.fail_sound", "BLOCK_GLASS_BREAK");
                Sound sound = Sound.valueOf(soundName);
                player.playSound(player.getLocation(), sound, 1.0f, 0.8f);
            } catch (IllegalArgumentException e) {
            }

            // 获取当前蟠桃血量
            double currentPeachBonus = 0;
            if (maxHealthAttr != null) {
                for (org.bukkit.attribute.AttributeModifier mod : maxHealthAttr.getModifiers()) {
                    if (mod.getUniqueId().equals(PEACH_MODIFIER_UUID)) {
                        currentPeachBonus = mod.getAmount();
                        break;
                    }
                }
            }
            String formattedPeachHealth = String.format("%.1f", currentPeachBonus);
            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("fail",
                "%peach_health%", formattedPeachHealth));
            eatingPlayers.remove(playerId);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!plugin.getConfig().getBoolean("settings.death_penalty.enabled", true)) {
            return;
        }

        final Player player = event.getEntity();
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttr == null) return;

        // 战斗中不扣蟠桃血
        if (PeachIntegrationAPI.isPlayerInBattle(player.getUniqueId())) {
            return;
        }

        // 检查蟠桃modifier是否处于激活状态
        // 如果处于屏蔽世界，不扣蟠桃血
        boolean peachActive = false;
        for (org.bukkit.attribute.AttributeModifier mod : maxHealthAttr.getModifiers()) {
            if (mod.getUniqueId().equals(PEACH_MODIFIER_UUID)) {
                peachActive = true;
                break;
            }
        }
        if (!peachActive) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        long deathCooldown = plugin.getConfig().getLong("settings.death_penalty.death_cooldown_ms", 10000);
        
        Long lastDeath = lastDeathTime.get(playerId);
        if (lastDeath != null && (currentTime - lastDeath) < deathCooldown) {
            player.sendMessage(plugin.getMessageManager().getPrefixedMessage("death_cooldown"));
            return;
        }
        
        lastDeathTime.put(playerId, currentTime);

        double healthThreshold = plugin.getConfig().getDouble("settings.death_penalty.health_threshold", 50.0);
        
        double[] penaltyConfig = getPenaltyConfig(player);
        double penaltyPercentage = penaltyConfig[0];
        double minPenalty = penaltyConfig[1];
        double maxPenalty = penaltyConfig[2];
        
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            pendingDeathPenalty.add(playerId);
            try {
                DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
                double currentPeachBonus = healthData.getPeachBonus();

                // 检查蟠桃加成是否超过阈值（而不是检查总生命值）
                if (currentPeachBonus <= healthThreshold) {
                    return;
                }

                if (currentPeachBonus <= 0) {
                    return;
                }

                double penalty = currentPeachBonus * penaltyPercentage;
                penalty = Math.max(minPenalty, Math.min(maxPenalty, penalty));
                final double finalPenalty = penalty;

                final double newPeachBonus = Math.max(0, currentPeachBonus - penalty);

                // 暂存惩罚信息，延迟恢复时再保存正确的 current_health
                plugin.getDatabaseManager().savePlayerData(playerId, player.getName(), newPeachBonus);

                long restoreDelay = plugin.getConfig().getLong("settings.death_penalty.restore_delay_ticks", 2L);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        attr.getModifiers().stream()
                            .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                            .forEach(attr::removeModifier);

                        if (newPeachBonus > 0) {
                            org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                                PEACH_MODIFIER_UUID,
                                "LuckyPeaches",
                                newPeachBonus,
                                org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                            );
                            attr.addModifier(modifier);
                        }

                        plugin.updateHealthScale(player);
                        // 确保当前血量不超过新上限
                        player.setHealth(Math.min(player.getHealth(), attr.getValue()));

                        // 用正确的 current_health 更新数据库
                        plugin.getDatabaseManager().savePlayerData(playerId, player.getName(), newPeachBonus, player.getHealth());

                        String penaltyMsg = plugin.getMessageManager().getPrefixedMessage("death_penalty");
                        player.sendMessage(ChatColor.translateAlternateColorCodes('&',
                            String.format(penaltyMsg, finalPenalty, newPeachBonus)));
                    }
                }, restoreDelay);
            } finally {
                pendingDeathPenalty.remove(playerId);
            }
        });
    }

    /**
     * 玩家切换世界事件监听
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String newWorld = player.getWorld().getName();
        
        if (!plugin.getConfig().getBoolean("world_integration.enabled", true)) {
            return;
        }

        java.util.List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");

        if (disabledWorlds.contains(newWorld)) {
            handleEnterDisabledWorld(player);
        } else {
            handleLeaveDisabledWorld(player);
        }

        if (plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            applyWorldMaxHealth(player, newWorld);
        }
    }

    /**
     * 处理玩家进入屏蔽世界
     */
    private void handleEnterDisabledWorld(Player player) {
        UUID playerUuid = player.getUniqueId();

        if (playersInDisabledWorld.contains(playerUuid)) {
            return;
        }

        playersInDisabledWorld.add(playerUuid);

        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            double healthBefore = player.getHealth();
            // 先移除蟠桃血量加成
            attr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                .forEach(attr::removeModifier);
            plugin.updateHealthScale(player);
            
            // 立即同步血量到新上限，防止血量断崖触发客户端假死
            double newMax = attr.getValue();
            if (plugin.getConfig().getBoolean("world_integration.restore_full_health_on_enter", false)) {
                player.setHealth(newMax);
            } else {
                player.setHealth(Math.min(healthBefore, newMax));
            }
        }

    }

    /**
     * 处理玩家离开屏蔽世界
     */
    private void handleLeaveDisabledWorld(Player player) {
        UUID playerUuid = player.getUniqueId();

        if (!playersInDisabledWorld.contains(playerUuid)) {
            return;
        }

        playersInDisabledWorld.remove(playerUuid);

        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
            double peachBonus = healthData.getPeachBonus();

            if (peachBonus > 0) {
                long delayTicks = plugin.getConfig().getLong("world_integration.restore_health_delay_ticks", 60L);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    // 检查玩家是否重新进入了屏蔽世界或已离线
                    if (!player.isOnline() || playersInDisabledWorld.contains(playerUuid)) {
                        return;
                    }
                    org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        attr.getModifiers().stream()
                            .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                            .forEach(attr::removeModifier);

                        org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                            PEACH_MODIFIER_UUID,
                            "LuckyPeaches",
                            peachBonus,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                        );
                        attr.addModifier(modifier);
                        plugin.updateHealthScale(player);

                        if (plugin.getConfig().getBoolean("world_integration.restore_full_health_on_exit", true)) {
                            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                            player.setHealth(maxHealth);
                        }
                    }
                }, delayTicks);
            }
        });

    }

    /**
     * 检查玩家是否在屏蔽世界中
     */
    public static boolean isPlayerInDisabledWorld(UUID playerUuid) {
        return playersInDisabledWorld.contains(playerUuid);
    }

    /**
     * 应用世界最大生命值设置
     */
    private void applyWorldMaxHealth(Player player, String worldName) {
        org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("world_max_health.worlds");
        if (section == null) return;
        java.util.Map<String, Object> worldsMap = section.getValues(false);
        
        if (worldsMap.containsKey(worldName)) {
            UUID playerUuid = player.getUniqueId();
            String previousWorld = playersMaxHealthWorld.get(playerUuid);
            
            if (worldName.equals(previousWorld)) {
                return;
            }
            
            playersMaxHealthWorld.put(playerUuid, worldName);
            
            double maxHealth = plugin.getConfig().getDouble("world_max_health.worlds." + worldName);
            
            org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                // 使用AttributeModifier应用世界最大生命值，不修改基础生命值
                // 移除旧的世界最大生命值modifier
                attr.getModifiers().stream()
                    .filter(mod -> mod.getUniqueId().equals(WORLD_MAX_HEALTH_MODIFIER_UUID))
                    .forEach(attr::removeModifier);
                
                // 计算加成差值（相对于原版20点基础生命值）
                double worldBonus = maxHealth - 20.0;
                if (worldBonus > 0) {
                    org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                        WORLD_MAX_HEALTH_MODIFIER_UUID,
                        "LuckyPeachesWorldMax",
                        worldBonus,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                    );
                    attr.addModifier(modifier);
                }
                plugin.updateHealthScale(player);
            }
        } else {
            UUID playerUuid = player.getUniqueId();
            String previousWorld = playersMaxHealthWorld.get(playerUuid);
            if (previousWorld != null) {
                handleLeaveMaxHealthWorld(player);
            }
        }
    }

    /**
     * 处理玩家离开最大生命值世界
     */
    private void handleLeaveMaxHealthWorld(Player player) {
        UUID playerUuid = player.getUniqueId();
        
        String previousWorld = playersMaxHealthWorld.remove(playerUuid);
        
        if (previousWorld == null) {
            return;
        }
        
        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            // 只移除世界最大生命值modifier，不影响其他插件的基础生命值
            attr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(WORLD_MAX_HEALTH_MODIFIER_UUID))
                .forEach(attr::removeModifier);
            plugin.updateHealthScale(player);
            
            if (plugin.getConfig().getBoolean("world_integration.restore_full_health_on_exit", true)) {
                double maxHealth = attr.getValue();
                player.setHealth(maxHealth);
            }
        }
    }
}