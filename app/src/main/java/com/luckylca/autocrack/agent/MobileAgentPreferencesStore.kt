package com.luckylca.autocrack.agent

import android.content.Context

enum class SystemWritePolicy {
    ASK,
    DENY,
    ALLOW,
}

data class MobileAgentPreferences(
    val customSystemPrompt: String = "",
    val contextCompressionEnabled: Boolean = true,
    val maxToolIterations: Int = 24,
    val dangerousOperationConfirmation: Boolean = true,
    val systemWritePolicy: SystemWritePolicy = SystemWritePolicy.ASK,
    val alwaysAllowedDangerousCategories: Set<String> = emptySet(),
) {
    fun validated(): MobileAgentPreferences = copy(
        customSystemPrompt = customSystemPrompt.trim().take(MAX_SYSTEM_PROMPT_CHARS),
        maxToolIterations = maxToolIterations.coerceIn(MIN_TOOL_ITERATIONS, MAX_TOOL_ITERATIONS),
        alwaysAllowedDangerousCategories = alwaysAllowedDangerousCategories
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet(),
    )

    companion object {
        const val MIN_TOOL_ITERATIONS = 1
        const val MAX_TOOL_ITERATIONS = 64
        const val MAX_SYSTEM_PROMPT_CHARS = 20_000
    }
}

class MobileAgentPreferencesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): MobileAgentPreferences = MobileAgentPreferences(
        customSystemPrompt = preferences.getString(KEY_SYSTEM_PROMPT, "").orEmpty(),
        contextCompressionEnabled = preferences.getBoolean(KEY_COMPRESSION, true),
        maxToolIterations = preferences.getInt(KEY_TOOL_ITERATIONS, 24),
        dangerousOperationConfirmation = preferences.getBoolean(KEY_DANGEROUS_CONFIRMATION, true),
        systemWritePolicy = runCatching {
            SystemWritePolicy.valueOf(preferences.getString(KEY_SYSTEM_WRITE_POLICY, SystemWritePolicy.ASK.name).orEmpty())
        }.getOrDefault(SystemWritePolicy.ASK),
        alwaysAllowedDangerousCategories = preferences.getStringSet(KEY_ALWAYS_ALLOWED, emptySet()).orEmpty().toSet(),
    ).validated()

    fun save(value: MobileAgentPreferences) {
        val normalized = value.validated()
        preferences.edit()
            .putString(KEY_SYSTEM_PROMPT, normalized.customSystemPrompt)
            .putBoolean(KEY_COMPRESSION, normalized.contextCompressionEnabled)
            .putInt(KEY_TOOL_ITERATIONS, normalized.maxToolIterations)
            .putBoolean(KEY_DANGEROUS_CONFIRMATION, normalized.dangerousOperationConfirmation)
            .putString(KEY_SYSTEM_WRITE_POLICY, normalized.systemWritePolicy.name)
            .putStringSet(KEY_ALWAYS_ALLOWED, normalized.alwaysAllowedDangerousCategories)
            .apply()
    }

    fun allowCategoryAlways(category: String) {
        val current = load()
        save(current.copy(alwaysAllowedDangerousCategories = current.alwaysAllowedDangerousCategories + category))
    }

    fun clearAlwaysAllowedCategories() {
        save(load().copy(alwaysAllowedDangerousCategories = emptySet()))
    }

    private companion object {
        const val PREFERENCES_NAME = "mobile_agent_preferences"
        const val KEY_SYSTEM_PROMPT = "custom_system_prompt"
        const val KEY_COMPRESSION = "context_compression_enabled"
        const val KEY_TOOL_ITERATIONS = "max_tool_iterations"
        const val KEY_DANGEROUS_CONFIRMATION = "dangerous_operation_confirmation"
        const val KEY_SYSTEM_WRITE_POLICY = "system_write_policy"
        const val KEY_ALWAYS_ALLOWED = "always_allowed_dangerous_categories"
    }
}
