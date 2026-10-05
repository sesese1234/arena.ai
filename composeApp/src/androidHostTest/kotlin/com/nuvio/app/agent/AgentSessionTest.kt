package com.nuvio.app.agent

import com.nuvio.app.agent.core.ToolCall
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.string
import com.nuvio.app.agent.llm.ApiKind
import com.nuvio.app.agent.llm.ChatMessage
import com.nuvio.app.agent.llm.ChatRequest
import com.nuvio.app.agent.llm.LlmClient
import com.nuvio.app.agent.llm.LlmEvent
import com.nuvio.app.agent.llm.LlmException
import com.nuvio.app.agent.llm.ModelConfig
import com.nuvio.app.agent.llm.ProviderConfig
import com.nuvio.app.agent.llm.ResolvedModel
import com.nuvio.app.agent.runtime.AgentRunConfig
import com.nuvio.app.agent.runtime.AgentSession
import com.nuvio.app.agent.runtime.ChatItemKind
import com.nuvio.app.agent.runtime.ContextManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A scripted provider: each call to [stream] plays the next scripted turn and records the request. */
private class FakeLlm(private val turns: List<(ChatRequest) -> List<LlmEvent>>) : LlmClient {
    val requests = mutableListOf<ChatRequest>()
    override fun stream(request: ChatRequest): Flow<LlmEvent> = flow {
        requests += request
        val turn = turns.getOrNull(requests.size - 1) ?: error("unexpected extra LLM call #${requests.size}")
        turn(request).forEach { emit(it) }
    }
}

private fun text(t: String) = listOf(LlmEvent.TextDelta(t), LlmEvent.Completed(t, emptyList(), null, null, "stop", null))
private fun calls(vararg c: ToolCall) = listOf<LlmEvent>(LlmEvent.Completed("", c.toList(), "reasoning", null, "tool_calls", null))

class AgentSessionTest {
    private val confirmations = RecordingConfirmations()
    private val executed = mutableListOf<String>()
    private val concurrent = AtomicInteger(0)
    private var maxConcurrent = 0
    private val registry = testRegistry(confirmations).apply {
        tool("search_titles") {
            description = "Search titles by name"
            readOnly = true
            params { string("query", "q", required = true) }
            run { args ->
                executed += "search:${args.string("query")}"
                val now = concurrent.incrementAndGet(); maxConcurrent = maxOf(maxConcurrent, now)
                delay(50); concurrent.decrementAndGet()
                okResult("results" to listOf(mapOf("id" to "tt0133093", "type" to "movie", "name" to "The Matrix")))
            }
        }
        tool("open_title") {
            description = "Open a title's details"
            params { string("id", "id", required = true); string("type", "type", required = true) }
            run { args -> executed += "open:${args.string("id")}"; okResult("opened" to true) }
        }
        tool("remove_addon") {
            description = "Remove an addon (destructive)"
            params { string("addon", "addon", required = true) }
            confirm { com.nuvio.app.agent.core.ConfirmationRequest("Remove?", "Remove it", "Remove") }
            run { executed += "remove"; okResult("removed" to true) }
        }
    }
    private val resolved = ResolvedModel(ProviderConfig("p", "Fake", ApiKind.OpenAiCompatible, "http://x"), ModelConfig("m", contextLength = 32_000), emptyMap())

