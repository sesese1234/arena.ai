package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit

object AgentHttp {
    /** Shared client. No logging interceptor: requests carry API keys. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val JSON = "application/json; charset=utf-8".toMediaType()
}

fun jsonPost(url: String, body: JsonElement, headers: Map<String, String>): Request {
    val builder = Request.Builder()
        .url(url)
        .post(AgentJson.encodeToString(JsonElement.serializer(), body).toRequestBody(AgentHttp.JSON))
    headers.forEach { (name, value) -> if (value.isNotEmpty()) builder.header(name, value) }
    return builder.build()
}

/**
 * Executes [request] and hands the open response to [block] on the IO dispatcher. Cancelling the
 * collecting coroutine cancels the HTTP call, so "Stop" in the chat aborts the stream immediately.
 */
fun <T> streamingCall(
    client: OkHttpClient,
    providerName: String,
    request: Request,
    block: suspend FlowCollector<T>.(Response) -> Unit,
): Flow<T> = flow {
    val call: Call = client.newCall(request)
    val job = currentCoroutineContext()[kotlinx.coroutines.Job]
    val handle = job?.invokeOnCompletion { if (it != null) call.cancel() }
    try {
        val response = try {
            call.execute()
        } catch (error: IOException) {
            if (call.isCanceled()) throw CancellationException("cancelled")
            throw LlmException("$providerName network error: ${error.message ?: error::class.simpleName}", retryable = true)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body.string() }.getOrDefault("")
                throw classifyHttpError(providerName, resp.code, body, resp.header("retry-after"))
            }
            try {
                block(resp)
            } catch (error: IOException) {
                if (call.isCanceled()) throw CancellationException("cancelled")
                throw LlmException("$providerName stream interrupted: ${error.message}", retryable = true)
            }
        }
    } finally {
        handle?.dispose()
    }
}.flowOn(Dispatchers.IO)

data class SseEvent(val event: String?, val data: String)

/** Minimal Server-Sent Events reader (handles multi-line data, comments and CRLF). */
suspend fun BufferedSource.forEachSseEvent(onEvent: suspend (SseEvent) -> Boolean) {
    var eventName: String? = null
    val data = StringBuilder()
    while (true) {
        currentCoroutineContext().ensureActive()
        val line = readUtf8Line() ?: break
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                val keepGoing = onEvent(SseEvent(eventName, data.toString()))
                data.clear()
                eventName = null
                if (!keepGoing) return
            }
            continue
        }
        if (line.startsWith(":")) continue
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }
    if (data.isNotEmpty()) onEvent(SseEvent(eventName, data.toString()))
}

/** Accumulates streamed tool-call fragments keyed by provider index. */
class ToolCallAccumulator {
    private class Partial(var id: String?, var name: String?, val args: StringBuilder = StringBuilder())

    private val partials = sortedMapOf<Int, Partial>()

    /** Returns true when this fragment started a new call (id/name first seen). */
    fun add(index: Int, id: String?, name: String?, argumentsDelta: String?): Boolean {
        val partial = partials.getOrPut(index) { Partial(null, null) }
        val isNew = partial.name == null && !name.isNullOrEmpty()
        if (!id.isNullOrEmpty()) partial.id = id
        if (partial.name == null && !name.isNullOrEmpty()) partial.name = name
        if (!argumentsDelta.isNullOrEmpty()) partial.args.append(argumentsDelta)
        return isNew
    }

    fun nameOf(index: Int): String? = partials[index]?.name
    fun idOf(index: Int): String? = partials[index]?.id

    fun build(idPrefix: String = "call"): List<com.nuvio.app.agent.core.ToolCall> =
        partials.entries.mapNotNull { (index, partial) ->
            val name = partial.name ?: return@mapNotNull null
            com.nuvio.app.agent.core.ToolCall(
                id = partial.id ?: "${idPrefix}_$index",
                name = name,
                argumentsJson = partial.args.toString().ifBlank { "{}" },
            )
        }
}
