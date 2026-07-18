# AGENTS.md

## What this is

Minecraft Spigot/Paper plugin (Java 17, Maven). Eating peach items permanently boosts max health via `AttributeModifier`. Features: multi-world isolation, death penalty, SQLite persistence, PlaceholderAPI, CraftEngine custom models, health scaling.

## Build

```sh
mvn package
```

Output: `target/Liu-LuckyPeaches-<version>.jar` (custom `<finalName>` in pom.xml).

**No Maven wrapper** — requires system Maven.

**CraftEngine dep is `provided` scope** from Maven repo `https://repo.momirealms.net/releases/`. Requires both `craft-engine-bukkit` and `craft-engine-core`. GitHub: https://github.com/Xiao-MoMi/craft-engine

No tests, no CI, no linting/formatting configured.

## Source layout

All Java in `src/main/java/com/luckypeaches/` (flat package). One sub-package: `com/luckypeaches/license/`.

| File | Role |
|------|------|
| `LuckyPeaches.java` | Entrypoint (`onEnable`/`onDisable`). License check bypassed unconditionally at line 29. |
| `PeachListener.java` | Core logic — join/quit/interact/death/world-change handlers. Contains the `PEACH_MODIFIER_UUID` and `WORLD_MAX_HEALTH_MODIFIER_UUID` constants. |
| `PeachManager.java` | Peach item creation, CraftEngine integration with vanilla fallback. |
| `DatabaseManager.java` | SQLite via JDBC. Table `player_peach_health`. Migration adds `current_health` column. |
| `PeachCommand.java` | All `/lp` subcommands. |
| `BackupManager.java` | Auto-backup of SQLite DB. |
| `MessageManager.java` | i18n from `messages.yml`. |
| `PeachPlaceholder.java` | PlaceholderAPI expansion. |
| `PeachIntegrationAPI.java` | Public API for other plugins (battle disable/restore). |
| `license/LicenseManager.java` | License verification (currently bypassed in `LuckyPeaches.java:29`). |

Resources: `plugin.yml`, `config.yml`, `messages.yml` in `src/main/resources/`. Resource filtering is on — `${project.version}` in `plugin.yml` is substituted at build time. `api-version: 1.13` (minimum supported server version), compiles against Paper 1.21.

## Key patterns

- **Health modifier UUIDs** are derived from `UUID.nameUUIDFromBytes(...)` with fixed strings `"LuckyPeaches"` and `"LuckyPeachesWorldMax"`. Changing these strings would orphan existing modifiers on live servers.
- **Thread model**: DB reads async (`runTaskAsynchronously`), modifier application main-thread (`runTask`). An `eatingPlayers` Set guards against race conditions during peach consumption. DB operations are synchronized via `dbLock` in `DatabaseManager`.
- **Database**: SQLite stored at `<plugin-data-folder>/data.db`. Single table `player_peach_health` with columns: `uuid`, `username`, `peach_bonus`, `current_health`, `last_updated`.
- **Config reload**: `/lp reload` reloads `config.yml`, peach list, and `messages.yml` at runtime. World list and max-health changes via commands also persist to `config.yml` via `saveConfig()`.
- **Config keys**: Messages use `&` color codes. Peach definitions under `peaches.<id>` with fields `display_name`, `material`, `lore`, `health_bonus`, `chance`, `custom_model_data`, `craftengine_model`.

## Commands and permissions

Main command: `/luckypeach` (aliases: `/lp`, `/luckyp`). Requires `luckypeach.admin`.

Other permissions: `luckypeaches.maxhealth.<key>` (VIP health caps), `luckypeaches.deathpenalty.<key>` (death penalty groups).

## Dependencies

| Plugin | Required | Notes |
|--------|----------|-------|
| Paper API 1.21 | Yes | `provided` scope |
| PlaceholderAPI | Optional | `provided`, `softdepend` |
| CraftEngine | Optional | `provided`, Maven repo `repo.momirealms.net` |
