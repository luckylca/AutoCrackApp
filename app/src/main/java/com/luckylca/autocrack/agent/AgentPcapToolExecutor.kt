package com.luckylca.autocrack.agent

import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellEscaper
import java.io.File
import org.json.JSONObject

/** Workspace-scoped pcap analysis through the Debian ARM64 rootfs. */
class AgentPcapToolExecutor(
    private val packageName: String,
    private val layout: RuntimeLayout,
    private val chroot: ChrootRuntimeEngine,
) : AgentToolExecutor {
    private val workspace: File = layout.createRuntimeWorkspace().canonicalFile
    private val captureRoot: File = File(
        workspace,
        "network-captures/${packageName.replace('.', '_')}",
    ).canonicalFile
    private val pcapMetaFile: File = File(captureRoot, "tcpdump-session.json").canonicalFile

    override val tools: List<AgentToolDefinition> = buildDefinitions()

    init {
        PackageOutputParser.requireValidPackageName(packageName)
        require(layout.isManagedPath(workspace) && layout.isManagedPath(captureRoot) && layout.isManagedPath(pcapMetaFile)) {
            "PCAP analysis workspace escaped AutoCrack managed storage"
        }
    }

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_INFO -> analyze(mode = "info", maxRecords = DEFAULT_MAX_RECORDS, arguments = arguments, allowMaxRecords = false)
            TOOL_PROTOCOLS -> analyze(mode = "protocols", maxRecords = arguments.maxRecords(), arguments = arguments, allowMaxRecords = true)
            TOOL_DNS -> analyze(mode = "dns", maxRecords = arguments.maxRecords(), arguments = arguments, allowMaxRecords = true)
            TOOL_TLS -> analyze(mode = "tls", maxRecords = arguments.maxRecords(), arguments = arguments, allowMaxRecords = true)
            TOOL_TOP_CONNECTIONS -> analyze(mode = "top-connections", maxRecords = arguments.maxRecords(), arguments = arguments, allowMaxRecords = true)
            else -> error("Unknown or unauthorized rootfs pcap Agent tool: $toolName")
        }
        return result
            .put("ok", true)
            .put("tool", toolName)
            .put("packageName", packageName)
            .put("runtimeTarget", "android_rootfs_only")
            .toString()
    }

    private suspend fun analyze(
        mode: String,
        maxRecords: Int,
        arguments: JSONObject,
        allowMaxRecords: Boolean,
    ): JSONObject {
        val allowedKeys = if (allowMaxRecords) setOf("max_records") else emptySet()
        requireKnownArguments(arguments, allowedKeys)
        val pcap = latestWorkspacePcap()
        val workspacePcap = toChrootWorkspacePath(pcap)
        val command = """
            set -eu
            pcap-summary --mode ${ShellEscaper.quote(mode)} --max-records $maxRecords ${ShellEscaper.quote(workspacePcap)}
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = PCAP_SUMMARY_TIMEOUT_MILLIS,
            ),
        )
        require(execution.succeeded) {
            when {
                execution.timedOut -> "pcap-summary timed out"
                execution.failure != null -> "pcap-summary failed: ${execution.failure}"
                execution.stderr.isNotBlank() -> "pcap-summary failed: ${execution.stderr.take(MAX_ERROR_CHARS)}"
                else -> "pcap-summary failed"
            }
        }
        return JSONObject(execution.stdout.trim())
            .put("pcapBytes", pcap.length())
            .put("durationMillis", execution.durationMillis)
    }

    private fun latestWorkspacePcap(): File {
        require(pcapMetaFile.isFile && pcapMetaFile.length() in 1..MAX_META_BYTES) {
            "No AutoCrack pcap metadata found. Run android_pcap_start first, then android_pcap_status."
        }
        val meta = JSONObject(pcapMetaFile.readText(Charsets.UTF_8))
        require(meta.optString("packageName") == packageName) { "PCAP metadata belongs to a different package" }
        require(!meta.optBoolean("vpnUsed", false)) { "Unexpected VPN capture metadata" }
        require(!meta.optBoolean("surfingConfigTouched", false)) { "Unexpected Surfing mutation metadata" }
        require(!meta.optBoolean("httpsDecrypted", false)) { "PCAP metadata must not claim HTTPS decryption" }
        val pcap = File(meta.getString("pcapPath")).canonicalFile
        require(layout.isManagedPath(pcap)) { "PCAP file is outside AutoCrack managed storage" }
        require(isInside(captureRoot, pcap)) { "PCAP file is outside the selected package capture directory" }
        require(pcap.isFile && pcap.length() > 0L) { "PCAP file is missing or empty" }
        require(pcap.length() <= MAX_PCAP_BYTES) { "PCAP file exceeds the bounded analysis limit" }
        return pcap
    }

    private fun toChrootWorkspacePath(file: File): String {
        val relative = workspace.toURI().relativize(file.toURI()).path
        require(relative.isNotBlank() && !relative.startsWith('/')) { "PCAP file is not relative to the runtime workspace" }
        return "/workspace/$relative"
    }

    private fun JSONObject.maxRecords(): Int = optInt("max_records", DEFAULT_MAX_RECORDS).coerceIn(1, MAX_RECORDS)

    private fun requireKnownArguments(arguments: JSONObject, allowedKeys: Set<String>) {
        val unknown = arguments.keys().asSequence().filterNot(allowedKeys::contains).toList().sorted()
        require(unknown.isEmpty()) { "rootfs pcap tool rejected unsupported argument(s): ${unknown.joinToString(", ")}" }
    }

    private fun isInside(root: File, child: File): Boolean {
        val rootPath = root.canonicalPath
        val childPath = child.canonicalPath
        return childPath == rootPath || childPath.startsWith("$rootPath${File.separator}")
    }

    private fun buildDefinitions(): List<AgentToolDefinition> {
        fun integerProperty(description: String, min: Int, max: Int) = JSONObject()
            .put("type", "integer")
            .put("description", description)
            .put("minimum", min)
            .put("maximum", max)
        val maxRecordSchema = AgentJsonSchema.objectSchema(
            JSONObject().put("max_records", integerProperty("Maximum returned rows", 1, MAX_RECORDS)),
        )
        return listOf(
            AgentToolDefinition(
                TOOL_INFO,
                "Read pcap global metadata for the latest AutoCrack capture in this selected package workspace. No arbitrary path is accepted.",
                AgentJsonSchema.emptyObject(),
            ),
            AgentToolDefinition(
                TOOL_PROTOCOLS,
                "Summarize packet, byte and protocol counts from the latest AutoCrack pcap capture.",
                maxRecordSchema,
            ),
            AgentToolDefinition(
                TOOL_DNS,
                "Extract bounded DNS query names observed in the latest AutoCrack pcap capture.",
                maxRecordSchema,
            ),
            AgentToolDefinition(
                TOOL_TLS,
                "Extract bounded TLS ClientHello metadata such as SNI and ALPN when visible in the latest AutoCrack pcap capture.",
                maxRecordSchema,
            ),
            AgentToolDefinition(
                TOOL_TOP_CONNECTIONS,
                "Rank top TCP/UDP endpoint pairs from the latest AutoCrack pcap capture.",
                maxRecordSchema,
            ),
        )
    }

    companion object {
        const val TOOLPACK_ID = "rootfs-pcap-analysis"
        const val TOOLPACK_VERSION = "pcap-summary-1.0.0"
        const val TOOL_INFO = "rootfs_pcap_info"
        const val TOOL_PROTOCOLS = "rootfs_pcap_protocol_summary"
        const val TOOL_DNS = "rootfs_pcap_dns_summary"
        const val TOOL_TLS = "rootfs_pcap_tls_summary"
        const val TOOL_TOP_CONNECTIONS = "rootfs_pcap_top_connections"
        val PCAP_TOOL_NAMES = listOf(
            TOOL_INFO,
            TOOL_PROTOCOLS,
            TOOL_DNS,
            TOOL_TLS,
            TOOL_TOP_CONNECTIONS,
        )
        private const val DEFAULT_MAX_RECORDS = 32
        private const val MAX_RECORDS = 128
        private const val MAX_META_BYTES = 16L * 1024L
        private const val MAX_PCAP_BYTES = 64L * 1024L * 1024L
        private const val PCAP_SUMMARY_TIMEOUT_MILLIS = 60_000L
        private const val MAX_ERROR_CHARS = 2_000
    }
}
