# LuckyPeaches

Minecraft 幸运蟠桃插件 — 食用蟠桃可永久提升最大生命值，支持多世界隔离、死亡惩罚、血量持久化、群组服共享配置。

## 功能

- **生命值提升**：右键食用蟠桃道具，概率触发永久增加最大生命值
- **多种蟠桃**：可配置多种蟠桃（不同材质、概率、加成数值、CustomModelData、CraftEngine 模型）
- **可配置上限**：全局最大生命值上限 + VIP 权限分组上限 + 每种蟠桃独立食用血量上限
- **登录血量归一**：登录时设置基础最大生命值（`base_max_health`，可配置）
- **死亡惩罚**：死亡时按比例扣除蟠桃加成，支持权限分组、冷却时间、阈值保护
- **世界隔离**：指定世界屏蔽蟠桃加成（进入即移除、离开即恢复）
- **世界最大生命值**：按世界设置基础最大生命值（跨世界自动切换）
- **血量缩放**：防止血条刷屏，客户端最多显示可配置的心数
- **双数据库**：SQLite（单机）/ MySQL（多服务器），支持游戏内热切换、自动迁移
- **多端血量同步**：登录时从数据库校验蟠桃加成与当前血量；开启 Redis 后切服更快且无血量跳变
- **共享配置目录**：群组服所有服务器读取同一份配置，修改后自动热重载
- **强制清理**：`/luckypeach clearhealth` 清理玩家非蟠桃血量加成（单人或全部）
- **自动备份**：SQLite 使用 VACUUM INTO，MySQL 导出 YML/JSON
- **战斗集成 API**：供其他插件临时关闭/恢复蟠桃加成（`PeachIntegrationAPI`）
- **粒子 & 音效**：成功/失败均有可配置的粒子效果与音效反馈
- **PlaceholderAPI**：内置占位符扩展

## 构建

```bash
mvn clean package
# 输出: target/Liu-LuckyPeaches-<版本>.jar
```

**部署：** JAR → Spigot/Paper 服务器的 `plugins/` 目录

> 建议使用 **Paper（含 Leaf/Purpur 等分支）**：SQLite/MySQL 驱动与 HikariCP 通过 `plugin.yml` 的
> `libraries` 自动下载，Spigot 不支持该字段，需要自行把驱动放进服务端 classpath。

## 命令

需要权限 `luckypeach.admin`，主指令 `/luckypeach`，别名 `/luckyp`；已移除 `/lp`，避免与 LuckPerms 冲突。

| 命令 | 说明 |
|------|------|
| `/luckypeach help` | 查看帮助 |
| `/luckypeach reload` | 重载配置、蟠桃列表和消息文件 |
| `/luckypeach gethealth <玩家>` | 查看玩家当前蟠桃加成 |
| `/luckypeach sethealth <玩家> <数值>` | 设置玩家蟠桃加成 |
| `/luckypeach clearhealth <玩家\|all>` | 清理非蟠桃血量加成（all = 所有在线玩家） |
| `/luckypeach give <玩家> <蟠桃ID> [数量]` | 给予玩家指定蟠桃 |
| `/luckypeach backup now\|list\|enable\|disable` | 数据库备份管理 |
| `/luckypeach world add\|remove\|list <世界>` | 管理屏蔽世界列表 |
| `/luckypeach world setmax\|getmax\|listmax\|removemax <世界> [数值]` | 管理世界最大生命值 |
| `/luckypeach db status\|mysql\|sqlite` | 查看/切换数据库类型（自动迁移数据） |
| `/luckypeach import <sqlite文件>` | 导入旧 SQLite 数据到 MySQL |

## 权限

| 权限 | 说明 |
|------|------|
| `luckypeach.admin` | 管理员指令 |
| `luckypeaches.maxhealth.<key>` | VIP 生命值上限分组（对应 `vip_health_limits`） |
| `luckypeaches.deathpenalty.<key>` | 死亡惩罚分组（对应 `penalty_groups`，拥有多个时取最优） |

