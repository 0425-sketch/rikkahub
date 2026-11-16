package me.rerere.rikkahub.data.sync

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.mapLocalFileUrls
import java.io.File
import java.net.URI

/** Rebase only archived managed files; never read or modify the source application's private data. */
internal class BackupPathRemapper(
    private val targetFilesDir: File,
    targetPackage: String,
    private val restoredPaths: Set<String>,
) {
    private val sourcePackages = setOf(
        "me.rerere.rikkahub",
        "me.rerere.rikkahub.debug",
        "me.rerere.rikkahub.chatgpt.debug",
        targetPackage,
    )
    private val privateFilesPath = Regex("^/data/(?:user(?:_de)?/[0-9]+|data)/([^/]+)/files/(.+)$")

    fun fileUri(value: String): String {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value
        if (uri.scheme != "file" || !uri.rawAuthority.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
            return value
        }
        val target = targetOf(uri.path ?: return value) ?: return value
        return URI("file", "", target.path, null).toASCIIString()
    }

    private fun absolutePath(value: String): String = targetOf(value)?.path ?: value

    private fun targetOf(path: String): File? {
        val match = privateFilesPath.matchEntire(path) ?: return null
        if (match.groupValues[1] !in sourcePackages) return null
        val relative = match.groupValues[2]
        if (relative !in restoredPaths || '\\' in relative ||
            relative.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) return null
        return PendingRestore.resolveInside(targetFilesDir, relative)
    }

    fun settings(source: Settings): Settings {
        fun Avatar.rebased() = if (this is Avatar.Image) copy(url = fileUri(url)) else this
        return source.copy(
            assistants = source.assistants.map { assistant ->
                assistant.copy(
                    avatar = assistant.avatar.rebased(),
                    background = assistant.background?.let(::fileUri),
                    presetMessages = assistant.presetMessages.map { message ->
                        message.copy(parts = message.parts.map { it.mapLocalFileUrls(::fileUri) })
                    },
                )
            },
            displaySetting = source.displaySetting.copy(
                userAvatar = source.displaySetting.userAvatar.rebased(),
                chatCustomFontPath = absolutePath(source.displaySetting.chatCustomFontPath),
            ),
        )
    }

    /** Preserve unknown usage/metadata fields and text verbatim; only attachment-part URL fields change. */
    fun messageJson(value: String): String {
        if (!value.contains("file:")) return value
        val parsed = try {
            Json.parseToJsonElement(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Backup message data could not be remapped")
        }
        val mapped = mapMessageElement(parsed)
        return if (mapped == parsed) value else mapped.toString()
    }

    private fun mapMessageElement(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map(::mapMessageElement))
        is JsonObject -> {
            val isAttachment = (element["type"] as? JsonPrimitive)?.contentOrNull in
                setOf("image", "video", "audio", "document")
            JsonObject(element.mapValues { (key, value) ->
                if (isAttachment && key == "url" && value is JsonPrimitive && value.isString) {
                    JsonPrimitive(fileUri(value.content))
                } else if (key == "metadata") {
                    value
                } else {
                    mapMessageElement(value)
                }
            })
        }
        else -> element
    }

    /** Only called on the migrated staging copy, before publishing a pending restore. */
    fun stagedDatabase(database: SupportSQLiteDatabase) {
        if (restoredPaths.isEmpty()) return
        database.beginTransaction()
        try {
            for ((table, column) in listOf(
                "message_node" to "messages",
                "ConversationEntity" to "nodes",
                "favorites" to "snapshot_json",
            )) {
                database.query("SELECT id, $column FROM $table WHERE $column LIKE '%file:%'").use { cursor ->
                    while (cursor.moveToNext()) {
                        val original = cursor.getString(1)
                        val mapped = messageJson(original)
                        if (mapped != original) {
                            database.execSQL("UPDATE $table SET $column = ? WHERE id = ?", arrayOf(mapped, cursor.getString(0)))
                        }
                    }
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }
}
