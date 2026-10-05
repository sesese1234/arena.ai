package com.nuvio.app.agent

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.core.ConfirmationRequest
import com.nuvio.app.agent.core.ToolCall
import com.nuvio.app.agent.core.ToolFailure
import com.nuvio.app.agent.core.okResult
import com.nuvio.app.agent.core.optInt
import com.nuvio.app.agent.core.schemaParamNames
import com.nuvio.app.agent.core.schemaRequired
import com.nuvio.app.agent.core.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {
    private val confirmations = RecordingConfirmations()
    private val registry = testRegistry(confirmations).apply {
        tool("echo") {
            description = "Echo a value"
            params {
                string("text", "Text to echo", required = true)
                integer("times", "Repeat count", min = 1, max = 5)
                string("mode", "Mode", enum = listOf("loud", "quiet"))
                boolean("flag", "A flag")
            }
            run { args -> okResult("text" to args.string("text").repeat(args.optInt("times") ?: 1), "mode" to args["mode"]) }
        }
        tool("dangerous") {
            description = "Deletes something"
            confirm { ConfirmationRequest("Delete?", "Really delete?", "Delete") }
            run { okResult("deleted" to true) }
        }
        tool("boom") {
            description = "Always fails"
            run { throw IllegalStateException("kaput") }
        }
        tool("expected_failure") {
            description = "Fails cleanly"
            run { throw ToolFailure("nothing is playing", "no_player") }
        }
        tool("slow") {
            description = "Too slow"
            timeoutMs = 50
            run { delay(5_000); okResult() }
        }
        tool("big") {
            description = "Large result"
            run { okResult("data" to "x".repeat(50_000)) }
        }
    }

    private fun exec(name: String, args: String) = runBlocking { registry.execute(ToolCall("c1", name, args)) }
    private fun json(content: String): JsonObject = AgentJson.parseToJsonElement(content.substringBefore("…[truncated")).jsonObject

    @Test
    fun schemaIsJsonSchemaObjectWithRequiredAndEnums() {
        val schema = registry["echo"]!!.schema
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("text", "times", "mode", "flag"), schema.schemaParamNames())
        assertEquals(setOf("text"), schema.schemaRequired())
        val mode = schema["properties"]!!.jsonObject["mode"]!!.jsonObject
        assertEquals("[\"loud\",\"quiet\"]", mode["enum"].toString())
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun validCallRunsAndCoercesStringNumbers() {
        val out = exec("echo", """{"text":"ab","times":"2","mode":"LOUD"}""")
        assertFalse(out.isError, out.content)
        assertEquals("abab", json(out.content)["text"]!!.jsonPrimitive.content)
        assertEquals("loud", json(out.content)["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun missingRequiredArgumentIsReportedWithExpectedSchema() {
        val out = exec("echo", """{"times":1}""")
        assertTrue(out.isError)
        val body = json(out.content)
        assertEquals("invalid_arguments", body["code"]!!.jsonPrimitive.content)
        assertTrue(body["error"]!!.jsonPrimitive.content.contains("text"))
        assertTrue("expected_parameters" in body)
    }

    @Test
    fun unknownArgumentsEnumViolationsAndRangeAreRejected() {
        assertTrue(exec("echo", """{"text":"a","colour":"red"}""").content.contains("Unknown argument"))
        assertTrue(exec("echo", """{"text":"a","mode":"medium"}""").content.contains("must be one of"))
        assertTrue(exec("echo", """{"text":"a","times":9}""").content.contains("<= 5"))
    }

    @Test
    fun malformedJsonIsRecoveredOrReported() {
        // Fenced / trailing garbage is recovered.
        assertFalse(exec("echo", "```json\n{\"text\":\"hi\"}\n``` thanks").isError)
        // Double-encoded string arguments are accepted.
        assertFalse(exec("echo", "\"{\\\"text\\\":\\\"hi\\\"}\"").isError)
        // Garbage is reported so the model can retry.
        val bad = exec("echo", "{text: hi")
        assertTrue(bad.isError)
        assertTrue(bad.content.contains("not valid JSON"))
        // Empty arguments mean {}.
        assertTrue(exec("echo", "").content.contains("Missing required"))
    }

    @Test
    fun unknownToolSuggestsAlternatives() {
        val out = exec("ech", "{}")
        assertTrue(out.isError)
        assertTrue(out.content.contains("echo"))
    }

    @Test
    fun confirmationGatesDestructiveTools() {
        confirmations.answer = false
        val declined = exec("dangerous", "{}")
        assertTrue(declined.isError)
        assertTrue(declined.content.contains("declined"))
        assertEquals(1, confirmations.asked.size)
        confirmations.answer = true
        assertFalse(exec("dangerous", "{}").isError)
        assertEquals(2, confirmations.asked.size)
        // Non-destructive tools never ask.
        exec("echo", """{"text":"x"}""")
        assertEquals(2, confirmations.asked.size)
    }

    @Test
    fun exceptionsTimeoutsAndFailuresBecomeStructuredErrors() {
        val crash = exec("boom", "{}")
        assertTrue(crash.isError)
        assertTrue(crash.content.contains("kaput"))
        val failure = json(exec("expected_failure", "{}").content)
        assertEquals("no_player", failure["code"]!!.jsonPrimitive.content)
        val timeout = exec("slow", "{}")
        assertTrue(timeout.isError)
        assertTrue(timeout.content.contains("timed out"))
    }

    @Test
    fun largeResultsAreTruncated() {
        val out = exec("big", "{}")
        assertTrue(out.content.length < 17_000)
        assertTrue(out.content.contains("truncated"))
    }

    @Test
    fun duplicateAndInvalidNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { registry.tool("echo") { description = "dup"; run { okResult() } } }
        assertFailsWith<IllegalArgumentException> { registry.tool("Bad-Name") { description = "x"; run { okResult() } } }
    }
}
