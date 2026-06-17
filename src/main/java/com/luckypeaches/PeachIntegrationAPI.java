package com.luckypeaches;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public class PeachIntegrationAPI {
    
    private static final UUID PEACH_MODIFIER_UUID = UUID.nameUUIDFromBytes("LuckyPeaches".getBytes());
    
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
            // 移除蟠桃 modifier
            attr.getModifiers().stream()
                .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                .forEach(attr::removeModifier);
            // 重置基础血量为 20
            attr.setBaseValue(20.0);
            plugin.updateHealthScale(player);
            player.setHealth(20.0);
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
                    org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                    if (attr != null) {
                        attr.getModifiers().stream()
                            .filter(mod -> mod.getUniqueId().equals(PEACH_MODIFIER_UUID))
                            .forEach(attr::removeModifier);
                        
                        // 重置基础血量为 20，再加蟠桃 modifier
                        attr.setBaseValue(20.0);
                        
                        org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(
                            PEACH_MODIFIER_UUID,
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
                // 支持UUID和字符串ID两种格式
                attr.getModifiers().stream()
                    .filter(mod -> {
                        // 检查UUID格式
                        if (mod.getUniqueId() != null) {
                            return !mod.getUniqueId().equals(PEACH_MODIFIER_UUID);
                        }
                        
                        // 检查字符串ID格式 - 只保留确切匹配LuckyPeaches的名称
                        String name = mod.getName();
                        if (name != null) {
                            // 只保留LuckyPeaches相关的modifier
                            return !name.equalsIgnoreCase("LuckyPeaches") && 
                                   !name.equalsIgnoreCase("luckypeaches") &&
                                   !name.equalsIgnoreCase("peach") &&
                                   !name.equalsIgnoreCase("Peach");
                        }
                        
                        return true; // 如果没有ID和名称，移除
                    })
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
