package com.nuvio.app.agent.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

enum class ToolCategory(val title: String) {
    State("App state (read-only)"),
    Navigation("Navigation"),
    Browse("Search, catalogs and discovery"),
    Details("Titles, episodes and people"),
    Playback("Streams and starting playback"),
    Player("Player controls"),
    Library("Library and lists"),
    Progress("Watch progress and watched state"),
    Addons("Addon management"),
    Settings("Settings"),
    Account("Account and profiles"),
    Downloads("Downloads"),
    Collections("Collections"),
    Web("Web search and fetch"),
    AddonProtocol("Stremio addon protocol (direct)"),
    Utility("Utilities"),
    Memory("Agent memory"),
}

data class ConfirmationRequest(
    val title: String,
    val message: String,
    val confirmLabel: String = "Confirm",
    val destructive: Boolean = true,
)

/** Shows a confirmation in the UI and suspends until the user answers. */
fun interface ConfirmationPresenter {
    suspend fun confirm(request: ConfirmationRequest): Boolean
}

class ToolCallScope(
    val registry: ToolRegistry,
    val toolName: String,
)

class AgentTool(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val params: List<ToolParam>,
    /** App entry points this tool exposes, as "Owner.member" (checked by the coverage test). */
    val covers: Set<String>,
    /** Non-null for destructive/account-affecting tools: describes what the user must approve. */
    val confirmation: ((JsonObject) -> ConfirmationRequest?)?,
    val timeoutMs: Long,
    /** Run on the main thread (UI-bound state such as navigation and the player). */
    val mainThread: Boolean,
    /** Safe to run concurrently with other read-only calls (no UI or data mutation). */
    val readOnly: Boolean,
    val handler: suspend ToolCallScope.(JsonObject) -> JsonElement,
) {
    val schema: JsonObject = params.toJsonSchema()
    val requiresConfirmation: Boolean get() = confirmation != null

    init {
        require(NAME_PATTERN.matches(name)) { "Tool name '$name' must match ${NAME_PATTERN.pattern}" }
        require(description.isNotBlank()) { "Tool '$name' needs a description" }
    }

    companion object {
        val NAME_PATTERN = Regex("^[a-z][a-z0-9_]{1,63}$")
    }
}

class ToolBuilder(private val name: String) {
    var description: String = ""
    var category: ToolCategory = ToolCategory.Utility
    var timeoutMs: Long = 30_000
    var mainThread: Boolean = true
    var readOnly: Boolean = false
    private var params: List<ToolParam> = emptyList()
    private val covers = linkedSetOf<String>()
    private var confirmation: ((JsonObject) -> ConfirmationRequest?)? = null
    private var handler: (suspend ToolCallScope.(JsonObject) -> JsonElement)? = null

    fun params(block: ToolParamsBuilder.() -> Unit) {
        params = ToolParamsBuilder().apply(block).build()
    }

    fun covers(vararg members: String) {
        covers += members
    }

    fun confirm(block: (JsonObject) -> ConfirmationRequest?) {
        confirmation = block
    }

    fun run(block: suspend ToolCallScope.(JsonObject) -> JsonElement) {
        handler = block
    }

    fun build(): AgentTool = AgentTool(
        name = name,
        description = description.trimIndent().trim(),
        category = category,
        params = params,
        covers = covers,
        confirmation = confirmation,
        timeoutMs = timeoutMs,
        mainThread = mainThread,
        readOnly = readOnly,
        handler = handler ?: error("Tool '$name' has no run {} block"),
    )
}

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

data class ToolOutcome(
    val call: ToolCall,
    val content: String,
    val isError: Boolean,
    val durationMs: Long,
)

