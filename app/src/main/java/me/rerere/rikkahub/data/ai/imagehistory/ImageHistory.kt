package me.rerere.rikkahub.data.ai.imagehistory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.MediaImageVersionId
import me.rerere.rikkahub.data.model.supportsPlatformEditing
import kotlin.uuid.Uuid

sealed interface ImageHistoryDecision {
    data class Edit(
        val base: MediaImageVersionId,
        val references: List<MediaImageVersionId>,
        val instruction: String,
    ) : ImageHistoryDecision

    /** Every option is a complete edit. Choosing it needs no further chat request. */
    data class Choose(val options: List<Edit>) : ImageHistoryDecision
}

private val relativeRounds = Regex(
    """([0-9]+|[零一二两三四五六七八九十]+)\s*(?:轮|版|次(?:修改|编辑)?|步)(?:以|之)?前|\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:rounds?|versions?|edits?|steps?)\s+(?:ago|back)\b""",
    RegexOption.IGNORE_CASE,
)
private val numberedVersion = Regex(
    """第\s*([0-9]+|[零一二两三四五六七八九十]+)\s*[版轮]|\b(?:version|revision)\s*#?([0-9]+)\b""",
    RegexOption.IGNORE_CASE,
)
private val precedingVersion = Regex("""上上?一?[轮版]|前一[轮版]""")
// Bare time words also describe ordinary pictures ("以前的上海街景"). Look for a prior output or edit attribute.
private val historyCue = Regex(
    """(?:之前|先前|早先|以前|最早|最初|原先)(?:(?:的|那次的)?(?:背景|配色|风格|色调|颜色|构图|字体)|(?:的|生成的|用过的)?那?一?(?:版|张图))""" +
        """|(?:历史版本|历史记录[中里]|上上?一?[轮版]|上次(?:的配色|的风格|那[张版])|上一张(?:图|结果)|前一[轮版]|那一?版)""" +
        """|(?:旧版|初版|原版)的?(?:配色|构图|风格|底色|背景)|(?:退回|改回|沿用|恢复|参照|参考|回到)(?:到)?(?:那张|那一张)?(?:旧图(?:片)?(?=$|[的中里，。 ])|旧版|原版|初版)""" +
        """|\b(?:(?:earlier|previous|original|first|older|last)\s+(?:one|image|version|edit|round|background|palette)|revert|go back to)\b""",
    RegexOption.IGNORE_CASE,
)

fun needsImageHistory(prompt: String): Boolean =
    historyCue.containsMatchIn(prompt) || relativeRounds.containsMatchIn(prompt) || numberedVersion.containsMatchIn(prompt)

// A reference to color/style never authorizes replacing the selected base. An explicit instruction can.
private val explicitBase = Regex(
    """(?:以|把|将|拿|用).{1,48}(?:作为|用作|当作|为|做)(?:编辑)?底图|(?:从|基于).{1,36}(?:开始|继续|重新)(?:编辑|改|画)|\b(?:start|continue|branch|work) (?:from|on)\b|\bas (?:the |my )?base\b""",
    RegexOption.IGNORE_CASE,
)

private fun roundNumber(value: String): Int {
    value.toIntOrNull()?.let { return it }
    val english = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
    english.indexOf(value.lowercase()).takeIf { it >= 0 }?.let { return it + 1 }
    val digits = "零一二三四五六七八九"
    val parts = value.replace('两', '二').split('十')
    fun digit(s: String) = s.singleOrNull()?.let(digits::indexOf)?.takeIf { it >= 0 }
    return when (parts.size) {
        1 -> digit(parts[0])
        2 -> {
            val tens = if (parts[0].isEmpty()) 1 else digit(parts[0])
            val ones = if (parts[1].isEmpty()) 0 else digit(parts[1])
            if (tens != null && ones != null) tens * 10 + ones else null
        }
        else -> null
    } ?: error("无法解析历史轮数，请手动选择参考图")
}

internal data class HistoryImage(
    val id: MediaImageVersionId,
    val record: MediaCreationRecord,
    val label: String,
) {
    val output get() = record.outputs[id.outputIndex]
}

