package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ChatGPTModelCapabilities
import me.rerere.ai.provider.ChatGPTCatalogField
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.supportedChatGPTReasoningLevels
import me.rerere.ai.provider.providers.configureAccountAuth
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.ResponseProviderCapabilities
import me.rerere.ai.provider.providers.openai.ResponsesEndpoint
import me.rerere.ai.provider.providers.withAccountAuthenticator
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
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
    supportedBuiltInTools = setOf(BuiltInTools.Search),
    validateCustomBody = ::validateChatGPTCustomBody,
    requestBodyTransform = ::prepareChatGPTRequest,
    validateRequestBody = ::validateChatGPTRequestBody,
    errorMapper = ::parseChatGPTError,
    logPayloads = false,
    isReasoningModel = { ModelAbility.REASONING in it.abilities || it.chatGPTCapabilities?.reasoningEfforts?.isNotEmpty() == true },
)

internal fun chatGPTCapabilities(model: Model): ResponseProviderCapabilities {
    val catalog = model.chatGPTCapabilities
    return CHATGPT_CAPABILITIES.copy(
        supportedBuiltInTools = if (catalog?.supportsWebSearch == false) emptySet() else setOf(BuiltInTools.Search),
        supportsReasoningSummary = catalog?.supportsReasoningSummary == true,
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
    ): Flow<StreamChunk> = flow {
        // Snapshot collection permission for this logical request. Turning diagnostics off
        // invalidates it, including retries and requests already waiting for authentication.
        val diagnosticToken = ChatGPTCacheDiagnostics.collectionToken()
        var account: String? = null
        var diagnosticRequest: Long? = null
        try {
            responseAPI.streamText(
                endpoint = ResponsesEndpoint(
                    url = "$CHATGPT_BASE_URL/responses",
                    capabilities = chatGPTCapabilities(params.model),
                    configureRequest = {
                        configureAuth(
                            providerSetting, params.customHeaders,
                            sessionId = params.sessionId, manageSessionAffinity = true,
                        ) { credential ->
                            if (diagnosticToken != null && ChatGPTCacheDiagnostics.isCollecting(diagnosticToken)) {
                                account = ChatGPTCacheDiagnostics.accountKey(providerSetting, credential)
                            }
                        }
                    },
                    observationEnabled = { diagnosticToken != null && ChatGPTCacheDiagnostics.isCollecting(diagnosticToken) },
                    observeRequest = if (diagnosticToken == null) null else { request, body ->
                        account?.let { identity ->
                            val previous = diagnosticRequest
                            previous?.let(ChatGPTCacheDiagnostics::ended)
                            diagnosticRequest = ChatGPTCacheDiagnostics.begin(
                                identity, body, params.sessionId,
                                finalSessionIdHeader = request.header("session_id"), previousAttempt = previous, token = diagnosticToken,
                            ).takeIf { it != CacheDiagnosticRecorder.NO_RECORD }
                        }
                    },
                    observeHttpStatus = if (diagnosticToken == null) null else { status ->
                        diagnosticRequest?.let { ChatGPTCacheDiagnostics.httpStatus(it, status) }
                    },
                    observeCompletedResponse = if (diagnosticToken == null) null else { response ->
                        diagnosticRequest?.let { ChatGPTCacheDiagnostics.serverDiagnostics(it, response) }
                    },
                ),
                messages = messages,
                params = params,
            ).collect { chunk ->
                // Usage is the actual terminal SSE snapshot; absence stays unknown.
                runCatching {
                    diagnosticRequest?.let { request ->
                        when (chunk) {
                            is StreamChunk.Usage -> ChatGPTCacheDiagnostics.usage(request, chunk.usage)
                            is StreamChunk.Finish -> ChatGPTCacheDiagnostics.completed(request)
                            else -> Unit
                        }
                    }
                }
                emit(chunk)
            }
        } finally {
            runCatching { diagnosticRequest?.let(ChatGPTCacheDiagnostics::ended) }
        }
    }

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
        sessionId: String? = null,
        manageSessionAffinity: Boolean = false,
        observeCredential: (OAuthCredential) -> Unit = {},
    ) {
        // Keep the original configured values separate from generated headers across a 401 retry.
        val configuredSessionHeaders = setting.mergeCustomHeaders(customHeaders).values("session_id")
        configureAccountAuth(credentials, setting, customHeaders) { credential ->
            check(credential.isOfficialChatGPT) { "此 ChatGPT 连接需要通过官方流程重新登录授权。" }
            check(credential.hasChatGPTPlanAccess) { "已登录，但未授予 ChatGPT 套餐调用权限。请进入提供商设置重新授权。" }
            if (manageSessionAffinity) configureChatGPTSessionAffinity(credential, sessionId, configuredSessionHeaders)
            runCatching { observeCredential(credential) }
            // Persisted/custom headers from the former integration must not accompany the public route.
            listOf("ChatGPT-Account-ID", "originator", "session-id", "client-version").forEach(::removeHeader)
        }
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
                contextLength = model["context_window"]?.jsonPrimitiveOrNull?.intOrNull?.takeIf { it > 0 }
                    ?: ModelRegistry.MODEL_CONTEXT_LENGTH.getData(slug)?.takeIf { it > 0 } ?: 0,
                chatGPTCatalogFields = ChatGPTCatalogField.all,
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
    validateChatGPTTools(tools)
    validateChatGPTToolChoice(body["tool_choice"])
    validateChatGPTPromptCacheOptions(body)
    val include = body["include"]
    require(include == null || include is JsonArray) { "ChatGPT 自定义 include 必须是数组，且需保留 reasoning.encrypted_content 以延续多轮推理。" }
    val includables = (include as? JsonArray).orEmpty()
    require(includables.all { it is JsonPrimitive && it.isString }) { "ChatGPT include 必须是字符串数组。" }
    val unsupportedIncludes = includables.map { (it as JsonPrimitive).content }
        .filter { it.startsWith("file_search") || it.startsWith("computer_call") || it.startsWith("code_interpreter") }
    require(unsupportedIncludes.isEmpty()) { "ChatGPT 套餐不支持 include 中的服务端工具：${unsupportedIncludes.joinToString()}。" }
    val compatible = body.filterKeys { it !in unsupportedFields }.toMutableMap()
    tools?.let { compatible["tools"] = stableChatGPTToolDefinitions(it) }
    return JsonObject(compatible + mapOf(
        "store" to JsonPrimitive(false),
        "stream" to JsonPrimitive(true),
        // A custom include list may request additional output, but cannot erase stateless history.
        "include" to JsonArray((includables + JsonPrimitive("reasoning.encrypted_content")).distinct()),
    ))
}

