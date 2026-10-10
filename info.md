# info.md

## What this is

Single Spigot/Paper backend plugin (Java 17, Maven). Eating peach items permanently boosts max health via `AttributeModifier`. Features: multi-world isolation, death penalty, SQLite/MySQL dual-database, PlaceholderAPI, CraftEngine custom models, health scaling, shared config directory for network servers.

The former `LuckyPeaches-Proxy` (Velocity config sync via MySQL) was removed — replaced by a **shared config directory** (`shared_config_dir`) pattern copied from mcskills: all servers read the same `config.yml` / `messages.yml` / `peaches.yml`, mtime polling auto-reloads on change. Cross-server player health is synced through MySQL (async, fallback) and optionally through a Redis snapshot for seamless server switching.

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
| `LuckyPeaches.java` | Entrypoint. **Overrides `getConfig()`/`reloadConfig()`/`saveConfig()`** to redirect to shared dir when `shared_config_dir` is set. `startConfigPollTask()` polls file mtimes. Also hosts `runAsync/runSync/runAsyncLater/runSyncLater` scheduler helpers. |
| `PeachListener.java` | Core logic — join/quit/interact/death/world-change handlers. `clearRuntimeState()` wipes the static per-player collections on disable. `refreshWorldStateForOnlinePlayers()` re-evaluates disabled-world / world-max state after a config reload. |
| `PeachManager.java` | Peach item creation, CraftEngine integration with vanilla fallback. Clamps `chance` to `[0,1]`, `health_bonus >= 0`, giveaway amount ≤ `MAX_GIVE_AMOUNT`. |
| `RedisSyncManager.java` | Optional cross-server health snapshot over Redis (Jedis). Written on quit/eat/death/sethealth + periodic flush; fetched in `AsyncPlayerPreLoginEvent`, applied synchronously in `PlayerJoinEvent`. Self-disables and falls back to DB when disabled/unreachable. |
| `DatabaseManager.java` | Dual SQLite/MySQL. `executeQuery(DBAction)` callback pattern handles connection lifecycle. `initialize()` returns `false` when the DB is unusable. `isMysql()` is the single type accessor. |
| `PeachCommand.java` | All `/luckypeach` subcommands including `/luckypeach db` hot-switch and `/luckypeach clearhealth`. |
| `BackupManager.java` | Auto-backup. SQLite: VACUUM INTO. MySQL: YML/JSON export. Re-entrancy guarded by an `AtomicBoolean`; interval clamped to ≥ 1h. |
| `MessageManager.java` | i18n from `messages.yml` (loaded from `getConfigDir()`). `&` color codes. Caches the colored prefix. |
| `PeachPlaceholder.java` | PlaceholderAPI expansion. Reads from AttributeModifier (no DB call). |
| `HealthModifierUtil.java` | Single place for `AttributeModifier` lookup/remove/apply. Owns the two modifier UUID constants (aliased by `PeachListener`). |
| `PeachIntegrationAPI.java` | Public API for other plugins (battle disable/restore, clear modifiers). Works on a battle **flag**, not on modifiers. |

## Resources

- `plugin.yml` — resource filtering on (`${project.version}` substituted). `api-version: 1.13`. MySQL/HikariCP via `libraries` (auto-downloaded by server, NOT bundled in jar).
- `config.yml` — `shared_config_dir` at root + everything else under `settings:`; optional `redis:` block for cross-server health sync.
- `messages.yml` — all user-facing strings. `&` color codes.
- `peaches.yml` — peach definitions (split out of `config.yml`); legacy `config.yml:peaches` is auto-migrated on first load.

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
- `onJoin` modifier verification: DB read on async thread, modifier apply back on main thread. When Redis is enabled, `onPreLogin` (async) fetches the snapshot and `onJoin` applies it synchronously on the main thread, then returns (no async DB round trip); without a snapshot it falls back to `loadCompletePlayerData` (bonus **and** `current_health`) applied on the main thread.
- `RedisSyncManager.storePlayer` / `peach_bonus` snapshotting must run on the main thread (reads attributes). Quit writes are synchronous on purpose (sub-ms) so the next server can read the fresh value.
- Use the `plugin.runAsync/runSync/runAsyncLater/runSyncLater` helpers instead of touching the scheduler
  directly: they skip scheduling while the plugin is disabled (avoids `IllegalStateException` during shutdown)
  and keep every call site consistent.
- Config writes (`getConfig().set(...)` + `saveConfig()` + `reloadConfig()`) must stay on the main thread —
  `handleDatabaseSwitch` does its migration async but returns to the main thread for the config/manager swap.
- `getConfig()` returns a `volatile` field so async readers (backup, commands) see a hot-reloaded config.

### Static state (leak-sensitive)

`PeachListener` keeps five `static` per-player collections (`playersInDisabledWorld`, `playersMaxHealthWorld`,
`lastDeathTime`, `eatingPlayers`, `pendingDeathPenalty`) and `PeachIntegrationAPI` keeps `playersInBattle`.
Entries are removed on quit, but a task cancelled at plugin shutdown never reaches its `finally`, so
`onDisable()` calls `PeachListener.clearRuntimeState()` + `PeachIntegrationAPI.clearAllBattleStatus()` and
nulls the static `instance`. **Any new static collection must be cleared there too.**

