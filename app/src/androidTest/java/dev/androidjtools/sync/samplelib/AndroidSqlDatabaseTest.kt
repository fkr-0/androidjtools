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
            db.transaction<Unit> { conn ->
                conn.execute("DELETE FROM entities")
                error("boom")
            }
            fail("expected the transaction to throw")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(listOf("a1"), cache.entities("asset").map { it.entityId })
    }
}
