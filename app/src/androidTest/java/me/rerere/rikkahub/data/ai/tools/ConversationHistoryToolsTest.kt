package me.rerere.rikkahub.data.ai.tools

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.fts.MessageFtsManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.ui.pages.search.MessageSearchScope
import me.rerere.rikkahub.ui.pages.search.SearchVM
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ConversationHistoryToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assistantA = Assistant(name = "A", enableMemory = true, enableRecentChatsReference = true)
    private val assistantB = assistantA.copy(id = Uuid.random(), name = "B")
    private val chatA = conversation(assistantA, "amber")
    private val chatB = conversation(assistantB, "cobalt")
    private val databaseName = "history-tools-${Uuid.random()}"
    private lateinit var database: AppDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var factory: ChatToolFactory

    @Before
    fun setUp() = runBlocking {
        database = AppDatabaseFactory.create(context, databaseName)
        val koin = GlobalContext.get()
        repository = ConversationRepository(
            database.conversationDao(), database.messageNodeDao(), database.favoriteDao(),
            database, koin.get(), MessageFtsManager(database),
        )
        factory = ChatToolFactory(
            context, MemoryRepository(database.memoryDao()), repository,
            localTools = koin.get(), mcpManager = koin.get(), skillManager = koin.get(),
            workspaceRepository = koin.get(), backgroundShellManager = koin.get(),
        )
        repository.insertConversation(chatA)
        repository.insertConversation(chatB)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun historyToolsStayScopedWithPrivateMemory() = checkHistoryTools(useGlobalMemory = false)

    @Test
    fun historyToolsStayScopedWithSharedMemory() = checkHistoryTools(useGlobalMemory = true)

    private fun checkHistoryTools(useGlobalMemory: Boolean) = runBlocking {
        val a = assistantA.copy(useGlobalMemory = useGlobalMemory)
        val b = assistantB.copy(useGlobalMemory = useGlobalMemory)
        val settings = Settings(assistants = listOf(a, b))
        val toolsA = factory.createTools(settings, settings.getAssistantOf(chatA), Model(), chatA)
        val toolsB = factory.createTools(settings, settings.getAssistantOf(chatB), Model(), chatB)

        assertEquals(setOf(chatA.id.toString()), toolsA.searchIds("amber"))
        assertEquals(emptySet<String>(), toolsA.searchIds("cobalt"))
        assertEquals(setOf(chatB.id.toString()), toolsB.searchIds("cobalt"))
        assertEquals(emptySet<String>(), toolsB.searchIds("amber"))
        assertEquals(setOf(chatA.id.toString()), toolsA.searchIds("shared"))
        assertEquals(setOf(chatB.id.toString()), toolsB.searchIds("shared"))
        assertEquals(setOf(chatA.id.toString()), toolsA.call("recent_chats").ids("id"))
        assertEquals(setOf(chatB.id.toString()), toolsB.call("recent_chats").ids("id"))
        assertFalse(toolsA.any { it.name == "conversation_read" })

        toolsA.call(MemoryToolNames.WRITE) {
            put("path", "/profile.md")
            put("content", "memory note from A")
            put("if_version", "new")
        }
        val read = toolsB.call(MemoryToolNames.READ) { put("path", "/profile.md") }
        assertEquals(
            if (useGlobalMemory) listOf("memory note from A") else emptyList<String>(),
            read.jsonObject.getValue("files").jsonArray.map { it.jsonObject.getValue("content").jsonPrimitive.content },
        )
    }

    @Test
    fun orphanedConversationDoesNotBorrowFallbackAssistantHistory() = runBlocking {
        val settings = Settings(assistants = listOf(assistantA), assistantId = assistantA.id)
        val fallback = settings.getAssistantOf(chatB)
        assertEquals(assistantA.id, fallback.id)
        val tools = factory.createTools(settings, fallback, Model(), chatB)
        assertFalse(tools.any { it.name == "recent_chats" || it.name == "conversation_search" })
    }

    @Test
    fun allAssistantsSearchInTheSearchPageFindsBothChats() = runBlocking {
        withContext(Dispatchers.Main) {
            val vm = SearchVM(context.applicationContext as Application, repository, GlobalContext.get().get())
            try {
                vm.onQueryChange("shared")
                vm.onScopeChange(MessageSearchScope.ALL_ASSISTANTS)
                withTimeout(5_000) { while (vm.results.isEmpty()) delay(10) }
                assertEquals(
                    setOf(chatA.id.toString(), chatB.id.toString()),
                    vm.results.map { it.conversationId }.toSet(),
                )
            } finally {
                vm.viewModelScope.cancel()
            }
        }
    }

    @Test
    fun consolidationSearchAndReadRespectTheGivenMemoryGroup() = runBlocking {
        val privateTools = createMemoryConversationTools(repository, setOf(assistantA.id))
        val sharedTools = createMemoryConversationTools(repository, setOf(assistantA.id, assistantB.id))

        assertEquals(setOf(chatA.id.toString()), privateTools.searchIds("shared"))
        assertEquals(setOf(chatA.id.toString(), chatB.id.toString()), sharedTools.searchIds("shared"))
        val denial = runCatching {
            privateTools.call("conversation_read") { put("conversation_id", chatB.id.toString()) }
        }.exceptionOrNull()
        assertEquals("conversation not found", denial?.message)
        val read = sharedTools.call("conversation_read") { put("conversation_id", chatB.id.toString()) }
        val message = read.jsonObject.getValue("messages").jsonArray.single()
        assertEquals("shared cobalt", message.jsonObject.getValue("text").jsonPrimitive.content)
    }

    private fun conversation(assistant: Assistant, keyword: String) = Conversation(
        assistantId = assistant.id,
        title = "Chat with ${assistant.name}",
        messageNodes = listOf(MessageNode.of(UIMessage.user("shared $keyword"))),
    )

    private suspend fun List<Tool>.searchIds(keyword: String): Set<String> =
        call("conversation_search") { put("query", keyword) }.ids("conversation_id")

    private suspend fun List<Tool>.call(name: String, args: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        val output = single { it.name == name }.execute(buildJsonObject(args))
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text)
    }

    private fun JsonElement.ids(key: String) =
        jsonArray.map { it.jsonObject.getValue(key).jsonPrimitive.content }.toSet()
}
