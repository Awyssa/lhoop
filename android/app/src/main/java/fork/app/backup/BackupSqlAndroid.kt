// Fork-owned. The two databases the server backup touches on the phone: the core's, read only, and
// the delta file it writes.
package fork.app.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import androidx.room.RoomDatabase
import java.io.File

private fun Cursor.readAll(): List<List<Any?>> = buildList {
    val width = columnCount
    while (moveToNext()) {
        add(
            List(width) { i ->
                when (getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
                    Cursor.FIELD_TYPE_STRING -> getString(i)
                    else -> getBlob(i)
                }
            },
        )
    }
}

/**
 * The core's database, through Room's own connection and for reading only. Every query the backup
 * makes is small: an hour of one table, or a sum over a window. So none holds the connection long,
 * and none competes with the strap's writes through a second connection of its own.
 */
internal class RoomReader(private val db: RoomDatabase) : SqlReader {
    override fun rows(sql: String, args: List<Any?>): List<List<Any?>> =
        db.query(sql, args.toTypedArray()).use { it.readAll() }
}

/** A delta file on the phone. */
internal class AndroidDeltaFile(file: File) : DeltaSink {

    // NO_LOCALIZED_COLLATORS: without it Android adds an `android_metadata` table to every database it
    // creates. The app's own database has a table of that name, which a delta must be able to carry.
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(
        file.path,
        null,
        SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    init {
        // One whole file with nothing beside it. Write-ahead logging would keep new rows in a second
        // file until a checkpoint, and the server opens what it is sent read-only.
        db.rawQuery("PRAGMA journal_mode = DELETE", null).use { it.moveToFirst() }
        // The file is rebuilt from the database if the phone dies, so it need not survive that.
        db.execSQL("PRAGMA synchronous = OFF")
    }

    /** Only queries with no arguments are needed of a delta file: the checksums of what it holds. */
    override fun rows(sql: String, args: List<Any?>): List<List<Any?>> {
        require(args.isEmpty()) { "a delta file is read without arguments" }
        return db.rawQuery(sql, null).use { it.readAll() }
    }

    override fun exec(sql: String) = db.execSQL(sql)

    override fun insertAll(sql: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        db.beginTransaction()
        try {
            db.compileStatement(sql).use { statement ->
                for (row in rows) {
                    statement.clearBindings()
                    row.forEachIndexed { i, value -> statement.bind(i + 1, value) }
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun close() = db.close()

    private fun SQLiteStatement.bind(index: Int, value: Any?) = when (value) {
        null -> bindNull(index)
        is Long -> bindLong(index, value)
        is Double -> bindDouble(index, value)
        is String -> bindString(index, value)
        is ByteArray -> bindBlob(index, value)
        else -> error("a value SQLite has no kind for: ${value.javaClass.simpleName}")
    }
}
