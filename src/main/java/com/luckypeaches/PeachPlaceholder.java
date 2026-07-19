package com.luckypeaches;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class PeachPlaceholder extends PlaceholderExpansion {

    private final LuckyPeaches plugin;
    private List<DatabaseManager.PlayerRankData> cachedTopPlayers = Collections.emptyList();
    private long cacheTime = 0;
    private static final long CACHE_TTL_MS = 1000; // 1秒缓存

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

    private static final int MAX_TOP_RANK = 10;

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();
        DatabaseManager db = plugin.getDatabaseManager();
        String lower = params.toLowerCase();

        // %luckypeach_top_player_<1-10>% - 排行榜第N名玩家名
        if (lower.startsWith("top_player_")) {
            int rank = parseRank(lower.substring("top_player_".length()));
            if (rank > 0 && rank <= MAX_TOP_RANK) {
                return getTopPlayerName(db, rank);
            }
            return "---";
        }

        // %luckypeach_top_bonus_<1-10>% - 排行榜第N名蟠桃加成值
        if (lower.startsWith("top_bonus_")) {
            int rank = parseRank(lower.substring("top_bonus_".length()));
            if (rank > 0 && rank <= MAX_TOP_RANK) {
                return getTopPlayerBonus(db, rank);
            }
            return "0.0";
        }

        // %luckypeach_top_name_<1-10>% - 排行榜第N名玩家名 (alias)
        if (lower.startsWith("top_name_")) {
            int rank = parseRank(lower.substring("top_name_".length()));
            if (rank > 0 && rank <= MAX_TOP_RANK) {
                return getTopPlayerName(db, rank);
            }
            return "---";
        }

        switch (lower) {
            case "peach_bonus":
                return formatDouble(db.loadPlayerData(uuid));

            case "peach_bonus_raw":
                return String.valueOf(db.loadPlayerData(uuid));

            case "total_health": {
                var attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                return attr != null ? formatDouble(attr.getValue()) : "20.0";
            }

            case "current_health":
                return formatDouble(player.getHealth());

            case "base_health": {
                var attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                return attr != null ? formatDouble(attr.getBaseValue()) : "20.0";
            }

            case "peach_rank":
                return "#" + db.getPlayerRank(uuid);

            case "peach_rank_ordinal":
                return "第" + db.getPlayerRank(uuid) + "名";

            case "peach_rank_percentage":
                return formatRankPercentage(db.getPlayerRank(uuid), db.getTotalPlayersWithPeachBonus());

            case "peach_count":
                return String.valueOf(db.getTotalPlayersWithPeachBonus());

            case "health_difference":
                return formatDouble(getMaxHealth(player) - player.getHealth());

            case "is_in_disabled_world":
                return String.valueOf(PeachListener.isPlayerInDisabledWorld(uuid));

            case "health_scale":
                return String.valueOf(plugin.getConfig().getDouble("settings.health_scaling.scale", 20.0));

            case "max_health_limit":
                return formatDouble(getMaxHealthLimit(player));

            case "peach_bonus_percentage": {
                double peachBonus = db.loadPlayerData(uuid);
                double totalHealth = getMaxHealth(player);
                if (totalHealth <= 0) return "0%";
                return String.format("%.1f%%", (peachBonus / totalHealth) * 100);
            }

            default:
                return null;
        }
    }

    private int parseRank(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private double getMaxHealth(Player player) {
        var attr = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
        return attr != null ? attr.getValue() : 20.0;
    }

    private double getMaxHealthLimit(Player player) {
        double baseLimit = plugin.getConfig().getDouble("settings.max_health_limit", 100.0);
        var vipSection = plugin.getConfig().getConfigurationSection("settings.vip_health_limits");
        if (vipSection != null) {
            for (String key : vipSection.getKeys(false)) {
                if (player.hasPermission("luckypeaches.maxhealth." + key)) {
                    double vipLimit = vipSection.getDouble(key);
                    if (vipLimit > baseLimit) {
                        baseLimit = vipLimit;
                    }
                }
            }
        }
        return baseLimit;
    }

    private String formatDouble(double value) {
        return String.format("%.1f", value);
    }

    private String formatRankPercentage(int rank, int total) {
        if (total <= 0) return "N/A";
        double percentage = ((double) rank / total) * 100;
        return String.format("%.1f%%", percentage);
    }

    private synchronized List<DatabaseManager.PlayerRankData> getTopPlayersCached(DatabaseManager db) {
        long now = System.currentTimeMillis();
        if (now - cacheTime > CACHE_TTL_MS) {
            cachedTopPlayers = db.getTopPlayers(MAX_TOP_RANK);
            cacheTime = now;
        }
        return cachedTopPlayers;
    }

    private String getTopPlayerName(DatabaseManager db, int rank) {
        List<DatabaseManager.PlayerRankData> topPlayers = getTopPlayersCached(db);
        if (topPlayers.size() >= rank) {
            return topPlayers.get(rank - 1).getUsername();
        }
        return "---";
    }

    private String getTopPlayerBonus(DatabaseManager db, int rank) {
        List<DatabaseManager.PlayerRankData> topPlayers = getTopPlayersCached(db);
        if (topPlayers.size() >= rank) {
            return formatDouble(topPlayers.get(rank - 1).getPeachBonus());
        }
        return "0.0";
    }
}
