package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.stringOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val CHATGPT_BASE_URL = "https://api.openai.com/v1"
internal const val CHATGPT_TOOL_NAMESPACE = "rikkahub"

/** The public ChatGPT plan route is stateless HTTP/SSE with client-executed namespaced tools. */
internal val CHATGPT_CAPABILITIES = ResponseProviderCapabilities(
    systemMessageRole = "developer",
    supportsSamplingParams = false,
    supportsReasoningSummary = false,
    alwaysIncludeEncryptedContent = true,
    functionToolNamespace = CHATGPT_TOOL_NAMESPACE,
    requireCompletedResponse = true,
    protectedBodyFields = setOf("model", "input", "tools", "stream", "store"),
    requestBodyTransform = ::prepareChatGPTRequest,
    errorMapper = ::parseChatGPTError,
    logPayloads = false,
    reasoningEffort = { _, _ -> null },
)

internal fun chatGPTCapabilities(model: Model): ResponseProviderCapabilities {
    val catalog = model.chatGPTCapabilities
    return CHATGPT_CAPABILITIES.copy(
        supportsReasoningSummary = catalog?.supportsReasoningSummary == true,
        reasoningEffort = { level, _ ->
            when {
                level == ReasoningLevel.AUTO -> null
                level == ReasoningLevel.OFF -> "none".takeIf { it in catalog?.reasoningEfforts.orEmpty() }
                level.effort in catalog?.reasoningEfforts.orEmpty() -> level.effort
                else -> error("此 ChatGPT 模型未声明支持 ${level.effort} 推理档位，请刷新模型列表或选择自动。")
            }
        },
        requestBodyTransform = { body ->
            val reasoning = body["reasoning"] as? JsonObject
            reasoning?.stringOrNull("effort")?.let { effort ->
                require(effort in catalog?.reasoningEfforts.orEmpty()) { "ChatGPT 模型不支持推理档位：$effort。请选择自动或刷新模型列表。" }
            }
            if (catalog?.supportsWebSearch == false) {
                require((body["tools"] as? JsonArray)?.none { (it as? JsonObject)?.stringOrNull("type") == "web_search" } != false) {
                    "此 ChatGPT 模型未支持网页搜索，请关闭内置搜索或选择其他模型。"
                }
            }
            prepareChatGPTRequest(body)
        },
    )
}

class ChatGPTProvider(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.ChatGPT> {
    private val client = client.withAccountAuthenticator(credentials).newBuilder()
        .retryOnConnectionFailure(false)
        .build()
    private val responseAPI = ResponseAPI(this.client)

    override suspend fun listModels(providerSetting: ProviderSetting.ChatGPT): List<Model> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$CHATGPT_BASE_URL/models")
            .apply { configureAuth(providerSetting) }
            .get()
            .build()
        client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val error = runCatching { json.parseToJsonElement(body) }.getOrElse { JsonPrimitive(body) }
                throw parseChatGPTError(error, response.code, response.header("x-request-id"))
            }
            parseChatGPTModels(body)
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = responseAPI.streamText(
        endpoint = ResponsesEndpoint(
            url = "$CHATGPT_BASE_URL/responses",
            capabilities = chatGPTCapabilities(params.model),
            configureRequest = { configureAuth(providerSetting, params.customHeaders) },
        ),
        messages = messages,
        params = params,
    )

    override suspend fun generateText(
        providerSetting: ProviderSetting.ChatGPT,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val handler = StreamChunkHandler(params.model)
        var result = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), modelId = params.model.id))
        var finish: StreamChunk.Finish? = null
        streamText(providerSetting, messages, params).collect { chunk ->
            if (chunk is StreamChunk.Finish) finish = chunk
            result = handler.handle(result, chunk)
        }
        check(finish?.finishReason == "completed") { "ChatGPT 响应未完成，请重试。" }
        val message = result.last()
        return TextGenerationResult(
            id = finish?.responseId.orEmpty(),
            model = finish?.model ?: params.model.modelId,
            message = message,
            finishReason = finish?.finishReason,
            usage = message.usage,
        )
    }

    private suspend fun Request.Builder.configureAuth(
        setting: ProviderSetting.ChatGPT,
        customHeaders: List<CustomHeader> = emptyList(),
    ) = configureAccountAuth(credentials, setting, customHeaders) { credential ->
        check(credential.isOfficialChatGPT) { "此 ChatGPT 连接需要通过官方流程重新登录授权。" }
        check(credential.hasChatGPTPlanAccess) { "已登录，但未授予 ChatGPT 套餐调用权限。请进入提供商设置重新授权。" }
        // Persisted/custom headers from the former integration must not accompany the public route.
        listOf("ChatGPT-Account-ID", "originator", "session-id", "client-version").forEach(::removeHeader)
    }
}

