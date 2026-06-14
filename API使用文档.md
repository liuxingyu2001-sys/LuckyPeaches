# LuckyPeaches API 使用文档

## 📖 概述

LuckyPeaches 插件提供了一套完整的API接口，允许其他插件（如决斗插件、公会战插件等）临时控制玩家的蟠桃血量加成。

### 核心功能
- **临时禁用蟠桃加成**：在战斗、决斗等场景下临时移除玩家的蟠桃血量加成
- **自动恢复满血**：恢复蟠桃加成时，自动将玩家生命值恢复到最大生命值上限
- **批量操作支持**：支持单个玩家和批量玩家的操作

### API类
```
com.luckypeaches.PeachIntegrationAPI
```

---

## 🔧 API方法说明

### 1. 单个玩家控制

#### `setPlayerInBattle(Player player)`
临时关闭指定玩家的蟠桃血量加成。

**参数：**
- `player` - 目标玩家对象

**功能：**
- 移除玩家身上的蟠桃血量modifier
- 更新血量显示
- 在主线程执行，确保线程安全

**使用场景：** 玩家进入决斗/战斗时调用

---

#### `setPlayerNotInBattle(Player player)`
恢复指定玩家的蟠桃血量加成。

**参数：**
- `player` - 目标玩家对象

**功能：**
- 从数据库加载玩家的蟠桃加成值
- 重新应用蟠桃血量modifier
- 更新血量显示
- **将玩家生命值恢复到最大生命值上限**
- 异步加载数据，主线程应用modifier

**使用场景：** 玩家退出决斗/战斗时调用

---

### 2. 批量玩家控制

#### `setPlayersInBattle(Collection<Player> players)`
临时关闭多个玩家的蟠桃血量加成。

**参数：**
- `players` - 玩家集合（List、Set等）

**功能：**
- 批量移除多个玩家的蟠桃血量modifier
- 自动过滤空值和离线玩家

**使用场景：** 团队决斗、公会战开始时调用

---

#### `setPlayersNotInBattle(Collection<Player> players)`
恢复多个玩家的蟠桃血量加成。

**参数：**
- `players` - 玩家集合（List、Set等）

**功能：**
- 批量恢复多个玩家的蟠桃血量modifier
- **将所有玩家生命值恢复到最大生命值上限**
- 自动过滤空值和离线玩家

**使用场景：** 团队决斗、公会战结束时调用

---

### 3. 工具方法

#### `getPluginInstance()`
获取LuckyPeaches插件实例。

**返回值：**
- `LuckyPeaches` - 插件实例对象

**使用场景：** 需要访问插件内部功能时使用

---

## 📦 依赖配置

### Maven配置

在您的插件 `pom.xml` 中添加以下依赖：

```xml
<dependencies>
    <dependency>
        <groupId>com.luckypeaches</groupId>
        <artifactId>LuckyPeaches</artifactId>
        <version>1.2.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Gradle配置

```groovy
dependencies {
    compileOnly 'com.luckypeaches:LuckyPeaches:1.2.0'
}
```

---

## 💡 使用示例

### 示例1：1v1决斗系统

```java
package com.example.duel;

