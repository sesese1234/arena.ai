package com.nuvio.app.agent.tools

import com.nuvio.app.agent.config.KeyValueStore
import com.nuvio.app.agent.config.SearchBackend
import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolCategory
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.optString
import com.nuvio.app.agent.core.string
import com.nuvio.app.agent.web.UrlFetcher
import com.nuvio.app.agent.web.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal fun ToolRegistry.registerWebTools(search: WebSearch, fetcher: UrlFetcher) {
    tool("web_search") {
        category = ToolCategory.Web
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = """
            Search the web (DuckDuckGo by default; Brave, Tavily or SearXNG if configured in Agent
            settings). Use for facts the app doesn't know: directors, cast trivia, release dates,
            "what should I watch" lists. Returns titles, URLs and snippets; use fetch_url to read a page.
        """
        params {
            string("query", "Search query", required = true)
            integer("max_results", "Results to return (default 6)", min = 1, max = 15)
            string("backend", "Force a backend", enum = SearchBackend.entries.map { it.name.lowercase() })
        }
        run { args ->
            val backend = args.optString("backend")?.let { name -> SearchBackend.entries.first { it.name.equals(name, true) } }
            val response = search.search(args.string("query"), args.optInt("max_results") ?: 6, backend)
            okResult(
                "backend" to response.backend.name.lowercase(),
                "fallback" to response.fallback,
                "answer" to response.answer,
                "results" to response.results.map { mapOf("title" to it.title, "url" to it.url, "snippet" to it.snippet.take(300)) },
            )
        }
    }

    tool("fetch_url") {
        category = ToolCategory.Web
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = """
            Download a web page and return its readable text (scripts/menus stripped). Max 2 MB download,
            20 s timeout; returns up to max_chars characters starting at offset (page through long pages).
        """
        params {
            string("url", "http(s) URL", required = true)
            integer("max_chars", "Characters to return (default 6000)", min = 200, max = 20_000)
            integer("offset", "Start offset into the extracted text", min = 0)
        }
        run { args ->
            val page = fetcher.fetch(args.string("url"), args.optInt("max_chars") ?: 6_000, args.optInt("offset") ?: 0)
            okResult(
                "url" to page.url, "status" to page.status, "title" to page.title, "content_type" to page.contentType,
                "text" to page.text, "truncated" to page.truncated, "total_chars" to page.totalChars,
            )
        }
    }
}

