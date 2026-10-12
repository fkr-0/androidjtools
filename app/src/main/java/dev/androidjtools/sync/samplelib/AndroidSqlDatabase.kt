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
