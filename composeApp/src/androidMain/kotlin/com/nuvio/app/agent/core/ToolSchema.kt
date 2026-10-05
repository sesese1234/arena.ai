package com.nuvio.app.agent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One parameter of a tool, rendered into JSON Schema for the provider. */
data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val enum: List<String>? = null,
    val itemsType: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
)

class ToolParamsBuilder {
    private val params = mutableListOf<ToolParam>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) {
        params += ToolParam(name, "string", description, required, enum = enum)
    }

    fun integer(name: String, description: String, required: Boolean = false, min: Long? = null, max: Long? = null) {
        params += ToolParam(name, "integer", description, required, minimum = min?.toDouble(), maximum = max?.toDouble())
    }

    fun number(name: String, description: String, required: Boolean = false, min: Double? = null, max: Double? = null) {
        params += ToolParam(name, "number", description, required, minimum = min, maximum = max)
    }

    fun boolean(name: String, description: String, required: Boolean = false) {
        params += ToolParam(name, "boolean", description, required)
    }

    fun stringList(name: String, description: String, required: Boolean = false) {
        params += ToolParam(name, "array", description, required, itemsType = "string")
    }

    fun build(): List<ToolParam> = params.toList()
}

fun List<ToolParam>.toJsonSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        this@toJsonSchema.forEach { param ->
            put(param.name, buildJsonObject {
                put("type", param.type)
                put("description", param.description)
                param.enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
                param.itemsType?.let { itemType -> put("items", buildJsonObject { put("type", itemType) }) }
                param.minimum?.let { put("minimum", it) }
                param.maximum?.let { put("maximum", it) }
            })
        }
    })
    put("required", buildJsonArray { this@toJsonSchema.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
    put("additionalProperties", false)
}

/**
 * Validates and normalises model-supplied arguments against the declared params. Lenient where
 * models commonly slip (numbers sent as strings, "true" strings) and strict where it matters
 * (missing required params, unknown enum values). Returns the cleaned arguments.
 */
fun List<ToolParam>.validate(arguments: JsonObject): JsonObject {
    val known = associateBy { it.name }
    val unknown = arguments.keys.filter { it !in known }
    if (unknown.isNotEmpty()) {
        throw ToolArgumentException(
            "Unknown argument(s): ${unknown.joinToString()}. Allowed: ${known.keys.joinToString().ifEmpty { "none" }}",
        )
    }
    val missing = filter { it.required && (arguments[it.name] == null || arguments[it.name] is JsonNull) }
    if (missing.isNotEmpty()) {
        throw ToolArgumentException("Missing required argument(s): ${missing.joinToString { it.name }}")
    }
    return buildJsonObject {
        arguments.forEach { (key, raw) ->
            val param = known.getValue(key)
            if (raw is JsonNull) return@forEach
            put(key, coerce(param, raw))
        }
    }
}

private fun coerce(param: ToolParam, raw: JsonElement): JsonElement {
    fun fail(expected: String): Nothing =
        throw ToolArgumentException("Argument '${param.name}' must be $expected, got $raw")
    return when (param.type) {
        "string" -> {
            val value = (raw as? JsonPrimitive)?.content ?: fail("a string")
            val allowed = param.enum
            if (allowed != null) {
                val match = allowed.firstOrNull { it.equals(value, ignoreCase = true) }
                    ?: throw ToolArgumentException(
                        "Argument '${param.name}' must be one of ${allowed.joinToString()}, got '$value'",
                    )
                JsonPrimitive(match)
            } else {
                JsonPrimitive(value)
            }
        }
        "integer" -> {
            val primitive = raw as? JsonPrimitive ?: fail("an integer")
            val number = primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull() ?: fail("an integer")
            checkRange(param, number)
            JsonPrimitive(number.toLong())
        }
        "number" -> {
            val primitive = raw as? JsonPrimitive ?: fail("a number")
            val number = primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull() ?: fail("a number")
            checkRange(param, number)
            JsonPrimitive(number)
        }
        "boolean" -> {
            val primitive = raw as? JsonPrimitive ?: fail("a boolean")
            val value = primitive.booleanOrNull ?: when (primitive.content.lowercase()) {
                "true", "yes", "1" -> true
                "false", "no", "0" -> false
                else -> fail("a boolean")
            }
            JsonPrimitive(value)
        }
        "array" -> when (raw) {
            is JsonArray -> raw
            is JsonPrimitive -> JsonArray(raw.content.split(',').map { JsonPrimitive(it.trim()) }.filter { it.content.isNotEmpty() })
            else -> fail("an array")
        }
        else -> raw
    }
}

private fun checkRange(param: ToolParam, value: Double) {
    param.minimum?.let { min ->
        if (value < min) throw ToolArgumentException("Argument '${param.name}' must be >= ${min.prettyNumber()}")
    }
    param.maximum?.let { max ->
        if (value > max) throw ToolArgumentException("Argument '${param.name}' must be <= ${max.prettyNumber()}")
    }
}

private fun Double.prettyNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()

/** Reads the param list back from a JSON schema (used by tests and by remote tool specs). */
fun JsonObject.schemaParamNames(): Set<String> =
    this["properties"]?.jsonObject?.keys ?: emptySet()

fun JsonObject.schemaRequired(): Set<String> =
    this["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
