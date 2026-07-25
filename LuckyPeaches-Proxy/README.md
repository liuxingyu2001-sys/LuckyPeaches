# LuckyPeaches-Proxy

Velocity 代理端插件，将游戏配置从 YAML 同步到 MySQL，供后端 Spigot 服务器读取。

## 功能

- 启动时自动加载 `config.yml` 并同步到 MySQL
- 自动创建数据库和配置表
- 支持 `/lp-proxy reload` 命令热重载配置
- HikariCP 连接池管理数据库连接

## 环境要求

- Java 17+
- MySQL 5.7+ / 8.0+
- Velocity 3.3.0+

## 构建

```bash
mvn clean package
```

输出：`target/Liu-LuckyPeaches-Proxy-1.0.jar`

将 JAR 文件放入 Velocity 的 `plugins/` 目录即可。

## 配置

插件首次启动时会在数据目录生成 `config.yml`，主要配置项：

```yaml
database:
  host: localhost
  port: 3306
  database: luckypeaches
  username: root
  password: ""
  table_prefix: "lp_"

settings:
  max_health_limit: 250
  # ...更多游戏配置
```

`settings` 下的配置会同步到 MySQL 的 `{table_prefix}config` 表（默认 `lp_config`），后端服务器启动时从该表读取。

## 命令

| 命令 | 说明 | 权限 |
|------|------|------|
| `/lp-proxy reload` | 重新加载配置并同步到 MySQL | `luckypeaches.proxy.admin` |

## 项目结构

```
src/main/java/com/luckypeaches/proxy/
├── LuckyPeachesProxy.java   # 插件入口
├── config/ProxyConfig.java   # 配置加载
├── database/ProxyDatabase.java # MySQL 数据库操作
└── command/ProxyCommand.java  # 命令处理
```

## 依赖

| 依赖 | 版本 | 说明 |
|------|------|------|
| Velocity API | 3.3.0-SNAPSHOT | `provided`，不打包 |
| MySQL Connector/J | 8.0.33 | 打包进 JAR |
| HikariCP | 5.1.0 | 打包进 JAR |

## License

未指定。
