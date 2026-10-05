package com.nuvio.app.agent.llm

import com.nuvio.app.agent.core.AgentJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * OAuth access tokens for Vertex AI. Accepts either a pasted access token (`gcloud auth
 * print-access-token`) or a service-account JSON key, which is exchanged via the JWT-bearer flow.
 */
class GoogleAccessTokenProvider(
    private val resolved: ResolvedModel,
    private val http: OkHttpClient = AgentHttp.client,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: Pair<String, Long>? = null

    suspend fun token(): String {
        resolved.secret(ProviderConfig.SECRET_GOOGLE_ACCESS_TOKEN)?.let { return it.trim() }
        val serviceAccount = resolved.secret(ProviderConfig.SECRET_GOOGLE_SERVICE_ACCOUNT)
            ?: throw LlmException("${resolved.provider.name}: add a service-account JSON key or an access token in Agent settings.")
        return mutex.withLock {
            cached?.takeIf { it.second - 60_000 > clock() }?.first ?: exchange(serviceAccount).also { cached = it }.first
        }
    }

    private suspend fun exchange(serviceAccountJson: String): Pair<String, Long> = withContext(Dispatchers.IO) {
        val account = runCatching { AgentJson.parseToJsonElement(serviceAccountJson).jsonObject }
            .getOrElse { throw LlmException("Service-account key is not valid JSON") }
        val email = account["client_email"]?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("Service-account key has no client_email")
        val privateKey = account["private_key"]?.jsonPrimitive?.contentOrNull
            ?: throw LlmException("Service-account key has no private_key")
        val tokenUri = account["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token"
        val assertion = signJwt(email, privateKey, tokenUri, clock() / 1000)
        val request = Request.Builder()
            .url(tokenUri)
            .post(
                FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    .add("assertion", assertion)
                    .build(),
            )
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw classifyHttpError("Google OAuth", response.code, body, null)
            val json = AgentJson.parseToJsonElement(body).jsonObject
            val token = json["access_token"]?.jsonPrimitive?.contentOrNull ?: throw LlmException("Google OAuth returned no token")
            val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
            token to (clock() + expiresIn * 1000)
        }
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"

        internal fun signJwt(email: String, privateKeyPem: String, audience: String, nowSeconds: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = """{"alg":"RS256","typ":"JWT"}"""
            val claims = """{"iss":"$email","scope":"$SCOPE","aud":"$audience","iat":$nowSeconds,"exp":${nowSeconds + 3600}}"""
            val signingInput = encoder.encodeToString(header.toByteArray()) + "." + encoder.encodeToString(claims.toByteArray())
            val keyBytes = Base64.getMimeDecoder().decode(
                privateKeyPem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replace("\\n", "\n")
                    .trim(),
            )
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(signingInput.toByteArray())
                sign()
            }
            return signingInput + "." + encoder.encodeToString(signature)
        }
    }
}
