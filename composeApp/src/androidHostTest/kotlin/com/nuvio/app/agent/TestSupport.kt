package com.nuvio.app.agent

import com.nuvio.app.agent.config.InMemoryKeyValueStore
import com.nuvio.app.agent.config.SearchSettings
import com.nuvio.app.agent.core.ConfirmationPresenter
import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.llm.AgentHttp
import com.nuvio.app.agent.tools.AgentMemory
import com.nuvio.app.agent.tools.AgentToolCatalog
import com.nuvio.app.agent.web.WebSearch
import kotlinx.coroutines.Dispatchers

internal fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResourceAsStream("/agent/fixtures/$name")) { "missing fixture $name" }
        .bufferedReader().use { it.readText() }

/** Records confirmation prompts and answers them with [answer]. */
internal class RecordingConfirmations(var answer: Boolean = true) : ConfirmationPresenter {
    val asked = mutableListOf<ConfirmationRequest>()
    override suspend fun confirm(request: ConfirmationRequest): Boolean {
        asked += request
        return answer
    }
}

internal fun testRegistry(confirmations: ConfirmationPresenter = RecordingConfirmations()) =
    ToolRegistry(confirmations, mainDispatcher = Dispatchers.Unconfined, backgroundDispatcher = Dispatchers.Unconfined)

internal fun fullCatalog(confirmations: ConfirmationPresenter = RecordingConfirmations()): ToolRegistry =
    AgentToolCatalog.registerAll(
        testRegistry(confirmations),
        AgentHttp.client,
        WebSearch(AgentHttp.client, { SearchSettings() }, { null }),
        AgentMemory(InMemoryKeyValueStore()),
    )
