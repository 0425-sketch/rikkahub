package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val API_URL = "https://updates.rikka-ai.com/"

private val githubHosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun HttpUrl.isGithubUpdateUrl(): Boolean =
    isHttps && port == 443 && host in githubHosts && username.isEmpty() && password.isEmpty()

class UpdateChecker(
    private val client: OkHttpClient,
    appScope: AppScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    internal val personal = BuildConfig.APPLICATION_ID == PersonalReleaseFeed.APPLICATION_ID
    private val updateClient = if (personal) client.newBuilder()
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            if (!request.url.isGithubUpdateUrl()) throw IOException("Unexpected update host")
            chain.proceed(request)
        }
        .build() else client
    private val downloadMutex = Mutex()
    private var nextCheckAt = 0L

    val updateState: StateFlow<UiState<UpdateInfo>> = flow {
        do {
            if (personal) delay(nextCheckAt - SystemClock.elapsedRealtime())
            val state = try {
                loadAvailableUpdate()?.let { UiState.Success(it) } ?: UiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e)
            }
            if (personal) {
                val interval = if (state is UiState.Error) 15.minutes else 4.hours
                nextCheckAt = SystemClock.elapsedRealtime() + interval.inWholeMilliseconds
            }
            emit(state)
        } while (personal)
    }.flowOn(Dispatchers.IO).stateIn(
        scope = appScope,
        started = if (personal) SharingStarted.WhileSubscribed() else SharingStarted.Lazily,
        initialValue = UiState.Loading,
    )

    private suspend fun loadAvailableUpdate(): UpdateInfo? {
        if (personal) {
            val release = PersonalReleaseFeed.latest(BuildConfig.VERSION_CODE.toInt(), ::readText) ?: return null
            return UpdateInfo(
                version = release.versionName,
                publishedAt = release.publishedAt,
                changelog = release.changelog,
                downloads = listOf(UpdateDownload(release.apkName, release.apkUrl, release.apkSize.fileSizeToString(), release)),
            )
        }

        val latest = json.decodeFromString<UpdateInfo>(readText(API_URL))
        return latest.takeIf { Version(it.version) > Version(BuildConfig.VERSION_NAME) }
    }

    private suspend fun readText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "RikkaHub ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            .build()
        return updateClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }

    internal suspend fun personalInstaller(context: Context, download: UpdateDownload): Intent = withContext(Dispatchers.IO) {
        check(personal)
        val release = requireNotNull(download.personalRelease)
        downloadMutex.withLock {
            preparePersonalInstall(context, release) { target ->
                val request = Request.Builder().url(release.apkUrl).build()
                updateClient.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    release.saveVerifiedBytes(response.body.byteStream(), target)
                }
            }
        }
    }

    fun downloadUpdate(context: Context, download: UpdateDownload): Boolean {
        if (personal) return false

        val result = runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(download.name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            checkNotNull(context.getSystemService(DownloadManager::class.java)).enqueue(request)
        }
        if (result.isFailure) context.openUrl(download.url)
        return result.isSuccess
    }
}

@Serializable
@ConsistentCopyVisibility
data class UpdateDownload internal constructor(
    val name: String,
    val url: String,
    val size: String,
    @Transient internal val personalRelease: PersonalReleaseUpdate? = null,
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

/**
 * 版本号值类，封装版本号字符串并提供比较功能
 *
 * 支持完整的 SemVer 规范：MAJOR.MINOR.PATCH[-prerelease][+build]
 * - 预发布版本优先级低于正式版：1.0.0-alpha < 1.0.0
 * - 预发布标识符按段逐个比较：数字按数值比较，字符串按字典序比较
 * - 预发布标识符优先级：alpha < beta < rc（通过字典序自然满足）
 * - build metadata（+号后面的部分）不影响优先级比较
 */
@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parse(): ParsedVersion {
        // 去掉 build metadata（+号后面的部分）
        val withoutBuild = value.split("+").first()
        // 分离主版本号和预发布标识符
        val hyphenIndex = withoutBuild.indexOf('-')
        val (coreStr, prereleaseStr) = if (hyphenIndex >= 0) {
            withoutBuild.substring(0, hyphenIndex) to withoutBuild.substring(hyphenIndex + 1)
        } else {
            withoutBuild to null
        }
        val core = coreStr.split(".").map { it.toIntOrNull() ?: 0 }
        val prerelease = prereleaseStr?.split(".")
        return ParsedVersion(core, prerelease)
    }

    override fun compareTo(other: Version): Int {
        val a = this.parse()
        val b = other.parse()

        // 先比较主版本号
        val maxLen = maxOf(a.core.size, b.core.size)
        for (i in 0 until maxLen) {
            val ap = if (i < a.core.size) a.core[i] else 0
            val bp = if (i < b.core.size) b.core[i] else 0
            if (ap != bp) return ap.compareTo(bp)
        }

        // 主版本号相同时比较预发布标识符
        // 有预发布标识符的版本优先级低于没有的：1.0.0-alpha < 1.0.0
        return when {
            a.prerelease == null && b.prerelease == null -> 0
            a.prerelease != null && b.prerelease == null -> -1
            a.prerelease == null && b.prerelease != null -> 1
            else -> comparePrerelease(a.prerelease!!, b.prerelease!!)
        }
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }

        private fun comparePrerelease(a: List<String>, b: List<String>): Int {
            val maxLen = maxOf(a.size, b.size)
            for (i in 0 until maxLen) {
                // 字段少的优先级更低：1.0.0-alpha < 1.0.0-alpha.1
                if (i >= a.size) return -1
                if (i >= b.size) return 1

                val aNum = a[i].toIntOrNull()
                val bNum = b[i].toIntOrNull()

                val cmp = when {
                    // 都是字：按数值比较
                    aNum != null && bNum != null -> aNum.compareTo(bNum)
                    // 数字优先级低于字符串
                    aNum != null -> -1
                    bNum != null -> 1
                    // 都是字符串：按字典序比较
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return 0
        }
    }
}

private data class ParsedVersion(
    val core: List<Int>,
    val prerelease: List<String>?,
)

// 扩展操作符函数，使比较更直观
operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))
