package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.data.ai.prompts.CHAT_ENHANCEMENT_PROMPT_206
import me.rerere.rikkahub.data.ai.prompts.IMAGE_ENHANCEMENT_PROMPT_206
import me.rerere.rikkahub.data.datastore.SettingsStore

// Separate from the upstream DataStore version. A user may choose the old text again after upgrading.
internal object PromptEnhancementDefaultsMigration : DataMigration<Preferences> {
    private val migrated = booleanPreferencesKey("prompt_enhancement_defaults_206_migrated")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = currentData[migrated] != true

    override suspend fun migrate(currentData: Preferences): Preferences = currentData.toMutablePreferences().apply {
        if (this[SettingsStore.CHAT_ENHANCEMENT_PROMPT] == CHAT_ENHANCEMENT_PROMPT_206) {
            remove(SettingsStore.CHAT_ENHANCEMENT_PROMPT)
        }
        if (this[SettingsStore.IMAGE_ENHANCEMENT_PROMPT] == IMAGE_ENHANCEMENT_PROMPT_206) {
            remove(SettingsStore.IMAGE_ENHANCEMENT_PROMPT)
        }
        this[migrated] = true
    }.toPreferences()

    override suspend fun cleanUp() = Unit
}

internal const val ENHANCEMENT_DEFAULTS_MIGRATED = "promptEnhancementDefaults206Migrated"

/** Older archives exported both default strings alongside custom rules, without recording their origin. */
internal fun JsonObject.migrateEnhancementDefaults(): JsonObject {
    if (this[ENHANCEMENT_DEFAULTS_MIGRATED] == JsonPrimitive(true)) return this
    return JsonObject(toMutableMap().apply {
        if (this["chatEnhancementPrompt"] == JsonPrimitive(CHAT_ENHANCEMENT_PROMPT_206)) {
            remove("chatEnhancementPrompt")
        }
        if (this["imageEnhancementPrompt"] == JsonPrimitive(IMAGE_ENHANCEMENT_PROMPT_206)) {
            remove("imageEnhancementPrompt")
        }
    })
}
