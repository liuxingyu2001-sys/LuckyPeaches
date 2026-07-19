# LuckyPeaches

Minecraft 幸运蟠桃插件 — 食用蟠桃可永久提升最大生命值，支持多世界隔离、死亡惩罚、血量持久化。

## 功能

- **生命值提升**：右键食用蟠桃道具，概率触发永久增加最大生命值
- **多种蟠桃**：可配置多种蟠桃（不同材质、概率、加成数值、CustomModelData）
- **可配置上限**：全局最大生命值上限 + VIP 权限分组上限
- **死亡惩罚**：死亡时按比例扣除蟠桃加成，支持权限分组、冷却时间、阈值保护
- **世界隔离**：指定世界屏蔽蟠桃加成（进入即移除、离开即恢复）
- **世界最大生命值**：按世界设置基础最大生命值（跨世界自动切换）
- **血量缩放**：防止血条刷屏，客户端最多显示可配置的心数
- **血量持久化**：跨 Session 保存血量，基于 SQLite + 自动备份
- **战斗集成 API**：供其他插件临时关闭/恢复蟠桃加成（`PeachIntegrationAPI`）
- **粒子 & 音效**：成功/失败均有可配置的粒子效果与音效反馈

## 命令

所有命令需要权限 `luckypeach.admin`，主指令别名：`/lp`、`/luckyp`、`/luckypeach`。

### 基础命令

| 命令 | 说明 |
|------|------|
| `/lp help` | 查看帮助 |
| `/lp reload` | 重载配置、蟠桃列表和消息文件 |
| `/lp reload license` | 重新验证授权 |

### 玩家管理

| 命令 | 说明 |
|------|------|
| `/lp gethealth <玩家>` | 查看玩家当前蟠桃加成 |
| `/lp sethealth <玩家> <数值>` | 设置玩家蟠桃加成 |
| `/lp give <玩家> <蟠桃ID> [数量]` | 给予玩家指定蟠桃 |

### 备份管理

| 命令 | 说明 |
|------|------|
| `/lp backup now` | 立即备份数据库 |
| `/lp backup list` | 查看备份列表 |
| `/lp backup enable` | 启用自动备份 |
| `/lp backup disable` | 禁用自动备份 |

### 世界管理

| 命令 | 说明 |
|------|------|
| `/lp world add <世界>` | 将世界加入屏蔽列表 |
| `/lp world remove <世界>` | 从屏蔽列表移除世界 |
| `/lp world list` | 查看屏蔽世界列表 |
| `/lp world setmax <世界> <数值>` | 设置世界最大生命值 |
| `/lp world getmax <世界>` | 查看世界最大生命值 |
| `/lp world listmax` | 列出所有世界的最大生命值 |
| `/lp world removemax <世界>` | 移除世界最大生命值限制 |

## 权限

| 权限 | 说明 |
|------|------|
| `luckypeach.admin` | 管理员指令权限 |
| `luckypeaches.maxhealth.<key>` | VIP 生命值上限分组（对应 config 中 `vip_health_limits`） |
| `luckypeaches.deathpenalty.<key>` | 死亡惩罚分组（对应 config 中 `penalty_groups`，拥有多个时取最优） |

## 依赖

| 插件 | 必需 | 说明 |
|------|------|------|
| Spigot/Paper 1.13+ | ✅ | 运行环境 |
| PlaceholderAPI | ❌ 可选 | 占位符扩展 |
| CraftEngine | ❌ 可选 | 自定义物品模型 |

## PlaceholderAPI 占位符

安装 PlaceholderAPI 后自动注册，无需额外配置。前缀：`luckypeach`。

### 玩家数据

| 占位符 | 说明 |
|--------|------|
| `%luckypeach_peach_bonus%` | 蟠桃加成生命值（保留1位小数） |
| `%luckypeach_peach_bonus_raw%` | 蟠桃加成生命值（原始值） |
| `%luckypeach_total_health%` | 总最大生命值（基础 + 蟠桃 + 其他） |
| `%luckypeach_current_health%` | 当前血量 |
| `%luckypeach_base_health%` | 基础最大生命值 |
| `%luckypeach_health_difference%` | 最大血量与当前血量的差值 |
| `%luckypeach_max_health_limit%` | 当前玩家的最大血量上限 |
| `%luckypeach_peach_bonus_percentage%` | 蟠桃加成占总血量百分比 |
| `%luckypeach_is_in_disabled_world%` | 是否在屏蔽世界中（true/false） |
| `%luckypeach_health_scale%` | 血条缩放比例 |

### 排行榜

