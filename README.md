# LuckyPeaches

Minecraft 幸运蟠桃插件 — 食用蟠桃可永久提升最大生命值，支持多世界隔离、死亡惩罚、血量持久化、群组服共享配置。

## 功能

- **生命值提升**：右键食用蟠桃道具，概率触发永久增加最大生命值
- **多种蟠桃**：可配置多种蟠桃（不同材质、概率、加成数值、CustomModelData、CraftEngine 模型）
- **可配置上限**：全局最大生命值上限 + VIP 权限分组上限
- **登录血量归一**：登录时设置基础最大生命值（`base_max_health`，可配置）
- **死亡惩罚**：死亡时按比例扣除蟠桃加成，支持权限分组、冷却时间、阈值保护
- **世界隔离**：指定世界屏蔽蟠桃加成（进入即移除、离开即恢复）
- **世界最大生命值**：按世界设置基础最大生命值（跨世界自动切换）
- **血量缩放**：防止血条刷屏，客户端最多显示可配置的心数
- **双数据库**：SQLite（单机）/ MySQL（多服务器），支持游戏内热切换、自动迁移
- **多端血量同步**：登录时从数据库校验蟠桃加成，切换服务器后血量自动恢复
- **共享配置目录**：群组服所有服务器读取同一份配置，修改后自动热重载
- **强制清理**：`/lp clearhealth` 清理玩家非蟠桃血量加成（单人或全部）
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

需要权限 `luckypeach.admin`，主指令别名：`/lp`、`/luckyp`、`/luckypeach`。

| 命令 | 说明 |
|------|------|
| `/lp help` | 查看帮助 |
| `/lp reload` | 重载配置、蟠桃列表和消息文件 |
| `/lp gethealth <玩家>` | 查看玩家当前蟠桃加成 |
| `/lp sethealth <玩家> <数值>` | 设置玩家蟠桃加成 |
| `/lp clearhealth <玩家\|all>` | 清理非蟠桃血量加成（all = 所有在线玩家） |
| `/lp give <玩家> <蟠桃ID> [数量]` | 给予玩家指定蟠桃 |
| `/lp backup now\|list\|enable\|disable` | 数据库备份管理 |
| `/lp world add\|remove\|list <世界>` | 管理屏蔽世界列表 |
| `/lp world setmax\|getmax\|listmax\|removemax <世界> [数值]` | 管理世界最大生命值 |
| `/lp db status\|mysql\|sqlite` | 查看/切换数据库类型（自动迁移数据） |
| `/lp import <sqlite文件>` | 导入旧 SQLite 数据到 MySQL |

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

- 设置 `shared_config_dir` 后，`config.yml` 和 `messages.yml` 均从该目录读取，写入配置的命令（`/lp world`、`/lp backup enable` 等）也会保存到共享目录
- `config_poll_interval` 秒数 > 0 时，插件定时检查共享文件修改时间，检测到变更自动热重载（配置、消息、蟠桃、在线玩家 modifier）
- 各服务器本地 `plugins/LuckyPeaches/config.yml` 仅需设置 `shared_config_dir` 作为指向

### 后端 `config.yml`

- **顶部** — `shared_config_dir`、`config_poll_interval`
- **`settings`** — 调试模式、`base_max_health`、数据库、最大生命值上限、VIP 分组、死亡惩罚、音效、粒子、血量缩放、自动备份
- **`world_integration`** — 世界屏蔽（进入/离开行为、回满血设置）
- **`world_max_health`** — 按世界的最大生命值
- **`peaches`** — 蟠桃定义（`display_name`、`material`、`lore`、`health_bonus`、`chance`、`custom_model_data`、`craftengine_model`）

### 数据库

默认使用 SQLite，支持热切换到 MySQL（适用于多服务器/群组服环境）。通过 `/lp db` 命令在游戏内一键切换，无需重启服务器，数据自动迁移。

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

多端血量同步：使用 MySQL 时，玩家登录会从数据库校验蟠桃加成并自动修正 modifier，切换服务器后血量保持一致（`data.db` 不应放入共享目录，SQLite 不支持跨服务器并发）。

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
// 战斗开始时标记（不移除 modifier，血条不变）
PeachIntegrationAPI.setPlayerInBattle(player);

