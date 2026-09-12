package com.luckypeaches;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final LuckyPeaches plugin;
    /** LinkedHashMap 保证遍历/tab 补全顺序与配置文件一致 */
    private final Map<String, PeachConfig> peaches = new LinkedHashMap<>();
    private final NamespacedKey peachKey;

    public PeachManager(LuckyPeaches plugin) {
        this.plugin = plugin;
        this.peachKey = new NamespacedKey(plugin, "peach_id");
    }

    public void loadPeaches() {
        peaches.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("peaches");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String displayName = ChatColor.translateAlternateColorCodes('&', section.getString(key + ".display_name", key));
            String materialStr = section.getString(key + ".material", "APPLE");
            Material material;
            try {
                material = Material.valueOf(materialStr.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("蟠桃 '" + key + "' 的 material 无效: " + materialStr + "，已回退为 APPLE");
                material = Material.APPLE;
            }

            List<String> lore = section.getStringList(key + ".lore").stream()
                    .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                    .collect(Collectors.toList());

            // 概率与加成做范围校验，避免配置写错导致永远吃不出 / 一次加满
            double healthBonus = Math.max(0.0, section.getDouble(key + ".health_bonus", 0.0));
            double chance = section.getDouble(key + ".chance", 1.0);
            if (chance < 0) {
                chance = 0;
            } else if (chance > 1) {
                chance = 1;
            }
            int cmd = section.getInt(key + ".custom_model_data", 0);
            String ceModel = section.getString(key + ".craftengine_model", "");

            peaches.put(key, new PeachConfig(key, displayName, material, lore, healthBonus, chance, cmd, ceModel));
        }
        plugin.getLogger().info("已加载 " + peaches.size() + " 种蟠桃配置");
    }

    public ItemStack createPeachItem(String id, int amount) {
        PeachConfig config = peaches.get(id);
        if (config == null) return null;

        int safeAmount = Math.max(1, Math.min(amount, MAX_GIVE_AMOUNT));

        ItemStack item = null;
        if (!config.ceModel.isEmpty() && Bukkit.getPluginManager().getPlugin("CraftEngine") != null) {
            try {
                net.momirealms.craftengine.bukkit.item.BukkitItemDefinition definition = CraftEngineItems.byId(config.ceModel);
                if (definition != null) {
                    item = definition.buildBukkitItem();
                    if (item != null) {
                        item.setAmount(safeAmount);
                    }
                }
            } catch (Exception | LinkageError e) {
                // CraftEngine 未安装/版本不匹配时抛 LinkageError，这里一并兜底降级
                plugin.getLogger().warning("CE模型 '" + config.ceModel + "' 失败，降级: " + e);
                item = null;
            }
        }

        // CE 不可用或失败 → 原版创建（降级时必须补上显示名/lore/CMD，否则玩家拿到一个"无名"苹果）
        boolean vanillaFallback = item == null;
        if (vanillaFallback) {
            item = new ItemStack(config.material, safeAmount);
        }

        // 写入 PDC 标识
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            // CE 渲染成功时不覆盖显示名/lore/CMD，避免破坏模型渲染
            if (vanillaFallback) {
                meta.setDisplayName(config.displayName);
                meta.setLore(config.lore);
                if (config.customModelData > 0) meta.setCustomModelData(config.customModelData);
            }
            meta.getPersistentDataContainer().set(peachKey, PersistentDataType.STRING, id);
            item.setItemMeta(meta);

            // 验证写入
            if (plugin.isDebug()) {
                String verify = item.getItemMeta().getPersistentDataContainer().get(peachKey, PersistentDataType.STRING);
                plugin.getLogger().info("[PeachManager] 创建 ID=" + id + " CE=" + config.ceModel + " verify=" + verify);
            }
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
        return new java.util.ArrayList<>(peaches.keySet());
    }

    /** 单次发放数量上限，防止填超大数字造成异常物品 */
    public static final int MAX_GIVE_AMOUNT = 2304;

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
