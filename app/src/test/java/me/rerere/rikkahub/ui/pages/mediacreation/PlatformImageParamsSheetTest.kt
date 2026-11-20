package me.rerere.rikkahub.ui.pages.mediacreation

import me.rerere.mediagen.provider.providers.openaiplatform.PlatformImageOptions
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.capabilities
import me.rerere.rikkahub.data.model.MediaCreationAsset
import me.rerere.rikkahub.data.model.MediaCreationDraft
import me.rerere.rikkahub.data.model.MediaCreationParams
import org.junit.Assert.assertEquals
import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlatformImageParamsSheetTest {
    private val model = "gpt-image-2.6-flare"

    @Test
    fun choosingMultipleImagesClearsStreamingAndPreviewsUntilEnabledAgain() {
        val options = PlatformImageOptions(outputFormat = "webp", quality = "xhigh", stream = true, partialImages = 3)
        val single = MediaCreationParams(count = 1, resolution = "1536x1024", platformOptions = options)
        val batch = single.selectPlatformCount(4)
        val synchronous = options.copy(stream = null, partialImages = null)

        assertEquals(single.copy(count = 4, platformOptions = synchronous), batch)
        assertEquals(single.copy(platformOptions = synchronous), batch.selectPlatformCount(1))
        assertEquals(single.copy(count = null, platformOptions = synchronous), batch.selectPlatformCount(null))
        for (editing in listOf(false, true)) {
            assertNull(platformParameterError(model, editing, batch))
        }
    }

    @Test
    fun restoredStreamingBatchCanKeepEitherItsRequestedCountOrItsPreviews() {
        val saved = MediaCreationParams(
            count = 4,
            platformOptions = PlatformImageOptions(quality = "high", stream = true, partialImages = 2),
        )
        val keepCount = saved.selectPlatformCount(saved.count)
        val keepPreviews = saved.selectPlatformCount(1)

        assertEquals(saved.copy(platformOptions = PlatformImageOptions(quality = "high")), keepCount)
        assertEquals(saved.copy(count = 1), keepPreviews)
        for (editing in listOf(false, true)) {
            assertNotNull(platformParameterError(model, editing, saved))
            assertNull(platformParameterError(model, editing, keepCount))
            assertNull(platformParameterError(model, editing, keepPreviews))
        }
    }

    @Test
    fun composerRoutesHistoryBeforeSubmittingAndPreservesExplicitReferences() {
        val platform = MediaGenerationProviderSetting.OpenAIPlatform().capabilities(MediaKind.IMAGE)!!
        val compatible = MediaGenerationProviderSetting.OpenAI().capabilities(MediaKind.IMAGE)!!
        val draft = MediaCreationDraft(prompt = "把配色改回三轮前")
        val base = MediaCreationAsset("base.png")
        val ref = MediaCreationAsset("ref.png")
        assertTrue(draft.needsPlatformHistory(platform))
        assertTrue(draft.copy(assets = listOf(base)).needsPlatformHistory(platform))
        assertFalse(draft.copy(assets = listOf(base, ref)).needsPlatformHistory(platform))
        assertFalse(draft.copy(editingNodeId = Uuid.random()).needsPlatformHistory(platform))
        assertFalse(draft.copy(prompt = "蓝色背景的小猫").needsPlatformHistory(platform))
        assertFalse(draft.needsPlatformHistory(compatible))
    }

    @Test
    fun choosingSvgFromStreamingWebpPreservesSharedSettings() {
        val webp = MediaCreationParams(
            count = 4,
            resolution = "1536x1024",
            platformOptions = PlatformImageOptions(
                quality = "xhigh", complexity = "low", outputFormat = "webp",
                outputCompression = 80, stream = true, partialImages = 3, background = "transparent",
            ),
        )
        val svg = webp.selectPlatformFormat("svg")
        assertEquals(
            MediaCreationParams(
                resolution = webp.resolution,
                platformOptions = PlatformImageOptions(
                    quality = "xhigh", complexity = "low", outputFormat = "svg", background = "transparent",
                ),
            ),
            svg,
        )
        assertNull(platformParameterError(model, false, svg))
        assertNotNull(platformParameterError(model, true, svg))
    }

    @Test
    fun gifSupportsStaticReferencesButHidesMaskCompressionAndTransparency() {
        val rgba = MediaCreationParams(
            count = 3, resolution = "512x512",
            platformOptions = PlatformImageOptions(outputFormat = "webp", outputCompression = 70, background = "transparent"),
        )
        val gif = rgba.selectPlatformFormat("gif")
        assertEquals(rgba.copy(platformOptions = PlatformImageOptions(outputFormat = "gif")), gif)
        assertNull(platformParameterError(model, true, gif))
        assertNotNull(platformParameterError(model, true, gif, mask = true))
    }

    @Test
    fun newFormatClearsAnimationAndAuthorFields() {
        val animation = MediaCreationParams(
            platformOptions = PlatformImageOptions(outputFormat = "gif", frameCount = 6, frameDurationMs = 100),
        )
        assertEquals(MediaCreationParams(), animation.selectPlatformFormat(null))
        val vector = animation.copy(
            platformOptions = PlatformImageOptions(outputFormat = "svg", svgModel = "author", svgReasoningEffort = "low"),
        )
        assertEquals(MediaCreationParams(), vector.selectPlatformFormat(null))
    }
}