internal class ImageHistoryContext(
    val images: List<HistoryImage>,
    val current: HistoryImage,
    val required: Set<MediaImageVersionId>,
    val branchLabels: List<String>,
    val editRecords: List<HistoryImage>,
    private val allowBaseChange: Boolean,
) {
    @Serializable
    private data class Reply(val choices: List<Choice>)

    @Serializable
    private data class Choice(val base: String, val references: List<String>, val instruction: String)

    fun validate(reply: String): ImageHistoryDecision {
        val byLabel = images.associateBy { it.label }
        fun image(label: String): MediaImageVersionId =
            byLabel[label]?.id ?: error("历史理解返回了未提供的图片编号，请手动选择参考图")
        val body = reply.trim().removeSurrounding("```json", "```").removeSurrounding("```", "```").trim()
        val choices = Json.decodeFromString<Reply>(body).choices
        require(choices.isNotEmpty()) { "没有找到符合描述的版本，请手动选择参考图" }
        val edits = choices.map { choice ->
            val base = image(choice.base)
            require(base == current.id || allowBaseChange) { "历史理解更换了所选底图，请重试或手动选择参考图" }
            val refs = choice.references.map(::image)
            require(refs.size <= 15 && base !in refs && refs.size == refs.distinct().size) { "历史理解返回了无效的参考图" }
            require((refs + base).containsAll(required)) { "历史理解与当前分支的版本关系不符，请重试或手动选择" }
            require(choice.instruction.isNotBlank()) { "历史理解没有返回修改指令" }
            ImageHistoryDecision.Edit(base, refs, choice.instruction.trim())
        }.distinct()
        return edits.singleOrNull() ?: ImageHistoryDecision.Choose(edits)
    }
}

internal fun imageHistoryContext(
    records: List<MediaCreationRecord>,
    currentId: MediaImageVersionId,
    prompt: String,
): ImageHistoryContext {
    val selected = records.singleOrNull { it.id == currentId.recordId } ?: error("所选图片记录已不存在")
    val recordsHere = records.filter { it.sessionId == selected.sessionId && it.status == MediaCreationStatus.SUCCEEDED }
    // An edit uploads the original bytes; GIF and SVG outputs cannot be submitted as static reference files.
    val versions = recordsHere.flatMap { record ->
        record.outputs.mapIndexedNotNull { index, output ->
            if (output.supportsPlatformEditing) MediaImageVersionId(record.id, index) to record else null
        }
    }.toMap()
    require(currentId in versions) { "所选底图不支持历史编辑" }

    val lineage = mutableSetOf<MediaImageVersionId>()
    var id: MediaImageVersionId? = currentId
    while (id != null && id in versions) {
        check(lineage.add(id)) { "本地图片历史存在循环，无法解释版本关系" }
        id = versions.getValue(id).parentVersion
    }
    val branch = lineage.reversed()
    val labels = branch.mapIndexed { index, version -> version to "v${index + 1}" }.toMap()
    fun ancestor(rounds: Int) = branch.getOrNull(branch.lastIndex - rounds)
        ?: error("当前分支没有向前 ${rounds} 轮的版本，请手动选择参考图")
    val required = buildSet {
        for (match in relativeRounds.findAll(prompt)) {
            add(ancestor(roundNumber(match.groupValues.drop(1).first(String::isNotEmpty))))
        }
        for (match in precedingVersion.findAll(prompt)) {
            add(ancestor(if (match.value.startsWith("上上")) 2 else 1))
        }
        for (match in numberedVersion.findAll(prompt)) {
            require(id == null) { "当前分支缺少早期记录，无法确认绝对版本号，请手动选择参考图" }
            val number = roundNumber(match.groupValues.drop(1).first(String::isNotEmpty))
            add(branch.getOrNull(number - 1) ?: error("当前分支没有第 ${number} 版，请手动选择参考图"))
        }
    }

    // Numeric references are deterministic and need only the current and requested pictures.
    // Descriptive references can also point to another result of the same generation, or a side branch.
    val otherDescription = prompt.replace(relativeRounds, "").replace(numberedVersion, "").replace(precedingVersion, "")
    val candidates = if (required.isNotEmpty() && !historyCue.containsMatchIn(otherDescription)) {
        linkedSetOf(currentId) + required
    } else {
        val relatedNodes = branch.mapTo(mutableSetOf()) { versions.getValue(it).nodeId }
        // Traverse descendants without inferring any parent from chronology or shared node membership.
        val children = recordsHere.groupBy { it.parentVersion?.recordId }
        val queue = ArrayDeque(recordsHere.filter { it.nodeId in relatedNodes }.map { it.id })
        val relatedRecords = mutableSetOf<Uuid>()
        while (queue.isNotEmpty()) {
            val recordId = queue.removeFirst()
            if (!relatedRecords.add(recordId)) continue
            children[recordId].orEmpty().forEach { queue.add(it.id) }
        }
        versions.keys.filter { it.recordId in relatedRecords }
    }
    val images = candidates.mapIndexed { index, key ->
        HistoryImage(key, versions.getValue(key), labels[key] ?: "s${index + 1}")
    }
    // Include intervening edit descriptions even when a numeric request only needs two thumbnails.
    val firstRelevant = if (required.isEmpty()) 0 else branch.indexOfFirst { it in required }
    val editRecords = branch.drop(firstRelevant).map { key -> HistoryImage(key, versions.getValue(key), labels.getValue(key)) }
    return ImageHistoryContext(
        images = images,
        current = images.single { it.id == currentId },
        required = required,
        branchLabels = branch.map(labels::getValue),
        editRecords = editRecords,
        allowBaseChange = explicitBase.containsMatchIn(prompt),
    )
}
