package com.luckypeaches;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

            peaches.put(key, new PeachConfig(key, displayName, material, lore, healthBonus, chance, cmd));
        }
    }

    public ItemStack createPeachItem(String id, int amount) {
        PeachConfig config = peaches.get(id);
        if (config == null) return null;

        ItemStack item = new ItemStack(config.material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(config.displayName);
            meta.setLore(config.lore);
            if (config.customModelData > 0) {
                meta.setCustomModelData(config.customModelData);
            }
            // 添加 NBT 标记
            meta.getPersistentDataContainer().set(peachKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);
        }
        return item;
    }

    public PeachConfig getPeachFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        
        String id = meta.getPersistentDataContainer().get(peachKey, PersistentDataType.STRING);
        return id != null ? peaches.get(id) : null;
    }

    public List<String> getPeachIds() {
        return new ArrayList<>(peaches.keySet());
    }

    public static class PeachConfig {
        public String id, displayName;
        public Material material;
        public List<String> lore;
        public double healthBonus, chance;
        public int customModelData;

        public PeachConfig(String id, String displayName, Material material, List<String> lore, double healthBonus, double chance, int cmd) {
            this.id = id;
            this.displayName = displayName;
            this.material = material;
            this.lore = lore;
            this.healthBonus = healthBonus;
            this.chance = chance;
            this.customModelData = cmd;
        }
    }
}
