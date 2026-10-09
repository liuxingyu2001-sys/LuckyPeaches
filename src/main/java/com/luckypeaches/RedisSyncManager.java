package com.luckypeaches;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * 跨服血量快照（Redis）。
 *
 * <p>群组服切换服务器时，源服务器在退出 / 吃桃 / 死亡时把玩家的最新状态写入 Redis；
 * 目标服务器在 {@code AsyncPlayerPreLoginEvent}（异步线程）预取，并于
 * {@code PlayerJoinEvent}（主线程）一次性同步还原最大血量与当前血量。</p>
 *
 * <p>这样登录时不再经历"先按本地旧血量进入 → 再异步查库校准"的空窗，
 * 因此不会出现血条跳变 / 受伤动画。Redis 只是加速层：未开启或连接失败时
 * 完全退化为原来的数据库同步路径，不影响任何既有功能。</p>
 *
 * <p>写入的是"真实持久化加成"（{@link PeachIntegrationAPI#persistentPeachBonus(Player)}），
 * 因此即使玩家正处于战斗屏蔽状态，快照里的加成也不会被误写成 0。</p>
 */
public class RedisSyncManager {

    /** 快照键的哈希字段名 */
    private static final String FIELD_BONUS = "peach_bonus";
    private static final String FIELD_HEALTH = "health";
    private static final String FIELD_MAX_HEALTH = "max_health";
    private static final String FIELD_USERNAME = "username";
    private static final String FIELD_WORLD = "world";
    private static final String FIELD_TIMESTAMP = "ts";

    /** 玩家跨服状态快照 */
    public static final class Snapshot {
        public final double peachBonus;
        public final double currentHealth;
        public final double maxHealth;
        public final long timestamp;

        public Snapshot(double peachBonus, double currentHealth, double maxHealth, long timestamp) {
            this.peachBonus = peachBonus;
            this.currentHealth = currentHealth;
            this.maxHealth = maxHealth;
            this.timestamp = timestamp;
        }
    }

    private final LuckyPeaches plugin;
    private JedisPool pool;
    private boolean enabled;
    private String keyPrefix = "luckypeaches:";
    private int ttlSeconds = 600;
    private org.bukkit.scheduler.BukkitTask flushTask;

    /** 连续失败后的熔断：避免 Redis 宕机时每次登录/操作都阻塞到超时 */
    private static final long SUSPEND_MS = 30_000L;
    private static final int FAILURES_BEFORE_SUSPEND = 3;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
        new java.util.concurrent.atomic.AtomicInteger();
    private volatile long suspendedUntilMs = 0L;

    public RedisSyncManager(LuckyPeaches plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled() {
        return enabled && System.currentTimeMillis() >= suspendedUntilMs;
    }

    /**
     * 按配置初始化连接。未启用 / 连接失败时返回 false 并自禁用，
     * 由调用方继续走数据库路径。
     */
    public boolean initialize() {
        if (!plugin.getConfig().getBoolean("redis.enabled", false)) {
            return false;
        }

        keyPrefix = plugin.getConfig().getString("redis.key_prefix", "luckypeaches:");
        ttlSeconds = plugin.getConfig().getInt("redis.ttl_seconds", 600);
        String host = plugin.getConfig().getString("redis.host", "localhost");
        int port = plugin.getConfig().getInt("redis.port", 6379);
        String password = plugin.getConfig().getString("redis.password", "");
        int database = plugin.getConfig().getInt("redis.database", 0);
        int timeout = Math.max(100, plugin.getConfig().getInt("redis.timeout_ms", 1000));

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(Math.max(2, plugin.getConfig().getInt("redis.max_total", 16)));
        poolConfig.setMaxIdle(Math.max(1, plugin.getConfig().getInt("redis.max_idle", 8)));
        poolConfig.setMinIdle(0);
        poolConfig.setTestWhileIdle(true);

        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(timeout)
            .socketTimeoutMillis(timeout)
            .database(database);
        if (password != null && !password.isEmpty()) {
            clientConfig.password(password);
        }

        try {
            this.pool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfig.build());
            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }
            this.enabled = true;
            plugin.getLogger().info("[RedisSync] 已连接 Redis " + host + ":" + port
                + "，跨服血量同步已启用（key 前缀: " + keyPrefix + "）");
            startFlushTask();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisSync] Redis 连接失败，跨服血量同步已禁用（回退数据库同步）: "
                + e.getMessage());
            closePool();
            enabled = false;
            return false;
        }
    }

    /**
     * 写入玩家当前状态。可在任意线程调用（主线程调用时通常 < 1ms）。
     */
    public void storePlayer(Player player) {
        if (!isEnabled() || player == null) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double bonus = PeachIntegrationAPI.persistentPeachBonus(player);
        double max = attr == null ? player.getHealth() : attr.getValue();
        double health = player.getHealth() > 0 ? player.getHealth() : max;
        store(player.getUniqueId(), player.getName(), bonus, health, max, player.getWorld().getName());
    }

    /**
     * 写入一条快照。写入失败只记日志，不影响主流程。
     */
    public void store(UUID uuid, String username, double peachBonus, double health, double maxHealth, String world) {
        if (!isEnabled() || pool == null || uuid == null) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            String key = key(uuid);
            Map<String, String> fields = new HashMap<>();
            fields.put(FIELD_BONUS, Double.toString(peachBonus));
            fields.put(FIELD_HEALTH, Double.toString(health));
            fields.put(FIELD_MAX_HEALTH, Double.toString(maxHealth));
            fields.put(FIELD_USERNAME, username == null ? "" : username);
            fields.put(FIELD_WORLD, world == null ? "" : world);
            fields.put(FIELD_TIMESTAMP, Long.toString(System.currentTimeMillis()));
            // 事务保证 hset + expire 一起生效：避免进程/连接中断后新 key 永不续期
            redis.clients.jedis.Transaction tx = jedis.multi();
            tx.hset(key, fields);
            if (ttlSeconds > 0) {
                tx.expire(key, ttlSeconds);
            }
            tx.exec();
            onSuccess();
        } catch (Exception e) {
            onFailure("写入快照", e);
        }
    }

    /**
     * 读取玩家快照；不存在、未启用或读取失败时返回 null。
     */
    public Snapshot fetch(UUID uuid) {
        if (!isEnabled() || pool == null || uuid == null) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> fields = jedis.hgetAll(key(uuid));
            if (fields == null || fields.isEmpty()) {
                return null;
            }
            String bonusRaw = fields.get(FIELD_BONUS);
            String healthRaw = fields.get(FIELD_HEALTH);
            if (bonusRaw == null || healthRaw == null) {
                return null;
            }
            double bonus = Double.parseDouble(bonusRaw);
            double health = Double.parseDouble(healthRaw);
            double max = parseDouble(fields.get(FIELD_MAX_HEALTH), health);
            long ts = (long) parseDouble(fields.get(FIELD_TIMESTAMP), 0.0);
            if (!Double.isFinite(bonus) || !Double.isFinite(health)) {
                return null;
            }
            onSuccess();
            return new Snapshot(Math.max(0.0, bonus), health, max, ts);
        } catch (Exception e) {
            onFailure("读取快照", e);
            return null;
        }
    }

    private void onSuccess() {
        consecutiveFailures.set(0);
    }

    /**
     * 失败计数 + 熔断。连续失败达到阈值后暂停 {@value #SUSPEND_MS} 毫秒，
     * 期间 {@link #isEnabled()} 返回 false，调用方直接走数据库路径，
     * 不会因为 Redis 宕机而把每次登录/操作都阻塞到超时。
     */
    private void onFailure(String action, Exception e) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURES_BEFORE_SUSPEND) {
            // 每次失败都续期：Redis 一直不恢复就保持熔断，不会反复阻塞调用方
            suspendedUntilMs = System.currentTimeMillis() + SUSPEND_MS;
            if (failures == FAILURES_BEFORE_SUSPEND) {
                plugin.getLogger().warning("[RedisSync] Redis 连续 " + failures + " 次失败，暂停跨服同步 "
                    + (SUSPEND_MS / 1000) + " 秒后自动重试（当前回退数据库同步）: " + e.getMessage());
            }
        } else {
            plugin.getLogger().warning("[RedisSync] " + action + "失败: " + e.getMessage());
        }
    }

    /** 周期兜底：定期把所有在线玩家的状态刷进 Redis，避免极端情况下快照过期 */
    private void startFlushTask() {
        long intervalSeconds = plugin.getConfig().getLong("redis.flush_interval_seconds", 60L);
        if (intervalSeconds <= 0) {
            return;
        }
        long periodTicks = intervalSeconds * 20L;
        flushTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            java.util.List<Object[]> batch = new java.util.ArrayList<>();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
                double bonus = PeachIntegrationAPI.persistentPeachBonus(player);
                double max = attr == null ? player.getHealth() : attr.getValue();
                double health = player.getHealth() > 0 ? player.getHealth() : max;
                batch.add(new Object[]{player.getUniqueId(), player.getName(), bonus, health, max,
                    player.getWorld().getName()});
            }
            if (batch.isEmpty()) {
                return;
            }
            plugin.runAsync(() -> {
                for (Object[] row : batch) {
                    store((UUID) row[0], (String) row[1], (Double) row[2], (Double) row[3],
                        (Double) row[4], (String) row[5]);
                }
            });
        }, periodTicks, periodTicks);
    }

    private String key(UUID uuid) {
        return keyPrefix + "state:" + uuid;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void close() {
        if (flushTask != null) {
            try {
                flushTask.cancel();
            } catch (RuntimeException ignored) {
                // 调度器已关闭
            }
            flushTask = null;
        }
        enabled = false;
        closePool();
    }

    private void closePool() {
        if (pool != null) {
            try {
                pool.close();
            } catch (RuntimeException ignored) {
                // 关闭期间的异常无须处理
            }
            pool = null;
        }
    }
}
