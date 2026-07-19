package com.luckypeaches;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public class PeachIntegrationAPI {
    
    /**
     * 临时关闭指定玩家的蟠桃血量加成
     * 用于战斗时临时禁用蟠桃加成
     */
    public static void setPlayerInBattle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin == null) {
            return;
        }
        
        // 直接同步执行，避免延迟一 tick 导致调用方拿到过期状态
        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr != null) {
            // 仅移除蟠桃 modifier，不重置基础血量（避免覆盖其他插件的基础血量修改）
            attr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                .forEach(attr::removeModifier);
            plugin.updateHealthScale(player);
            player.setHealth(Math.min(player.getHealth(), attr.getValue()));
        }
    }
    
    /**
     * 恢复指定玩家的蟠桃血量加成
     * 用于战斗结束后恢复蟠桃加成
     */
    public static void setPlayerNotInBattle(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        
        LuckyPeaches plugin = LuckyPeaches.getInstance();
        if (plugin == null) {
            return;
        }
        
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            DatabaseManager.PlayerHealthData healthData = plugin.getDatabaseManager().loadCompletePlayerData(playerId);
            double peachBonus = healthData.getPeachBonus();
            
            if (peachBonus > 0) {
                long delayTicks = plugin.getConfig().getLong("world_integration.peach_restore_delay_ticks", 60L);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!player.isOnline()) return;
                    org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        attr.getModifiers().stream()
                            .filter(mod -> mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID))
                            .forEach(attr::removeModifier);
                        
                        // 不重置基础血量，避免覆盖其他插件的修改
                        org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                            PeachListener.PEACH_MODIFIER_UUID,
                            "LuckyPeaches",
                            peachBonus,
                            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER
                        );
                        attr.addModifier(modifier);
                        plugin.updateHealthScale(player);
                        player.setHealth(attr.getValue());
                    }
                }, delayTicks);
            }
        });
    }
    
    /**
     * 清理玩家身上所有非蟠桃插件的血量加成
     * 只保留装备和蟠桃插件的modifier
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
                // 移除所有非蟠桃插件的modifier
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
