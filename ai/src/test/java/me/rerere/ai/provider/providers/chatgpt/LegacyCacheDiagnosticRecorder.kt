package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Exact recorder from custom baseline 929c17c8, retained only for repeatable benchmarks. */
internal class LegacyCacheDiagnosticRecorder(
    private val capacity: Int = 40,
    private val salt: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Item(val hash: String, val length: Int, val type: String?, val role: String?)
    private data class Record(
        val id: Long,
        val account: String,
        val at: Long,
        val model: String?,
        val effort: String?,
        val session: String?,
        val sessionHeader: String?,
        val cacheKeyPresent: Boolean,
        val cacheKey: String?,
        val previousAttempt: Long?,
        val logicalRequest: Long,
        val attemptNumber: Int,
        val mode: String?,
        val fields: Map<String, String>,
        val inputHash: String,
        val inputCount: Int,
        val items: List<Item>,
        val previous: Long?,
        val changedFields: List<String>,
        val matchingItems: Int?,
        val changedItem: Int?,
        var usage: TokenUsage? = null,
        var outcome: String = "in_progress",
        var diagnosticType: String? = null,
        var usageObjectReported: Boolean? = null,
        var responseCacheKeyState: String = "not_observed",
        var responseCacheKey: String? = null,
        var responseCacheKeyPrevious: Long? = null,
        var responseCacheKeyChanged: Boolean? = null,
    )

    private val records = ArrayDeque<Record>()
    private var sequence = 0L

    fun fingerprint(value: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(salt, "HmacSHA256"))
        doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Synchronized
    fun begin(account: String, body: JsonObject, sessionId: String? = null, finalSessionIdHeader: String? = null, previousAttempt: Long? = null): Long {
        val input = body["input"] as? JsonArray ?: JsonArray(emptyList())
        val session = sessionId?.takeIf { it.isNotBlank() }?.let(::fingerprint)
        val sessionHeader = finalSessionIdHeader?.let { fingerprint(canonical(JsonPrimitive(it))) }
        val cacheKey = (body["prompt_cache_key"] as? JsonPrimitive)?.takeIf { it.isString }?.let { fingerprint(canonical(it)) }
        val earlierAttempt = records.find { it.id == previousAttempt && it.account == account && it.session == session }
        val fields = COMPARISON_FIELDS.associateWith { fingerprint(canonical(body[it] ?: JsonNull)) }
        val items = input.take(MAX_INPUT_ITEMS).map { item ->
            val value = canonical(item)
            val obj = item as? JsonObject
            Item(fingerprint(value), value.length, obj?.get("type").safeEnum(ITEM_TYPES), obj?.get("role").safeEnum(ROLES))
        }
        // Group by the client session, independently of an overridden final wire cache key.
        // Auxiliary requests without a session must never be grouped by a coincidental cache key.
        val previous = session?.let { key -> records.lastOrNull { it.account == account && it.session == key } }
        val matching = previous?.items?.zip(items)?.takeWhile { (old, new) -> old.hash == new.hash }?.size
        val changedItem = if (previous == null || matching == null) null else when {
            matching < minOf(previous.items.size, items.size) -> matching
            input.size < previous.inputCount -> input.size
            else -> null
        }
        val record = Record(
            id = ++sequence, account = account, at = now(),
            model = (body["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { MODEL_NAME.matches(it) },
            effort = (body["reasoning"] as? JsonObject)?.get("effort").safeEnum(EFFORTS),
            session = session,
            sessionHeader = sessionHeader,
            cacheKeyPresent = body.containsKey("prompt_cache_key"),
            cacheKey = cacheKey,
            previousAttempt = earlierAttempt?.id,
            logicalRequest = earlierAttempt?.logicalRequest ?: sequence,
            attemptNumber = (earlierAttempt?.attemptNumber ?: 0) + 1,
            mode = (body["prompt_cache_options"] as? JsonObject)?.get("mode").safeEnum(CACHE_MODES),
            fields = fields, inputHash = fingerprint(canonical(input)), inputCount = input.size, items = items,
            previous = previous?.id,
            changedFields = previous?.let { prior -> fields.keys.filter { prior.fields[it] != fields[it] } }.orEmpty(),
            matchingItems = matching, changedItem = changedItem,
        )
        records.addLast(record)
        while (records.size > capacity) records.removeFirst()
        return record.id
    }

    @Synchronized
    fun usage(id: Long, usage: TokenUsage) { records.find { it.id == id }?.usage = usage }

    @Synchronized
    fun finish(id: Long, outcome: String) {
        records.find { it.id == id }?.takeIf { it.outcome == "in_progress" }?.outcome = outcome
    }

    @Synchronized
    fun serverDiagnostics(id: Long, response: JsonObject) {
        val diagnostic = response["prompt_cache_diagnostics"] as? JsonObject
        records.find { it.id == id && it.usageObjectReported == null }?.let { record ->
            record.diagnosticType = diagnostic?.get("type").safeEnum(DIAGNOSTIC_TYPES)
            record.usageObjectReported = response["usage"] is JsonObject
            val returnedKey = response["prompt_cache_key"]
            record.responseCacheKeyState = when {
                !response.containsKey("prompt_cache_key") -> "missing"
                returnedKey == JsonNull -> "null"
                returnedKey !is JsonPrimitive || !returnedKey.isString -> "invalid_type"
                else -> "provided"
            }
            record.responseCacheKey = returnedKey?.takeIf { record.responseCacheKeyState == "provided" }?.let { fingerprint(canonical(it)) }
            val previous = record.session?.let { session -> records.lastOrNull {
                it.id < record.id && it.account == record.account && it.session == session && it.usageObjectReported != null
            } }
            record.responseCacheKeyPrevious = previous?.id
            record.responseCacheKeyChanged = record.responseCacheKey?.let { current -> previous?.responseCacheKey?.let { it != current } }
        }
    }

    @Synchronized
    fun export(account: String?): JsonObject = buildJsonObject {
        put("format_version", 1)
        put("scope", "current_account_current_app_process")
        put("comparison_basis", "client_json_fields_and_input_items_not_server_token_positions")
        put("hash_scope", "HMAC_SHA256_random_process_key_not_exported")
        put("capacity", capacity)
        put("requests", JsonArray(records.filter { account != null && it.account == account }.map { record ->
            buildJsonObject {
                put("sequence", record.id)
                put("timestamp_epoch_ms", record.at)
                put("model", record.model?.let(::JsonPrimitive) ?: JsonNull)
                put("reasoning_effort", record.effort?.let(::JsonPrimitive) ?: JsonNull)
                put("session_id_hash", record.session?.let(::JsonPrimitive) ?: JsonNull)
                put("request_session_id_header_present", record.sessionHeader != null)
                put("request_session_id_header_hash", record.sessionHeader?.let(::JsonPrimitive) ?: JsonNull)
                put("request_prompt_cache_key_present", record.cacheKeyPresent)
                put("prompt_cache_key_hash", record.cacheKey?.let(::JsonPrimitive) ?: JsonNull)
                put("request_session_id_header_matches_body_key", record.sessionHeader?.let { header -> record.cacheKey?.let { header == it } }?.let(::JsonPrimitive) ?: JsonNull)
                put("wire_attempt_number", record.attemptNumber)
                put("logical_request_sequence", record.logicalRequest)
                put("previous_attempt_sequence", record.previousAttempt?.let(::JsonPrimitive) ?: JsonNull)
                put("cache_mode", record.mode?.let(::JsonPrimitive) ?: JsonNull)
                put("request_field_hashes", JsonObject(record.fields.mapValues { JsonPrimitive(it.value) }))
                put("input_hash", record.inputHash)
                put("input_item_count", record.inputCount)
                put("input_items_truncated", record.inputCount > record.items.size)
                put("input_items", JsonArray(record.items.map { item -> buildJsonObject {
                    put("hash", item.hash)
                    put("json_character_length", item.length)
                    put("type", item.type?.let(::JsonPrimitive) ?: JsonNull)
                    put("role", item.role?.let(::JsonPrimitive) ?: JsonNull)
                } }))
                put("compared_to_sequence", record.previous?.let(::JsonPrimitive) ?: JsonNull)
                put("changed_request_fields", JsonArray(record.changedFields.map(::JsonPrimitive)))
                put("matching_input_items", record.matchingItems?.let(::JsonPrimitive) ?: JsonNull)
                put("first_changed_input_index", record.changedItem?.let(::JsonPrimitive) ?: JsonNull)
                put("outcome", record.outcome)
                put("decoded_usage_received", record.usage != null)
                put("usage_object_reported", record.usageObjectReported?.let(::JsonPrimitive) ?: JsonNull)
                put("input_tokens", record.usage?.takeIf { it.promptTokensReported }?.promptTokens?.let(::JsonPrimitive) ?: JsonNull)
                put("cached_tokens", record.usage?.takeIf { it.cachedTokensReported }?.cachedTokens?.let(::JsonPrimitive) ?: JsonNull)
                put("cache_write_tokens", record.usage?.cacheWriteTokens?.let(::JsonPrimitive) ?: JsonNull)
                put("input_tokens_reported", record.usage?.promptTokensReported == true)
                put("cached_tokens_reported", record.usage?.cachedTokensReported == true)
                put("cache_write_tokens_reported", record.usage?.cacheWriteTokens != null)
                put("server_diagnostic_type", record.diagnosticType?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_state", record.responseCacheKeyState)
                put("response_prompt_cache_key_present", when (record.responseCacheKeyState) {
                    "not_observed" -> JsonNull
                    "missing" -> JsonPrimitive(false)
                    else -> JsonPrimitive(true)
                })
                put("response_prompt_cache_key_hash", record.responseCacheKey?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_compared_to_sequence", record.responseCacheKeyPrevious?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_changed", record.responseCacheKeyChanged?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_matches_request", record.responseCacheKey?.let { response -> record.cacheKey?.let { response == it } }?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_matches_session_id_header", record.responseCacheKey?.let { response -> record.sessionHeader?.let { response == it } }?.let(::JsonPrimitive) ?: JsonNull)
            }
        }))
    }

    private fun canonical(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) -> "${JsonPrimitive(key)}:${canonical(value)}" }
        is JsonArray -> element.joinToString(",", "[", "]", transform = ::canonical)
        else -> element.toString()
    }

    private fun JsonElement?.safeEnum(allowed: Set<String>): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it in allowed }

    companion object {
        private const val MAX_INPUT_ITEMS = 512
        private val MODEL_NAME = Regex("[A-Za-z0-9._:-]{1,128}")
        private val COMPARISON_FIELDS = listOf("model", "instructions", "tools", "reasoning", "parallel_tool_calls", "text", "service_tier", "prompt_cache_key", "prompt_cache_options")
        private val EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        private val CACHE_MODES = setOf("implicit", "explicit")
        private val ITEM_TYPES = setOf("message", "function_call", "function_call_output", "reasoning", "compaction", "item_reference")
        private val ROLES = setOf("system", "developer", "user", "assistant")
        private val DIAGNOSTIC_TYPES = setOf("cache_hit", "cache_miss", "comparison_response_not_found", "unavailable")
    }
}