| 占位符 | 说明 |
|--------|------|
| `%luckypeach_peach_rank%` | 蟠桃排行榜排名（#1, #2, ...） |
| `%luckypeach_peach_rank_ordinal%` | 排名序数（第1名, 第2名, ...） |
| `%luckypeach_peach_rank_percentage%` | 排名百分比（top 10%） |
| `%luckypeach_peach_count%` | 拥有蟠桃加成的玩家总数 |
| `%luckypeach_top_player_<1-10>%` | 排行榜第N名玩家名 |
| `%luckypeach_top_bonus_<1-10>%` | 排行榜第N名蟠桃加成值 |
| `%luckypeach_top_name_<1-10>%` | 排行榜第N名玩家名（别名） |

排行榜占位符支持 1-10 名，例如 `%luckypeach_top_player_3%` 显示第3名玩家名，`%luckypeach_top_bonus_5%` 显示第5名的蟠桃加成值。

## 配置

主配置文件 `config.yml` 包含以下模块：

- **`settings`** — 调试模式、最大生命值上限、VIP 分组、死亡惩罚、音效、粒子、血量缩放、数据库备份
- **`world_integration`** — 世界屏蔽（进入/离开行为、回满血设置）
- **`world_max_health`** — 按世界的最大生命值
- **`peaches`** — 蟠桃定义（`display_name`、`material`、`lore`、`health_bonus`、`chance`、`custom_model_data`）

## 消息配置

`messages.yml` 可自定义所有插件消息，支持颜色代码（`&`）。

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `show_prefix` | 是否显示 `[幸运蟠桃]` 前缀 | `true` |
| `prefix` | 前缀内容 | `&e[幸运蟠桃] ` |
| `success` | 吃桃成功消息（占位符: `%bonus%` `%peach_health%`） | 蟠桃血量 +X / 当前蟠桃血量: Y |
| `fail` | 吃桃失败消息（占位符: `%peach_health%`） | 什么也没发生... |
| `max_health_reached` | 已达上限消息 | 生命值已达巅峰... |
| `death_penalty` | 死亡扣除消息 | 失去了 X 点蟠桃血量... |
| `death_cooldown` | 死亡冷却消息 | 刚复活不久... |
| `world_disabled` | 世界禁用消息 | 当前世界已屏蔽... |
| `world_enter` / `world_exit` | 进入/离开屏蔽世界消息 | — |
| 其他 | `give_success` `give_dropped` `get_health` 等 | — |

设为 `show_prefix: false` 即可关闭所有消息的 `[幸运蟠桃]` 前缀。

## API

供其他插件调用（调用时无视觉变化，不触发受伤/回血动画）：

```java
// 战斗开始时标记玩家（不移除 modifier，血条不变）
PeachIntegrationAPI.setPlayerInBattle(player);

// 战斗结束后移除标记（从数据库同步 modifier，仅值变化时更新）
PeachIntegrationAPI.setPlayerNotInBattle(player);

// 检查玩家是否处于战斗中（战斗中死亡不扣蟠桃血）
PeachIntegrationAPI.isPlayerInBattle(player.getUniqueId());

// 清理非本插件的血量 modifier（保留蟠桃相关）
PeachIntegrationAPI.clearNonPeachModifiers(player);
PeachIntegrationAPI.clearNonPeachModifiers(players); // 批量
```

详见 [API使用文档.md](API使用文档.md)

## 更新日志

### v2.3
- **API 零视觉变化**：`setPlayerInBattle` / `setPlayerNotInBattle` 改为标志位机制，不再移除/恢复 modifier，调用时无屏幕闪烁或受伤动画
- **战斗死亡豁免**：战斗中的玩家死亡不扣除蟠桃加成
- **线程安全修复**：`eatingPlayers` 和 `playersInDisabledWorld` 改用线程安全集合，修复并发访问导致的潜在 `ConcurrentModificationException`
- **异步备份**：自动备份任务改为异步执行，避免 `VACUUM INTO` 阻塞主线程
- **代码优化**：统一 `PEACH_MODIFIER_UUID` 常量定义，消除三处重复；简化 `clearNonPeachModifiers` 过滤逻辑；移除 `PeachPlaceholder` 冗余 `volatile` 声明
- **编译修复**：`pom.xml` 添加 `<fork>true</fork>` 解决 `maven-compiler-plugin:3.11.0` 的 `ConcurrentModificationException` 编译错误

### v2.2
- **血量优化**：登录时不再每次覆盖血量，优先信任 Minecraft player.dat 的恢复结果，仅数据库与本地不一致时才同步，避免登录受伤动画
- **吃桃体验**：吃桃成功后同步增加当前血量，满血吃完仍是满血，不再触发受伤动画
- **备份频率**：默认自动备份间隔从 24 小时改为 1 小时
