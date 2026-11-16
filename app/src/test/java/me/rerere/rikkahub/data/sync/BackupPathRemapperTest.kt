package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ChatGPTPendingRefresh
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.withoutChatGPTCredentials
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class BackupPathRemapperTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldRoot = "/data/user/0/me.rerere.rikkahub.chatgpt.debug/files"
    private val restored = setOf("upload/photo.png", "upload/中文 name.png", "fonts/custom.ttf", "media_creation/job/video.mp4")
    private fun remapper(paths: Set<String> = restored) = BackupPathRemapper(
        File(temporary.root, "new-files"), "me.rerere.rikkahub.personal", paths,
    )
    private fun target(relative: String) = File(temporary.root, "new-files/$relative").canonicalPath

    @Test fun `known source app paths resolve only to the new app managed files`() {
        val mapper = remapper()
        for (prefix in listOf(
            oldRoot,
            "/data/data/me.rerere.rikkahub.debug/files",
            "/data/user/10/me.rerere.rikkahub.personal/files",
        )) {
            assertEquals(target("upload/photo.png"), URI(mapper.fileUri("file://$prefix/upload/photo.png")).path)
        }
        val unicode = "file://$oldRoot/upload/%E4%B8%AD%E6%96%87%20name.png"
        val rebased = mapper.fileUri(unicode)
        assertTrue(rebased.startsWith("file:///"))
        assertEquals(target("upload/中文 name.png"), URI(rebased).path)
    }

    @Test fun `unselected missing foreign and unsafe locations are never rewritten`() {
        val mapper = remapper()
        val untouched = listOf(
            "https://example.org/upload/photo.png",
            "content://old.provider/upload/photo.png",
            "file:///data/user/0/another.app/files/upload/photo.png",
            "file://remote$oldRoot/upload/photo.png",
            "file://$oldRoot/upload/missing.png",
            "file://$oldRoot/cache/photo.png",
            "file://$oldRoot/upload/../photo.png",
            "file://$oldRoot/upload/%2e%2e/photo.png",
            "file://$oldRoot/upload/photo.png?query=1",
            "file://$oldRoot/upload/photo.png#fragment",
        )
        untouched.forEach { assertEquals(it, mapper.fileUri(it)) }
        val valid = "file://$oldRoot/upload/photo.png"
        assertEquals(valid, remapper(emptySet()).fileUri(valid))
    }

    @Test fun `nested tool attachments rebase without changing text encrypted metadata usage or unknown fields`() {
        val old = "file://$oldRoot/upload/photo.png"
        val source = """[{"role":"ASSISTANT","usage":{"inputTokens":5000,"cachedInputTokens":4096},"futureField":"keep","parts":[{"type":"text","text":"$old"},{"type":"reasoning","reasoning":"summary","metadata":{"encrypted_content":"ciphertext","url":"$old"}},{"type":"tool","toolCallId":"call-1","input":"{\"url\":\"$old\"}","output":[{"type":"image","url":"$old"},{"type":"document","url":"$old","fileName":"photo.png"},{"type":"audio","url":"$old"},{"type":"video","url":"$old"}]}]}]"""
        val mapped = Json.parseToJsonElement(remapper().messageJson(source)).jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("keep"), mapped["futureField"])
        assertEquals(Json.parseToJsonElement(source).jsonArray.single().jsonObject["usage"], mapped["usage"])
        val parts = mapped.getValue("parts").jsonArray
        assertEquals(old, parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(old, parts[1].jsonObject.getValue("metadata").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("ciphertext", parts[1].jsonObject.getValue("metadata").jsonObject.getValue("encrypted_content").jsonPrimitive.content)
        val tool = parts[2].jsonObject
        assertEquals("call-1", tool.getValue("toolCallId").jsonPrimitive.content)
        assertTrue(tool.getValue("input").jsonPrimitive.content.contains(old))
        tool.getValue("output").jsonArray.forEach {
            assertEquals(target("upload/photo.png"), URI(it.jsonObject.getValue("url").jsonPrimitive.content).path)
        }
    }

    @Test fun `settings rebase avatar background font and preset attachment while preserving ordinary configuration`() {
        val old = "file://$oldRoot/upload/photo.png"
        val provider = ProviderSetting.OpenAI(apiKey = "synthetic-api-setting", models = listOf(Model(modelId = "keep-model")))
        val assistant = Assistant(
            name = "Keep assistant", systemPrompt = old,
            avatar = Avatar.Image(old), background = old,
            presetMessages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(old)))),
        )
        val settings = Settings(
            providers = listOf(provider), assistants = listOf(assistant), titlePrompt = "Keep title prompt",
            displaySetting = DisplaySetting(userAvatar = Avatar.Image(old), chatCustomFontPath = "$oldRoot/fonts/custom.ttf"),
        )
        val mapped = remapper().settings(settings)
        assertEquals(settings.providers, mapped.providers)
        assertEquals(settings.titlePrompt, mapped.titlePrompt)
        val mappedAssistant = mapped.assistants.single()
        assertEquals(assistant.id, mappedAssistant.id)
        assertEquals(old, mappedAssistant.systemPrompt)
        assertEquals(target("upload/photo.png"), URI((mappedAssistant.avatar as Avatar.Image).url).path)
        assertEquals(target("upload/photo.png"), URI(requireNotNull(mappedAssistant.background)).path)
        assertEquals(target("upload/photo.png"), URI((mappedAssistant.presetMessages.single().parts.single() as UIMessagePart.Image).url).path)
        assertEquals(target("fonts/custom.ttf"), mapped.displaySetting.chatCustomFontPath)
        assertEquals(target("upload/photo.png"), URI((mapped.displaySetting.userAvatar as Avatar.Image).url).path)
    }

    @Test fun `portable restore strips pending OAuth state but keeps reusable registration and model configuration`() {
        val registration = ChatGPTRegistration(clientId = "oaiapp_synthetic", subject = "synthetic-user")
        val credential = OAuthCredential(
            accessToken = "current-secret", refreshToken = "current-refresh", idTokenNonce = "old-nonce",
            pendingRefresh = ChatGPTPendingRefresh("pending-secret", "pending-refresh", "pending-id", receivedAt = 1),
        )
        val account = ProviderSetting.ChatGPT(
            name = "Keep account", credential = credential, chatGPTRegistration = registration,
            models = listOf(Model(modelId = "same-model")),
        )
        val nested = ProviderSetting.OpenAI(models = listOf(Model(modelId = "override", providerOverwrite = account)))
        val settings = Settings(providers = listOf(account, nested))
        val exported = remapper().settings(settings).withoutChatGPTCredentials()
        val wire = JsonInstant.encodeToString(exported)
        for (secret in listOf("current-secret", "current-refresh", "old-nonce", "pending-secret", "pending-refresh", "pending-id")) {
            assertFalse(wire.contains(secret))
        }
        val reopened = JsonInstant.decodeFromString<Settings>(wire)
        val restoredAccount = reopened.providers.first() as ProviderSetting.ChatGPT
        assertNull(restoredAccount.credential)
        assertEquals(registration, restoredAccount.chatGPTRegistration)
        assertEquals(account.models, restoredAccount.models)
        val override = reopened.providers[1].models.single().providerOverwrite as ProviderSetting.ChatGPT
        assertNull(override.credential)
        assertEquals(registration, override.chatGPTRegistration)
        assertEquals(credential, (settings.providers.first() as ProviderSetting.ChatGPT).credential)
    }
}