## 依赖

| 插件/库 | 必需 | 说明 |
|---------|------|------|
| Spigot/Paper 1.13+ | ✅ | 运行环境（推荐 Paper 1.21） |
| MySQL Connector + HikariCP | ❌ | MySQL 模式需要，服务器通过 `libraries` 自动下载 |
| Jedis（Redis） | ❌ | 跨服血量同步需要，服务器通过 `libraries` 自动下载 |
| SparrowSync | ❌ | 可选：把跨服生命同步完全交给它（见下文“委托给 SparrowSync”） |
| PlaceholderAPI | ❌ | 占位符扩展（`softdepend`） |
| CraftEngine | ❌ | 自定义物品模型 |

## PlaceholderAPI 占位符

安装 PlaceholderAPI 后自动注册，前缀：`luckypeach`。

| 占位符 | 说明 |
|--------|------|
| `%luckypeach_peach_bonus%` | 蟠桃加成生命值（保留1位小数） |
| `%luckypeach_peach_bonus_raw%` | 蟠桃加成生命值（原始值） |

## 配置

### 共享配置目录（群组服）

所有服务器可通过共享目录（NFS 等）读取同一份配置，替代原来的 Proxy 同步：

```yaml
# config.yml 顶部
shared_config_dir: "/path/to/shared/LuckyPeaches"   # 留空 = 使用各服务器本地配置
config_poll_interval: 5                             # 配置变更检测间隔（秒），0 = 禁用
```

- 所有服务器读取同一份 `config.yml`、`messages.yml` 和 `peaches.yml`，修改后自动同步重载。蟠桃列表单独放在 `peaches.yml`。
- `config_poll_interval` 秒数 > 0 时，插件定时检查共享文件修改时间，检测到变更自动热重载（主配置、消息、蟠桃列表、在线玩家 modifier）
- 各服务器本地 `plugins/LuckyPeaches/config.yml` 仅需设置 `shared_config_dir` 作为指向

### 后端 `config.yml`

- **顶部** — `shared_config_dir`、`config_poll_interval`
- **`settings`** — 调试模式、`base_max_health`、`force_base_max_health`、`join_health_restore_delay_ticks`、`suppressed_restore_health`、数据库、最大生命值上限、VIP 分组、死亡惩罚、音效、粒子、血量缩放、自动备份
- **`world_integration`** — 世界屏蔽（进入/离开行为、回满血设置）
- **`world_max_health`** — 按世界的最大生命值
- **`peaches.yml`** — 蟠桃定义（`display_name`、`material`、`lore`、`health_bonus`、`max_consume_health`、`chance`、`custom_model_data`、`craftengine_model`）

设置 `craftengine_model` 时，新生成的蟠桃保留 CraftEngine 的模型数据，但名称和描述统一使用本插件的 `display_name` 和 `lore`；`lore: []` 会清除 CE 自带描述。`custom_model_data` 仅用于原版物品或 CE 不可用时的回退物品。

### 每种蟠桃的食用血量上限

蟠桃定义位于 `peaches.yml`：

```yaml
peaches:
  peach_1:
    # 保留该蟠桃的其它配置
    max_consume_health: 100.0
  peach_2:
    max_consume_health: 200.0
  peach_3:
    max_consume_health: 0.0
```

- 按**基础最大生命值 + 蟠桃加成**判断，不看受伤后的当前血量，也不计世界和其它插件的生命值 modifier；2 点血量 = 1 颗心。
- 设置为 `100` 时，超过 100 血不能吃，正好 100 血仍可吃；成功后正常增加该蟠桃的完整加成，因此这颗可能使血量超过 100。
- 被限制时不消耗道具，也不进行成功概率判定。
- `0` 或未配置表示没有该蟠桃的额外食用限制。全局和 VIP 上限仍然生效，VIP 权限不能绕过单种蟠桃的限制。
- 修改后执行 `/luckypeach reload`（或等待已启用的配置自动重载），已发放的蟠桃同样生效。
- 拒绝食用提示可在 `messages.yml` 的 `peach_health_limit_reached` 中修改，支持 `%peach%`、`%limit%`、`%health%` 和 `%peach_health%`。

