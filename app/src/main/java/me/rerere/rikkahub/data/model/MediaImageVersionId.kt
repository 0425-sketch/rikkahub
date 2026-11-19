package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** A single result in a generation. The record ID alone is ambiguous when n > 1. */
@Serializable
data class MediaImageVersionId(val recordId: Uuid, val outputIndex: Int)

/** Images API edits accept only static PNG, JPEG and WebP results as inputs. */
internal val MediaCreationOutput.supportsPlatformEditing: Boolean
    get() = mimeType == "image/png" || mimeType == "image/jpeg" || mimeType == "image/webp"
