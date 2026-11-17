package me.rerere.ai.provider.providers.chatgpt

import kotlinx.serialization.Serializable
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

/** Diagnostic collection only: never controls server caching or ordinary usage accounting. */
@Serializable
enum class CacheDiagnosticMode { OFF, LIGHT, FULL }

/** Process-local diagnostics: raw content is never retained and the HMAC key is never exported. */
object ChatGPTCacheDiagnostics {
    private val recorder = CacheDiagnosticRecorder()
    val mode: CacheDiagnosticMode get() = recorder.mode
    fun setMode(mode: CacheDiagnosticMode) = recorder.setMode(mode)
    internal fun collectionToken(): Long? = recorder.collectionToken()
    internal fun isCollecting(token: Long): Boolean = recorder.isCollecting(token)

    internal fun accountKey(provider: ProviderSetting.ChatGPT, credential: OAuthCredential): String =
        recorder.fingerprint(listOf(provider.id.toString(), credential.issuer, credential.subject).joinToString("\u0000"))

    internal fun begin(account: String, body: JsonObject?, sessionId: String? = null, finalSessionIdHeader: String? = null, previousAttempt: Long? = null, token: Long? = null): Long =
        recorder.begin(account, body, sessionId, finalSessionIdHeader, previousAttempt, token)
    internal fun usage(request: Long, usage: TokenUsage) = recorder.usage(request, usage)
    internal fun httpStatus(request: Long, status: Int) = recorder.httpStatus(request, status)
    internal fun completed(request: Long) = recorder.finish(request, "completed")
    internal fun ended(request: Long) = recorder.finish(request, "not_completed")
    internal fun serverDiagnostics(request: Long, response: JsonObject) = recorder.serverDiagnostics(request, response)

    fun export(provider: ProviderSetting.ChatGPT): JsonObject {
        val credential = provider.credential?.takeIf { it.isOfficialChatGPT && mode != CacheDiagnosticMode.OFF }
        return recorder.export(credential?.let { accountKey(provider, it) })
    }
}

