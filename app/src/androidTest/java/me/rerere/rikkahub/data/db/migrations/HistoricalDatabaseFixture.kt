package me.rerere.rikkahub.data.db.migrations

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.SQLiteDatabase
import io.requery.android.database.sqlite.SQLiteDatabaseConfiguration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/** Recreate released schemas from their unmodified Room exports, with their real versions/hashes. */
internal enum class HistoricalDatabaseFixture(val asset: String, val hasPresence: Boolean = false) {
    ORIGINAL_32("me.rerere.rikkahub.data.db.AppDatabase/32.json"),
    PERSONAL_33("me.rerere.rikkahub.data.db.AppDatabase/33.json", true),
    UPSTREAM_33("database-history/upstream-33.json"),
    UPSTREAM_34("me.rerere.rikkahub.data.db.AppDatabase/34.json");

    fun create(context: Context, file: File, malformed: Boolean = false) {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use {
            Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject.getValue("database").jsonObject
        }
        SQLiteDatabase.openDatabase(
            SQLiteConfiguration.configure(context, SQLiteDatabaseConfiguration(file.path, SQLiteDatabase.CREATE_IF_NECESSARY)),
            null,
            null,
        ).use { db ->
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
            db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('conversation', 'preserved chat', '[]', 1, 2)")
            db.execSQL("INSERT INTO message_node VALUES ('message', 'conversation', 7, '[]', 0)")
            db.execSQL("INSERT INTO memory_file VALUES ('assistant', 'profile.md', 'preserved memory', 9)")
            db.execSQL("INSERT INTO workspaces (id, name, root, shell_status, created_at, updated_at) VALUES ('workspace', 'preserved workspace', '/workspace', 'DISABLED', 1, 2)")
            db.execSQL("INSERT INTO usage_record (created_at, source, prompt_tokens, completion_tokens, cached_tokens) VALUES (1, 'CHAT', 100, 10, 0), (2, 'CHAT', 100, 10, 50)")
            if (hasPresence) {
                db.execSQL("UPDATE usage_record SET prompt_tokens_reported = 1, cached_tokens_reported = 1, cache_write_tokens = 0 WHERE id = 1")
                // A saved unknown flag must survive even when the numeric field is positive.
                db.execSQL("UPDATE usage_record SET cache_write_tokens = 25 WHERE id = 2")
            }
            if (this == UPSTREAM_34) {
                db.execSQL("UPDATE workspaces SET storage_mounts = '[{\"path\":\"/shared\",\"writable\":false}]'")
            }
            if (malformed) db.execSQL("ALTER TABLE usage_record DROP COLUMN completion_tokens")
        }
    }

    fun assertPreserved(db: SupportSQLiteDatabase) {
        assertEquals(35, db.version)
        db.query("SELECT title FROM ConversationEntity").use {
            assertTrue(it.moveToFirst()); assertEquals("preserved chat", it.getString(0)); assertFalse(it.moveToNext())
        }
        db.query("SELECT content FROM memory_file").use {
            assertTrue(it.moveToFirst()); assertEquals("preserved memory", it.getString(0))
        }
        db.query("SELECT prompt_tokens, completion_tokens, cached_tokens, prompt_tokens_reported, cached_tokens_reported, cache_write_tokens FROM usage_record ORDER BY id").use {
            assertTrue(it.moveToNext())
            assertEquals(100, it.getInt(0)); assertEquals(10, it.getInt(1)); assertEquals(0, it.getInt(2))
            assertEquals(1, it.getInt(3)); assertEquals(if (hasPresence) 1 else 0, it.getInt(4))
            if (hasPresence) assertEquals(0, it.getInt(5)) else assertTrue(it.isNull(5))
            assertTrue(it.moveToNext())
            assertEquals(50, it.getInt(2)); assertEquals(if (hasPresence) 0 else 1, it.getInt(3))
            assertEquals(if (hasPresence) 0 else 1, it.getInt(4))
            if (hasPresence) assertEquals(25, it.getInt(5)) else assertTrue(it.isNull(5))
            assertFalse(it.moveToNext())
        }
        db.query("SELECT storage_mounts FROM workspaces").use {
            assertTrue(it.moveToFirst())
            assertEquals(if (this == UPSTREAM_34) "[{\"path\":\"/shared\",\"writable\":false}]" else "[]", it.getString(0))
        }
        db.query("EXPLAIN QUERY PLAN SELECT * FROM message_node WHERE conversation_id = 'conversation' ORDER BY node_index").use {
            val details = buildList { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("detail"))) }
            assertTrue(details.toString(), details.any { line -> "index_message_node_conversation_id_node_index" in line })
            assertFalse(details.toString(), details.any { line -> "TEMP B-TREE" in line })
        }
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_message_node_conversation_id'").use {
            assertFalse(it.moveToFirst())
        }
    }
}
