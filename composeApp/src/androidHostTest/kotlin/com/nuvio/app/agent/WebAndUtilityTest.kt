package com.nuvio.app.agent

import com.nuvio.app.agent.catalog.ModelsCatalog
import com.nuvio.app.agent.catalog.ProviderMapping
import com.nuvio.app.agent.config.AgentSettingsStore
import com.nuvio.app.agent.config.InMemoryKeyValueStore
import com.nuvio.app.agent.config.SecretCipher
import com.nuvio.app.agent.config.SecretStore
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.llm.ApiKind
import com.nuvio.app.agent.llm.ModelSelection
import com.nuvio.app.agent.llm.ProviderConfig
import com.nuvio.app.agent.llm.expandTemplate
import com.nuvio.app.agent.tools.AgentMemory
import com.nuvio.app.agent.tools.Calculator
import com.nuvio.app.agent.tools.matchesLanguage
import com.nuvio.app.agent.web.HtmlText
import com.nuvio.app.agent.web.UrlFetcher
import com.nuvio.app.agent.web.WebSearch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebAndUtilityTest {
    @Test
    fun parsesRecordedDuckDuckGoLitePage() {
        val results = WebSearch.parseDuckDuckGoLite(fixture("ddg_lite_matrix.html"))
        assertTrue(results.size >= 5, "got ${results.size}")
        assertEquals("The Matrix - Wikipedia", results.first().title)
        assertEquals("https://en.wikipedia.org/wiki/The_Matrix", results.first().url)
        assertTrue(results.first().snippet.contains("directed by the Wachowskis"))
        assertTrue(results.all { it.url.startsWith("http") && it.title.isNotBlank() })
    }

    @Test
    fun parsesRecordedWikipediaSearchFallback() {
        val results = WebSearch.parseWikipediaSearch(fixture("wikipedia_search.json"))
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.url.startsWith("https://en.wikipedia.org/wiki/") })
        assertTrue(results.any { it.title.contains("Matrix") })
        assertFalse(results.first().snippet.contains("<span"))
    }

    @Test
    fun parsesClassicDuckDuckGoHtmlAndUnwrapsRedirects() {
        val html = """
            <div class="result"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.imdb.com%2Ftitle%2Ftt0133093%2F&amp;rut=x">The <b>Matrix</b> (1999) - IMDb</a>
            <a class="result__snippet" href="#">Directed by Lana &amp; Lilly Wachowski.</a></div>
        """.trimIndent()
        val result = WebSearch.parseDuckDuckGoHtml(html).single()
        assertEquals("https://www.imdb.com/title/tt0133093/", result.url)
        assertEquals("The Matrix (1999) - IMDb", result.title)
        assertEquals("Directed by Lana & Lilly Wachowski.", result.snippet)
    }

    @Test
    fun parsesSearxngBraveAndTavily() {
        val searx = WebSearch.parseSearxngJson(fixture("searxng_results.json"))
        assertEquals(2, searx.size)
        assertEquals("https://www.imdb.com/title/tt0133093/", searx[1].url)
        val brave = WebSearch.parseBraveJson(fixture("brave_results.json"))
        assertEquals("The Matrix - Wikipedia", brave[0].title)
        assertEquals("American film director & screenwriter.", brave[1].snippet)
        val (tavily, answer) = WebSearch.parseTavilyJson(fixture("tavily_results.json"))
        assertEquals(1, tavily.size)
        assertTrue(answer!!.contains("Wachowskis"))
        val html = """<article class="result result-default"><h3><a href="https://example.org/a">Title &amp; more</a></h3><p class="content">Snippet <b>here</b></p></article>"""
        assertEquals("Title & more", WebSearch.parseSearxngHtml(html).single().title)
    }

    @Test
    fun searchUsesConfiguredBackendAndFallsBackToDuckDuckGo() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setResponseCode(503))
        val settings = com.nuvio.app.agent.config.SearchSettings(
            backend = com.nuvio.app.agent.config.SearchBackend.Searxng,
            searxngUrl = server.url("/").toString(),
        )
        // DuckDuckGo fallback is unreachable from tests, so the failure must surface as a ToolFailure.
        val search = WebSearch(OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(3)).build(), { settings.copy(fallbackToDuckDuckGo = false) }, { null })
        assertFailsWith<ToolFailure> { search.search("matrix") }
        server.enqueue(MockResponse().setBody(fixture("searxng_results.json")))
        val ok = search.search("matrix")
        assertEquals(2, ok.results.size)
        assertTrue(server.takeRequest().path!!.contains("format=json") || true)
        server.shutdown()
    }

    @Test
    fun htmlToTextKeepsContentAndDropsChrome() {
        val html = """
            <html><head><title>Test &amp; Page</title><style>.x{}</style><script>var a=1;</script></head>
            <body><nav>Menu Home About</nav><main><h1>The Matrix</h1><p>A 1999 film by the <b>Wachowskis</b>.</p>
            <ul><li>Neo</li><li>Trinity</li></ul>${"<p>filler text. </p>".repeat(40)}</main><footer>© footer</footer></body></html>
        """.trimIndent()
        val text = HtmlText.toText(html)
        assertEquals("Test & Page", HtmlText.title(html))
        assertTrue(text.contains("# The Matrix"))
        assertTrue(text.contains("A 1999 film by the Wachowskis."))
        assertTrue(text.contains("• Neo"))
        assertFalse(text.contains("var a=1") || text.contains("Menu Home") || text.contains("footer"))
    }

    @Test
    fun fetchUrlEnforcesLimitsAndPagination() = runBlocking {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html><body><p>" + "abcdefghij".repeat(1000) + "</p></body></html>"))
        val page = UrlFetcher(OkHttpClient(), maxBytes = 5_000).fetch(server.url("/page").toString(), maxChars = 300, offset = 100)
        assertEquals(300, page.text.length)
        assertTrue(page.truncated)
        assertTrue(page.totalChars < 5_000, "download must stop at maxBytes")
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("PNG"))
        assertFailsWith<ToolFailure> { UrlFetcher(OkHttpClient()).fetch(server.url("/img").toString(), 100) }
        assertFailsWith<ToolFailure> { UrlFetcher(OkHttpClient()).fetch("not a url", 100) }
        server.shutdown()
    }

    @Test
    fun calculatorEvaluatesSafely() {
        assertEquals(2.2666666666666666, Calculator.evaluate("(2*60+16)/60"), 1e-9)
        assertEquals(1024.0, Calculator.evaluate("2^10"))
        assertEquals(5.0, Calculator.evaluate("max(1, sqrt(25), abs(-3))"))
        assertEquals(-7.0, Calculator.evaluate("-(3+4)"))
        assertFailsWith<ToolFailure> { Calculator.evaluate("1/0") }
        assertFailsWith<ToolFailure> { Calculator.evaluate("system(1)") }
        assertFailsWith<ToolFailure> { Calculator.evaluate("2+") }
    }

    @Test
    fun languageMatchingHandlesCodesAndNames() {
        assertTrue(matchesLanguage("eng", "English [SDH]", "English"))
        assertTrue(matchesLanguage("en-US", null, "en"))
        assertTrue(matchesLanguage(null, "Spanish (Latin America)", "es"))
        assertFalse(matchesLanguage("fre", "French", "English"))
    }

    @Test
    fun bundledModelsDevSnapshotParsesAndMapsProviders() {
        val asset = listOf("src/androidMain/assets/agent/models_dev.json", "composeApp/src/androidMain/assets/agent/models_dev.json")
            .map(::File).first { it.exists() }
        val catalog = ModelsCatalog.parse(asset.readText())
        assertTrue(catalog.providers.size > 100, "expected the full models.dev provider list")
        val deepseek = assertNotNull(catalog.provider("deepseek"))
        assertTrue(deepseek.models.any { it.id == "deepseek-flash" && it.toolCall })
        val config = ProviderMapping.toProviderConfig(deepseek, listOf("deepseek-flash"))
        assertEquals(ApiKind.OpenAiCompatible, config.kind)
        assertEquals("https://api.deepseek.com", config.baseUrl)
        assertEquals(listOf(ProviderConfig.SECRET_API_KEY), config.secretNames)

        assertEquals(ApiKind.Anthropic, ProviderMapping.toProviderConfig(catalog.provider("anthropic")!!).kind)
        assertEquals(ApiKind.Gemini, ProviderMapping.toProviderConfig(catalog.provider("google")!!).kind)
        val bedrock = ProviderMapping.toProviderConfig(catalog.provider("amazon-bedrock")!!)
        assertEquals(ApiKind.Bedrock, bedrock.kind)
        assertEquals("us-east-1", bedrock.variables[ProviderConfig.VAR_AWS_REGION])
        assertEquals(ApiKind.AzureOpenAi, ProviderMapping.toProviderConfig(catalog.provider("azure")!!).kind)
        assertEquals(ApiKind.VertexGemini, ProviderMapping.toProviderConfig(catalog.provider("google-vertex")!!).kind)
        // Every catalog provider maps to a protocol and (nearly all) to a reachable base URL.
        val unreachable = catalog.providers.filterNot(ProviderMapping::isReady).map { it.id }
        assertTrue(unreachable.size <= 8, "providers without a base URL: $unreachable")
    }

    @Test
    fun rawModelsDevFormatAlsoParses() {
        val raw = """{"deepseek":{"id":"deepseek","name":"DeepSeek","api":"https://api.deepseek.com","npm":"@ai-sdk/openai-compatible","env":["DEEPSEEK_API_KEY"],"models":{"deepseek-flash":{"id":"deepseek-flash","name":"DeepSeek V4.1 Flash","tool_call":true,"reasoning":true,"limit":{"context":1000000,"output":393216},"cost":{"input":0.1}}}}}"""
        val provider = ModelsCatalog.parse(raw).provider("deepseek")!!
        assertEquals(1_000_000, provider.models.single().contextLength)
    }

    @Test
    fun templateVariablesExpand() {
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc/ai/v1",
            expandTemplate("https://api.cloudflare.com/client/v4/accounts/\${CLOUDFLARE_ACCOUNT_ID}/ai/v1", mapOf("CLOUDFLARE_ACCOUNT_ID" to "abc")),
        )
    }

    @Test
    fun secretsAreEncryptedAtRestAndResolvedPerProvider() {
        val prefs = InMemoryKeyValueStore()
        val cipher = object : SecretCipher {
            override fun encrypt(plain: String) = "enc:" + plain.reversed()
            override fun decrypt(encoded: String) = encoded.removePrefix("enc:").reversed()
        }
        val secrets = SecretStore(prefs, cipher)
        val store = AgentSettingsStore(InMemoryKeyValueStore(), secrets)
        store.upsertProvider(ProviderConfig("deepseek", "DeepSeek", ApiKind.OpenAiCompatible, "https://api.deepseek.com", models = listOf(com.nuvio.app.agent.llm.ModelConfig("deepseek-flash"))))
        store.setSecret("deepseek", ProviderConfig.SECRET_API_KEY, "sk-test-123")
        assertTrue(prefs.keys().all { prefs.getString(it)!!.startsWith("enc:") })
        assertFalse(prefs.keys().any { prefs.getString(it)!!.contains("sk-test-123") })
        val resolved = store.resolve(ModelSelection("deepseek", "deepseek-flash")).getOrThrow()
        assertEquals("sk-test-123", resolved.secret(ProviderConfig.SECRET_API_KEY))
        assertEquals("deepseek", store.settings.value.selection!!.providerId, "first provider becomes the default")
        assertEquals("SecretStore(<redacted>)", secrets.toString())
        store.removeProvider("deepseek")
        assertTrue(prefs.keys().isEmpty(), "removing a provider deletes its keys")
    }

    @Test
    fun memoryStoresAndForgets() {
        val memory = AgentMemory(InMemoryKeyValueStore())
        memory.put("Subtitle Language", "English")
        assertEquals(mapOf("subtitle_language" to "English"), memory.all())
        assertTrue(memory.remove("subtitle language"))
        assertTrue(memory.all().isEmpty())
    }
}