internal class CacheDiagnosticRecorder(
    private val capacity: Int = 40,
    private val salt: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes),
    private val now: () -> Long = System::currentTimeMillis,
    initialMode: CacheDiagnosticMode = CacheDiagnosticMode.OFF,
    private val maxCharacters: Int = 262_144,
    private val maxNodes: Int = 8_192,
    private val maxInputItems: Int = 512,
) {
    private data class Item(val hash: String, val length: Int, val type: String?, val role: String?)
    private data class Record(
        val id: Long,
        val account: String,
        val at: Long,
        val collectionMode: CacheDiagnosticMode,
        val missingReasons: List<String>,
        val model: String?,
        val effort: String?,
        val session: String?,
        val sessionHeader: String?,
        val sessionHeaderPresent: Boolean?,
        val cacheKeyPresent: Boolean?,
        val cacheKey: String?,
        val previousAttempt: Long?,
        val logicalRequest: Long,
        val attemptNumber: Int,
        val mode: String?,
        val fields: Map<String, String>,
        val inputHash: String?,
        val inputCount: Int?,
        val items: List<Item>,
        val previous: Long?,
        val changedFields: List<String>,
        val matchingItems: Int?,
        val changedItem: Int?,
        var usage: TokenUsage? = null,
        var outcome: String = "in_progress",
        var durationMs: Long? = null,
        var httpStatus: Int? = null,
        var diagnosticType: String? = null,
        var usageObjectReported: Boolean? = null,
        var responseCacheKeyState: String = "not_observed",
        var responseCacheKey: String? = null,
        var responseCacheKeyPrevious: Long? = null,
        var responseCacheKeyChanged: Boolean? = null,
    )

    private val records = ArrayDeque<Record>()
    private var sequence = 0L
    private var generation = 0L
    @Volatile var mode: CacheDiagnosticMode = initialMode
        private set

    @Synchronized
    fun setMode(value: CacheDiagnosticMode) {
        if (mode != value) {
            generation++
            mode = value
            // Old in-flight requests cannot repopulate this buffer after a settings change.
            records.clear()
        }
    }

    @Synchronized fun collectionToken(): Long? = generation.takeIf { mode != CacheDiagnosticMode.OFF }
    @Synchronized fun isCollecting(token: Long): Boolean = token == generation && mode != CacheDiagnosticMode.OFF

    private fun mac(): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
    private fun ByteArray.hex(): String = buildString(size * 2) {
        for (byte in this@hex) { val value = byte.toInt() and 255; append(HEX[value ushr 4]); append(HEX[value and 15]) }
    }
    fun fingerprint(value: String): String = mac().doFinal(value.toByteArray(Charsets.UTF_8)).hex()

    /** Limits are checked before serialization, sorting, descent or UTF-8 allocation. */
    private class Budget(var characters: Int, var nodes: Int) {
        fun node(depth: Int) {
            if (--nodes < 0 || depth > 32) throw BudgetExceeded()
        }
        fun characters(count: Int) {
            if (count > characters) throw BudgetExceeded()
            characters -= count
        }
    }
    private class BudgetExceeded : RuntimeException(null, null, false, false)

    private fun digest(element: JsonElement, budget: Budget): Pair<String, Int> {
        val digest = mac()
        var length = 0
        fun write(value: String) {
            budget.characters(value.length)
            length += value.length
            digest.update(value.toByteArray(Charsets.UTF_8))
        }
        fun primitive(value: JsonPrimitive) {
            // In particular, never serialize a megabyte image/base64 string just to discover
            // that it exceeds the diagnostic budget. The model request remains untouched.
            if (value.content.length > budget.characters) throw BudgetExceeded()
            write(value.toString())
        }
        fun visit(value: JsonElement, depth: Int) {
            budget.node(depth)
            when (value) {
                is JsonObject -> {
                    if (value.size > budget.nodes) throw BudgetExceeded()
                    // Bound key comparisons before sorting: long common prefixes must not
                    // turn canonical ordering itself into an unbounded diagnostic operation.
                    var keyCharacters = 0L
                    for (key in value.keys) {
                        keyCharacters += key.length
                        if (keyCharacters > budget.characters) throw BudgetExceeded()
                    }
                    write("{")
                    value.entries.sortedBy { it.key }.forEachIndexed { index, entry ->
                        if (index != 0) write(",")
                        primitive(JsonPrimitive(entry.key)); write(":"); visit(entry.value, depth + 1)
                    }
                    write("}")
                }
                is JsonArray -> {
                    if (value.size > budget.nodes) throw BudgetExceeded()
                    write("[")
                    value.forEachIndexed { index, item -> if (index != 0) write(","); visit(item, depth + 1) }
                    write("]")
                }
                is JsonPrimitive -> primitive(value)
            }
        }
        visit(element, 0)
        return digest.doFinal().hex() to length
    }

    fun begin(account: String, body: JsonObject?, sessionId: String? = null, finalSessionIdHeader: String? = null, previousAttempt: Long? = null, token: Long? = null): Long {
        val snapshot = synchronized(this) {
            if (mode == CacheDiagnosticMode.OFF || (token != null && token != generation)) return NO_RECORD
            generation to mode
        }
        val started = now()
        val full = snapshot.second == CacheDiagnosticMode.FULL && body != null
        val missing = mutableListOf<String>()
        if (body == null) missing += "wire_body_replaced_by_interceptor"
        if (!full) missing += "content_fingerprints_not_collected"
        val budget = Budget(maxCharacters, maxNodes)
        fun hash(value: JsonElement): String? = try { digest(value, budget).first } catch (_: BudgetExceeded) {
            if ("diagnostic_work_budget_exceeded" !in missing) missing += "diagnostic_work_budget_exceeded"
            null
        }
        val session = if (full) sessionId?.takeIf { it.length > maxCharacters || it.isNotBlank() }?.let { hash(JsonPrimitive(it)) } else null
        val sessionHeader = if (full) finalSessionIdHeader?.let { hash(JsonPrimitive(it)) } else null
        val cacheKey = if (full) (body?.get("prompt_cache_key") as? JsonPrimitive)?.takeIf { it.isString }?.let(::hash) else null
        val fields = linkedMapOf<String, String>()
        val items = mutableListOf<Item>()
        val input = if (full) body?.get("input") as? JsonArray ?: JsonArray(emptyList()) else null
        if (full) {
            for (field in COMPARISON_FIELDS) hash(body?.get(field) ?: JsonNull)?.let { fields[field] = it }
            for (item in input.orEmpty().take(maxInputItems)) {
                try {
                    val (hash, length) = digest(item, budget)
                    val obj = item as? JsonObject
                    items += Item(hash, length, obj?.get("type").safeEnum(ITEM_TYPES), obj?.get("role").safeEnum(ROLES))
                } catch (_: BudgetExceeded) {
                    if ("diagnostic_work_budget_exceeded" !in missing) missing += "diagnostic_work_budget_exceeded"
                    break
                }
            }
            if (input != null && input.size > maxInputItems) missing += "input_item_limit_exceeded"
        }
        // A domain-separated digest of item digests avoids walking/copying the entire input again.
        val inputHash = input?.takeIf { it.size == items.size }?.let {
            fingerprint("input-items-v2\u0000" + items.joinToString("") { item -> item.hash })
        }
        return synchronized(this) {
            if (snapshot.first != generation || snapshot.second != mode) return@synchronized NO_RECORD
            val earlierAttempt = records.find { it.id == previousAttempt && it.account == account && it.session == session }
            val previous = session?.let { key -> records.lastOrNull { it.account == account && it.session == key } }
            val matching = previous?.takeIf {
                (it.items.isNotEmpty() || it.inputCount == 0) && (items.isNotEmpty() || input?.size == 0)
            }?.items?.zip(items)?.takeWhile { (old, new) -> old.hash == new.hash }?.size
            val changedItem = if (previous == null || matching == null) null else when {
                matching < minOf(previous.items.size, items.size) -> matching
                input != null && previous.inputCount != null && input.size < previous.inputCount && input.size == items.size -> input.size
                else -> null
            }
            val record = Record(
                id = ++sequence, account = account, at = started, collectionMode = snapshot.second, missingReasons = missing,
                model = (body?.get("model") as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 128 && MODEL_NAME.matches(it) },
                effort = (body?.get("reasoning") as? JsonObject)?.get("effort").safeEnum(EFFORTS),
                session = session, sessionHeader = sessionHeader,
                sessionHeaderPresent = if (snapshot.second == CacheDiagnosticMode.FULL) finalSessionIdHeader != null else null,
                cacheKeyPresent = if (full) body?.containsKey("prompt_cache_key") else null, cacheKey = cacheKey,
                previousAttempt = earlierAttempt?.id, logicalRequest = earlierAttempt?.logicalRequest ?: sequence,
                attemptNumber = (earlierAttempt?.attemptNumber ?: 0) + 1,
                mode = (body?.get("prompt_cache_options") as? JsonObject)?.get("mode").safeEnum(CACHE_MODES),
                fields = fields, inputHash = inputHash, inputCount = input?.size, items = items,
                previous = previous?.id,
                changedFields = previous?.let { prior -> fields.keys.filter { prior.fields.containsKey(it) && prior.fields[it] != fields[it] } }.orEmpty(),
                matchingItems = matching, changedItem = changedItem,
            )
            records.addLast(record)
            while (records.size > capacity) records.removeFirst()
            record.id
        }
    }

    @Synchronized fun usage(id: Long, usage: TokenUsage) { records.find { it.id == id }?.usage = usage }
    @Synchronized fun httpStatus(id: Long, status: Int) { records.find { it.id == id }?.httpStatus = status }
    @Synchronized fun finish(id: Long, outcome: String) {
        records.find { it.id == id }?.takeIf { it.outcome == "in_progress" }?.let {
            it.outcome = outcome
            it.durationMs = (now() - it.at).coerceAtLeast(0)
        }
    }

    fun serverDiagnostics(id: Long, response: JsonObject) {
        val full = synchronized(this) { records.find { it.id == id }?.collectionMode == CacheDiagnosticMode.FULL }
        val returnedKey = response["prompt_cache_key"]
        val state = when {
            !full -> "not_collected"
            !response.containsKey("prompt_cache_key") -> "missing"
            returnedKey == JsonNull -> "null"
            returnedKey !is JsonPrimitive || !returnedKey.isString -> "invalid_type"
            returnedKey.content.length > MAX_KEY_CHARACTERS -> "diagnostic_work_budget_exceeded"
            else -> "provided"
        }
        val returnedHash = if (state == "provided") digest(returnedKey!!, Budget(MAX_KEY_CHARACTERS * 6 + 2, 1)).first else null
        synchronized(this) {
            records.find { it.id == id && it.usageObjectReported == null }?.let { record ->
                record.diagnosticType = (response["prompt_cache_diagnostics"] as? JsonObject)?.get("type").safeEnum(DIAGNOSTIC_TYPES)
                record.usageObjectReported = response["usage"] is JsonObject
                record.responseCacheKeyState = state
                record.responseCacheKey = returnedHash
                val previous = record.session?.let { session -> records.lastOrNull {
                    it.id < record.id && it.account == record.account && it.session == session && it.usageObjectReported != null
                } }
                record.responseCacheKeyPrevious = previous?.id
                record.responseCacheKeyChanged = returnedHash?.let { current -> previous?.responseCacheKey?.let { it != current } }
            }
        }
    }

    fun export(account: String?): JsonObject {
        // Copy at most capacity records while locked; JSON formatting runs outside the lock.
        val snapshot = synchronized(this) { mode to records.filter { account != null && it.account == account }.map { it.copy() } }
        return buildJsonObject {
            put("format_version", 2)
            put("collection_mode", snapshot.first.name.lowercase())
            put("missing_reasons", JsonArray(if (snapshot.first == CacheDiagnosticMode.OFF) listOf(JsonPrimitive("diagnostics_disabled_buffer_cleared")) else emptyList()))
            put("scope", "current_account_current_app_process")
            put("comparison_basis", "client_json_fields_and_input_items_not_server_token_positions")
            put("input_hash_basis", "HMAC_of_ordered_item_HMACs_v2")
            put("hash_scope", "HMAC_SHA256_random_process_key_not_exported")
            put("capacity", capacity)
            put("max_canonical_characters_per_request", maxCharacters)
            put("max_json_nodes_per_request", maxNodes)
            put("max_json_depth", 32)
            put("max_input_items", maxInputItems)
            put("requests", JsonArray(snapshot.second.map { record -> buildJsonObject {
                put("sequence", record.id)
                put("timestamp_epoch_ms", record.at)
                put("collection_mode", record.collectionMode.name.lowercase())
                put("diagnostics_complete", record.missingReasons.isEmpty())
                put("missing_reasons", JsonArray(record.missingReasons.map(::JsonPrimitive)))
                put("duration_ms", record.durationMs?.let(::JsonPrimitive) ?: JsonNull)
                put("http_status", record.httpStatus?.let(::JsonPrimitive) ?: JsonNull)
                put("model", record.model?.let(::JsonPrimitive) ?: JsonNull)
                put("reasoning_effort", record.effort?.let(::JsonPrimitive) ?: JsonNull)
                put("session_id_hash", record.session?.let(::JsonPrimitive) ?: JsonNull)
                put("request_session_id_header_present", record.sessionHeaderPresent?.let(::JsonPrimitive) ?: JsonNull)
                put("request_session_id_header_hash", record.sessionHeader?.let(::JsonPrimitive) ?: JsonNull)
                put("request_prompt_cache_key_present", record.cacheKeyPresent?.let(::JsonPrimitive) ?: JsonNull)
                put("prompt_cache_key_hash", record.cacheKey?.let(::JsonPrimitive) ?: JsonNull)
                put("request_session_id_header_matches_body_key", record.sessionHeader?.let { header -> record.cacheKey?.let { header == it } }?.let(::JsonPrimitive) ?: JsonNull)
                put("wire_attempt_number", record.attemptNumber)
                put("logical_request_sequence", record.logicalRequest)
                put("previous_attempt_sequence", record.previousAttempt?.let(::JsonPrimitive) ?: JsonNull)
                put("cache_mode", record.mode?.let(::JsonPrimitive) ?: JsonNull)
                put("request_field_hashes", JsonObject(record.fields.mapValues { JsonPrimitive(it.value) }))
                put("input_hash", record.inputHash?.let(::JsonPrimitive) ?: JsonNull)
                put("input_item_count", record.inputCount?.let(::JsonPrimitive) ?: JsonNull)
                put("input_items_truncated", record.inputCount?.let { JsonPrimitive(it > record.items.size) } ?: JsonNull)
                put("input_items", JsonArray(record.items.map { item -> buildJsonObject {
                    put("hash", item.hash); put("json_character_length", item.length)
                    put("type", item.type?.let(::JsonPrimitive) ?: JsonNull); put("role", item.role?.let(::JsonPrimitive) ?: JsonNull)
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
                    "not_observed", "not_collected" -> JsonNull
                    "missing" -> JsonPrimitive(false)
                    else -> JsonPrimitive(true)
                })
                put("response_prompt_cache_key_hash", record.responseCacheKey?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_compared_to_sequence", record.responseCacheKeyPrevious?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_changed", record.responseCacheKeyChanged?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_matches_request", record.responseCacheKey?.let { response -> record.cacheKey?.let { response == it } }?.let(::JsonPrimitive) ?: JsonNull)
                put("response_prompt_cache_key_matches_session_id_header", record.responseCacheKey?.let { response -> record.sessionHeader?.let { response == it } }?.let(::JsonPrimitive) ?: JsonNull)
            } }))
        }
    }

    private fun JsonElement?.safeEnum(allowed: Set<String>): String? =
        (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 64 && it in allowed }

    companion object {
        const val NO_RECORD = -1L
        private const val HEX = "0123456789abcdef"
        private const val MAX_KEY_CHARACTERS = 4096
        private val MODEL_NAME = Regex("[A-Za-z0-9._:-]{1,128}")
        private val COMPARISON_FIELDS = listOf("model", "instructions", "tools", "reasoning", "parallel_tool_calls", "text", "service_tier", "prompt_cache_key", "prompt_cache_options")
        private val EFFORTS = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        private val CACHE_MODES = setOf("implicit", "explicit")
        private val ITEM_TYPES = setOf("message", "function_call", "function_call_output", "reasoning", "compaction", "item_reference")
        private val ROLES = setOf("system", "developer", "user", "assistant")
        private val DIAGNOSTIC_TYPES = setOf("cache_hit", "cache_miss", "comparison_response_not_found", "unavailable")
    }
}
