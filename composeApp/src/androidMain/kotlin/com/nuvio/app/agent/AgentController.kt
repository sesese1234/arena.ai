package com.nuvio.app.agent

import android.content.Context
import com.nuvio.app.agent.catalog.ModelsCatalog
import com.nuvio.app.agent.catalog.ProviderMapping
import com.nuvio.app.agent.config.AgentSettingsStore
import com.nuvio.app.agent.config.KeystoreSecretCipher
import com.nuvio.app.agent.config.SecretStore
import com.nuvio.app.agent.config.SharedPrefsKeyValueStore
import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ConfirmationPresenter
import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.llm.AgentHttp
import com.nuvio.app.agent.llm.ChatMessage
import com.nuvio.app.agent.llm.LlmClientFactory
import com.nuvio.app.agent.runtime.AgentRunConfig
import com.nuvio.app.agent.runtime.AgentSession
import com.nuvio.app.agent.runtime.ChatItem
import com.nuvio.app.agent.runtime.PromptContext
import com.nuvio.app.agent.runtime.SystemPrompt
import com.nuvio.app.agent.tools.AgentMemory
import com.nuvio.app.agent.tools.AgentToolCatalog
import com.nuvio.app.agent.tools.describe
import com.nuvio.app.agent.web.WebSearch
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.library.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.util.UUID

data class PendingConfirmation(val request: ConfirmationRequest, val answer: CompletableDeferred<Boolean>)

@Serializable
data class SavedConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val items: List<ChatItem>,
)

/**
 * Process-wide entry point for the in-app agent: owns settings, the tool registry, the active
 * session, confirmations shown by the overlay, conversation history and the models.dev catalog.
 */