### 数据库

默认使用 SQLite，支持热切换到 MySQL（适用于多服务器/群组服环境）。通过 `/luckypeach db` 命令在游戏内一键切换，无需重启服务器，数据自动迁移。

```yaml
settings:
  database:
    type: sqlite    # sqlite 或 mysql
    mysql:
      host: localhost
      port: 3306
      database: luckypeaches
      username: root
      password: ""
      table_prefix: "lp_"
      max_connections: 10
```

多端血量同步：使用 MySQL 时，玩家登录会从数据库校验蟠桃加成与当前血量，切换服务器后血量保持一致（`data.db` 不应放入共享目录，SQLite 不支持跨服务器并发）。

### 跨服血量同步（Redis，可选，推荐群组服开启）

默认的数据库同步是异步的：玩家切服进入目标服时，先按目标服本地的旧血量显示，插件随后才异步查库修正 modifier / 血量，因此可能出现血条跳变、受伤动画，甚至高血量被夹回基础值。

开启 Redis 后，源服在**退出 / 吃桃 / 死亡 / 设置加成**时把玩家最新状态（加成 + 当前血量）写入快照；目标服在登录预处理阶段（`AsyncPlayerPreLoginEvent`，异步线程）预取，并在 `PlayerJoinEvent` 主线程内**一次性同步还原**最大血量与当前血量，不再存在异步空窗：

```yaml
redis:
  enabled: true
  host: localhost
  port: 6379
  password: ""
  database: 0
  key_prefix: "luckypeaches:"    # 多套群组服共用一个 Redis 时用不同前缀隔离
  timeout_ms: 1000
  ttl_seconds: 600                # 快照 TTL，0 = 不过期；建议略大于一次切服耗时
  max_total: 16
  max_idle: 8
  flush_interval_seconds: 60      # 周期兜底上报在线玩家血量，0 = 禁用
```

- Redis 只是加速层：**未开启或连接失败时自动回退数据库同步**，不影响任何既有功能。
- **加成以数据库为准**：登录时先用快照即时还原血量，随后仍异步读库校准 `peach_bonus`；即使快照过期/丢失/被误删，也会被纠回数据库值，Redis 不会造成数据回退。
- Redis 连续失败会自动熔断 30 秒后重试，期间不阻塞登录/吃桃等主线程操作。
- 修改 `redis` 配置需重启服务器后生效（不随 `/luckypeach reload` 重连）。
- 快照里存的是**真实持久化加成**，因此战斗中（`setPeachBonusSuppressed`）也不会被误写成 0。
- 屏蔽世界内桃 modifier 会被临时清空，插件会缓存进入前的真实加成：即使玩家站在屏蔽世界退出，快照/退出保存也写真实值，不会写成 0（下次仍会按目标世界规则决定是否生效）。
- 若仍看到明显掉血，通常是切服瞬间的真实伤害（如落地/窒息）；本插件已保证不会因为同步时序而额外改动血量。

### 委托给 SparrowSync（可选，将跨服生命交给专业同步插件）

