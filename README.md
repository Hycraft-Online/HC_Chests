# HC_Chests

Database-driven chest loot system with level-aware wilderness drops and persistent player storage. Manages chest state through an in-memory cache backed by PostgreSQL, with periodic flushing and configurable eviction. Filters worldgen chests in newly generated wilderness chunks and integrates with faction claim systems to distinguish wilderness from claimed territory.

## Features

- Persistent chest state stored in PostgreSQL with write-back caching
- Configurable cache flush interval and idle eviction age
- Worldgen chest filtering for newly generated wilderness chunks via `ChunkPreLoadProcessEvent`
- Faction-aware chest type determination (wilderness vs. claimed) with soft dependency on HC_Factions
- WildernessRegen integration to invalidate cache and database entries when chunks regenerate
- Level-gated loot generation using HC_Leveling and HC_DropLists
- Admin command: `/chestscan`

## Dependencies

- HC_Core
- EntityModule (Hytale built-in)
- HC_Leveling
- HC_DropLists

### Optional

- HC_Factions -- claim-based chest type determination
- HC_WildernessRegen -- cache invalidation on chunk regeneration

## Building

```
./gradlew build
```
