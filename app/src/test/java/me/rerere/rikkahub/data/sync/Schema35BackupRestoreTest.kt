package me.rerere.rikkahub.data.sync

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.SettingsUnavailableForBackupException
import me.rerere.rikkahub.data.datastore.createSettingsDataStore
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
import org.junit.Assert.assertThrows
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
import java.util.zip.ZipFile
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
        manager = BackupManager(context, { live }, store, JsonInstant, ::nativeRoom, ::normalizeNativeDatabase)
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
        val liveDictionary = File(context.filesDir, "simple_dict/dictionary").apply {
            parentFile!!.mkdirs()
            writeText("live dictionary")
        }
        manager.stageRestore(archive(source), includeDatabase = true, includeFiles = false)
        assertEquals("live dictionary", liveDictionary.readText())
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

    @Test fun missingOrEmptySettingsOnExistingInstallCannotBeExportedAsDefaults() = runBlocking {
        for (initialFile in listOf("missing", "empty", "corrupt")) {
            val previous = isolatedDatabaseContext(context, File(directory, initialFile))
            val room = nativeRoom(previous)
            try {
                room.openHelper.writableDatabase
            } finally {
                room.close()
            }
            val file = previous.preferencesDataStoreFile("settings")
            if (initialFile != "missing") {
                file.parentFile!!.mkdirs()
                file.writeBytes(if (initialFile == "empty") byteArrayOf() else byteArrayOf(-1, -1, -1))
            }
            repeat(2) {
                assertThrows(SettingsUnavailableForBackupException::class.java) {
                    runBlocking { readAfterStartup(previous) }
                }
                // The second launch sees a valid migrated file, so it must rely on the persisted marker.
                assertTrue(file.length() > 0)
            }
        }

        val fresh = isolatedDatabaseContext(context, File(directory, "fresh-install"))
        assertFalse(readAfterStartup(fresh).init)
    }

    private suspend fun readAfterStartup(context: Context): Settings {
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return try {
            val store = SettingsStore(createSettingsDataStore(context, storeScope), storeScope)
            store.readForBackup().first
        } finally {
            storeScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun portableSettingsStripTokensAndReplaceExistingLoginOnlyOnApply() = runBlocking {
        val registration = ChatGPTRegistration(clientId = "oaiapp_example", subject = "example-user")
        val account = ProviderSetting.ChatGPT(
            credential = OAuthCredential(
                accessToken = "sample-device-token",
                protocolVersion = 1,
                clientId = registration.clientId,
                issuer = registration.issuer,
                subject = registration.subject,
            ),
            chatGPTRegistration = registration,
        )
        val override = account.copy(credential = OAuthCredential(accessToken = "sample-legacy-token"))
        val model = Model(modelId = "custom-model", providerOverwrite = override)
        val platform = MediaGenerationProviderSetting.OpenAIPlatform(apiKey = "sample-platform-key")
        val settings = Settings(
            providers = listOf(account.copy(models = listOf(model))),
            mediaGenerationProviders = listOf(platform),
        )
        val appScope = AppScope()
        try {
            val store = SettingsStore(context, appScope)
            store.update { settings }
            val safeManager = BackupManager(context, { error("Settings-only export accessed Room") }, store, JsonInstant)
            val exported = safeManager.prepareLocalBackup(includeDatabase = false, includeFiles = false, safeMode = true)
            ZipFile(exported.file).use { zip ->
                val json = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().use { it.readText() }
                assertFalse(json.contains("sample-device-token"))
                assertFalse(json.contains("sample-legacy-token"))
                assertTrue(json.contains("sample-platform-key"))
            }

            // An old package may contain credentials even though this version no longer exports them.
            val legacy = File(directory, "legacy-settings.zip")
            ZipOutputStream(legacy.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                zip.write(JsonInstant.encodeToString(settings).toByteArray())
            }
            manager.stageRestore(legacy, includeDatabase = false, includeFiles = false)
            val staged = File(context.noBackupFilesDir, "backup-restore/pending/settings.json").readText()
            assertFalse(staged.contains("sample-device-token"))
            assertFalse(staged.contains("sample-legacy-token"))
            val before = store.awaitLoaded().providers.single { it.id == account.id } as ProviderSetting.ChatGPT
            assertEquals(account.credential, before.credential)

            assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
            val restored = SettingsStore(context, appScope).awaitLoaded()
            val provider = restored.providers.single { it.id == account.id } as ProviderSetting.ChatGPT
            assertEquals(null, provider.credential)
            assertEquals(registration, provider.chatGPTRegistration)
            assertEquals(model.id, provider.models.single().id)
            val restoredOverride = provider.models.single().providerOverwrite as ProviderSetting.ChatGPT
            assertEquals(null, restoredOverride.credential)
            assertEquals(registration, restoredOverride.chatGPTRegistration)
            assertEquals(platform, restored.mediaGenerationProviders.single())
        } finally {
            appScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test fun safeFileExportRoundTripsOldGalleryAndMediaUsingOrdinaryRestore() = runBlocking {
        val originals = listOf(
            "images/173_vendor/model_0.png", "images/new.png",
            "media_creation/session/record/out_0.png", "media_creation/session/draft/asset.jpg",
        )
        val excluded = listOf(
            "media_creation/session/record/out_1.mp4.part", "workspace/private", "upload/nested/ignored",
            "datastore/settings.preferences_pb", "datastore/settings.preferences_pb.corrupt-1", "logs/error.log",
        )
        val files = context.filesDir
        for (path in originals + excluded) {
            File(files, path).run { parentFile!!.mkdirs(); writeText("synthetic $path") }
        }
        Files.createSymbolicLink(File(files, "skills").toPath(), File(files, "workspace").toPath())
        Files.createSymbolicLink(File(files, "images/linked").toPath(), context.noBackupFilesDir.toPath())
        File(context.noBackupFilesDir, "private-key").writeText("test only")
        val backup = manager.prepareLocalBackup(safeMode = true, includeSettings = false, includeDatabase = false)
        assertFalse(backup.hasDatabase)
        assertFalse(backup.hasSettings)
        assertEquals(originals.size, backup.attachmentCount)
        ZipFile(backup.file).use { zip -> assertEquals(originals.toSet(), zip.entries().toList().map { it.name }.toSet()) }
        originals.forEach { assertTrue(File(files, it).delete()) }
        manager.stageRestore(backup.file, includeDatabase = true, includeFiles = true)
        assertTrue(BackupManager.applyPendingRestore(context, JsonInstant))
        originals.forEach { assertEquals("synthetic $it", File(files, it).readText()) }
        assertLiveIntact()
    }

    @Test fun damagedMediaWithValidLengthFailsBeforePublication() = runBlocking {
        val archive = File(directory, "damaged.zip")
        val content = byteArrayOf(10, 20, 30, 40, 50)
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("images/test.png").apply {
                method = ZipEntry.STORED
                size = content.size.toLong()
                crc = java.util.zip.CRC32().apply { update(content) }.value
            })
            zip.write(content)
            zip.closeEntry()
        }
        val damaged = archive.readBytes()
        val offset = damaged.indices.first { pos ->
            pos + content.size <= damaged.size && content.indices.all { damaged[pos + it] == content[it] }
        }
        damaged[offset] = 99
        archive.writeBytes(damaged)
        val failure = runCatching { manager.stageRestore(archive, includeDatabase = true, includeFiles = true) }.exceptionOrNull()
        assertTrue(failure is Exception)
        assertFalse(File(context.noBackupFilesDir, "backup-restore/pending").exists())
        assertFalse(File(context.filesDir, "images/test.png").exists())
        assertLiveIntact()
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
        assertRejected(archive(source), expectedMessage = "schema 9999 is newer")
    }

    @Test fun versionZeroDatabaseCannotInitializeAnEmptyReplacement() = runBlocking {
        val source = File(directory, "uninitialized.db")
        SQLiteDatabase.openOrCreateDatabase(source.path, null).use { it.version = 0 }
        assertTrue(source.length() > 0)
        assertRejected(archive(source), expectedMessage = "version 0")
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

    private suspend fun assertRejected(archive: File, expectedMessage: String? = null) {
        var failed = false
        try {
            manager.stageRestore(archive, includeDatabase = true, includeFiles = false)
        } catch (failure: Exception) {
            if (expectedMessage != null) {
                assertTrue(failure.toString(), expectedMessage in failure.message.orEmpty())
            }
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