object AgentController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null

    lateinit var settings: AgentSettingsStore
        private set
    lateinit var registry: ToolRegistry
        private set
    lateinit var session: AgentSession
        private set
    lateinit var memory: AgentMemory
        private set

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _catalog = MutableStateFlow<ModelsCatalog?>(null)
    val catalog: StateFlow<ModelsCatalog?> = _catalog.asStateFlow()

    private val _chatOpen = MutableStateFlow(false)
    val chatOpen: StateFlow<Boolean> = _chatOpen.asStateFlow()

    private val _history = MutableStateFlow<List<SavedConversation>>(emptyList())
    val history: StateFlow<List<SavedConversation>> = _history.asStateFlow()

    private var conversationId: String = UUID.randomUUID().toString()

    val isInitialized: Boolean get() = appContext != null

    fun initialize(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        settings = AgentSettingsStore(
            prefs = SharedPrefsKeyValueStore(app, AgentSettingsStore.PREFS_FILE),
            secrets = SecretStore(SharedPrefsKeyValueStore(app, SecretStore.PREFS_FILE), KeystoreSecretCipher()),
        )
        memory = AgentMemory(SharedPrefsKeyValueStore(app, "nuvio_agent_memory"))
        val webSearch = WebSearch(AgentHttp.client, { settings.settings.value.search }, settings::searchSecret)
        registry = AgentToolCatalog.registerAll(
            ToolRegistry(ConfirmationPresenter { request -> askUser(request) }),
            AgentHttp.client,
            webSearch,
            memory,
        )
        session = AgentSession(scope, registry, onConversationChanged = ::persistConversation)
        scope.launch {
            loadConversations()
            _catalog.value = withContext(Dispatchers.IO) { loadCatalog(app) }
        }
    }

    fun openChat() { _chatOpen.value = true }
    fun closeChat() { _chatOpen.value = false }

    /** Sends a user message using the currently selected provider/model. */
    fun send(text: String): Boolean = session.send(text) { buildRunConfig() }

    fun stop() = session.cancel()

    fun newConversation() {
        session.cancel()
        persistConversation(session.history, session.state.value.items)
        conversationId = UUID.randomUUID().toString()
        session.clear()
    }

    fun openConversation(id: String) {
        val saved = _history.value.firstOrNull { it.id == id } ?: return
        session.cancel()
        conversationId = saved.id
        session.restore(saved.messages, saved.items)
    }

    fun deleteConversation(id: String) {
        conversationsDir()?.resolve("$id.json")?.delete()
        _history.value = _history.value.filterNot { it.id == id }
        if (id == conversationId) {
            conversationId = UUID.randomUUID().toString()
            session.clear()
        }
    }

    private fun buildRunConfig(): AgentRunConfig {
        val current = settings.settings.value
        val resolved = settings.resolve().getOrElse { throw IllegalStateException(it.message) }
        return AgentRunConfig(
            resolved = resolved,
            client = LlmClientFactory.create(resolved),
            systemPrompt = { SystemPrompt.build(registry.all, promptContext()) },
            maxSteps = current.maxSteps,
            temperature = current.temperature,
            maxOutputTokens = current.maxOutputTokens,
            parallelToolCalls = current.parallelToolCalls,
        )
    }

    private fun promptContext(): PromptContext {
        val nav = AgentBridge.navigation.value
        val auth = AuthRepository.state.value
        val player = AgentBridge.player.value?.state()
        return PromptContext(
            screen = nav?.currentRoute.describe().entries.joinToString(", ") { "${it.key}=${it.value}" },
            tab = nav?.selectedTab?.name?.lowercase(),
            nowPlaying = player?.let { "${it.title}${it.episodeTitle?.let { e -> " — $e" }.orEmpty()} at ${it.positionMs / 1000}s/${it.durationMs / 1000}s (${if (it.isPlaying) "playing" else "paused"})" },
            installedAddons = AddonRepository.uiState.value.addons.map { it.displayTitle + if (!it.enabled) " (disabled)" else "" },
            libraryCount = LibraryRepository.uiState.value.items.size,
            signedIn = when (auth) {
                is AuthState.Authenticated -> if (auth.isAnonymous) "guest" else "signed in"
                else -> "signed out"
            },
            memory = memory.all(),
        )
    }

    private suspend fun askUser(request: ConfirmationRequest): Boolean {
        val pending = PendingConfirmation(request, CompletableDeferred())
        _pendingConfirmation.value = pending
        return try {
            pending.answer.await()
        } finally {
            _pendingConfirmation.compareAndSet(pending, null)
        }
    }

    fun answerConfirmation(approved: Boolean) {
        _pendingConfirmation.value?.answer?.complete(approved)
    }

    // --- models.dev catalog ---------------------------------------------------------------

    private fun loadCatalog(context: Context): ModelsCatalog? {
        val cached = File(context.filesDir, "agent/models_dev_cache.json")
        if (cached.exists()) runCatching { return ModelsCatalog.parse(cached.readText()) }
        return runCatching {
            context.assets.open("agent/models_dev.json").bufferedReader().use { ModelsCatalog.parse(it.readText()) }
        }.getOrNull()
    }

    /** Downloads the latest catalog from models.dev (falling back to opencode's mirror). */
    suspend fun refreshCatalog(): Result<ModelsCatalog> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext Result.failure(IllegalStateException("not initialized"))
        var lastError: Throwable? = null
        for (url in listOf(ModelsCatalog.PRIMARY_URL, ModelsCatalog.MIRROR_URL)) {
            try {
                val body = AgentHttp.client.newCall(Request.Builder().url(url).header("User-Agent", "NuvioAgent").build()).execute().use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val parsed = ModelsCatalog.parse(body)
                if (parsed.providers.isEmpty()) error("empty catalog")
                File(context.filesDir, "agent").mkdirs()
                File(context.filesDir, "agent/models_dev_cache.json").writeText(body)
                val withSource = parsed.copy(source = url, generatedAt = java.time.Instant.now().toString())
                _catalog.value = withSource
                return@withContext Result.success(withSource)
            } catch (error: Throwable) {
                lastError = error
            }
        }
        Result.failure(lastError ?: IllegalStateException("refresh failed"))
    }

    /** Adds (or updates) a provider from the catalog with the given models and optional API key. */
    fun addCatalogProvider(providerId: String, modelIds: Collection<String>?, apiKey: String?) {
        val provider = _catalog.value?.provider(providerId) ?: error("Unknown provider $providerId")
        val existing = settings.provider(providerId)
        val config = ProviderMapping.toProviderConfig(provider, modelIds).let { fresh ->
            existing?.let { fresh.copy(headers = it.headers, variables = fresh.variables + it.variables.filterValues(String::isNotBlank), models = (it.models + fresh.models).distinctBy { m -> m.id }) } ?: fresh
        }
        settings.upsertProvider(config)
        if (!apiKey.isNullOrBlank()) settings.setSecret(providerId, com.nuvio.app.agent.llm.ProviderConfig.SECRET_API_KEY, apiKey)
    }

    // --- conversation persistence ---------------------------------------------------------

    private fun conversationsDir(): File? = appContext?.let { File(it.filesDir, "agent/conversations").apply { mkdirs() } }

    private fun persistConversation(messages: List<ChatMessage>, items: List<ChatItem>) {
        if (messages.isEmpty()) return
        val dir = conversationsDir() ?: return
        val title = (messages.firstOrNull { it is ChatMessage.User } as? ChatMessage.User)?.text?.take(60) ?: "Conversation"
        val saved = SavedConversation(conversationId, title, System.currentTimeMillis(), messages, items.takeLast(400))
        scope.launch(Dispatchers.IO) {
            runCatching { File(dir, "${saved.id}.json").writeText(AgentJson.encodeToString(SavedConversation.serializer(), saved)) }
        }
        _history.value = (listOf(saved) + _history.value.filterNot { it.id == saved.id }).sortedByDescending { it.updatedAt }.take(MAX_CONVERSATIONS)
    }

    private suspend fun loadConversations() {
        val dir = conversationsDir() ?: return
        val loaded = withContext(Dispatchers.IO) {
            dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { file ->
                runCatching { AgentJson.decodeFromString(SavedConversation.serializer(), file.readText()) }.getOrNull()
            }.sortedByDescending { it.updatedAt }
        }
        loaded.drop(MAX_CONVERSATIONS).forEach { conversationsDir()?.resolve("${it.id}.json")?.delete() }
        _history.value = loaded.take(MAX_CONVERSATIONS)
        loaded.firstOrNull()?.let { latest ->
            if (session.history.isEmpty()) {
                conversationId = latest.id
                session.restore(latest.messages, latest.items)
            }
        }
    }

    private const val MAX_CONVERSATIONS = 30
}
