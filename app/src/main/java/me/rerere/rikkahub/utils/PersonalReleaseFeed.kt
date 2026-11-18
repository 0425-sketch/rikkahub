package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

internal data class PersonalReleaseUpdate(
    val versionCode: Int,
    val versionName: String,
    val publishedAt: String,
    val changelog: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
)

internal object PersonalReleaseFeed {
    private const val REPOSITORY = "0425-sketch/rikkahub"
    private const val PAGE_SIZE = 100
    private const val CERTIFICATE_SHA256 = "882c1cc87ccf36c2ac5eb178b8d8ef13ff74a3ac8b759b41aed6e48bddb4e1fd"
    private val json = Json { ignoreUnknownKeys = true }
    private val personalTag = Regex("""custom-(\d+\.\d+\.\d+)-personal-([0-9a-f]{12})-\d+-\d+""")
    private val personalVersion = Regex("""(\d+\.\d+\.\d+)-personal\.[1-9]\d*""")
    private val versionCodeHint = Regex("""(?m)^<!-- rikkahub-personal-version-code: ([1-9]\d*) -->\r?$""")
    private val sourceSha = Regex("[0-9a-f]{40}")
    private val sha256 = Regex("[0-9a-f]{64}")

    // GitHub's /latest omits pre-releases, and publication order does not determine Android upgrade order.
    suspend fun latest(installedVersionCode: Int, readText: suspend (String) -> String): PersonalReleaseUpdate? {
        var page = 1
        var latest: PersonalReleaseUpdate? = null
        do {
            val url = "https://api.github.com/repos/$REPOSITORY/releases?per_page=$PAGE_SIZE&page=$page"
            val releases = json.decodeFromString<List<Release>>(readText(url))
            for (release in releases) {
                val threshold = latest?.versionCode ?: installedVersionCode
                val hint = versionCodeHint.findAll(release.body.orEmpty()).singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
                if (hint != null && hint <= threshold) continue
                val candidate = release.personalUpdate(readText) ?: continue
                if (candidate.versionCode > threshold) latest = candidate
            }
            page++
        } while (releases.size == PAGE_SIZE)
        return latest
    }

    private suspend fun Release.personalUpdate(readText: suspend (String) -> String): PersonalReleaseUpdate? {
        val tag = personalTag.matchEntire(tagName) ?: return null
        val (version, shortSha) = tag.destructured
        if (draft || publishedAt == null || !sourceSha.matches(target) || !target.startsWith(shortSha)) return null
        try {
            Instant.parse(publishedAt)
        } catch (_: DateTimeParseException) {
            return null
        }

        val proof = asset("build-provenance.json") ?: return null
        val text = readText(proof.url)
        val metadata = try {
            json.decodeFromString<Provenance>(text)
        } catch (_: SerializationException) {
            return null
        }
        if (metadata.applicationId != "me.rerere.rikkahub.personal" || metadata.certificateSha256 != CERTIFICATE_SHA256 ||
            metadata.tag != tagName || metadata.sourceCommit != target || metadata.versionCode <= 0 ||
            personalVersion.matchEntire(metadata.versionName)?.groupValues?.get(1) != version ||
            "arm64-v8a" !in metadata.architectures || !sha256.matches(metadata.apkSha256)
        ) return null

        val apk = asset("rikkahub-${metadata.versionName}-$shortSha-arm64.apk") ?: return null
        if (apk.size <= 0 || apk.size != metadata.apkSize || apk.digest != "sha256:${metadata.apkSha256}") return null
        return PersonalReleaseUpdate(
            metadata.versionCode, metadata.versionName, publishedAt, body.orEmpty().replace(versionCodeHint, "").trim(),
            apk.name, apk.url, apk.size,
        )
    }

    private fun Release.asset(name: String): Asset? = assets.singleOrNull {
        it.name == name && it.state == "uploaded" &&
            it.url == "https://github.com/$REPOSITORY/releases/download/$tagName/$name"
    }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tagName: String,
        @SerialName("target_commitish") val target: String = "",
        val draft: Boolean,
        @SerialName("published_at") val publishedAt: String? = null,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val state: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long,
        val digest: String? = null,
    )

    @Serializable
    private data class Provenance(
        @SerialName("application_id") val applicationId: String,
        @SerialName("certificate_sha256") val certificateSha256: String,
        @SerialName("source_commit") val sourceCommit: String,
        val tag: String,
        @SerialName("version_code") val versionCode: Int,
        @SerialName("version_name") val versionName: String,
        val architectures: List<String>,
        @SerialName("apk_sha256") val apkSha256: String,
        @SerialName("apk_size") val apkSize: Long,
    )
}
