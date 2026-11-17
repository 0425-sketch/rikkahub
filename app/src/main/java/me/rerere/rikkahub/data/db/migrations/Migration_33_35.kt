package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.DatabaseMigrationTracker

/**
 * Version 33 was released with two different schemas: personal builds added usage presence,
 * while upstream added the message index. Upstream 34 also added workspace storage mounts.
 * Inspect columns instead of trusting a version/identity hash to identify either release.
 * Room validates the complete target schema after this migration in its upgrade transaction.
 */
val Migration_33_35 = compatibilityMigration(33)
val Migration_34_35 = compatibilityMigration(34)

private fun compatibilityMigration(from: Int) = object : Migration(from, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        DatabaseMigrationTracker.onMigrationStart(from, 35)
        try {
            val usageColumns = db.columnNames("usage_record")
            // Preserve previously recorded explicit zero/unknown flags and cache writes verbatim.
            // Only newly added flags can be inferred, and old zero counts remain unknown.
            if ("prompt_tokens_reported" !in usageColumns) {
                db.execSQL("ALTER TABLE usage_record ADD COLUMN prompt_tokens_reported INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE usage_record SET prompt_tokens_reported = CASE WHEN prompt_tokens > 0 THEN 1 ELSE 0 END")
            }
            if ("cached_tokens_reported" !in usageColumns) {
                db.execSQL("ALTER TABLE usage_record ADD COLUMN cached_tokens_reported INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE usage_record SET cached_tokens_reported = CASE WHEN cached_tokens > 0 THEN 1 ELSE 0 END")
            }
            if ("cache_write_tokens" !in usageColumns) {
                db.execSQL("ALTER TABLE usage_record ADD COLUMN cache_write_tokens INTEGER")
            }
            if ("storage_mounts" !in db.columnNames("workspaces")) {
                db.execSQL("ALTER TABLE workspaces ADD COLUMN storage_mounts TEXT NOT NULL DEFAULT '[]'")
            }
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_node_conversation_id_node_index` ON `message_node` (`conversation_id`, `node_index`)")
            db.execSQL("DROP INDEX IF EXISTS `index_message_node_conversation_id`")
        } finally {
            DatabaseMigrationTracker.onMigrationEnd()
        }
    }
}

private fun SupportSQLiteDatabase.columnNames(table: String): Set<String> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndexOrThrow("name")
        buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
    }
