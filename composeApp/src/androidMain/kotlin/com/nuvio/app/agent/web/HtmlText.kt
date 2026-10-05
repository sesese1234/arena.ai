package com.nuvio.app.agent.web

import com.nuvio.app.agent.core.ToolFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Dependency-free HTML → readable text conversion (good enough for articles and wiki pages). */
object HtmlText {
    private val dropBlocks = Regex(
        "<(script|style|noscript|svg|head|nav|footer|header|form|iframe|template|aside)\\b[^>]*>.*?</\\1\\s*>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val blockBreaks = Regex("<\\s*(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/blockquote|/pre|/table|hr)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val listItems = Regex("<\\s*li\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val headings = Regex("<\\s*h([1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val inlineTags = Regex("</?(b|i|em|strong|a|span|code|sup|sub|small|abbr|u|s|mark|font|q|cite|time)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^>]+>")
    private val titleRegex = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val mainRegex = Regex("<(main|article)\\b[^>]*>(.*)</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun title(html: String): String? = titleRegex.find(html)?.groupValues?.get(1)?.let(::inlineText)?.takeIf { it.isNotBlank() }

    fun toText(html: String): String {
        var body = comments.replace(html, " ")
        // Prefer the main/article element when present: it skips menus and sidebars.
        mainRegex.find(body)?.groupValues?.get(2)?.takeIf { it.length > 500 }?.let { body = it }
        body = dropBlocks.replace(body, " ")
        body = headings.replace(body) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " }
        body = listItems.replace(body, "\n• ")
        body = blockBreaks.replace(body, "\n")
        body = inlineTags.replace(body, "")
        body = tags.replace(body, " ")
        body = decodeEntities(body)
        return body.lines()
            .map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .fold(mutableListOf<String>()) { acc, line ->
                if (line.isEmpty() && (acc.isEmpty() || acc.last().isEmpty())) acc else acc.apply { add(line) }
            }
            .joinToString("\n")
            .trim()
    }

    fun inlineText(html: String): String = decodeEntities(tags.replace(html, "")).replace(Regex("\\s+"), " ").trim()

    fun decodeEntities(text: String): String =
        Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);").replace(text) { match ->
            val entity = match.groupValues[1]
            when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[entity.lowercase()]
            } ?: match.value
        }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "copy" to "©", "reg" to "®", "trade" to "™", "middot" to "·",
    )
}

data class FetchedPage(
    val url: String,
    val status: Int,
    val contentType: String,
    val title: String?,
    val text: String,
    val truncated: Boolean,
    val totalChars: Int,
)

/** `fetch_url`: bounded download (size + time) and readable-text extraction. */
class UrlFetcher(
    baseClient: OkHttpClient,
    private val maxBytes: Long = 2_000_000,
    timeoutSeconds: Long = 20,
) {
    private val http = baseClient.newBuilder()
        .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetch(url: String, maxChars: Int, offset: Int = 0): FetchedPage = withContext(Dispatchers.IO) {
        val httpUrl = url.trim().toHttpUrlOrNull()
            ?: throw ToolFailure("Not a valid http(s) URL: $url", "invalid_url")
        val request = Request.Builder().url(httpUrl)
            .header("User-Agent", WebSearch.BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.5")
            .build()
        http.newCall(request).execute().use { response ->
            val contentType = response.header("content-type").orEmpty()
            val body = response.body
            if (contentType.startsWith("image/") || contentType.startsWith("video/") || contentType.startsWith("audio/") ||
                contentType.contains("octet-stream") || contentType.contains("zip")
            ) {
                throw ToolFailure("Unsupported content type '$contentType' (only text/HTML/JSON pages can be read)", "unsupported")
            }
            val source = body.source()
            source.request(maxBytes)
            val bytes = source.buffer.let { buffer -> buffer.readByteArray(minOf(buffer.size, maxBytes)) }
            val raw = String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
            val isHtml = contentType.contains("html") || raw.trimStart().startsWith("<")
            val text = if (isHtml) HtmlText.toText(raw) else raw
            val start = offset.coerceIn(0, text.length)
            val slice = text.substring(start, minOf(text.length, start + maxChars))
            FetchedPage(
                url = response.request.url.toString(),
                status = response.code,
                contentType = contentType,
                title = if (isHtml) HtmlText.title(raw) else null,
                text = slice,
                truncated = start + slice.length < text.length,
                totalChars = text.length,
            )
        }
    }
}
