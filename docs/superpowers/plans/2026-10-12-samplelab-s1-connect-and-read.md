# Sample Lab Integration S1 (Connect and Read Metadata) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user pair the app with a real Sample Lib server, sync the server library, cues and loops into a durable local cache, and browse them, with a Sync screen that states which server capabilities are and are not available.

**Architecture:** A pure-Kotlin sync stack (profile store, SQL-backed `LibraryCache`, `SampleLibSyncEngine`, `SampleLibSessionManager`) sits on the already-verified `SampleLibClient`. Static provider wrappers (composite library, server preparation, server-aware playback, sync) are installed once in `AndroidDjToolsActivity` and change content as pairing state changes. The SQL is behind a two-method interface so the exact SQL runs in JVM tests via a test-only JDBC driver.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx-coroutines (+test), kotlinx-serialization-json, OkHttp, WorkManager, `SQLiteOpenHelper` (production), `org.xerial:sqlite-jdbc` (test only), JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (stage S1). S2 to S5 each get their own plan after S1 ships.

## Deviations from the spec (reviewer: please confirm)

1. **Test-only dependency `org.xerial:sqlite-jdbc`** plus a `SqlDatabase` interface. The spec says "plain SQLite via `SQLiteOpenHelper`"; production still is. The interface exists only so the real SQL is verifiable on the JVM (there is no Robolectric or device in this environment).
2. **Device tracks stay playable and visible after pairing.** The spec says a paired track plays from the device when its content hash matches a local file. Hashing device files is a separate expensive feature, so S1 does not dedupe: the library shows server tracks plus device tracks, server tracks are not playable and say why, and hash matching moves to a follow-up after S2 (server audio does not depend on it).
3. **Playback notice** (`PlaybackProvider.playbackNotice`) is a small additive interface member needed so tapping an unplayable server track is never a silent no-op.
4. **The cache is cleared as soon as the server's authority generation changes or a pull cursor expires**, before the fresh snapshot is pulled, instead of staying readable until the new snapshot commits. A correct staged swap needs its own schema design; S1 chooses never to show rows from a previous authority generation. Consequence: a brief empty library (and an empty library if the first pull fails offline) after such a reset. A separate follow-up after S2.
5. `authority_generation` and the cursor live in `CacheMeta`, not in the profile store, as an undeclared-but-sensible detail.

## Global Constraints

Copied from the spec; every task implicitly includes these.

- Credential and pairing code are never logged, put in provenance, or included in diagnostics.
- Credential encrypted at rest (Keystore); excluded from backup; deleted on "Forget server".
- Non-loopback hosts must use HTTPS (the transport already enforces this).
- The cursor advances in the same transaction that commits the batch.
- `cursor_expired` or an authority-generation change forces a fresh snapshot and is never treated as an empty delta.
- The cache is disposable: a schema bump or reset drops it.
- `minSdk = 28` (Android's SQLite 3.22 has no `UPSERT`; use `INSERT OR REPLACE`).
- No new annotation processors or build plugins.
- Evidence honesty: anything needing a physical device or a real library with audio stays "blocked" in `docs/parity/parity.json`; do not edit that ledger in S1.

## Shell setup (every gradle command below assumes this)

```bash
cd /home/user/code/androidjtools
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=/opt/android-sdk GRADLE_USER_HOME=$PWD/.gradle-local
```

Run Task 1 Step 3 **without** `--offline` once, to download `sqlite-jdbc`. Afterwards `--offline` works.

## Review Focus

Failure modes the spec implies but no happy-path test would catch, most likely first. Each has a test in the task named.

1. **Server returns entities the app does not understand** (malformed JSON, `null` fields, a bad `color`, unknown entity types or schemas): they are stored but must never crash the projection (Task 2).
2. **Server address variants** (surrounding whitespace, upper-case scheme or host, trailing slash, reverse-proxy path prefix `https://host/samplelab`, query or credentials in the URL): normalized or rejected with a clear message, and the path prefix survives into every request (Task 4).
3. **Device permission denied while paired** must not hide the server library behind the device provider's `Error` state (Task 7).
4. **Forget while a sync is in flight** must leave no profile, credential or cached rows behind (Task 6).
5. **Unreadable stored credential** (Keystore key invalidated, file corrupt) must show "pair again" and never crash or retry blindly (Tasks 3 and 6).

---

### Task 1: SQL abstraction and `LibraryCache`

**Files:**
- Modify: `app/build.gradle.kts` (add one `testImplementation`)
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SqlDatabase.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/LibraryCache.kt`
- Create: `app/src/test/java/dev/androidjtools/sync/samplelib/JdbcSqlDatabase.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/LibraryCacheTest.kt`

**Interfaces:**
- Consumes: `EntityChange`, `Tombstone`, `OpaqueRevision` from `dev.androidjtools.remote.samplelib`.
- Produces (later tasks rely on these exact names):
  - `interface SqlRow { fun string(index: Int): String?; fun long(index: Int): Long }`
  - `interface SqlConnection { fun execute(sql: String, vararg args: Any?); fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> }`
  - `interface SqlDatabase { fun <T> transaction(block: (SqlConnection) -> T): T }`
  - `data class CachedEntity(val entityType: String, val entityId: String, val revision: String, val schema: String, val valueJson: String)`
  - `data class CacheMeta(val cursor: String? = null, val authorityGeneration: String? = null, val serverId: String? = null)`
  - `interface LibraryCache { val changes: StateFlow<Long>; fun meta(): CacheMeta; fun applyPage(changes: List<EntityChange>, tombstones: List<Tombstone>, meta: CacheMeta); fun entities(entityType: String): List<CachedEntity>; fun reset() }`
  - `class SqlLibraryCache(db: SqlDatabase) : LibraryCache`
  - Test helpers: `class JdbcSqlDatabase : SqlDatabase, AutoCloseable` (in-memory SQLite).

- [ ] **Step 1: Add the test-only dependency**

In `app/build.gradle.kts`, inside `dependencies { ... }` next to the other `testImplementation` lines add:

```kotlin
    testImplementation("org.xerial:sqlite-jdbc:3.46.1.3")
```

- [ ] **Step 2: Write the SQL interfaces and the JDBC test driver**

`app/src/main/java/dev/androidjtools/sync/samplelib/SqlDatabase.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

interface SqlRow {
    fun string(index: Int): String?
    fun long(index: Int): Long
}

interface SqlConnection {
    fun execute(sql: String, vararg args: Any?)
    fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T>
}

interface SqlDatabase {
    /** Runs [block] atomically: commits when it returns, rolls back when it throws. */
    fun <T> transaction(block: (SqlConnection) -> T): T
}
```

`app/src/test/java/dev/androidjtools/sync/samplelib/JdbcSqlDatabase.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/** In-memory SQLite used only by JVM tests so the production SQL runs against a real engine. */
class JdbcSqlDatabase : SqlDatabase, AutoCloseable {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    override fun <T> transaction(block: (SqlConnection) -> T): T = synchronized(this) {
        connection.autoCommit = false
        try {
            val result = block(JdbcConnection(connection))
            connection.commit()
            result
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()
}

private class JdbcConnection(private val connection: Connection) : SqlConnection {
    override fun execute(sql: String, vararg args: Any?) {
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeUpdate()
        }
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, args)
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(map(JdbcRow(rs))) }
            }
        }

    private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
    }
}

private class JdbcRow(private val rs: ResultSet) : SqlRow {
    override fun string(index: Int): String? = rs.getString(index + 1)
    override fun long(index: Int): Long = rs.getLong(index + 1)
}
```

- [ ] **Step 3: Write the failing cache tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/LibraryCacheTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.Tombstone
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LibraryCacheTest {
    private val db = JdbcSqlDatabase()

    @After
    fun close() = db.close()

    private fun change(type: String, id: String, revision: String, title: String = id) =
        EntityChange(type, id, OpaqueRevision(revision), "sample-lib.$type/v1", buildJsonObject { put("title", title) })

    private fun tombstone(type: String, id: String) = Tombstone(type, id, OpaqueRevision("t"), Instant.EPOCH)

    @Test
    fun emptyCacheHasNoMetaAndNoEntities() {
        val cache = SqlLibraryCache(db)
        assertEquals(CacheMeta(), cache.meta())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun applyPageStoresEntitiesAndMetaTogether() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(
            listOf(change("asset", "a1", "r1", "Alpha"), change("interval", "i1", "r1")),
            emptyList(),
            CacheMeta("cursor-1", "gen-1", "server-1"),
        )
        assertEquals(CacheMeta("cursor-1", "gen-1", "server-1"), cache.meta())
        val asset = cache.entities("asset").single()
        assertEquals("a1", asset.entityId)
        assertEquals("r1", asset.revision)
        assertEquals("sample-lib.asset/v1", asset.schema)
        assertTrue(asset.valueJson.contains("Alpha"))
        assertEquals(listOf("i1"), cache.entities("interval").map { it.entityId })
    }

    @Test
    fun newerRevisionReplacesTheRowInsteadOfDuplicatingIt() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(listOf(change("asset", "a1", "r1", "Old")), emptyList(), CacheMeta("c1", "g", "s"))
        cache.applyPage(listOf(change("asset", "a1", "r2", "New")), emptyList(), CacheMeta("c2", "g", "s"))
        val rows = cache.entities("asset")
        assertEquals(1, rows.size)
        assertEquals("r2", rows.single().revision)
        assertTrue(rows.single().valueJson.contains("New"))
    }

    @Test
    fun tombstoneRemovesOnlyTheNamedEntity() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(
            listOf(change("asset", "a1", "r1"), change("asset", "a2", "r1")),
            emptyList(),
            CacheMeta("c1", "g", "s"),
        )
        cache.applyPage(emptyList(), listOf(tombstone("asset", "a1")), CacheMeta("c2", "g", "s"))
        assertEquals(listOf("a2"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun failureMidPageRollsBackEntitiesTombstonesAndCursor() {
        val failing = FailingSqlDatabase(db)
        val cache = SqlLibraryCache(failing)
        cache.applyPage(listOf(change("asset", "a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))

        failing.failOnNthExecute(2)
        try {
            cache.applyPage(
                listOf(change("asset", "a2", "r1"), change("asset", "a3", "r1")),
                listOf(tombstone("asset", "a1")),
                CacheMeta("c2", "g", "s"),
            )
            fail("expected the injected failure")
        } catch (expected: IllegalStateException) {
        }

        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertEquals("c1", cache.meta().cursor)
    }

    @Test
    fun resetClearsEntitiesAndMetaAndBumpsChanges() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(listOf(change("asset", "a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))
        val before = cache.changes.value
        cache.reset()
        assertTrue(cache.entities("asset").isEmpty())
        assertEquals(CacheMeta(), cache.meta())
        assertTrue(cache.changes.value > before)
    }

    @Test
    fun applyPageBumpsChangesOncePerCommit() {
        val cache = SqlLibraryCache(db)
        val start = cache.changes.value
        cache.applyPage(listOf(change("asset", "a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))
        assertEquals(start + 1, cache.changes.value)
    }

    @Test
    fun staleSchemaVersionDropsOldRowsOnOpen() {
        db.transaction { conn ->
            conn.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
            conn.execute("INSERT INTO meta (key, value) VALUES ('schema_version', '0')")
            conn.execute(
                "CREATE TABLE entities (entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, revision TEXT NOT NULL, " +
                    "schema_id TEXT NOT NULL, value_json TEXT NOT NULL, PRIMARY KEY (entity_type, entity_id))",
            )
            conn.execute("INSERT INTO entities VALUES ('asset', 'old', 'r', 's', '{}')")
        }
        val cache = SqlLibraryCache(db)
        assertTrue(cache.entities("asset").isEmpty())
        assertNull(cache.meta().cursor)
    }

    @Test
    fun aSecondInstanceOnTheSameDatabaseSeesCommittedData() {
        SqlLibraryCache(db).applyPage(listOf(change("asset", "a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))
        val reopened = SqlLibraryCache(db)
        assertEquals(listOf("a1"), reopened.entities("asset").map { it.entityId })
        assertEquals("c1", reopened.meta().cursor)
    }
}

/** Delegates to a real database but throws on a chosen execute, to prove transactions roll back. */
class FailingSqlDatabase(private val delegate: SqlDatabase) : SqlDatabase {
    private var countdown = 0

    /** The [n]th execute from now throws; n = 1 fails the very next execute. */
    fun failOnNthExecute(n: Int) {
        countdown = n
    }

    override fun <T> transaction(block: (SqlConnection) -> T): T =
        delegate.transaction { real -> block(FailingConnection(real)) }

    private inner class FailingConnection(private val real: SqlConnection) : SqlConnection {
        override fun execute(sql: String, vararg args: Any?) {
            if (countdown > 0) {
                countdown--
                if (countdown == 0) throw IllegalStateException("injected failure")
            }
            real.execute(sql, *args)
        }

        override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
            real.query(sql, *args, map = map)
    }
}
```

- [ ] **Step 4: Run the tests to verify they fail**

Run (online, first time only, so Gradle can fetch `sqlite-jdbc`):

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.LibraryCacheTest' --console=plain
```

Expected: FAIL to compile with `Unresolved reference: SqlLibraryCache` (and `CacheMeta`).

- [ ] **Step 5: Implement the cache**

`app/src/main/java/dev/androidjtools/sync/samplelib/LibraryCache.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.Tombstone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CachedEntity(
    val entityType: String,
    val entityId: String,
    val revision: String,
    val schema: String,
    val valueJson: String,
)

data class CacheMeta(
    val cursor: String? = null,
    val authorityGeneration: String? = null,
    val serverId: String? = null,
)

/** Disposable read cache of the server's pulled entities. Never an authority. */
interface LibraryCache {
    /** Increments after every committed page or reset; collectors recompute their projections. */
    val changes: StateFlow<Long>

    fun meta(): CacheMeta

    /** Atomically applies upserts, deletions and the new [meta] (including the cursor). */
    fun applyPage(changes: List<EntityChange>, tombstones: List<Tombstone>, meta: CacheMeta)

    fun entities(entityType: String): List<CachedEntity>

    /** Drops every entity and the sync metadata; the schema version marker is kept. */
    fun reset()
}

class SqlLibraryCache(private val db: SqlDatabase) : LibraryCache {
    private val version = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = version.asStateFlow()

    init {
        db.transaction { conn ->
            conn.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
            val stored = conn.query("SELECT value FROM meta WHERE key = ?", KEY_SCHEMA) { it.string(0) }.firstOrNull()
            if (stored != SCHEMA_VERSION) {
                conn.execute("DROP TABLE IF EXISTS entities")
                conn.execute("DELETE FROM meta")
                conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", KEY_SCHEMA, SCHEMA_VERSION)
            }
            conn.execute(
                "CREATE TABLE IF NOT EXISTS entities (" +
                    "entity_type TEXT NOT NULL, entity_id TEXT NOT NULL, revision TEXT NOT NULL, " +
                    "schema_id TEXT NOT NULL, value_json TEXT NOT NULL, PRIMARY KEY (entity_type, entity_id))",
            )
        }
    }

    override fun meta(): CacheMeta = db.transaction { conn ->
        val values = conn.query("SELECT key, value FROM meta") { it.string(0) to it.string(1) }.toMap()
        CacheMeta(values[KEY_CURSOR], values[KEY_GENERATION], values[KEY_SERVER])
    }

    override fun applyPage(changes: List<EntityChange>, tombstones: List<Tombstone>, meta: CacheMeta) {
        db.transaction { conn ->
            changes.forEach { change ->
                conn.execute(
                    "INSERT OR REPLACE INTO entities (entity_type, entity_id, revision, schema_id, value_json) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    change.entityType,
                    change.entityId,
                    change.revision.value,
                    change.schema,
                    change.value.toString(),
                )
            }
            tombstones.forEach { tombstone ->
                conn.execute(
                    "DELETE FROM entities WHERE entity_type = ? AND entity_id = ?",
                    tombstone.entityType,
                    tombstone.entityId,
                )
            }
            writeMeta(conn, KEY_CURSOR, meta.cursor)
            writeMeta(conn, KEY_GENERATION, meta.authorityGeneration)
            writeMeta(conn, KEY_SERVER, meta.serverId)
        }
        version.update { it + 1 }
    }

