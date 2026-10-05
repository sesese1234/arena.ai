package com.nuvio.app.agent.runtime

import com.nuvio.app.agent.core.ToolCall
import com.nuvio.app.agent.core.ToolOutcome
import com.nuvio.app.agent.core.ToolRegistry
import com.nuvio.app.agent.llm.ChatMessage
import com.nuvio.app.agent.llm.ChatRequest
import com.nuvio.app.agent.llm.LlmClient
import com.nuvio.app.agent.llm.LlmEvent
import com.nuvio.app.agent.llm.LlmException
import com.nuvio.app.agent.llm.ResolvedModel
import com.nuvio.app.agent.llm.ToolSpec
import com.nuvio.app.agent.llm.serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ChatItemKind { User, Assistant, ToolCall, Notice, Error }

/** What the chat UI renders. Tool calls carry their arguments and result for the collapsible card. */
@Serializable
data class ChatItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: ChatItemKind,
    val text: String = "",
    val reasoning: String = "",
    val toolName: String? = null,
    val toolCallId: String? = null,
    val arguments: String? = null,
    val result: String? = null,
    val isError: Boolean = false,
    val running: Boolean = false,
    val durationMs: Long? = null,
)

data class AgentUiState(
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val step: Int = 0,
    val modelLabel: String? = null,
)

/** Per-run knobs; [AgentSession] reads them fresh for every user message. */
data class AgentRunConfig(
    val resolved: ResolvedModel,
    val client: LlmClient,
    val systemPrompt: () -> String,
    val maxSteps: Int = 16,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val parallelToolCalls: Boolean = true,
)

/**
 * The agent loop: plan → call tools → read results → continue, until the model answers without
 * tool calls or the step limit is hit. Streams tokens into [state], retries transient provider
 * errors with exponential backoff, falls back to no-tools mode if a provider rejects tools, feeds
 * tool errors back to the model, and trims old context to fit the model's window.
 */