    private fun run(llm: FakeLlm, prompt: String, maxSteps: Int = 8, parallel: Boolean = true, contextManager: ContextManager = ContextManager()): AgentSession = runBlocking {
        val session = AgentSession(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), registry, contextManager, retryDelaysMs = listOf(1, 1))
        session.send(prompt) { AgentRunConfig(resolved, llm, { "system" }, maxSteps = maxSteps, parallelToolCalls = parallel) }
        withTimeout(10_000) { session.state.first { !it.running && it.items.isNotEmpty() } }
        session
    }

    @Test
    fun multiStepToolLoopReachesFinalAnswer() {
        val llm = FakeLlm(
            listOf(
                { calls(ToolCall("c1", "search_titles", """{"query":"The Matrix"}""")) },
                { req ->
                    val result = req.messages.last() as ChatMessage.ToolResult
                    assertTrue(result.content.contains("tt0133093"))
                    calls(ToolCall("c2", "open_title", """{"id":"tt0133093","type":"movie"}"""))
                },
                { text("Opened The Matrix (1999).") },
            ),
        )
        val session = run(llm, "Find The Matrix and open its details")
        assertEquals(listOf("search:The Matrix", "open:tt0133093"), executed)
        assertEquals(3, llm.requests.size)
        val items = session.state.value.items
        assertEquals(2, items.count { it.kind == ChatItemKind.ToolCall && !it.isError })
        assertEquals("Opened The Matrix (1999).", items.last { it.kind == ChatItemKind.Assistant }.text)
        // History is a valid tool-call protocol: assistant(tool_calls) followed by tool results.
        val history = session.history
        assertTrue(history[1] is ChatMessage.Assistant && (history[1] as ChatMessage.Assistant).reasoning == "reasoning")
        assertTrue(history[2] is ChatMessage.ToolResult)
        // Tools are sent to the provider with JSON schemas.
        assertTrue(llm.requests.first().tools.any { it.name == "search_titles" && "properties" in it.parameters })
    }

    @Test
    fun readOnlyCallsRunInParallelMutatingCallsSequentially() {
        val llm = FakeLlm(
            listOf(
                { calls(ToolCall("a", "search_titles", """{"query":"A"}"""), ToolCall("b", "search_titles", """{"query":"B"}"""), ToolCall("c", "search_titles", """{"query":"C"}""")) },
                { text("done") },
            ),
        )
        run(llm, "search three")
        assertEquals(3, executed.size)
        assertTrue(maxConcurrent >= 2, "read-only calls should overlap, max was $maxConcurrent")
    }

    @Test
    fun toolErrorsAreFedBackSoTheModelCanRecover() {
        val llm = FakeLlm(
            listOf(
                { calls(ToolCall("c1", "open_title", """{"id":"tt1"}""")) }, // missing "type"
                { req ->
                    val result = req.messages.last() as ChatMessage.ToolResult
                    assertTrue(result.isError && result.content.contains("type"))
                    calls(ToolCall("c2", "open_title", """{"id":"tt1","type":"movie"}"""))
                },
                { text("fixed") },
            ),
        )
        run(llm, "open tt1")
        assertEquals(listOf("open:tt1"), executed)
    }

    @Test
    fun transientProviderErrorsAreRetried() {
        val attempts = AtomicInteger(0)
        val llm = object : LlmClient {
            override fun stream(request: ChatRequest) = flow {
                if (attempts.incrementAndGet() < 3) throw LlmException("HTTP 503", statusCode = 503, retryable = true)
                text("recovered").forEach { emit(it) }
            }
        }
        val session = runBlocking {
            val s = AgentSession(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), registry, retryDelaysMs = listOf(1, 1, 1))
            s.send("hi") { AgentRunConfig(resolved, llm, { "system" }) }
            withTimeout(10_000) { s.state.first { !it.running && it.items.size > 1 } }
            s
        }
        assertEquals(3, attempts.get())
        assertEquals("recovered", session.state.value.items.last { it.kind == ChatItemKind.Assistant }.text)
        assertTrue(session.state.value.items.any { it.kind == ChatItemKind.Notice && it.text.contains("retrying") })
    }

    @Test
    fun providerRejectingToolsFallsBackToChat() {
        val llm = FakeLlm(
            listOf(
                { throw LlmException("tools not supported", statusCode = 400, toolsRejected = true) },
                { req -> assertTrue(req.tools.isEmpty()); text("I can only chat with this model.") },
            ),
        )
        val session = run(llm, "hello")
        assertTrue(session.state.value.items.any { it.kind == ChatItemKind.Notice && it.text.contains("rejected tool") })
    }

    @Test
    fun nonRetryableErrorsSurfaceInTheChat() {
        val llm = FakeLlm(listOf({ throw LlmException("Test rejected the API key (HTTP 401)", statusCode = 401) }))
        val session = run(llm, "hello")
        assertTrue(session.state.value.items.last().let { it.kind == ChatItemKind.Error && it.text.contains("401") })
    }

    @Test
    fun stepLimitStopsRunawayLoops() {
        val llm = FakeLlm(List(3) { { calls(ToolCall("c$it", "search_titles", """{"query":"x"}""")) } })
        val session = run(llm, "loop", maxSteps = 3)
        assertEquals(3, llm.requests.size)
        assertTrue(session.state.value.items.last().text.contains("step limit"))
    }

    @Test
    fun declinedConfirmationIsReportedToTheModel() {
        confirmations.answer = false
        val llm = FakeLlm(
            listOf(
                { calls(ToolCall("c1", "remove_addon", """{"addon":"Cinemeta"}""")) },
                { req -> assertTrue((req.messages.last() as ChatMessage.ToolResult).content.contains("declined")); text("OK, kept it.") },
            ),
        )
        run(llm, "remove cinemeta")
        assertTrue("remove" !in executed)
        assertEquals(1, confirmations.asked.size)
    }

    @Test
    fun cancellationStopsTheRunAndKeepsHistoryValid() = runBlocking {
        val llm = object : LlmClient {
            override fun stream(request: ChatRequest) = flow<LlmEvent> {
                emit(LlmEvent.TextDelta("thinking"))
                delay(10_000)
            }
        }
        val session = AgentSession(CoroutineScope(SupervisorJob() + Dispatchers.Default), registry)
        session.send("slow") { AgentRunConfig(resolved, llm, { "system" }) }
        withTimeout(5_000) { session.state.first { state -> state.items.any { it.text == "thinking" } } }
        session.cancel()
        withTimeout(5_000) { session.state.first { !it.running } }
        assertTrue(session.state.value.items.any { it.kind == ChatItemKind.Notice && it.text.contains("Stopped") })
        assertTrue(session.history.last() is ChatMessage.User)
    }

    @Test
    fun oldTurnsAreTrimmedToFitTheContextWindow() {
        val tiny = ContextManager(charsPerToken = 1.0)
        val long = "x".repeat(3_000)
        val history = buildList {
            repeat(6) { i ->
                add(ChatMessage.User("question $i"))
                add(ChatMessage.Assistant("", listOf(com.nuvio.app.agent.llm.SerializableToolCall("t$i", "search_titles", "{}"))))
                add(ChatMessage.ToolResult("t$i", "search_titles", long))
                add(ChatMessage.Assistant("answer $i"))
            }
        }
        val fitted = tiny.fit(history, budgetTokens = 5_000)
        assertTrue(fitted.trimmed)
        assertTrue(tiny.estimateTokens(fitted.messages) <= 5_000)
        assertTrue((fitted.messages.first() as ChatMessage.User).text.startsWith(ContextManager.SUMMARY_PREFIX))
        // Tool calls and results stay paired after trimming.
        val callIds = fitted.messages.filterIsInstance<ChatMessage.Assistant>().flatMap { it.toolCalls.map { c -> c.id } }.toSet()
        val resultIds = fitted.messages.filterIsInstance<ChatMessage.ToolResult>().map { it.callId }.toSet()
        assertEquals(callIds, resultIds)
        assertTrue((fitted.messages.last() as ChatMessage.Assistant).text == "answer 5")
    }
}
