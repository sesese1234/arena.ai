package com.nuvio.app.agent.tools

import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.web.UrlFetcher
import com.nuvio.app.agent.web.WebSearch
import okhttp3.OkHttpClient

/** Registers every agent tool. New app features add one `register…()` call or one `tool("…") {}` block. */
object AgentToolCatalog {
    fun registerAll(
        registry: ToolRegistry,
        http: OkHttpClient,
        webSearch: WebSearch,
        memory: AgentMemory,
    ): ToolRegistry = registry.apply {
        registerStateTools()
        registerNavigationTools()
        registerBrowseTools()
        registerDetailsTools()
        registerPlaybackTools()
        registerPlayerTools()
        registerLibraryTools()
        registerProgressTools()
        registerAddonTools()
        registerSettingsTools()
        registerAccountTools()
        registerDownloadTools()
        registerCollectionTools()
        registerWebTools(webSearch, UrlFetcher(http))
        registerAddonProtocolTools(http)
        registerUtilityTools()
        registerMemoryTools(memory)
    }
}