/** Direct Stremio addon protocol queries: manifest, catalog, meta, stream and subtitles resources. */
internal fun ToolRegistry.registerAddonProtocolTools(http: OkHttpClient) {
    suspend fun getJson(url: String): JsonElement = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ToolFailure("HTTP ${response.code} from $url: ${body.take(200)}", "http_${response.code}")
            runCatching { AgentJson.parseToJsonElement(body) }.getOrElse { throw ToolFailure("Addon returned invalid JSON", "invalid_response") }
        }
    }

    fun baseUrl(addon: String): String {
        val raw = addon.trim()
        val url = if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("stremio://")) {
            raw.replace("stremio://", "https://")
        } else {
            findAddon(raw).manifestUrl
        }
        return url.substringBefore("?").removeSuffix("/manifest.json").trimEnd('/')
    }

    fun extraPath(extra: Map<String, String?>): String =
        extra.filterValues { !it.isNullOrBlank() }.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        }.let { if (it.isEmpty()) "" else "/$it" }

    tool("addon_manifest") {
        category = ToolCategory.AddonProtocol
        readOnly = true
        mainThread = false
        description = "Fetch an addon's manifest (any URL, or an installed addon by name) — useful before installing to see what it provides."
        params { string("addon", "Manifest URL or installed addon name/id", required = true) }
        run { args ->
            val manifest = getJson(baseUrl(args.string("addon")) + "/manifest.json").jsonObject
            okResult(
                "id" to manifest.str("id"), "name" to manifest.str("name"), "version" to manifest.str("version"),
                "description" to manifest.str("description"),
                "resources" to manifest["resources"], "types" to manifest["types"],
                "catalogs" to (manifest["catalogs"] as? JsonArray)?.take(30),
                "id_prefixes" to manifest["idPrefixes"],
                "behavior_hints" to manifest["behaviorHints"],
            )
        }
    }

    tool("addon_catalog") {
        category = ToolCategory.AddonProtocol
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = "Query an addon's /catalog resource directly (with optional search, genre or skip extras)."
        params {
            string("addon", "Manifest URL or installed addon name/id", required = true)
            string("type", "Content type, e.g. movie", required = true)
            string("catalog_id", "Catalog id, e.g. top", required = true)
            string("search", "Search query extra")
            string("genre", "Genre extra")
            integer("skip", "Pagination offset", min = 0)
            integer("limit", "Items to return (default 20)", min = 1, max = 100)
        }
        run { args ->
            val url = "${baseUrl(args.string("addon"))}/catalog/${args.string("type")}/${args.string("catalog_id")}" +
                extraPath(mapOf("search" to args.optString("search"), "genre" to args.optString("genre"), "skip" to args.optInt("skip")?.toString())) + ".json"
            val metas = (getJson(url).jsonObject["metas"] as? JsonArray).orEmpty()
            okResult("count" to metas.size, "items" to metas.take(args.optInt("limit") ?: 20).map { element ->
                val m = element.jsonObject
                mapOf("id" to m.str("id"), "type" to m.str("type"), "name" to m.str("name"), "year" to (m.str("releaseInfo") ?: m.str("year")), "imdb_rating" to m.str("imdbRating"))
            })
        }
    }

    tool("addon_meta") {
        category = ToolCategory.AddonProtocol
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = "Query an addon's /meta resource directly for one title (raw addon metadata)."
        params {
            string("addon", "Manifest URL or installed addon name/id", required = true)
            string("type", "movie or series", required = true)
            string("id", "Title id", required = true)
        }
        run { args ->
            val meta = getJson("${baseUrl(args.string("addon"))}/meta/${args.string("type")}/${args.string("id")}.json").jsonObject["meta"]?.jsonObject
                ?: throw ToolFailure("Addon returned no meta", "not_found")
            okResult(
                "id" to meta.str("id"), "name" to meta.str("name"), "year" to meta.str("releaseInfo"),
                "description" to meta.str("description")?.take(800), "genres" to meta["genres"], "director" to meta["director"],
                "cast" to (meta["cast"] as? JsonArray)?.take(12), "imdb_rating" to meta.str("imdbRating"),
                "videos" to (meta["videos"] as? JsonArray)?.size,
            )
        }
    }

    tool("addon_streams") {
        category = ToolCategory.AddonProtocol
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = "Query /stream directly: which streams an addon offers for a title or episode id (series: tt…:season:episode)."
        params {
            string("addon", "Manifest URL or installed addon name/id", required = true)
            string("type", "movie or series", required = true)
            string("id", "Video id, e.g. tt0133093 or tt0903747:1:1", required = true)
        }
        run { args ->
            val streams = (getJson("${baseUrl(args.string("addon"))}/stream/${args.string("type")}/${args.string("id")}.json").jsonObject["streams"] as? JsonArray).orEmpty()
            okResult("count" to streams.size, "streams" to streams.take(25).map { element ->
                val s = element.jsonObject
                mapOf(
                    "name" to s.str("name")?.replace('\n', ' '), "title" to (s.str("title") ?: s.str("description"))?.replace('\n', ' ')?.take(160),
                    "kind" to when { s["url"] != null -> "http"; s["infoHash"] != null -> "torrent"; s["externalUrl"] != null -> "external"; else -> "other" },
                )
            })
        }
    }

    tool("addon_subtitles") {
        category = ToolCategory.AddonProtocol
        readOnly = true
        mainThread = false
        timeoutMs = 30_000
        description = "Query /subtitles directly: subtitle tracks an addon (e.g. OpenSubtitles) has for a title or episode."
        params {
            string("addon", "Manifest URL or installed addon name/id", required = true)
            string("type", "movie or series", required = true)
            string("id", "Video id", required = true)
            string("language", "Only this language (ISO 639-2, e.g. eng)")
        }
        run { args ->
            val subs = (getJson("${baseUrl(args.string("addon"))}/subtitles/${args.string("type")}/${args.string("id")}.json").jsonObject["subtitles"] as? JsonArray).orEmpty()
            val lang = args.optString("language")
            val filtered = subs.map { it.jsonObject }.filter { lang == null || matchesLanguage(it.str("lang"), null, lang) }
            okResult(
                "count" to filtered.size,
                "languages" to subs.mapNotNull { it.jsonObject.str("lang") }.distinct(),
                "subtitles" to filtered.take(20).map { mapOf("id" to it.str("id"), "lang" to it.str("lang"), "url" to it.str("url")) },
            )
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

internal fun ToolRegistry.registerUtilityTools(clockZone: () -> ZoneId = { ZoneId.systemDefault() }) {
    tool("calculate") {
        category = ToolCategory.Utility
        readOnly = true
        mainThread = false
        description = "Evaluate an arithmetic expression exactly (+ - * / % ^, parentheses, sqrt, abs, round, floor, ceil, min, max). E.g. runtime math."
        params { string("expression", "Expression, e.g. (2*60+16)/60", required = true) }
        run { args ->
            val value = Calculator.evaluate(args.string("expression"))
            okResult("expression" to args.string("expression"), "result" to if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong() else value)
        }
    }

    tool("get_current_time") {
        category = ToolCategory.Utility
        readOnly = true
        mainThread = false
        description = "Current date/time (optionally in another IANA time zone), plus days until/since a date if given."
        params {
            string("time_zone", "IANA zone, e.g. Europe/Paris (default: device zone)")
            string("compare_date", "ISO date (YYYY-MM-DD) to compute days until/since")
        }
        run { args ->
            val zone = args.optString("time_zone")?.let { runCatching { ZoneId.of(it) }.getOrElse { throw ToolFailure("Unknown time zone '$it'", "invalid_arguments") } } ?: clockZone()
            val now = ZonedDateTime.now(zone)
            val compare = args.optString("compare_date")?.let { raw ->
                val date = runCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrElse { throw ToolFailure("compare_date must be YYYY-MM-DD", "invalid_arguments") }
                ChronoUnit.DAYS.between(now.toLocalDate(), date)
            }
            okResult(
                "iso" to now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                "weekday" to now.dayOfWeek.name.lowercase(),
                "time_zone" to zone.id,
                "days_until_compare_date" to compare,
            )
        }
    }
}

/** Remembered user preferences ("I prefer English subtitles"); injected into the system prompt. */
class AgentMemory(private val store: KeyValueStore) {
    fun all(): Map<String, String> = store.keys().filter { it.startsWith(PREFIX) }.associate { it.removePrefix(PREFIX) to store.getString(it).orEmpty() }
    fun put(key: String, value: String) = store.putString(PREFIX + key.trim().lowercase().replace(Regex("\\s+"), "_").take(60), value.take(500))
    fun remove(key: String): Boolean {
        val k = PREFIX + key.trim().lowercase().replace(Regex("\\s+"), "_")
        val existed = store.getString(k) != null
        store.remove(k)
        return existed
    }
    fun clear() = store.keys().filter { it.startsWith(PREFIX) }.forEach(store::remove)

    companion object {
        private const val PREFIX = "memory/"
        const val MAX_ENTRIES = 50
    }
}

internal fun ToolRegistry.registerMemoryTools(memory: AgentMemory) {
    tool("remember") {
        category = ToolCategory.Memory
        mainThread = false
        description = "Save a lasting user preference or fact (e.g. key=subtitle_language value=English). Remembered items are shown to you in every conversation."
        params {
            string("key", "Short key, e.g. favorite_genre", required = true)
            string("value", "What to remember", required = true)
        }
        run { args ->
            if (memory.all().size >= AgentMemory.MAX_ENTRIES) throw ToolFailure("Memory is full (${AgentMemory.MAX_ENTRIES}); forget something first", "full")
            memory.put(args.string("key"), args.string("value"))
            okResult("remembered" to args.string("key"))
        }
    }

    tool("recall") {
        category = ToolCategory.Memory
        readOnly = true
        mainThread = false
        description = "List everything remembered about the user."
        run { okResult("memory" to memory.all()) }
    }

    tool("forget") {
        category = ToolCategory.Memory
        mainThread = false
        description = "Forget one remembered item (by key), or everything with key=\"*\" (asks to confirm)."
        params { string("key", "Key to forget, or * for all", required = true) }
        confirm { args -> if (args.string("key") == "*") ConfirmationRequest("Forget everything?", "All remembered preferences will be deleted.", "Forget all") else null }
        run { args ->
            if (args.string("key") == "*") {
                memory.clear()
                okResult("forgot" to "all")
            } else {
                okResult("forgot" to memory.remove(args.string("key")))
            }
        }
    }
}

/** Small recursive-descent evaluator (no reflection, no script engine). */
object Calculator {
    fun evaluate(expression: String): Double {
        val parser = Parser(expression.replace(" ", "").replace("×", "*").replace("÷", "/"))
        val value = parser.parseExpression()
        if (parser.pos < parser.text.length) throw ToolFailure("Unexpected '${parser.text[parser.pos]}' at position ${parser.pos}", "invalid_arguments")
        if (value.isNaN() || value.isInfinite()) throw ToolFailure("Result is not a finite number", "math_error")
        return value
    }

    private class Parser(val text: String) {
        var pos = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (pos < text.length) {
                when (text[pos]) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
            return value
        }

        fun parseTerm(): Double {
            var value = parsePower()
            while (pos < text.length) {
                when (text[pos]) {
                    '*' -> { pos++; value *= parsePower() }
                    '/' -> { pos++; val d = parsePower(); if (d == 0.0) throw ToolFailure("Division by zero", "math_error"); value /= d }
                    '%' -> { pos++; value %= parsePower() }
                    else -> return value
                }
            }
            return value
        }

        fun parsePower(): Double {
            val base = parseUnary()
            if (pos < text.length && text[pos] == '^') {
                pos++
                return Math.pow(base, parsePower())
            }
            return base
        }

        fun parseUnary(): Double = when {
            pos < text.length && text[pos] == '-' -> { pos++; -parseUnary() }
            pos < text.length && text[pos] == '+' -> { pos++; parseUnary() }
            else -> parsePrimary()
        }

        fun parsePrimary(): Double {
            if (pos >= text.length) throw ToolFailure("Unexpected end of expression", "invalid_arguments")
            if (text[pos] == '(') {
                pos++
                val value = parseExpression()
                expect(')')
                return value
            }
            if (text[pos].isLetter()) {
                val start = pos
                while (pos < text.length && text[pos].isLetter()) pos++
                val name = text.substring(start, pos).lowercase()
                if (name == "pi") return Math.PI
                if (name == "e") return Math.E
                expect('(')
                val argsList = mutableListOf(parseExpression())
                while (pos < text.length && text[pos] == ',') { pos++; argsList += parseExpression() }
                expect(')')
                return when (name) {
                    "sqrt" -> Math.sqrt(argsList[0])
                    "abs" -> Math.abs(argsList[0])
                    "round" -> Math.round(argsList[0]).toDouble()
                    "floor" -> Math.floor(argsList[0])
                    "ceil" -> Math.ceil(argsList[0])
                    "min" -> argsList.min()
                    "max" -> argsList.max()
                    "log" -> Math.log10(argsList[0])
                    "ln" -> Math.log(argsList[0])
                    else -> throw ToolFailure("Unknown function '$name'", "invalid_arguments")
                }
            }
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '.')) pos++
            return text.substring(start, pos).toDoubleOrNull() ?: throw ToolFailure("Expected a number at position $start", "invalid_arguments")
        }

        fun expect(c: Char) {
            if (pos >= text.length || text[pos] != c) throw ToolFailure("Expected '$c' at position $pos", "invalid_arguments")
            pos++
        }
    }
}
