package me.rerere.rikkahub.data.db

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Schema35RoomTest {
    private lateinit var directory: File

    @Before fun setUp() {
        directory = Files.createTempDirectory("room-35-").toFile()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun original32DirectUpgrade() = upgrade(ReleasedDatabase.ORIGINAL_32)
    @Test fun personal33DirectUpgrade() = upgrade(ReleasedDatabase.PERSONAL_33)
    @Test fun upstream33DirectUpgrade() = upgrade(ReleasedDatabase.UPSTREAM_33)
    @Test fun upstream34DirectUpgrade() = upgrade(ReleasedDatabase.UPSTREAM_34)

    private fun upgrade(fixture: ReleasedDatabase) {
        val context = isolatedDatabaseContext(RuntimeEnvironment.getApplication(), directory)
        val file = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        fixture.create(file)
        repeat(2) {
            val room = nativeRoom(context)
            try {
                // Room's generated validation and identity update must succeed before these assertions.
                fixture.assertPreserved(room.openHelper.writableDatabase)
            } finally {
                room.close()
            }
        }
    }

    @Test fun freshInstallUsesGeneratedSchemaAndIdentity() {
        val context = isolatedDatabaseContext(RuntimeEnvironment.getApplication(), directory)
        val room = nativeRoom(context)
        try {
            val db = room.openHelper.writableDatabase
            assertTargetIdentity(db)
            db.query("SELECT prompt_tokens_reported, cached_tokens_reported, cache_write_tokens FROM usage_record").close()
            db.query("SELECT storage_mounts FROM workspaces").close()
            db.query("SELECT name FROM sqlite_master WHERE name='index_message_node_conversation_id_node_index'").use {
                assertTrue(it.moveToFirst())
            }
            db.query("SELECT count(*) FROM message_node").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally {
            room.close()
        }
    }
}