    override fun entities(entityType: String): List<CachedEntity> = db.transaction { conn ->
        conn.query(
            "SELECT entity_id, revision, schema_id, value_json FROM entities WHERE entity_type = ? ORDER BY entity_id",
            entityType,
        ) { row ->
            CachedEntity(entityType, row.string(0).orEmpty(), row.string(1).orEmpty(), row.string(2).orEmpty(), row.string(3).orEmpty())
        }
    }

    override fun reset() {
        db.transaction { conn ->
            conn.execute("DELETE FROM entities")
            conn.execute("DELETE FROM meta WHERE key <> ?", KEY_SCHEMA)
        }
        version.update { it + 1 }
    }

    private fun writeMeta(conn: SqlConnection, key: String, value: String?) {
        if (value == null) {
            conn.execute("DELETE FROM meta WHERE key = ?", key)
        } else {
            conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", key, value)
        }
    }

    private companion object {
        const val SCHEMA_VERSION = "1"
        const val KEY_SCHEMA = "schema_version"
        const val KEY_CURSOR = "cursor"
        const val KEY_GENERATION = "authority_generation"
        const val KEY_SERVER = "server_id"
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.LibraryCacheTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 9 tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/dev/androidjtools/sync/samplelib app/src/test/java/dev/androidjtools/sync/samplelib
git commit -m "feat(samplelib): add SQL-backed library cache with atomic cursor commit

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Library projection (server entities to `Track`, `Cue`, `Loop`)

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/LibraryProjection.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/LibraryProjectionTest.kt`

**Interfaces:**
- Consumes: `CachedEntity` (Task 1); `Track`, `Cue`, `Loop` from `dev.androidjtools.core.model`.
- Produces:
  - `data class LibraryProjection(val tracks: List<Track>, val cues: Map<String, List<Cue>>, val loops: Map<String, List<Loop>>)` with `companion val EMPTY` and `fun hasTrack(id: String): Boolean`
  - `object LibraryProjector { fun project(assets: List<CachedEntity>, intervals: List<CachedEntity>): LibraryProjection }`

Mapping rules (from the real wire shapes recorded in the spec's evidence):
- Track from `asset`: `title` (fallback `original_filename`, then id), `artist` (fallback `"Unknown artist"`), `album` = `collection`, `durationMs` = `duration_ms`, `rating`. `bpm` and `key` come from the asset's **full-span interval** (`start_ms == 0` and `end_ms >= duration_ms`), else `null`.
- Cues from the interval aggregate's `markers`: `slice` becomes role `"slice"`, `custom` becomes role `"cue"`, `loop_start`/`loop_end` are never cues. `color` `#RRGGBB` becomes `0xFFRRGGBB`; anything else becomes `null`.
- Loops: an interval with `loopable == 1`, plus each `loop_start` marker paired with the next later `loop_end` marker in the same interval (unpaired markers are ignored).
- `BeatGrid` stays unsupported in S1 (the server only sends an opaque `beatgrid_ref`).

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.androidjtools.sync.samplelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProjectionTest {
    private fun asset(id: String, json: String) = CachedEntity("asset", id, "r", "sample-lib.asset/v1", json)
    private fun interval(id: String, json: String) = CachedEntity("interval", id, "r", "sample-lib.interval-editor-state/v1", json)

    private fun intervalJson(
        assetId: String,
        start: Long = 0,
        end: Long = 10_000,
        extra: String = "",
        markers: String = "",
    ) = """{"interval":{"id":"i","asset_id":"$assetId","label":"Loop","start_ms":$start,"end_ms":$end$extra},"markers":[$markers],"revision":"r"}"""

    private fun marker(id: String, pos: Long, type: String, color: String? = "#112233") =
        """{"id":"$id","interval_id":"i","position_ms":$pos,"label":"$id","marker_type":"$type","color":${color?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun assetBecomesTrackWithTempoAndKeyFromTheFullSpanInterval() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"Alpha","artist":"Ann","collection":"LP","duration_ms":10000,"rating":4}""")),
            listOf(interval("i1", intervalJson("a1", extra = ""","bpm":128.0,"key_label":"8A""""))),
        )
        val track = projection.tracks.single()
        assertEquals("a1", track.id)
        assertEquals("Alpha", track.title)
        assertEquals("Ann", track.artist)
        assertEquals("LP", track.album)
        assertEquals(10_000L, track.durationMs)
        assertEquals(4, track.rating)
        assertEquals(128.0, track.bpm!!, 0.0)
        assertEquals("8A", track.key)
        assertTrue(projection.hasTrack("a1"))
    }

    @Test
    fun partialIntervalDoesNotSupplyTrackTempo() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"Alpha","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", start = 1000, end = 4000, extra = ""","bpm":90.0"""))),
        )
        assertNull(projection.tracks.single().bpm)
    }

    @Test
    fun missingTitleAndArtistFallBackWithoutFailing() {
        val projection = LibraryProjector.project(
            listOf(
                asset("a1", """{"original_filename":"take1.wav","duration_ms":5}"""),
                asset("a2", """{"duration_ms":5}"""),
            ),
            emptyList(),
        )
        assertEquals(listOf("take1.wav", "a2"), projection.tracks.map { it.title })
        assertEquals(listOf("Unknown artist", "Unknown artist"), projection.tracks.map { it.artist })
    }

    @Test
    fun sliceAndCustomMarkersBecomeCuesWithArgbColour() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", markers = marker("m1", 1500, "slice") + "," + marker("m2", 2500, "custom")))),
        )
        val cues = projection.cues.getValue("a1")
        assertEquals(listOf("m1", "m2"), cues.map { it.id })
        assertEquals(listOf("slice", "cue"), cues.map { it.role })
        assertEquals(1500L, cues[0].positionMs)
        assertEquals(0xFF112233L, cues[0].colorArgb)
        assertEquals("a1", cues[0].trackId)
    }

    @Test
    fun badOrMissingColourBecomesNullInsteadOfFailing() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(
                interval(
                    "i1",
                    intervalJson("a1", markers = marker("m1", 1, "slice", "red") + "," + marker("m2", 2, "slice", null) + "," + marker("m3", 3, "slice", "#12345")),
                ),
            ),
        )
        assertEquals(listOf(null, null, null), projection.cues.getValue("a1").map { it.colorArgb })
    }

    @Test
    fun loopMarkersPairAndUnpairedOnesAreIgnored() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(
                interval(
                    "i1",
                    intervalJson(
                        "a1",
                        markers = listOf(
                            marker("end0", 100, "loop_end"),
                            marker("s1", 1000, "loop_start"),
                            marker("e1", 2000, "loop_end"),
                            marker("s2", 3000, "loop_start"),
                        ).joinToString(","),
                    ),
                ),
            ),
        )
        val loops = projection.loops.getValue("a1")
        assertEquals(1, loops.size)
        assertEquals("s1~e1", loops.single().id)
        assertEquals(1000L, loops.single().startMs)
        assertEquals(2000L, loops.single().endMs)
        assertTrue(projection.cues["a1"].isNullOrEmpty())
    }

    @Test
    fun loopableIntervalBecomesALoop() {
        val projection = LibraryProjector.project(
            listOf(asset("a1", """{"title":"A","duration_ms":10000}""")),
            listOf(interval("i1", intervalJson("a1", start = 1000, end = 4000, extra = ""","loopable":1"""))),
        )
        val loop = projection.loops.getValue("a1").single()
        assertEquals("i1", loop.id)
        assertEquals(1000L, loop.startMs)
        assertEquals(4000L, loop.endMs)
    }

    @Test
    fun malformedUnknownAndNullShapedEntitiesAreSkippedNotFatal() {
        val projection = LibraryProjector.project(
            listOf(
                asset("bad", "not json at all"),
                asset("nulls", """{"title":null,"artist":null,"duration_ms":null,"rating":null}"""),
                asset("array", "[1,2,3]"),
                asset("ok", """{"title":"Fine","duration_ms":10}"""),
            ),
            listOf(
                interval("i-bad", "{"),
                interval("i-no-asset", """{"interval":{"id":"x","start_ms":0,"end_ms":1},"markers":[]}"""),
                interval("i-wrong-markers", """{"interval":{"id":"y","asset_id":"ok","start_ms":0,"end_ms":10},"markers":"nope"}"""),
            ),
        )
        assertEquals(listOf("nulls", "ok"), projection.tracks.map { it.id })
        assertEquals("nulls", projection.tracks.first().title)
        assertTrue(projection.cues.isEmpty())
    }

    @Test
    fun aLargeLibraryProjectsEveryAsset() {
        val assets = (1..5_000).map { asset("a$it", """{"title":"T$it","duration_ms":1000}""") }
        val intervals = (1..5_000).map { interval("i$it", intervalJson("a$it", end = 1000, markers = marker("m$it", 10, "slice"))) }
        val projection = LibraryProjector.project(assets, intervals)
        assertEquals(5_000, projection.tracks.size)
        assertEquals(5_000, projection.cues.size)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.LibraryProjectionTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: LibraryProjector`.

- [ ] **Step 3: Implement the projection**

`app/src/main/java/dev/androidjtools/sync/samplelib/LibraryProjection.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class LibraryProjection(
    val tracks: List<Track>,
    val cues: Map<String, List<Cue>>,
    val loops: Map<String, List<Loop>>,
) {
    private val trackIds: Set<String> by lazy { tracks.mapTo(HashSet()) { it.id } }

    fun hasTrack(id: String): Boolean = id in trackIds

    companion object {
        val EMPTY = LibraryProjection(emptyList(), emptyMap(), emptyMap())
    }
}

object LibraryProjector {
    fun project(assets: List<CachedEntity>, intervals: List<CachedEntity>): LibraryProjection {
        val parsedIntervals = intervals.mapNotNull(::parseInterval)
        val byAsset = parsedIntervals.groupBy { it.assetId }

        val tracks = assets.mapNotNull { entity ->
            val value = parseObject(entity.valueJson) ?: return@mapNotNull null
            val duration = value.long("duration_ms") ?: 0L
            val fullSpan = byAsset[entity.entityId].orEmpty()
                .firstOrNull { duration > 0 && it.startMs == 0L && it.endMs >= duration }
            Track(
                id = entity.entityId,
                title = value.string("title") ?: value.string("original_filename") ?: entity.entityId,
                artist = value.string("artist") ?: "Unknown artist",
                album = value.string("collection"),
                durationMs = duration,
                bpm = fullSpan?.bpm,
                key = fullSpan?.key,
                rating = value.long("rating")?.toInt(),
            )
        }
        return LibraryProjection(
            tracks = tracks,
            cues = parsedIntervals.flatMap { it.cues }.groupBy { it.trackId },
            loops = parsedIntervals.flatMap { it.loops }.groupBy { it.trackId },
        )
    }

    private class ParsedInterval(
        val assetId: String,
        val startMs: Long,
        val endMs: Long,
        val bpm: Double?,
        val key: String?,
        val cues: List<Cue>,
        val loops: List<Loop>,
    )

    private class ParsedMarker(val id: String, val positionMs: Long, val type: String, val label: String?, val color: String?)

    private fun parseInterval(entity: CachedEntity): ParsedInterval? {
        val root = parseObject(entity.valueJson) ?: return null
        val interval = root["interval"] as? JsonObject ?: return null
        val assetId = interval.string("asset_id") ?: return null
        val start = interval.long("start_ms") ?: return null
        val end = interval.long("end_ms") ?: return null

        val markers = ((root["markers"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { element ->
            val marker = element as? JsonObject ?: return@mapNotNull null
            ParsedMarker(
                id = marker.string("id") ?: return@mapNotNull null,
                positionMs = marker.long("position_ms") ?: return@mapNotNull null,
                type = marker.string("marker_type") ?: "custom",
                label = marker.string("label"),
                color = marker.string("color"),
            )
        }

        val cues = markers.mapNotNull { marker ->
            val role = when (marker.type) {
                "slice" -> "slice"
                "custom" -> "cue"
                else -> return@mapNotNull null
            }
            Cue(id = marker.id, trackId = assetId, positionMs = marker.positionMs, role = role, label = marker.label, colorArgb = parseColor(marker.color))
        }

        val loops = mutableListOf<Loop>()
        if (interval.long("loopable") == 1L) {
            loops += Loop(id = entity.entityId, trackId = assetId, startMs = start, endMs = end, role = "loop", label = interval.string("label"))
        }
        var openStart: ParsedMarker? = null
        for (marker in markers.sortedBy { it.positionMs }) {
            when (marker.type) {
                "loop_start" -> openStart = marker
                "loop_end" -> {
                    val opened = openStart
                    if (opened != null && marker.positionMs > opened.positionMs) {
                        loops += Loop(
                            id = "${opened.id}~${marker.id}",
                            trackId = assetId,
                            startMs = opened.positionMs,
                            endMs = marker.positionMs,
                            role = "loop",
                            label = opened.label,
                        )
                        openStart = null
                    }
                }
            }
        }

        return ParsedInterval(assetId, start, end, interval.double("bpm"), interval.string("key_label"), cues, loops)
    }

    private val colour = Regex("^#([0-9a-fA-F]{6})$")

    private fun parseColor(value: String?): Long? =
        value?.let { colour.matchEntire(it) }?.groupValues?.get(1)?.toLong(16)?.let { 0xFF000000L or it }

    private fun parseObject(json: String): JsonObject? =
        runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.LibraryProjectionTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/androidjtools/sync/samplelib/LibraryProjection.kt app/src/test/java/dev/androidjtools/sync/samplelib/LibraryProjectionTest.kt
git commit -m "feat(samplelib): project server entities into tracks, cues and loops

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Profile store and credential encryption

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/AtomicFiles.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProfileStore.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/AesGcmKeystoreCipher.kt`
- Create: `app/src/test/java/dev/androidjtools/sync/samplelib/FakeCipher.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProfileStoreTest.kt`
- Test: `app/src/androidTest/java/dev/androidjtools/sync/samplelib/AesGcmKeystoreCipherTest.kt` (compiles locally, runs on a device or emulator)

**Interfaces:**
- Consumes: `MobileCredential` (`bearerValue()` is `internal`, reachable from main and test source sets) from `dev.androidjtools.remote.samplelib`.
- Produces:
  - `data class SampleLibProfile(val baseUrl: String, val serverId: String, val principalId: String?, val installationId: String)`
  - `data class StoredSession(val profile: SampleLibProfile, val credential: MobileCredential)`
  - `sealed interface StoredSessionResult { data object Absent; data class Loaded(val session: StoredSession); data class Unreadable(val profile: SampleLibProfile?, val reason: String) }`
  - `interface CredentialCipher { fun encrypt(plain: ByteArray): ByteArray; fun decrypt(encrypted: ByteArray): ByteArray }`
  - `class SampleLibProfileStore(dir: File, cipher: CredentialCipher) { fun save(profile, credential); fun load(): StoredSessionResult; fun clear() }`
  - `class AesGcmKeystoreCipher(alias: String = ...) : CredentialCipher` (Android only)
  - Test helper `class FakeCipher(failDecrypt: Boolean = false) : CredentialCipher`

- [ ] **Step 1: Write the test helper and the failing store tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/FakeCipher.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

/** Reversible stand-in for the Keystore cipher; output never equals the plaintext. */
class FakeCipher(private val failDecrypt: Boolean = false) : CredentialCipher {
    override fun encrypt(plain: ByteArray): ByteArray =
        ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() } + byteArrayOf(0x7F)

    override fun decrypt(encrypted: ByteArray): ByteArray {
        check(!failDecrypt) { "key invalidated" }
        require(encrypted.isNotEmpty() && encrypted.last() == 0x7F.toByte()) { "bad ciphertext" }
        return ByteArray(encrypted.size - 1) { (encrypted[it].toInt() xor 0x5A).toByte() }
    }
}
```

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProfileStoreTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibProfileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val profile = SampleLibProfile("https://samplelab.example/prefix", "server-1", "principal-1", "install-1")
    private val token = "super-secret-token-value"

    private fun store(cipher: CredentialCipher = FakeCipher()) = SampleLibProfileStore(File(tmp.root, "samplelib"), cipher)

    @Test
    fun emptyDirectoryIsAbsent() {
        assertEquals(StoredSessionResult.Absent, store().load())
    }

    @Test
    fun saveThenLoadRoundTripsProfileAndCredential() {
        store().save(profile, MobileCredential.issued(token))
        val loaded = store().load() as StoredSessionResult.Loaded
        assertEquals(profile, loaded.session.profile)
        assertEquals(token, loaded.session.credential.bearerValue())
    }

    @Test
    fun theCredentialNeverAppearsInPlaintextOnDisk() {
        store().save(profile, MobileCredential.issued(token))
        val all = File(tmp.root, "samplelib").walkTopDown().filter { it.isFile }.map { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertTrue(all.none { it.contains(token) })
        assertTrue(all.any { it.contains("server-1") })
    }

    @Test
    fun savingAgainReplacesTheCredentialAndLeavesNoTempFiles() {
        store().save(profile, MobileCredential.issued("old-token"))
        store().save(profile.copy(principalId = null), MobileCredential.issued("new-token"))
        val loaded = store().load() as StoredSessionResult.Loaded
        assertEquals("new-token", loaded.session.credential.bearerValue())
        assertEquals(null, loaded.session.profile.principalId)
        assertTrue(File(tmp.root, "samplelib").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun clearRemovesProfileAndCredential() {
        val store = store()
        store.save(profile, MobileCredential.issued(token))
        store.clear()
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(File(tmp.root, "samplelib").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun clearOnAnEmptyStoreIsHarmless() {
        store().clear()
        assertEquals(StoredSessionResult.Absent, store().load())
    }

    @Test
    fun aCredentialThatCannotBeDecryptedIsReportedWithTheProfileKept() {
        store().save(profile, MobileCredential.issued(token))
        val result = store(FakeCipher(failDecrypt = true)).load()
        val unreadable = result as StoredSessionResult.Unreadable
        assertEquals(profile, unreadable.profile)
        assertFalse(unreadable.reason.contains(token))
    }

    @Test
    fun aCorruptCredentialFileIsUnreadableNotACrash() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-credential.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store().load() is StoredSessionResult.Unreadable)
    }

    @Test
    fun aMissingCredentialFileIsUnreadableWithTheProfileKept() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-credential.bin").delete()
        val unreadable = store().load() as StoredSessionResult.Unreadable
        assertEquals(profile, unreadable.profile)
    }

    @Test
    fun aTruncatedProfileFileIsUnreadableNotACrash() {
        store().save(profile, MobileCredential.issued(token))
        File(tmp.root, "samplelib/samplelib-profile.json").writeText("{\"base_url\":")
        val unreadable = store().load() as StoredSessionResult.Unreadable
        assertEquals(null, unreadable.profile)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibProfileStoreTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: SampleLibProfileStore`.

- [ ] **Step 3: Implement atomic file writes and the store**

`app/src/main/java/dev/androidjtools/sync/samplelib/AtomicFiles.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object AtomicFiles {
    /** Writes [bytes] to a temp file in the same directory, fsyncs it, then atomically replaces [target]. */
    fun write(target: File, bytes: ByteArray) {
        val dir = target.absoluteFile.parentFile ?: throw IOException("no parent directory for $target")
        dir.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", dir)
        try {
            FileOutputStream(temp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                throw IOException("atomic replace is not supported for $target", error)
            }
        } finally {
            temp.delete()
        }
    }
}
```

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProfileStore.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class SampleLibProfile(
    val baseUrl: String,
    val serverId: String,
    val principalId: String?,
    val installationId: String,
)

data class StoredSession(val profile: SampleLibProfile, val credential: MobileCredential)

sealed interface StoredSessionResult {
    data object Absent : StoredSessionResult
    data class Loaded(val session: StoredSession) : StoredSessionResult
    data class Unreadable(val profile: SampleLibProfile?, val reason: String) : StoredSessionResult
}

interface CredentialCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Persists the (non-secret) server profile and the encrypted credential in two separate files. */
class SampleLibProfileStore(private val dir: File, private val cipher: CredentialCipher) {
    private val profileFile = File(dir, "samplelib-profile.json")
    private val credentialFile = File(dir, "samplelib-credential.bin")

    fun save(profile: SampleLibProfile, credential: MobileCredential) {
        // Credential first: a profile without a credential is recoverable, the reverse never exists.
        AtomicFiles.write(credentialFile, cipher.encrypt(credential.bearerValue().toByteArray(Charsets.UTF_8)))
        AtomicFiles.write(profileFile, encode(profile).toString().toByteArray(Charsets.UTF_8))
    }

    fun load(): StoredSessionResult {
        if (!profileFile.exists()) return StoredSessionResult.Absent
        val profile = try {
            decode(Json.parseToJsonElement(profileFile.readText(Charsets.UTF_8)) as JsonObject)
        } catch (_: Exception) {
            return StoredSessionResult.Unreadable(null, "The stored server profile could not be read.")
        }
        if (!credentialFile.exists()) {
            return StoredSessionResult.Unreadable(profile, "The stored server credential is missing.")
        }
        val credential = try {
            MobileCredential.issued(String(cipher.decrypt(credentialFile.readBytes()), Charsets.UTF_8))
        } catch (_: Exception) {
            return StoredSessionResult.Unreadable(profile, "The stored server credential could not be decrypted.")
        }
        return StoredSessionResult.Loaded(StoredSession(profile, credential))
    }

    fun clear() {
        profileFile.delete()
        credentialFile.delete()
    }

    private fun encode(profile: SampleLibProfile): JsonObject = buildJsonObject {
        put("base_url", profile.baseUrl)
        put("server_id", profile.serverId)
        put("principal_id", profile.principalId?.let(::JsonPrimitive) ?: JsonNull)
        put("installation_id", profile.installationId)
    }

    private fun decode(obj: JsonObject): SampleLibProfile = SampleLibProfile(
        baseUrl = obj.getValue("base_url").jsonPrimitive.content,
        serverId = obj.getValue("server_id").jsonPrimitive.content,
        principalId = obj["principal_id"]?.jsonPrimitive?.contentOrNull,
        installationId = obj.getValue("installation_id").jsonPrimitive.content,
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibProfileStoreTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 10 tests pass.

- [ ] **Step 5: Add the Android Keystore cipher and its device test**

`app/src/main/java/dev/androidjtools/sync/samplelib/AesGcmKeystoreCipher.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable key in the Android Keystore. Output is IV (12 bytes) + ciphertext. */
class AesGcmKeystoreCipher(private val alias: String = DEFAULT_ALIAS) : CredentialCipher {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > IV_BYTES) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, encrypted, 0, IV_BYTES))
        return cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
    }

    fun deleteKey() {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "androidjtools.samplelib.credential"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

`app/src/androidTest/java/dev/androidjtools/sync/samplelib/AesGcmKeystoreCipherTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class AesGcmKeystoreCipherTest {
    private val cipher = AesGcmKeystoreCipher("androidjtools.samplelib.test")

    @After
    fun cleanUp() = cipher.deleteKey()

    @Test
    fun roundTripsAndHidesThePlaintext() {
        val plain = "secret-token".toByteArray()
        val encrypted = cipher.encrypt(plain)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("secret-token"))
        assertArrayEquals(plain, cipher.decrypt(encrypted))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val encrypted = cipher.encrypt("secret-token".toByteArray())
        encrypted[encrypted.size - 1] = (encrypted.last().toInt() xor 1).toByte()
        try {
            cipher.decrypt(encrypted)
            fail("tampered ciphertext must not decrypt")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun aDeletedKeyMakesOldCiphertextUnreadable() {
        val encrypted = cipher.encrypt("secret-token".toByteArray())
        cipher.deleteKey()
        try {
            cipher.decrypt(encrypted)
            fail("a regenerated key must not decrypt old data")
        } catch (expected: Exception) {
        }
    }
}
```

- [ ] **Step 6: Verify compilation of main and instrumentation sources**

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline --console=plain -q
```

Expected: no output, exit 0. (The Keystore test needs a device or emulator and cannot run in this environment; it is recorded as "blocked: no device" evidence, not a pass.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/androidjtools/sync/samplelib app/src/test/java/dev/androidjtools/sync/samplelib app/src/androidTest/java/dev/androidjtools/sync/samplelib
git commit -m "feat(samplelib): persist server profile with a Keystore-encrypted credential

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Server address normalization and path-prefix-safe transport

**Files:**
- Create: `app/src/main/java/dev/androidjtools/remote/samplelib/SampleLibUrl.kt`
- Modify: `app/src/main/java/dev/androidjtools/remote/samplelib/SampleLibHttpTransport.kt` (one line in `postJson`, currently `val url = base.resolve(path) ?: ...`)
- Test: `app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibUrlTest.kt`
- Test: `app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibHttpTransportPathTest.kt`

**Interfaces:**
- Produces: `fun normalizeBaseUrl(input: String): String` (throws `IllegalArgumentException` with a user-presentable message).

- [ ] **Step 1: Write the failing tests**

`SampleLibUrlTest.kt`:

```kotlin
package dev.androidjtools.remote.samplelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SampleLibUrlTest {
    @Test
    fun trimsWhitespaceLowercasesSchemeAndHostAndDropsTrailingSlash() {
        assertEquals("https://samplelab.example", normalizeBaseUrl("  HTTPS://SampleLab.Example/  "))
    }

    @Test
    fun keepsPortAndReverseProxyPathPrefix() {
        assertEquals("https://host:8443/samplelab", normalizeBaseUrl("https://host:8443/samplelab/"))
        assertEquals("http://127.0.0.1:8017", normalizeBaseUrl("http://127.0.0.1:8017"))
    }

    @Test
    fun dropsQueryAndFragment() {
        assertEquals("https://host/p", normalizeBaseUrl("https://host/p?token=abc#frag"))
    }

    @Test
    fun rejectsEmbeddedCredentials() = expectMessage("username or password") { normalizeBaseUrl("https://user:pw@host") }

    @Test
    fun rejectsBlankInput() = expectMessage("Enter the server address") { normalizeBaseUrl("   ") }

    @Test
    fun rejectsAddressesWithoutAScheme() = expectMessage("full address") { normalizeBaseUrl("samplelab.local") }

    @Test
    fun rejectsOtherSchemes() = expectMessage("full address") { normalizeBaseUrl("ftp://host") }

    private fun expectMessage(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message.orEmpty().contains(fragment))
        }
    }
}
```

`SampleLibHttpTransportPathTest.kt`:

```kotlin
package dev.androidjtools.remote.samplelib

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SampleLibHttpTransportPathTest {
    private lateinit var server: HttpServer
    private val seenPaths = CopyOnWriteArrayList<String>()

    private val hello = """{"type":"hello_accepted","protocol_version":"1","server_id":"s","authority_generation":"g","server_change_revision":1,"capabilities":{"pull":["asset"],"mutations":[],"playlists":"canonical_v1","analysis":[],"media":[]},"limits":{"pull_page_max":10,"push_batch_max":10,"tombstone_retention_seconds":1,"mutation_replay_horizon_seconds":1}}"""
    private val pair = """{"type":"pairing_accepted","server_id":"s","principal_id":"p","installation_id":"i","access_token":"tok","scopes":["sync.read"]}"""
    private val pull = """{"type":"pull_response","mode":"snapshot","next_cursor":"c","has_more":false,"changes":[],"tombstones":[],"authority_generation":"g","server_change_revision":1}"""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            seenPaths += exchange.requestURI.path
            val body = when {
                exchange.requestURI.path.endsWith("/hello") -> hello
                exchange.requestURI.path.endsWith("/pair") -> pair
                else -> pull
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun base() = "http://127.0.0.1:${server.address.port}"
    private val credential = MobileCredential.issued("tok")

    @Test
    fun everyEndpointKeepsAReverseProxyPathPrefix() = runBlocking {
        val transport = SampleLibHttpTransport(base() + "/samplelab")
        transport.pair(PairingRequest("c", "i", PairingCode.entered("123456")))
        transport.hello(credential, HelloRequest("c", "i"))
        transport.pull(credential, null, setOf("library"), 10)
        assertEquals(
            listOf("/samplelab/v1/sync/pair", "/samplelab/v1/sync/hello", "/samplelab/v1/sync/pull"),
            seenPaths.toList(),
        )
    }

    @Test
    fun withoutAPrefixEndpointsStayAtTheRoot() = runBlocking {
        SampleLibHttpTransport(base()).hello(credential, HelloRequest("c", "i"))
        assertEquals(listOf("/v1/sync/hello"), seenPaths.toList())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.remote.samplelib.SampleLibUrlTest' --tests 'dev.androidjtools.remote.samplelib.SampleLibHttpTransportPathTest' --offline --console=plain
```

Expected: FAIL. `SampleLibUrlTest` does not compile (`Unresolved reference: normalizeBaseUrl`).

- [ ] **Step 3: Implement the normalizer**

`app/src/main/java/dev/androidjtools/remote/samplelib/SampleLibUrl.kt`:

```kotlin
package dev.androidjtools.remote.samplelib

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Canonical form of a server address as entered by a person: trimmed, http(s) only, no credentials,
 * query or fragment, no trailing slash. A path prefix (reverse proxy) is preserved.
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim()
    require(trimmed.isNotEmpty()) { "Enter the server address." }
    val url = try {
        trimmed.toHttpUrl()
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Enter a full address such as https://samplelab.example:8017.")
    }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "Do not put a username or password in the server address."
    }
    return url.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
}
```

- [ ] **Step 4: Fix the transport so path prefixes survive**

In `SampleLibHttpTransport.kt`, method `postJson`, replace

```kotlin
        val url = base.resolve(path) ?: throw SampleLibFailure.InvalidResponse("Invalid Sample Lib endpoint path")
```

with

```kotlin
        // Relative on purpose: an absolute path ("/v1/...") would discard a reverse-proxy prefix in the base URL.
        val url = base.resolve(path.removePrefix("/")) ?: throw SampleLibFailure.InvalidResponse("Invalid Sample Lib endpoint path")
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.remote.samplelib.*' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`. This runs the 7 URL tests, the 2 transport path tests, and the existing `SampleLibClientTest`/`SampleLibAnalysisTest`; the live contract tests are skipped.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/androidjtools/remote/samplelib app/src/test/java/dev/androidjtools/remote/samplelib
git commit -m "fix(samplelib): normalize server addresses and keep proxy path prefixes

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```


---

### Task 5: Sync engine (paged pull with atomic cursor commits)

**Files:**
- Create: `app/src/test/java/dev/androidjtools/sync/samplelib/ScriptedTransport.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSyncEngine.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSyncEngineTest.kt`

**Interfaces:**
- Consumes: `SampleLibClient.pull(session, cursor, scopes)`, `SampleLibSession` (a data class; `requiresReset` is reset with `copy`), `SampleLibFailure.CursorExpired(authorityGeneration)`, `SampleLibFailure.ResetRequired`, `SampleLibFailure.ServerIdentityMismatch(expected, actual)`, `LibraryCache`, `CacheMeta` (Task 1).
- Produces:
  - `sealed interface SyncResult { data class Synced(val pages: Int, val upserts: Int, val tombstones: Int, val bootstrapped: Boolean) : SyncResult; data object NeedsReconnect : SyncResult }`
  - `class SampleLibSyncEngine(client: SampleLibClient, cache: LibraryCache) { suspend fun sync(session: SampleLibSession, scopes: Set<String> = setOf("library")): SyncResult }`
  - Test helpers: `class ScriptedTransport : SampleLibTransport`, `fun page(...)`, `fun assetChange(...)`, `fun tombstoneOf(...)`.

Behavior contract: exceptions other than the two reset cases propagate untouched (the manager maps them). A page is committed together with its `next_cursor`; a thrown failure mid-sync leaves the previous commit as the resume point.

- [ ] **Step 1: Write the scripted transport and the failing engine tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/ScriptedTransport.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.HelloAccepted
import dev.androidjtools.remote.samplelib.HelloRequest
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.MutationReceipt
import dev.androidjtools.remote.samplelib.OpaqueRevision
import dev.androidjtools.remote.samplelib.PairingRequest
import dev.androidjtools.remote.samplelib.PairingResult
import dev.androidjtools.remote.samplelib.PullMode
import dev.androidjtools.remote.samplelib.PullPage
import dev.androidjtools.remote.samplelib.PushResult
import dev.androidjtools.remote.samplelib.ResourceChunk
import dev.androidjtools.remote.samplelib.ResourceDescriptor
import dev.androidjtools.remote.samplelib.SampleLibMutation
import dev.androidjtools.remote.samplelib.SampleLibTransport
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.ServerIdentity
import dev.androidjtools.remote.samplelib.ServerLimits
import dev.androidjtools.remote.samplelib.Tombstone
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Deterministic in-memory server for sync tests: pull pages and failures are queued by the test. */
class ScriptedTransport : SampleLibTransport {
    var serverId = "server-1"
    var generation = "gen-1"
    var capabilities = ServerCapabilities(
        pull = setOf("asset", "interval", "marker"),
        mutations = setOf("interval.editor_state.replace"),
        playlists = "canonical_v1",
        analysis = emptySet(),
        media = setOf("resource_uri", "content_hash", "range"),
    )
    var limits = ServerLimits(500, 100, 7_776_000, 31_536_000)
    var pairFailure: Throwable? = null
    var helloFailure: Throwable? = null
    var pairCalls = 0
    var helloCalls = 0
    val pullCursors = mutableListOf<String?>()
    var onPull: (suspend () -> Unit)? = null
    private val script = ArrayDeque<() -> PullPage>()

    fun enqueuePage(page: PullPage) {
        script.addLast { page }
    }

    fun enqueueFailure(failure: Throwable) {
        script.addLast { throw failure }
    }

    override suspend fun pair(request: PairingRequest): PairingResult {
        pairCalls++
        pairFailure?.let { throw it }
        return PairingResult(serverId, "principal-1", request.installationId, MobileCredential.issued("token-secret"), setOf("sync.read"))
    }

    override suspend fun hello(credential: MobileCredential, request: HelloRequest): HelloAccepted {
        helloCalls++
        helloFailure?.let { throw it }
        return HelloAccepted("1", ServerIdentity(serverId, generation), 1L, capabilities, limits)
    }

    override suspend fun pull(credential: MobileCredential, cursor: String?, scopes: Set<String>, limit: Int): PullPage {
        pullCursors += cursor
        onPull?.invoke()
        val next = script.removeFirstOrNull() ?: error("no scripted pull page")
        return next()
    }

    override suspend fun push(credential: MobileCredential, clientId: String, mutations: List<SampleLibMutation>): PushResult =
        error("push is not scripted")

    override suspend fun receipt(credential: MobileCredential, mutationId: String): MutationReceipt =
        error("receipt is not scripted")

    override suspend fun fetchResource(credential: MobileCredential, descriptor: ResourceDescriptor, offset: Long): ResourceChunk =
        error("fetchResource is not scripted")
}

fun page(
    next: String,
    hasMore: Boolean = false,
    changes: List<EntityChange> = emptyList(),
    tombstones: List<Tombstone> = emptyList(),
    mode: PullMode = PullMode.SNAPSHOT,
    generation: String = "gen-1",
) = PullPage(mode, next, hasMore, changes, tombstones, generation, 1L)

fun assetChange(id: String, title: String = id, revision: String = "r1") =
    EntityChange(
        "asset",
        id,
        OpaqueRevision(revision),
        "sample-lib.asset/v1",
        buildJsonObject {
            put("title", title)
            put("duration_ms", 1000)
        },
    )

fun tombstoneOf(type: String, id: String) = Tombstone(type, id, OpaqueRevision("t"), Instant.EPOCH)
```

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSyncEngineTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.PullMode
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleLibSyncEngineTest {
    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, "android-dj-tools", "install-1")
    private val engine = SampleLibSyncEngine(client, cache)

    @After
    fun close() = db.close()

    private fun session(): SampleLibSession = runBlocking { client.connect(MobileCredential.issued("token"), transport.serverId) }

    private fun seedCache(cursor: String, vararg ids: String, generation: String = "gen-1", server: String = "server-1") {
        cache.applyPage(ids.map { assetChange(it) }, emptyList(), CacheMeta(cursor, generation, server))
    }

    private fun sync(): SyncResult = runBlocking { engine.sync(session()) }

    @Test
    fun bootstrapsAMultiPageSnapshotAndCommitsEachPageWithItsCursor() {
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a1"), assetChange("a2"))))
        transport.enqueuePage(page("c2", changes = listOf(assetChange("a3"))))

        val result = sync()

        assertEquals(SyncResult.Synced(pages = 2, upserts = 3, tombstones = 0, bootstrapped = true), result)
        assertEquals(listOf(null, "c1"), transport.pullCursors)
        assertEquals(listOf("a1", "a2", "a3"), cache.entities("asset").map { it.entityId })
        assertEquals(CacheMeta("c2", "gen-1", "server-1"), cache.meta())
    }

    @Test
    fun incrementalPullAppliesChangesAndTombstonesFromTheStoredCursor() {
        seedCache("c1", "a1", "a2")
        transport.enqueuePage(page("c2", mode = PullMode.INCREMENTAL, changes = listOf(assetChange("a3")), tombstones = listOf(tombstoneOf("asset", "a1"))))

        val result = sync()

        assertEquals(SyncResult.Synced(pages = 1, upserts = 1, tombstones = 1, bootstrapped = false), result)
        assertEquals(listOf("c1"), transport.pullCursors)
        assertEquals(listOf("a2", "a3"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anInterruptedBootstrapResumesFromTheLastCommittedPage() {
        transport.enqueuePage(page("c1", hasMore = true, changes = listOf(assetChange("a1"))))
        transport.enqueueFailure(SampleLibFailure.Offline())

        assertThrows(SampleLibFailure.Offline::class.java) { sync() }
        assertEquals("c1", cache.meta().cursor)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })

        transport.enqueuePage(page("c2", changes = listOf(assetChange("a2"))))
        sync()

        assertEquals(listOf(null, "c1", "c1"), transport.pullCursors)
        assertEquals(listOf("a1", "a2"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aMalformedPageNeverAdvancesTheCursor() {
        seedCache("c1", "a1")
        transport.enqueueFailure(SampleLibFailure.InvalidResponse("bad page"))

        assertThrows(SampleLibFailure.InvalidResponse::class.java) { sync() }
        assertEquals("c1", cache.meta().cursor)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun cursorExpiredDropsTheCacheAndRebootstraps() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))
        transport.enqueuePage(page("c9", changes = listOf(assetChange("fresh"))))

        val result = sync() as SyncResult.Synced

        assertTrue(result.bootstrapped)
        assertEquals(listOf("old", null), transport.pullCursors)
        assertEquals(listOf("fresh"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun cursorExpiredForAnotherGenerationAsksForAReconnect() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-2"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun repeatedCursorExpiryDoesNotLoopForever() {
        seedCache("old", "stale")
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))
        transport.enqueueFailure(SampleLibFailure.CursorExpired("gen-1"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertEquals(2, transport.pullCursors.size)
    }

    @Test
    fun aGenerationChangeClearsTheCacheBeforeAnythingIsPulled() {
        seedCache("old", "stale", generation = "gen-0")
        transport.enqueuePage(page("c1", changes = listOf(assetChange("fresh"))))

        val result = sync() as SyncResult.Synced

        assertTrue(result.bootstrapped)
        assertEquals(listOf<String?>(null), transport.pullCursors)
        assertEquals(listOf("fresh"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aPageFromAnotherGenerationMidSyncAsksForAReconnect() {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1")), generation = "gen-2"))

        assertEquals(SyncResult.NeedsReconnect, sync())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun aDifferentServerIdentityIsRefusedAndTheCacheIsLeftAlone() {
        seedCache("c1", "a1", server = "other-server")

        assertThrows(SampleLibFailure.ServerIdentityMismatch::class.java) { sync() }
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertTrue(transport.pullCursors.isEmpty())
    }

    @Test
    fun aSyncWithNothingNewIsAnEmptyDeltaNotAReset() {
        seedCache("c1", "a1")
        transport.enqueuePage(page("c1", mode = PullMode.INCREMENTAL))

        assertEquals(SyncResult.Synced(pages = 1, upserts = 0, tombstones = 0, bootstrapped = false), sync())
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibSyncEngineTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: SampleLibSyncEngine`.

- [ ] **Step 3: Implement the engine**

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSyncEngine.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibSession

sealed interface SyncResult {
    data class Synced(val pages: Int, val upserts: Int, val tombstones: Int, val bootstrapped: Boolean) : SyncResult

    /** The server's authority generation moved under us; reconnect (fresh hello) and sync again. */
    data object NeedsReconnect : SyncResult
}

class SampleLibSyncEngine(
    private val client: SampleLibClient,
    private val cache: LibraryCache,
) {
    suspend fun sync(session: SampleLibSession, scopes: Set<String> = setOf("library")): SyncResult {
        val stored = cache.meta()
        val serverId = session.identity.serverId
        if (stored.serverId != null && stored.serverId != serverId) {
            throw SampleLibFailure.ServerIdentityMismatch(stored.serverId, serverId)
        }
        if (stored.authorityGeneration != null && stored.authorityGeneration != session.identity.authorityGeneration) {
            // Cached rows and the cursor describe another authority generation: they are meaningless now.
            cache.reset()
        }
        var cursor = cache.meta().cursor
        var bootstrapped = cursor == null
        // The generation was just reconciled against the cache, so the client must not demand a reset again.
        val active = session.copy(requiresReset = false)

        var pages = 0
        var upserts = 0
        var tombstones = 0
        var retriedExpiredCursor = false

        while (true) {
            val page = try {
                client.pull(active, cursor, scopes)
            } catch (expired: SampleLibFailure.CursorExpired) {
                cache.reset()
                val otherGeneration = expired.authorityGeneration.isNotBlank() &&
                    expired.authorityGeneration != session.identity.authorityGeneration
                if (retriedExpiredCursor || otherGeneration) return SyncResult.NeedsReconnect
                retriedExpiredCursor = true
                cursor = null
                bootstrapped = true
                pages = 0
                upserts = 0
                tombstones = 0
                continue
            } catch (reset: SampleLibFailure.ResetRequired) {
                cache.reset()
                return SyncResult.NeedsReconnect
            }

            cache.applyPage(page.changes, page.tombstones, CacheMeta(page.nextCursor, page.authorityGeneration, serverId))
            pages++
            upserts += page.changes.size
            tombstones += page.tombstones.size
            cursor = page.nextCursor
            if (!page.hasMore) return SyncResult.Synced(pages, upserts, tombstones, bootstrapped)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibSyncEngineTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSyncEngine.kt app/src/test/java/dev/androidjtools/sync/samplelib
git commit -m "feat(samplelib): add paged sync engine with atomic cursor commits and reset handling

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Session manager (pairing, reconnect, failure states, forget)

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSessionManager.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionManagerTest.kt`

**Interfaces:**
- Consumes: `SampleLibProfileStore`, `StoredSessionResult`, `SampleLibProfile` (Task 3); `LibraryCache` (Task 1); `SampleLibSyncEngine`, `SyncResult` (Task 5); `normalizeBaseUrl` (Task 4); `SyncState` from `dev.androidjtools.core.model`.
- Produces:
  - `const val SAMPLE_LIB_CLIENT_ID = "android-dj-tools"`
  - `val defaultSampleLibClientFactory: (baseUrl: String, installationId: String) -> SampleLibClient`
  - `class SampleLibSessionManager(store, cache, clientFactory = defaultSampleLibClientFactory, newInstallationId = { UUID.randomUUID().toString() })` with `val state: StateFlow<SyncState>`, `val capabilities: StateFlow<ServerCapabilities?>`, `val error: StateFlow<String?>`, `val profile: StateFlow<SampleLibProfile?>`, `suspend fun refresh()`, `suspend fun pair(rawUrl: String, rawCode: String)`, `suspend fun forget()`

All operations are serialized by one mutex. Messages shown to people never contain the pairing code or the credential.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SampleLibSessionManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, SAMPLE_LIB_CLIENT_ID, "install-1")
    private lateinit var store: SampleLibProfileStore
    private lateinit var manager: SampleLibSessionManager
    private val profile = SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1")

    @Before
    fun setUp() {
        store = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher())
        manager = SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" })
    }

    @After
    fun close() = db.close()

    private fun seedStoredSession() = store.save(profile, MobileCredential.issued("token-secret"))

    private fun seedCache(vararg ids: String) =
        cache.applyPage(ids.map { assetChange(it) }, emptyList(), CacheMeta("c0", "gen-1", "server-1"))

    @Test
    fun pairingSavesTheProfileSyncsTheLibraryAndGoesOnline() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        manager.pair("  https://samplelab.example/  ", " 246810 ")

        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
        assertNull(manager.error.value)
        assertEquals("https://samplelab.example", manager.profile.value?.baseUrl)
        assertEquals(setOf("asset", "interval", "marker"), manager.capabilities.value?.pull)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        val loaded = store.load() as StoredSessionResult.Loaded
        assertEquals("token-secret", loaded.session.credential.bearerValue())
        assertEquals("server-1", loaded.session.profile.serverId)
    }

    @Test
    fun aRejectedPairingCodeKeepsTheStateAndNeverEchoesTheCode() = runBlocking {
        transport.pairFailure = SampleLibFailure.PairingRejected()

        manager.pair("https://samplelab.example", "000999")

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals("The server rejected the pairing code.", manager.error.value)
        assertFalse(manager.error.value.orEmpty().contains("000999"))
        assertEquals(StoredSessionResult.Absent, store.load())
    }

    @Test
    fun anInsecureRemoteAddressIsRefusedBeforeAnyNetworkCall() = runBlocking {
        val real = SampleLibSessionManager(store, cache)

        real.pair("http://192.0.2.10:8765", "246810")

        assertTrue(real.error.value.orEmpty().contains("https://"))
        assertEquals(SyncState.UNCONFIGURED, real.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
    }

    @Test
    fun invalidInputIsReportedWithoutTouchingTheNetwork() = runBlocking {
        manager.pair("   ", "246810")
        assertEquals("Enter the server address.", manager.error.value)
        manager.pair("https://samplelab.example", "  ")
        assertEquals("Enter the pairing code.", manager.error.value)
        assertEquals(0, transport.pairCalls)
    }

    @Test
    fun pairingADifferentServerDropsTheOldServersCache() = runBlocking {
        cache.applyPage(listOf(assetChange("old")), emptyList(), CacheMeta("c0", "gen-0", "old-server"))
        transport.enqueuePage(page("c1", changes = listOf(assetChange("new"))))

        manager.pair("https://samplelab.example", "246810")

        assertEquals(listOf("new"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun restoreWithNothingStoredIsUnconfigured() = runBlocking {
        manager.refresh()
        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals(0, transport.helloCalls)
    }

    @Test
    fun refreshReconnectsWithThePinnedIdentityAndSyncs() = runBlocking {
        seedStoredSession()
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        manager.refresh()

        assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
        assertEquals(profile, manager.profile.value)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun aRejectedCredentialRequiresPairingAgainButKeepsTheCachedLibrary() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.helloFailure = SampleLibFailure.AuthRequired()

        manager.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, manager.state.value)
        assertFalse(manager.error.value.orEmpty().contains("token-secret"))
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anUnreachableServerIsOfflineAndTheCachedLibraryStaysReadable() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.helloFailure = SampleLibFailure.Offline()

        manager.refresh()

        assertEquals(SyncState.OFFLINE, manager.state.value)
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anIncompatibleServerIsReportedAsSuch() = runBlocking {
        seedStoredSession()
        transport.helloFailure = SampleLibFailure.Incompatible(listOf("1"), listOf("2"))

        manager.refresh()

        assertEquals(SyncState.SERVER_INCOMPATIBLE, manager.state.value)
    }

    @Test
    fun aChangedServerIdentityIsRefusedAndTellsThePersonToForgetIt() = runBlocking {
        seedStoredSession()
        seedCache("a1")
        transport.serverId = "someone-else"

        manager.refresh()

        assertEquals(SyncState.DEGRADED, manager.state.value)
        assertTrue(manager.error.value.orEmpty().contains("Forget"))
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }

    @Test
    fun anUnreadableStoredCredentialRequiresPairingAndMakesNoNetworkCall() = runBlocking {
        seedStoredSession()
        val brokenStore = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher(failDecrypt = true))
        val broken = SampleLibSessionManager(brokenStore, cache, { _, _ -> client }, { "install-1" })

        broken.refresh()

        assertEquals(SyncState.AUTH_REQUIRED, broken.state.value)
        assertEquals(profile, broken.profile.value)
        assertEquals(0, transport.helloCalls)
    }

    @Test
    fun aLocalCacheFailureDegradesInsteadOfCrashing() = runBlocking {
        val failing = FailingSqlDatabase(JdbcSqlDatabase())
        val brokenCache = SqlLibraryCache(failing)
        val broken = SampleLibSessionManager(store, brokenCache, { _, _ -> client }, { "install-1" })
        seedStoredSession()
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        failing.failOnNthExecute(1)

        broken.refresh()

        assertEquals(SyncState.DEGRADED, broken.state.value)
        assertEquals("Local storage error.", broken.error.value)
    }

    @Test
    fun forgetRemovesTheProfileCredentialAndCache() = runBlocking {
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        manager.pair("https://samplelab.example", "246810")

        manager.forget()

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertNull(manager.profile.value)
        assertNull(manager.capabilities.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun forgetDuringAnInFlightSyncLeavesNothingBehind() = runBlocking {
        seedStoredSession()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        transport.onPull = {
            started.complete(Unit)
            gate.await()
        }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))

        val refresh = async(Dispatchers.Default) { manager.refresh() }
        started.await()
        val forget = async(Dispatchers.Default) { manager.forget() }
        delay(50)
        assertEquals(SyncState.PULLING, manager.state.value)
        gate.complete(Unit)
        refresh.await()
        forget.await()

        assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
        assertNull(manager.profile.value)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibSessionManagerTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: SampleLibSessionManager`.

- [ ] **Step 3: Implement the manager**

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSessionManager.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.PairingCode
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import dev.androidjtools.remote.samplelib.SampleLibHttpTransport
import dev.androidjtools.remote.samplelib.SampleLibSession
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.remote.samplelib.normalizeBaseUrl
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val SAMPLE_LIB_CLIENT_ID = "android-dj-tools"

val defaultSampleLibClientFactory: (String, String) -> SampleLibClient = { baseUrl, installationId ->
    SampleLibClient(SampleLibHttpTransport(baseUrl), SAMPLE_LIB_CLIENT_ID, installationId)
}

/**
 * Owns the connection lifecycle: pair, reconnect, sync, forget. Every operation holds one lock, so a
 * forget can never interleave with a sync. User-visible messages never contain secrets.
 */
class SampleLibSessionManager(
    private val store: SampleLibProfileStore,
    private val cache: LibraryCache,
    private val clientFactory: (baseUrl: String, installationId: String) -> SampleLibClient = defaultSampleLibClientFactory,
    private val newInstallationId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(SyncState.UNCONFIGURED)
    private val mutableCapabilities = MutableStateFlow<ServerCapabilities?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    private val mutableProfile = MutableStateFlow<SampleLibProfile?>(null)

    val state: StateFlow<SyncState> = mutableState.asStateFlow()
    val capabilities: StateFlow<ServerCapabilities?> = mutableCapabilities.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    val profile: StateFlow<SampleLibProfile?> = mutableProfile.asStateFlow()

    /** Loads the stored session (if any), reconnects with the pinned server identity and syncs. */
    suspend fun refresh() = lock.withLock {
        when (val loaded = store.load()) {
            StoredSessionResult.Absent -> {
                mutableProfile.value = null
                mutableCapabilities.value = null
                mutableState.value = SyncState.UNCONFIGURED
            }
            is StoredSessionResult.Unreadable -> {
                mutableProfile.value = loaded.profile
                mutableCapabilities.value = null
                fail(SyncState.AUTH_REQUIRED, "The stored server credential could not be read. Pair again.")
            }
            is StoredSessionResult.Loaded -> {
                val stored = loaded.session
                mutableProfile.value = stored.profile
                guarded(::applyRefreshFailure) {
                    val client = clientFactory(stored.profile.baseUrl, stored.profile.installationId)
                    val session = client.connect(stored.credential, stored.profile.serverId, null, stored.profile.principalId)
                    syncSession(client, session)
                }
            }
        }
    }

    suspend fun pair(rawUrl: String, rawCode: String) = lock.withLock {
        val baseUrl = try {
            normalizeBaseUrl(rawUrl)
        } catch (invalid: IllegalArgumentException) {
            mutableError.value = invalid.message
            return@withLock
        }
        val code = rawCode.trim()
        if (code.isEmpty()) {
            mutableError.value = "Enter the pairing code."
            return@withLock
        }

        val previousState = mutableState.value
        mutableError.value = null
        mutableState.value = SyncState.PAIRING
        val installationId = mutableProfile.value?.installationId ?: newInstallationId()
        val (client, session) = try {
            val created = clientFactory(baseUrl, installationId)
            created to created.pair(PairingCode.entered(code))
        } catch (cancelled: CancellationException) {
            mutableState.value = previousState
            throw cancelled
        } catch (failure: Exception) {
            mutableState.value = previousState
            mutableError.value = describePairingFailure(failure)
            return@withLock
        }

        guarded(::applyRefreshFailure) {
            val cachedServer = cache.meta().serverId
            if (cachedServer != null && cachedServer != session.identity.serverId) cache.reset()
            val saved = SampleLibProfile(baseUrl, session.identity.serverId, session.principalId, installationId)
            store.save(saved, session.credential)
            mutableProfile.value = saved
            syncSession(client, session)
        }
    }

    suspend fun forget() = lock.withLock {
        store.clear()
        cache.reset()
        mutableProfile.value = null
        mutableCapabilities.value = null
        mutableError.value = null
        mutableState.value = SyncState.UNCONFIGURED
    }

    private suspend fun syncSession(client: SampleLibClient, session: SampleLibSession) {
        mutableCapabilities.value = session.capabilities
        mutableState.value = SyncState.PULLING
        val engine = SampleLibSyncEngine(client, cache)
        var result = engine.sync(session)
        if (result is SyncResult.NeedsReconnect) {
            val fresh = client.connect(session.credential, session.identity.serverId, null, session.principalId)
            mutableCapabilities.value = fresh.capabilities
            result = engine.sync(fresh)
            if (result is SyncResult.NeedsReconnect) {
                throw SampleLibFailure.InvalidResponse("Server authority kept changing during sync")
            }
        }
        mutableError.value = null
        mutableState.value = SyncState.ONLINE_IDLE
    }

    private suspend fun guarded(onFailure: (Exception) -> Unit, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }

    private fun applyRefreshFailure(failure: Exception) {
        when (failure) {
            is SampleLibFailure.AuthRequired ->
                fail(SyncState.AUTH_REQUIRED, "The server rejected the stored credential. Pair again.")
            is SampleLibFailure.Incompatible ->
                fail(SyncState.SERVER_INCOMPATIBLE, "This server does not support a compatible sync protocol.")
            is SampleLibFailure.Offline ->
                fail(SyncState.OFFLINE, "Server unreachable. Showing the last synced library.")
            is SampleLibFailure.ServerIdentityMismatch ->
                fail(SyncState.DEGRADED, "The server identity changed. Forget this server and pair again.")
            is SampleLibFailure ->
                fail(SyncState.DEGRADED, "The server returned an unexpected response.")
            else -> fail(SyncState.DEGRADED, "Local storage error.")
        }
    }

    private fun describePairingFailure(failure: Exception): String = when (failure) {
        is SampleLibFailure.PairingRejected -> "The server rejected the pairing code."
        is SampleLibFailure.InsecureTransport -> "Use an https:// address for servers outside this device."
        is SampleLibFailure.Offline -> "Could not reach the server."
        is SampleLibFailure.Incompatible -> "This server does not support a compatible sync protocol."
        is SampleLibFailure.ServerIdentityMismatch -> "The server identity could not be confirmed."
        is SampleLibFailure.AuthRequired -> "The server did not authorize this pairing."
        is SampleLibFailure -> "The server returned an unexpected response."
        is IllegalArgumentException -> failure.message ?: "Invalid pairing input."
        else -> "Pairing failed."
    }

    private fun fail(state: SyncState, message: String) {
        mutableError.value = message
        mutableState.value = state
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibSessionManagerTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 16 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSessionManager.kt app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionManagerTest.kt
git commit -m "feat(samplelib): add session manager for pairing, reconnect and forget

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Providers (server library, composite, server-aware playback)

**Files:**
- Modify: `app/src/main/java/dev/androidjtools/core/provider/Providers.kt` (add `playbackNotice` to `PlaybackProvider`)
- Modify: `app/src/main/java/dev/androidjtools/ui/library/LibraryScreen.kt` (show the notice)
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt`
- Test: `app/src/androidTest/java/dev/androidjtools/ui/library/PlaybackNoticeTest.kt` (compiles locally; runs on device or emulator)

**Interfaces:**
- Consumes: `LibraryCache` (Task 1), `LibraryProjector`/`LibraryProjection` (Task 2), `SampleLibSessionManager` (Task 6), provider interfaces from `core.provider`.
- Produces:
  - `PlaybackProvider.playbackNotice: StateFlow<String?>` (default: always `null`)
  - `class SampleLibProjectionState(cache, scope, dispatcher = Dispatchers.IO)` with `val projection: StateFlow<LibraryProjection>` and `val tracks: StateFlow<List<Track>>`
  - `class SampleLibLibraryProvider(state) : LibraryProvider`
  - `class SampleLibPreparationProvider(state, scope) : PreparationProvider`
  - `class SampleLibSyncProvider(manager) : SyncProvider`
  - `class CompositeLibraryProvider(server: LibraryProvider, device: LibraryProvider, scope) : LibraryProvider`
  - `class CompositeRuntimeProvider(device: AppRuntimeProvider, server: LibraryProvider, scope) : AppRuntimeProvider`
  - `class ServerAwarePlayback(delegate: PlaybackProvider, isServerTrack: (String) -> Boolean) : PlaybackProvider`
  - `const val SERVER_AUDIO_UNAVAILABLE_NOTICE: String`

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.OpaqueRevision
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibProvidersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(type: String, id: String, body: String) =
        EntityChange(type, id, OpaqueRevision("r"), "s", kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)

    private fun asset(id: String, title: String) = json("asset", id, """{"title":"$title","duration_ms":10000}""")

    private fun interval(assetId: String) = json(
        "interval",
        "i-$assetId",
        """{"interval":{"id":"i-$assetId","asset_id":"$assetId","start_ms":1000,"end_ms":4000,"loopable":1},"markers":[{"id":"m-$assetId","position_ms":1500,"marker_type":"slice","color":"#112233"}]}""",
    )

    private class FakeLibrary(initial: List<Track>) : LibraryProvider {
        val flow = MutableStateFlow(initial)
        override val tracks: StateFlow<List<Track>> = flow
        override fun track(id: String): Track? = flow.value.firstOrNull { it.id == id }
    }

    private class FakeRuntime(initial: ProviderUiState) : AppRuntimeProvider {
        val flow = MutableStateFlow(initial)
        override val uiState: StateFlow<ProviderUiState> = flow
    }

    private class FakePlayback : PlaybackProvider {
        val played = mutableListOf<String>()
        override val currentTrackId = MutableStateFlow<String?>(null)
        override val isPlaying = MutableStateFlow(false)
        override val positionMs = MutableStateFlow(0L)
        override fun play(trackId: String) {
            played += trackId
        }
        override fun toggle() = Unit
        override fun seek(positionMs: Long) = Unit
    }

    private fun track(id: String) = Track(id = id, title = id, artist = "x", durationMs = 1)

    @Test
    fun theServerLibraryFollowsEveryCacheCommit() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val library = SampleLibLibraryProvider(state)
        assertTrue(library.tracks.value.isEmpty())

        cache.applyPage(listOf(asset("a1", "Alpha")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()
        assertEquals(listOf("Alpha"), library.tracks.value.map { it.title })
        assertEquals("Alpha", library.track("a1")?.title)
        assertNull(library.track("missing"))

        cache.reset()
        advanceUntilIdle()
        assertTrue(library.tracks.value.isEmpty())
    }

    @Test
    fun preparationExposesCuesAndLoopsPerTrackAndNothingForUnknownTracks() = runTest(UnconfinedTestDispatcher()) {
        val cache = SqlLibraryCache(JdbcSqlDatabase())
        val state = SampleLibProjectionState(cache, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        val preparation = SampleLibPreparationProvider(state, backgroundScope)

        cache.applyPage(listOf(asset("a1", "Alpha"), interval("a1")), emptyList(), CacheMeta("c1", "g", "s"))
        advanceUntilIdle()

        assertEquals(listOf("m-a1"), preparation.cues("a1").value.map { it.id })
        assertEquals(listOf("i-a1"), preparation.loops("a1").value.map { it.id })
        assertTrue(preparation.cues("nope").value.isEmpty())
        assertNull(preparation.beatGrid("a1").value)
    }

    @Test
    fun theCompositeLibraryListsServerTracksAndDeviceTracksTogether() = runTest(UnconfinedTestDispatcher()) {
        val server = FakeLibrary(listOf(track("srv-1")))
        val device = FakeLibrary(listOf(track("dev-1")))
        val composite = CompositeLibraryProvider(server, device, backgroundScope)

        assertEquals(listOf("srv-1", "dev-1"), composite.tracks.value.map { it.id })
        device.flow.value = listOf(track("dev-1"), track("dev-2"))
        assertEquals(listOf("srv-1", "dev-1", "dev-2"), composite.tracks.value.map { it.id })
        assertEquals("srv-1", composite.track("srv-1")?.id)
        assertEquals("dev-2", composite.track("dev-2")?.id)
        assertNull(composite.track("none"))
    }

    @Test
    fun aDevicePermissionErrorDoesNotHideAServerLibrary() = runTest(UnconfinedTestDispatcher()) {
        val server = FakeLibrary(emptyList())
        val device = FakeRuntime(ProviderUiState.Error("Audio-library permission is required"))
        val runtime = CompositeRuntimeProvider(device, server, backgroundScope)

        assertEquals(ProviderUiState.Error("Audio-library permission is required"), runtime.uiState.value)
        server.flow.value = listOf(track("srv-1"))
        assertEquals(ProviderUiState.Ready, runtime.uiState.value)
        device.flow.value = ProviderUiState.Offline
        assertEquals(ProviderUiState.Offline, runtime.uiState.value)
    }

    @Test
    fun serverTracksAreNotPlayedAndSayWhy() {
        val device = FakePlayback()
        val playback = ServerAwarePlayback(device) { it.startsWith("srv-") }

        playback.play("srv-1")
        assertTrue(device.played.isEmpty())
        assertEquals(SERVER_AUDIO_UNAVAILABLE_NOTICE, playback.playbackNotice.value)

        playback.play("dev-1")
        assertEquals(listOf("dev-1"), device.played)
        assertNull(playback.playbackNotice.value)
    }

    @Test
    fun playbackWithoutAGateNeverHasANotice() {
        assertNull(FakePlayback().playbackNotice.value)
    }

    @Test
    fun theSyncProviderMirrorsTheManagerState() = runTest(UnconfinedTestDispatcher()) {
        val db = JdbcSqlDatabase()
        val manager = SampleLibSessionManager(
            SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher()),
            SqlLibraryCache(db),
            { _, _ -> error("not used") },
        )
        val provider = SampleLibSyncProvider(manager)
        assertEquals(SyncState.UNCONFIGURED, provider.state.value)
        assertEquals(0, provider.pendingMutationCount.value)
        db.close()
    }
}
```

`app/src/androidTest/java/dev/androidjtools/ui/library/PlaybackNoticeTest.kt`:

```kotlin
package dev.androidjtools.ui.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class PlaybackNoticeTest {
    @get:Rule
    val compose = createComposeRule()

    private class NoticePlayback(private val delegate: PlaybackProvider) : PlaybackProvider by delegate {
        override val playbackNotice: StateFlow<String?> = MutableStateFlow("Server audio is not available yet.")
    }

    @Test
    fun theLibraryShowsTheProvidersPlaybackNotice() {
        val fixtures = FixtureAppProviders.create()
        val providers = fixtures.copy(playback = NoticePlayback(fixtures.playback))
        compose.setContent { AndroidDjToolsTheme { LibraryScreen(providers) } }
        compose.onNodeWithTag("playback-notice").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.SampleLibProvidersTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: SampleLibProjectionState` (and `playbackNotice`).

- [ ] **Step 3: Add `playbackNotice` to `PlaybackProvider`**

In `core/provider/Providers.kt` add the import `import kotlinx.coroutines.flow.MutableStateFlow` and change `PlaybackProvider` to:

```kotlin
interface PlaybackProvider {
    val currentTrackId: StateFlow<String?>
    val isPlaying: StateFlow<Boolean>
    val positionMs: StateFlow<Long>
    fun play(trackId: String)
    fun toggle()
    fun seek(positionMs: Long)
    fun release() = Unit

    /** A short reason the last play request did nothing, or null. Providers that never refuse inherit null. */
    val playbackNotice: StateFlow<String?> get() = NoPlaybackNotice
}

private val NoPlaybackNotice: StateFlow<String?> = MutableStateFlow<String?>(null)
```

- [ ] **Step 4: Implement the providers**

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.BeatGrid
import dev.androidjtools.core.model.Cue
import dev.androidjtools.core.model.Loop
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.model.Track
import dev.androidjtools.core.provider.AppRuntimeProvider
import dev.androidjtools.core.provider.LibraryProvider
import dev.androidjtools.core.provider.PlaybackProvider
import dev.androidjtools.core.provider.PreparationProvider
import dev.androidjtools.core.provider.ProviderUiState
import dev.androidjtools.core.provider.SyncProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

const val SERVER_AUDIO_UNAVAILABLE_NOTICE =
    "This track is on your Sample Lib server. Server audio is not available yet, so it cannot be played here."

/** Recomputes the library projection off the main thread after every cache commit. */
class SampleLibProjectionState(
    cache: LibraryCache,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val projection: StateFlow<LibraryProjection> = cache.changes
        .map { LibraryProjector.project(cache.entities("asset"), cache.entities("interval")) }
        .flowOn(dispatcher)
        .stateIn(scope, SharingStarted.Eagerly, LibraryProjection.EMPTY)

    val tracks: StateFlow<List<Track>> = projection
        .map { it.tracks }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}

class SampleLibLibraryProvider(private val state: SampleLibProjectionState) : LibraryProvider {
    override val tracks: StateFlow<List<Track>> = state.tracks
    override fun track(id: String): Track? = state.projection.value.tracks.firstOrNull { it.id == id }
}

class SampleLibPreparationProvider(
    private val state: SampleLibProjectionState,
    private val scope: CoroutineScope,
) : PreparationProvider {
    private val cues = ConcurrentHashMap<String, StateFlow<List<Cue>>>()
    private val loops = ConcurrentHashMap<String, StateFlow<List<Loop>>>()
    private val noBeatGrid = MutableStateFlow<BeatGrid?>(null).asStateFlow()

    override fun cues(trackId: String): StateFlow<List<Cue>> = cues.getOrPut(trackId) {
        state.projection.map { it.cues[trackId].orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, state.projection.value.cues[trackId].orEmpty())
    }

    override fun loops(trackId: String): StateFlow<List<Loop>> = loops.getOrPut(trackId) {
        state.projection.map { it.loops[trackId].orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, state.projection.value.loops[trackId].orEmpty())
    }

    /** The server only sends an opaque beatgrid reference in S1, so no grid is shown. */
    override fun beatGrid(trackId: String): StateFlow<BeatGrid?> = noBeatGrid
}

class SampleLibSyncProvider(manager: SampleLibSessionManager) : SyncProvider {
    override val state: StateFlow<SyncState> = manager.state
    override val pendingMutationCount: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
}

/** Server tracks first, then device tracks. No de-duplication until S2 brings content-hash matching. */
class CompositeLibraryProvider(
    private val server: LibraryProvider,
    private val device: LibraryProvider,
    scope: CoroutineScope,
) : LibraryProvider {
    override val tracks: StateFlow<List<Track>> = combine(server.tracks, device.tracks) { s, d -> s + d }
        .stateIn(scope, SharingStarted.Eagerly, server.tracks.value + device.tracks.value)

    override fun track(id: String): Track? = server.track(id) ?: device.track(id)
}

/** A device-side failure (for example a denied audio permission) must not hide a synced server library. */
class CompositeRuntimeProvider(
    device: AppRuntimeProvider,
    server: LibraryProvider,
    scope: CoroutineScope,
) : AppRuntimeProvider {
    override val uiState: StateFlow<ProviderUiState> = combine(device.uiState, server.tracks, ::merge)
        .stateIn(scope, SharingStarted.Eagerly, merge(device.uiState.value, server.tracks.value))

    private companion object {
        fun merge(device: ProviderUiState, serverTracks: List<Track>): ProviderUiState =
            if (serverTracks.isNotEmpty() &&
                (device is ProviderUiState.Error || device is ProviderUiState.Empty || device is ProviderUiState.Loading)
            ) {
                ProviderUiState.Ready
            } else {
                device
            }
    }
}

/** Plays device tracks through [delegate]; refuses server tracks and says why instead of failing silently. */
class ServerAwarePlayback(
    private val delegate: PlaybackProvider,
    private val isServerTrack: (String) -> Boolean,
) : PlaybackProvider by delegate {
    private val notice = MutableStateFlow<String?>(null)
    override val playbackNotice: StateFlow<String?> = notice.asStateFlow()

    override fun play(trackId: String) {
        if (isServerTrack(trackId)) {
            notice.value = SERVER_AUDIO_UNAVAILABLE_NOTICE
            return
        }
        notice.value = null
        delegate.play(trackId)
    }
}
```

- [ ] **Step 5: Show the notice in the library screen**

In `ui/library/LibraryScreen.kt`, next to the other `collectAsState` lines (near `val syncState by providers.sync.state.collectAsState()`), add:

```kotlin
    val playbackNotice by providers.playback.playbackNotice.collectAsState()
```

and inside the `Column(... .testTag(if (roomy) "library-layout-roomy" else "library-layout-compact"))`, immediately before `LibraryHeader(`, add:

```kotlin
            playbackNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding, vertical = 8.dp)
                        .testTag("playback-notice"),
                )
            }
```

Add any missing imports the compiler reports (`Text`, `MaterialTheme`, `padding`, `fillMaxWidth` are already used elsewhere in this file).

- [ ] **Step 6: Run the tests and compile everything**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.sync.samplelib.*' --offline --console=plain
./gradlew :app:compileDebugAndroidTestKotlin --offline --console=plain -q
```

Expected: both succeed; 7 provider tests pass. (`PlaybackNoticeTest` is compile-only here: it needs a device or emulator, which is recorded as "blocked: no device", not a pass.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/androidjtools/core/provider/Providers.kt app/src/main/java/dev/androidjtools/ui/library/LibraryScreen.kt app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibProviders.kt app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibProvidersTest.kt app/src/androidTest/java/dev/androidjtools/ui/library/PlaybackNoticeTest.kt
git commit -m "feat(samplelib): add server library, composite and server-aware playback providers

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```


---

### Task 8: Capability panel, session controller and the Sync screen

**Files:**
- Create: `app/src/main/java/dev/androidjtools/ui/sync/ServerCapabilityRows.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/ServerSessionController.kt`
- Modify: `app/src/main/java/dev/androidjtools/ui/sync/SyncScreen.kt`
- Modify: `app/src/main/java/dev/androidjtools/App.kt`
- Test: `app/src/test/java/dev/androidjtools/ui/sync/ServerCapabilityRowsTest.kt`
- Test: `app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionControllerTest.kt`
- Test: `app/src/androidTest/java/dev/androidjtools/ui/sync/SyncScreenServerTest.kt` (compiles locally; runs on device or emulator)

**Interfaces:**
- Consumes: `ServerCapabilities` (`pull`, `mutations`, `playlists`, `analysis`, `media`), `SampleLibSessionManager` (Task 6), `SyncState`.
- Produces:
  - `data class CapabilityRow(val id: String, val label: String, val available: Boolean, val detail: String)`; row ids are `library`, `playlists`, `editor`, `metadata`, `tags`, `analysis`, `audio`, `resume`
  - `fun capabilityRows(capabilities: ServerCapabilities?): List<CapabilityRow>`
  - `fun syncStateLabel(state: SyncState): String`
  - `fun canPair(url: String, code: String, state: SyncState): Boolean`
  - `interface SyncScheduler { fun schedule(); fun cancel() }`
  - `interface ServerSessionController { val state; val capabilities; val error; val serverUrl: StateFlow<String?>; fun pair(baseUrl: String, code: String); fun refresh(); fun forget() }`
  - `class SampleLibSessionController(manager, scope, scheduler) : ServerSessionController`
  - `SyncScreen(providers: AppProviders, server: ServerSessionController? = null)`; `AndroidDjToolsApp(..., server: ServerSessionController? = null)`

- [ ] **Step 1: Write the failing pure-logic tests**

`app/src/test/java/dev/androidjtools/ui/sync/ServerCapabilityRowsTest.kt`:

```kotlin
package dev.androidjtools.ui.sync

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.ServerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCapabilityRowsTest {
    /** Exactly what the real Sample Lib sync facade advertised in the live contract run. */
    private val realServer = ServerCapabilities(
        pull = setOf("asset", "interval", "marker", "playlist", "playlist_item", "tag", "tag_attachment"),
        mutations = setOf("interval.editor_state.replace"),
        playlists = "canonical_v1",
        analysis = emptySet(),
        media = setOf("resource_uri", "content_hash", "range"),
    )

    private fun rows(c: ServerCapabilities?) = capabilityRows(c).associateBy { it.id }

    @Test
    fun theRealServerSupportsLibraryPlaylistsAndEditorButNotTheRest() {
        val r = rows(realServer)
        assertTrue(r.getValue("library").available)
        assertTrue(r.getValue("playlists").available)
        assertEquals("Read-only.", r.getValue("playlists").detail)
        assertTrue(r.getValue("editor").available)
        assertTrue(r.getValue("resume").available)
        assertFalse(r.getValue("metadata").available)
        assertFalse(r.getValue("tags").available)
        assertFalse(r.getValue("analysis").available)
        assertFalse(r.getValue("audio").available)
    }

    @Test
    fun unavailableRowsNameTheMissingServerCapability() {
        val r = rows(realServer)
        assertTrue(r.getValue("audio").detail.contains("asset_file"))
        assertTrue(r.getValue("metadata").detail.contains("asset.metadata.patch"))
        assertTrue(r.getValue("tags").detail.contains("tag.ensure_attach"))
        assertTrue(r.getValue("analysis").detail.contains("suggestions.read"))
    }

    @Test
    fun aFutureServerThatAdvertisesAudioAndAnalysisLightsUpThoseRows() {
        val future = realServer.copy(
            pull = realServer.pull + setOf("asset_file", "analysis_suggestion"),
            analysis = setOf("suggestions.read"),
            mutations = realServer.mutations + setOf("asset.metadata.patch", "tag.ensure_attach", "tag.detach"),
        )
        val r = rows(future)
        assertTrue(r.getValue("audio").available)
        assertTrue(r.getValue("analysis").available)
        assertTrue(r.getValue("metadata").available)
        assertTrue(r.getValue("tags").available)
    }

    @Test
    fun anOldServerThatReportsPlaylistsAsUnsupportedSaysSo() {
        val old = realServer.copy(playlists = "unsupported_until_canonical_playlist_migration", pull = setOf("asset", "interval"))
        val row = rows(old).getValue("playlists")
        assertFalse(row.available)
        assertTrue(row.detail.contains("unsupported_until_canonical_playlist_migration"))
    }

    @Test
    fun readWritePlaylistsAreReportedAsSuch() {
        assertEquals("Read and write.", rows(realServer.copy(playlists = "read_write")).getValue("playlists").detail)
    }

    @Test
    fun missingLibraryFamiliesAreNamed() {
        val row = rows(realServer.copy(pull = setOf("asset"))).getValue("library")
        assertFalse(row.available)
        assertTrue(row.detail.contains("interval"))
    }

    @Test
    fun whenNotConnectedEveryRowIsUnavailableAndSaysSo() {
        val all = capabilityRows(null)
        assertEquals(8, all.size)
        assertTrue(all.none { it.available })
        assertTrue(all.all { it.detail == "Not connected." })
    }

    @Test
    fun everySyncStateHasADistinctNonBlankLabel() {
        val labels = SyncState.entries.map(::syncStateLabel)
        assertTrue(labels.all { it.isNotBlank() })
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun pairingNeedsBothFieldsAndNoPairingInFlight() {
        assertTrue(canPair("https://h", "123456", SyncState.UNCONFIGURED))
        assertFalse(canPair("", "123456", SyncState.UNCONFIGURED))
        assertFalse(canPair("https://h", "  ", SyncState.UNCONFIGURED))
        assertFalse(canPair("https://h", "123456", SyncState.PAIRING))
        assertFalse(canPair("https://h", "123456", SyncState.PULLING))
        assertTrue(canPair("https://h", "123456", SyncState.AUTH_REQUIRED))
    }
}
```

`app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionControllerTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.MobileCredential
import dev.androidjtools.remote.samplelib.SampleLibClient
import dev.androidjtools.remote.samplelib.SampleLibFailure
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SampleLibSessionControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeScheduler : SyncScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun schedule() { scheduled++ }
        override fun cancel() { cancelled++ }
    }

    private val transport = ScriptedTransport()
    private val client = SampleLibClient(transport, SAMPLE_LIB_CLIENT_ID, "install-1")
    private val db = JdbcSqlDatabase()
    private val cache = SqlLibraryCache(db)
    private val scheduler = FakeScheduler()

    private fun store() = SampleLibProfileStore(File(tmp.root, "sl"), FakeCipher())
    private fun manager(store: SampleLibProfileStore) =
        SampleLibSessionManager(store, cache, { _, _ -> client }, { "install-1" })

    @Test
    fun aSuccessfulPairingSchedulesPeriodicSyncAndAFailedOneDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val controller = SampleLibSessionController(manager(store()), backgroundScope, scheduler)

        transport.pairFailure = SampleLibFailure.PairingRejected()
        controller.pair("https://samplelab.example", "000000")
        advanceUntilIdle()
        assertEquals(0, scheduler.scheduled)
        assertEquals("The server rejected the pairing code.", controller.error.value)

        transport.pairFailure = null
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        controller.pair("https://samplelab.example", "246810")
        advanceUntilIdle()
        assertEquals(1, scheduler.scheduled)
        assertEquals(SyncState.ONLINE_IDLE, controller.state.value)
        assertEquals("https://samplelab.example", controller.serverUrl.value)
    }

    @Test
    fun forgetCancelsTheScheduleAndClearsEverything() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val controller = SampleLibSessionController(manager(store), backgroundScope, scheduler)
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        controller.pair("https://samplelab.example", "246810")
        advanceUntilIdle()

        controller.forget()
        advanceUntilIdle()

        assertEquals(1, scheduler.cancelled)
        assertEquals(SyncState.UNCONFIGURED, controller.state.value)
        assertNull(controller.serverUrl.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
    }

    @Test
    fun forgetCancelsAnInFlightRefreshInsteadOfWaitingForIt() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        store.save(SampleLibProfile("https://samplelab.example", "server-1", "principal-1", "install-1"), MobileCredential.issued("token-secret"))
        val gate = CompletableDeferred<Unit>()
        transport.onPull = { gate.await() }
        transport.enqueuePage(page("c1", changes = listOf(assetChange("a1"))))
        val controller = SampleLibSessionController(manager(store), backgroundScope, scheduler)

        controller.refresh()
        advanceUntilIdle()
        assertEquals(SyncState.PULLING, controller.state.value)

        controller.forget()
        advanceUntilIdle()

        assertEquals(SyncState.UNCONFIGURED, controller.state.value)
        assertEquals(StoredSessionResult.Absent, store.load())
        assertTrue(cache.entities("asset").isEmpty())
        assertTrue(!gate.isCompleted)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.ui.sync.*' --tests 'dev.androidjtools.sync.samplelib.SampleLibSessionControllerTest' --offline --console=plain
```

Expected: FAIL to compile with `Unresolved reference: capabilityRows` / `SampleLibSessionController`.

- [ ] **Step 3: Implement the capability rows and labels**

`app/src/main/java/dev/androidjtools/ui/sync/ServerCapabilityRows.kt`:

```kotlin
package dev.androidjtools.ui.sync

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.ServerCapabilities

data class CapabilityRow(val id: String, val label: String, val available: Boolean, val detail: String)

private val PLAYLIST_MODES = setOf("canonical_v1", "read_only", "read_write")

private fun row(id: String, label: String, missing: List<String>, availableDetail: String) =
    if (missing.isEmpty()) {
        CapabilityRow(id, label, true, availableDetail)
    } else {
        CapabilityRow(id, label, false, "Server does not advertise ${missing.joinToString(", ")}.")
    }

/** One row per feature, derived only from what the server advertised in `hello`. */
fun capabilityRows(c: ServerCapabilities?): List<CapabilityRow> {
    if (c == null) {
        return listOf(
            "library" to "Library metadata",
            "playlists" to "Playlists",
            "editor" to "Cue, loop and marker editing",
            "metadata" to "Metadata editing",
            "tags" to "Tag editing",
            "analysis" to "Analysis suggestions",
            "audio" to "Server audio",
            "resume" to "Resumable downloads",
        ).map { (id, label) -> CapabilityRow(id, label, false, "Not connected.") }
    }
    val playlists = when {
        c.playlists !in PLAYLIST_MODES ->
            CapabilityRow("playlists", "Playlists", false, "Server reports playlists as '${c.playlists}'.")
        else -> row(
            "playlists",
            "Playlists",
            listOf("playlist", "playlist_item").filter { it !in c.pull },
            if (c.playlists == "read_write") "Read and write." else "Read-only.",
        )
    }
    return listOf(
        row("library", "Library metadata", listOf("asset", "interval").filter { it !in c.pull }, "Assets, intervals and markers sync from the server."),
        playlists,
        row("editor", "Cue, loop and marker editing", listOf("interval.editor_state.replace").filter { it !in c.mutations }, "Edits are sent as acknowledged mutations."),
        row("metadata", "Metadata editing", listOf("asset.metadata.patch").filter { it !in c.mutations }, "Title, artist and rating edits are supported."),
        row("tags", "Tag editing", listOf("tag.ensure_attach", "tag.detach").filter { it !in c.mutations }, "Tags can be attached and detached."),
        row(
            "analysis",
            "Analysis suggestions",
            listOf("suggestions.read").filter { it !in c.analysis } + listOf("analysis_suggestion").filter { it !in c.pull },
            "Suggestions are read from the server.",
        ),
        row(
            "audio",
            "Server audio",
            listOf("asset_file").filter { it !in c.pull } + listOf("resource_uri", "content_hash").filter { it !in c.media }.map { "media:$it" },
            "Tracks can be streamed and downloaded from the server.",
        ),
        row("resume", "Resumable downloads", listOf("range").filter { it !in c.media }.map { "media:$it" }, "Interrupted downloads can resume."),
    )
}

fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.UNCONFIGURED -> "Not connected"
    SyncState.DISCOVERED -> "Server found"
    SyncState.PAIRING -> "Pairing..."
    SyncState.ONLINE_IDLE -> "Connected and up to date"
    SyncState.PULLING -> "Syncing..."
    SyncState.PUSHING -> "Sending changes..."
    SyncState.PENDING_LOCAL -> "Local changes waiting to sync"
    SyncState.OFFLINE -> "Offline (showing last synced library)"
    SyncState.DEGRADED -> "Connected with problems"
    SyncState.CONFLICT -> "Conflicts need review"
    SyncState.AUTH_REQUIRED -> "Pairing required again"
    SyncState.SERVER_INCOMPATIBLE -> "Server version not supported"
    SyncState.LOCAL_MIGRATION_REQUIRED -> "Local data needs migration"
}

fun canPair(url: String, code: String, state: SyncState): Boolean =
    url.isNotBlank() && code.isNotBlank() && state != SyncState.PAIRING && state != SyncState.PULLING
```

- [ ] **Step 4: Implement the controller**

`app/src/main/java/dev/androidjtools/sync/samplelib/ServerSessionController.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import dev.androidjtools.core.model.SyncState
import dev.androidjtools.remote.samplelib.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface SyncScheduler {
    fun schedule()
    fun cancel()
}

/** What the Sync screen needs; implemented by [SampleLibSessionController] and by test fakes. */
interface ServerSessionController {
    val state: StateFlow<SyncState>
    val capabilities: StateFlow<ServerCapabilities?>
    val error: StateFlow<String?>
    val serverUrl: StateFlow<String?>
    fun pair(baseUrl: String, code: String)
    fun refresh()
    fun forget()
}

class SampleLibSessionController(
    private val manager: SampleLibSessionManager,
    private val scope: CoroutineScope,
    private val scheduler: SyncScheduler,
) : ServerSessionController {
    override val state: StateFlow<SyncState> = manager.state
    override val capabilities: StateFlow<ServerCapabilities?> = manager.capabilities
    override val error: StateFlow<String?> = manager.error
    override val serverUrl: StateFlow<String?> = manager.profile
        .map { it?.baseUrl }
        .stateIn(scope, SharingStarted.Eagerly, manager.profile.value?.baseUrl)

    private var job: Job? = null
    private var lastWasForget = false

    override fun pair(baseUrl: String, code: String) = enqueue(forget = false) {
        manager.pair(baseUrl, code)
        if (manager.profile.value != null) scheduler.schedule()
    }

    override fun refresh() = enqueue(forget = false) { manager.refresh() }

    override fun forget() {
        scheduler.cancel()
        enqueue(forget = true) { withContext(NonCancellable) { manager.forget() } }
    }

    /** A new request supersedes an in-flight pair or refresh, but never cancels a pending forget. */
    private fun enqueue(forget: Boolean, block: suspend () -> Unit) {
        val previous = job
        val previousWasForget = lastWasForget
        lastWasForget = forget
        job = scope.launch {
            if (previousWasForget) previous?.join() else previous?.cancelAndJoin()
            block()
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests 'dev.androidjtools.ui.sync.*' --tests 'dev.androidjtools.sync.samplelib.SampleLibSessionControllerTest' --offline --console=plain
```

Expected: `BUILD SUCCESSFUL`; 9 capability/label tests and 3 controller tests pass.

- [ ] **Step 6: Replace the Sync screen**

Replace `app/src/main/java/dev/androidjtools/ui/sync/SyncScreen.kt` with:

```kotlin
package dev.androidjtools.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.core.provider.AppProviders
import dev.androidjtools.sync.samplelib.ServerSessionController

@Composable
fun SyncScreen(providers: AppProviders, server: ServerSessionController? = null) {
    val state by providers.sync.state.collectAsState()
    val pending by providers.sync.pendingMutationCount.collectAsState()
    if (server == null) {
        LegacySyncContent(providers, state, pending)
    } else {
        ServerSyncContent(server, state, pending)
    }
}

@Composable
private fun LegacySyncContent(providers: AppProviders, state: SyncState, pending: Int) {
    val capabilities by providers.analysis.capabilities.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync & backend", style = MaterialTheme.typography.headlineMedium)
        Text("State: ${state.name}")
        Text("Pending local mutations: $pending")
        Text("Cloud dependency: none")
        Text("Backend capabilities", style = MaterialTheme.typography.titleMedium)
        capabilities.forEach { capability ->
            Text("• ${capability.displayName}: ${if (capability.available) "available" else "unavailable"}")
        }
    }
}

@Composable
private fun ServerSyncContent(server: ServerSessionController, state: SyncState, pending: Int) {
    val capabilities by server.capabilities.collectAsState()
    val error by server.error.collectAsState()
    val savedUrl by server.serverUrl.collectAsState()
    var url by rememberSaveable(savedUrl) { mutableStateOf(savedUrl.orEmpty()) }
    // Deliberately not rememberSaveable: the pairing code must never be written into saved instance state.
    var code by remember { mutableStateOf("") }
    val rows = remember(capabilities) { capabilityRows(capabilities) }
    val busy = state == SyncState.PAIRING || state == SyncState.PULLING

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item { Text("Sync & backend", style = MaterialTheme.typography.headlineMedium) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("State: ${syncStateLabel(state)}", modifier = Modifier.testTag("sync-state"))
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sync-error"))
                }
                Text("Pending local mutations: $pending")
                Text("Cloud dependency: none")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample Lib server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("server-url-field"),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("pairing-code-field"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            server.pair(url, code)
                            code = ""
                        },
                        enabled = canPair(url, code, state),
                        modifier = Modifier.testTag("pair-button"),
                    ) { Text(if (savedUrl == null) "Pair" else "Pair again") }
                    TextButton(
                        onClick = server::refresh,
                        enabled = savedUrl != null && !busy,
                        modifier = Modifier.testTag("refresh-button"),
                    ) { Text("Refresh") }
                    TextButton(
                        onClick = server::forget,
                        enabled = savedUrl != null,
                        modifier = Modifier.testTag("forget-button"),
                    ) { Text("Forget server") }
                }
            }
        }
        item { Text("What this server supports", style = MaterialTheme.typography.titleMedium) }
        items(rows, key = { it.id }) { row ->
            Text(
                "${if (row.available) "Available" else "Unavailable"}: ${row.label}. ${row.detail}",
                modifier = Modifier.testTag("capability-${row.id}"),
            )
        }
    }
}
```

In `app/src/main/java/dev/androidjtools/App.kt` change the signature to add `server`, and pass it to the route:

```kotlin
fun AndroidDjToolsApp(
    providers: AppProviders,
    playerQueueController: PlayerQueueController? = null,
    livePerformanceController: LivePerformanceController? = null,
    server: ServerSessionController? = null,
) {
```

```kotlin
            composable("sync") { SyncScreen(providers, server) }
```

and add `import dev.androidjtools.sync.samplelib.ServerSessionController`.

- [ ] **Step 7: Add the instrumentation test and compile everything**

`app/src/androidTest/java/dev/androidjtools/ui/sync/SyncScreenServerTest.kt`:

```kotlin
package dev.androidjtools.ui.sync

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.fixture.FixtureAppProviders
import dev.androidjtools.remote.samplelib.ServerCapabilities
import dev.androidjtools.sync.samplelib.ServerSessionController
import dev.androidjtools.ui.theme.AndroidDjToolsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncScreenServerTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeController : ServerSessionController {
        override val state = MutableStateFlow(SyncState.UNCONFIGURED)
        override val capabilities = MutableStateFlow<ServerCapabilities?>(null)
        override val error = MutableStateFlow<String?>(null)
        override val serverUrl: StateFlow<String?> = MutableStateFlow(null)
        val pairs = mutableListOf<Pair<String, String>>()
        override fun pair(baseUrl: String, code: String) { pairs += baseUrl to code }
        override fun refresh() = Unit
        override fun forget() = Unit
    }

    private fun show(controller: FakeController) {
        compose.setContent {
            AndroidDjToolsTheme { SyncScreen(FixtureAppProviders.create(), controller) }
        }
    }

    @Test
    fun pairStaysDisabledUntilBothFieldsAreFilled() {
        show(FakeController())
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
        compose.onNodeWithTag("server-url-field").performTextInput("https://samplelab.example")
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
        compose.onNodeWithTag("pairing-code-field").performTextInput("246810")
        compose.onNodeWithTag("pair-button").assertIsEnabled()
    }

    @Test
    fun pairingSendsBothValuesAndClearsTheCodeField() {
        val controller = FakeController()
        show(controller)
        compose.onNodeWithTag("server-url-field").performTextInput("https://samplelab.example")
        compose.onNodeWithTag("pairing-code-field").performTextInput("246810")
        compose.onNodeWithTag("pair-button").performClick()
        assertEquals(listOf("https://samplelab.example" to "246810"), controller.pairs)
        compose.onNodeWithTag("pair-button").assertIsNotEnabled()
    }

    @Test
    fun theErrorAndEveryCapabilityRowAreShown() {
        val controller = FakeController()
        controller.error.value = "The server rejected the pairing code."
        controller.capabilities.value = ServerCapabilities(
            pull = setOf("asset", "interval"),
            mutations = emptySet(),
            playlists = "unsupported_until_canonical_playlist_migration",
            analysis = emptySet(),
            media = emptySet(),
        )
        show(controller)
        compose.onNodeWithTag("sync-error").assertTextContains("rejected the pairing code", substring = true)
        compose.onNodeWithTag("capability-library").assertTextContains("Available", substring = true)
        compose.onNodeWithTag("capability-audio").assertTextContains("asset_file", substring = true)
    }
}
```

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline --console=plain -q
```

Expected: no output, exit 0. (Device execution is "blocked: no device".)

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/dev/androidjtools/ui/sync app/src/main/java/dev/androidjtools/sync/samplelib/ServerSessionController.kt app/src/main/java/dev/androidjtools/App.kt app/src/test/java/dev/androidjtools/ui/sync app/src/test/java/dev/androidjtools/sync/samplelib/SampleLibSessionControllerTest.kt app/src/androidTest/java/dev/androidjtools/ui/sync
git commit -m "feat(ui): add server pairing form and capability-driven Sync screen

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Runtime wiring, background sync and manifest hardening

**Files:**
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/AndroidSqlDatabase.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt`
- Create: `app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSyncWorker.kt`
- Modify: `app/src/main/java/dev/androidjtools/shell/AndroidDjToolsActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/network_security_config.xml`
- Create: `scripts/check_manifest_hardening.py`
- Test: `scripts/test_check_manifest_hardening.py`
- Modify: `.github/workflows/android-ci.yml` (one line in the "Verify production launcher" step)
- Test: `app/src/androidTest/java/dev/androidjtools/sync/samplelib/AndroidSqlDatabaseTest.kt` (compiles locally; runs on device or emulator)

**Interfaces:**
- Consumes: everything from Tasks 1 to 8.
- Produces: `SampleLibRuntime.get(context)` (process-wide singleton with `manager`, `controller`, `scheduler`, `compose(device: AppProviders): AppProviders`), `AndroidSqlDatabase(context, path)`, `SampleLibSyncWorker`, `WorkManagerSyncScheduler`.

- [ ] **Step 1: Write the failing manifest-hardening test**

`scripts/test_check_manifest_hardening.py`:

```python
import unittest

import check_manifest_hardening as chk

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def manifest(application_attrs: str) -> str:
    return f'<manifest {NS}><application {application_attrs}/></manifest>'


GOOD = 'android:allowBackup="false" android:networkSecurityConfig="@xml/network_security_config"'


class ManifestHardeningTest(unittest.TestCase):
    def test_hardened_manifest_has_no_problems(self):
        self.assertEqual([], chk.check(manifest(GOOD)))

    def test_backup_enabled_is_a_problem(self):
        problems = chk.check(manifest(GOOD.replace('"false"', '"true"')))
        self.assertTrue(any("allowBackup" in p for p in problems))

    def test_missing_allow_backup_defaults_to_enabled_and_is_a_problem(self):
        problems = chk.check(manifest('android:networkSecurityConfig="@xml/n"'))
        self.assertTrue(any("allowBackup" in p for p in problems))

    def test_missing_network_security_config_is_a_problem(self):
        problems = chk.check(manifest('android:allowBackup="false"'))
        self.assertTrue(any("networkSecurityConfig" in p for p in problems))

    def test_cleartext_traffic_enabled_is_a_problem(self):
        problems = chk.check(manifest(GOOD + ' android:usesCleartextTraffic="true"'))
        self.assertTrue(any("usesCleartextTraffic" in p for p in problems))

    def test_manifest_without_application_is_a_problem(self):
        self.assertTrue(chk.check(f"<manifest {NS}/>"))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to verify it fails**

```bash
python3 -I -m unittest discover -s scripts -p 'test_check_manifest_hardening.py' 2>&1 | tail -3
```

Expected: `ModuleNotFoundError: No module named 'check_manifest_hardening'`.

- [ ] **Step 3: Implement the checker and wire it into CI**

`scripts/check_manifest_hardening.py`:

```python
#!/usr/bin/env python3
"""Fail unless the (apkanalyzer-printed) manifest keeps credentials out of backups and blocks cleartext."""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"


def check(manifest_xml: str) -> list[str]:
    application = ET.fromstring(manifest_xml).find("application")
    if application is None:
        return ["manifest has no <application> element"]
    problems = []
    if application.get(ANDROID + "allowBackup") != "false":
        problems.append('android:allowBackup must be "false" (credentials and cache must not enter backups)')
    if application.get(ANDROID + "networkSecurityConfig") is None:
        problems.append("android:networkSecurityConfig is required (cleartext only for loopback)")
    if application.get(ANDROID + "usesCleartextTraffic") == "true":
        problems.append('android:usesCleartextTraffic must not be "true"')
    return problems


def main(argv: list[str]) -> int:
    with open(argv[1], encoding="utf-8") as handle:
        problems = check(handle.read())
    for problem in problems:
        print(f"manifest hardening FAILED: {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
```

In `.github/workflows/android-ci.yml`, in the "Verify production launcher and record APK checksums" step, directly after the line

```bash
          "$apkanalyzer_bin" manifest print "$debug_apk" > /tmp/debug-manifest.xml
```

add

```bash
          python3 scripts/check_manifest_hardening.py /tmp/release-manifest.xml
```

Run: `python3 -I -m unittest discover -s scripts -p 'test_*.py' 2>&1 | tail -3`
Expected: `OK` (all script tests, including the earlier signature tests).

- [ ] **Step 4: Add the Android SQL implementation and its device test**

`app/src/main/java/dev/androidjtools/sync/samplelib/AndroidSqlDatabase.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Production [SqlDatabase] on Android's SQLite. Schema management lives in [SqlLibraryCache]. */
class AndroidSqlDatabase(context: Context, path: String) : SqlDatabase, AutoCloseable {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun <T> transaction(block: (SqlConnection) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(AndroidSqlConnection(db))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = helper.close()
}

private class AndroidSqlConnection(private val db: SQLiteDatabase) : SqlConnection {
    override fun execute(sql: String, vararg args: Any?) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args))
    }

    override fun <T> query(sql: String, vararg args: Any?, map: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(map(CursorRow(cursor))) }
        }
}

private class CursorRow(private val cursor: Cursor) : SqlRow {
    override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
    override fun long(index: Int): Long = cursor.getLong(index)
}
```

`app/src/androidTest/java/dev/androidjtools/sync/samplelib/AndroidSqlDatabaseTest.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import androidx.test.platform.app.InstrumentationRegistry
import dev.androidjtools.remote.samplelib.EntityChange
import dev.androidjtools.remote.samplelib.OpaqueRevision
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Runs the cache contract against Android's real SQLite (API 28 ships SQLite 3.22, so no UPSERT). */
class AndroidSqlDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "samplelib-test.db")
    private lateinit var db: AndroidSqlDatabase

    @Before
    fun open() {
        file.delete()
        db = AndroidSqlDatabase(context, file.path)
    }

    @After
    fun close() {
        db.close()
        file.delete()
    }

    private fun change(id: String, revision: String) =
        EntityChange("asset", id, OpaqueRevision(revision), "s", buildJsonObject { put("title", id) })

    @Test
    fun cacheRoundTripsReplacesAndSurvivesReopen() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(listOf(change("a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))
        cache.applyPage(listOf(change("a1", "r2")), emptyList(), CacheMeta("c2", "g", "s"))
        db.close()

        db = AndroidSqlDatabase(context, file.path)
        val reopened = SqlLibraryCache(db)
        assertEquals(listOf("r2"), reopened.entities("asset").map { it.revision })
        assertEquals("c2", reopened.meta().cursor)
    }

    @Test
    fun aThrowingTransactionRollsBack() {
        val cache = SqlLibraryCache(db)
        cache.applyPage(listOf(change("a1", "r1")), emptyList(), CacheMeta("c1", "g", "s"))
        try {
            db.transaction { conn ->
                conn.execute("DELETE FROM entities")
                error("boom")
            }
            fail("expected the transaction to throw")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }
}
```

- [ ] **Step 5: Add the runtime, worker and scheduler**

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibSyncWorker.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.androidjtools.core.model.SyncState
import java.util.concurrent.TimeUnit

class SampleLibSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = SampleLibRuntime.get(applicationContext).manager
        manager.refresh()
        return when (manager.state.value) {
            SyncState.OFFLINE, SyncState.DEGRADED -> Result.retry()
            else -> Result.success()
        }
    }
}

/** Bounded periodic sync on unmetered networks only. Manual refresh is always available. */
class WorkManagerSyncScheduler(context: Context) : SyncScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule() {
        val request = PeriodicWorkRequestBuilder<SampleLibSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        workManager.cancelUniqueWork(UNIQUE_NAME)
    }

    private companion object {
        const val UNIQUE_NAME = "samplelib-periodic-sync"
    }
}
```

`app/src/main/java/dev/androidjtools/sync/samplelib/SampleLibRuntime.kt`:

```kotlin
package dev.androidjtools.sync.samplelib

import android.content.Context
import dev.androidjtools.core.provider.AppProviders
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide Sample Lib stack shared by the activity and the background worker. */
class SampleLibRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext

    // noBackupFilesDir is excluded from backups even if the manifest policy ever changes.
    private val directory = File(appContext.noBackupFilesDir, "samplelib").also { it.mkdirs() }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cache: LibraryCache = SqlLibraryCache(AndroidSqlDatabase(appContext, File(directory, "library-cache.db").path))
    val manager = SampleLibSessionManager(SampleLibProfileStore(directory, AesGcmKeystoreCipher()), cache)
    val scheduler: SyncScheduler = WorkManagerSyncScheduler(appContext)
    val controller: ServerSessionController = SampleLibSessionController(manager, scope, scheduler)
    private val projection = SampleLibProjectionState(cache, scope)

    /** Wraps the device providers once; their content then follows pairing state at runtime. */
    fun compose(device: AppProviders): AppProviders {
        val serverLibrary = SampleLibLibraryProvider(projection)
        return device.copy(
            runtime = CompositeRuntimeProvider(device.runtime, serverLibrary, scope),
            library = CompositeLibraryProvider(serverLibrary, device.library, scope),
            preparation = SampleLibPreparationProvider(projection, scope),
            playback = ServerAwarePlayback(device.playback) { projection.projection.value.hasTrack(it) },
            sync = SampleLibSyncProvider(manager),
        )
    }

    companion object {
        @Volatile
        private var instance: SampleLibRuntime? = null

        fun get(context: Context): SampleLibRuntime =
            instance ?: synchronized(this) {
                instance ?: SampleLibRuntime(context.applicationContext).also { instance = it }
            }
    }
}
```

- [ ] **Step 6: Wire the activity, manifest and network policy**

In `AndroidDjToolsActivity.kt`:

1. Add imports: `androidx.lifecycle.lifecycleScope`, `dev.androidjtools.sync.samplelib.SampleLibRuntime`, `kotlinx.coroutines.launch`.
2. Replace

```kotlin
        val providers = deviceProviders.providers
```

with

```kotlin
        val runtime = SampleLibRuntime.get(this)
        val providers = runtime.compose(deviceProviders.providers)
```

(`PlayerQueueController(playback = providers.playback, ...)` now receives the server-aware playback.)
3. Replace the `setContent` body's call with

```kotlin
                AndroidDjToolsApp(providers, playerQueueController, livePerformanceController, server = runtime.controller)
```

4. Directly after the `setContent { ... }` block add:

```kotlin
        lifecycleScope.launch {
            runtime.manager.refresh()
            if (runtime.manager.profile.value != null) runtime.scheduler.schedule()
        }
```

In `AndroidManifest.xml`, change the `<application` attributes `android:allowBackup="true"` to

```xml
        android:allowBackup="false"
        android:networkSecurityConfig="@xml/network_security_config"
```

Create `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- HTTPS everywhere except this device itself (a Sample Lib server on loopback for development). -->
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">127.0.0.1</domain>
        <domain includeSubdomains="false">localhost</domain>
    </domain-config>
</network-security-config>
```

- [ ] **Step 7: Build and verify the shipped manifest**

```bash
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --console=plain
"$ANDROID_HOME"/cmdline-tools/latest/bin/apkanalyzer manifest print app/build/outputs/apk/release/app-release.apk > /tmp/release-manifest.xml
python3 -I scripts/check_manifest_hardening.py /tmp/release-manifest.xml && echo "manifest hardened"
```

Expected: `BUILD SUCCESSFUL` (lint must stay at 0 errors; fix any lint finding in the new files rather than suppressing it) and `manifest hardened`. If `apkanalyzer` is not found, use `find "$ANDROID_HOME" -name apkanalyzer`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main app/src/androidTest/java/dev/androidjtools/sync scripts .github/workflows/android-ci.yml
git commit -m "feat(samplelib): wire the sync stack into the app with hardened backup and network policy

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 10: End-to-end verification against a real server, docs and changelog

**Files:**
- Modify: `app/src/test/java/dev/androidjtools/remote/samplelib/SampleLibLiveContractTest.kt` (add one test and imports)
- Modify: `CHANGELOG.md`
- Modify: `docs/superpowers/specs/2026-10-12-sample-lab-integration-design.md` (status line only)

**Interfaces:**
- Consumes: `SampleLibSessionManager`, `SampleLibProfileStore`, `SqlLibraryCache`, `LibraryProjector`, `JdbcSqlDatabase`, `FakeCipher` (test helpers from earlier tasks).

- [ ] **Step 1: Add the failing live end-to-end test**

Add these imports to `SampleLibLiveContractTest.kt`:

```kotlin
import dev.androidjtools.core.model.SyncState
import dev.androidjtools.sync.samplelib.FakeCipher
import dev.androidjtools.sync.samplelib.JdbcSqlDatabase
import dev.androidjtools.sync.samplelib.LibraryProjector
import dev.androidjtools.sync.samplelib.SampleLibProfileStore
import dev.androidjtools.sync.samplelib.SampleLibSessionManager
import dev.androidjtools.sync.samplelib.SqlLibraryCache
import dev.androidjtools.sync.samplelib.StoredSessionResult
import java.nio.file.Files
```

and this test inside the class:

```kotlin
    @Test
    fun managerPairsSyncsAndProjectsTheRealLibraryThenForgets() = runBlocking {
        requireLive()
        val dir = Files.createTempDirectory("samplelib-live-store").toFile()
        val db = JdbcSqlDatabase()
        try {
            val cache = SqlLibraryCache(db)
            val store = SampleLibProfileStore(dir, FakeCipher())
            val manager = SampleLibSessionManager(store, cache)

            manager.pair(baseUrl, pairingCode)

            assertEquals(manager.error.value, SyncState.ONLINE_IDLE, manager.state.value)
            assertTrue("interval.editor_state.replace" in manager.capabilities.value!!.mutations)
            val projection = LibraryProjector.project(cache.entities("asset"), cache.entities("interval"))
            val track = projection.tracks.single { it.id == "asset-1" }
            assertEquals("A", track.title)
            assertEquals("Artist", track.artist)
            assertEquals(3, track.rating)
            val cue = projection.cues.getValue("asset-1").single { it.id == "marker-a" }
            assertEquals(1500L, cue.positionMs)
            assertEquals(0xFF112233L, cue.colorArgb)

            // A second refresh reconnects with the pinned identity and finds nothing new.
            val before = cache.entities("asset").map { it.revision }
            manager.refresh()
            assertEquals(SyncState.ONLINE_IDLE, manager.state.value)
            assertEquals(before, cache.entities("asset").map { it.revision })

            manager.forget()
            assertEquals(StoredSessionResult.Absent, store.load())
            assertTrue(cache.entities("asset").isEmpty())
            assertEquals(SyncState.UNCONFIGURED, manager.state.value)
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }
```

- [ ] **Step 2: Run it against a real server**

```bash
GRADLE_ARGS=--offline scripts/run_live_samplelib_contract.sh 2>&1 | tail -8
```

Expected: `live contract: 6 executed, 0 failed`. If the new test fails, the failure message names the assertion; fix the production code (not the test) unless the assertion is wrong about the documented wire shapes in the spec's evidence table.

- [ ] **Step 3: Negative control**

```bash
SAMPLE_LIB_LIVE_URL=http://127.0.0.1:1 SAMPLE_LIB_LIVE_PAIRING_CODE=000000 \
  ./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest --tests '*SampleLibLiveContractTest' --offline --console=plain 2>&1 | grep -E 'tests completed|BUILD'
```

Expected: `BUILD FAILED` with `... tests completed, ... failed` (nothing listens on port 1), proving the live tests can fail.

- [ ] **Step 4: Update the changelog and the spec status**

In `CHANGELOG.md`, under `## Unreleased`, add a new section above `### Release engineering`:

```markdown
### Sample Lab integration (S1: connect and read)

- Pair with a Sample Lib server from the Sync screen; the server's library, cues and loops are synced into a local cache and shown in the Library. The Sync screen lists which server capabilities (library, playlists, editing, analysis, audio) are and are not available, and why.
- The credential is encrypted with an Android Keystore key and stored outside backups; `allowBackup` is now `false`, and cleartext traffic is limited to loopback.
- Server tracks cannot be played yet (server audio arrives in S2); device tracks stay visible and playable, and tapping a server track explains why.
- Server addresses with a reverse-proxy path prefix now work (the sync endpoints no longer drop the prefix).
- Periodic background sync (unmetered networks) and manual refresh; Forget server removes the profile, credential and cache.
```

In the spec, change the status line `Status: approved in conversation 2026-10-12; awaiting written-spec review.` to

```markdown
Status: spec approved 2026-10-12. S1 implementation plan: `docs/superpowers/plans/2026-10-12-samplelab-s1-connect-and-read.md`. S2 to S5 not yet planned.
```

- [ ] **Step 5: Final verification, in this order**

```bash
python3 -I -m unittest discover -s scripts -p 'test_*.py' 2>&1 | tail -3
PYTHONDONTWRITEBYTECODE=1 uv run --quiet --with-requirements test-server/requirements-test.txt python -I -m unittest discover -s test-server -p 'test_*.py' 2>&1 | tail -3
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug :app:assembleRelease --offline --console=plain 2>&1 | tail -4
GRADLE_ARGS=--offline scripts/run_live_samplelib_contract.sh 2>&1 | tail -2
uv run --quiet --with mkdocs mkdocs build --strict -d /tmp/mkdocs-site 2>&1 | tail -2
```

Expected: script tests `OK`; protocol tests `OK` (32); `BUILD SUCCESSFUL`; `live contract: 6 executed, 0 failed`; docs `Documentation built`.

Record in the commit message exactly what was and was not verified. Device-only tests (`AesGcmKeystoreCipherTest`, `AndroidSqlDatabaseTest`, `PlaybackNoticeTest`, `SyncScreenServerTest`) compile but have not run: state "blocked: no device" and do not call them passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/test CHANGELOG.md docs/superpowers/specs
git commit -m "test(samplelib): verify pair, sync, projection and forget against a real server

Verified: unit tests, lint, debug and release assemble, live contract against a real
Sample Lib server built from a clean HEAD export (6 executed, 0 failed), negative control.
Not verified (no device available): Keystore cipher, Android SQLite, Compose screens.

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Spec coverage map (S1)

| Spec S1 requirement | Task |
| --- | --- |
| Server profile + pairing UI, status from `SyncState` | 8 |
| Capability panel with reasons | 8 |
| `SampleLibProfileStore`, Keystore credential, `allowBackup` off | 3, 9 |
| `SampleLibSessionManager` failure mapping, generation and `cursor_expired` reset | 5, 6 |
| `LibraryCache` plain SQLite, cursor in the same transaction | 1, 9 |
| `SampleLibSyncEngine` paging, tombstones, `pull_page_max` | 5 |
| Triggers: on connect, manual refresh, WorkManager periodic | 8, 9 |
| Providers composed at the activity; unpaired uses device providers | 7, 9 |
| Server-only tracks not playable, with explanation | 7 |
| Network security config (cleartext loopback only) | 9 |
| Tests: unit, live contract, UI, evidence honesty | all, 10 |
| Content-hash match to device files | **deferred after S2** (deviation 2) |
