package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.Locale

object BingSearchService : SearchService<SearchServiceOptions.BingLocalOptions> {
    override val name: String = "Bing"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.bing_desc))
    }

    override fun parameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", SEARCH_QUERY_DESCRIPTION)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.BingLocalOptions): InputSchema? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            require(query.isNotBlank()) { "query must not be blank" }
            val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            val locale = Locale.getDefault()
            val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Charset", "utf-8")
                .header("Referer", "https://www.bing.com/")
                .header("Cookie", "SRCHHPGUSR=ULSR=1")
                .build()
            // Share the app's proxy, TLS configuration and timeouts with the other search services.
            // Leave Accept-Encoding to OkHttp so compressed responses are decoded before parsing.
            val result = SearchService.httpClient.newCall(request).await().use { response ->
                check(response.isSuccessful) { "Bing search failed: HTTP ${response.code}" }
                parseBingSearchResults(response.body.string(), response.request.url.toString())
            }
            Result.success(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.BingLocalOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for Bing"))
    }
}

internal fun parseBingSearchResults(html: String, baseUri: String): SearchResult {
    val doc = Jsoup.parse(html, baseUri)
    check(doc.select("#b_captcha, .b_captcha, #bnp_captcha, iframe[src*=captcha], form[action*=captcha]").isEmpty()) {
        "Bing search requires browser verification; no search results were returned to the app"
    }
    // Keep the original result selectors and content; never infer relevance from keyword matching.
    val results = doc.select("li.b_algo").mapNotNull { element ->
        val title = element.select("h2").text()
        val link = element.select("h2 > a").attr("href")
        if (title.isBlank() || link.isBlank()) return@mapNotNull null
        SearchResultItem(title = title, url = link, text = element.select(".b_caption p").text())
    }
    if (results.isEmpty()) {
        if (doc.select("#b_results .b_no").isNotEmpty()) {
            error("Bing returned no results for this query")
        }
        error("Bing returned a page without recognizable search results; the page may be blocked, incomplete, or its layout may have changed")
    }
    return SearchResult(items = results)
}
