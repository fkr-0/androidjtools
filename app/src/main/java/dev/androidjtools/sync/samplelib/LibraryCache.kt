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

    /** Returns the entities for each requested type in a single transaction. Missing types map to empty lists. */
    fun snapshot(vararg entityTypes: String): Map<String, List<CachedEntity>>

    /** Drops every entity and the sync metadata; the schema version marker is kept. */
    fun reset()
}

class SqlLibraryCache(private val db: SqlDatabase) : LibraryCache {
    private val version = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = version.asStateFlow()

    /**
     * Schema creation and the version check run once, lazily, on the first public call, so constructing the
     * cache performs no database access (the caller may be on the main thread). A failure is not remembered:
     * the next call tries again.
     */
    @Volatile
    private var schemaReady = false
    private val schemaLock = Any()

    private fun ensureSchema() {
        if (schemaReady) return
        synchronized(schemaLock) {
            if (schemaReady) return
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
            schemaReady = true
        }
    }

    override fun meta(): CacheMeta {
        ensureSchema()
        return db.transaction { conn ->
            val values = conn.query("SELECT key, value FROM meta") { it.string(0) to it.string(1) }.toMap()
            CacheMeta(values[KEY_CURSOR], values[KEY_GENERATION], values[KEY_SERVER])
        }
    }

    override fun applyPage(changes: List<EntityChange>, tombstones: List<Tombstone>, meta: CacheMeta) {
        ensureSchema()
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

    override fun entities(entityType: String): List<CachedEntity> {
        ensureSchema()
        return db.transaction { conn ->
            conn.query(
                "SELECT entity_id, revision, schema_id, value_json FROM entities WHERE entity_type = ? ORDER BY entity_id",
                entityType,
            ) { row ->
                CachedEntity(entityType, row.string(0).orEmpty(), row.string(1).orEmpty(), row.string(2).orEmpty(), row.string(3).orEmpty())
            }
        }
    }

    override fun snapshot(vararg entityTypes: String): Map<String, List<CachedEntity>> {
        ensureSchema()
        return db.transaction { conn ->
            entityTypes.associateWith { entityType ->
                conn.query(
                    "SELECT entity_id, revision, schema_id, value_json FROM entities WHERE entity_type = ? ORDER BY entity_id",
                    entityType,
                ) { row ->
                    CachedEntity(entityType, row.string(0).orEmpty(), row.string(1).orEmpty(), row.string(2).orEmpty(), row.string(3).orEmpty())
                }
            }
        }
    }

    override fun reset() {
        ensureSchema()
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
