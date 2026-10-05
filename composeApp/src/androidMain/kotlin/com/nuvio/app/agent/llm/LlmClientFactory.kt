package com.nuvio.app.agent.llm

import okhttp3.OkHttpClient

object LlmClientFactory {
    fun create(resolved: ResolvedModel, http: OkHttpClient = AgentHttp.client): LlmClient {
        val missing = missingSecrets(resolved)
        if (missing.isNotEmpty()) {
            throw LlmException(
                "No ${missing.joinToString(" / ") { secretLabel(it) }} set for ${resolved.provider.name}. " +
                    "Open Agent settings → Providers to add it.",
            )
        }
        if (resolved.baseUrl.hasUnresolvedTemplate()) {
            throw LlmException("${resolved.provider.name}: fill in the URL variables in Agent settings (${resolved.baseUrl}).")
        }
        return when (resolved.apiKind) {
            ApiKind.OpenAiCompatible, ApiKind.AzureOpenAi -> OpenAiCompatibleClient(resolved, http)
            ApiKind.Anthropic -> AnthropicClient(resolved, http)
            ApiKind.Gemini -> GeminiClient(resolved, http)
            ApiKind.Bedrock -> BedrockClient(resolved, http)
            ApiKind.VertexGemini -> GeminiClient(resolved, http, GoogleAccessTokenProvider(resolved, http)::token)
            ApiKind.VertexAnthropic -> AnthropicClient(resolved, http, GoogleAccessTokenProvider(resolved, http)::token)
        }
    }

    /** Which required secrets are missing. Local servers (Ollama, LM Studio) need none. */
    fun missingSecrets(resolved: ResolvedModel): List<String> = when (resolved.apiKind) {
        ApiKind.Bedrock ->
            if (resolved.secret(ProviderConfig.SECRET_API_KEY) != null ||
                (resolved.secret(ProviderConfig.SECRET_AWS_ACCESS_KEY_ID) != null && resolved.secret(ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY) != null)
            ) emptyList() else listOf(ProviderConfig.SECRET_AWS_ACCESS_KEY_ID, ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY)
        ApiKind.VertexGemini, ApiKind.VertexAnthropic ->
            if (resolved.secret(ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT) != null || resolved.secret(ProviderConfig.SECRET_GOOGLE_ACCESS_TOKEN) != null) {
                emptyList()
            } else {
                listOf(ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT)
            }
        else -> if (ProviderConfig.SECRET_API_KEY in resolved.provider.secretNames && resolved.secret(ProviderConfig.SECRET_API_KEY) == null) {
            listOf(ProviderConfig.SECRET_API_KEY)
        } else {
            emptyList()
        }
    }

    fun secretLabel(name: String): String = when (name) {
        ProviderConfig.SECRET_API_KEY -> "API key"
        ProviderConfig.SECRET_AWS_ACCESS_KEY_ID -> "AWS access key id"
        ProviderConfig.SECRET_AWS_SECRET_ACCESS_KEY -> "AWS secret access key"
        ProviderConfig.SECRET_AWS_SESSION_TOKEN -> "AWS session token (optional)"
        ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT -> "service-account JSON key"
        ProviderConfig.SECRET_GOOGLE_ACCESS_TOKEN -> "OAuth access token"
        else -> name
    }
}
