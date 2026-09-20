# LuckyPeaches API 使用说明

本文对应 **2.2.8**。服务端和编译依赖均需更新为 `Liu-LuckyPeaches-2.2.8.jar`；不要把 LuckyPeaches 打包进调用插件，使用 `provided` 或 `compileOnly`，并声明 `softdepend: [LuckyPeaches]`。

API 类：`com.luckypeaches.PeachIntegrationAPI`。

## 死亡豁免与血量屏蔽

这是两个独立功能，旧的战斗标记方法保持原语义：

| 方法 | 行为 |
|---|---|
| `setPlayerInBattle(Player)` | 设置死亡不扣蟠桃加成的标记，**不移除血量加成**；null/离线忽略 |
| `setPlayerNotInBattle(Player)` | 清除标记，异步读取数据库并校准蟠桃 modifier；null/离线忽略 |
| `isPlayerInBattle(UUID)` | 查询死亡豁免标记 |
| `setPeachBonusSuppressed(Player, true)` | **2.2.8 新增**，立即屏蔽蟠桃血量 modifier，保留待恢复数值 |
| `setPeachBonusSuppressed(Player, false)` | **2.2.8 新增**，立即解除屏蔽并恢复缓存数值，无须等待数据库或调度任务 |
| `isPeachBonusSuppressed(UUID)` | 查询血量屏蔽状态 |

`setPeachBonusSuppressed` 必须在 Bukkit **主线程**调用，错误线程抛出异常。null 忽略；重复启用不会把已移除后的 0 当成原加成，重复解除无副作用。它仅处理 LuckyPeaches 的蟠桃 modifier，不改变基础血量、装备/药水/其他插件 modifier，也不修改数据库中的永久蟠桃总量。

屏蔽时当前血量超过新上限会被限制到上限，因此血条会变化；解除时不会强制满血。解除支持退出、关服回调中 `isOnline()` 已为 false 的有效 Player 对象；调用方应在 `saveData()` 和背包同步插件取快照前解除。

登录校验、吃桃、管理员改值、重载、离开屏蔽世界等内置加成更新都尊重屏蔽状态；收到的新加成先记入待恢复数值。离开屏蔽世界的延迟回调不会在屏蔽期间强制回血。解除时如果仍位于配置的禁用世界，仍保持该世界的禁用规则。

## 决斗接入示例

```java
// 主线程；先持久化决斗恢复记录，再设置这些临时状态。
PeachIntegrationAPI.setPlayerInBattle(player);
PeachIntegrationAPI.setPeachBonusSuppressed(player, true);
if (!PeachIntegrationAPI.isPlayerInBattle(player.getUniqueId())
        || !PeachIntegrationAPI.isPeachBonusSuppressed(player.getUniqueId())) {
    throw new IllegalStateException("蟠桃决斗限制未生效");
}
// 然后传送、发套件、设置本场自定义基础血量。
```

结束、取消、准备失败、退出或关服时，在主线程配对解除：

```java
// 先恢复调用方自己管理的基础血量，再恢复蟠桃加成。
PeachIntegrationAPI.setPeachBonusSuppressed(player, false); // 同步完成
PeachIntegrationAPI.setPlayerNotInBattle(player);         // 在线时额外从数据库校准
if (!player.isOnline()) {
    PeachIntegrationAPI.clearBattleStatus(player.getUniqueId());
}
player.saveData();
```

两个 API 独立配对；仅 `setPlayerNotInBattle` 不会解除显式血量屏蔽。旧版 LuckyPeaches 没有屏蔽 API，调用方应检查方法是否存在；需要公平血量的决斗应拒绝入场并提示升级，不能把仅设置死亡豁免当作血量屏蔽成功。

正常退出和 LuckyPeaches 停用也会尝试同步解除血量屏蔽。屏蔽缓存只在本服内存中，不参与共享配置；强杀进程后依靠重新登录时的数据库校验恢复蟠桃总量，不能替代调用方的持久化装备/基础血量恢复记录。

## 其他接口

- `setPlayersInBattle(Collection<Player>)` / `setPlayersNotInBattle(Collection<Player>)`：批量设置/解除死亡豁免；不是批量血量屏蔽，后者请在主线程逐个调用新 API。
- `clearBattleStatus(UUID)`：只清除死亡标记，不加载数据库、不解除血量屏蔽。
- `clearAllBattleStatus()`：插件卸载时清空运行时记录；必须先恢复玩家，不应作为正常比赛结束接口。
- `getPluginInstance()`：返回 LuckyPeaches 实例，未加载或卸载后为 null。
- `clearNonPeachModifiers(Player)` / 同名集合重载：清除**非蟠桃**最大血量 modifier，可能包含装备和药水。它不是屏蔽蟠桃的接口，不应用于本接入流程。

## 线程和恢复边界

死亡标记集合可并发访问；涉及 Player、属性或世界的调用应在主线程执行。数据库校准异步读取，应用前重新检查玩家在线状态、战斗标记与禁用世界，防止上一场的恢复任务干扰新决斗。数据库校准不强制回血。

新屏蔽 API 没有按调用插件区分所有权：同一玩家的生命周期应由一个管理者配对控制，多个插件不得各自解除同一个屏蔽标记。重载整个插件、不同步更新后端或外部插件直接改写蟠桃 modifier 不属于该保证范围。