如果群组服已经安装 [sparrow-sync](https://github.com/NanamoServer/sparrow-sync)，可以把跨服生命同步完全交给它，本插件则退居**蟠桃加成的权威与备份数据库**：

```yaml
sparrow_sync_delegate_health: true
```

开启后（且检测到 SparrowSync）：

- 本插件**不再启用 Redis、不再自行还原 `current_health`**，最大生命值与当前血量由 SparrowSync 负责。
- 本插件仍然把 `peach_bonus` 与 `current_health` 写入自己的数据库作为备份，并在登录后异步把 `peach_bonus` 对应的 modifier 校准到数据库值（若 SparrowSync 已正确恢复则无操作）。
- SparrowSync 未安装时该配置被忽略并输出一条警告，回退到本插件自带的同步逻辑（Redis 或数据库）。

使用前请确保 SparrowSync：

- `data-types.health: true` 与 `data-types.attributes: true`（默认已开启）。
- 把 `LuckyPeachesWorldMax` 加入 `attributes.modifier-blacklist`——它是**逐世界**的上限，不能跨服同步。
- `LuckyPeaches` modifier 由本插件数据库权威，只要两边配置一致就不会互相覆盖。
- **抑制态不跨服**：`setPeachBonusSuppressed` 只在本服内存；切服后目标服按数据库/快照恢复真实加成。委托模式下本插件不自行补血，故若 sparrow 未同步 `LuckyPeaches` modifier，玩家血量会停在 sparrow 恢复的值（建议让 sparrow 同步该 modifier）。

> 建议：要么用本插件自带同步（Redis / 数据库），要么用 SparrowSync，不要两套同时开。开启委托后本插件会自动关掉自己的那份。

### 非蟠桃血量与其它插件的兼容

本插件只负责**蟠桃加成 + 当前血量**的跨服同步，非蟠桃最大生命（其它插件的 modifier/基础值）由对应插件自己同步。为避免本插件在同步时误伤非蟠桃血量：

- `settings.base_max_health` 默认**只接管仍为原版默认值(20) 的基础最大生命值**，不会覆盖其它插件用 `setBaseValue` 设置的基础生命。要恢复旧行为（每次登录强制设为该值）请设 `force_base_max_health: true`。
- `settings.join_health_restore_delay_ticks`（默认 40 tick）：其它插件的最大生命加成通常晚于本插件应用，若登录血量被当时的偏低上限夹低，会在该延迟后补回到记录值；期间若玩家受到**真实伤害**则放弃补回，不会把伤害治掉。
- **退出/关服保存默认不补满**：战斗中玩家（`setPeachBonusSuppressed`）退出时只恢复 modifier、保留真实血量，不会把“满血”写库；确需补满可设 `settings.suppressed_restore_health: true`。
- 进入屏蔽世界时会记录进入前血量（并缓存真实蟠桃加成，避免退出快照写成 0），离开时按配置还原（`restore_full_health_on_exit: true` 补满；否则至少还原到进入前血量），避免非蟠桃血量被永久压低。
- `world_max_health` 配置的是**该世界的总最大生命值**，会扣除基础值/蟠桃加成/其它插件 modifier 后计算所需增量，叠加其它加成后不会顶穿。

自动备份支持 SQLite（VACUUM INTO）和 MySQL（YML/JSON 导出）两种模式。

## 消息配置

`messages.yml` 可自定义所有插件消息，支持 `&` 颜色代码。设为 `show_prefix: false` 可关闭 `[幸运蟠桃]` 前缀。

可用占位符：

| 占位符 | 含义 | 使用位置 |
|--------|------|----------|
| `%player%` | 玩家名 | 多数指令消息 |
| `%peach_health%` | 当前蟠桃加成值（1 位小数） | `success` / `fail` / `max_health_reached` / `death_penalty` |
| `%bonus%` | 本次获得的加成 | `success` |
| `%penalty%` | 本次死亡损失的加成 | `death_penalty` |
| `%world%` / `%worlds%` | 世界名 / 世界列表 | `world_*` |
| `%health%` | 数值 | `get_health` / `set_health_success` / `world_max_health_*` |
| `%amount%` / `%peach%` | 数量 / 蟠桃名 | `give_*` |
| `%count%` / `%type%` / `%error%` | 数量 / 数据库类型 / 错误信息 | `db_*` / `clear_health_all` |

> ⚠️ 消息模板里不要写裸 `%`（例如 `100%`），它会被当作格式化占位符。
> `death_penalty` 兼容旧的 `%.1f / %.1f` 写法，但推荐使用 `%penalty%` + `%peach_health%`；
> 模板写坏时插件会原样发送消息并输出一条警告，不会中断主线程。

## API

供其他插件调用（调用时无视觉变化，不触发受伤/回血动画）：

```java
// 2.2.8 起：主线程设置死亡豁免，并独立屏蔽蟠桃血量
PeachIntegrationAPI.setPlayerInBattle(player);
PeachIntegrationAPI.setPeachBonusSuppressed(player, true);

// 2.2.9：结束/退出/关服先同步恢复加成并为存活玩家补满，再解除死亡豁免并异步校准
PeachIntegrationAPI.setPeachBonusSuppressed(player, false);
PeachIntegrationAPI.setPlayerNotInBattle(player);

// 2.3.1：退出/关服保存前只想恢复属性、保留真实血量（不补满）时用它
PeachIntegrationAPI.restoreSuppressedBonusKeepingHealth(player);

// 检查是否战斗中（战斗中死亡不扣蟠桃血）
PeachIntegrationAPI.isPlayerInBattle(player.getUniqueId());

// 清理非蟠桃插件的血量 modifier
PeachIntegrationAPI.clearNonPeachModifiers(player);
```

详见 [API使用文档.md](API使用文档.md)

## 项目结构

```
├── pom.xml                           # Maven 配置
├── src/main/java/com/luckypeaches/
│   ├── LuckyPeaches.java             # 插件入口（共享配置目录 + 配置读写重定向 + 调度辅助）
│   ├── PeachListener.java            # 核心逻辑（吃桃/死亡/世界切换/登录血量同步）
│   ├── PeachManager.java             # 蟠桃物品创建（CraftEngine 集成）
│   ├── RedisSyncManager.java         # 跨服血量快照（Redis，可选，登录同步还原）
│   ├── DatabaseManager.java          # 双数据库（SQLite/MySQL）
│   ├── PeachCommand.java             # /luckypeach 命令处理
│   ├── BackupManager.java            # 自动备份
│   ├── MessageManager.java           # i18n 消息（从共享目录读取）
│   ├── PeachPlaceholder.java         # PlaceholderAPI 扩展
│   ├── HealthModifierUtil.java       # 生命值 AttributeModifier 统一操作（UUID/增删/读取）
│   └── PeachIntegrationAPI.java      # 公共 API
└── src/main/resources/
    ├── plugin.yml
    ├── config.yml
    ├── messages.yml
    └── peaches.yml
```

## 更新日志

### v2.2.5
- **修复回归：必须对着方块/地面右键才能吃桃**。v2.2.1 给右键处理加了 `event.isCancelled()` 早退，
  而服务器上其它插件（自定义物品/技能/拦截类插件）经常会把"对着空气右键"整个取消掉，
  于是空气右键不再能吃东西。服务端本身不会取消空气右键（已从 CraftBukkit 字节码确认
  `CraftEventFactory` 以 `DEFAULT` 构造空气右键事件），所以取消只可能来自其它插件。
  现在**故意不检查** `isCancelled()`（蟠桃是管理员发放的消耗品，直接放行）
- 新增 debug 诊断：右键被其它插件取消时会打印一条日志，并（首次）列出所有
  `ignoreCancelled=false` 的 `PlayerInteractEvent` 监听插件，便于定位是谁拦掉了空气右键

### v2.2.4
- **死亡冷却改为跨服共享**：冷却时间戳持久化到数据库（新表 `<前缀>peach_death_cooldown`），
  用于拦截"假死 + 切服导致同一次死亡在两个服各触发一次 `PlayerDeathEvent`，被扣两次"的问题
  （此前冷却只存在单服内存里，且退出即清空，切服/重进后完全无效）
- 冷却单独一张表：玩家表的 upsert（SQLite 为整行替换）不会冲掉时间戳；本表缺失或查询异常时
  冷却读取**软失败返回 0**，绝不会影响正常玩家数据的读写（最坏退化为原来的单服冷却）
- **吃桃改为锁内"读-加-写"**（`addPeachBonus`）：避免与死亡惩罚等并发写入交错时互相覆盖
- 延迟恢复保存改用 `updateCurrentHealth()`，不再重复写 peach_bonus 绝对值（同样避免覆盖并发吃桃）
- 若要让假死/切服场景完全被拦住，请把 `death_cooldown_ms` 调大到你一次切服的耗时以上
  （例如 15000），配置项注释里已写明

### v2.2.3
- **修复退出/关服保存会覆盖并发写入的蟠桃加成**（可导致死亡惩罚失效）：原来的退出保存是
  "读出 peach_bonus 再原样写回"，数据库卡顿（例如备份正持有锁做 VACUUM）时，这个读-写之间可能
  夹进死亡惩罚或吃桃的写入，把已扣掉/加上的加成覆盖回旧值。现在退出保存与关服保存改用
  `updateCurrentHealth()`，**只更新 current_health 列，绝不触碰 peach_bonus**
  （已用 SQLite 实测复现旧行为丢惩罚、新行为保留惩罚）
- **死亡惩罚标记改为同步打点**：`pendingDeathPenalty` 现在在死亡处理的同步阶段就写入，
  覆盖"死亡 → 扣罚落库"的整个窗口，避免玩家死亡后秒退/秒切服时退出保存抢先把旧值写回而
  "逃掉"本次扣罚；扣罚写库失败时不再改本地血条，保证数据库与玩家血条一致
- **关服保存不再逐个"读-改-写"**：`saveAllOnlinePlayers()` 同样只更新血量列，关服更快且无覆盖风险

### v2.2.2
- **数据库写入失败会退回蟠桃**：吃桃时若数据库写入失败，消耗掉的道具会退回背包（满则掉落原地）并提示
  `eat_save_failed`，不再让玩家白白损失道具
- **数据库关闭后不再懒重连**：`close()` 之后迟到的查询直接失败，而不是偷偷重开一个再也没人关闭的
  SQLite 连接（连接 + 文件锁泄漏）
- **同一次逻辑只取一次 DatabaseManager**：`databaseManager` 改为 `volatile`，吃桃/退出保存/死亡惩罚/登录
  校验都先取本地引用再用，避免热切换瞬间"从旧库读、往新库写"或命中已关闭的旧库
- **MySQL 连接参数一次性快照**：`initMySQL()` 在异步迁移线程执行，不再逐项重复读配置，
  避免热重载期间连接参数来自两份不同的配置实例
- **备份配置一次性快照**：备份线程同样取一次配置快照（备份目录/格式/保留份数）
- **已实测**：本机 MySQL 不可用时 `/luckypeach db mysql` 迁移失败会**保留 SQLite 与配置文件**
  （`settings.database.type` 不会被改写）、插件继续正常服务；配置里手改 `type` 后
  热重载或 `/luckypeach reload` 都会明确提示改用 `/luckypeach db` 迁移

### v2.2.1
- **修复内存/资源泄漏**：默认配置与消息文件的 jar 资源流未关闭；插件卸载时未清理静态集合
  （`playersInDisabledWorld`、`playersMaxHealthWorld`、`lastDeathTime`、`eatingPlayers`、
  `pendingDeathPenalty`、战斗标记）与静态 `instance` 引用，热重载后会残留状态
- **修复配置键补全失效**：`mergeDefaultConfig()` 使用了会回退到 defaults 的 `contains(key)`，
  永远判定为"已存在"，导致新增配置项从不写入配置文件（改用 `contains(key, true)`）
- **修复备份任务风险**：`backup_interval_hours: 0` 会让任务每 tick 执行；重复启动会产生多个
  备份任务；手动备份与定时备份可能并发写同一文件
- **修复死亡惩罚消息**：模板被改坏（裸 `%`）时 `String.format` 抛异常打断主线程任务，
  改为容错格式化，并支持 `%penalty%` / `%peach_health%` 占位符
- **修复数据库热切换**：配置写入/备份任务重启原来在异步线程调用 Bukkit API（线程不安全），
  改为迁移成功后在主线程执行；迁移失败时不再写入配置（避免重启后连到空库）
- **修复排名查询**：`getPlayerRank` 对无记录/无加成玩家返回 1，改为返回 -1
- **修复 `/luckypeach sethealth 0`**：会留下 0 值 modifier，现在会正确清除
- **修复世界状态残留**：`world_max_health` 关闭或世界配置被删除后，旧 modifier 会一直挂在玩家身上；
  配置热重载/`/luckypeach world` 修改后现在会立即对在线玩家生效
- **修复世界最大生命值不更新**：`/luckypeach world setmax`、配置热重载对"当前就站在该世界"的玩家原本完全无效
  （只比较世界名就跳过），现在会比对 modifier 数值后重新应用
- **修复屏蔽世界加成回灌**：配置重载/数据库切换/登录校验/吃桃/死亡惩罚等异步回调，重新套用
  modifier 前都会复查玩家是否已被移入屏蔽世界
- **修复 `/luckypeach backup now` 卡服**：备份（VACUUM INTO / 整表导出）改为异步执行
- **修复 CraftEngine 降级物品**：CE 不可用时回退的原版物品现在会正确写入显示名/lore/CustomModelData
  （之前只带 PDC 标记，玩家拿到的是"无名"物品）
- **修复交互被拦截仍吃桃**：被区域/保护插件取消的右键事件不再消耗蟠桃
- **修复 `world getmax/removemax`**：改用 `contains(key, true)`，不再把默认配置里的键当成"已设置"
- **插件生命周期**：卸载时注销 PlaceholderAPI 扩展并清空所有静态状态；`settings.debug` 现在会随
  配置热重载刷新；`/luckypeach reload` 检测到 `database.type` 被改动时会提示改用 `/luckypeach db` 迁移
- **代码优化**：抽出 `HealthModifierUtil` 统一 10+ 处重复的 modifier 查找/移除/添加逻辑；
  SQL 语句预拼接；迁移写入改为分批 + 事务；`toLowerCase` 指定 Locale；`chance` 判定由 `<=` 改为 `<`
- **健壮性**：数据库初始化失败时禁用插件而不是静默带病运行（并明确声明 sqlite-jdbc 依赖）；
  `/luckypeach give` 数量上限 2304；插件关闭期间静默跳过调度任务；控制台配置热重载任务增加异常兜底
  （避免一次异常永久停掉热重载）

### v2.2
- **MySQL 数据库支持**：HikariCP 连接池，适用于多服务器/群组服环境
- **数据库热切换**：`/luckypeach db` 命令一键切换 SQLite ↔ MySQL，数据自动迁移
- **共享配置目录**：`shared_config_dir` + `config_poll_interval`，替代原 Proxy 配置同步，多端读取同一份配置并自动热重载
- **登录血量归一**：`base_max_health` 登录时设置基础生命值
- **多端血量同步**：登录时从数据库校验蟠桃加成，切换服务器血量保持一致
- **强制清理**：`/luckypeach clearhealth <玩家|all>` 清理非蟠桃血量加成
- **表名前缀**：`table_prefix` 配置避免多插件表名冲突
- **API 零视觉变化**：标志位机制，调用时无屏幕闪烁或受伤动画
- **战斗死亡豁免**：战斗中死亡不扣除蟠桃加成
- **线程安全**：修复异步线程调用 Bukkit API、主线程阻塞 DB 等问题
- **自动备份**：支持 SQLite（VACUUM INTO）和 MySQL（YML/JSON 导出）
