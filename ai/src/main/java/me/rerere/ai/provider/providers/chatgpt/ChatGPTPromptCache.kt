package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.common.http.stringOrNull

/**
 * Do not opt the plan route into API cache modes whose account support is unknown.
 * If advanced settings explicitly choose a mode, reject a configuration documented
 * to disable all caching rather than silently changing its meaning.
 */
internal fun validateChatGPTPromptCacheOptions(body: JsonObject) {
    require("prompt_cache_diagnostics" !in body) {
        "prompt_cache_diagnostics 是服务端响应字段，不能作为 ChatGPT 请求参数。当前连接未自动启用服务端缓存诊断。"
    }
    val value = body["prompt_cache_options"] ?: return
    if (value == JsonNull) return
    val options = value as? JsonObject
        ?: error("高级自定义请求 prompt_cache_options 必须是对象。")
    val modeValue = options["mode"] ?: return
    val mode = (modeValue as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(mode == "implicit" || mode == "explicit") {
        "prompt_cache_options.mode 只能是 implicit 或 explicit；具体支持情况由当前模型和授权路由决定。"
    }
    if (mode == "explicit") {
        val hasBreakpoint = (body["input"] as? JsonArray).orEmpty().any { item ->
            val input = item as? JsonObject ?: return@any false
            if (input.stringOrNull("type") == "additional_tools") return@any false
            val content = if (input.stringOrNull("type") == "function_call_output") {
                input["output"]
            } else {
                input["content"]
            }
            (content as? JsonArray).orEmpty().any blockLoop@{ element ->
                val block = element as? JsonObject ?: return@blockLoop false
                block.stringOrNull("type") in setOf("input_text", "input_image", "input_file") &&
                    (block["prompt_cache_breakpoint"] as? JsonObject)?.stringOrNull("mode") == "explicit"
            }
        }
        require(hasBreakpoint) {
            "高级自定义请求开启了 explicit 缓存模式，但最终输入没有有效内容断点；官方规则下这会禁用缓存读取和写入。请移除该模式设置以恢复模型默认行为；应用不会擅自添加缓存参数或断点。"
        }
    }
}

/** MCP/tool catalog refresh order and JSON object insertion order are not semantic. */
internal fun stableChatGPTToolDefinitions(tools: JsonArray): JsonArray = JsonArray(
    tools.map { element ->
        val tool = element as? JsonObject ?: return@map element
        val normalized = if (tool.stringOrNull("type") == "namespace") {
            val functions = tool["tools"] as? JsonArray
            if (functions == null) tool else JsonObject(tool + ("tools" to JsonArray(
                functions.sortedBy { (it as? JsonObject)?.stringOrNull("name").orEmpty() }
            )))
        } else tool
        stableObjectKeys(normalized)
    }.sortedBy { element ->
        val tool = element as? JsonObject
        "${tool?.stringOrNull("type").orEmpty()}:${tool?.stringOrNull("name").orEmpty()}"
    }
)

private fun stableObjectKeys(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.toSortedMap().mapValues { (_, entry) -> stableObjectKeys(entry) })
    is JsonArray -> JsonArray(value.map(::stableObjectKeys))
    else -> value
}