class ToolRegistry(
    private val confirmations: ConfirmationPresenter,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxResultChars: Int = 16_000,
) {
    private val tools = linkedMapOf<String, AgentTool>()

    val all: List<AgentTool> get() = tools.values.toList()

    operator fun get(name: String): AgentTool? = tools[name]

    fun register(tool: AgentTool) {
        require(tool.name !in tools) { "Duplicate tool name '${tool.name}'" }
        tools[tool.name] = tool
    }

    /** The one-line registration used by every feature: `registry.tool("name") { ... }`. */
    fun tool(name: String, block: ToolBuilder.() -> Unit) {
        register(ToolBuilder(name).apply(block).build())
    }

    suspend fun execute(call: ToolCall): ToolOutcome {
        val started = System.currentTimeMillis()
        fun outcome(content: JsonElement, isError: Boolean) = ToolOutcome(
            call = call,
            content = AgentJson.encodeToString(JsonElement.serializer(), content).truncateResult(),
            isError = isError,
            durationMs = System.currentTimeMillis() - started,
        )

        val tool = tools[call.name]
            ?: return outcome(errorJson("unknown_tool", "No tool named '${call.name}'. ${suggest(call.name)}"), true)

        val rawArgs = try {
            parseArguments(call.argumentsJson)
        } catch (error: ToolArgumentException) {
            return outcome(errorJson("invalid_arguments", error.message.orEmpty()), true)
        }
        val args = try {
            tool.params.validate(rawArgs)
        } catch (error: ToolArgumentException) {
            return outcome(errorJson("invalid_arguments", error.message.orEmpty(), tool), true)
        }

        tool.confirmation?.invoke(args)?.let { request ->
            val approved = withContext(mainDispatcher) { confirmations.confirm(request) }
            if (!approved) {
                return outcome(
                    errorJson("declined", "The user declined this action in the confirmation dialog. Do not retry unless they ask."),
                    true,
                )
            }
        }

        return try {
            val result = withTimeout(tool.timeoutMs) {
                withContext(if (tool.mainThread) mainDispatcher else backgroundDispatcher) {
                    ToolCallScope(this@ToolRegistry, tool.name).(tool.handler)(args)
                }
            }
            val isError = (result as? JsonObject)?.get("ok")?.toString() == "false"
            outcome(result, isError)
        } catch (error: TimeoutCancellationException) {
            outcome(errorJson("timeout", "Tool timed out after ${tool.timeoutMs / 1000}s"), true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ToolArgumentException) {
            outcome(errorJson("invalid_arguments", error.message.orEmpty(), tool), true)
        } catch (error: ToolFailure) {
            outcome(errorJson(error.code, error.message.orEmpty()), true)
        } catch (error: Throwable) {
            outcome(errorJson("exception", "${error::class.simpleName}: ${error.message.orEmpty()}".take(500)), true)
        }
    }

    private fun parseArguments(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return JsonObject(emptyMap())
        val element = try {
            AgentJson.parseToJsonElement(trimmed)
        } catch (error: Exception) {
            // Some models wrap arguments in markdown fences or emit trailing garbage.
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) {
                runCatching { AgentJson.parseToJsonElement(trimmed.substring(start, end + 1)) }.getOrNull()
            } else {
                null
            } ?: throw ToolArgumentException(
                "Arguments were not valid JSON (${error.message?.take(120)}). Send a single JSON object.",
            )
        }
        return when (element) {
            is JsonObject -> element
            is JsonNull -> JsonObject(emptyMap())
            else -> runCatching {
                // Double-encoded JSON string: "{\"query\":\"x\"}"
                AgentJson.parseToJsonElement((element as kotlinx.serialization.json.JsonPrimitive).content).jsonObject
            }.getOrElse { throw ToolArgumentException("Arguments must be a JSON object") }
        }
    }

    private fun suggest(name: String): String {
        val close = tools.keys.filter { it.contains(name, true) || name.contains(it, true) || it.commonPrefixWith(name).length >= 4 }
        return if (close.isEmpty()) "Use one of the tools you were given." else "Did you mean: ${close.take(5).joinToString()}?"
    }

    private fun String.truncateResult(): String =
        if (length <= maxResultChars) this
        else take(maxResultChars) + "…[truncated ${length - maxResultChars} chars; ask for fewer items or use paging]"

    companion object {
        fun errorJson(code: String, message: String, tool: AgentTool? = null): JsonObject = buildJsonObject {
            put("ok", false)
            put("error", message)
            put("code", code)
            if (tool != null) put("expected_parameters", tool.schema)
        }
    }
}
