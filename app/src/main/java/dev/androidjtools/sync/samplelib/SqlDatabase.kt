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