import com.luckypeaches.PeachIntegrationAPI;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class DuelManager {
    
    private final DuelPlugin plugin;
    
    public DuelManager(DuelPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void startDuel(Player player1, Player player2) {
        // 关闭蟠桃血量加成
        PeachIntegrationAPI.setPlayerInBattle(player1);
        PeachIntegrationAPI.setPlayerInBattle(player2);
        
        player1.sendMessage("§a决斗开始！蟠桃加成已暂时禁用");
        player2.sendMessage("§a决斗开始！蟠桃加成已暂时禁用");
        
        // 开始决斗逻辑...
        startDuelLogic(player1, player2);
    }
    
    public void endDuel(Player winner, Player loser) {
        // 恢复蟠桃血量加成
        PeachIntegrationAPI.setPlayerNotInBattle(winner);
        PeachIntegrationAPI.setPlayerNotInBattle(loser);
        
        winner.sendMessage("§a决斗结束！蟠桃加成已恢复");
        loser.sendMessage("§a决斗结束！蟠桃加成已恢复");
        
        // 结束决斗逻辑...
    }
    
    private void startDuelLogic(Player p1, Player p2) {
        new BukkitRunnable() {
            int timeLeft = 300; // 5分钟
            
            @Override
            public void run() {
                if (timeLeft <= 0) {
                    this.cancel();
                    endDuel(null, null); // 平局
                    return;
                }
                
                if (!p1.isOnline() || !p2.isOnline()) {
                    this.cancel();
                    endDuel(null, null); // 玩家离线
                    return;
                }
                
                timeLeft--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
```

---

### 示例2：团队决斗系统

```java
package com.example.teamduel;

import com.luckypeaches.PeachIntegrationAPI;
import org.bukkit.entity.Player;
import java.util.List;

public class TeamDuelManager {
    
    public void startTeamDuel(List<Player> teamA, List<Player> teamB) {
        // 关闭所有玩家的蟠桃血量加成
        PeachIntegrationAPI.setPlayersInBattle(teamA);
        PeachIntegrationAPI.setPlayersInBattle(teamB);
        
        // 通知所有玩家
        teamA.forEach(p -> p.sendMessage("§a团队决斗开始！蟠桃加成已暂时禁用"));
        teamB.forEach(p -> p.sendMessage("§a团队决斗开始！蟠桃加成已暂时禁用"));
        
        // 开始决斗逻辑...
    }
    
    public void endTeamDuel(List<Player> teamA, List<Player> teamB, String winningTeam) {
        // 恢复所有玩家的蟠桃血量加成
        PeachIntegrationAPI.setPlayersNotInBattle(teamA);
        PeachIntegrationAPI.setPlayersNotInBattle(teamB);
        
        String message = "§a团队决斗结束！蟠桃加成已恢复";
        if (winningTeam != null) {
            message += " §e获胜队伍: " + winningTeam;
        }
        
        teamA.forEach(p -> p.sendMessage(message));
        teamB.forEach(p -> p.sendMessage(message));
    }
}
```

---

### 示例3：公会战系统

```java
package com.example.guildwar;

import com.luckypeaches.PeachIntegrationAPI;
import org.bukkit.entity.Player;
import java.util.Map;
import java.util.HashMap;

public class GuildWarManager {
    
    private Map<String, List<Player>> warParticipants = new HashMap<>();
    
    public void startGuildWar(String guild1, String guild2, List<Player> players1, List<Player> players2) {
        // 记录参与者
        warParticipants.put(guild1, players1);
        warParticipants.put(guild2, players2);
        
        // 关闭所有参与者的蟠桃血量加成
        PeachIntegrationAPI.setPlayersInBattle(players1);
        PeachIntegrationAPI.setPlayersInBattle(players2);
        
        // 广播消息
        String message = "§6[公会战] §a公会战开始！所有参与者的蟠桃加成已暂时禁用";
        players1.forEach(p -> p.sendMessage(message));
        players2.forEach(p -> p.sendMessage(message));
    }
    
    public void endGuildWar(String winningGuild) {
        // 恢复所有参与者的蟠桃血量加成
        warParticipants.values().forEach(PeachIntegrationAPI::setPlayersNotInBattle);
        
        String message = "§6[公会战] §a公会战结束！蟠桃加成已恢复";
        if (winningGuild != null) {
            message += " §e获胜公会: " + winningGuild;
        }
        
        warParticipants.values().forEach(players -> 
            players.forEach(p -> p.sendMessage(message))
        );
        
        // 清空参与者
        warParticipants.clear();
    }
    
    public void playerJoinWar(Player player, String guild) {
        // 玩家中途加入公会战
        PeachIntegrationAPI.setPlayerInBattle(player);
        player.sendMessage("§6[公会战] §a你已加入公会战！蟠桃加成已暂时禁用");
    }
    
    public void playerLeaveWar(Player player) {
        // 玩家中途离开公会战
        PeachIntegrationAPI.setPlayerNotInBattle(player);
        player.sendMessage("§6[公会战] §a你已离开公会战！蟠桃加成已恢复");
    }
}
```

---

### 示例4：竞技场系统

```java
package com.example.arena;

import com.luckypeaches.PeachIntegrationAPI;
import org.bukkit.entity.Player;
import java.util.HashSet;
import java.util.Set;

public class ArenaManager {
    
    private final Set<Player> activePlayers = new HashSet<>();
    
    public void enterArena(Player player) {
        if (activePlayers.contains(player)) {
            player.sendMessage("§c你已经在竞技场中了！");
            return;
        }
        
        // 关闭蟠桃血量加成
        PeachIntegrationAPI.setPlayerInBattle(player);
        activePlayers.add(player);
        
        player.sendMessage("§a进入竞技场！蟠桃加成已暂时禁用");
    }
    
    public void exitArena(Player player) {
        if (!activePlayers.contains(player)) {
            player.sendMessage("§c你不在竞技场中！");
            return;
        }
        
        // 恢复蟠桃血量加成
        PeachIntegrationAPI.setPlayerNotInBattle(player);
        activePlayers.remove(player);
        
        player.sendMessage("§a退出竞技场！蟠桃加成已恢复");
    }
    
    public void forceEndArena() {
        // 强制结束竞技场（如服务器关闭等）
        PeachIntegrationAPI.setPlayersNotInBattle(activePlayers);
        activePlayers.forEach(p -> p.sendMessage("§c竞技场强制结束！蟠桃加成已恢复"));
        activePlayers.clear();
    }
}
```

---

## ⚠️ 注意事项

### 1. 线程安全
- API内部已经处理了线程安全问题
- 可以在任何线程调用API方法
- 数据库操作在异步线程执行，modifier应用在主线程执行

### 2. 空值处理
- API会自动处理 `null` 值
- API会自动过滤离线玩家
- 无需额外检查

### 3. 配对调用
- **重要**：`setPlayerInBattle()` 和 `setPlayerNotInBattle()` 必须成对调用
- 如果只调用 `setPlayerInBattle()` 而不调用 `setPlayerNotInBattle()`，玩家的蟠桃加成将永久失效
- 建议在 `finally` 块中调用恢复方法

### 4. 玩家离线处理
```java
try {
    PeachIntegrationAPI.setPlayerInBattle(player);
} finally {
    // 确保即使玩家离线也能正确处理
    if (player.isOnline()) {
        PeachIntegrationAPI.setPlayerNotInBattle(player);
    }
}
```

### 5. 服务器关闭处理
建议在插件禁用时恢复所有玩家的蟠桃加成：

```java
@Override
public void onDisable() {
    // 恢复所有活跃玩家的蟠桃加成
    PeachIntegrationAPI.setPlayersNotInBattle(activePlayers);
}
```

### 6. 自动恢复满血
- **重要**：调用 `setPlayerNotInBattle()` 时，玩家的生命值会自动恢复到最大生命值上限
- 这是默认行为，无需额外配置
- 适用于所有恢复蟠桃加成的场景（单个玩家和批量玩家）
- 确保玩家在战斗结束后以满血状态继续游戏

---

## 🎯 最佳实践

### 1. 使用状态管理
```java
public class DuelSession {
    private Player player1;
    private Player player2;
    private boolean active;
    
    public void start() {
        if (active) return;
        
        PeachIntegrationAPI.setPlayerInBattle(player1);
        PeachIntegrationAPI.setPlayerInBattle(player2);
        active = true;
    }
    
    public void end() {
        if (!active) return;
        
        PeachIntegrationAPI.setPlayerNotInBattle(player1);
        PeachIntegrationAPI.setPlayerNotInBattle(player2);
        active = false;
    }
}
```

### 2. 使用监听器处理异常情况
```java
@EventHandler
public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    
    // 检查玩家是否在决斗中
    if (duelManager.isInDuel(player)) {
        // 自动结束决斗并恢复蟠桃加成
        duelManager.endDuel(player);
    }
}

@EventHandler
public void onPlayerKick(PlayerKickEvent event) {
    Player player = event.getPlayer();
    
    // 检查玩家是否在竞技场中
    if (arenaManager.isInArena(player)) {
        // 自动退出竞技场并恢复蟠桃加成
        arenaManager.exitArena(player);
    }
}
```

### 3. 使用配置控制
```java
public class DuelConfig {
    private boolean enablePeachIntegration;
    
    public DuelConfig(YamlConfiguration config) {
        this.enablePeachIntegration = config.getBoolean("peach_integration.enabled", true);
    }
    
    public void applyPeachIntegration(Player player) {
        if (enablePeachIntegration) {
            PeachIntegrationAPI.setPlayerInBattle(player);
        }
    }
}
```

---

## 🔍 故障排查

### 问题1：调用API后血量没有变化
**可能原因：**
- 玩家没有蟠桃加成
- 玩家离线
- 插件未正确加载

**解决方案：**
```java
if (player.isOnline()) {
    LuckyPeaches plugin = PeachIntegrationAPI.getPluginInstance();
    if (plugin != null) {
        PeachIntegrationAPI.setPlayerInBattle(player);
    } else {
        player.sendMessage("§c蟠桃插件未加载！");
    }
}
```

### 问题2：恢复后血量不正确
**可能原因：**
- 数据库中没有玩家的蟠桃加成数据
- 玩家数据未正确保存

**解决方案：**
确保玩家数据正确保存，可以手动检查数据库。

### 问题3：编译错误
**可能原因：**
- 依赖未正确添加
- LuckyPeaches插件未安装到服务器

**解决方案：**
检查 `pom.xml` 或 `build.gradle` 中的依赖配置是否正确。

---

## 📞 技术支持

如有问题或建议，请联系LuckyPeaches插件开发者。

---

## 📄 版本信息

- **API版本**：1.2.0
- **最后更新**：2026-03-12
- **兼容Bukkit/Spigot版本**：1.13+

---

## 📜 许可证

本API文档遵循LuckyPeaches插件的许可证。
