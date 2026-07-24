package com.luckypeaches;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class PeachPlaceholder extends PlaceholderExpansion {

    private final LuckyPeaches plugin;

    public PeachPlaceholder(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "luckypeach";
    }

    @Override
    public String getAuthor() {
        var authors = plugin.getDescription().getAuthors();
        return authors.isEmpty() ? "unknown" : authors.get(0);
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        }

        String lower = params.toLowerCase();

        switch (lower) {
            case "peach_bonus":
                return String.format("%.1f", getPeachBonusFromModifier(player));

            case "peach_bonus_raw":
                return String.valueOf(getPeachBonusFromModifier(player));

            default:
                return null;
        }
    }

    /**
     * 从玩家属性 modifier 中读取蟠桃加成，避免主线程阻塞数据库查询
     */
    private double getPeachBonusFromModifier(Player player) {
        org.bukkit.attribute.AttributeInstance attr =
            player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        if (attr == null) return 0.0;

        for (org.bukkit.attribute.AttributeModifier mod : attr.getModifiers()) {
            if (mod.getUniqueId().equals(PeachListener.PEACH_MODIFIER_UUID)) {
                return mod.getAmount();
            }
        }
        return 0.0;
    }
}
