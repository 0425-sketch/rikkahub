package me.rerere.rikkahub.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.migrations.HistoricalDatabaseFixture
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var liveDatabase: AppDatabase
    private lateinit var manager: BackupManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "backup-manager-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String): File = if (File(name).isAbsolute) File(name)
                else File(directory, "databases/$name").also { it.parentFile!!.mkdirs() }
        }
        liveDatabase = AppDatabaseFactory.create(context)
        liveDatabase.openHelper.writableDatabase.execSQL("CREATE TABLE backup_probe (text TEXT)")
        liveDatabase.openHelper.writableDatabase.execSQL("INSERT INTO backup_probe VALUES ('live')")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settingsStore = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
            File(directory, "settings.preferences_pb")
        }, scope)
        manager = BackupManager(context, { liveDatabase }, settingsStore, JsonInstant)
    }

    @After fun tearDown() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        liveDatabase.close()
        directory.deleteRecursively()
        Unit
    }

    @Test fun oldArchiveIgnoresShmAndLeavesLiveDatabaseUntouchedUntilStartup() = runBlocking {
        val source = File(directory, "source")
        val archive = File(directory, "legacy.zip")
        withRoom(source) { room ->
            val db = room.openHelper.writableDatabase
            db.execSQL("CREATE TABLE backup_probe (text TEXT)")
            DatabaseBackup.checkpoint(db)
            db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
            db.execSQL("INSERT INTO backup_probe VALUES ('archived')")
            ZipOutputStream(archive.outputStream()).use { zip ->
                addFile(zip, DatabaseBackup.ARCHIVE_DATABASE, source)
                addFile(zip, DatabaseBackup.WAL, File(source.path + "-wal"))
                zip.putNextEntry(ZipEntry(DatabaseBackup.SHM))
                zip.write("deliberately invalid SHM".toByteArray())
                zip.closeEntry()
            }
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        assertTrue(staged.isFile)
        assertFalse(File(staged.path + "-wal").exists())
        assertFalse(File(staged.path + "-shm").exists())
        liveDatabase.close()
        BackupManager.applyPendingRestore(context, JsonInstant)
        liveDatabase = AppDatabaseFactory.create(context)
        assertEquals("archived", probe(liveDatabase))
        ZipFile(archive).use { assertTrue(it.getEntry(DatabaseBackup.SHM) != null) }
    }

    @Test fun newArchiveContainsStandaloneDatabaseAndNoWalOrShm() = runBlocking {
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("settings.json") != null)
            assertTrue(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE) != null)
            assertEquals(null, zip.getEntry(DatabaseBackup.WAL))
            assertEquals(null, zip.getEntry(DatabaseBackup.SHM))
        }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun rescueArchiveWithCommittedMessageAndMemoryStagesThroughOrdinaryRestore() = runBlocking {
        settingsStore.update { it.copy(titlePrompt = "synthetic backup setting") }
        val db = liveDatabase.openHelper.writableDatabase
        DatabaseBackup.checkpoint(db)
        db.query("PRAGMA wal_autocheckpoint=0").use { assertTrue(it.moveToFirst()) }
        db.execSQL("INSERT INTO ConversationEntity (id, title, nodes, create_at, update_at) VALUES ('chat', 'Hello', '[]', 1, 1)")
        db.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('n', 'chat', 0, ?, 0)",
            arrayOf("""[{"role":"USER","parts":[{"type":"text","text":"rescue me"}]}]"""),
        )
        db.execSQL("INSERT INTO memory_file VALUES ('assistant', 'notes', 'synthetic memory', 1)")
        // Historical image rows store the basename even when the vendor/model name created a directory.
        db.execSQL(
            "INSERT INTO GenMediaEntity (path, model_id, prompt, create_at) VALUES ('images/model_0.png', 'vendor/model', 'test', 7)"
        )
        val original = File(context.filesDir, "images/7_vendor/model_0.png").apply {
            parentFile!!.mkdirs()
            writeText("gallery original")
        }
        val rescue = BackupManager(context, { error("Rescue must never request Room") }, settingsStore, JsonInstant)
        val prepared = rescue.prepareLocalBackup(safeMode = true)
        assertTrue(prepared.hasSettings)
        assertTrue(prepared.hasDatabase)
        assertEquals(1, prepared.attachmentCount)
        assertTrue(original.delete())
        db.execSQL("DELETE FROM memory_file")
        manager.stageRestore(prepared.file, includeDatabase = true, includeFiles = true)
        liveDatabase.close()
        val restore = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )
        // Capture the settings write locally rather than using the target app's process-wide DataStore.
        var restoredPrompt: String? = null
        assertTrue(restore.apply { archivedSettings ->
            restoredPrompt = with(BackupManager) { JsonInstant.decodeBackupSettings(archivedSettings).titlePrompt }
        })
        assertEquals("synthetic backup setting", restoredPrompt)
        liveDatabase = AppDatabaseFactory.create(context)
        val restored = liveDatabase.openHelper.readableDatabase
        assertEquals("gallery original", original.readText())
        restored.query("SELECT path FROM GenMediaEntity WHERE model_id = 'vendor/model'").use {
            assertTrue(it.moveToFirst())
            assertEquals("images/model_0.png", it.getString(0))
        }
        restored.query("SELECT messages FROM message_node WHERE id = 'n'").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getString(0).contains("rescue me"))
        }
        restored.query("SELECT content FROM memory_file WHERE path = 'notes'").use {
            assertTrue(it.moveToFirst())
            assertEquals("synthetic memory", it.getString(0))
        }
    }

    @Test fun ordinaryExportFallsBackWhenRoomRejectsTheReadableSchema() = runBlocking {
        liveDatabase.openHelper.writableDatabase.version = 9999
        liveDatabase.close()
        liveDatabase = AppDatabaseFactory.create(context)
        val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
        val snapshot = File(directory, "saved.db")
        ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(DatabaseBackup.ARCHIVE_DATABASE)).use { input ->
                snapshot.outputStream().use { output -> input.copyTo(output) }
            }
        }
        // A Room upgrade/downgrade on the source or the archived copy would destroy its original version.
        java.io.RandomAccessFile(snapshot, "r").use {
            it.seek(60) // SQLite database header, big-endian user_version.
            assertEquals(9999, it.readInt())
        }
    }

    @Test fun coldRoomExportCannotInitializeOrReplaceAnInvalidSource() = runBlocking {
        liveDatabase.close()
        val source = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        assertTrue(source.delete())
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use { it.version = 0 }
        assertTrue(source.length() > 0)
        liveDatabase = AppDatabaseFactory.create(context)

        suspend fun assertRejectedBeforeOpeningRoom() {
            val before = source.readBytes()
            val failure = runCatching {
                manager.prepareLocalBackup(includeFiles = false, includeSettings = false)
            }.exceptionOrNull()
            assertTrue("Invalid source must fail ordinary export", failure is Exception)
            assertFalse(liveDatabase.isOpen)
            assertArrayEquals(before, source.readBytes())
            assertTrue(context.cacheDir.listFiles()!!.isEmpty())
        }
        assertRejectedBeforeOpeningRoom()
        source.writeText("synthetic damaged database")
        assertRejectedBeforeOpeningRoom()
    }

    @Test fun cancellationWhileOpeningRoomDoesNotAttemptRescueOrLeaveAnArchive() = runBlocking {
        val cancelled = BackupManager(context, { throw CancellationException("cancel") }, settingsStore, JsonInstant)
        try {
            cancelled.createBackup(includeDatabase = true, includeFiles = false)
            throw AssertionError("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(context.cacheDir.listFiles()!!.isEmpty())
        }
    }

    @Test fun launchCountStaysInSettingsJsonThroughBackupAndStaging() = runBlocking {
        val archive = manager.createBackup(includeDatabase = false, includeFiles = false)
        val archived = ZipFile(archive).use { zip ->
            launchCountOf(zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString())
        }
        assertTrue(archived != null)
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(archived, launchCountOf(staged))
    }

    @Test fun archiveWithoutLaunchCountRestoresItAsZero() = runBlocking {
        val current = manager.createBackup(includeDatabase = false, includeFiles = false)
        val settingsJson = ZipFile(current).use { zip ->
            zip.getInputStream(zip.getEntry("settings.json")).readBytes().decodeToString()
        }
        val archive = File(directory, "no-launch-count.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(JsonObject(JsonInstant.parseToJsonElement(settingsJson).jsonObject - "launchCount").toString().toByteArray())
            zip.closeEntry()
        }
        manager.stageRestore(archive, includeDatabase = false, includeFiles = false)
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
        assertEquals(0, launchCountOf(staged))
    }

    private fun launchCountOf(settingsJson: String): Int? =
        JsonInstant.parseToJsonElement(settingsJson).jsonObject["launchCount"]?.jsonPrimitive?.int

    @Test fun mediaCreationFilesKeepTheirDirectoriesAndSkipUnfinishedDownloads() = runBlocking {
        val session = File(context.filesDir, "media_creation/session")
        File(session, "record").mkdirs()
        File(session, "draft").mkdirs()
        File(session, "record/out_0.png").writeText("image")
        File(session, "record/out_1.mp4.part").writeText("half a video")
        File(session, "draft/asset.jpg").writeText("asset")
        val archive = manager.createBackup(includeDatabase = false, includeFiles = true)
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("media_creation/session/record/out_0.png") != null)
            assertTrue(zip.getEntry("media_creation/session/draft/asset.jpg") != null)
            assertEquals(null, zip.getEntry("media_creation/session/record/out_1.mp4.part"))
        }
        session.deleteRecursively()
        manager.stageRestore(archive, includeDatabase = false, includeFiles = true)
        assertFalse(session.exists())
        assertTrue(File(context.noBackupFilesDir, "backup-restore/pending/settings.json").delete())
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        assertEquals("image", File(session, "record/out_0.png").readText())
        assertEquals("asset", File(session, "draft/asset.jpg").readText())
        assertFalse(File(session, "record/out_1.mp4.part").exists())
    }

    @Test fun unsupportedSchemaIsRejectedBeforePublishing() = runBlocking {
        val future = File(directory, "future")
        withRoom(future) {
            it.openHelper.writableDatabase.version = 9999
        }
        val archive = File(directory, "future.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, future) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    @Test fun original32BackupIsValidatedAndMigratedBeforePublishing() = restoreHistorical(HistoricalDatabaseFixture.ORIGINAL_32)

    @Test fun personal33BackupPreservesExplicitZeroAndUnknownUsage() = restoreHistorical(HistoricalDatabaseFixture.PERSONAL_33)

    @Test fun upstream33BackupIsValidatedAndMigratedBeforePublishing() = restoreHistorical(HistoricalDatabaseFixture.UPSTREAM_33)

    @Test fun upstream34BackupPreservesWorkspaceMounts() = restoreHistorical(HistoricalDatabaseFixture.UPSTREAM_34)

    private fun restoreHistorical(fixture: HistoricalDatabaseFixture) = runBlocking {
        val source = File(directory, "historical-source")
        fixture.create(context, source)
        val originalBytes = source.readBytes()
        val archive = File(directory, "historical.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, source) }
        manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        assertEquals("live", probe(liveDatabase))
        assertTrue("The imported source must remain unchanged", originalBytes.contentEquals(source.readBytes()))
        val staged = File(context.noBackupFilesDir, "backup-restore/pending/payload/database/rikka_hub")
        withRoom(staged) { fixture.assertPreserved(it.openHelper.writableDatabase) }
        liveDatabase.close()
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        liveDatabase = AppDatabaseFactory.create(context)
        fixture.assertPreserved(liveDatabase.openHelper.writableDatabase)
    }

    @Test fun malformedHistoricalBackupFailsRoomValidationWithoutReplacingLiveData() = runBlocking {
        val source = File(directory, "malformed-source")
        HistoricalDatabaseFixture.UPSTREAM_33.create(context, source, malformed = true)
        val archive = File(directory, "malformed.zip")
        ZipOutputStream(archive.outputStream()).use { addFile(it, DatabaseBackup.ARCHIVE_DATABASE, source) }
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("An incomplete table must fail final Room validation", failed)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertEquals("live", probe(liveDatabase))
    }

    private fun probe(database: AppDatabase): String = database.openHelper.readableDatabase
        .query("SELECT text FROM backup_probe").use { check(it.moveToFirst()); it.getString(0) }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun <T> withRoom(file: File, block: (AppDatabase) -> T): T {
        val room = AppDatabaseFactory.create(context, file.path)
        return try {
            block(room)
        } finally {
            room.close()
        }
    }
}
