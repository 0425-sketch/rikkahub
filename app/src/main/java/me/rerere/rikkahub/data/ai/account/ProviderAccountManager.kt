package me.rerere.rikkahub.data.ai.account

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.ChatGPTPendingRefresh
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.await
import me.rerere.oauth.CustomTabsOAuthAuthorizationLauncher
import me.rerere.oauth.OAuthHttpClient
import me.rerere.oauth.OAuthLoopbackCallbackServer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val TAG = "ProviderAccountManager"
private val HOST_ID_LOCK = Any()

// Refresh shortly before access-token expiry.
private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60_000L
private val AUTHORIZATION_TIMEOUT = 5.minutes

sealed interface AccountLoginStatus {
    data object Authorizing : AccountLoginStatus

    /** 设备码登录：用户在浏览器里打开 [verificationUrl] 确认 [userCode] */
    data class DeviceCode(val userCode: String, val verificationUrl: String) : AccountLoginStatus

    data class Failed(val message: String) : AccountLoginStatus
}

/**
 * 管理账号型提供商（[ProviderSetting.Account]）的登录、token 刷新和凭据持久化。
 *
 * 凭据只由这里写回设置，界面编辑提供商时应保留设置里的最新凭据，见 [preserveCredential]。
 */
class ProviderAccountManager internal constructor(
    private val settingsStore: SettingsStore,
    private val appScope: CoroutineScope,
    private val client: OkHttpClient,
) : AccountCredentialProvider {
    // 走应用的 OkHttpClient，授权和刷新请求也能用上设置里的代理
    private val privateClient = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).apply {
        interceptors().removeAll { it.javaClass.simpleName in setOf("HttpLoggingInterceptor", "RequestLoggingInterceptor", "SafeHttpLoggingInterceptor") }
        networkInterceptors().removeAll { it.javaClass.simpleName in setOf("HttpLoggingInterceptor", "RequestLoggingInterceptor", "SafeHttpLoggingInterceptor") }
    }.build()
    private val oauthClient = OAuthHttpClient(privateClient)
    private val idTokenVerifier = ChatGPTIdTokenVerifier(privateClient)
    private val generations = ConcurrentHashMap<Uuid, AtomicLong>()
    private val signingOut = ConcurrentHashMap.newKeySet<Uuid>()
    private val chatGPTCallbackServer = OAuthLoopbackCallbackServer(
        port = ChatGPTAuth.CALLBACK_PORT,
        callbackPath = ChatGPTAuth.CALLBACK_PATH,
        redirectHost = ChatGPTAuth.CALLBACK_HOST,
    )
    private val loginJobs = ConcurrentHashMap<Uuid, Job>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    private val _loginStatus = MutableStateFlow<Map<Uuid, AccountLoginStatus>>(emptyMap())
    val loginStatus: StateFlow<Map<Uuid, AccountLoginStatus>> = _loginStatus.asStateFlow()

    fun startLogin(providerId: Uuid, context: Context, requestPlanConsent: Boolean = false) {
        launchLogin(providerId) { provider, generation ->
            when (provider) {
                is ProviderSetting.ChatGPT -> loginChatGPT(provider, context.applicationContext, generation, requestPlanConsent)
                is ProviderSetting.Grok -> loginGrok(providerId, context.applicationContext)
            }
        }
    }

    /** Owns the attempt lifetime; the browser/code exchange is kept separate from atomic activation. */
    internal fun launchLogin(providerId: Uuid, authorize: suspend (ProviderSetting.Account, Long) -> OAuthCredential) {
        val provider = findAccount(providerId) ?: return
        if (providerId in signingOut) return
        cancelLogin(providerId)
        val generation = generation(providerId)
        setStatus(providerId, AccountLoginStatus.Authorizing)
        val job = appScope.launch {
            try {
                val credential = authorize(provider, generation)
                ensureActive()
                settingsStore.update { old ->
                    if (generation(providerId) != generation || providerId in signingOut) old
                    else old.copy(providers = old.providers.map {
                        if (it.id != providerId || it !is ProviderSetting.Account) it
                        else if (it is ProviderSetting.ChatGPT) it.copy(
                            credential = credential, chatGPTRegistration = ChatGPTAuth.registration(credential)
                        ) else it.withCredential(credential)
                    })
                }
                if (generation(providerId) == generation) setStatus(providerId, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // OAuth failures may contain token responses or authorization URLs: never log the exception.
                if (generation(providerId) == generation) setStatus(providerId, AccountLoginStatus.Failed(
                    when (e) {
                        is OAuthHttpClient.OAuthHttpException -> if (e.error == "invalid_grant")
                            "授权码已失效，请重新登录；已保留本次注册。" else e.message ?: "ChatGPT 授权失败"
                        is IllegalArgumentException, is IllegalStateException -> e.message ?: "账号验证失败，请重试"
                        else -> "无法完成登录，请检查网络后重试；原账号保持不变。"
                    }
                ))
            }
        }
        loginJobs[providerId] = job
        job.invokeOnCompletion { loginJobs.remove(providerId, job) }
    }

    private fun generation(providerId: Uuid): Long = generations.computeIfAbsent(providerId) { AtomicLong() }.get()

    fun cancelLogin(providerId: Uuid) {
        generations.computeIfAbsent(providerId) { AtomicLong() }.incrementAndGet()
        loginJobs.remove(providerId)?.cancel()
        setStatus(providerId, null)
    }

    suspend fun logout(providerId: Uuid) = withContext(NonCancellable + Dispatchers.IO) {
        if (!signingOut.add(providerId)) return@withContext
        cancelLogin(providerId)
        cancelChatGPTRequests(findAccount(providerId))
        try {
            refreshLocks.computeIfAbsent(providerId) { Mutex() }.withLock {
                // Wait for an in-flight rotation, then revoke its newest token before clearing locally.
                val account = findAccount(providerId)
                val credential = account?.credential
                cancelChatGPTRequests(account)
                var remoteConfirmed = true
                if (account is ProviderSetting.ChatGPT && credential?.isOfficialChatGPT == true &&
                    (credential.pendingRefresh?.refreshToken ?: credential.refreshToken) != null) {
                    remoteConfirmed = revokeChatGPT(credential.copy(
                        refreshToken = credential.pendingRefresh?.refreshToken ?: credential.refreshToken,
                    ))
                }
                persistCredential(providerId, null)
                if (!remoteConfirmed) setStatus(providerId, AccountLoginStatus.Failed(
                    "已在本机退出，但未能确认远端撤销。请在 ChatGPT 设置中断开此应用。"
                ))
            }
        } finally {
            signingOut.remove(providerId)
        }
    }

    private fun cancelChatGPTRequests(account: ProviderSetting.Account?) {
        if (account !is ProviderSetting.ChatGPT) return
        val token = account.credential?.accessToken ?: return
        (client.dispatcher.queuedCalls() + client.dispatcher.runningCalls()).forEach { call ->
            if (call.request().url.host == "api.openai.com" && call.request().header("Authorization") == "Bearer $token") {
                call.cancel()
            }
        }
    }

    private suspend fun revokeChatGPT(credential: OAuthCredential): Boolean {
        val revokeClient = privateClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        val revokeOAuth = OAuthHttpClient(revokeClient)
        repeat(3) { attempt ->
            try {
                val discovery = Request.Builder().url(ChatGPTAuth.DISCOVERY_ENDPOINT).build()
                val endpoint = revokeClient.newCall(discovery).await().use { response ->
                    check(response.isSuccessful) { "Discovery unavailable" }
                    val metadata = JsonInstant.parseToJsonElement(response.body.string()).jsonObject
                    check(metadata.claimString("issuer") == ChatGPTAuth.ISSUER)
                    metadata.claimString("revocation_endpoint") ?: error("No revocation endpoint")
                }
                // Discovery is trusted only for same-origin HTTPS endpoints.
                val parsed = endpoint.toHttpUrlOrNull()
                check(parsed?.scheme == "https" && parsed.host == "auth.openai.com" && parsed.port == 443)
                revokeOAuth.revokeToken(endpoint, credential.clientId!!, credential.refreshToken!!)
                return true
            } catch (e: Exception) {
                if (e is OAuthHttpClient.OAuthHttpException && e.status < 500) return false
                if (attempt < 2) delay(500L * (1L shl attempt))
            }
        }
        return false
    }

    /**
     * 需要刷新时在 [appScope] 里执行，调用方被取消（停止生成、关掉额度界面）时打断不了已经发出的刷新：
     * 服务端一收到请求就会轮换 refresh token，新的没存下来就只能重新登录。
     */
    override suspend fun getCredential(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
    ): OAuthCredential {
        check(setting.id !in signingOut) { "正在退出账号" }
        val current = findAccount(setting.id) ?: error("账号配置已移除")
        if (current is ProviderSetting.ChatGPT) {
            check(current.credential?.isOfficialChatGPT == true) { "此 ChatGPT 连接需要重新授权官方登录" }
        }
        // 绝大多数请求的 token 都还有效，直接返回，不用切线程排队拿锁
        current.credential
            ?.takeUnless { it.needsRefresh(rejectedAccessToken) }
            ?.let { return it }
        return appScope.async(Dispatchers.IO) {
            refreshIfNeeded(setting, rejectedAccessToken)
        }.await()
    }

    /**
     * 按提供商串行刷新。拿到锁后重新读设置：并发请求可能已经刷新过，refresh token 只能用一次。
     */
    private suspend fun refreshIfNeeded(
        setting: ProviderSetting.Account,
        rejectedAccessToken: String?,
        allowExpiredPendingRetry: Boolean = true,
    ): OAuthCredential {
        val lock = refreshLocks.computeIfAbsent(setting.id) { Mutex() }
        return lock.withLock {
            check(setting.id !in signingOut) { "正在退出账号" }
            val latest = findAccount(setting.id) ?: error("账号配置已移除")
            val credential = latest.credential ?: error("${latest.name}: not signed in")
            if (!credential.needsRefresh(rejectedAccessToken)) return@withLock credential

            val refreshToken = credential.refreshToken
            if (refreshToken == null && credential.pendingRefresh == null) {
                error("${latest.name}: session expired, please sign in again")
            }
            var unverifiedRefresh = credential.pendingRefresh
            var successfulChatGPTTokenResponse = unverifiedRefresh != null
            val refreshed = try {
                when (latest) {
                    is ProviderSetting.ChatGPT -> {
                        check(credential.isOfficialChatGPT) { "此 ChatGPT 连接需要重新授权官方登录" }
                        val clientId = credential.clientId!!
                        val pending = unverifiedRefresh
                        val token = if (pending != null) OAuthHttpClient.TokenResponse(
                            pending.accessToken, pending.tokenType, pending.expiresIn,
                            pending.refreshToken, pending.idToken, pending.scope,
                        ) else oauthClient.refreshToken(ChatGPTAuth.refreshRequest(clientId, refreshToken!!))
                        successfulChatGPTTokenResponse = true
                        unverifiedRefresh = pending ?: ChatGPTPendingRefresh(
                            token.accessToken, token.refreshToken, token.idToken, token.tokenType,
                            token.expiresIn, token.scope, System.currentTimeMillis(),
                        )
                        val identity = token.idToken?.let {
                            idTokenVerifier.verify(it, clientId, nonce = null, expectedSubject = credential.subject,
                                expectedNonce = credential.idTokenNonce)
                        } ?: ChatGPTVerifiedIdentity(
                            credential.issuer!!, credential.subject!!, credential.email, credential.accountId, credential.plan,
                        )
                        ChatGPTAuth.toCredential(token, clientId, identity, credential, now = unverifiedRefresh!!.receivedAt)
                    }

                    is ProviderSetting.Grok -> GrokAuth.toCredential(
                        token = oauthClient.refreshToken(GrokAuth.refreshRequest(refreshToken!!)),
                        previous = credential,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (latest is ProviderSetting.ChatGPT && e is ChatGPTSigningKeysUnavailableException && unverifiedRefresh != null) {
                    // The refresh token may already have rotated. Persist the response without trusting it,
                    // then resume validation (not another token exchange) after restart/network recovery.
                    replaceCredential(latest.id, credential, credential.copy(pendingRefresh = unverifiedRefresh))
                    error("OpenAI 签名密钥暂时不可用；已安全保存待验证的刷新结果，请稍后重试。")
                }
                if (latest is ProviderSetting.ChatGPT && (successfulChatGPTTokenResponse ||
                    e is OAuthHttpClient.TokenProtocolException ||
                    (e is OAuthHttpClient.OAuthHttpException && e.error in ChatGPTAuth.TERMINAL_REFRESH_ERRORS))) {
                    replaceCredential(latest.id, credential, null)
                    error("ChatGPT 会话已失效，请重新登录授权")
                }
                if (e is OAuthHttpClient.OAuthHttpException && e.error == "invalid_client") {
                    error("ChatGPT 客户端注册无效，请检查连接配置")
                }
                // A temporary network error never erases the renewable session.
                error("账号暂时无法刷新，请检查网络后重试；已保留登录信息")
            }
            // 刷新期间用户可能退出或换了账号，这时结果作废，不能把旧会话写回去
            if (!replaceCredential(latest.id, expected = credential, credential = refreshed)) {
                error("${latest.name}: sign-in changed during refresh, please try again")
            }
            check(setting.id !in signingOut) { "正在退出账号" }
            refreshed
        }.let { refreshed ->
            // A persisted pending response can be older than the access-token lifetime. Validate it first,
            // then use its replacement refresh token once, after releasing the per-account lock.
            if (refreshed.expiresAt > 0 && refreshed.expiresAt <= System.currentTimeMillis()) {
                check(allowExpiredPendingRetry) { "ChatGPT 返回的令牌已过期，请稍后重试" }
                refreshIfNeeded(setting, null, allowExpiredPendingRetry = false)
            } else refreshed
        }
    }

    private suspend fun loginChatGPT(
        provider: ProviderSetting.ChatGPT,
        context: Context,
        generation: Long,
        requestPlanConsent: Boolean,
    ): OAuthCredential = withContext(Dispatchers.IO) {
        val registration = provider.chatGPTRegistration
            ?: provider.credential?.takeIf { it.isOfficialChatGPT }?.let(ChatGPTAuth::registration)
        val pkce = oauthClient.generatePkce()
        val state = oauthClient.generateState()
        val nonce = oauthClient.generateState()
        val session = chatGPTCallbackServer.openSession(context, state)
        try {
            val authorizationUrl = oauthClient.buildAuthorizationUrl(ChatGPTAuth.authorizationRequest(
                pkce, state, nonce, session.redirectUri, installationHostId(context),
                registration, provider.credential, requestPlanConsent,
            ))
            withContext(Dispatchers.Main) {
                CustomTabsOAuthAuthorizationLauncher.launch(context, authorizationUrl)
            }
            val callback = session.awaitCallback(AUTHORIZATION_TIMEOUT) ?: error("Authorization timed out")
            val clientId = ChatGPTAuth.callbackClientId(callback, state, registration)
            if (registration == null) {
                // Retain the issued ID even if this one-time authorization code later expires.
                // No identity or active credential is changed until ID-token verification succeeds.
                settingsStore.update { old -> old.copy(providers = old.providers.map {
                    if (it is ProviderSetting.ChatGPT && it.id == provider.id &&
                        generation(provider.id) == generation && it.chatGPTRegistration == null) {
                        it.copy(chatGPTRegistration = ChatGPTRegistration(clientId))
                    } else it
                }) }
            }
            val token = oauthClient.exchangeAuthorizationCode(
                ChatGPTAuth.codeRequest(clientId, callback.code!!, pkce.verifier, session.redirectUri)
            )
            val identity = idTokenVerifier.verify(
                token.idToken ?: error("ChatGPT 没有返回身份令牌"), clientId, nonce, registration?.subject,
            )
            // A new authorization must use this response's grants, never the previous session's scope.
            ChatGPTAuth.toCredential(token, clientId, identity, nonce = nonce)
        } finally {
            withContext(NonCancellable) { session.close() }
        }
    }

    private fun installationHostId(context: Context): String = synchronized(HOST_ID_LOCK) {
        val file = File(context.noBackupFilesDir, "chatgpt-host-id")
        if (file.isFile) {
            file.readText().trim().takeIf { it.startsWith("urn:uuid:") }?.let { return@synchronized it }
        }
        val hostId = "urn:uuid:${Uuid.random()}"
        val temporary = File(context.noBackupFilesDir, "chatgpt-host-id.tmp")
        temporary.writeText(hostId)
        check(temporary.renameTo(file)) { "Cannot persist ChatGPT installation identity" }
        hostId
    }

    // 登录由 appScope（主线程）发起，网络请求和读响应体都放到 IO
    private suspend fun loginGrok(providerId: Uuid, context: Context): OAuthCredential =
        withContext(Dispatchers.IO) {
            val authorization =
                oauthClient.requestDeviceAuthorization(GrokAuth.deviceAuthorizationRequest())
            // 带 user_code 的地址打开后不用手动输入
            val url = authorization.verificationUriComplete ?: authorization.verificationUri
            require(url.startsWith("https://")) { "Invalid verification URL: $url" }
            setStatus(providerId, AccountLoginStatus.DeviceCode(authorization.userCode, url))
            withContext(Dispatchers.Main) {
                CustomTabsOAuthAuthorizationLauncher.launch(context, url)
            }
            val token = oauthClient.awaitDeviceToken(
                GrokAuth.deviceTokenRequest(authorization.deviceCode),
                authorization
            )
            val credential = GrokAuth.toCredential(token, previous = null)
            if (credential.email != null) credential else credential.copy(email = fetchGrokEmail(credential))
        }

    // token 里没有邮箱时查 userinfo，只用于界面展示，查不到不影响登录
    private suspend fun fetchGrokEmail(credential: OAuthCredential): String? = runCatching {
        val request = Request.Builder()
            .url(GrokAuth.USERINFO_ENDPOINT)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) return@use null
            JsonInstant.parseToJsonElement(response.body.string()).jsonObject.claimString("email")
        }
    }.onFailure { Log.w(TAG, "Failed to fetch Grok userinfo", it) }.getOrNull()

    private suspend fun persistCredential(providerId: Uuid, credential: OAuthCredential?) {
        settingsStore.update { old ->
            old.copy(
                providers = old.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.Account) {
                        provider.withCredential(credential)
                    } else {
                        provider
                    }
                }
            )
        }
    }

    /** 设置里的凭据仍是 [expected] 时才替换，返回是否替换了 */
    private suspend fun replaceCredential(
        providerId: Uuid,
        expected: OAuthCredential,
        credential: OAuthCredential?,
    ): Boolean {
        var replaced = false
        settingsStore.update { old ->
            old.copy(
                providers = old.providers.map { provider ->
                    if (provider.id == providerId && provider is ProviderSetting.Account &&
                        provider.credential == expected) {
                        replaced = true
                        provider.withCredential(credential)
                    } else {
                        provider
                    }
                }
            )
        }
        return replaced
    }

    // 刚被服务端拒绝，或者快过期了
    private fun OAuthCredential.needsRefresh(rejectedAccessToken: String?): Boolean =
        pendingRefresh != null || accessToken == rejectedAccessToken ||
            (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS)

    private fun findAccount(providerId: Uuid): ProviderSetting.Account? =
        settingsStore.settingsFlow.value.providers
            .find { it.id == providerId } as? ProviderSetting.Account

    private fun setStatus(providerId: Uuid, status: AccountLoginStatus?) {
        _loginStatus.update { if (status == null) it - providerId else it + (providerId to status) }
    }
}

/**
 * 界面整份写回提供商时用：凭据只能由 [ProviderAccountManager] 修改，
 * 编辑期间后台可能已经刷新过 token，旧凭据写回会把轮换后的 refresh token 覆盖掉。
 */
fun ProviderSetting.preserveCredential(latest: ProviderSetting): ProviderSetting =
    if (this is ProviderSetting.ChatGPT && latest is ProviderSetting.ChatGPT) {
        copy(credential = latest.credential, chatGPTRegistration = latest.chatGPTRegistration)
    } else if (this is ProviderSetting.Account && latest is ProviderSetting.Account) {
        withCredential(latest.credential)
    } else {
        this
    }
