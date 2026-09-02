package com.luckylca.autocrack.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class SecureLlmConfigStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(config: LlmProviderConfig) = saveProvider(config, makeActive = true)

    @Synchronized
    fun saveProvider(config: LlmProviderConfig, makeActive: Boolean = true) {
        val validated = config.validated()
        val current = loadCatalog()
        val providers = current.providers.filterNot { it.id == validated.id } + validated
        writeCatalog(
            LlmProviderCatalog(
                providers = providers,
                activeProviderId = if (makeActive) validated.id else current.activeProviderId,
            ).validated(),
        )
    }

    @Synchronized
    fun deleteProvider(id: String) {
        val current = loadCatalog()
        val providers = current.providers.filterNot { it.id == id }
        writeCatalog(
            LlmProviderCatalog(
                providers = providers,
                activeProviderId = current.activeProviderId.takeIf { it != id },
            ).validated(),
        )
    }

    @Synchronized
    fun setActiveProvider(id: String) {
        val current = loadCatalog()
        require(current.providers.any { it.id == id }) { "供应商不存在" }
        writeCatalog(current.copy(activeProviderId = id).validated())
    }

    @Synchronized
    fun load(): LlmProviderConfig? = loadCatalog().activeProvider

    @Synchronized
    fun loadCatalog(): LlmProviderCatalog {
        val json = decryptJson() ?: return LlmProviderCatalog()
        return runCatching {
            val catalog = LlmProviderCatalogJson.decode(json).validated()
            if (!json.has("providers")) writeCatalog(catalog)
            catalog
        }.getOrElse {
            clear()
            LlmProviderCatalog()
        }
    }

    private fun writeCatalog(catalog: LlmProviderCatalog) {
        val plaintext = LlmProviderCatalogJson.encode(catalog.validated())
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        preferences.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    private fun decryptJson(): JSONObject? {
        val ivText = preferences.getString(KEY_IV, null) ?: return null
        val encryptedText = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        return runCatching {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedText, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        }.getOrElse {
            clear()
            null
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun hasSavedConfig(): Boolean =
        preferences.contains(KEY_IV) && preferences.contains(KEY_CIPHERTEXT)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_llm_provider"
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
        const val KEY_ALIAS = "autocrack_llm_config_aes"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val GCM_TAG_BITS = 128
    }
}

internal object LlmProviderCatalogJson {
    private const val VERSION = 2
    private const val LEGACY_PROVIDER_ID = "migrated-default"

    fun encode(catalog: LlmProviderCatalog): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("activeProviderId", catalog.activeProviderId)
        .put(
            "providers",
            JSONArray().also { providers ->
                catalog.providers.forEach { providers.put(encodeProvider(it)) }
            },
        )

    fun decode(json: JSONObject): LlmProviderCatalog {
        val providersJson = json.optJSONArray("providers")
        if (providersJson == null) {
            val legacy = decodeProvider(
                json = json,
                fallbackId = LEGACY_PROVIDER_ID,
                fallbackName = "原有供应商",
            )
            return LlmProviderCatalog(listOf(legacy), legacy.id)
        }
        val providers = buildList {
            for (index in 0 until providersJson.length()) {
                add(decodeProvider(providersJson.getJSONObject(index)))
            }
        }
        return LlmProviderCatalog(
            providers = providers,
            activeProviderId = json.optString("activeProviderId").takeIf(String::isNotBlank),
        )
    }

    private fun encodeProvider(config: LlmProviderConfig): JSONObject = JSONObject()
        .put("id", config.id)
        .put("name", config.name)
        .put("baseUrl", config.baseUrl)
        .put("model", config.model)
        .put("apiKey", config.apiKey)
        .put("protocol", config.protocol.name)

    private fun decodeProvider(
        json: JSONObject,
        fallbackId: String = LlmProviderConfig.DEFAULT_PROVIDER_ID,
        fallbackName: String = "默认供应商",
    ): LlmProviderConfig {
        val baseUrl = json.getString("baseUrl")
        val protocol = json.optString("protocol")
            .takeIf(String::isNotBlank)
            ?.let { runCatching { LlmApiProtocol.valueOf(it) }.getOrNull() }
            ?: LlmEndpointNormalizer.protocol(baseUrl)
        return LlmProviderConfig(
            baseUrl = baseUrl,
            model = json.getString("model"),
            apiKey = json.getString("apiKey"),
            id = json.optString("id", fallbackId).ifBlank { fallbackId },
            name = json.optString("name", fallbackName).ifBlank { fallbackName },
            protocol = protocol,
        )
    }
}
