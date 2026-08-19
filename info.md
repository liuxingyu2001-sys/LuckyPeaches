# info.md

## What this is

Single Spigot/Paper backend plugin (Java 17, Maven). Eating peach items permanently boosts max health via `AttributeModifier`. Features: multi-world isolation, death penalty, SQLite/MySQL dual-database, PlaceholderAPI, CraftEngine custom models, health scaling, shared config directory for network servers.

The former `LuckyPeaches-Proxy` (Velocity config sync via MySQL) was removed — replaced by a **shared config directory** (`shared_config_dir`) pattern copied from mcskills: all servers read the same `config.yml` / `messages.yml`, mtime polling auto-reloads on change.

## Build

```sh
mvn package
```

Output: `target/Liu-LuckyPeaches-<version>.jar` (custom `<finalName>` in pom.xml).

**No Maven wrapper** — requires system Maven. No tests, no CI, no linting.

## Deployment (test servers)

```sh
# Backend jars
cp target/Liu-LuckyPeaches-*.jar /home/test/test1/plugins/
cp target/Liu-LuckyPeaches-*.jar /home/test/test2/plugins/
cp target/Liu-LuckyPeaches-*.jar /home/p/          # production
```

## Source layout (`src/main/java/com/luckypeaches/`)

| File | Role |
|------|------|
| `LuckyPeaches.java` | Entrypoint. **Overrides `getConfig()`/`reloadConfig()`/`saveConfig()`** to redirect to shared dir when `shared_config_dir` is set. `startConfigPollTask()` polls file mtimes. |
| `PeachListener.java` | Core logic — join/quit/interact/death/world-change handlers. `PEACH_MODIFIER_UUID`, `WORLD_MAX_HEALTH_MODIFIER_UUID` constants. `eatingPlayers` set guards async eat. `onJoin` async-verifies peach bonus from DB (multi-server sync). |
| `PeachManager.java` | Peach item creation, CraftEngine integration with vanilla fallback. |
| `DatabaseManager.java` | Dual SQLite/MySQL. `executeQuery(DBAction)` callback pattern handles connection lifecycle. |
| `PeachCommand.java` | All `/lp` subcommands including `/lp db` hot-switch and `/lp clearhealth`. |
| `BackupManager.java` | Auto-backup. SQLite: VACUUM INTO. MySQL: YML/JSON export. |
| `MessageManager.java` | i18n from `messages.yml` (loaded from `getConfigDir()`). `&` color codes. |
| `PeachPlaceholder.java` | PlaceholderAPI expansion. Reads from AttributeModifier (no DB call). |
| `PeachIntegrationAPI.java` | Public API for other plugins (battle disable/restore, clear modifiers). |

## Resources

- `plugin.yml` — resource filtering on (`${project.version}` substituted). `api-version: 1.13`. MySQL/HikariCP via `libraries` (auto-downloaded by server, NOT bundled in jar).
- `config.yml` — `shared_config_dir` at root + everything else under `settings:`.
- `messages.yml` — all user-facing strings. `&` color codes.

## Key patterns & gotchas

### Shared config directory (network servers)

`shared_config_dir` points to a directory shared by all servers (NFS etc.). When set:

- `getConfig()`/`reloadConfig()`/`saveConfig()` are overridden to read/write `shared_config_dir/config.yml` transparently — **all existing `getConfig()` call sites keep working**.
- `getConfigDir()` returns shared dir (used by `MessageManager` for `messages.yml`).
- `config_poll_interval` (seconds, 0=off) enables a timer that watches `config.yml` + `messages.yml` mtimes and hot-reloads config, messages, peaches, and re-applies modifiers when a file changes.
- Local `plugins/LuckyPeaches/config.yml` only needs `shared_config_dir` set — it acts as a pointer.
- Do NOT store `data.db` (SQLite) in the shared dir — SQLite is not safe across servers. Only config/messages are shared.

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
- `onJoin` modifier verification: DB read on async thread, modifier apply back on main thread.

### Health modifier UUIDs

Derived from `UUID.nameUUIDFromBytes("LuckyPeaches".getBytes())` and `"LuckyPeachesWorldMax"`. **Changing these strings orphans existing modifiers on live servers.**

### Database hot-switch (`/lp db`)

Switches SQLite ↔ MySQL with data migration. Sequence: save online players → read all data → create new DB → write data → replace manager → close old. **Old DB must be closed AFTER new DB is fully ready**, not before. Player snapshots (UUID/name/health) captured on main thread BEFORE the async task — never call Bukkit API from the async thread.

## Dependencies

| Plugin | Required | Scope | Notes |
|--------|----------|-------|-------|
| Paper API 1.21 | Yes | provided | Minimum `api-version: 1.13` |
| PlaceholderAPI | Optional | provided | `softdepend` in plugin.yml |
| CraftEngine | Optional | provided | Maven repo `repo.momirealms.net`. Both `craft-engine-bukkit` + `craft-engine-core`. |
| MySQL Connector 8.0.33 | Optional | provided | Via `libraries` auto-download |
| HikariCP 5.1.0 | Optional | provided | Same as above |

All deps are `provided` scope — the server supplies them at runtime via `libraries` in plugin.yml.

## Commands and permissions

Main: `/luckypeach` (aliases: `/lp`, `/luckyp`). Requires `luckypeach.admin`.

Permissions: `luckypeaches.maxhealth.<key>` (VIP health caps), `luckypeaches.deathpenalty.<key>` (death penalty groups).

## Documentation

`README.md` and `API使用文档.md` are in Chinese. The API doc covers `PeachIntegrationAPI` usage with code examples for 1v1 duels, team duels, guild wars, and arenas.
