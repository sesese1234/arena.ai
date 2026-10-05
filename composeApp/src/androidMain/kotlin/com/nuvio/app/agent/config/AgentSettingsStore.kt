package com.nuvio.app.agent.config

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.llm.ModelConfig
import com.nuvio.app.agent.llm.ModelSelection
import com.nuvio.app.agent.llm.ProviderConfig
import com.nuvio.app.agent.llm.ResolvedModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable
enum class SearchBackend(val label: String, val needsKey: Boolean, val needsUrl: Boolean) {
    DuckDuckGo("DuckDuckGo (no key)", needsKey = false, needsUrl = false),
    SearxngPublic("SearXNG public instance (no key)", needsKey = false, needsUrl = true),
    Brave("Brave Search API", needsKey = true, needsUrl = false),
    Tavily("Tavily", needsKey = true, needsUrl = false),
    Searxng("SearXNG (your instance)", needsKey = false, needsUrl = true),
}

@Serializable
data class SearchSettings(
    val backend: SearchBackend = SearchBackend.DuckDuckGo,
    val searxngUrl: String = "",
    val publicSearxngUrl: String = DEFAULT_PUBLIC_SEARXNG,
    /** Try the next backend when the chosen one fails (rate limit, captcha). */
    val fallbackToDuckDuckGo: Boolean = true,
) {
    companion object {
        const val DEFAULT_PUBLIC_SEARXNG = "https://searx.be"
        const val SECRET_SCOPE = "search"
    }
}

@Serializable
data class AgentSettings(
    val providers: List<ProviderConfig> = emptyList(),
    val selection: ModelSelection? = null,
    val search: SearchSettings = SearchSettings(),
    val maxSteps: Int = 16,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val showReasoning: Boolean = false,
    val showFloatingButton: Boolean = true,
    val parallelToolCalls: Boolean = true,
)

/** Non-secret agent configuration persisted as JSON; secrets go through [SecretStore]. */
class AgentSettingsStore(
    private val prefs: KeyValueStore,
    val secrets: SecretStore,
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    private fun load(): AgentSettings =
        prefs.getString(KEY)?.let { raw ->
            runCatching { AgentJson.decodeFromString(AgentSettings.serializer(), raw) }.getOrNull()
        } ?: AgentSettings()

    fun update(transform: (AgentSettings) -> AgentSettings) {
        _settings.update(transform)
        prefs.putString(KEY, AgentJson.encodeToString(AgentSettings.serializer(), _settings.value))
    }

    fun provider(id: String): ProviderConfig? = settings.value.providers.firstOrNull { it.id == id }

    fun upsertProvider(provider: ProviderConfig) = update { current ->
        val exists = current.providers.any { it.id == provider.id }
        val providers = if (exists) current.providers.map { if (it.id == provider.id) provider else it } else current.providers + provider
        val selection = current.selection ?: provider.models.firstOrNull()?.let { ModelSelection(provider.id, it.id) }
        current.copy(providers = providers, selection = selection)
    }

    fun removeProvider(id: String) {
        secrets.clearScope(providerScope(id))
        update { current ->
            current.copy(
                providers = current.providers.filterNot { it.id == id },
                selection = current.selection?.takeIf { it.providerId != id },
            )
        }
    }

    fun select(providerId: String, modelId: String) = update { it.copy(selection = ModelSelection(providerId, modelId)) }

    fun setSecret(providerId: String, name: String, value: String?) = secrets.put(providerScope(providerId), name, value)

    fun hasSecret(providerId: String, name: String): Boolean = secrets.has(providerScope(providerId), name)

    fun setSearchSecret(backend: SearchBackend, value: String?) = secrets.put(SearchSettings.SECRET_SCOPE, backend.name, value)

    fun searchSecret(backend: SearchBackend): String? = secrets.get(SearchSettings.SECRET_SCOPE, backend.name)

    /** Resolves the active (or given) selection with decrypted secrets, or null with a reason. */
    fun resolve(selection: ModelSelection? = settings.value.selection): Result<ResolvedModel> {
        val chosen = selection ?: return Result.failure(IllegalStateException("No model selected. Open Agent settings and pick a provider and model."))
        val provider = provider(chosen.providerId)
            ?: return Result.failure(IllegalStateException("Provider '${chosen.providerId}' is not configured."))
        val model = provider.models.firstOrNull { it.id == chosen.modelId } ?: ModelConfig(id = chosen.modelId, custom = true)
        val secretNames = provider.secretNames + listOf(
            ProviderConfig.SECRET_API_KEY,
            ProviderConfig.SECRET_AWS_SESSION_TOKEN,
            ProviderConfig.SECRET_GOOGLE_ACCESS_TOKEN,
        )
        return Result.success(ResolvedModel(provider, model, secrets.all(providerScope(provider.id), secretNames.distinct())))
    }

    companion object {
        const val PREFS_FILE = "nuvio_agent_config"
        private const val KEY = "settings_v1"
        fun providerScope(id: String) = "provider:$id"
    }
}
