package com.luckypeaches;

import java.util.*;
import java.util.stream.Collectors;

import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class PeachManager {
    private final Map<String, PeachConfig> peaches = new HashMap<>();
    private final NamespacedKey peachKey;

    public PeachManager() {
        this.peachKey = new NamespacedKey(LuckyPeaches.getInstance(), "peach_id");
    }

    public void loadPeaches() {
        peaches.clear();
        ConfigurationSection section = LuckyPeaches.getInstance().getConfig().getConfigurationSection("peaches");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String displayName = ChatColor.translateAlternateColorCodes('&', section.getString(key + ".display_name", key));
            String materialStr = section.getString(key + ".material", "APPLE");
            Material material;
            try {
                material = Material.valueOf(materialStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                material = Material.APPLE;
            }

            List<String> lore = section.getStringList(key + ".lore").stream()
                    .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                    .collect(Collectors.toList());
            double healthBonus = section.getDouble(key + ".health_bonus", 0.0);
            double chance = section.getDouble(key + ".chance", 1.0);
            int cmd = section.getInt(key + ".custom_model_data", 0);
            String ceModel = section.getString(key + ".craftengine_model", "");

            peaches.put(key, new PeachConfig(key, displayName, material, lore, healthBonus, chance, cmd, ceModel));
        }
    }

    public ItemStack createPeachItem(String id, int amount) {
        PeachConfig config = peaches.get(id);
        if (config == null) return null;

        ItemStack item;
        if (!config.ceModel.isEmpty() && Bukkit.getPluginManager().getPlugin("CraftEngine") != null) {
            try {
                item = CraftEngineItems.byId(config.ceModel).buildBukkitItem();
                item.setAmount(amount);
            } catch (Exception e) {
                LuckyPeaches.getInstance().getLogger().warning("CE模型 '" + config.ceModel + "' 失败，降级: " + e.getMessage());
                item = null;
            }
        } else {
            item = null;
        }

        // CE 不可用或失败 → 原版创建
        if (item == null) {
            item = new ItemStack(config.material, amount);
        }

        // 写入 PDC 标识
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // CE 模式下不覆盖显示名/lore/CMD，避免破坏模型渲染
            if (config.ceModel.isEmpty()) {
                meta.setDisplayName(config.displayName);
                meta.setLore(config.lore);
                if (config.customModelData > 0) meta.setCustomModelData(config.customModelData);
            }
            meta.getPersistentDataContainer().set(peachKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);

            // 验证写入
            if (LuckyPeaches.getInstance().isDebug()) {
                String verify = item.getItemMeta().getPersistentDataContainer().get(peachKey, PersistentDataType.STRING);
                LuckyPeaches.getInstance().getLogger().info("[PeachManager] 创建 ID=" + id + " CE=" + config.ceModel + " verify=" + verify);
            }
        }
        return item;
    }

    public PeachConfig getPeachFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(peachKey, PersistentDataType.STRING);
        return id != null ? peaches.get(id) : null;
    }

    public List<String> getPeachIds() {
        return new ArrayList<>(peaches.keySet());
    }

    public static class PeachConfig {
        public final String id, displayName, ceModel;
        public final Material material;
        public final List<String> lore;
        public final double healthBonus, chance;
        public final int customModelData;

        public PeachConfig(String id, String displayName, Material material, List<String> lore,
                          double healthBonus, double chance, int cmd, String ceModel) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.lore = lore;
            this.healthBonus = healthBonus;
            this.chance = chance;
            this.customModelData = cmd;
            this.ceModel = ceModel != null ? ceModel : "";
        }
    }
}
