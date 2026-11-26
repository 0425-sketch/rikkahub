package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import me.rerere.rikkahub.data.db.APP_DATABASE_VERSION
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import java.io.File

internal object DatabaseBackup {
    const val ARCHIVE_DATABASE = "rikka_hub.db"
    const val WAL = "rikka_hub-wal"
    const val SHM = "rikka_hub-shm"

    /** VACUUM INTO includes committed WAL contents in a consistent, standalone snapshot. */
    fun createSnapshot(database: SupportSQLiteDatabase, destination: File) {
        check(!destination.exists()) { "Backup snapshot already exists" }
        database.execSQL("VACUUM main INTO ?", arrayOf(destination.absolutePath))
    }

    /** Reads the on-disk schema (including its user_version) without running Room's open callbacks. */
    fun withValidatedSource(context: Context, databaseFile: File, read: (SupportSQLiteDatabase) -> Unit = {}) {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READONLY),
        )
        // The default corruption handler can delete the database. A rescue must leave it in place.
        SQLiteDatabase.openDatabase(configuration, null) { error("Database is corrupt") }.use { database ->
            requireInitializedVersion(database.version)
            checkIntegrity(database)
            read(database)
        }
    }

    fun createFileSnapshot(context: Context, databaseFile: File, destination: File) {
        withValidatedSource(context, databaseFile) { createSnapshot(it, destination) }
    }

    /** Only opens the staged copy. The caller must place its matching WAL beside it first. */
    fun normalize(context: Context, databaseFile: File): Int {
        require(databaseFile.isFile && databaseFile.length() > 0) { "Backup database is missing or empty" }
        val configuration = SQLiteConfiguration.configure(
            context,
            SQLiteDatabaseConfiguration(databaseFile.absolutePath, SQLiteDatabase.OPEN_READWRITE)
        )
        val version = SQLiteDatabase.openDatabase(configuration, null) {
            error("Backup database is corrupt")
        }.use { database ->
            checkpoint(database)
            checkIntegrity(database)
            database.version
        }
        removeSidecars(databaseFile)
        return version
    }

    private fun requireInitializedVersion(version: Int) {
        require(version > 0) { "Database has no initialized RikkaHub schema (version $version)" }
    }

    fun requireRestorableVersion(version: Int) {
        // SQLiteOpenHelper treats zero as a new database and would initialize an empty replacement.
        requireInitializedVersion(version)
        require(version <= APP_DATABASE_VERSION) {
            "Backup database schema $version is newer than this app supports ($APP_DATABASE_VERSION)"
        }
    }

    private fun checkIntegrity(database: SupportSQLiteDatabase) {
        database.query("PRAGMA integrity_check").use { cursor ->
            check(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) {
                "Backup database failed its integrity check"
            }
        }
    }

    fun checkpoint(database: SupportSQLiteDatabase) {
        database.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Could not checkpoint the backup database"
            }
        }
    }

    fun removeSidecars(databaseFile: File) {
        // All connections must be closed and committed WAL data checkpointed before removal.
        check(File(databaseFile.path + "-wal").length() == 0L) { "Backup WAL was not fully checkpointed" }
        for (suffix in listOf("-wal", "-shm")) {
            val sidecar = File(databaseFile.path + suffix)
            check(!sidecar.exists() || sidecar.delete()) { "Could not remove staged database sidecar" }
        }
    }
}
