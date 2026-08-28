package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.HostFridaAuthorization
import com.luckylca.autocrack.runtime.HostFridaClientOperation
import com.luckylca.autocrack.runtime.HostFridaOperationResult
import com.luckylca.autocrack.runtime.HostFridaSessionManager
import com.luckylca.autocrack.runtime.HostFridaSessionSnapshot
import org.json.JSONObject

/** Agent-facing Frida tools bound permanently to one user-authorized package/PID. */
class AgentFridaToolExecutor private constructor(
    private val packageName: String,
    private val pid: Int,
    private val manager: HostFridaSessionManager,
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()
    private var ownsSession = false

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_START -> sessionJson(start())
            TOOL_STATUS -> manager.refresh()?.let(::sessionJson) ?: JSONObject().put("active", false)
            TOOL_PING -> operationJson(manager.execute(HostFridaClientOperation.Ping))
            TOOL_MODULES -> operationJson(
                manager.execute(HostFridaClientOperation.Modules(arguments.optInt("max_count", 128).coerceIn(1, 512))),
            )
            TOOL_EXPORTS -> operationJson(
                manager.execute(
                    HostFridaClientOperation.Exports(
                        module = arguments.requireString("module"),
                        query = arguments.optString("query").take(512),
                        maxCount = arguments.optInt("max_count", 128).coerceIn(1, 512),
                    ),
                ),
            )
            TOOL_JAVA_CLASSES -> operationJson(
                manager.execute(
                    HostFridaClientOperation.JavaClasses(
                        query = arguments.optString("query").take(512),
                        maxCount = arguments.optInt("max_count", 128).coerceIn(1, 512),
                    ),
                ),
            )
            TOOL_JAVA_METHODS -> operationJson(
                manager.execute(
                    HostFridaClientOperation.JavaMethods(
                        className = arguments.requireString("class_name"),
                        maxCount = arguments.optInt("max_count", 128).coerceIn(1, 512),
                    ),
                ),
            )
            TOOL_NET_DETECT_STACK -> operationJson(
                manager.execute(
                    HostFridaClientOperation.NetDetectStack(
                        maxCount = arguments.optInt("max_count", 64).coerceIn(1, 128),
                    ),
                ),
            )
            TOOL_TLS_TRACE -> operationJson(
                manager.execute(
                    HostFridaClientOperation.TlsTrace(
                        durationMillis = arguments.optInt("duration_ms", 1_000).coerceIn(50, 5_000),
                        maxEvents = arguments.optInt("max_events", 64).coerceIn(1, 128),
                        maxBytesPerEvent = arguments.optInt("max_bytes_per_event", 256).coerceIn(16, 1_024),
                    ),
                ),
            )
            TOOL_NETWORK_HINTS -> operationJson(
                manager.execute(
                    HostFridaClientOperation.NetworkHints(
                        maxCount = arguments.optInt("max_count", 64).coerceIn(1, 128),
                    ),
                ),
            )
            TOOL_NATIVE_TRACE -> operationJson(
                manager.execute(
                    HostFridaClientOperation.NativeTrace(
                        module = arguments.requireString("module"),
                        offset = arguments.requireString("offset"),
                        durationMillis = arguments.optInt("duration_ms", 1_000).coerceIn(50, 5_000),
                        maxEvents = arguments.optInt("max_events", 64).coerceIn(1, 128),
                    ),
                ),
            )
            TOOL_STOP -> {
                val stopped = if (ownsSession) manager.stop() else null
                ownsSession = false
                stopped?.let(::sessionJson) ?: JSONObject().put("active", false)
            }
            else -> error("Unknown or unauthorized Agent Frida tool: $toolName")
        }
        return result.put("ok", true).put("tool", toolName).toString()
    }

    override suspend fun closeSafely() {
        if (!ownsSession) return
        runCatching { manager.stop() }
        ownsSession = false
    }

    private suspend fun start(): HostFridaSessionSnapshot {
        val current = manager.refresh()
        if (current != null && current.running) {
            require(ownsSession && current.packageName == packageName && current.pid == pid) {
                "A Frida session already exists and is not owned by this Agent run"
            }
            require(current.helperVerified && current.serverReadyForClient && current.failure == null) {
                current.failure ?: "Existing Frida helper is not verified or ready"
            }
            return current
        }
        return manager.start(
            packageName = packageName,
            pid = pid,
            authorizationPhrase = HostFridaAuthorization.expected(packageName, pid),
        ).also { ownsSession = true }
    }

    private fun sessionJson(snapshot: HostFridaSessionSnapshot): JSONObject = JSONObject()
        .put("active", snapshot.running)
        .put("packageName", snapshot.packageName)
        .put("pid", snapshot.pid)
        .put("endpoint", "127.0.0.1:${snapshot.port}")
        .put("helperPid", snapshot.helperPid ?: JSONObject.NULL)
        .put("helperVerified", snapshot.helperVerified)
        .put("serverReadyForClient", snapshot.serverReadyForClient)
        .put("targetTracerPid", snapshot.targetTracerPid ?: JSONObject.NULL)
        .put("operationCount", snapshot.operationCount)
        .put("failure", snapshot.failure ?: JSONObject.NULL)

    private fun operationJson(result: HostFridaOperationResult): JSONObject {
        require(result.succeeded) {
            result.failure ?: "Frida operation ${result.operation} failed with exit=${result.exitCode}"
        }
        return JSONObject()
            .put("operation", result.operation)
            .put("succeeded", true)
            .put("exitCode", result.exitCode ?: JSONObject.NULL)
            .put("durationMillis", result.durationMillis)
            .put("result", result.result ?: JSONObject.NULL)
            .put("failure", JSONObject.NULL)
    }

    private fun buildDefinitions(): List<AgentToolDefinition> {
        fun integerProperty(description: String, minimum: Int, maximum: Int) = JSONObject()
            .put("type", "integer").put("description", description).put("minimum", minimum).put("maximum", maximum)
        fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)
        val empty = AgentJsonSchema.emptyObject()
        return listOf(
            AgentToolDefinition(TOOL_START, "Start the trusted loopback Frida server for the already user-authorized target. The target PID cannot be changed by the model.", empty),
            AgentToolDefinition(TOOL_STATUS, "Read Frida helper and target status without attaching to a different process.", empty),
            AgentToolDefinition(TOOL_PING, "Perform a short bounded Frida attach and report agent/runtime information, then detach.", empty),
            AgentToolDefinition(
                TOOL_MODULES,
                "Enumerate bounded loaded modules in the authorized process.",
                AgentJsonSchema.objectSchema(JSONObject().put("max_count", integerProperty("Maximum modules", 1, 512))),
            ),
            AgentToolDefinition(
                TOOL_EXPORTS,
                "Enumerate bounded exports from one loaded module. No memory writes or arbitrary JavaScript are available.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("module", stringProperty("Exact module basename returned by frida_modules"))
                        .put("query", stringProperty("Optional bounded export-name substring"))
                        .put("max_count", integerProperty("Maximum exports", 1, 512)),
                    listOf("module"),
                ),
            ),
            AgentToolDefinition(
                TOOL_JAVA_CLASSES,
                "Search currently loaded Java classes through the fixed Frida Java bridge.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("query", stringProperty("Optional bounded class-name substring"))
                        .put("max_count", integerProperty("Maximum class names", 1, 512)),
                ),
            ),
            AgentToolDefinition(
                TOOL_JAVA_METHODS,
                "Enumerate bounded methods for one currently loaded Java class. Method replacement is unavailable.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("class_name", stringProperty("Exact loaded class name"))
                        .put("max_count", integerProperty("Maximum methods", 1, 512)),
                    listOf("class_name"),
                ),
            ),
            AgentToolDefinition(
                TOOL_NET_DETECT_STACK,
                "Detect loaded Android HTTPS/network stacks through the fixed Frida Java bridge. This does not modify certificate verification or proxy configuration.",
                AgentJsonSchema.objectSchema(JSONObject().put("max_count", integerProperty("Maximum matching loaded classes", 1, 128))),
            ),
            AgentToolDefinition(
                TOOL_TLS_TRACE,
                "Observe bounded plaintext previews at Android Conscrypt SSL_read/SSL_write for at most 5 seconds. No pinning bypass, CA installation, return-value replacement, or arbitrary script execution is exposed.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("duration_ms", integerProperty("Trace duration in milliseconds", 50, 5_000))
                        .put("max_events", integerProperty("Maximum retained TLS read/write events", 1, 128))
                        .put("max_bytes_per_event", integerProperty("Maximum plaintext preview bytes retained per event", 16, 1_024)),
                ),
            ),
            AgentToolDefinition(
                TOOL_NETWORK_HINTS,
                "Report fixed, bounded network-stack and pinning-related class hints from the authorized process. This detects evidence only and does not bypass pinning or modify runtime behavior.",
                AgentJsonSchema.objectSchema(JSONObject().put("max_count", integerProperty("Maximum hints", 1, 128))),
            ),
            AgentToolDefinition(
                TOOL_NATIVE_TRACE,
                "Observe entry hits for one native module+offset for at most 5 seconds using Interceptor.attach. No argument, memory, or return-value modification is exposed.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("module", stringProperty("Exact loaded module basename"))
                        .put("offset", stringProperty("0x-prefixed module-relative hexadecimal offset"))
                        .put("duration_ms", integerProperty("Trace duration in milliseconds", 50, 5_000))
                        .put("max_events", integerProperty("Maximum retained trace events", 1, 128)),
                    listOf("module", "offset"),
                ),
            ),
            AgentToolDefinition(TOOL_STOP, "Stop only the verified AutoCrack Frida helper and verify the target is no longer traced.", empty),
        )
    }

    private fun JSONObject.requireString(name: String): String = getString(name).trim().also {
        require(it.isNotEmpty()) { "$name must not be blank" }
    }

    companion object {
        fun authorized(
            packageName: String,
            pid: Int,
            authorizationPhrase: String,
            manager: HostFridaSessionManager,
        ): AgentFridaToolExecutor {
            HostFridaAuthorization.requireAuthorized(packageName, pid, authorizationPhrase)
            return AgentFridaToolExecutor(packageName, pid, manager)
        }

        private const val TOOL_START = "frida_start"
        private const val TOOL_STATUS = "frida_status"
        private const val TOOL_PING = "frida_ping"
        private const val TOOL_MODULES = "frida_modules"
        private const val TOOL_EXPORTS = "frida_exports"
        private const val TOOL_JAVA_CLASSES = "frida_java_classes"
        private const val TOOL_JAVA_METHODS = "frida_java_methods"
        private const val TOOL_NET_DETECT_STACK = "frida_net_detect_stack"
        private const val TOOL_TLS_TRACE = "frida_tls_trace"
        private const val TOOL_NETWORK_HINTS = "frida_network_hints"
        private const val TOOL_NATIVE_TRACE = "frida_native_trace"
        private const val TOOL_STOP = "frida_stop"
    }
}
