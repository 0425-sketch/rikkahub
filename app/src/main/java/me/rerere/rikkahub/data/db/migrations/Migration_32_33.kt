package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Old zero values did not distinguish an absent field from a reported zero. */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE usage_record ADD COLUMN prompt_tokens_reported INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE usage_record ADD COLUMN cached_tokens_reported INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE usage_record ADD COLUMN cache_write_tokens INTEGER")
        db.execSQL("UPDATE usage_record SET prompt_tokens_reported = CASE WHEN prompt_tokens > 0 THEN 1 ELSE 0 END, cached_tokens_reported = CASE WHEN cached_tokens > 0 THEN 1 ELSE 0 END")
    }
}
