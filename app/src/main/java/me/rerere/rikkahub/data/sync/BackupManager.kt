package me.rerere.rikkahub.data.sync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.withoutChatGPTCredentials
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.datastore.migration.ENHANCEMENT_DEFAULTS_MIGRATED
import me.rerere.rikkahub.data.datastore.migration.migrateEnhancementDefaults
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PreparedBackup(
    val file: File,
    val hasSettings: Boolean,
    val hasDatabase: Boolean,
    val attachmentCount: Int,
    val byteCount: Long = file.length(),
)

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager internal constructor(
    private val context: Context,
    private val database: () -> AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val createDatabase: (Context, String) -> AppDatabase,
    private val normalizeDatabase: (Context, File) -> Int,
) {
    constructor(context: Context, database: () -> AppDatabase, settingsStore: SettingsStore, json: Json) : this(
        context, database, settingsStore, json,
        { databaseContext, name -> AppDatabaseFactory.create(databaseContext, name) },
        DatabaseBackup::normalize,
    )

    private val restoreMutex = Mutex()

    suspend fun createBackup(includeDatabase: Boolean, includeFiles: Boolean): File =
        prepareLocalBackup(includeDatabase, includeFiles).file

    suspend fun prepareLocalBackup(
        includeDatabase: Boolean = true,
        includeFiles: Boolean = true,
        safeMode: Boolean = false,
        includeSettings: Boolean = true,
    ): PreparedBackup {
        // A cancelled dispatch back to the UI must also discard a completed but unclaimed archive.
        var temporaryArchive: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
                temporaryArchive = archive
                val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
                val entries = mutableSetOf<String>()
                try {
                    ZipOutputStream(FileOutputStream(archive)).use { zip ->
                        if (includeSettings) {
                            val (settings, launchCount) = settingsStore.readForBackup()
                            zip.putNextEntry(ZipEntry("settings.json"))
                            zip.write(json.encodeSettings(settings, launchCount).toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                            entries.add("settings.json")
                        }
                        if (includeDatabase) {
                            val snapshot = snapshot(staging, safeMode)
                            addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                            entries.add(DatabaseBackup.ARCHIVE_DATABASE)
                        }
                        if (includeFiles) addAttachments(zip, entries)
                    }
                } finally {
                    staging.deleteRecursively()
                }
                check(entries.isNotEmpty()) { "No selected data found to back up" }
                ZipFile(archive).use { zip ->
                    check(zip.size() == entries.size) { "Backup archive has unexpected entries" }
                    for (name in entries) {
                        val entry = checkNotNull(zip.getEntry(name)) { "Missing backup entry: $name" }
                        zip.readVerified(entry)
                    }
                }
                PreparedBackup(
                    file = archive,
                    hasSettings = "settings.json" in entries,
                    hasDatabase = DatabaseBackup.ARCHIVE_DATABASE in entries,
                    attachmentCount = entries.count { '/' in it },
                )
            }
        } catch (e: Throwable) {
            temporaryArchive?.delete()
            throw e
        }
    }

    private suspend fun snapshot(staging: File, safeMode: Boolean): File {
        val source = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME)
        require(source.isFile && source.length() > 0) { "Database is missing or empty" }
        if (!safeMode) {
            val destination = File(staging, "room.db")
            try {
                val room = database()
                // Opening Room may initialize an empty schema or invoke its destructive corruption handler.
                if (!room.isOpen) DatabaseBackup.withValidatedSource(context, source)
                DatabaseBackup.createSnapshot(room.openHelper.writableDatabase, destination)
                return destination
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                // Release any partial Room snapshot before allocating space for the read-only retry.
                check(!destination.exists() || destination.delete()) { "Cannot remove incomplete database snapshot" }
            }
        }
        val destination = File(staging, "readonly.db")
        DatabaseBackup.createFileSnapshot(context, source, destination)
        return destination
    }

    private suspend fun addAttachments(zip: ZipOutputStream, entries: MutableSet<String>) {
        for (folder in ATTACHMENT_FOLDERS) {
            val directory = context.filesDir.toPath().resolve(folder)
            if (Files.notExists(directory, NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) continue
            check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Cannot read backup folder: $folder" }
            val depth = if (folder in NESTED_ATTACHMENT_FOLDERS) Int.MAX_VALUE else 1
            // By default Files.walk never enters a symbolic link, even inside nested skills or galleries.
            Files.walk(directory, depth).use { paths ->
                for (path in paths.iterator()) {
                    currentCoroutineContext().ensureActive()
                    if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) continue
                    val name = "$folder/${path.toFile().relativeTo(directory.toFile()).invariantSeparatorsPath}"
                    if (!isAttachment(name)) continue
                    val file = PendingRestore.resolveInside(context.filesDir, name)
                    addFile(zip, file, name)
                    entries.add(name)
                }
            }
        }
    }

    suspend fun stageRestore(archive: File, includeDatabase: Boolean, includeFiles: Boolean) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                val restore = pendingRestore(context)
                val staging = restore.createStagingDirectory()
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> if (includeFiles && isAttachment(entry.name)) {
                                    PendingRestore.resolveInside(File(payload, "files"), entry.name)
                                } else null
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            FileOutputStream(target).use { output ->
                                zip.readVerified(entry, output)
                                output.fd.sync()
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    val pathRemapper = BackupPathRemapper(
                        targetFilesDir = context.filesDir,
                        targetPackage = context.packageName,
                        restoredPaths = seen.filterTo(mutableSetOf()) { it.substringBefore('/') in ATTACHMENT_FOLDERS },
                    )
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.requireRestorableVersion(normalizeDatabase(context, stagedDatabase))
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = createDatabase(context, stagedDatabase.absolutePath)
                        try {
                            pathRemapper.stagedDatabase(room.openHelper.writableDatabase)
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val raw = settingsFile.readText()
                        val (settings, launchCount) = try {
                            val migrated = SettingsJsonMigrator.migrate(raw)
                            json.decodeBackupSettings(migrated) to json.launchCountOf(migrated)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Decoder errors can echo the archived token values from older backups.
                            throw IllegalArgumentException("Backup settings could not be decoded")
                        }
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(
                            settingsFile,
                            json.encodeSettings(pathRemapper.settings(settings), launchCount),
                        )
                    }
                    currentCoroutineContext().ensureActive()
                    restore.publish(staging)
                } finally {
                    staging.deleteRecursively()
                }
            }
        }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return folder != FileFolders.MEDIA_CREATION || !relative.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
    }

    // ZipFile exposes the central directory's CRC but need not check it when reading an entry.
    private suspend fun ZipFile.readVerified(entry: ZipEntry, output: OutputStream? = null) {
        val crc = CRC32()
        val size = getInputStream(entry).use { input ->
            copyChunks(input) { buffer, count ->
                crc.update(buffer, 0, count)
                output?.write(buffer, 0, count)
            }
        }
        check(size == entry.size && crc.value == entry.crc) { "Damaged backup entry: ${entry.name}" }
    }

    private suspend inline fun copyChunks(input: InputStream, consume: (ByteArray, Int) -> Unit): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count == -1) return total
            consume(buffer, count)
            total += count
        }
    }

    private suspend fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input ->
            copyChunks(input) { buffer, count -> zip.write(buffer, 0, count) }
        }
        zip.closeEntry()
    }

    companion object {
        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION, FileFolders.IMAGES)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION, FileFolders.IMAGES)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        internal fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings.withoutChatGPTCredentials()).jsonObject + mapOf(
                LAUNCH_COUNT_KEY to JsonPrimitive(launchCount),
                ENHANCEMENT_DEFAULTS_MIGRATED to JsonPrimitive(true),
            )
            return encodeToString(JsonObject(fields))
        }

        internal fun Json.decodeBackupSettings(settingsJson: String): Settings =
            decodeFromJsonElement(parseToJsonElement(settingsJson).jsonObject.migrateEnhancementDefaults())

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeBackupSettings(settingsJson).withoutChatGPTCredentials(),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}