### Known limitations (reviewed, deliberately not changed)

- **Multi-server MySQL `peach_bonus` increment is read-modify-write**, not an atomic
  `UPDATE ... SET peach_bonus = peach_bonus + ?`. A player is only ever on one backend at a time, so the race
  window is limited to a delayed quit-save (~1s) overlapping a fast re-login on another server. Making it
  atomic needs dialect-specific UPSERT SQL that cannot be tested in this environment, so it is left as-is
  (the failure mode of shipping wrong SQL — every eat failing — is worse than the rare lost increment).
- `current_health` is the cross-server source of truth for current health and IS read on join now: with Redis
  via the `health` snapshot field, without Redis via `loadCompletePlayerData(...).getCurrentHealth()` in the
  main-thread completion of the join handler. It is still maintained through `updateCurrentHealth()` (never
  rewrites `peach_bonus`). `RedisSyncManager` guards against storing `0` (dead players store their max instead).
- The death-penalty cooldown IS persisted now (`<prefix>peach_death_cooldown` table, written by
  `saveDeathPenalty`, read by `getLastPenaltyMs`) so a "fake death" (client stuck in the death state)
  followed by a server switch cannot be charged twice inside one cooldown window. The in-memory
  `lastDeathTime` map stays as a same-server fast path. `getLastPenaltyMs` fails soft (returns 0) if the
  table is missing, so the cooldown can never break normal player-data access.
- Set `death_cooldown_ms` above your typical switch time (e.g. 15000) for the fake-death case to be
  covered; the shipped default (5000) only covers fast transfers.
- `onDisable`'s `saveAllOnlinePlayers()` does synchronous DB I/O on the main thread on purpose, so no player
  data is lost at shutdown. Everything else (`/luckypeach backup now`, backups, quit-saves, eat/penalty writes) runs
  off the tick thread.
- Delayed tasks (quit-save, world-exit restore) are cancelled by Bukkit when the plugin disables. Bonuses are
  unaffected (they live in the DB); `saveAllOnlinePlayers()` covers online players at shutdown.

### Cross-server health sync (optional Redis)

`RedisSyncManager` is a **cache, never the source of truth**. The database remains authoritative for
`peach_bonus`; Redis only carries a fresh snapshot so the health bar can be restored synchronously in
`PlayerJoinEvent`.

- `onJoin` applies the snapshot (if any) for an instant visual, then **always** runs an async
  `loadCompletePlayerData` reconciliation. A stale/lost/expired snapshot is corrected back to the DB value
  and the corrected value is re-published to Redis.
- `PeachListener.markBonusChanged(uuid)` is set after every locally-persisted bonus change (eat / death
  penalty / `sethealth`). The join reconciliation skips its correction if a local change happened after it
  started reading, so a stale DB read can never overwrite a just-eaten bonus.
- Any Redis failure is swallowed and logged; after 3 consecutive failures the manager suspends itself for
  30 s and all callers fall back to the DB path (no main-thread blocking, no data loss).
- Quit writes Redis synchronously (sub-ms) and re-publishes after the delayed DB save succeeds, so the
  snapshot converges with the DB even if the first write failed.
- Ownership changed: `current_health` is no longer vestigial — it is read on join when no snapshot exists.
  Guard: values `<= 0` (missing row / death-time write) are ignored, so new players are not clamped to 1 HP.

### Non-peach health safety (other plugins / base health)

The plugin only owns the peach modifier + current health. To stop cross-server sync from destroying health that
belongs to other plugins:

- **Deferred top-up** (`settings.join_health_restore_delay_ticks`, default 40): other plugins' max-health
  modifiers are often applied after our `PlayerJoinEvent`. If the recorded health was clamped by the then-lower
  max, `restoreHealthWithPending` schedules a delayed `reconcileRestoredHealth` that raises health back to the
  recorded value once the max grows. `PeachListener.onExternalDamage` (MONITOR) marks the player on real
  damage, and the reconcile then refuses to top up — so damage is never healed away.
- **Base health is no longer force-overwritten**: it is only set when the current base is the vanilla default
  (20) unless `settings.force_base_max_health: true`. Otherwise another plugin's `setBaseValue` survives.
- **Quit/shutdown no longer heal suppressed players**: `PeachIntegrationAPI.restoreSuppressedBonusKeepingHealth`
  restores the modifier but keeps the real health (the normal `setPeachBonusSuppressed(false)` heals to full,
  which is only appropriate when a battle ends).
- **Disabled worlds**: `preDisabledWorldHealth` records the pre-entry health; on exit it is restored (or fully
  healed per `restore_full_health_on_exit`), unless real damage happened in the meantime
  (`healthModifiedExternally`).
- **World max health** now computes the needed delta as `configured - (getValue() - currentWorldModifier)`,
  so peach/other modifiers no longer push the total past the configured world cap.
