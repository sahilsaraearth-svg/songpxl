# Svara App - Fix Tasks

## CRITICAL - Crash Investigation

### Confirmed:
- `uu6` = `PlayerViewModel$special$$inlined$map$6$2$1` (map lambda continuation)
- `mm8` = `com.google.gson.internal.sql.SqlTypesSupport` (Gson internal class)
- The crash happens in the collect/emit chain for `map$6` in PlayerViewModel
- Source line 1079: `val libraryTabsFlow: StateFlow<List<String>> = userPreferencesRepository.libraryTabsOrderFlow`
- The collect body for this StateFlow at line 1744: `combine(libraryTabsFlow, lastLibraryTabIndexFlow)`

### The NPE Pattern:
- `mm8.getValue()` on null — mm8 is SqlTypesSupport, a Gson class
- Gson's SqlTypesSupport has a static `SUPPORTED` instance that is null on Android (no java.sql)
- When Gson tries to use `SqlTypesSupport.SUPPORTED` for SQL type detection, it crashes
- **This is triggered when Gson tries to serialize/deserialize something**
- The crash is in a coroutine that's part of the PlayerViewModel scope

### Where Gson Is Used In This Path:
- DailyMixManager uses `Gson` for legacy JSON migration
- `SyncWorker` uses Gson for TelegramData
- The crash happens during coroutine execution in PlayerViewModel's map$6 collect

### Root Cause Hypothesis:
The Gson crash (`SqlTypesSupport.SUPPORTED` is null) happens when Gson tries to register
its SQL TypeAdapters on Android where java.sql is not available. This can happen when
Gson is called on a thread/coroutine where the static initializer hasn't completed safely.

However, this doesn't connect directly to `libraryTabsFlow.map`.

### Alternative: Gson 2.14.0 Bug
Gson 2.14.0 changed `SqlTypesSupport` handling. On Android API < 26, `java.sql.Timestamp`
exists but `java.sql.Date.valueOf()` may not. Or in ProGuard/R8 release builds,
the reflection in SqlTypesSupport could NPE because classes are stripped.

### MOST LIKELY ROOT CAUSE:
The Gson SqlTypesSupport.SUPPORTED field is initialized via reflection:
```java
static {
    SUPPORTED = SqlTypesSupport.isAvailable() ? new SqlTypesSupport() : null;
}
```
When R8 strips the java.sql reflection classes, SUPPORTED is null.
Then somewhere Gson calls `SUPPORTED.getValue()` (or similar method on the TypeAdapter factory)
without null checking.

This happens in: **DailyMixManager.readLegacyEngagementsLocked()** which calls
`gson.fromJson(raw, JsonElement::class.java)` — this is the Gson.fromJson() path
that could trigger SqlTypesSupport if the JSON contains date-like values.

### Fix Plan:
1. **Add Gson ProGuard keep rule for SqlTypesSupport** OR
2. **Upgrade/downgrade Gson** to avoid the Android R8 compatibility issue OR
3. **Wrap all Gson.fromJson() calls in try/catch(Throwable)** (not just Exception) OR
4. **Remove Gson dependency from DailyMixManager** — migrate to kotlinx.serialization

### Also check: PlayerViewModel.kt line 1103
`currentLibraryTabId.map { tabId ->` is map$7 in the mapping
So map$6 = the `libraryTabsFlow.map { orderJson -> ... }` lambda (line 1079-1090)
The `availableSortOptionsMapping` at line 1103 also has Trace.beginSection calls

## Build Info
- Package: com.svara.music
- Version: 1.1.2 (code 13)
- DB: version 41, fallbackToDestructiveMigration
- Keystore: vz-playpix.jks, alias songpxl, pass songpxl123
- GitHub release: 323974021 (v1.1.2)

## TODO
- [x] Understand codebase
- [x] Investigate crash (uu6/mm8 identified)
- [ ] **FIX: Gson SqlTypesSupport NPE** - Add R8 keep rule or fix Gson usage
- [ ] Build new APK after fix
- [ ] Upload to GitHub release v1.1.2
