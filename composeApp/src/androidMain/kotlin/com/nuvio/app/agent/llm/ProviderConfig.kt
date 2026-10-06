package com.nuvio.app.agent.llm

import kotlinx.serialization.Serializable

/** Wire protocol a provider speaks. Most models.dev providers are [OpenAiCompatible]. */
@Serializable
enum class ApiKind(val label: String) {
    OpenAiCompatible("OpenAI-compatible (Chat Completions)"),
    Anthropic("Anthropic Messages"),
    Gemini("Google Gemini"),
    Bedrock("AWS Bedrock (Converse, SigV4)"),
    AzureOpenAi("Azure OpenAI"),
    VertexGemini("Google Vertex AI (Gemini)"),
    VertexAnthropic("Google Vertex AI (Anthropic)"),
}

@Serializable
data class ModelConfig(
    val id: String,
    val name: String = id,
    val contextLength: Int = 128_000,
    val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val supportsTools: Boolean = true,
    val supportsTemperature: Boolean = true,
    val reasoning: Boolean = false,
    /** Per-model override (models.dev `provider.npm/api`), e.g. a gateway serving Claude natively. */
    val apiKindOverride: ApiKind? = null,
    val baseUrlOverride: String? = null,
    val custom: Boolean = false,
)

/**
 * A configured provider. Secrets (API keys, AWS secret, service-account JSON) are never stored
 * here — they live in [com.nuvio.app.agent.config.SecretStore] keyed by provider id.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ApiKind,
    val baseUrl: String,
    val headers: Map<String, String> = emptyMap(),
    /** Non-secret settings: AWS region, Azure resource/api-version, Vertex project/location, URL template vars. */
    val variables: Map<String, String> = emptyMap(),
    /** Names of secrets this provider needs, e.g. ["apiKey"] or ["awsAccessKeyId", "awsSecretAccessKey"]. */
    val secretNames: List<String> = listOf(SECRET_API_KEY),
    val models: List<ModelConfig> = emptyList(),
    val catalogId: String? = null,
    val docUrl: String? = null,
    val enabled: Boolean = true,
    val custom: Boolean = false,
) {
    fun resolvedBaseUrl(): String = expandTemplate(baseUrl, variables)

    companion object {
        const val SECRET_API_KEY = "apiKey"
        const val SECRET_AWS_ACCESS_KEY_ID = "awsAccessKeyId"
        const val SECRET_AWS_SECRET_ACCESS_KEY = "awsSecretAccessKey"
        const val SECRET_AWS_SESSION_TOKEN = "awsSessionToken"
        const val SECRET_GOOGLE_SERVICE_ACCOUNT = "googleServiceAccountJson"
        const val SECRET_GOOGLE_ACCESS_TOKEN = "googleAccessToken"

        const val VAR_AWS_REGION = "AWS_REGION"
        const val VAR_AZURE_RESOURCE = "AZURE_RESOURCE_NAME"
        const val VAR_AZURE_API_VERSION = "AZURE_API_VERSION"
        const val VAR_VERTEX_PROJECT = "GOOGLE_VERTEX_PROJECT"
        const val VAR_VERTEX_LOCATION = "GOOGLE_VERTEX_LOCATION"
    }
}

/** Replaces `${NAME}` placeholders (models.dev uses them for account ids and hosts). */
fun expandTemplate(template: String, variables: Map<String, String>): String =
    Regex("\\$\\{([A-Z0-9_]+)\\}").replace(template) { match ->
        variables[match.groupValues[1]]?.trim().orEmpty().ifEmpty { match.value }
    }

fun String.hasUnresolvedTemplate(): Boolean = Regex("\\$\\{[A-Z0-9_]+\\}").containsMatchIn(this)

/** The active selection used by the agent. */
@Serializable
data class ModelSelection(val providerId: String, val modelId: String)

/** Everything a client needs for one request: config, resolved model and decrypted secrets. */
data class ResolvedModel(
    val provider: ProviderConfig,
    val model: ModelConfig,
    val secrets: Map<String, String>,
) {
    val apiKind: ApiKind get() = model.apiKindOverride ?: provider.kind
    val baseUrl: String get() = expandTemplate(model.baseUrlOverride ?: provider.baseUrl, provider.variables).trimEnd('/')
    fun secret(name: String): String? = secrets[name]?.takeIf { it.isNotBlank() }
}
