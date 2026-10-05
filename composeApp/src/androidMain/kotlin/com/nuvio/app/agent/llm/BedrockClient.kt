package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.EOFException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Bedrock Runtime `ConverseStream` client. Authenticates with SigV4 (access key + secret,
 * optional session token) or with a Bedrock API key (bearer token). Responses arrive as
 * `application/vnd.amazon.eventstream` binary frames, decoded by [AwsEventStreamDecoder].
 */
class BedrockClient(
    private val resolved: ResolvedModel,
    private val http: OkHttpClient = AgentHttp.client,
    private val clock: () -> Date = { Date() },
) : LlmClient {

    private val region: String
        get() = resolved.provider.variables[ProviderConfig.VAR_AWS_REGION]?.takeIf { it.isNotBlank() } ?: "us-east-1"

    override fun stream(request: ChatRequest): Flow<LlmEvent> {
        val providerName = resolved.provider.name
        val body = AgentJson.encodeToString(JsonElement.serializer(), buildBody(request)).toByteArray()
        val url = endpointUrl(request.model)
        val headers = signedHeaders(url, body)
        val builder = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return streamingCall(http, providerName, builder.build()) { response ->
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var signature: String? = null
            val toolCalls = ToolCallAccumulator()
            var stopReason: String? = null
            var usage: TokenUsage? = null
            val source = response.body?.source() ?: throw LlmException("$providerName returned an empty body")
            val decoder = AwsEventStreamDecoder(source)
            while (true) {
                currentCoroutineContext().ensureActive()
                val message = decoder.next() ?: break
                val messageType = message.headers[":message-type"]
                val eventType = message.headers[":event-type"] ?: message.headers[":exception-type"]
                val payload = runCatching { AgentJson.parseToJsonElement(message.payload.decodeToString()).jsonObject }
                    .getOrDefault(JsonObject(emptyMap()))
                if (messageType == "exception" || messageType == "error") {
                    val detail = payload["message"]?.jsonPrimitive?.contentOrNull ?: message.payload.decodeToString()
                    throw LlmException(
                        "$providerName $eventType: $detail",
                        retryable = eventType in setOf("throttlingException", "serviceUnavailableException", "internalServerException", "modelStreamErrorException"),
                    )
                }
                when (eventType) {
                    "contentBlockStart" -> {
                        val index = payload["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: 0
                        payload["start"]?.jsonObject?.get("toolUse")?.jsonObject?.let { toolUse ->
                            val id = toolUse["toolUseId"]?.jsonPrimitive?.contentOrNull
                            val name = toolUse["name"]?.jsonPrimitive?.contentOrNull
                            toolCalls.add(index, id, name, null)
                            emit(LlmEvent.ToolCallStarted(id ?: "tooluse_$index", name.orEmpty()))
                        }
                    }
                    "contentBlockDelta" -> {
                        val index = payload["contentBlockIndex"]?.jsonPrimitive?.intOrNull ?: 0
                        val delta = payload["delta"]?.jsonObject ?: continue
                        delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                            text.append(it)
                            emit(LlmEvent.TextDelta(it))
                        }
                        delta["toolUse"]?.jsonObject?.get("input")?.jsonPrimitive?.contentOrNull?.let {
                            toolCalls.add(index, null, null, it)
                        }
                        delta["reasoningContent"]?.jsonObject?.let { rc ->
                            rc["text"]?.jsonPrimitive?.contentOrNull?.let {
                                reasoning.append(it)
                                emit(LlmEvent.ReasoningDelta(it))
                            }
                            rc["signature"]?.jsonPrimitive?.contentOrNull?.let { signature = it }
                        }
                    }
                    "messageStop" -> stopReason = payload["stopReason"]?.jsonPrimitive?.contentOrNull
                    "metadata" -> payload["usage"]?.jsonObject?.let { u ->
                        usage = TokenUsage(
                            u["inputTokens"]?.jsonPrimitive?.intOrNull ?: 0,
                            u["outputTokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        )
                    }
                }
            }
            emit(LlmEvent.Completed(text.toString(), toolCalls.build("tooluse"), reasoning.toString().ifEmpty { null }, signature, stopReason, usage))
        }
    }

    internal fun endpointUrl(model: String): String {
        val base = resolved.model.baseUrlOverride
            ?: resolved.provider.baseUrl.takeIf { it.isNotBlank() && !it.contains("\${") }?.let { expandTemplate(it, resolved.provider.variables) }
            ?: "https://bedrock-runtime.$region.amazonaws.com"
        return "${base.trimEnd('/')}/model/${awsUriEncode(model)}/converse-stream"
    }

    internal fun signedHeaders(url: String, body: ByteArray): Map<String, String> {
        val extra = resolved.provider.headers
        resolved.secret(ProviderConfig.SECRET_API_KEY)?.let { bearer ->
            return mapOf("Authorization" to "Bearer $bearer", "Content-Type" to "application/json", "Accept" to "application/vnd.amazon.eventstream") + extra
        }
        val accessKey = resolved.secret(ProviderConfig.SECRET_AWS_ACCESS_KEY_ID)
            ?: throw LlmException("${resolved.provider.name}: add an AWS access key id + secret or a Bedrock API key in Agent settings.")
        val secretKey = resolved.secret(ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY)
            ?: throw LlmException("${resolved.provider.name}: AWS secret access key is missing.")
        val sessionToken = resolved.secret(ProviderConfig.SECRET_AWS_SESSION_TOKEN)
        return SigV4Signer.sign(
            method = "POST",
            url = url,
            body = body,
            region = region,
            service = "bedrock",
            accessKey = accessKey,
            secretKey = secretKey,
            sessionToken = sessionToken,
            now = clock(),
            extraHeaders = mapOf("content-type" to "application/json", "accept" to "application/vnd.amazon.eventstream"),
        ) + extra
    }

    internal fun buildBody(request: ChatRequest): JsonObject = buildJsonObject {
        if (request.system.isNotBlank()) {
            put("system", buildJsonArray { add(buildJsonObject { put("text", request.system) }) })
        }
        put("messages", buildMessages(request.messages))
        put("inferenceConfig", buildJsonObject {
            put("maxTokens", request.maxTokens ?: 4096)
            request.temperature?.let { put("temperature", it) }
        })
        if (request.tools.isNotEmpty()) {
            put("toolConfig", buildJsonObject {
                put("tools", buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("toolSpec", buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", buildJsonObject { put("json", tool.parameters) })
                            })
                        })
                    }
                })
            })
        }
    }

    private fun buildMessages(messages: List<ChatMessage>): JsonArray = buildJsonArray {
        var pending = mutableListOf<JsonObject>()
        fun flush(target: kotlinx.serialization.json.JsonArrayBuilder) {
            if (pending.isEmpty()) return
            target.add(buildJsonObject { put("role", "user"); put("content", JsonArray(pending.toList())) })
            pending = mutableListOf()
        }
        messages.forEach { message ->
            when (message) {
                is ChatMessage.ToolResult -> pending += buildJsonObject {
                    put("toolResult", buildJsonObject {
                        put("toolUseId", message.callId)
                        put("content", buildJsonArray { add(buildJsonObject { put("text", message.content) }) })
                        put("status", if (message.isError) "error" else "success")
                    })
                }
                is ChatMessage.User -> {
                    flush(this)
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray { add(buildJsonObject { put("text", message.text) }) })
                    })
                }
                is ChatMessage.Assistant -> {
                    flush(this)
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("content", buildJsonArray {
                            if (message.reasoning != null && message.reasoningSignature != null) {
                                add(buildJsonObject {
                                    put("reasoningContent", buildJsonObject {
                                        put("reasoningText", buildJsonObject {
                                            put("text", message.reasoning)
                                            put("signature", message.reasoningSignature)
                                        })
                                    })
                                })
                            }
                            if (message.text.isNotBlank()) add(buildJsonObject { put("text", message.text) })
                            message.toolCalls.forEach { call ->
                                add(buildJsonObject {
                                    put("toolUse", buildJsonObject {
                                        put("toolUseId", call.id)
                                        put("name", call.name)
                                        put("input", parseArgsObject(call.toToolCall()))
                                    })
                                })
                            }
                            if (message.text.isBlank() && message.toolCalls.isEmpty()) add(buildJsonObject { put("text", " ") })
                        })
                    })
                }
            }
        }
        flush(this)
    }
}

