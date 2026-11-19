package me.rerere.rikkahub.ui.pages.mediacreation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.ai.imagehistory.ImageHistoryDecision
import me.rerere.rikkahub.data.ai.imagehistory.needsImageHistory
import me.rerere.rikkahub.data.ai.imagehistory.resolveImageHistory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.supportsPlatformEditing
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationOutput
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaCreationStatus
import me.rerere.rikkahub.data.model.MediaImageVersionId
import me.rerere.rikkahub.data.model.PlatformImageEditHistory
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.service.MediaCreationSubmission
import java.io.File
import kotlin.uuid.Uuid

internal data class PlatformEditTarget(
    val providerId: Uuid,
    val modelId: String,
    val base: File,
    val version: MediaImageVersionId?,
    val params: MediaCreationParams,
    val prompt: String = "",
    val otherInputs: List<MediaCreationAsset> = emptyList(),
    val sourceDraft: MediaCreationDraft? = null,
)

/** Local files for the selected session. IDs returned by the planner are resolved a second time here. */
internal class PlatformImageWorkflow(
    private val sessionId: Uuid,
    private val repository: MediaCreationRepository,
    private val settings: SettingsStore,
    private val chatProviders: ProviderManager,
    private val service: MediaCreationService,
    private val submitted: suspend (MediaCreationRecord) -> Unit,
) {
    val draftDir get() = repository.draftDir(sessionId)

    fun target(record: MediaCreationRecord, output: MediaCreationOutput): PlatformEditTarget {
        val index = record.outputs.indexOf(output)
        require(index >= 0 && record.sessionId == sessionId)
        return PlatformEditTarget(
            record.providerId, record.modelId, repository.resolve(output.path), MediaImageVersionId(record.id, index),
            record.params.copy(count = null, imageEdit = null, imageResult = null),
        )
    }

    fun target(
        selection: MediaCreationModelSelection,
        draft: MediaCreationDraft,
        asset: MediaCreationAsset,
    ) = PlatformEditTarget(
        selection.provider.id, selection.model.modelId, repository.resolve(asset.path), asset.version,
        draft.params.copy(imageEdit = null, imageResult = null), draft.prompt, draft.assets - asset, sourceDraft = draft,
    )

    suspend fun composerBases(draft: MediaCreationDraft): List<MediaCreationAsset> {
        if (draft.assets.isNotEmpty()) return listOf(draft.assets.first())
        // Respect the version currently displayed in each timeline item, including a manually selected older branch.
        return repository.getSelectedRecordsOfSession(sessionId).asReversed().flatMap { record ->
            record.outputs.mapIndexedNotNull { index, output ->
                if (record.status == MediaCreationStatus.SUCCEEDED && output.supportsPlatformEditing) {
                    MediaCreationAsset(output.path, version = MediaImageVersionId(record.id, index))
                } else null
            }
        }
    }

    suspend fun historyFiles() = withContext(Dispatchers.IO) {
        repository.getRecordsOfSession(sessionId).sortedByDescending { it.createAt }.flatMap { record ->
            record.outputs.mapIndexedNotNull { index, output ->
                if (record.status == MediaCreationStatus.SUCCEEDED && output.supportsPlatformEditing) {
                    MediaImageVersionId(record.id, index) to repository.resolve(output.path)
                } else null
            }
        }.toMap()
    }

    suspend fun submit(
        target: PlatformEditTarget,
        edit: PlatformImageEdit,
        choose: suspend (List<ImageHistoryDecision.Edit>) -> ImageHistoryDecision.Edit,
    ) {
        val config = settings.awaitLoaded()
        val platform = config.mediaGenerationProviders.filterIsInstance<MediaGenerationProviderSetting.OpenAIPlatform>()
            .singleOrNull { it.id == target.providerId } ?: error("OpenAI Platform 图像提供商已删除")
        val model = platform.models.firstOrNull { it.modelId == target.modelId }
            ?: error("提供商中已没有这个图像模型，请重新添加模型")
        val records = repository.getRecordsOfSession(sessionId)
        val selected = target.version
        val useHistory = edit.references.isEmpty() && (edit.forceHistory || needsImageHistory(edit.prompt))
        val decision = if (useHistory) {
            requireNotNull(selected) { "这张底图尚无本地版本历史，请手动选择参考图" }
            when (val plan = resolveImageHistory(
                chatProviders, config.providers, platform.historyModelId, records, selected, edit.prompt, repository::resolve,
            )) {
                is ImageHistoryDecision.Edit -> plan
                is ImageHistoryDecision.Choose -> choose(plan.options).also { require(it in plan.options) }
            }
        } else null

        // A local deletion or version change while a choice dialog was open must not redirect an edit.
        val available = historyFiles()
        fun original(id: MediaImageVersionId) = available[id] ?: error("选中的历史原图已不存在")
        val baseId = decision?.base ?: selected
        val base = if (baseId != null && baseId != selected) {
            require(edit.mask == null && edit.markup == null) { "图层只适用于当前底图，请先打开目标版本再涂选" }
            original(baseId)
        } else {
            if (baseId != null) original(baseId)
            edit.base
        }
        val versionsByFile = available.entries.associate { it.value to it.key } +
            target.otherInputs.mapNotNull { asset -> asset.version?.let { repository.resolve(asset.path) to it } }
        val references = decision?.references?.map(::original) ?: edit.references
        val assets = (listOf(base) + references).mapIndexed { index, file ->
            MediaCreationAsset(repository.relativePath(file), version = if (index == 0) baseId else versionsByFile[file])
        }
        val history = PlatformImageEditHistory(
            parent = baseId,
            references = assets.drop(1).mapNotNull { it.version },
            originalBasePath = if (base == edit.base && base != target.base) repository.relativePath(target.base) else null,
            maskPath = edit.mask?.let(repository::relativePath),
            markupPath = edit.markup?.let(repository::relativePath),
            originalInstruction = edit.prompt.takeIf { decision != null },
        )
        val baseRecord = baseId?.let { id -> records.firstOrNull { it.id == id.recordId } }
        val submission = MediaCreationSubmission(
            sessionId = sessionId,
            nodeId = baseRecord?.nodeId,
            provider = platform,
            model = model,
            prompt = decision?.instruction ?: edit.prompt,
            params = edit.params.copy(imageEdit = history),
            assets = assets,
        )
        currentCoroutineContext().ensureActive()
        // The editor owns temporary layers until submit has taken its durable snapshot.
        val record = withContext(NonCancellable) { service.submit(submission) }
        submitted(record)
    }
}
