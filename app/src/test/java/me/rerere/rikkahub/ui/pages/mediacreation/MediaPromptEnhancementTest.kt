package me.rerere.rikkahub.ui.pages.mediacreation

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.rerere.ai.provider.ProviderManager
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.nativeRoom
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import me.rerere.rikkahub.data.model.MediaCreationRecord
import me.rerere.rikkahub.data.model.MediaImageVersionId
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.components.ai.PromptEnhancementDraft
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MediaPromptEnhancementTest {
    @Test
    fun platformDraftKeepsItsReferencesAndParamsAcrossRewriteUndoAndReuse() = runBlocking {
        Dispatchers.setMain(Dispatchers.Default.limitedParallelism(1))
        val app = RuntimeEnvironment.getApplication()
        val db = nativeRoom(app, "enhance-${Uuid.random()}.db")
        val repo = MediaCreationRepository(app, db, db.mediaCreationDao(), JsonInstant)
        val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = diskScope) {
            File(app.cacheDir, "enhance-${Uuid.random()}.preferences_pb")
        }, diskScope)
        val platform = MediaGenerationProviderSetting.OpenAIPlatform()
        val model = platform.models.first()
        store.awaitLoaded()
        store.update { it.copy(mediaGenerationProviders = listOf(platform)) }
        store.settingsFlow.first { it.mediaGenerationProviders.contains(platform) }
        val session = repo.newSession()
        val appScope = AppScope()
        val remote = RemoteFileStore(appScope, { store.settingsFlow.value.uploadS3Config }, { error("Unexpected upload") })
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected network request") }.build()
        val service = MediaCreationService(app, appScope, store, repo, MediaGenerationManager(client), remote, client)
        val vm = MediaCreationVM(session.id.toString(), app, store, repo, service, remote, appScope,
            ProviderManager(client, app) { _, _ -> error("Unexpected chat request") })
        try {
            withTimeout(15_000) {
                vm.draft.first { it.modelId == model.id }
                val source = MediaCreationRecord(
                    sessionId = session.id, providerId = platform.id, providerName = platform.name,
                    modelId = model.modelId, kind = model.kind, prompt = "沿用上一张\n只改车漆",
                    inputs = listOf(MediaCreationAsset("references/base.png", version = MediaImageVersionId(Uuid.random(), 0))),
                    params = MediaCreationParams(promptEnhancement = true, platformOptions = PlatformImageOptions(quality = "high")),
                )
                val expected = MediaCreationDraft(
                    modelId = model.id, prompt = source.prompt, assets = source.inputs,
                    params = source.params, editingNodeId = source.nodeId,
                )
                withContext(Dispatchers.Main) {
                    vm.editFrom(source)
                    val editor = PromptEnhancementDraft(vm.promptState, { vm.promptDraftEpoch }, { vm.promptEditRevision })
                    val enhanced = "沿用上一张，只修改车漆。"
                    editor.enhance { original ->
                        assertEquals(source.prompt, original)
                        enhanced
                    }
                    Snapshot.sendApplyNotifications()
                    assertEquals(expected.copy(prompt = enhanced), vm.draft.first { it.prompt == enhanced })
                    editor.undo()
                    Snapshot.sendApplyNotifications()
                    assertEquals(expected, vm.draft.first { it.prompt == source.prompt })

                    // The field and text are identical after reusing a record, but the request is obsolete.
                    editor.enhance {
                        vm.editFrom(source)
                        enhanced
                    }
                    assertEquals(source.prompt, vm.promptState.text.toString())
                    assertFalse(editor.canUndo)
                }
            }
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            appScope.coroutineContext.job.cancelAndJoin()
            diskScope.coroutineContext.job.cancelAndJoin()
            db.close()
            Dispatchers.resetMain()
        }
    }
}