/** AWS Signature Version 4 for a single request with a fully buffered body. */
object SigV4Signer {
    fun sign(
        method: String,
        url: String,
        body: ByteArray,
        region: String,
        service: String,
        accessKey: String,
        secretKey: String,
        sessionToken: String?,
        now: Date,
        extraHeaders: Map<String, String> = emptyMap(),
        signContentSha256: Boolean = true,
    ): Map<String, String> {
        val parsed = java.net.URI(url)
        val amzDate = format("yyyyMMdd'T'HHmmss'Z'", now)
        val dateStamp = format("yyyyMMdd", now)
        val payloadHash = sha256Hex(body)
        val host = parsed.rawAuthority
        val headers = sortedMapOf<String, String>()
        headers["host"] = host
        headers["x-amz-date"] = amzDate
        if (signContentSha256) headers["x-amz-content-sha256"] = payloadHash
        sessionToken?.let { headers["x-amz-security-token"] = it }
        extraHeaders.forEach { (k, v) -> headers[k.lowercase()] = v.trim() }

        // Non-S3 services: each path segment is URI-encoded again on top of the request encoding.
        val canonicalUri = (parsed.rawPath ?: "/").ifEmpty { "/" }
            .split('/').joinToString("/") { awsUriEncode(it) }
        val canonicalQuery = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
            .map { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = headers.keys.joinToString(";")
        val canonicalRequest = listOf(method, canonicalUri, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash)
            .joinToString("\n")
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray())}"
        var key = hmac("AWS4$secretKey".toByteArray(), dateStamp)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hmac(key, stringToSign).toHex()
        val authorization = "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return buildMap {
            headers.filterKeys { it != "host" }.forEach { (k, v) -> put(k, v) }
            put("Authorization", authorization)
        }
    }

    internal fun canonicalRequestForTest(method: String, url: String, body: ByteArray, headers: Map<String, String>): String {
        val parsed = java.net.URI(url)
        val sorted = headers.mapKeys { it.key.lowercase() }.toSortedMap()
        val canonicalUri = parsed.rawPath.split('/').joinToString("/") { awsUriEncode(it) }
        return listOf(
            method, canonicalUri, parsed.rawQuery.orEmpty(),
            sorted.entries.joinToString("") { "${it.key}:${it.value}\n" },
            sorted.keys.joinToString(";"), sha256Hex(body),
        ).joinToString("\n")
    }

    private fun format(pattern: String, date: Date): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray())
        }

    internal fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** RFC 3986 encoding as AWS expects: unreserved characters kept, everything else %XX (upper-case). */
