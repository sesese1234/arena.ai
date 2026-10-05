package com.nuvio.app.agent

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ToolCall
import com.nuvio.app.agent.llm.AnthropicClient
import com.nuvio.app.agent.llm.ApiKind
import com.nuvio.app.agent.llm.AwsEventStreamDecoder
import com.nuvio.app.agent.llm.BedrockClient
import com.nuvio.app.agent.llm.ChatMessage
import com.nuvio.app.agent.llm.ChatRequest
import com.nuvio.app.agent.llm.GeminiClient
import com.nuvio.app.agent.llm.LlmClient
import com.nuvio.app.agent.llm.LlmClientFactory
import com.nuvio.app.agent.llm.LlmEvent
import com.nuvio.app.agent.llm.LlmException
import com.nuvio.app.agent.llm.ModelConfig
import com.nuvio.app.agent.llm.OpenAiCompatibleClient
import com.nuvio.app.agent.llm.ProviderConfig
import com.nuvio.app.agent.llm.ResolvedModel
import com.nuvio.app.agent.llm.SigV4Signer
import com.nuvio.app.agent.llm.ToolSpec
import com.nuvio.app.agent.llm.awsUriEncode
import com.nuvio.app.agent.llm.sanitizeGeminiSchema
import com.nuvio.app.agent.llm.serializable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderClientsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val searchTool = ToolSpec(
        "search_titles",
        "Search catalog titles",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string"); put("description", "q") }) })
            put("additionalProperties", false)
            put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
        },
    )

    @BeforeTest fun start() { server = MockWebServer().apply { start() } }
    @AfterTest fun stop() { server.shutdown() }

    private fun resolved(kind: ApiKind, secrets: Map<String, String> = mapOf(ProviderConfig.SECRET_API_KEY to "test-key"), vars: Map<String, String> = emptyMap(), base: String = server.url("/v1").toString().trimEnd('/'), modelId: String = "test-model") =
        ResolvedModel(
            ProviderConfig(id = "p", name = "Test", kind = kind, baseUrl = base, variables = vars, headers = mapOf("X-Extra" to "1")),
            ModelConfig(id = modelId),
            secrets,
        )

    private fun request(messages: List<ChatMessage> = listOf(ChatMessage.User("Find The Matrix"))) =
        ChatRequest(model = "test-model", system = "You are a test.", messages = messages, tools = listOf(searchTool), temperature = 0.2, maxTokens = 500)

    private fun collect(client: LlmClient, req: ChatRequest = request()): List<LlmEvent> = runBlocking { client.stream(req).toList() }

    private fun sse(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)

    // ---- OpenAI-compatible (DeepSeek recordings) ------------------------------------------

    @Test
    fun openAiCompatibleParsesRecordedDeepSeekToolCallStream() {
        server.enqueue(sse(fixture("deepseek_stream_tool_call.sse")))
        val events = collect(OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http))
        val done = events.filterIsInstance<LlmEvent.Completed>().single()
        assertEquals("tool_calls", done.stopReason)
        val call = done.toolCalls.single()
        assertEquals("search_titles", call.name)
        assertTrue(call.id.startsWith("call_"))
        assertEquals("The Matrix", AgentJson.parseToJsonElement(call.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)
        assertNotNull(done.reasoning, "deepseek-flash streams reasoning_content")
        assertTrue(events.any { it is LlmEvent.ToolCallStarted })

        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer test-key", sent.getHeader("Authorization"))
        assertEquals("1", sent.getHeader("X-Extra"))
        val body = AgentJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("search_titles", body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"].toString())
    }

    @Test
    fun openAiCompatibleStreamsRecordedDeepSeekText() {
        server.enqueue(sse(fixture("deepseek_stream_text.sse")))
        val events = collect(OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http))
        val text = events.filterIsInstance<LlmEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello there friend", text)
        assertEquals(text, events.filterIsInstance<LlmEvent.Completed>().single().text)
    }

    @Test
    fun openAiCompatibleSerializesToolLoopWithReasoningEcho() {
        val client = OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http)
        val history = listOf(
            ChatMessage.User("Find The Matrix"),
            ChatMessage.Assistant("", listOf(ToolCall("call_1", "search_titles", """{"query":"The Matrix"}""").serializable()), reasoning = "think"),
            ChatMessage.ToolResult("call_1", "search_titles", """{"ok":true}"""),
        )
        val body = client.buildBody(request(history))
        val messages = body["messages"]!!.jsonArray
        val assistant = messages[2].jsonObject
        assertEquals("think", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        val tool = messages[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun azureUsesDeploymentUrlApiVersionAndApiKeyHeader() {
        server.enqueue(sse(fixture("deepseek_stream_text.sse")))
        val base = server.url("/openai").toString().trimEnd('/')
        val client = OpenAiCompatibleClient(resolved(ApiKind.AzureOpenAi, base = base, vars = mapOf(ProviderConfig.VAR_AZURE_API_VERSION to "2024-10-21"), modelId = "gpt-4o"), http)
        collect(client)
        val sent = server.takeRequest()
        assertEquals("/openai/deployments/gpt-4o/chat/completions?api-version=2024-10-21", sent.path)
        assertEquals("test-key", sent.getHeader("api-key"))
        assertNull(sent.getHeader("Authorization"))
    }

    @Test
    fun httpErrorsAreClassified() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
        val auth = assertFailsWith<LlmException> { collect(OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http)) }
        assertEquals(401, auth.statusCode)
        assertTrue(!auth.retryable && auth.message!!.contains("API key"))

        server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "2").setBody("slow down"))
        val limited = assertFailsWith<LlmException> { collect(OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http)) }
        assertTrue(limited.retryable)
        assertEquals(2000L, limited.retryAfterMs)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"This model does not support tools"}}"""))
        val tools = assertFailsWith<LlmException> { collect(OpenAiCompatibleClient(resolved(ApiKind.OpenAiCompatible), http)) }
        assertTrue(tools.toolsRejected)
    }

    @Test
    fun missingKeyIsReportedBeforeAnyRequest() {
        val error = assertFailsWith<LlmException> { LlmClientFactory.create(resolved(ApiKind.OpenAiCompatible, secrets = emptyMap())) }
        assertTrue(error.message!!.contains("API key"))
        // Local servers configured without a key requirement work keyless.
        val local = resolved(ApiKind.OpenAiCompatible, secrets = emptyMap()).let { it.copy(provider = it.provider.copy(secretNames = emptyList())) }
        assertTrue(LlmClientFactory.create(local) is OpenAiCompatibleClient)
    }

    // ---- Anthropic -----------------------------------------------------------------------

    @Test
    fun anthropicParsesThinkingTextAndToolUse() {
        server.enqueue(sse(fixture("anthropic_stream_tool_use.sse")))
        val events = collect(AnthropicClient(resolved(ApiKind.Anthropic), http))
        val done = events.filterIsInstance<LlmEvent.Completed>().single()
        assertEquals("Searching for The Matrix.", done.text)
        assertEquals("tool_use", done.stopReason)
        assertEquals("The user wants The Matrix.", done.reasoning)
        assertEquals("sig-abc", done.reasoningSignature)
        val call = done.toolCalls.single()
        assertEquals("toolu_01ABC", call.id)
        assertEquals("""{"query": "The Matrix"}""", call.argumentsJson)
        assertEquals(412, done.usage!!.inputTokens)

        val sent = server.takeRequest()
        assertEquals("/v1/messages", sent.path)
        assertEquals("test-key", sent.getHeader("x-api-key"))
        assertEquals(AnthropicClient.API_VERSION, sent.getHeader("anthropic-version"))
        val body = AgentJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("You are a test.", body["system"]!!.jsonPrimitive.content)
        assertEquals("search_titles", body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("input_schema" in body["tools"]!!.jsonArray[0].jsonObject)
    }

    @Test
    fun anthropicMergesToolResultsAndEchoesThinkingBlocks() {
        val client = AnthropicClient(resolved(ApiKind.Anthropic), http)
        val body = client.buildBody(
            request(
                listOf(
                    ChatMessage.User("hi"),
                    ChatMessage.Assistant("", listOf(ToolCall("t1", "a", "{}").serializable(), ToolCall("t2", "b", "{\"x\":1}").serializable()), reasoning = "r", reasoningSignature = "s"),
                    ChatMessage.ToolResult("t1", "a", "one"),
                    ChatMessage.ToolResult("t2", "b", "two", isError = true),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        val assistantBlocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("thinking", assistantBlocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", assistantBlocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        val results = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(2, results.size)
        assertEquals("true", results[1].jsonObject["is_error"].toString())
    }

    // ---- Gemini --------------------------------------------------------------------------

    @Test
    fun geminiParsesThoughtsTextAndFunctionCalls() {
        server.enqueue(sse(fixture("gemini_stream_function_call.sse")))
        val base = server.url("/v1beta").toString().trimEnd('/')
        val events = collect(GeminiClient(resolved(ApiKind.Gemini, base = base), http))
        val done = events.filterIsInstance<LlmEvent.Completed>().single()
        assertEquals("I'll search for it. ", done.text)
        assertEquals("Let me look that up", done.reasoning)
        assertEquals("gem-sig-1", done.reasoningSignature)
        val call = done.toolCalls.single()
        assertEquals("search_titles", call.name)
        assertEquals("The Matrix", AgentJson.parseToJsonElement(call.argumentsJson).jsonObject["query"]!!.jsonPrimitive.content)

        val sent = server.takeRequest()
        assertEquals("/v1beta/models/test-model:streamGenerateContent?alt=sse", sent.path)
        assertEquals("test-key", sent.getHeader("x-goog-api-key"))
        val body = AgentJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
        val declaration = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        assertTrue("additionalProperties" !in declaration["parameters"]!!.jsonObject, "Gemini rejects additionalProperties")
    }

    @Test
    fun geminiSchemaSanitizerDropsUnsupportedKeywordsRecursively() {
        val schema = AgentJson.parseToJsonElement(
            """{"type":"object","additionalProperties":false,"required":[],"properties":{"n":{"type":"integer","minimum":1,"maximum":3,"description":"d"}}}""",
        )
        val clean = sanitizeGeminiSchema(schema).jsonObject
        assertTrue("additionalProperties" !in clean && "required" !in clean)
        assertEquals(setOf("type", "description"), clean["properties"]!!.jsonObject["n"]!!.jsonObject.keys)
    }

    // ---- Bedrock -------------------------------------------------------------------------

    @Test
    fun bedrockDecodesEventStreamAndSignsRequests() {
        fun frame(type: String, payload: String) = AwsEventStreamDecoder.encode(
            mapOf(":event-type" to type, ":content-type" to "application/json", ":message-type" to "event"),
            payload.toByteArray(),
        )
        val stream = Buffer().apply {
            write(frame("messageStart", """{"role":"assistant"}"""))
            write(frame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"Looking it up"}}"""))
            write(frame("contentBlockStart", """{"contentBlockIndex":1,"start":{"toolUse":{"toolUseId":"tu-1","name":"search_titles"}}}"""))
            write(frame("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"{\"query\":"}}}"""))
            write(frame("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"toolUse":{"input":"\"The Matrix\"}"}}}"""))
            write(frame("messageStop", """{"stopReason":"tool_use"}"""))
            write(frame("metadata", """{"usage":{"inputTokens":10,"outputTokens":5}}"""))
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/vnd.amazon.eventstream").setBody(stream))
        val creds = mapOf(ProviderConfig.SECRET_AWS_ACCESS_KEY_ID to "AKIDEXAMPLE", ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY to "secret")
        val base = server.url("/").toString().trimEnd('/')
        val model = resolved(ApiKind.Bedrock, secrets = creds, vars = mapOf(ProviderConfig.VAR_AWS_REGION to "us-west-2"), base = base, modelId = "us.anthropic.claude-test-v1:0")
        val done = collect(BedrockClient(model, http), request().copy(model = "us.anthropic.claude-test-v1:0")).filterIsInstance<LlmEvent.Completed>().single()
        assertEquals("Looking it up", done.text)
        assertEquals("tool_use", done.stopReason)
        assertEquals("""{"query":"The Matrix"}""", done.toolCalls.single().argumentsJson)
        assertEquals(10, done.usage!!.inputTokens)

        val sent = server.takeRequest()
        assertEquals("/model/us.anthropic.claude-test-v1%3A0/converse-stream", sent.path)
        val auth = sent.getHeader("Authorization")!!
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), auth)
        assertTrue(auth.contains("/us-west-2/bedrock/aws4_request"))
        assertNotNull(sent.getHeader("x-amz-date"))
        val body = AgentJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
        assertEquals("search_titles", body["toolConfig"]!!.jsonObject["tools"]!!.jsonArray[0].jsonObject["toolSpec"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun bedrockReportsExceptionsFromTheStream() {
        val frame = AwsEventStreamDecoder.encode(
            mapOf(":exception-type" to "throttlingException", ":message-type" to "exception"),
            """{"message":"Too many requests"}""".toByteArray(),
        )
        server.enqueue(MockResponse().setBody(Buffer().write(frame)))
        val creds = mapOf(ProviderConfig.SECRET_API_KEY to "bedrock-api-key")
        val error = assertFailsWith<LlmException> {
            collect(BedrockClient(resolved(ApiKind.Bedrock, secrets = creds, base = server.url("/").toString().trimEnd('/')), http))
        }
        assertTrue(error.retryable && error.message!!.contains("Too many requests"))
        assertEquals("Bearer bedrock-api-key", server.takeRequest().getHeader("Authorization"))
    }

    /** AWS SigV4 test-suite vector "get-vanilla" (service=service, region=us-east-1). */
    @Test
    fun sigV4MatchesAwsTestSuiteVector() {
        val date = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse("20150830T123600Z")!!
        val headers = SigV4Signer.sign(
            method = "GET",
            url = "https://example.amazonaws.com/",
            body = ByteArray(0),
            region = "us-east-1",
            service = "service",
            accessKey = "AKIDEXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            sessionToken = null,
            now = date,
            signContentSha256 = false,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            headers["Authorization"],
        )
        assertEquals("abc%3A0", awsUriEncode("abc:0"))
        assertEquals("a%20b~c", awsUriEncode("a b~c"))
    }

    @Test
    fun eventStreamDecoderRejectsCorruptFrames() {
        val good = AwsEventStreamDecoder.encode(mapOf(":event-type" to "x"), "{}".toByteArray())
        good[good.size - 1] = (good[good.size - 1] + 1).toByte()
        assertFailsWith<LlmException> { AwsEventStreamDecoder(Buffer().write(good)).next() }
    }

    @Suppress("unused")
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content
}
