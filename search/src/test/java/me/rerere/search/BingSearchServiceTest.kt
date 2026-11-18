package me.rerere.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BingSearchServiceTest {
    private lateinit var previousClient: OkHttpClient
    private var testClient: OkHttpClient? = null

    @Before
    fun saveClient() {
        previousClient = SearchService.httpClient
    }

    @After
    fun restoreClient() {
        SearchService.httpClient = previousClient
        testClient?.dispatcher?.executorService?.shutdownNow()
        testClient?.connectionPool?.evictAll()
    }

    @Test
    fun `shared client receives the complete unicode and reserved query characters`() = runBlocking {
        val query = "海滨公园新馆开放 + \"新闻\" & 本地/#?=2026"
        var captured: Request? = null
        useClient(OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            htmlResponse(chain.request(), RESULT_HTML)
        }.build())

        val result = search(query).getOrThrow()
        val request = checkNotNull(captured)
        assertEquals("www.bing.com", request.url.host)
        assertEquals("/search", request.url.encodedPath)
        assertEquals(setOf("q"), request.url.queryParameterNames)
        assertEquals(listOf(query), request.url.queryParameterValues("q"))
        // OkHttp owns transparent gzip decoding; the parser must not receive compressed bytes.
        assertEquals(null, request.header("Accept-Encoding"))
        assertEquals("https://www.bing.com/", request.header("Referer"))
        assertEquals("SRCHHPGUSR=ULSR=1", request.header("Cookie"))
        assertEquals("海滨公园 & 新闻", result.items.single().title)
        assertEquals("https://example.org/news?q=1&lang=zh", result.items.single().url)
        assertEquals("原始摘要：新馆开放。", result.items.single().text)
    }

    @Test
    fun `upstream unrelated results are preserved without fabricated relevance or fallback`() {
        val result = parseBingSearchResults(
            """<ol id="b_results"><li class="b_algo"><h2><a href="https://example.org/dictionary/hai">海 - 字典</a></h2><div class="b_caption"><p>上游原始结果</p></div></li></ol>""",
            "https://www.bing.com/search?q=海滨公园新馆开放",
        )
        assertEquals("海 - 字典", result.items.single().title)
        assertEquals("上游原始结果", result.items.single().text)
    }

    @Test
    fun `explicit empty results and unrecognized pages have distinct truthful failures`() {
        val empty = assertThrows(IllegalStateException::class.java) {
            parseBingSearchResults("<ol id='b_results'><li class='b_no'>No results</li></ol>", "https://www.bing.com/")
        }
        assertEquals("Bing returned no results for this query", empty.message)

        for (html in listOf("<html>Unexpected page</html>", "<li class='b_algo'><h2></h2></li>")) {
            val unknown = assertThrows(IllegalStateException::class.java) {
                parseBingSearchResults(html, "https://www.bing.com/")
            }
            assertTrue(unknown.message.orEmpty().contains("without recognizable search results"))
            assertFalse(unknown.message.orEmpty().contains("no results for this query"))
        }
    }

    @Test
    fun `verification page is not described as a query with no results`() {
        val error = assertThrows(IllegalStateException::class.java) {
            parseBingSearchResults("<div id='b_captcha'>Verify</div>", "https://www.bing.com/")
        }
        assertTrue(error.message.orEmpty().contains("requires browser verification"))
    }

    @Test
    fun `HTTP failure retains Bing status and does not attempt another service`() = runBlocking {
        var requests = 0
        useClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            htmlResponse(chain.request(), "Rate limited", 429)
        }.build())
        assertEquals("Bing search failed: HTTP 429", search("新闻").exceptionOrNull()?.message)
        assertEquals(1, requests)
    }

    @Test
    fun `cancelling a pending search cancels the shared HTTP call`() = runBlocking {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        useClient(OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { cancelled.countDown() }
        }).addInterceptor { chain ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            htmlResponse(chain.request(), RESULT_HTML)
        }.build())
        val job = async(Dispatchers.Default) { search("新闻") }
        try {
            assertTrue("Search did not reach the configured client", started.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue("Cancelled coroutine left the HTTP call running", cancelled.await(5, TimeUnit.SECONDS))
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

    private suspend fun search(query: String) = BingSearchService.search(
        buildJsonObject { put("query", query) },
        SearchCommonOptions(),
        SearchServiceOptions.BingLocalOptions(),
    )

    private fun useClient(client: OkHttpClient) {
        testClient = client
        SearchService.httpClient = client
    }

    private fun htmlResponse(request: Request, html: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
        .body(html.toResponseBody("text/html; charset=utf-8".toMediaType())).build()

    companion object {
        private const val RESULT_HTML = """<ol id="b_results"><li class="b_algo"><h2><a href="https://example.org/news?q=1&amp;lang=zh">海滨公园 &amp; 新闻</a></h2><div class="b_caption"><p>原始摘要：新馆开放。</p></div></li></ol>"""
    }
}
