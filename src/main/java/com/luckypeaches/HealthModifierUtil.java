package com.luckypeaches;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.UUID;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;

/**
 * 生命值 AttributeModifier 统一操作工具。
 *
 * <p>插件里多处需要「查找某个 UUID 的 modifier 数值 / 移除旧的 / 添加新的」，
 * 之前这段逻辑在 LuckyPeaches、PeachListener、PeachCommand、PeachIntegrationAPI
 * 中重复了 10 次以上，容易出现漏判（例如留下 0 值 modifier）。
 * 这里统一收口，保证语义一致。</p>
 *
 * <p><b>警告：</b>下面两个 UUID 由固定字符串派生，改动会让线上玩家已有的 modifier 变成孤儿，
 * 不要修改字符串内容。</p>
 */
public final class HealthModifierUtil {

    /** 蟠桃加成 modifier UUID */
    public static final UUID PEACH_MODIFIER_UUID = UUID.nameUUIDFromBytes("LuckyPeaches".getBytes());
    /** 世界最大生命值 modifier UUID */
    public static final UUID WORLD_MAX_HEALTH_MODIFIER_UUID = UUID.nameUUIDFromBytes("LuckyPeachesWorldMax".getBytes());

    /** modifier 来源名称（用于展示） */
    public static final String PEACH_MODIFIER_NAME = "LuckyPeaches";
    public static final String WORLD_MAX_MODIFIER_NAME = "LuckyPeachesWorldMax";

    private HealthModifierUtil() {
    }

    // ========== 数值归一 ==========

    /**
     * 血量类数值统一保留 1 位小数（四舍五入）。
     *
     * <p>死亡惩罚按百分比扣减、以及浮点累加（如 0.1 + 0.2）都会算出
     * {@code 2621.75960666} 这种长小数；如果原样存库并套到
     * {@link AttributeModifier} 上，HUD / 占位符 / 其它读取属性的插件都会显示
     * 一串小数位。所有写入数据库、套用 modifier、读取显示的血量值都走这里，
     * 保证对玩家可见的蟠桃血量最多只有 1 位小数。</p>
     *
     * <p>用 {@link BigDecimal#valueOf(double)} 而不是 {@code Math.round(v * 10) / 10.0}：
     * 后者会受二进制误差影响（例如 0.15 可能被舍成 0.1）。</p>
     */
    public static double roundToOneDecimal(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 格式化显示用的 1 位小数文本。
     *
     * <p>固定 {@link java.util.Locale#ROOT}：部分语言环境（如德语）默认会把小数点
     * 格式化成逗号，统一成 {@code 2621.8} 这种写法。</p>
     */
    public static String formatOneDecimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", roundToOneDecimal(value));
    }

    /**
     * 读取指定 modifier 的数值。
     *
     * @return 不存在时返回 0
     */
    public static double getAmount(AttributeInstance attr, UUID modifierId) {
        if (attr == null || modifierId == null) {
            return 0.0;
        }
        for (AttributeModifier mod : attr.getModifiers()) {
            if (modifierId.equals(mod.getUniqueId())) {
                return mod.getAmount();
            }
        }
        return 0.0;
    }

    /**
     * 移除指定 modifier（先复制一份再删，避免边遍历边修改集合）
     */
    public static void remove(AttributeInstance attr, UUID modifierId) {
        if (attr == null || modifierId == null) {
            return;
        }
        for (AttributeModifier mod : new ArrayList<>(attr.getModifiers())) {
            if (modifierId.equals(mod.getUniqueId())) {
                attr.removeModifier(mod);
            }
        }
    }

    /**
     * 应用 modifier：先移除同 UUID 的旧值，再添加新值。
     *
     * <p>amount ≤ 0 时只移除不添加，避免留下 0 值 modifier 影响战斗/死亡判定。</p>
     */
    public static void apply(AttributeInstance attr, UUID modifierId, String name, double amount) {
        if (attr == null) {
            return;
        }
        remove(attr, modifierId);
        if (amount <= 0) {
            return;
        }
        attr.addModifier(new AttributeModifier(modifierId, name, amount,
            AttributeModifier.Operation.ADD_NUMBER));
    }

    // ========== 蟠桃加成 ==========

    public static double getPeachBonus(AttributeInstance attr) {
        // 读取也归一：旧版本写进 modifier 的长小数会在下一次套用前就被显示成 1 位小数
        return roundToOneDecimal(getAmount(attr, PEACH_MODIFIER_UUID));
    }

    public static void applyPeachBonus(AttributeInstance attr, double bonus) {
        apply(attr, PEACH_MODIFIER_UUID, PEACH_MODIFIER_NAME, roundToOneDecimal(bonus));
    }

    /** Player-aware writes honour temporary API suppression and remember the latest bonus. */
    public static void applyPeachBonus(org.bukkit.entity.Player player, AttributeInstance attr, double bonus) {
        applyPeachBonus(attr, PeachIntegrationAPI.effectivePeachBonus(player, bonus));
    }

    public static void clearPeachBonus(AttributeInstance attr) {
        remove(attr, PEACH_MODIFIER_UUID);
    }

    // ========== 世界最大生命值 ==========

    public static void applyWorldMaxBonus(AttributeInstance attr, double bonus) {
        apply(attr, WORLD_MAX_HEALTH_MODIFIER_UUID, WORLD_MAX_MODIFIER_NAME, bonus);
    }

    public static void clearWorldMaxBonus(AttributeInstance attr) {
        remove(attr, WORLD_MAX_HEALTH_MODIFIER_UUID);
    }
}
