package me.rerere.rikkahub.data.db

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.sync.DatabaseBackup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** These fixtures retain the published version and Room identity; no source DB is relabelled. */
internal enum class ReleasedDatabase(val resource: String, val personalUsage: Boolean = false) {
    ORIGINAL_32("me.rerere.rikkahub.data.db.AppDatabase/32.json"),
    PERSONAL_33("me.rerere.rikkahub.data.db.AppDatabase/33.json", true),
    UPSTREAM_33("database-history/upstream-33.json"),
    UPSTREAM_34("me.rerere.rikkahub.data.db.AppDatabase/34.json");

    fun create(file: File, malformed: Boolean = false) {
        val schema = readDatabaseSchema(resource)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { value ->
                val entity = value.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = schema.getValue("version").jsonPrimitive.int
            db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('chat', 'preserved chat', '[]', 1, 2)")
            listOf(3, 0, 1, 1).forEachIndexed { id, index ->
                db.execSQL("INSERT INTO message_node VALUES ('node-$id', 'chat', $index, '[]', 0)")
            }
            db.execSQL("INSERT INTO memory_file VALUES ('assistant', 'profile.md', 'preserved memory', 9)")
            db.execSQL("INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) VALUES ('workspace', 'preserved workspace', '/workspace', 'DISABLED', 1, 2)")
            db.execSQL("INSERT INTO usage_record (created_at, source, prompt_tokens, completion_tokens, cached_tokens) VALUES (1, 'CHAT', 100, 10, 0), (2, 'CHAT', 100, 10, 50)")
            if (personalUsage) {
                db.execSQL("UPDATE usage_record SET prompt_tokens_reported=1, cached_tokens_reported=1, cache_write_tokens=0 WHERE id=1")
                db.execSQL("UPDATE usage_record SET cache_write_tokens=25 WHERE id=2")
            }
            if (this == UPSTREAM_34) {
                db.execSQL("UPDATE workspaces SET storage_mounts='[{\"path\":\"/shared\",\"writable\":false}]'")
            }
            if (malformed) db.execSQL("ALTER TABLE usage_record DROP COLUMN completion_tokens")
        }
    }

    fun assertPreserved(db: SupportSQLiteDatabase) {
        assertTargetIdentity(db)
        db.query("SELECT title FROM ConversationEntity").use {
            assertTrue(it.moveToFirst()); assertEquals("preserved chat", it.getString(0)); assertFalse(it.moveToNext())
        }
        db.query("SELECT content FROM memory_file").use {
            assertTrue(it.moveToFirst()); assertEquals("preserved memory", it.getString(0))
        }
        db.query("SELECT prompt_tokens, completion_tokens, cached_tokens, prompt_tokens_reported, cached_tokens_reported, cache_write_tokens FROM usage_record ORDER BY id").use {
            assertTrue(it.moveToNext())
            assertEquals(100, it.getInt(0)); assertEquals(10, it.getInt(1)); assertEquals(0, it.getInt(2))
            assertEquals(1, it.getInt(3)); assertEquals(if (personalUsage) 1 else 0, it.getInt(4))
            if (personalUsage) assertEquals(0, it.getInt(5)) else assertTrue(it.isNull(5))
            assertTrue(it.moveToNext())
            assertEquals(50, it.getInt(2)); assertEquals(if (personalUsage) 0 else 1, it.getInt(3))
            assertEquals(if (personalUsage) 0 else 1, it.getInt(4))
            if (personalUsage) assertEquals(25, it.getInt(5)) else assertTrue(it.isNull(5))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT storage_mounts FROM workspaces").use {
            assertTrue(it.moveToFirst())
            assertEquals(if (this == UPSTREAM_34) "[{\"path\":\"/shared\",\"writable\":false}]" else "[]", it.getString(0))
        }
        db.query("SELECT node_index FROM message_node WHERE conversation_id='chat' ORDER BY node_index").use {
            assertEquals(listOf(0, 1, 1, 3), buildList { while (it.moveToNext()) add(it.getInt(0)) })
        }
        db.query("EXPLAIN QUERY PLAN SELECT * FROM message_node WHERE conversation_id='chat' ORDER BY node_index").use {
            val details = buildList { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("detail"))) }
            assertTrue(details.toString(), details.any { line -> "index_message_node_conversation_id_node_index" in line })
            assertFalse(details.toString(), details.any { line -> "TEMP B-TREE" in line })
        }
        db.query("SELECT name FROM sqlite_master WHERE name='index_message_node_conversation_id'").use {
            assertFalse(it.moveToFirst())
        }
        db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}

internal fun readDatabaseSchema(resource: String): JsonObject =
    checkNotNull(ReleasedDatabase::class.java.classLoader!!.getResourceAsStream(resource)) { "Missing schema fixture: $resource" }
        .use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject.getValue("database").jsonObject }

internal fun assertTargetIdentity(db: SupportSQLiteDatabase) {
    val target = readDatabaseSchema("me.rerere.rikkahub.data.db.AppDatabase/35.json")
    assertEquals(35, db.version)
    db.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
        assertTrue(it.moveToFirst()); assertEquals(target.getValue("identityHash").jsonPrimitive.content, it.getString(0))
    }
}

internal fun isolatedDatabaseContext(base: Context, directory: File): Context = object : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
    override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
    override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
    override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
        else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
}

/** Real Room configuration and migrations; only Android-only Requery/libsimple JNI is substituted. */
internal fun nativeRoom(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
    AppDatabaseFactory.create(context, name, FrameworkSQLiteOpenHelperFactory(), initializeSearch = {})

/** Equivalent integrity/checkpoint boundary on Robolectric's AOSP native SQLite implementation. */
internal fun normalizeNativeDatabase(@Suppress("UNUSED_PARAMETER") context: Context, file: File) {
    SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
            check(it.moveToFirst() && it.getInt(0) == 0)
        }
        db.rawQuery("PRAGMA integrity_check", null).use {
            check(it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext())
        }
    }
    DatabaseBackup.removeSidecars(file)
}
