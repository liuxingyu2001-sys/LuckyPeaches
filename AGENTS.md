# AGENTS.md

## What this is

Two Minecraft plugins sharing one workspace:

- **LuckyPeaches** (`/home/plugins/LuckyPeaches/`) — Spigot/Paper backend plugin (Java 17, Maven). Eating peach items permanently boosts max health via `AttributeModifier`. Features: multi-world isolation, death penalty, SQLite/MySQL dual-database, PlaceholderAPI, CraftEngine custom models, health scaling.
- **LuckyPeaches-Proxy** (`/home/plugins/LuckyPeaches-Proxy/`) — Velocity proxy plugin. Stores full config in MySQL so backend servers sync settings on startup.

## Build

```sh
# Backend
cd /home/plugins/LuckyPeaches && mvn package

# Proxy
cd /home/plugins/LuckyPeaches-Proxy && mvn package
```

Output: `target/Liu-<artifactId>-<version>.jar` (custom `<finalName>` in each pom.xml).

**No Maven wrapper** — requires system Maven. No tests, no CI, no linting.

## Deployment (test servers)

```sh
# Backend jars
cp LuckyPeaches/target/Liu-LuckyPeaches-*.jar /home/test/test1/plugins/
cp LuckyPeaches/target/Liu-LuckyPeaches-*.jar /home/test/test2/plugins/
cp LuckyPeaches/target/Liu-LuckyPeaches-*.jar /home/p/          # production

# Proxy jar
cp LuckyPeaches-Proxy/target/Liu-LuckyPeaches-Proxy-*.jar /home/test/vel/plugins/
```

## Source layout

### Backend (`src/main/java/com/luckypeaches/`)

| File | Role |
|------|------|
| `LuckyPeaches.java` | Entrypoint. License check bypassed at line 30. `mergeDefaultConfig()` runs before MySQL sync. |
| `PeachListener.java` | Core logic — join/quit/interact/death/world-change handlers. `PEACH_MODIFIER_UUID`, `WORLD_MAX_HEALTH_MODIFIER_UUID` constants. `eatingPlayers` set guards async eat. |
| `PeachManager.java` | Peach item creation, CraftEngine integration with vanilla fallback. |
| `DatabaseManager.java` | Dual SQLite/MySQL. `executeQuery(DBAction)` callback pattern handles connection lifecycle. |
| `PeachCommand.java` | All `/lp` subcommands including `/lp db` for hot-switching database type. |
| `BackupManager.java` | Auto-backup. SQLite: VACUUM INTO. MySQL: YML/JSON export. |
| `MessageManager.java` | i18n from `messages.yml`. `&` color codes. |
| `PeachPlaceholder.java` | PlaceholderAPI expansion. Reads from AttributeModifier (no DB call). |
| `PeachIntegrationAPI.java` | Public API for other plugins (battle disable/restore). |
| `license/LicenseManager.java` | License verification (bypassed in `LuckyPeaches.java:30`). |

### Proxy (`../LuckyPeaches-Proxy/src/main/java/com/luckypeaches/proxy/`)

| File | Role |
|------|------|
| `LuckyPeachesProxy.java` | Velocity entrypoint. On init: load config → init DB → register command → sync config to MySQL. |
| `config/ProxyConfig.java` | SnakeYAML config loading. Promotes `settings.*` keys to top-level for backend compat. |
| `database/ProxyDatabase.java` | MySQL via HikariCP. Auto-creates database. Writes raw config.yml to `lp_config` table. |
| `command/ProxyCommand.java` | `/lp-proxy reload` command. |

## Resources

- `plugin.yml` — resource filtering on (`${project.version}` substituted). `api-version: 1.13`. MySQL/HikariCP via `libraries` (auto-downloaded by server, NOT bundled in jar).
- `velocity-plugin.json` — proxy plugin descriptor.
- `config.yml` (both projects) — proxy has `database:` at root + `settings:` section. Backend has everything under `settings:`.
- `messages.yml` — all user-facing strings. `&` color codes.

## Key patterns & gotchas

### DatabaseManager connection lifecycle

**This is the #1 source of bugs.** SQLite and MySQL have opposite connection semantics:

- **SQLite**: Single persistent connection (`sqliteConnection`). Do NOT close it in try-with-resources — it's reused across calls.
- **MySQL**: HikariCP pool. Every `getConnection()` borrows from pool; must close (via try-with-resources) to return to pool.

The `executeQuery(DBAction<T>)` callback pattern handles this: MySQL connections are auto-closed, SQLite connections are not. Always use this pattern for new queries.

`getConnection()` will throw if `hikariPool` is null (MySQL init failed) or SQLite connection is dead. Callers catch via `executeQuery`'s `throws SQLException`.

### Thread model

- DB reads/writes: `runTaskAsynchronously`. Modifier application: `runTask` (main thread).
- `dbLock` (synchronized) protects all DB operations.
- `eatingPlayers` Set prevents duplicate eat attempts during async processing. Always clean up in `finally` or `catch`.
- **Never call Bukkit API from async threads** (e.g., `player.getHealth()` in async context is unsafe).
- **Never do blocking DB calls on main thread** — `PeachPlaceholder` reads from AttributeModifier, not DB.

### Config sync (proxy → backend via MySQL)

1. Proxy reads its `config.yml` (raw YAML string), writes to MySQL `lp_config` table.
2. Backend on startup: if MySQL mode, reads config from DB, merges into local config.
3. **Critical**: merge must skip `settings.database` keys to preserve local MySQL connection details. Filter: `key.startsWith("settings.database.") || key.equals("settings.database")`.

### Health modifier UUIDs

Derived from `UUID.nameUUIDFromBytes("LuckyPeaches".getBytes())` and `"LuckyPeachesWorldMax"`. **Changing these strings orphans existing modifiers on live servers.**

### Database hot-switch (`/lp db`)

Switches SQLite ↔ MySQL with data migration. Sequence: save online players → read all data → create new DB → write data → replace manager → close old. **Old DB must be closed AFTER new DB is fully ready**, not before.

## Dependencies

| Plugin | Required | Scope | Notes |
|--------|----------|-------|-------|
| Paper API 1.21 | Yes | provided | Minimum `api-version: 1.13` |
| PlaceholderAPI | Optional | provided | `softdepend` in plugin.yml |
| CraftEngine | Optional | provided | Maven repo `repo.momirealms.net`. Both `craft-engine-bukkit` + `craft-engine-core`. |
| MySQL Connector 8.0.33 | Optional | provided | Backend: via `libraries` auto-download. Proxy: bundled via shade. |
| HikariCP 5.1.0 | Optional | provided | Same as above. |

**Backend**: all deps are `provided` scope — the server supplies them at runtime via `libraries` in plugin.yml.
**Proxy**: MySQL + HikariCP are bundled (shade plugin with `ServicesResourceTransformer`). Velocity API is `provided`.

## Commands and permissions

Main: `/luckypeach` (aliases: `/lp`, `/luckyp`). Requires `luckypeach.admin`.
Proxy: `/lp-proxy`. Requires `luckypeaches.proxy.admin`.

Permissions: `luckypeaches.maxhealth.<key>` (VIP health caps), `luckypeaches.deathpenalty.<key>` (death penalty groups).

## Documentation

`README.md` and `API使用文档.md` are in Chinese. The API doc covers `PeachIntegrationAPI` usage with code examples for 1v1 duels, team duels, guild wars, and arenas.