class AgentSession(
    private val scope: CoroutineScope,
    private val registry: ToolRegistry,
    private val contextManager: ContextManager = ContextManager(),
    private val retryDelaysMs: List<Long> = listOf(1_000, 3_000, 8_000),
    private val onConversationChanged: (List<ChatMessage>, List<ChatItem>) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private val messages = mutableListOf<ChatMessage>()
    private var job: Job? = null

    val history: List<ChatMessage> get() = messages.toList()

    fun restore(savedMessages: List<ChatMessage>, savedItems: List<ChatItem>) {
        job?.cancel()
        messages.clear()
        messages += savedMessages
        _state.value = AgentUiState(items = savedItems.map { it.copy(running = false) })
    }

    fun clear() = restore(emptyList(), emptyList())

    fun cancel() {
        job?.cancel(CancellationException("Stopped by user"))
    }

    /** Starts a run; returns false if one is already in progress. */
    fun send(text: String, config: () -> AgentRunConfig): Boolean {
        if (job?.isActive == true || text.isBlank()) return false
        addItem(ChatItem(kind = ChatItemKind.User, text = text.trim()))
        messages += ChatMessage.User(text.trim())
        job = scope.launch {
            _state.update { it.copy(running = true, step = 0) }
            try {
                val runConfig = config()
                _state.update { it.copy(modelLabel = "${runConfig.resolved.provider.name} · ${runConfig.resolved.model.name}") }
                runLoop(runConfig)
            } catch (error: CancellationException) {
                repairAfterCancel()
                addItem(ChatItem(kind = ChatItemKind.Notice, text = "Stopped."))
            } catch (error: LlmException) {
                addItem(ChatItem(kind = ChatItemKind.Error, text = error.message ?: "Provider error"))
            } catch (error: IllegalStateException) {
                addItem(ChatItem(kind = ChatItemKind.Error, text = error.message ?: "Agent is not configured"))
            } catch (error: Throwable) {
                addItem(ChatItem(kind = ChatItemKind.Error, text = "${error::class.simpleName}: ${error.message}"))
            } finally {
                _state.update { state -> state.copy(running = false, items = state.items.map { it.copy(running = false) }) }
                onConversationChanged(messages.toList(), _state.value.items)
            }
        }
        return true
    }

    private suspend fun runLoop(config: AgentRunConfig) {
        var toolsEnabled = config.resolved.model.supportsTools
        if (!toolsEnabled) {
            addItem(ChatItem(kind = ChatItemKind.Notice, text = "${config.resolved.model.name} does not support tool calling; the agent can only chat."))
        }
        for (step in 1..config.maxSteps) {
            _state.update { it.copy(step = step) }
            val system = config.systemPrompt()
            val tools = if (toolsEnabled) registry.all.map { ToolSpec(it.name, it.description, it.schema) } else emptyList()
            val budget = (config.resolved.model.contextLength * 0.75).toInt() -
                contextManager.estimateTokens(system) -
                tools.sumOf { contextManager.estimateTokens(it.description) + contextManager.estimateTokens(it.parameters.toString()) } -
                (config.maxOutputTokens ?: 4_096)
            val fitted = contextManager.fit(messages.toList(), budget.coerceAtLeast(4_000))
            if (fitted.droppedTurns > 0) {
                messages.clear()
                messages += fitted.messages
                addItem(ChatItem(kind = ChatItemKind.Notice, text = "Trimmed ${fitted.droppedTurns} older turn(s) to fit the context window."))
            }
            val request = ChatRequest(
                model = config.resolved.model.id,
                system = system,
                messages = fitted.messages,
                tools = tools,
                temperature = config.temperature?.takeIf { config.resolved.model.supportsTemperature }
                    ?: config.resolved.model.temperature,
                maxTokens = config.maxOutputTokens ?: config.resolved.model.maxOutputTokens,
                parallelToolCalls = config.parallelToolCalls,
            )

            val completed = try {
                streamWithRetry(config.client, request)
            } catch (error: LlmException) {
                if (error.toolsRejected && toolsEnabled) {
                    toolsEnabled = false
                    addItem(ChatItem(kind = ChatItemKind.Notice, text = "The provider rejected tool definitions; continuing without app control. (${error.message?.take(160)})"))
                    continue
                }
                throw error
            }

            messages += ChatMessage.Assistant(
                text = completed.text,
                toolCalls = completed.toolCalls.map { it.serializable() },
                reasoning = completed.reasoning,
                reasoningSignature = completed.reasoningSignature,
            )
            if (completed.toolCalls.isEmpty()) {
                if (completed.text.isBlank()) {
                    finishAssistantItem(if (completed.stopReason == "length" || completed.stopReason == "max_tokens") "(The response hit the output token limit.)" else "(No response.)")
                }
                return
            }
            executeTools(completed.toolCalls, config.parallelToolCalls)
        }
        addItem(
            ChatItem(
                kind = ChatItemKind.Notice,
                text = "Reached the step limit (${config.maxSteps}). Ask me to continue if the task isn't finished.",
            ),
        )
    }

    private suspend fun streamWithRetry(client: LlmClient, request: ChatRequest): LlmEvent.Completed {
        var attempt = 0
        while (true) {
            val assistantItemId = addItem(ChatItem(kind = ChatItemKind.Assistant, running = true))
            try {
                var completed: LlmEvent.Completed? = null
                client.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> updateItem(assistantItemId) { it.copy(text = it.text + event.text) }
                        is LlmEvent.ReasoningDelta -> updateItem(assistantItemId) { it.copy(reasoning = it.reasoning + event.text) }
                        is LlmEvent.ToolCallStarted -> Unit
                        is LlmEvent.Completed -> completed = event
                    }
                }
                val result = completed ?: throw LlmException("The provider closed the stream without a response.", retryable = true)
                updateItem(assistantItemId) { it.copy(text = result.text, running = false) }
                if (result.text.isBlank()) removeItem(assistantItemId)
                return result
            } catch (error: LlmException) {
                removeItem(assistantItemId)
                if (!error.retryable || attempt >= retryDelaysMs.size) throw error
                val wait = error.retryAfterMs?.coerceIn(500, 30_000) ?: retryDelaysMs[attempt]
                addItem(ChatItem(kind = ChatItemKind.Notice, text = "${error.message?.take(140)} — retrying in ${wait / 1000.0}s (attempt ${attempt + 2}/${retryDelaysMs.size + 1})"))
                delay(wait)
                attempt++
            }
        }
    }

    private suspend fun executeTools(calls: List<ToolCall>, parallel: Boolean) {
        val itemIds = calls.associate { call ->
            call.id to addItem(
                ChatItem(
                    kind = ChatItemKind.ToolCall,
                    toolName = call.name,
                    toolCallId = call.id,
                    arguments = call.argumentsJson,
                    running = true,
                ),
            )
        }
        val canParallel = parallel && calls.size > 1 && calls.all { registry[it.name]?.readOnly == true }
        val outcomes: List<ToolOutcome> = if (canParallel) {
            coroutineScope { calls.map { call -> async { registry.execute(call).also { publish(itemIds.getValue(call.id), it) } } }.awaitAll() }
        } else {
            calls.map { call -> registry.execute(call).also { publish(itemIds.getValue(call.id), it) } }
        }
        outcomes.forEach { outcome ->
            messages += ChatMessage.ToolResult(outcome.call.id, outcome.call.name, outcome.content, outcome.isError)
        }
    }

    private fun publish(itemId: String, outcome: ToolOutcome) {
        updateItem(itemId) {
            it.copy(result = outcome.content, isError = outcome.isError, running = false, durationMs = outcome.durationMs)
        }
    }

    /** After a cancel, drop a dangling assistant tool-call message so the history stays valid. */
    private fun repairAfterCancel() {
        val last = messages.lastOrNull()
        if (last is ChatMessage.Assistant && last.toolCalls.isNotEmpty()) {
            val answered = messages.filterIsInstance<ChatMessage.ToolResult>().map { it.callId }.toSet()
            if (last.toolCalls.any { it.id !in answered }) {
                last.toolCalls.filter { it.id !in answered }.forEach { call ->
                    messages += ChatMessage.ToolResult(call.id, call.name, """{"ok":false,"error":"Cancelled by the user"}""", isError = true)
                }
            }
        }
    }

    private fun finishAssistantItem(text: String) {
        addItem(ChatItem(kind = ChatItemKind.Notice, text = text))
    }

    private fun addItem(item: ChatItem): String {
        _state.update { it.copy(items = it.items + item) }
        return item.id
    }

    private fun updateItem(id: String, transform: (ChatItem) -> ChatItem) {
        _state.update { state -> state.copy(items = state.items.map { if (it.id == id) transform(it) else it }) }
    }

    private fun removeItem(id: String) {
        _state.update { state -> state.copy(items = state.items.filterNot { it.id == id }) }
    }
}
