package com.luckypeaches;

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
     * 临时关闭指定玩家的蟠桃血量加成
     * 仅标记为战斗状态，不移除 modifier，不产生视觉变化
     */
    public static void setPlayerInBattle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        playersInBattle.add(player.getUniqueId());
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
            plugin.runSyncLater(() -> {
                if (!player.isOnline()) return;
                org.bukkit.attribute.AttributeInstance attr =
                    player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                if (attr == null) return;

                // 仅在值变化时更新，避免不必要的视觉波动
                if (Math.abs(HealthModifierUtil.getPeachBonus(attr) - peachBonus) > 0.001) {
                    HealthModifierUtil.applyPeachBonus(attr, peachBonus);
                    plugin.updateHealthScale(player);
                    player.setHealth(Math.min(player.getHealth(), attr.getValue()));
                }
            }, delayTicks);
        });
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