- Dead players are stored at max instead of `0` (`saveAllOnlinePlayers`, quit path), avoiding a `0` row that the
  join guard must ignore.

### Optional: delegate cross-server health to SparrowSync

`sparrow_sync_delegate_health: true` (with SparrowSync installed) makes LuckyPeaches stop being the health
transport and become the **authority + backup database** for `peach_bonus` only:

- Redis is not initialized (`getRedisSyncManager()` stays `null`; `onPreLogin`/quit/flush are no-ops).
- `onJoin` reconciliation still corrects the `LuckyPeaches` modifier from the DB (no-op if SparrowSync already
  restored it) but **never writes `current_health`** — the health that SparrowSync restored is left untouched.
- The disabled-world join branch also skips its `setHealthQuietly`.
- The DB keeps receiving `peach_bonus` + `current_health` (backup).
- Requires SparrowSync `data-types.health`/`attributes` = true; blacklist `LuckyPeachesWorldMax` (world-local).
- If SparrowSync is absent the flag is ignored with a warning and the normal Redis/DB path runs.

`SparrowSync` is listed in `softdepend`, so no hard dependency/class reference is needed — detection is by
plugin name only.

### Config merge gotcha

`Configuration.contains(path)` falls back to the section's `defaults`. The plugin config always has defaults
set (`setDefaults` + `copyDefaults`), so `contains(key)` returns `true` for every default key and the
"fill in missing config keys" logic silently does nothing. Always use `contains(key, true)`.
`messages.yml` is merged against a separate default file, but it uses `contains(key, true)` as well for safety.

### Health modifier UUIDs

Owned by `HealthModifierUtil` (derived from `UUID.nameUUIDFromBytes("LuckyPeaches".getBytes())` and
`"LuckyPeachesWorldMax"`), re-exported as `PeachListener.PEACH_MODIFIER_UUID` /
`WORLD_MAX_HEALTH_MODIFIER_UUID`. **Changing these strings orphans existing modifiers on live servers.**

All modifier work goes through `HealthModifierUtil.apply/remove/getAmount` (`apply` removes the old value and
skips adding when `amount <= 0`, so no zero-value modifiers are left behind). Don't hand-roll the
stream-filter-remove-add dance at new call sites.

### Database hot-switch (`/luckypeach db`)

Switches SQLite ↔ MySQL with data migration. Sequence: save online players → read all data → build the new
`DatabaseManager` with the **explicit target type** (`new DatabaseManager(plugin, useMysql)`) → write data →
back on the main thread: persist `settings.database.type`, swap the manager, close the old one async, restart
`BackupManager`, re-apply modifiers. **The config file is only written after a successful migration**, so a
failed switch leaves the old database and config untouched. Player snapshots (UUID/name/health) are captured
on the main thread BEFORE the async task — never call Bukkit API from the async thread.

### Interact handling: never gate on `isCancelled()`

`PeachListener.onInteract` deliberately does **not** return early on `event.isCancelled()`.
The server never cancels an air right-click itself (`CraftEventFactory` builds RIGHT_CLICK_AIR with
`Result.DEFAULT`; verified in the server jar bytecode), so a cancelled event can only come from another
plugin — and many plugins cancel air right-clicks globally (custom-item/skill/intercept listeners).
Respecting that made peaches edible only while aiming at a block (regression in v2.2.1, reverted in v2.2.5).
Debug mode logs the cancellation plus the list of `ignoreCancelled=false` PlayerInteractEvent listeners so the
culprit can be identified.

### Death penalty message

`messages.yml` `death_penalty` uses `%penalty%` / `%peach_health%`. The legacy `%.1f / %.1f` template is still
honoured, and a malformed template (a bare `%`) no longer throws out of the scheduled task — it is sent
verbatim with a console warning.

## Dependencies

| Plugin | Required | Scope | Notes |
|--------|----------|-------|-------|
| Paper API 1.21 | Yes | provided | Minimum `api-version: 1.13` |
| PlaceholderAPI | Optional | provided | `softdepend` in plugin.yml |
| CraftEngine | Optional | provided | Maven repo `repo.momirealms.net`. Both `craft-engine-bukkit` + `craft-engine-core`. |
| MySQL Connector 8.0.33 | Optional | provided | Via `libraries` auto-download |
| HikariCP 5.1.0 | Optional | provided | Same as above |
| Jedis 5.1.5 | Optional | provided | Redis cross-server health sync. Via `libraries` auto-download. |

All deps are `provided` scope — the server supplies them at runtime via `libraries` in plugin.yml.

## Commands and permissions

Main: `/luckypeach` (alias: `/luckyp`; `/lp` is reserved for LuckPerms). Requires `luckypeach.admin`.

Permissions: `luckypeaches.maxhealth.<key>` (VIP health caps), `luckypeaches.deathpenalty.<key>` (death penalty groups).

## Documentation

`README.md` and `API使用文档.md` are in Chinese. The API doc covers `PeachIntegrationAPI` usage with code examples for 1v1 duels, team duels, guild wars, and arenas.
