package com.nuvio.app.agent.catalog

import com.nuvio.app.agent.core.AgentJson
import com.nuvio.app.agent.llm.ApiKind
import com.nuvio.app.agent.llm.ModelConfig
import com.nuvio.app.agent.llm.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CatalogModel(
    val id: String,
    val name: String,
    val family: String?,
    val toolCall: Boolean,
    val reasoning: Boolean,
    val temperature: Boolean,
    val contextLength: Int,
    val outputLimit: Int?,
    val releaseDate: String?,
    val status: String?,
    val npmOverride: String?,
    val apiOverride: String?,
)

data class CatalogProvider(
    val id: String,
    val name: String,
    val api: String?,
    val npm: String?,
    val env: List<String>,
    val doc: String?,
    val models: List<CatalogModel>,
)

/**
 * The models.dev provider/model catalog — the same list opencode uses (opencode fetches
 * `https://models.opencode.ai/api.json`, a mirror of `https://models.dev/api.json`). A trimmed
 * snapshot ships as an asset (`scripts/update-models-catalog.py`); users can refresh it in-app.
 */
data class ModelsCatalog(
    val providers: List<CatalogProvider>,
    val source: String?,
    val generatedAt: String?,
) {
    fun provider(id: String): CatalogProvider? = providers.firstOrNull { it.id == id }

    companion object {
        const val PRIMARY_URL = "https://models.dev/api.json"
        const val MIRROR_URL = "https://models.opencode.ai/api.json"

        /** Accepts both the bundled trimmed format and the raw models.dev api.json. */
        fun parse(text: String): ModelsCatalog {
            val root = AgentJson.parseToJsonElement(text).jsonObject
            val providersObject = (root["providers"] as? JsonObject) ?: root
            val providers = providersObject.mapNotNull { (id, element) ->
                val provider = element as? JsonObject ?: return@mapNotNull null
                if (provider["models"] !is JsonObject) return@mapNotNull null
                CatalogProvider(
                    id = provider.str("id") ?: id,
                    name = provider.str("name") ?: id,
                    api = provider.str("api"),
                    npm = provider.str("npm"),
                    env = (provider["env"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    doc = provider.str("doc"),
                    models = provider["models"]!!.jsonObject.mapNotNull { (modelId, modelElement) ->
                        val model = modelElement as? JsonObject ?: return@mapNotNull null
                        val limit = model["limit"] as? JsonObject
                        val override = model["provider"] as? JsonObject
                        CatalogModel(
                            id = model.str("id") ?: modelId,
                            name = model.str("name") ?: modelId,
                            family = model.str("family"),
                            toolCall = model.bool("tool_call") ?: false,
                            reasoning = model.bool("reasoning") ?: false,
                            temperature = model.bool("temperature") ?: true,
                            contextLength = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 128_000,
                            outputLimit = limit?.get("output")?.jsonPrimitive?.intOrNull,
                            releaseDate = model.str("release_date"),
                            status = model.str("status"),
                            npmOverride = override?.str("npm"),
                            apiOverride = override?.str("api"),
                        )
                    }.sortedWith(compareByDescending<CatalogModel> { it.releaseDate.orEmpty() }.thenBy { it.name }),
                )
            }.sortedBy { it.name.lowercase() }
            return ModelsCatalog(providers, root.str("source"), root.str("generatedAt"))
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    }
}

/** Maps models.dev `npm` packages (the AI SDK provider opencode would load) to our wire protocols. */
object ProviderMapping {
    fun apiKind(npm: String?): ApiKind = when (npm) {
        "@ai-sdk/anthropic" -> ApiKind.Anthropic
        "@ai-sdk/google" -> ApiKind.Gemini
        "@ai-sdk/amazon-bedrock" -> ApiKind.Bedrock
        "@ai-sdk/azure" -> ApiKind.AzureOpenAi
        "@ai-sdk/google-vertex" -> ApiKind.VertexGemini
        "@ai-sdk/google-vertex/anthropic" -> ApiKind.VertexAnthropic
        else -> ApiKind.OpenAiCompatible
    }

    /** Base URLs for catalog providers that don't publish an `api` field. */
    private val defaultBaseUrls = mapOf(
        "openai" to "https://api.openai.com/v1",
        "anthropic" to "https://api.anthropic.com/v1",
        "google" to "https://generativelanguage.googleapis.com/v1beta",
        "groq" to "https://api.groq.com/openai/v1",
        "deepinfra" to "https://api.deepinfra.com/v1/openai",
        "cerebras" to "https://api.cerebras.ai/v1",
        "cohere" to "https://api.cohere.ai/compatibility/v1",
        "venice" to "https://api.venice.ai/api/v1",
        "perplexity" to "https://api.perplexity.ai",
        "xai" to "https://api.x.ai/v1",
        "vercel" to "https://ai-gateway.vercel.sh/v1",
        "aihubmix" to "https://aihubmix.com/v1",
        "v0" to "https://api.v0.dev/v1",
        "mistral" to "https://api.mistral.ai/v1",
        "togetherai" to "https://api.together.xyz/v1",
        "azure" to "https://\${AZURE_RESOURCE_NAME}.openai.azure.com/openai",
        "azure-cognitive-services" to "https://\${AZURE_COGNITIVE_SERVICES_RESOURCE_NAME}.cognitiveservices.azure.com/openai",
        "cloudflare-ai-gateway" to "https://gateway.ai.cloudflare.com/v1/\${CLOUDFLARE_ACCOUNT_ID}/\${CLOUDFLARE_GATEWAY_ID}/compat",
        "amazon-bedrock" to "https://bedrock-runtime.\${AWS_REGION}.amazonaws.com",
        "google-vertex" to "",
        "google-vertex-anthropic" to "",
    )

    private val secretMarkers = listOf("KEY", "TOKEN", "SECRET", "PAT", "CREDENTIALS", "PASSWORD")

    fun isSecretEnv(name: String): Boolean = secretMarkers.any { name.uppercase().contains(it) }

    fun baseUrlFor(provider: CatalogProvider): String =
        provider.api?.trimEnd('/') ?: defaultBaseUrls[provider.id] ?: ""

    /** True when we know how to reach the provider without the user typing a base URL. */
    fun isReady(provider: CatalogProvider): Boolean =
        baseUrlFor(provider).isNotBlank() || apiKind(provider.npm) in setOf(ApiKind.VertexGemini, ApiKind.VertexAnthropic)

    fun toProviderConfig(provider: CatalogProvider, modelIds: Collection<String>? = null): ProviderConfig {
        val kind = apiKind(provider.npm)
        val secretNames = when (kind) {
            ApiKind.Bedrock -> listOf(
                ProviderConfig.SECRET_AWS_ACCESS_KEY_ID,
                ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY,
                ProviderConfig.SECRET_AWS_SESSION_TOKEN,
                ProviderConfig.SECRET_API_KEY,
            )
            ApiKind.VertexGemini, ApiKind.VertexAnthropic -> listOf(
                ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT,
                ProviderConfig.SECRET_GOOGLE_ACCESS_TOKEN,
            )
            else -> if (provider.env.any(::isSecretEnv) || provider.env.isEmpty()) listOf(ProviderConfig.SECRET_API_KEY) else emptyList()
        }
        val variables = provider.env.filterNot(::isSecretEnv).associateWith { "" }.toMutableMap()
        fun default(key: String, value: String) {
            if (variables[key].isNullOrBlank()) variables[key] = value
        }
        when (kind) {
            ApiKind.Bedrock -> default(ProviderConfig.VAR_AWS_REGION, "us-east-1")
            ApiKind.AzureOpenAi -> default(ProviderConfig.VAR_AZURE_API_VERSION, "2024-10-21")
            ApiKind.VertexGemini, ApiKind.VertexAnthropic -> default(ProviderConfig.VAR_VERTEX_LOCATION, "global")
            else -> Unit
        }
        val selected = provider.models.filter { modelIds == null || it.id in modelIds }
        return ProviderConfig(
            id = provider.id,
            name = provider.name,
            kind = kind,
            baseUrl = baseUrlFor(provider),
            variables = variables,
            secretNames = secretNames,
            models = selected.map { it.toModelConfig() },
            catalogId = provider.id,
            docUrl = provider.doc,
        )
    }

    fun CatalogModel.toModelConfig(): ModelConfig = ModelConfig(
        id = id,
        name = name,
        contextLength = contextLength,
        maxOutputTokens = outputLimit?.coerceAtMost(16_384),
        supportsTools = toolCall,
        supportsTemperature = temperature,
        reasoning = reasoning,
        apiKindOverride = npmOverride?.let(::apiKind),
        baseUrlOverride = apiOverride,
    )
}
