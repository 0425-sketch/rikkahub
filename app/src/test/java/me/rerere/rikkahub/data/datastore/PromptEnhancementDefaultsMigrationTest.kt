package me.rerere.rikkahub.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.prompts.CHAT_ENHANCEMENT_PROMPT_206
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_CHAT_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_IMAGE_ENHANCEMENT_PROMPT
import me.rerere.rikkahub.data.ai.prompts.IMAGE_ENHANCEMENT_PROMPT_206
import me.rerere.rikkahub.data.datastore.migration.PromptEnhancementDefaultsMigration
import me.rerere.rikkahub.data.sync.BackupManager.Companion.decodeBackupSettings
import me.rerere.rikkahub.data.sync.BackupManager.Companion.encodeSettings
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PromptEnhancementDefaultsMigrationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private suspend fun withDisk(
        upgrade: Boolean = true,
        block: suspend (DataStore<Preferences>, CoroutineScope) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(
            migrations = if (upgrade) listOf(PromptEnhancementDefaultsMigration) else emptyList(),
            scope = scope,
            produceFile = { File(temp.root, "settings.preferences_pb") },
        )
        try {
            block(data, scope)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `only an exact legacy default is removed in its own slot`() = runBlocking {
        val migration = PromptEnhancementDefaultsMigration
        val customChat = "$CHAT_ENHANCEMENT_PROMPT_206\n"
        val customImage = " $IMAGE_ENHANCEMENT_PROMPT_206"
        for ((chat, image) in listOf(
            CHAT_ENHANCEMENT_PROMPT_206 to IMAGE_ENHANCEMENT_PROMPT_206,
            CHAT_ENHANCEMENT_PROMPT_206 to customImage,
            customChat to IMAGE_ENHANCEMENT_PROMPT_206,
            IMAGE_ENHANCEMENT_PROMPT_206 to CHAT_ENHANCEMENT_PROMPT_206,
            "" to " \r\n ",
        )) {
            val old = mutablePreferencesOf(
                SettingsStore.CHAT_ENHANCEMENT_PROMPT to chat,
                SettingsStore.IMAGE_ENHANCEMENT_PROMPT to image,
                SettingsStore.TITLE_PROMPT to "untouched",
            )
            assertTrue(migration.shouldMigrate(old))
            val upgraded = migration.migrate(old)
            assertFalse(migration.shouldMigrate(upgraded))
            assertEquals(
                chat.takeUnless { it == CHAT_ENHANCEMENT_PROMPT_206 },
                upgraded[SettingsStore.CHAT_ENHANCEMENT_PROMPT],
            )
            assertEquals(
                image.takeUnless { it == IMAGE_ENHANCEMENT_PROMPT_206 },
                upgraded[SettingsStore.IMAGE_ENHANCEMENT_PROMPT],
            )
            assertEquals("untouched", upgraded[SettingsStore.TITLE_PROMPT])
        }
    }

    @Test
    fun `upgrading missing and explicit defaults then editing and resetting survives repeated reopening`() = runBlocking {
        withDisk(upgrade = false) { data, _ ->
            data.edit { it[SettingsStore.CHAT_ENHANCEMENT_PROMPT] = CHAT_ENHANCEMENT_PROMPT_206 }
        }
        val customImage = " \n更改图像。\r\n "
        withDisk { data, scope ->
            val initial = data.data.first()
            assertFalse(initial.contains(SettingsStore.CHAT_ENHANCEMENT_PROMPT))
            assertFalse(initial.contains(SettingsStore.IMAGE_ENHANCEMENT_PROMPT))
            val store = SettingsStore(data, scope)
            assertEquals(DEFAULT_CHAT_ENHANCEMENT_PROMPT, store.awaitLoaded().chatEnhancementPrompt)
            assertEquals(DEFAULT_IMAGE_ENHANCEMENT_PROMPT, store.settingsFlow.value.imageEnhancementPrompt)
            store.update {
                it.copy(chatEnhancementPrompt = CHAT_ENHANCEMENT_PROMPT_206, imageEnhancementPrompt = customImage)
            }
        }
        repeat(2) {
            withDisk { data, scope ->
                val restored = SettingsStore(data, scope).awaitLoaded()
                assertEquals(CHAT_ENHANCEMENT_PROMPT_206, restored.chatEnhancementPrompt)
                assertEquals(customImage, restored.imageEnhancementPrompt)
            }
        }
        withDisk { data, scope ->
            SettingsStore(data, scope).resetStoredField(SettingsStore.CHAT_ENHANCEMENT_PROMPT.name)
        }
        withDisk { data, scope ->
            val reset = SettingsStore(data, scope).awaitLoaded()
            assertEquals(DEFAULT_CHAT_ENHANCEMENT_PROMPT, reset.chatEnhancementPrompt)
            assertEquals(customImage, reset.imageEnhancementPrompt)
        }
    }

    @Test
    fun `legacy backup migrates each rule independently and preserves exact whitespace`() {
        val customChat = "$CHAT_ENHANCEMENT_PROMPT_206 "
        val customImage = "\r\n$IMAGE_ENHANCEMENT_PROMPT_206"
        for ((chat, image) in listOf(
            CHAT_ENHANCEMENT_PROMPT_206 to null,
            null to IMAGE_ENHANCEMENT_PROMPT_206,
            CHAT_ENHANCEMENT_PROMPT_206 to customImage,
            customChat to IMAGE_ENHANCEMENT_PROMPT_206,
            "" to "  \n ",
        )) {
            val oldBackup = buildJsonObject {
                chat?.let { put("chatEnhancementPrompt", it) }
                image?.let { put("imageEnhancementPrompt", it) }
            }
            val imported = JsonInstant.decodeBackupSettings(oldBackup.toString())
            assertEquals(
                chat?.takeUnless { it == CHAT_ENHANCEMENT_PROMPT_206 } ?: DEFAULT_CHAT_ENHANCEMENT_PROMPT,
                imported.chatEnhancementPrompt,
            )
            assertEquals(
                image?.takeUnless { it == IMAGE_ENHANCEMENT_PROMPT_206 } ?: DEFAULT_IMAGE_ENHANCEMENT_PROMPT,
                imported.imageEnhancementPrompt,
            )
        }
    }

    @Test
    fun `new archives and pending restores retain deliberately restored old wording`() {
        val custom = Settings(
            chatEnhancementPrompt = CHAT_ENHANCEMENT_PROMPT_206,
            imageEnhancementPrompt = IMAGE_ENHANCEMENT_PROMPT_206,
        )
        val archive = JsonInstant.encodeSettings(custom, 4)
        val staged = JsonInstant.decodeBackupSettings(archive)
        val restored = JsonInstant.decodeBackupSettings(JsonInstant.encodeSettings(staged, 4))

        assertEquals(CHAT_ENHANCEMENT_PROMPT_206, restored.chatEnhancementPrompt)
        assertEquals(IMAGE_ENHANCEMENT_PROMPT_206, restored.imageEnhancementPrompt)
    }
}