// 战斗结束后恢复（从数据库同步 modifier，仅值变化时更新）
PeachIntegrationAPI.setPlayerNotInBattle(player);

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
│   ├── DatabaseManager.java          # 双数据库（SQLite/MySQL）
│   ├── PeachCommand.java             # /lp 命令处理
│   ├── BackupManager.java            # 自动备份
│   ├── MessageManager.java           # i18n 消息（从共享目录读取）
│   ├── PeachPlaceholder.java         # PlaceholderAPI 扩展
│   ├── HealthModifierUtil.java       # 生命值 AttributeModifier 统一操作（UUID/增删/读取）
│   └── PeachIntegrationAPI.java      # 公共 API
└── src/main/resources/
    ├── plugin.yml
    ├── config.yml
    └── messages.yml
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
- **已实测**：本机 MySQL 不可用时 `/lp db mysql` 迁移失败会**保留 SQLite 与配置文件**
  （`settings.database.type` 不会被改写）、插件继续正常服务；配置里手改 `type` 后
  热重载或 `/lp reload` 都会明确提示改用 `/lp db` 迁移

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
- **修复 `/lp sethealth 0`**：会留下 0 值 modifier，现在会正确清除
- **修复世界状态残留**：`world_max_health` 关闭或世界配置被删除后，旧 modifier 会一直挂在玩家身上；
  配置热重载/`/lp world` 修改后现在会立即对在线玩家生效
- **修复世界最大生命值不更新**：`/lp world setmax`、配置热重载对"当前就站在该世界"的玩家原本完全无效
  （只比较世界名就跳过），现在会比对 modifier 数值后重新应用
- **修复屏蔽世界加成回灌**：配置重载/数据库切换/登录校验/吃桃/死亡惩罚等异步回调，重新套用
  modifier 前都会复查玩家是否已被移入屏蔽世界
- **修复 `/lp backup now` 卡服**：备份（VACUUM INTO / 整表导出）改为异步执行
- **修复 CraftEngine 降级物品**：CE 不可用时回退的原版物品现在会正确写入显示名/lore/CustomModelData
  （之前只带 PDC 标记，玩家拿到的是"无名"物品）
- **修复交互被拦截仍吃桃**：被区域/保护插件取消的右键事件不再消耗蟠桃
- **修复 `world getmax/removemax`**：改用 `contains(key, true)`，不再把默认配置里的键当成"已设置"
- **插件生命周期**：卸载时注销 PlaceholderAPI 扩展并清空所有静态状态；`settings.debug` 现在会随
  配置热重载刷新；`/lp reload` 检测到 `database.type` 被改动时会提示改用 `/lp db` 迁移
- **代码优化**：抽出 `HealthModifierUtil` 统一 10+ 处重复的 modifier 查找/移除/添加逻辑；
  SQL 语句预拼接；迁移写入改为分批 + 事务；`toLowerCase` 指定 Locale；`chance` 判定由 `<=` 改为 `<`
- **健壮性**：数据库初始化失败时禁用插件而不是静默带病运行（并明确声明 sqlite-jdbc 依赖）；
  `/lp give` 数量上限 2304；插件关闭期间静默跳过调度任务；控制台配置热重载任务增加异常兜底
  （避免一次异常永久停掉热重载）

### v2.2
- **MySQL 数据库支持**：HikariCP 连接池，适用于多服务器/群组服环境
- **数据库热切换**：`/lp db` 命令一键切换 SQLite ↔ MySQL，数据自动迁移
- **共享配置目录**：`shared_config_dir` + `config_poll_interval`，替代原 Proxy 配置同步，多端读取同一份配置并自动热重载
- **登录血量归一**：`base_max_health` 登录时设置基础生命值
- **多端血量同步**：登录时从数据库校验蟠桃加成，切换服务器血量保持一致
- **强制清理**：`/lp clearhealth <玩家|all>` 清理非蟠桃血量加成
- **表名前缀**：`table_prefix` 配置避免多插件表名冲突
- **API 零视觉变化**：标志位机制，调用时无屏幕闪烁或受伤动画
- **战斗死亡豁免**：战斗中死亡不扣除蟠桃加成
- **线程安全**：修复异步线程调用 Bukkit API、主线程阻塞 DB 等问题
- **自动备份**：支持 SQLite（VACUUM INTO）和 MySQL（YML/JSON 导出）
