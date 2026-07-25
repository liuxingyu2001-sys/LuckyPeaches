package com.luckypeaches;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
        if (plugin == null) {
            return;
        }

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
            double peachBonus = healthData.getPeachBonus();

            long delayTicks = plugin.getConfig().getLong("world_integration.peach_restore_delay_ticks", 0L);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                if (attr == null) return;

                // 读取当前 modifier 值
                double currentModifier = 0;
                for (org.bukkit.attribute.AttributeModifier mod : attr.getModifiers()) {
                    if (mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID)) {
                        currentModifier = mod.getAmount();
                        break;
                    }
                }

                // 仅在值变化时更新，避免不必要的视觉波动
                if (Math.abs(currentModifier - peachBonus) > 0.001) {
                    attr.getModifiers().stream()
                        .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                        .forEach(attr::removeModifier);

                    if (peachBonus > 0) {
                        org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                            PeachListener.PEACH_MODIFIER_UUID,
                            "LuckyPeaches",
                            peachBonus,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                        );
                        attr.addModifier(modifier);
                    }
                    plugin.updateHealthScale(player);
                    player.setHealth(Math.min(player.getHealth(), attr.getValue()));
                }
            }, delayTicks);
        });
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

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            if (attr != null) {
                attr.getModifiers().stream()
                    .filter(mod -> !mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                    .forEach(attr::removeModifier);
                plugin.updateHealthScale(player);
            }
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
