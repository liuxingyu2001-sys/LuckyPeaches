package com.luckypeaches;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PeachListener implements Listener {
    private final LuckyPeaches plugin;
    private final Random random = new Random();

    /** 蟠桃加成 modifier UUID（改动会让线上已有 modifier 失效，不要修改） */
    public static final UUID PEACH_MODIFIER_UUID = HealthModifierUtil.PEACH_MODIFIER_UUID;
    /** 世界最大生命值 modifier UUID（同上） */
    public static final UUID WORLD_MAX_HEALTH_MODIFIER_UUID = HealthModifierUtil.WORLD_MAX_HEALTH_MODIFIER_UUID;

    /** 死亡惩罚消息的容错格式化（见 formatDeathPenaltyMessage 注释） */
    private static final String PLACEHOLDER_PENALTY = "%penalty%";
    private static final String PLACEHOLDER_PEACH_HEALTH = "%peach_health%";

    private static final Set<UUID> playersInDisabledWorld = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<UUID, String> playersMaxHealthWorld = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<UUID, Long> lastDeathTime = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Set<UUID> eatingPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<UUID> pendingDeathPenalty = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public PeachListener(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    /**
     * 清理静态运行时状态。
     *
     * <p>这些集合是 static 的，插件重载（PlugMan / 重启前 onDisable）时若不清理：
     * 玩家记录会跨实例残留，被取消的调度任务里的 UUID 也会永久留在 pendingDeathPenalty
     * 中，导致该玩家之后的退出保存被静默跳过。</p>
     */
    public static void clearRuntimeState() {
        playersInDisabledWorld.clear();
        playersMaxHealthWorld.clear();
        lastDeathTime.clear();
        eatingPlayers.clear();
        pendingDeathPenalty.clear();
    }

    // ========== 配置读取辅助 ==========

    /** 该世界是否屏蔽蟠桃加成 */
    private boolean isWorldDisabled(String worldName) {
        if (!plugin.getConfig().getBoolean("world_integration.enabled", true)) {
            return false;
        }
        List<String> disabledWorlds = plugin.getConfig().getStringList("world_integration.disabled_worlds");
        return disabledWorlds.contains(worldName);
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

    // ========== 登录 / 退出 ==========

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        // 设置基础生命值
        double baseMaxHealth = plugin.getConfig().getDouble("settings.base_max_health", 20.0);
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (baseMaxHealth > 0 && maxHealthAttr != null && maxHealthAttr.getBaseValue() != baseMaxHealth) {
            maxHealthAttr.setBaseValue(baseMaxHealth);
        }

        String worldName = player.getWorld().getName();

        if (isWorldDisabled(worldName)) {
            // 屏蔽世界：移除蟠桃 modifier，但仍需应用世界最大生命值设置
            playersInDisabledWorld.add(playerId);
            HealthModifierUtil.clearPeachBonus(maxHealthAttr);
            if (plugin.isDebug()) {
                plugin.getLogger().info("玩家 " + player.getName() + " 在屏蔽世界中，移除蟠桃加成");
            }
            applyWorldMaxHealth(player, worldName);
            plugin.updateHealthScale(player);
            return;
        }

        playersInDisabledWorld.remove(playerId);

        // 非屏蔽世界：异步从数据库校验蟠桃加成，确保多端切换后血量正确
        final UUID joiningPlayerId = playerId;
        plugin.runAsync(() -> {
            double peachBonus = plugin.getDatabaseManager().loadPlayerData(joiningPlayerId);

            plugin.runSync(() -> {
                if (!player.isOnline()) return;

                AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attr == null) return;

                // 异步期间玩家可能已进入屏蔽世界，此时不能再套用蟠桃加成
                if (!playersInDisabledWorld.contains(joiningPlayerId)) {
                    // 校验蟠桃 modifier 是否与数据库一致
                    double currentModifierValue = HealthModifierUtil.getPeachBonus(attr);
                    if (Math.abs(currentModifierValue - peachBonus) >= 0.001) {
                        double healthBefore = player.getHealth();
                        HealthModifierUtil.applyPeachBonus(attr, peachBonus);
                        player.setHealth(Math.min(healthBefore, attr.getValue()));

                        if (plugin.isDebug()) {
                            plugin.getLogger().info("玩家 " + player.getName() + " 登录校验: modifier "
                                + currentModifierValue + " → " + peachBonus);
                        }
                    }
                }

                // 使用当前实际所在世界，避免异步期间切换世界后套用旧世界设置
                applyWorldMaxHealth(player, player.getWorld().getName());

                plugin.updateHealthScale(player);
            });
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
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
        plugin.runAsyncLater(() -> {
            // 如果死亡惩罚正在处理中，跳过保存以避免覆盖惩罚结果
            if (pendingDeathPenalty.contains(playerId)) {
                return;
            }
            // 直接读取数据库中的peach_bonus，不重新计算
            // 这样可以避免被其他插件的基础生命值修改影响
            DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
            plugin.getDatabaseManager().savePlayerData(playerId, playerName, healthData.getPeachBonus(), currentHealth);
        }, 20L); // 延迟1秒（20 ticks）
    }

    // ========== 吃桃 ==========

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        // 仅处理右键点击
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        // 已被其他插件（区域/保护/交互拦截）取消，不应消耗蟠桃
        if (event.isCancelled()) return;

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

        // 屏蔽世界不允许使用
        if (isWorldDisabled(player.getWorld().getName())) {
            player.sendMessage(plugin.getMessageManager().getPrefixedMessage("world_disabled"));
            event.setCancelled(true);
            return;
        }

        // 取消原版动作（如吃苹果）
        event.setCancelled(true);

        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttr == null) return;

        // 检查动态上限（基础生命值 + 蟠桃加成，排除世界最大生命值 modifier）
        // 否则在设置了 world_max_health 的世界中总生命值恒 ≥ 上限，无法吃桃
        double limit = getMaxHealthLimit(player);
        double currentPeachBonus = HealthModifierUtil.getPeachBonus(maxHealthAttr);
        double currentTotalHealth = maxHealthAttr.getBaseValue() + currentPeachBonus;
        if (currentTotalHealth >= limit) {
            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("max_health_reached",
                PLACEHOLDER_PEACH_HEALTH, String.format("%.1f", currentPeachBonus)));
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
        } else if (event.getHand() == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(null);
        } else {
            player.getInventory().setItemInOffHand(null);
        }

        // 判定概率（用 < 而不是 <=，避免 chance: 0 时极小概率仍然成功）
        if (random.nextDouble() < config.chance) {
            eatingPlayers.add(playerId);
            // 主线程捕获当前血量和玩家名，异步保存数据库
            final double currentHealth = player.getHealth();
            final String playerName = player.getName();
            plugin.runAsync(() -> {
                try {
                    DatabaseManager.PlayerHealthData healthData =
                        plugin.getDatabaseManager().loadCompletePlayerData(playerId);
                    double newPeachBonus = healthData.getPeachBonus() + config.healthBonus;

                    // 保存新的peach_bonus
                    plugin.getDatabaseManager().savePlayerData(playerId, playerName, newPeachBonus, currentHealth);

                    // 在主线程中应用peach_bonus
                    plugin.runSync(() -> {
                        try {
                            if (!player.isOnline()) return;

                            // 使用AttributeModifier应用peach_bonus，不修改基础生命值
                            AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                            if (attr == null) return;

                            // 异步间隙玩家可能已进入屏蔽世界，此时不套用加成（数值已入库，离开屏蔽世界后恢复）
                            if (!playersInDisabledWorld.contains(playerId)) {
                                // 保存当前血量，防止移除 modifier 时被截断
                                double healthBefore = player.getHealth();
                                HealthModifierUtil.applyPeachBonus(attr, newPeachBonus);

                                // 恢复血量到新上限以内
                                player.setHealth(Math.min(healthBefore + config.healthBonus, attr.getValue()));

                                // 更新缩放
                                plugin.updateHealthScale(player);
                            }

                            // 粒子效果
                            if (plugin.getConfig().getBoolean("settings.enable_particles", true)) {
                                player.spawnParticle(Particle.HEART, player.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
                                player.spawnParticle(Particle.HAPPY_VILLAGER, player.getLocation().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.1);
                            }

                            // 成功音效
                            playConfiguredSound(player, "settings.success_sound", "ENTITY_PLAYER_LEVELUP", 1.2f);

                            // 格式化健康值显示，保留一位小数
                            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("success",
                                "%bonus%", String.format("%.1f", config.healthBonus),
                                PLACEHOLDER_PEACH_HEALTH, String.format("%.1f", newPeachBonus)));
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
            playConfiguredSound(player, "settings.fail_sound", "BLOCK_GLASS_BREAK", 0.8f);

            player.sendMessage(plugin.getMessageManager().getPrefixedReplacedMessage("fail",
                PLACEHOLDER_PEACH_HEALTH, String.format("%.1f", currentPeachBonus)));
        }
    }

    /**
     * 播放配置里的音效，配置值非法时只忽略音效，不影响主流程
     */
    private void playConfiguredSound(Player player, String configPath, String defaultSound, float pitch) {
        try {
            Sound sound = Sound.valueOf(plugin.getConfig().getString(configPath, defaultSound));
            player.playSound(player.getLocation(), sound, 1.0f, pitch);
        } catch (IllegalArgumentException ignored) {
            // 配置了不存在的音效名，静默忽略
        }
    }

    // ========== 死亡惩罚 ==========

    @EventHandler(priority = EventPriority.HIGHEST)
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

        // 没有蟠桃加成（含屏蔽世界已移除的情况）不扣血
        if (HealthModifierUtil.getPeachBonus(maxHealthAttr) <= 0) {
            return;
        }

        final UUID playerId = player.getUniqueId();
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

        final String playerName = player.getName();
        plugin.runAsync(() -> {
            try {
                DatabaseManager.PlayerHealthData healthData =
                    plugin.getDatabaseManager().loadCompletePlayerData(playerId);
                double currentPeachBonus = healthData.getPeachBonus();

                // 检查蟠桃加成是否超过阈值（而不是检查总生命值）
                if (currentPeachBonus <= healthThreshold || currentPeachBonus <= 0) {
                    return;
                }

                // 确认需要扣罚后才标记，避免提前 return 导致标记残留
                pendingDeathPenalty.add(playerId);

                double penalty = Math.max(minPenalty, Math.min(maxPenalty, currentPeachBonus * penaltyPercentage));
                final double newPeachBonus = Math.max(0, currentPeachBonus - penalty);

                // 暂存惩罚信息，延迟恢复时再保存正确的 current_health
                plugin.getDatabaseManager().savePlayerData(playerId, playerName, newPeachBonus);

                long restoreDelay = plugin.getConfig().getLong("settings.death_penalty.restore_delay_ticks", 2L);
                plugin.runSyncLater(() -> {
                    try {
                        if (!player.isOnline()) return;
                        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                        if (attr != null) {
                            // 屏蔽世界中不套用加成，只更新数据库与提示
                            if (!playersInDisabledWorld.contains(playerId)) {
                                HealthModifierUtil.applyPeachBonus(attr, newPeachBonus);
                                plugin.updateHealthScale(player);
                                player.setHealth(Math.min(player.getHealth(), attr.getValue()));
                            }
                            plugin.getDatabaseManager().savePlayerData(playerId, player.getName(), newPeachBonus, player.getHealth());

                            String penaltyMsg = plugin.getMessageManager().getPrefixedMessage("death_penalty");
                            player.sendMessage(formatDeathPenaltyMessage(penaltyMsg, penalty, newPeachBonus));
                        }
                    } finally {
                        pendingDeathPenalty.remove(playerId);
                    }
                }, restoreDelay);
            } catch (Exception e) {
                pendingDeathPenalty.remove(playerId);
                plugin.getLogger().severe("死亡惩罚处理失败: " + e.getMessage());
            }
        });
    }

    /**
     * 容错格式化死亡惩罚消息。
     *
     * <p>新版模板使用 {@code %penalty%} / {@code %peach_health%}；
     * 旧版模板是 {@code %.1f / %.1f}，仍然兼容。
     * 管理员改坏模板（例如写了一个裸 {@code %}）时 {@link String#format} 会抛异常并打断主线程任务，
     * 这里兜底成原样发送。</p>
     */
    private String formatDeathPenaltyMessage(String template, double penalty, double remaining) {
        if (template.contains(PLACEHOLDER_PENALTY) || template.contains(PLACEHOLDER_PEACH_HEALTH)) {
            return template
                .replace(PLACEHOLDER_PENALTY, String.format("%.1f", penalty))
                .replace(PLACEHOLDER_PEACH_HEALTH, String.format("%.1f", remaining));
        }
        try {
            return String.format(template, penalty, remaining);
        } catch (java.util.IllegalFormatException e) {
            plugin.getLogger().warning("death_penalty 消息模板格式错误，已按原文发送: " + e.getMessage());
            return template;
        }
    }

    // ========== 世界切换 ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        String newWorld = player.getWorld().getName();

        if (!plugin.getConfig().getBoolean("world_integration.enabled", true)) {
            // 功能关闭时清掉可能残留的屏蔽状态记录（下次开启时会重新判定）
            playersInDisabledWorld.remove(player.getUniqueId());
        } else if (isWorldDisabled(newWorld)) {
            handleEnterDisabledWorld(player);
        } else {
            handleLeaveDisabledWorld(player);
        }

        applyWorldMaxHealth(player, newWorld);
    }

    /**
     * 配置热重载后刷新所有在线玩家的世界相关状态。
     *
     * <p>屏蔽世界列表 / 世界最大生命值配置变更后，站在受影响世界里的玩家
     * 原本要等下次切世界才会生效，这里立即修正。</p>
     */
    public void refreshWorldStateForOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            String worldName = player.getWorld().getName();
            if (!plugin.getConfig().getBoolean("world_integration.enabled", true)) {
                playersInDisabledWorld.remove(player.getUniqueId());
            } else if (isWorldDisabled(worldName)) {
                handleEnterDisabledWorld(player);
            } else {
                handleLeaveDisabledWorld(player);
            }
            applyWorldMaxHealth(player, worldName);
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

        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            double healthBefore = player.getHealth();
            // 先移除蟠桃血量加成
            HealthModifierUtil.clearPeachBonus(attr);
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
        plugin.runAsync(() -> {
            double peachBonus = plugin.getDatabaseManager().loadCompletePlayerData(playerId).getPeachBonus();

            if (peachBonus <= 0) {
                return;
            }

            long delayTicks = plugin.getConfig().getLong("world_integration.restore_health_delay_ticks", 60L);
            plugin.runSyncLater(() -> {
                // 检查玩家是否重新进入了屏蔽世界或已离线
                if (!player.isOnline() || playersInDisabledWorld.contains(playerUuid)) {
                    return;
                }
                AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                if (attr == null) {
                    return;
                }
                HealthModifierUtil.applyPeachBonus(attr, peachBonus);
                plugin.updateHealthScale(player);

                if (plugin.getConfig().getBoolean("world_integration.restore_full_health_on_exit", true)) {
                    player.setHealth(attr.getValue());
                }
            }, delayTicks);
        });
    }

    /**
     * 检查玩家是否在屏蔽世界中
     */
    public static boolean isPlayerInDisabledWorld(UUID playerUuid) {
        return playersInDisabledWorld.contains(playerUuid);
    }

    /**
     * 应用世界最大生命值设置。
     *
     * <p>功能关闭或世界未配置时，会移除残留的世界最大生命值 modifier，
     * 避免配置改动后旧 modifier 一直挂在玩家身上。</p>
     */
    private void applyWorldMaxHealth(Player player, String worldName) {
        UUID playerUuid = player.getUniqueId();
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);

        if (!plugin.getConfig().getBoolean("world_max_health.enabled", true)) {
            playersMaxHealthWorld.remove(playerUuid);
            if (attr != null && HealthModifierUtil.getAmount(attr, WORLD_MAX_HEALTH_MODIFIER_UUID) > 0) {
                HealthModifierUtil.clearWorldMaxBonus(attr);
                plugin.updateHealthScale(player);
            }
            return;
        }

        org.bukkit.configuration.ConfigurationSection section =
            plugin.getConfig().getConfigurationSection("world_max_health.worlds");
        boolean configured = section != null
            && section.getValues(false).containsKey(worldName);

        if (!configured) {
            if (playersMaxHealthWorld.remove(playerUuid) != null) {
                handleLeaveMaxHealthWorld(player, attr);
            }
            return;
        }

        double maxHealth = plugin.getConfig().getDouble("world_max_health.worlds." + worldName);
        double worldBonus = attr != null ? maxHealth - attr.getBaseValue() : 0;

        // 世界没变**且** modifier 数值已经正确时才跳过；
        // 只比较世界名会让 /lp world setmax 与配置热重载对当前世界的玩家完全失效
        if (worldName.equals(playersMaxHealthWorld.get(playerUuid)) && attr != null) {
            double desired = Math.max(0.0, worldBonus);
            double current = HealthModifierUtil.getAmount(attr, WORLD_MAX_HEALTH_MODIFIER_UUID);
            if (Math.abs(current - desired) < 0.001) {
                return;
            }
        }

        playersMaxHealthWorld.put(playerUuid, worldName);

        if (attr == null) {
            return;
        }

        // 使用AttributeModifier应用世界最大生命值，不修改基础生命值
        // worldBonus 已按当前基础生命值算好（兼容 base_max_health 配置）
        HealthModifierUtil.applyWorldMaxBonus(attr, worldBonus);
        plugin.updateHealthScale(player);
    }

    /**
     * 处理玩家离开最大生命值世界
     */
    private void handleLeaveMaxHealthWorld(Player player, AttributeInstance attr) {
        if (attr == null) {
            return;
        }
        // 只移除世界最大生命值modifier，不影响其他插件的基础生命值
        HealthModifierUtil.clearWorldMaxBonus(attr);
        plugin.updateHealthScale(player);

        if (plugin.getConfig().getBoolean("world_integration.restore_full_health_on_exit", true)) {
            player.setHealth(attr.getValue());
        }
    }
}
