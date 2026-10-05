package com.nuvio.app.agent.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

val AgentJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}

/** Thrown by argument accessors; the registry turns it into a structured tool error for the model. */
class ToolArgumentException(message: String) : IllegalArgumentException(message)

/** Thrown by tool implementations for expected failures (e.g. "nothing is playing"). */
class ToolFailure(message: String, val code: String = "failed") : RuntimeException(message)

fun JsonObject.string(name: String): String =
    optString(name) ?: throw ToolArgumentException("Missing required string argument '$name'")

fun JsonObject.optString(name: String): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw ToolArgumentException("Argument '$name' must be a string")
    return primitive.content.takeIf { it.isNotEmpty() }
}

fun JsonObject.int(name: String): Int =
    optInt(name) ?: throw ToolArgumentException("Missing required integer argument '$name'")

fun JsonObject.optInt(name: String): Int? = optLong(name)?.toInt()

fun JsonObject.optLong(name: String): Long? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw ToolArgumentException("Argument '$name' must be an integer")
    return primitive.longOrNull
        ?: primitive.doubleOrNull?.toLong()
        ?: primitive.content.trim().toLongOrNull()
        ?: throw ToolArgumentException("Argument '$name' must be an integer, got '${primitive.content}'")
}

fun JsonObject.optDouble(name: String): Double? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw ToolArgumentException("Argument '$name' must be a number")
    return primitive.doubleOrNull
        ?: primitive.content.trim().toDoubleOrNull()
        ?: throw ToolArgumentException("Argument '$name' must be a number, got '${primitive.content}'")
}

fun JsonObject.boolean(name: String): Boolean =
    optBoolean(name) ?: throw ToolArgumentException("Missing required boolean argument '$name'")

fun JsonObject.optBoolean(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: throw ToolArgumentException("Argument '$name' must be a boolean")
    return primitive.booleanOrNull
        ?: when (primitive.content.trim().lowercase()) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> throw ToolArgumentException("Argument '$name' must be true or false")
        }
}

fun JsonObject.optStringList(name: String): List<String>? {
    val value = this[name] ?: return null
    if (value is JsonNull) return null
    return when (value) {
        is JsonArray -> value.map { (it as? JsonPrimitive)?.content ?: it.toString() }
        is JsonPrimitive -> value.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        else -> throw ToolArgumentException("Argument '$name' must be an array of strings")
    }
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value.toJsonElement()) }
}

fun JsonObjectBuilder.putAny(key: String, value: Any?) {
    put(key, value.toJsonElement())
}

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Enum<*> -> JsonPrimitive(name)
    is Map<*, *> -> buildJsonObject {
        this@toJsonElement.forEach { (k, v) -> put(k.toString(), v.toJsonElement()) }
    }
    is Iterable<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    is Array<*> -> buildJsonArray { this@toJsonElement.forEach { add(it.toJsonElement()) } }
    else -> JsonPrimitive(toString())
}

fun okResult(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    put("ok", true)
    pairs.forEach { (key, value) -> put(key, value.toJsonElement()) }
}
