package com.nuvio.app.agent.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts/decrypts secret values. Production uses the Android Keystore; tests use a fake. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/** AES-256-GCM with a non-exportable key held by the Android Keystore. */
class KeystoreSecretCipher(private val alias: String = KEY_ALIAS) : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nuvio_agent_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Stores provider secrets (API keys etc.) encrypted at rest. Values are never logged; `toString`
 * is redacted. The backing preferences file is excluded from backup and device transfer
 * (`allowBackup=false` + `agent_data_extraction_rules.xml`).
 */
class SecretStore(
    private val prefs: KeyValueStore,
    private val cipher: SecretCipher,
) {
    fun get(scope: String, name: String): String? {
        val stored = prefs.getString(key(scope, name)) ?: return null
        return runCatching { cipher.decrypt(stored) }.getOrNull()
    }

    fun put(scope: String, name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.remove(key(scope, name))
        } else {
            prefs.putString(key(scope, name), cipher.encrypt(value.trim()))
        }
    }

    fun has(scope: String, name: String): Boolean = prefs.getString(key(scope, name)) != null

    fun all(scope: String, names: Collection<String>): Map<String, String> =
        names.mapNotNull { name -> get(scope, name)?.let { name to it } }.toMap()

    fun clearScope(scope: String) {
        prefs.keys().filter { it.startsWith("$scope/") }.forEach(prefs::remove)
    }

    override fun toString(): String = "SecretStore(<redacted>)"

    private fun key(scope: String, name: String) = "$scope/$name"

    companion object {
        const val PREFS_FILE = "nuvio_agent_secrets"
    }
}

/** Tiny key-value abstraction so stores can be unit-tested without Android. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

class SharedPrefsKeyValueStore(context: Context, name: String) : KeyValueStore {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun keys(): Set<String> = prefs.all.keys
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = linkedMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}
