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
# 输出: target/Liu-LuckyPeaches-2.2.jar
```

**部署：** JAR → Spigot/Paper 服务器的 `plugins/` 目录

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
│   ├── LuckyPeaches.java             # 插件入口（共享配置目录 + 配置读写重定向）
│   ├── PeachListener.java            # 核心逻辑（吃桃/死亡/世界切换/登录血量同步）
│   ├── PeachManager.java             # 蟠桃物品创建（CraftEngine 集成）
│   ├── DatabaseManager.java          # 双数据库（SQLite/MySQL）
│   ├── PeachCommand.java             # /lp 命令处理
│   ├── BackupManager.java            # 自动备份
│   ├── MessageManager.java           # i18n 消息（从共享目录读取）
│   ├── PeachPlaceholder.java         # PlaceholderAPI 扩展
│   └── PeachIntegrationAPI.java      # 公共 API
└── src/main/resources/
    ├── plugin.yml
    ├── config.yml
    └── messages.yml
```

## 更新日志

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
