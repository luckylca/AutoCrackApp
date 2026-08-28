package com.luckylca.autocrack.agent

import org.json.JSONObject

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
) {
    fun toOpenAiJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters),
        )
}

data class AgentToolExecutionRecord(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String,
)

data class LlmToolAgentAnswer(
    val model: String,
    val endpointHost: String,
    val content: String,
    val toolExecutions: List<AgentToolExecutionRecord>,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

interface AgentToolExecutor {
    val tools: List<AgentToolDefinition>
    suspend fun dispatch(toolName: String, arguments: JSONObject): String
    suspend fun closeSafely() = Unit
}

enum class AgentDynamicBackend {
    DEBUGGER,
    FRIDA,
}

class AgentToolSession(
    private val executors: List<AgentToolExecutor>,
) {
    private val owners: Map<String, AgentToolExecutor> = buildMap {
        executors.forEach { executor ->
            executor.tools.forEach { tool ->
                require(put(tool.name, executor) == null) { "Duplicate Agent tool name: ${tool.name}" }
            }
        }
    }
    private var dynamicBackend: AgentDynamicBackend? = null

    val tools: List<AgentToolDefinition> = executors.flatMap(AgentToolExecutor::tools)

    suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val requestedBackend = when {
            toolName.startsWith("debugger_") || toolName == "inspect_target" -> AgentDynamicBackend.DEBUGGER
            toolName.startsWith("frida_") -> AgentDynamicBackend.FRIDA
            else -> null
        }
        if (requestedBackend != null && isDynamicActivation(toolName)) {
            val active = dynamicBackend
            require(active == null || active == requestedBackend) {
                "Dynamic backend $active is already active; stop/detach it before using $requestedBackend"
            }
        }
        val owner = owners[toolName] ?: error("Unknown or unauthorized Agent tool: $toolName")
        return try {
            owner.dispatch(toolName, arguments).also {
                if (requestedBackend != null && isDynamicActivation(toolName)) {
                    dynamicBackend = requestedBackend
                }
            }
        } finally {
            if (toolName == "debugger_detach" || toolName == "frida_stop") {
                dynamicBackend = null
            }
        }
    }

    suspend fun closeSafely() {
        executors.asReversed().forEach { executor -> runCatching { executor.closeSafely() } }
        dynamicBackend = null
    }

    private fun isDynamicActivation(toolName: String): Boolean = when (toolName) {
        "debugger_attach", "frida_start", "frida_ping", "frida_modules", "frida_exports",
        "frida_java_classes", "frida_java_methods", "frida_net_detect_stack", "frida_tls_trace",
        "frida_network_hints", "frida_native_trace" -> true
        else -> false
    }
}

typealias AgentToolDispatcher = suspend (toolName: String, arguments: JSONObject) -> String

object AgentJsonSchema {
    fun emptyObject(): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
        .put("additionalProperties", false)

    fun objectSchema(
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", org.json.JSONArray(required))
        .put("additionalProperties", false)
}
