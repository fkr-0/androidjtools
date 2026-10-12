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
    fun failureAtTheTombstoneRollsBackTheEarlierUpserts() = assertPageRollsBackWhenExecuteFails(3)

    @Test
    fun failureAtTheCursorWriteRollsBackEntitiesAndTombstone() = assertPageRollsBackWhenExecuteFails(4)

    @Test
    fun failureAtTheLastMetaWriteRollsBackEntitiesTombstoneAndCursor() = assertPageRollsBackWhenExecuteFails(6)

    /** Page order is: a2 upsert (1), a3 upsert (2), a1 tombstone (3), cursor (4), generation (5), server (6). */
    private fun assertPageRollsBackWhenExecuteFails(failingExecute: Int) {
        val failing = FailingSqlDatabase(db)
        val cache = SqlLibraryCache(failing)
        cache.applyPage(listOf(change("asset", "a1", "r1")), emptyList(), CacheMeta("c1", "g1", "s1"))
        val changesBefore = cache.changes.value

        failing.failOnNthExecute(failingExecute)
        try {
            cache.applyPage(
                listOf(change("asset", "a2", "r1"), change("asset", "a3", "r1")),
                listOf(tombstone("asset", "a1")),
                CacheMeta("c2", "g2", "s2"),
            )
            fail("expected the injected failure")
        } catch (expected: IllegalStateException) {
        }

        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
        assertEquals(CacheMeta("c1", "g1", "s1"), cache.meta())
        assertEquals(changesBefore, cache.changes.value)
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
    fun constructingTheCacheDoesNoDatabaseWorkAndTheFirstUseCreatesTheSchema() {
        val counting = CountingSqlDatabase(db)
        val cache = SqlLibraryCache(counting)
        assertEquals(0, counting.transactions)
        assertEquals(emptyList<String>(), tableNames())

        cache.meta()

        assertTrue(counting.transactions > 0)
        assertEquals(listOf("entities", "meta"), tableNames())
    }

    @Test
    fun aFirstUseFailureIsRetriedOnTheNextCall() {
        val failing = FailingSqlDatabase(db)
        val cache = SqlLibraryCache(failing)
        failing.failOnNthExecute(1)
        try {
            cache.entities("asset")
            fail("expected the injected failure during the schema setup")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(emptyList<String>(), tableNames())

        assertTrue(cache.entities("asset").isEmpty())

        assertEquals(listOf("entities", "meta"), tableNames())
    }

    private fun tableNames(): List<String> = db.transaction { conn ->
        conn.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { it.string(0).orEmpty() }
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

/** Counts how often the database is touched, to prove that constructing a cache does no database work. */
class CountingSqlDatabase(private val delegate: SqlDatabase) : SqlDatabase {
    var transactions = 0
        private set

    override fun <T> transaction(block: (SqlConnection) -> T): T {
        transactions++
        return delegate.transaction(block)
    }
}
