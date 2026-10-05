package com.nuvio.app.agent

import com.nuvio.app.agent.catalog.ModelsCatalog
import com.nuvio.app.agent.catalog.ProviderMapping
import com.nuvio.app.agent.config.InMemoryKeyValueStore
import com.nuvio.app.agent.config.SearchSettings
import com.nuvio.app.agent.llm.AgentHttp
import com.nuvio.app.agent.llm.LlmClientFactory
import com.nuvio.app.agent.llm.ProviderConfig
import com.nuvio.app.agent.llm.ResolvedModel
import com.nuvio.app.agent.runtime.AgentRunConfig
import com.nuvio.app.agent.runtime.AgentSession
import com.nuvio.app.agent.runtime.ChatItemKind
import com.nuvio.app.agent.tools.AgentMemory
import com.nuvio.app.agent.tools.registerAddonProtocolTools
import com.nuvio.app.agent.tools.registerMemoryTools
import com.nuvio.app.agent.tools.registerUtilityTools
import com.nuvio.app.agent.tools.registerWebTools
import com.nuvio.app.agent.web.UrlFetcher
import com.nuvio.app.agent.web.WebSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end agent loop against a real provider (DeepSeek `deepseek-flash` by default) with real
 * network tools. Skipped unless DEEPSEEK_API_KEY is set. Override with LIVE_PROVIDER / LIVE_MODEL.
 */
class LiveProviderTest {
    private val key: String? = System.getenv("DEEPSEEK_API_KEY")?.takeIf { it.isNotBlank() }
    private val providerId = System.getenv("LIVE_PROVIDER") ?: "deepseek"
    private val modelId = System.getenv("LIVE_MODEL") ?: "deepseek-flash"

    private fun resolved(): ResolvedModel {
        val asset = listOf("src/androidMain/assets/agent/models_dev.json", "composeApp/src/androidMain/assets/agent/models_dev.json").map(::File).first { it.exists() }
        val provider = ModelsCatalog.parse(asset.readText()).provider(providerId)!!
        val config = ProviderMapping.toProviderConfig(provider, listOf(modelId))
        return ResolvedModel(config, config.models.single(), mapOf(ProviderConfig.SECRET_API_KEY to key!!))
    }

    private fun runTask(prompt: String): Pair<List<String>, String> = runBlocking {
        val registry = testRegistry().apply {
            registerWebTools(WebSearch(AgentHttp.client, { SearchSettings() }, { null }), UrlFetcher(AgentHttp.client))
            registerAddonProtocolTools(AgentHttp.client)
            registerUtilityTools()
            registerMemoryTools(AgentMemory(InMemoryKeyValueStore()))
        }
        val model = resolved()
        val session = AgentSession(CoroutineScope(SupervisorJob() + Dispatchers.IO), registry)
        session.send(prompt) {
            AgentRunConfig(model, LlmClientFactory.create(model), { "You are a helpful assistant inside a media app. Use tools to find facts; answer briefly." }, maxSteps = 8)
        }
        withTimeout(180_000) { session.state.first { !it.running && it.items.size > 1 } }
        val items = session.state.value.items
        items.forEach { println("[${it.kind}] ${it.toolName ?: ""} ${it.arguments ?: ""} ${it.text.take(300)} ${it.result?.take(200) ?: ""}") }
        val tools = items.filter { it.kind == ChatItemKind.ToolCall }.mapNotNull { it.toolName }
        val answer = items.lastOrNull { it.kind == ChatItemKind.Assistant }?.text.orEmpty()
        assertTrue(items.none { it.kind == ChatItemKind.Error }, "agent run reported an error")
        tools to answer
    }

    @Test
    fun webSearchThenAnswer() {
        assumeTrue("DEEPSEEK_API_KEY not set", key != null)
        val (tools, answer) = runTask("Search the web: who directed the 1999 film The Matrix? Reply with the directors' names.")
        assertTrue("web_search" in tools, "expected a web_search call, got $tools")
        assertTrue(answer.contains("Wachowski", ignoreCase = true), answer)
    }

    @Test
    fun stremioAddonProtocolQuery() {
        assumeTrue("DEEPSEEK_API_KEY not set", key != null)
        val (tools, answer) = runTask(
            "Using the Stremio addon at https://v3-cinemeta.strem.io/manifest.json, search its movie catalog " +
                "(catalog id 'top', type 'movie') for \"The Matrix\" and tell me the IMDb id of the 1999 film.",
        )
        assertTrue(tools.any { it.startsWith("addon_") }, "expected addon protocol calls, got $tools")
        assertTrue(answer.contains("tt0133093"), answer)
    }

    @Test
    fun calculatorAndMultiStepReasoning() {
        assumeTrue("DEEPSEEK_API_KEY not set", key != null)
        val (tools, answer) = runTask("Use the calculate tool: a movie is 136 minutes long. How many hours is that, to two decimals?")
        assertTrue("calculate" in tools, "$tools")
        assertTrue(answer.contains("2.27"), answer)
    }
}