/** Only account-specific, explicitly listed models are shown; absent modalities do not imply vision. */
internal fun parseChatGPTModels(body: String): List<Model> {
    val root = json.parseToJsonElement(body) as? JsonObject ?: error("ChatGPT 模型列表格式无效")
    val models = root["models"] as? JsonArray ?: error("ChatGPT 模型列表缺少 models")
    return models.mapNotNull { it as? JsonObject }
        .filter { it.stringOrNull("visibility") == "list" }
        .mapNotNull { model ->
            val slug = model.stringOrNull("slug")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val inputModalities = (model["input_modalities"] as? JsonArray)
                ?.mapNotNull { modality ->
                    when (modality.jsonPrimitiveOrNull?.contentOrNull) {
                        "text" -> Modality.TEXT
                        "image" -> Modality.IMAGE
                        else -> null // Audio/video are unsupported by the plan route.
                    }
                }?.ifEmpty { null } ?: listOf(Modality.TEXT)
            val reasoningEfforts = (model["supported_reasoning_levels"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.stringOrNull("effort") }.orEmpty()
            val reasoning = reasoningEfforts.isNotEmpty()
            Model(
                modelId = slug,
                displayName = model.stringOrNull("display_name")?.takeIf { it.isNotBlank() } ?: slug,
                inputModalities = inputModalities,
                abilities = buildList {
                    add(ModelAbility.TOOL)
                    if (reasoning) add(ModelAbility.REASONING)
                },
                contextLength = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull
                    ?.takeIf { it > 0 } ?: Model().contextLength,
                chatGPTCapabilities = ChatGPTModelCapabilities(
                    reasoningEfforts = reasoningEfforts,
                    defaultReasoningEffort = model.stringOrNull("default_reasoning_level"),
                    supportsReasoningSummary = model["supports_reasoning_summary_parameter"]?.jsonPrimitiveOrNull?.booleanOrNull,
                    supportsWebSearch = model["supports_search_tool"]?.jsonPrimitiveOrNull?.booleanOrNull,
                ),
            )
        }.distinctBy { it.modelId }
}

private val unsupportedFields = setOf(
    "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent",
    "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation",
    "user", "previous_response_id",
)

internal fun prepareChatGPTRequest(body: JsonObject): JsonObject {
    val tools = body["tools"] as? JsonArray
    tools?.forEach { element ->
        val tool = element as? JsonObject ?: error("ChatGPT 工具定义无效")
        val type = tool.stringOrNull("type")
        require(type == "namespace" || type == "web_search") { "ChatGPT 套餐不支持此服务端工具：$type。请使用客户端函数工具。" }
        if (type == "namespace") {
            require(tool.stringOrNull("name") == CHATGPT_TOOL_NAMESPACE) { "ChatGPT 工具 namespace 无效" }
            require((tool["tools"] as? JsonArray)?.all { (it as? JsonObject)?.stringOrNull("type") == "function" } == true) {
                "ChatGPT 客户端 namespace 仅支持函数工具"
            }
        }
    }
    return JsonObject(body.filterKeys { it !in unsupportedFields } + mapOf(
        "store" to JsonPrimitive(false),
        "stream" to JsonPrimitive(true),
    ))
}

/** Preserve structured diagnostics without logging request contents or credentials. */
internal class ChatGPTApiException(
    message: String,
    val status: Int?,
    val code: String?,
    val parameter: String?,
    val requestId: String?,
    val responseBody: JsonElement,
) : IllegalStateException(message)

internal fun parseChatGPTError(body: JsonElement, status: Int? = null, requestId: String? = null): Exception {
    val root = body as? JsonObject
    val response = root?.get("response") as? JsonObject
    val error = (root?.get("error") as? JsonObject) ?: (response?.get("error") as? JsonObject) ?: root
    val code = error?.stringOrNull("code")
        ?: "response_incomplete".takeIf { root?.stringOrNull("type") == "response.incomplete" }
    val parameter = error?.stringOrNull("param")
    val detail = error?.stringOrNull("message") ?: root?.stringOrNull("detail")
        ?: (response?.get("incomplete_details") as? JsonObject)?.stringOrNull("reason")
        ?: (body as? JsonPrimitive)?.contentOrNull
    val recovery = when (code) {
        "subscription_sharing_user_not_eligible" -> "此账号或工作区目前不符合 ChatGPT 套餐调用条件。请检查官方资格和策略。"
        "subscription_sharing_usage_limit_exceeded" -> "此应用的 ChatGPT 套餐调用已达到限额，请在 ChatGPT 设置 → 用量中查看。"
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> "ChatGPT 套餐服务暂时不可用，账号已保留，请稍后重试。"
        "subscription_sharing_unsupported_capability" -> "ChatGPT 套餐不支持此模型、参数或工具能力，请调整请求后重试。"
        "subscription_sharing_route_not_supported" -> "ChatGPT 套餐不支持此调用路径。"
        "subscription_sharing_invalid_user" -> "ChatGPT 身份验证失效，请检查账号并重新登录授权。"
        "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" -> "此授权不允许 ChatGPT 套餐调用，请重新授权并检查权限。"
        "response_incomplete" -> "ChatGPT 回答未完成，请重试。"
        else -> when (status) {
            401 -> "ChatGPT 身份或套餐权限未被接受，请检查账号并重新授权。"
            403 -> "ChatGPT 套餐调用被资格、地区或权限策略限制。"
            429 -> "ChatGPT 请求达到限额，请稍后重试并查看官方用量设置。"
            503 -> "ChatGPT 套餐服务暂时不可用，账号已保留，请稍后重试。"
            else -> "ChatGPT 请求失败。"
        }
    }
    val diagnostics = listOfNotNull(
        status?.let { "HTTP $it" }, code, parameter?.let { "param=$it" }, requestId?.let { "request_id=$it" }, detail,
    ).joinToString(" · ")
    return ChatGPTApiException("$recovery${if (diagnostics.isEmpty()) "" else "\n$diagnostics"}", status, code, parameter, requestId, body)
}
