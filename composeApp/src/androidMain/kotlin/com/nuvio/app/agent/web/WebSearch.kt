package com.nuvio.app.agent.web

import com.nuvio.app.agent.config.SearchBackend
import com.nuvio.app.agent.config.SearchSettings
import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ToolFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder

data class SearchResult(val title: String, val url: String, val snippet: String)

data class SearchResponse(val backend: SearchBackend, val results: List<SearchResult>, val answer: String? = null)

/**
 * Pluggable web search. DuckDuckGo (Lite HTML endpoint) and public SearXNG need no key; Brave,
 * Tavily and a private SearXNG use the user's key/URL. Parsers are pure functions (unit-tested
 * against recorded pages).
 */
class WebSearch(
    private val http: OkHttpClient,
    private val settings: () -> SearchSettings,
    private val secret: (SearchBackend) -> String?,
) {
    suspend fun search(query: String, maxResults: Int = 8, backendOverride: SearchBackend? = null): SearchResponse =
        withContext(Dispatchers.IO) {
            val config = settings()
            val backend = backendOverride ?: config.backend
            try {
                runBackend(backend, query, maxResults, config)
            } catch (error: Exception) {
                if (backend != SearchBackend.DuckDuckGo && config.fallbackToDuckDuckGo && backendOverride == null) {
                    runBackend(SearchBackend.DuckDuckGo, query, maxResults, config)
                } else {
                    throw ToolFailure("${backend.label} search failed: ${error.message}", "search_failed")
                }
            }
        }

    private fun runBackend(backend: SearchBackend, query: String, max: Int, config: SearchSettings): SearchResponse = when (backend) {
        SearchBackend.DuckDuckGo -> {
            val request = Request.Builder()
                .url("https://lite.duckduckgo.com/lite/")
                .header("User-Agent", BROWSER_UA)
                .post(FormBody.Builder().add("q", query).add("kl", "wt-wt").build())
                .build()
            SearchResponse(backend, parseDuckDuckGoLite(execute(request)).take(max))
        }
        SearchBackend.SearxngPublic, SearchBackend.Searxng -> {
            val base = (if (backend == SearchBackend.Searxng) config.searxngUrl else config.publicSearxngUrl)
                .trim().trimEnd('/').ifEmpty { throw ToolFailure("Set a SearXNG URL in Agent settings → Web search", "not_configured") }
            val url = "$base/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .build()
            val builder = Request.Builder().url(url).header("User-Agent", BROWSER_UA).header("Accept", "application/json")
            secret(backend)?.let { builder.header("Authorization", "Bearer $it") }
            val body = execute(builder.build())
            val results = if (body.trimStart().startsWith("{")) parseSearxngJson(body) else parseSearxngHtml(body)
            if (results.isEmpty() && !body.trimStart().startsWith("{")) {
                throw ToolFailure("SearXNG instance returned no parseable results (JSON output may be disabled)", "search_failed")
            }
            SearchResponse(backend, results.take(max))
        }
        SearchBackend.Brave -> {
            val key = secret(backend) ?: throw ToolFailure("Add a Brave Search API key in Agent settings → Web search", "not_configured")
            val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("count", max.coerceIn(1, 20).toString())
                .build()
            val request = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", key)
                .build()
            SearchResponse(backend, parseBraveJson(execute(request)).take(max))
        }
        SearchBackend.Tavily -> {
            val key = secret(backend) ?: throw ToolFailure("Add a Tavily API key in Agent settings → Web search", "not_configured")
            val payload = buildJsonObject {
                put("query", query)
                put("max_results", max.coerceIn(1, 20))
                put("include_answer", true)
            }
            val request = Request.Builder().url("https://api.tavily.com/search")
                .header("Authorization", "Bearer $key")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val (results, answer) = parseTavilyJson(execute(request))
            SearchResponse(backend, results.take(max), answer)
        }
    }

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ToolFailure("HTTP ${response.code}: ${body.take(200)}", "http_${response.code}")
            body
        }

    companion object {
        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

        fun parseDuckDuckGoLite(html: String): List<SearchResult> {
            val linkRegex = Regex("""<a[^>]+href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
            val links = linkRegex.findAll(html).toList()
            return links.mapIndexed { index, match ->
                val nextStart = links.getOrNull(index + 1)?.range?.first ?: html.length
                val section = html.substring(match.range.last, nextStart)
                SearchResult(
                    title = HtmlText.inlineText(match.groupValues[2]),
                    url = unwrapDuckDuckGoUrl(HtmlText.decodeEntities(match.groupValues[1])),
                    snippet = snippetRegex.find(section)?.groupValues?.get(1)?.let(HtmlText::inlineText).orEmpty(),
                )
            }.filter { it.url.startsWith("http") && !it.url.contains("duckduckgo.com/y.js") }
        }

        /** Classic html.duckduckgo.com markup (result__a / result__snippet). */
        fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
            val blockRegex = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>(.*?)(?=<a[^>]*class="result__a"|$)""", RegexOption.DOT_MATCHES_ALL)
            val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            return blockRegex.findAll(html).map { match ->
                SearchResult(
                    title = HtmlText.inlineText(match.groupValues[2]),
                    url = unwrapDuckDuckGoUrl(HtmlText.decodeEntities(match.groupValues[1])),
                    snippet = snippetRegex.find(match.groupValues[3])?.groupValues?.get(1)?.let(HtmlText::inlineText).orEmpty(),
                )
            }.filter { it.url.startsWith("http") }.toList()
        }

        fun unwrapDuckDuckGoUrl(raw: String): String {
            val url = if (raw.startsWith("//")) "https:$raw" else raw
            val uddg = Regex("[?&]uddg=([^&]+)").find(url)?.groupValues?.get(1) ?: return url
            return URLDecoder.decode(uddg, "UTF-8")
        }

        fun parseSearxngJson(body: String): List<SearchResult> {
            val json = AgentJson.parseToJsonElement(body).jsonObject
            return (json["results"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                SearchResult(
                    title = item.text("title"),
                    url = item.text("url").ifEmpty { return@mapNotNull null },
                    snippet = item.text("content"),
                )
            }
        }

        fun parseSearxngHtml(html: String): List<SearchResult> {
            val articleRegex = Regex("""<article[^>]*class="[^"]*result[^"]*"[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
            return articleRegex.findAll(html).mapNotNull { article ->
                val body = article.groupValues[1]
                val link = Regex("""<h3[^>]*>\s*<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).find(body)
                    ?: return@mapNotNull null
                SearchResult(
                    title = HtmlText.inlineText(link.groupValues[2]),
                    url = HtmlText.decodeEntities(link.groupValues[1]),
                    snippet = Regex("""<p[^>]*class="content"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(body)
                        ?.groupValues?.get(1)?.let(HtmlText::inlineText).orEmpty(),
                )
            }.toList()
        }

        fun parseBraveJson(body: String): List<SearchResult> {
            val json = AgentJson.parseToJsonElement(body).jsonObject
            val results = json["web"]?.jsonObject?.get("results") as? JsonArray
            return results.orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                SearchResult(
                    title = HtmlText.inlineText(item.text("title")),
                    url = item.text("url").ifEmpty { return@mapNotNull null },
                    snippet = HtmlText.inlineText(item.text("description")),
                )
            }
        }

        fun parseTavilyJson(body: String): Pair<List<SearchResult>, String?> {
            val json = AgentJson.parseToJsonElement(body).jsonObject
            val results = (json["results"] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                SearchResult(item.text("title"), item.text("url").ifEmpty { return@mapNotNull null }, item.text("content"))
            }
            return results to json["answer"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        private fun JsonObject.text(key: String): String =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull.orEmpty()
    }
}
