package me.rerere.rikkahub.data.ai.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.ai.provider.ChatGPTPendingRefresh
import me.rerere.ai.provider.ChatGPTRegistration
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.util.Date
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProviderAccountManagerTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(30)
    private val original = OAuthCredential(
        accessToken = "old-access", refreshToken = "old-refresh", expiresAt = 1,
        protocolVersion = 1, clientId = "oaiapp_test", issuer = ChatGPTAuth.ISSUER,
        subject = "subject", idToken = "previous-verified-id", grantedScopes = setOf("chatgpt.tokens.use.direct"),
    )
    private fun response(request: Request, body: String, status: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Test")
        .body(body.toResponseBody("application/json".toMediaType())).build()
    private fun rotated() = """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}"""
    private suspend fun withManager(
        respond: (Request) -> Response,
        block: suspend (SettingsStore, ProviderAccountManager, ProviderSetting.ChatGPT) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) {
                File(temp.newFolder(), "settings.preferences_pb")
            }, scope)
            val provider = ProviderSetting.ChatGPT(credential = original,
                chatGPTRegistration = ChatGPTRegistration("oaiapp_test", subject = "subject"))
            store.update { it.copy(providers = listOf(provider)) }
            val client = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
            block(store, ProviderAccountManager(store, scope, client), provider)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `parallel callers serialize rotation and old provider snapshots use latest token`() = runBlocking {
        val calls = AtomicInteger()
        withManager({ request ->
            assertEquals(ChatGPTAuth.TOKEN_ENDPOINT, request.url.toString())
            val form = request.body as FormBody
            assertEquals("oaiapp_test", (0 until form.size).associate { form.name(it) to form.value(it) }["client_id"])
            assertEquals(ChatGPTAuth.RESOURCE, (0 until form.size).associate { form.name(it) to form.value(it) }["resource"])
            calls.incrementAndGet()
            response(request, rotated())
        }) { store, manager, provider ->
            val results = coroutineScope { (1..12).map { async { manager.getCredential(provider, null) } }.awaitAll() }
            assertEquals(1, calls.get())
            assertTrue(results.all { it.accessToken == "new-access" && it.refreshToken == "new-refresh" })
            assertEquals("new-access", manager.getCredential(provider, "old-access").accessToken)
            assertEquals(original.grantedScopes, (store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential!!.grantedScopes)
        }
    }

    @Test
    fun `rotation is saved even after the requesting chat is cancelled`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withManager({ request ->
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            response(request, rotated())
        }) { store, manager, provider ->
            val caller = launch { manager.getCredential(provider, null) }
            withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
            caller.cancelAndJoin()
            release.countDown()
            val saved = withTimeout(10_000) {
                store.settingsFlow.first { (it.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential?.refreshToken == "new-refresh" }
            }
            assertEquals("new-access", (saved.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential?.accessToken)
        }
    }

    @Test
    fun `refresh completing after account replacement cannot write old session back`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        withManager({ request ->
            started.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            response(request, rotated())
        }) { store, manager, provider ->
            val result = async { runCatching { manager.getCredential(provider, null) } }
            withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
            val replacement = original.copy(accessToken = "another-session", refreshToken = "another-refresh", expiresAt = Long.MAX_VALUE)
            store.update { it.copy(providers = listOf(provider.copy(credential = replacement))) }
            release.countDown()
            assertTrue(result.await().isFailure)
            assertEquals(replacement, (store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential)
        }
    }

    @Test
    fun `logout waits for rotation revokes newest token and retains registration`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var revoked: String? = null
        withManager({ request ->
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    response(request, rotated())
                }
                "/.well-known/openid-configuration" -> response(request, """{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""")
                "/api/accounts/oauth/revoke" -> {
                    val form = request.body as FormBody
                    revoked = (0 until form.size).associate { form.name(it) to form.value(it) }["token"]
                    response(request, "")
                }
                else -> error("Unexpected path")
            }
        }) { store, manager, provider ->
            val refresh = async { runCatching { manager.getCredential(provider, null) } }
            withContext(Dispatchers.IO) { assertTrue(started.await(10, TimeUnit.SECONDS)) }
            val logout = async { manager.logout(provider.id) }
            // Logout blocks new requests before waiting for the refresh lock.
            delay(100)
            release.countDown()
            logout.await()
            refresh.await()
            val saved = store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT
            assertNull(saved.credential)
            assertEquals(provider.chatGPTRegistration, saved.chatGPTRegistration)
            assertEquals("new-refresh", revoked)
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
        }
    }

    @Test
    fun `network failures preserve account but terminal refresh failures clear only tokens`() = runBlocking {
        withManager({ throw IOException("network down") }) { store, manager, provider ->
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
            assertEquals(original, (store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential)
        }
        withManager({ response(it, """{"error":"invalid_grant"}""", 400) }) { store, manager, provider ->
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
            val saved = store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT
            assertNull(saved.credential)
            assertEquals(provider.chatGPTRegistration, saved.chatGPTRegistration)
        }
    }

    @Test
    fun `invalid identity after rotation requires reauthorization without replacing registered subject`() = runBlocking {
        withManager({ response(it, """{"access_token":"rotated","refresh_token":"rotated-refresh","expires_in":3600,"id_token":"invalid-jwt"}""") }) { store, manager, provider ->
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
            val saved = store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT
            assertNull(saved.credential)
            assertEquals("subject", saved.chatGPTRegistration?.subject)
        }
    }

    @Test
    fun `legacy and deleted providers cannot trigger token requests`() = runBlocking {
        withManager({ error("Must not contact token endpoint") }) { store, manager, provider ->
            store.update { it.copy(providers = listOf(provider.copy(credential = OAuthCredential("legacy")))) }
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
            store.update { it.copy(providers = listOf(ProviderSetting.OpenAI())) }
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
        }
    }
    @Test
    fun `JWKS outage saves rotated response and restart validates it without reusing old refresh`() = runBlocking {
        val key = RSAKeyGenerator(2048).keyID("rotation-key").generate()
        val idToken = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rotation-key").build(),
            JWTClaimsSet.Builder().issuer(ChatGPTAuth.ISSUER).audience("oaiapp_test").subject("subject")
                .issueTime(Date()).expirationTime(Date(System.currentTimeMillis() + 3_600_000)).build(),
        ).apply { sign(RSASSASigner(key)) }.serialize()
        val tokenCalls = AtomicInteger()
        val jwksCalls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    tokenCalls.incrementAndGet()
                    response(request, """{"access_token":"rotated","refresh_token":"rotated-refresh","expires_in":3600,"id_token":"$idToken"}""")
                }
                "/.well-known/jwks.json" -> if (jwksCalls.incrementAndGet() == 1) response(request, "unavailable", 503)
                    else response(request, JWKSet(key.toPublicJWK()).toString())
                else -> error("Unexpected endpoint")
            }
        }.build()
        val file = File(temp.newFolder(), "settings.preferences_pb")
        val provider = ProviderSetting.ChatGPT(credential = original,
            chatGPTRegistration = ChatGPTRegistration("oaiapp_test", subject = "subject"))
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
            store.update { it.copy(providers = listOf(provider)) }
            var manager = ProviderAccountManager(store, scope, client)
            assertTrue(runCatching { manager.getCredential(provider, null) }.isFailure)
            val pending = (store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential!!
            assertEquals("rotated-refresh", pending.pendingRefresh?.refreshToken)
            assertEquals(original.subject, pending.subject)
            assertFalse(pending.hasChatGPTPlanAccess)
            assertFalse(pending.pendingRefresh.toString().contains("rotated-refresh"))
            scope.coroutineContext.job.cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file }, scope)
            store.awaitLoaded()
            manager = ProviderAccountManager(store, scope, client)
            val restored = manager.getCredential(provider, null)
            assertEquals("rotated", restored.accessToken)
            assertEquals("rotated-refresh", restored.refreshToken)
            assertNull(restored.pendingRefresh)
            assertTrue(restored.hasChatGPTPlanAccess)
            assertEquals(1, tokenCalls.get())
            assertEquals(2, jwksCalls.get())
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `logout revokes pending response token without trusting its identity`() = runBlocking {
        var revoked: String? = null
        withManager({ request ->
            if (request.url.encodedPath == "/.well-known/openid-configuration") {
                response(request, """{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""")
            } else {
                assertEquals("/api/accounts/oauth/revoke", request.url.encodedPath)
                val form = request.body as FormBody
                revoked = (0 until form.size).associate { form.name(it) to form.value(it) }["token"]
                response(request, "")
            }
        }) { store, manager, provider ->
            val pending = ChatGPTPendingRefresh("pending-access", "pending-refresh", "not-validated", expiresIn = 3600, receivedAt = System.currentTimeMillis())
            store.update { it.copy(providers = it.providers.map { p -> if (p.id == provider.id) provider.copy(credential = original.copy(pendingRefresh = pending)) else p }) }
            manager.logout(provider.id)
            assertEquals("pending-refresh", revoked)
            assertNull((store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential)
        }
    }

    @Test
    fun `short lived valid response does not cause recursive refresh loop`() = runBlocking {
        val calls = AtomicInteger()
        withManager({ request ->
            calls.incrementAndGet()
            response(request, """{"access_token":"short-lived","refresh_token":"next-refresh","expires_in":60}""")
        }) { _, manager, provider ->
            assertEquals("short-lived", manager.getCredential(provider, null).accessToken)
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun `cancelled authorization cannot activate after logout or replace a newer attempt`() = runBlocking {
        withManager({ error("No token request expected") }) { store, manager, provider ->
            suspend fun clearCredential() = store.update { it.copy(providers = it.providers.map { p -> if (p.id == provider.id) provider.copy(credential = null) else p }) }
            clearCredential()
            val began = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ended = CompletableDeferred<Unit>()
            manager.launchLogin(provider.id) { _, _ ->
                withContext(NonCancellable) {
                    began.complete(Unit)
                    release.await()
                    ended.complete(Unit)
                    original
                }
            }
            began.await()
            manager.logout(provider.id)
            release.complete(Unit)
            ended.await()
            delay(50)
            assertNull((store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential)

            val firstBegan = CompletableDeferred<Unit>()
            val finishFirst = CompletableDeferred<Unit>()
            val firstEnded = CompletableDeferred<Unit>()
            manager.launchLogin(provider.id) { _, _ ->
                withContext(NonCancellable) {
                    firstBegan.complete(Unit)
                    finishFirst.await()
                    firstEnded.complete(Unit)
                    original
                }
            }
            firstBegan.await()
            val newCredential = original.copy(accessToken = "new-login", expiresAt = Long.MAX_VALUE)
            manager.launchLogin(provider.id) { _, _ -> newCredential }
            withTimeout(10_000) { store.settingsFlow.first { settings ->
                (settings.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential?.accessToken == "new-login"
            } }
            finishFirst.complete(Unit)
            firstEnded.await()
            delay(50)
            assertEquals("new-login", (store.settingsFlow.value.providers.single { it.id == provider.id } as ProviderSetting.ChatGPT).credential?.accessToken)
            assertNull(manager.loginStatus.value[provider.id])
        }
    }

}