private fun validateChatGPTCustomBody(bodies: List<CustomBody>) {
    val custom = JsonObject(emptyMap()).mergeCustomBody(bodies)
    val unsupported = custom.keys.intersect(unsupportedFields)
    require(unsupported.isEmpty()) {
        "高级自定义请求指定了官方 ChatGPT 套餐路由不支持的字段：${unsupported.joinToString()}。请移除这些字段；普通模型参数会按套餐能力自动处理。"
    }
    custom["tools"]?.let { value ->
        val tools = value as? JsonArray
        require(tools != null) { "高级自定义请求 tools 必须是数组；ChatGPT 套餐工具由客户端管理。" }
        validateChatGPTTools(tools, advancedField = "tools")
    }
    validateChatGPTToolChoice(custom["tool_choice"])
}

private fun validateChatGPTRequestBody(body: JsonObject, params: TextGenerationParams) {
    val catalog = params.model.chatGPTCapabilities
    require(params.reasoningLevel in params.model.supportedChatGPTReasoningLevels()) {
        if (params.reasoningLevel == ReasoningLevel.OFF) {
            "此 ChatGPT 模型未声明支持关闭推理（reasoning.effort=none）。请选择自动或模型支持的档位；不会把关闭静默改成其他档位。"
        } else {
            "此 ChatGPT 模型未声明支持 ${params.reasoningLevel.effort} 推理档位，请刷新模型列表或选择自动。"
        }
    }
    val reasoning = body["reasoning"]
    require(reasoning == null || reasoning is JsonObject) { "ChatGPT 自定义 reasoning 必须是对象，不能覆盖或删除已选择的思考档位。" }
    val reasoningObject = reasoning as? JsonObject
    val expectedEffort = params.reasoningLevel.effort.takeUnless { params.reasoningLevel == ReasoningLevel.AUTO }
    val effortValue = reasoningObject?.get("effort")
    val actualEffort = (effortValue as? JsonPrimitive)?.takeIf { it.isString }?.content
    require((effortValue == null || actualEffort != null) && actualEffort == expectedEffort) {
        "高级自定义请求 reasoning.effort 与界面思考档位冲突（当前应为 ${expectedEffort ?: "自动：省略 effort"}）。请移除自定义 effort，或选择一致且受支持的档位。"
    }
    for (key in listOf("summary", "generate_summary")) {
        val summary = reasoningObject?.get(key)
        if (summary != null) {
            require(catalog?.supportsReasoningSummary == true) { "此 ChatGPT 模型未声明支持 reasoning.$key；请移除此自定义推理摘要参数。" }
            require(summary == JsonNull || (summary is JsonPrimitive && summary.isString && summary.content in setOf("auto", "concise", "detailed"))) {
                "reasoning.$key 必须是 auto、concise 或 detailed。"
            }
        }
    }
    if (catalog?.supportsWebSearch == false) {
        require((body["tools"] as? JsonArray)?.none { (it as? JsonObject)?.stringOrNull("type") == "web_search" } != false &&
            !toolChoiceUsesWebSearch(body["tool_choice"])) {
            "此 ChatGPT 模型未支持网页搜索，请关闭内置搜索或移除自定义 tool_choice。"
        }
    }
    for (field in listOf("instructions", "prompt_cache_key")) {
        val value = body[field]
        require(value == null || value == JsonNull || (value is JsonPrimitive && value.isString)) {
            "ChatGPT 自定义 $field 必须是字符串或 null。"
        }
    }
}

