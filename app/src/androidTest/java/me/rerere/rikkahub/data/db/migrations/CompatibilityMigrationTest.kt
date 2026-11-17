package me.rerere.rikkahub.data.db.migrations

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class CompatibilityMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun allReleasedSchemasUpgradeThroughTheProductionFactory() {
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "migration-35-").toFile()
        try {
            HistoricalDatabaseFixture.entries.forEach { fixture ->
                val file = File(directory, fixture.name)
                fixture.create(context, file)
                // This is the app's actual Room open/migration/validation path, not a manual version bump.
                withRoom(file) { room ->
                    fixture.assertPreserved(room.openHelper.writableDatabase)
                }
                withRoom(file) { room ->
                    fixture.assertPreserved(room.openHelper.writableDatabase)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun newInstallUsesTheSameTargetSchema() {
        val file = File(context.cacheDir, "fresh-35-${System.nanoTime()}")
        try {
            withRoom(file) { room ->
                val db = room.openHelper.writableDatabase
                assertEquals(35, db.version)
                db.query("SELECT prompt_tokens_reported, cached_tokens_reported, cache_write_tokens FROM usage_record").close()
                db.query("SELECT storage_mounts FROM workspaces").close()
                db.query("SELECT name FROM sqlite_master WHERE name = 'index_message_node_conversation_id_node_index'").use {
                    assertTrue(it.moveToFirst())
                }
            }
        } finally {
            file.delete()
        }
    }

    private fun withRoom(file: File, action: (AppDatabase) -> Unit) {
        val room = AppDatabaseFactory.create(context, file.path)
        try {
            action(room)
        } finally {
            room.close()
        }
    }
}
