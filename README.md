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

## 配置

主配置文件 `config.yml` 包含以下模块：

- **`settings`** — 调试模式、最大生命值上限、VIP 分组、死亡惩罚、音效、粒子、血量缩放、数据库备份
- **`world_integration`** — 世界屏蔽（进入/离开行为、回满血设置）
- **`world_max_health`** — 按世界的最大生命值
- **`peaches`** — 蟠桃定义（`display_name`、`material`、`lore`、`health_bonus`、`chance`、`custom_model_data`）

## API

供其他插件调用：

```java
// 战斗开始时临时关闭蟠桃加成
PeachIntegrationAPI.setPlayerInBattle(player);

// 战斗结束后恢复蟠桃加成
PeachIntegrationAPI.setPlayerNotInBattle(player);

// 清理非本插件的血量 modifier（保留蟠桃相关）
PeachIntegrationAPI.clearNonPeachModifiers(player);
PeachIntegrationAPI.clearNonPeachModifiers(players); // 批量
```

详见 [API使用文档.md](API使用文档.md)
