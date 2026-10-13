package com.luckypeaches;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供其他插件调用的公共 API。
 *
 * <p>战斗标记只记录状态，不立即改动 modifier，避免血条视觉波动；
 * 真正恢复加成时才从数据库读取并同步 modifier。</p>
 */
public class PeachIntegrationAPI {

    private static final Set<UUID> playersInBattle = ConcurrentHashMap.newKeySet();

    /**
     * 设置死亡扣罚豁免标记，不屏蔽血量加成。
     */
    public static void setPlayerInBattle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        playersInBattle.add(player.getUniqueId());
    }

    // Main-thread mutations; async callers may query the flag safely.
    private static final java.util.Map<UUID, Double> suppressedBonuses = new ConcurrentHashMap<>();

    /**
     * 屏蔽世界内 modifier 会被清空（但不改数据库），这里缓存进入前的真实加成，
     * 让 {@link #persistentPeachBonus(Player)} 在 modifier 为 0 时仍能返回真实值，
     * 避免从屏蔽世界退出时的跨服快照 / 退出保存把加成写成 0。
     */
    private static final java.util.Map<UUID, Double> blockedWorldBonuses = new ConcurrentHashMap<>();

    /**
     * Temporarily suppress ONLY the peach health modifier. Call on the primary thread.
     * Repeated calls are idempotent. Restoring also works during quit/shutdown,
     * without waiting for database tasks. Persistent peach totals are never changed. Living players are healed to the restored maximum.
     */
    public static void setPeachBonusSuppressed(Player player, boolean suppressed) {
        if (player == null) return;
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("蟠桃血量屏蔽 API 必须在主线程调用");
        }
        UUID uuid = player.getUniqueId();
        org.bukkit.attribute.AttributeInstance attr =
            player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) throw new IllegalStateException("玩家缺少最大血量属性");
        if (suppressed) {
            suppressedBonuses.putIfAbsent(uuid, HealthModifierUtil.getPeachBonus(attr));
            HealthModifierUtil.clearPeachBonus(attr);
        } else {
            Double bonus = suppressedBonuses.get(uuid);
            if (bonus == null) return;
            HealthModifierUtil.applyPeachBonus(attr, isWorldBlocked(player) ? 0 : bonus);
            suppressedBonuses.remove(uuid);
        }
        double health = player.getHealth();
        if (!suppressed && !player.isDead() && health > 0) player.setHealth(attr.getValue());
        else if (health > attr.getValue()) player.setHealth(attr.getValue());
        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin != null) plugin.updateHealthScale(player);
    }

    /**
     * 恢复被屏蔽的蟠桃加成，但<b>保留玩家的真实当前血量</b>（只做上限夹取，不补满）。
     *
     * <p>{@link #setPeachBonusSuppressed(Player, boolean)} 在解除屏蔽时会把存活玩家补满，
     * 这对战斗结束是可接受的；但在退出/关服保存前调用的话，会把"满血"当成真实血量写库，
     * 导致切服后非蟠桃造成的真实掉血丢失。退出保存应改用本方法。</p>
     */
    public static void restoreSuppressedBonusKeepingHealth(Player player) {
        if (player == null) return;
        if (!org.bukkit.Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("蟠桃血量屏蔽 API 必须在主线程调用");
        }
        UUID uuid = player.getUniqueId();
        Double bonus = suppressedBonuses.get(uuid);
        if (bonus == null) {
            return; // 未被屏蔽，无需处理
        }
        org.bukkit.attribute.AttributeInstance attr =
            player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return;
        double health = player.getHealth();
        HealthModifierUtil.applyPeachBonus(attr, isWorldBlocked(player) ? 0 : bonus);
        suppressedBonuses.remove(uuid);
        // 恢复加成后上限只会变大，因此这里不会丢失真实血量
        if (!player.isDead() && health > 0) player.setHealth(Math.min(health, attr.getValue()));
        else if (health > attr.getValue()) player.setHealth(attr.getValue());
        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin != null) plugin.updateHealthScale(player);
    }

    public static boolean isPeachBonusSuppressed(UUID uuid) {
        return uuid != null && suppressedBonuses.containsKey(uuid);
    }

    /** Every internal modifier write passes here, including delayed world/login/admin callbacks. */
    static double effectivePeachBonus(Player player, double bonus) {
        if (suppressedBonuses.replace(player.getUniqueId(), bonus) != null) return 0;
        return bonus;
    }

    /**
     * 持久化的蟠桃加成。
     *
     * <p>屏蔽期间 modifier 会被暂时移除（对外表现为 0），但真实值仍保存在内存里。
     * 跨服快照 / 占位符等需要“真实加成”的场景必须走这里，
     * 否则会把战斗中玩家的加成误写成 0。</p>
     */
    public static double persistentPeachBonus(Player player) {
        if (player == null) {
            return 0.0;
        }
        UUID uuid = player.getUniqueId();
        Double suppressed = suppressedBonuses.get(uuid);
        if (suppressed != null) {
            return suppressed;
        }
        // 屏蔽世界：modifier 被清空，回退到进入前缓存的真实加成（数据库仍是最终权威）
        Double blocked = blockedWorldBonuses.get(uuid);
        if (blocked != null && isWorldBlocked(player)) {
            return blocked;
        }
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attr == null ? 0.0 : HealthModifierUtil.getPeachBonus(attr);
    }

    /**
     * 进入屏蔽世界、清空 modifier 之前调用，记录真实加成。
     */
    public static void cacheBlockedWorldBonus(Player player) {
        if (player == null) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) {
            return;
        }
        blockedWorldBonuses.put(player.getUniqueId(), HealthModifierUtil.getPeachBonus(attr));
    }

    /** 离开屏蔽世界 / 退出后清除缓存，避免拿旧值回退。 */
    public static void clearBlockedWorldBonus(UUID uuid) {
        if (uuid != null) {
            blockedWorldBonuses.remove(uuid);
        }
    }

    private static boolean isWorldBlocked(Player player) {
        LuckyPeaches plugin = LuckyPeaches.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("world_integration.enabled", true)
            && plugin.getConfig().getStringList("world_integration.disabled_worlds").contains(player.getWorld().getName());
    }

    /**
     * 批量标记战斗状态
     */
    public static void setPlayersInBattle(Collection<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        players.forEach(PeachIntegrationAPI::setPlayerInBattle);
    }

    /**
     * 恢复指定玩家的蟠桃血量加成
     * 移除战斗标记，从数据库重新加载并同步 modifier（仅在值变化时更新）
     */
    public static void setPlayerNotInBattle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        playersInBattle.remove(playerId);

        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin == null || plugin.getDatabaseManager() == null) {
            return;
        }

        plugin.runAsync(() -> {
            double peachBonus = plugin.getDatabaseManager().loadCompletePlayerData(playerId).getPeachBonus();

            long delayTicks = plugin.getConfig().getLong("world_integration.peach_restore_delay_ticks", 0L);
            plugin.runSyncLater(() -> finishBattleRestore(player, peachBonus), delayTicks);
        });
    }

    /** Main-thread completion: heal even when the cached modifier already matches the database. */
    static void finishBattleRestore(Player player, double peachBonus) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || isPlayerInBattle(uuid) || isPeachBonusSuppressed(uuid) || isWorldBlocked(player)) return;
        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return;
        if (Math.abs(HealthModifierUtil.getPeachBonus(attr) - peachBonus) > 0.001) {
            HealthModifierUtil.applyPeachBonus(player, attr, peachBonus);
            LuckyPeaches plugin = LuckyPeaches.getInstance();
            if (plugin != null) plugin.updateHealthScale(player);
        }
        // Never revive a dead player through setHealth; respawn/duel recovery owns that lifecycle.
        if (!player.isDead() && player.getHealth() > 0) player.setHealth(attr.getValue());
    }

    /**
     * 批量恢复蟠桃加成
     */
    public static void setPlayersNotInBattle(Collection<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        players.forEach(PeachIntegrationAPI::setPlayerNotInBattle);
    }

    /**
     * 检查玩家是否处于战斗状态
     */
    public static boolean isPlayerInBattle(UUID playerUuid) {
        return playersInBattle.contains(playerUuid);
    }

    /**
     * 清理玩家战斗状态（玩家下线时调用）
     */
    public static void clearBattleStatus(UUID playerUuid) {
        playersInBattle.remove(playerUuid);
    }

    /**
     * 清空所有战斗状态（插件卸载时调用，避免静态集合跨重载残留）
     */
    public static void clearAllBattleStatus() {
        playersInBattle.clear();
        suppressedBonuses.clear();
        blockedWorldBonuses.clear();
    }

    /**
     * 清理玩家身上所有非蟠桃插件的血量加成 modifier
     * 注意：此方法会移除除蟠桃插件以外的所有 AttributeModifier（包括装备、药水等），请谨慎使用
     */
    public static void clearNonPeachModifiers(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin == null) {
            return;
        }

        plugin.runSync(() -> {
            org.bukkit.attribute.AttributeInstance attr =
                player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr == null) {
                return;
            }
            java.util.List<org.bukkit.attribute.AttributeModifier> toRemove = new java.util.ArrayList<>();
            for (org.bukkit.attribute.AttributeModifier mod : new java.util.ArrayList<>(attr.getModifiers())) {
                if (!HealthModifierUtil.PEACH_MODIFIER_UUID.equals(mod.getUniqueId())) {
                    toRemove.add(mod);
                }
            }
            toRemove.forEach(attr::removeModifier);
            plugin.updateHealthScale(player);
        });
    }

    /**
     * 批量清理非蟠桃插件的血量加成
     */
    public static void clearNonPeachModifiers(Collection<Player> players) {
        if (players == null || players.isEmpty()) {
            return;
        }
        players.forEach(PeachIntegrationAPI::clearNonPeachModifiers);
    }

    public static LuckyPeaches getPluginInstance() {
        return LuckyPeaches.getInstance();
    }
}
