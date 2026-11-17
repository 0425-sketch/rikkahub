package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.ReleasedDatabase
import me.rerere.rikkahub.data.db.isolatedDatabaseContext
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.db.normalizeNativeDatabase
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Schema35BackupRestoreTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var live: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        directory = Files.createTempDirectory("backup-room-35-").toFile()
        context = isolatedDatabaseContext(RuntimeEnvironment.getApplication(), directory)
        live = nativeRoom(context)
        live.openHelper.writableDatabase.execSQL("CREATE TABLE live_probe (text TEXT)")
        live.openHelper.writableDatabase.execSQL("INSERT INTO live_probe VALUES ('current data')")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "settings.preferences_pb")
        }, scope)
        // Executes the production archive parsing, staging, Room validation, publication and restore.
        manager = BackupManager(context, live, store, JsonInstant, ::nativeRoom, ::normalizeNativeDatabase)
    }

    @After fun tearDown() = runBlocking {
        live.close()
        scope.coroutineContext.job.cancelAndJoin()
        directory.deleteRecursively()
        Unit
    }

    @Test fun original32Backup() = importBackup(ReleasedDatabase.ORIGINAL_32)
    @Test fun personal33BackupKeepsExplicitZeroAndUnknown() = importBackup(ReleasedDatabase.PERSONAL_33)
    @Test fun upstream33Backup() = importBackup(ReleasedDatabase.UPSTREAM_33)
    @Test fun upstream34BackupKeepsStorageMounts() = importBackup(ReleasedDatabase.UPSTREAM_34)

    private fun importBackup(fixture: ReleasedDatabase) = runBlocking {
        val source = File(directory, "source.db")
        fixture.create(source)
        val before = source.readBytes()
        manager.stageRestore(archive(source), includeDatabase = true, includeFiles = false)
        assertLiveIntact()
        assertArrayEquals(before, source.readBytes())
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        val validated = nativeRoom(context, staged.path)
        try {
            fixture.assertPreserved(validated.openHelper.writableDatabase)
        } finally {
            validated.close()
        }
        live.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        live = nativeRoom(context)
        fixture.assertPreserved(live.openHelper.writableDatabase)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
    }

    @Test fun malformedHistoricalSchemaIsRejectedBeforePublication() = runBlocking {
        val source = File(directory, "malformed.db")
        ReleasedDatabase.UPSTREAM_33.create(source, malformed = true)
        assertRejected(archive(source))
    }

    @Test fun futureVersionIsRejectedBeforePublication() = runBlocking {
        val source = File(directory, "future.db")
        ReleasedDatabase.UPSTREAM_34.create(source)
        // Deliberately unsupported input, never the app's compatibility strategy.
        SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 9999 }
        assertRejected(archive(source))
    }

    @Test fun corruptArchiveDatabaseIsRejectedBeforePublication() = runBlocking {
        val source = File(directory, "corrupt.db").apply { writeText("not sqlite") }
        assertRejected(archive(source))
    }

    @Test fun restoreFailureRollsBackTheAlreadyInstalledDatabase() = runBlocking {
        val source = File(directory, "source.db")
        ReleasedDatabase.PERSONAL_33.create(source)
        manager.stageRestore(archive(source), includeDatabase = true, includeFiles = false)
        // Simulate a staging file corrupted after validation. The production apply path must roll back.
        File(context.noBackupFilesDir, "backup-restore/pending/settings.json").writeText("{broken")
        live.close()
        var failed = false
        try {
            BackupManager.applyPendingRestore(context, JsonInstant)
        } catch (_: RestoreFailedException) {
            failed = true
        }
        assertTrue(failed)
        live = nativeRoom(context)
        assertLiveIntact()
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
    }

    private suspend fun assertRejected(archive: File) {
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("Invalid backup must fail", failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertLiveIntact()
    }

    private fun archive(source: File): File = File(directory, "input.zip").also { archive ->
        ZipOutputStream(archive.outputStream()).use {
            it.putNextEntry(ZipEntry(DatabaseBackup.ARCHIVE_DATABASE))
            source.inputStream().use { input -> input.copyTo(it) }
            it.closeEntry()
        }
    }

    private fun assertLiveIntact() {
        live.openHelper.readableDatabase.query("SELECT text FROM live_probe").use {
            assertTrue(it.moveToFirst()); assertEquals("current data", it.getString(0)); assertFalse(it.moveToNext())
        }
    }
}