private fun toolChoiceUsesWebSearch(choice: JsonElement?): Boolean {
    val objectChoice = choice as? JsonObject ?: return false
    return objectChoice.stringOrNull("type") in setOf("web_search", "web_search_preview", "web_search_preview_2025_03_11") ||
        (objectChoice["tools"] as? JsonArray)?.any(::toolChoiceUsesWebSearch) == true
}

private fun validateChatGPTToolChoice(choice: JsonElement?, path: String = "tool_choice") {
    if (choice == null || choice == JsonNull) return
    if (choice is JsonPrimitive) {
        require(choice.isString && choice.content in setOf("auto", "none", "required")) { "高级自定义请求 $path 必须是 auto、none、required 或合法工具选择对象。" }
        return
    }
    val selected = choice as? JsonObject ?: error("高级自定义请求 $path 格式无效")
    when (selected.stringOrNull("type")) {
        "allowed_tools" -> {
            require(selected.stringOrNull("mode") in setOf("auto", "required")) { "高级自定义请求 $path.mode 必须是 auto 或 required。" }
            val tools = selected["tools"] as? JsonArray ?: error("高级自定义请求 $path.tools 必须是数组")
            tools.forEachIndexed { index, tool -> validateChatGPTToolChoice(tool, "$path.tools[$index]") }
        }
        "function" -> {
            require(!selected.stringOrNull("name").isNullOrBlank()) { "高级自定义请求 $path 缺少函数名称。" }
            val namespace = selected.stringOrNull("namespace")
            require(namespace == null || namespace == CHATGPT_TOOL_NAMESPACE) { "高级自定义请求 $path 指定了无效的客户端工具 namespace。" }
        }
        "web_search", "web_search_preview", "web_search_preview_2025_03_11" -> Unit
        else -> validateChatGPTTools(JsonArray(listOf(selected)), advancedField = path)
    }
}

private fun validateChatGPTTools(tools: JsonArray?, advancedField: String? = null) {
    tools?.forEach { element ->
        val tool = element as? JsonObject ?: error("ChatGPT 工具定义无效")
        val type = tool.stringOrNull("type")
        require(type == "namespace" || type == "web_search") {
            val source = if (advancedField != null) "高级自定义请求 $advancedField 明确指定了" else "请求包含"
            if (type == "image_generation") {
                "${source} image_generation，但官方 ChatGPT 套餐路由尚不支持原生图像生成。请移除此自定义工具；图片输入识别和独立生图提供商不受影响。"
            } else {
                "${source}不支持的服务端工具：$type。ChatGPT 套餐仍可使用本机 MCP 和函数工具。"
            }
        }
        if (type == "namespace") {
            require(tool.stringOrNull("name") == CHATGPT_TOOL_NAMESPACE) { "ChatGPT 工具 namespace 无效" }
            require((tool["tools"] as? JsonArray)?.all { (it as? JsonObject)?.stringOrNull("type") == "function" } == true) {
                "ChatGPT 客户端 namespace 仅支持函数工具"
            }
        }
    }
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
        "subscription_sharing_usage_limit_exceeded" -> "ChatGPT 套餐或此应用的调用限额已达到，请在 ChatGPT 设置 → 用量中查看适用限额。"
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