fun awsUriEncode(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder()
    for (byte in bytes) {
        val c = byte.toInt().toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
            out.append(c)
        } else {
            out.append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }
    return out.toString()
}

class AwsEventStreamMessage(val headers: Map<String, String>, val payload: ByteArray)

/** Decoder for `application/vnd.amazon.eventstream` frames (prelude, headers, payload, CRCs). */
class AwsEventStreamDecoder(private val source: BufferedSource) {
    fun next(): AwsEventStreamMessage? {
        if (source.exhausted()) return null
        val prelude = try {
            source.readByteArray(12)
        } catch (error: EOFException) {
            return null
        }
        val preludeBuffer = ByteBuffer.wrap(prelude)
        val totalLength = preludeBuffer.int
        val headersLength = preludeBuffer.int
        val preludeCrc = preludeBuffer.int.toLong() and 0xffffffffL
        if (crc32(prelude, 0, 8) != preludeCrc) throw LlmException("Bedrock event stream: prelude CRC mismatch")
        if (totalLength < 16 || headersLength < 0 || headersLength > totalLength - 16) {
            throw LlmException("Bedrock event stream: invalid frame lengths")
        }
        val rest = source.readByteArray((totalLength - 12).toLong())
        val messageCrc = ByteBuffer.wrap(rest, rest.size - 4, 4).int.toLong() and 0xffffffffL
        val crc = CRC32().apply { update(prelude); update(rest, 0, rest.size - 4) }.value
        if (crc != messageCrc) throw LlmException("Bedrock event stream: message CRC mismatch")
        val headers = parseHeaders(ByteBuffer.wrap(rest, 0, headersLength))
        val payload = rest.copyOfRange(headersLength, rest.size - 4)
        return AwsEventStreamMessage(headers, payload)
    }

    private fun parseHeaders(buffer: ByteBuffer): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (buffer.hasRemaining()) {
            val nameLength = buffer.get().toInt() and 0xff
            val name = ByteArray(nameLength).also { buffer.get(it) }.decodeToString()
            val value = when (val type = buffer.get().toInt()) {
                0 -> "true"
                1 -> "false"
                2 -> buffer.get().toString()
                3 -> buffer.short.toString()
                4 -> buffer.int.toString()
                5, 8 -> buffer.long.toString()
                6, 7 -> {
                    val length = buffer.short.toInt() and 0xffff
                    val bytes = ByteArray(length).also { buffer.get(it) }
                    if (type == 7) bytes.decodeToString() else java.util.Base64.getEncoder().encodeToString(bytes)
                }
                9 -> ByteArray(16).also { buffer.get(it) }.joinToString("") { "%02x".format(it) }
                else -> throw LlmException("Bedrock event stream: unknown header type $type")
            }
            headers[name] = value
        }
        return headers
    }

    companion object {
        fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
            CRC32().apply { update(bytes, offset, length) }.value

        /** Encodes a frame (used by tests and the mock server). */
        fun encode(headers: Map<String, String>, payload: ByteArray): ByteArray {
            val headerBytes = java.io.ByteArrayOutputStream().apply {
                headers.forEach { (name, value) ->
                    val n = name.toByteArray()
                    val v = value.toByteArray()
                    write(n.size)
                    write(n)
                    write(7)
                    write((v.size shr 8) and 0xff)
                    write(v.size and 0xff)
                    write(v)
                }
            }.toByteArray()
            val total = 12 + headerBytes.size + payload.size + 4
            val buffer = ByteBuffer.allocate(total)
            buffer.putInt(total)
            buffer.putInt(headerBytes.size)
            val preludeCrc = CRC32().apply { update(buffer.array(), 0, 8) }.value
            buffer.putInt(preludeCrc.toInt())
            buffer.put(headerBytes)
            buffer.put(payload)
            val crc = CRC32().apply { update(buffer.array(), 0, total - 4) }.value
            buffer.putInt(crc.toInt())
            return buffer.array()
        }
    }
}
