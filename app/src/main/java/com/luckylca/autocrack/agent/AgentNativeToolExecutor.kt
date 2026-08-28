package com.luckylca.autocrack.agent

import android.os.Process
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellOutputMode
import com.luckylca.autocrack.runtime.ShellEscaper
import com.luckylca.autocrack.tools.ElfInspector
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounded native/ELF analysis permanently scoped to the APKs in one selected extraction workspace.
 * The model never receives an arbitrary file path or raw Rizin command channel.
 */
class AgentNativeToolExecutor(
    private val extraction: ExtractionReport,
    private val layout: RuntimeLayout,
    private val chroot: ChrootRuntimeEngine,
    private val elfInspector: ElfInspector = ElfInspector(),
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()

    private val workspace = layout.createRuntimeWorkspace().canonicalFile
    private val stagedSo = File(workspace, STAGED_SO).canonicalFile
    private val rizinReportDir = File(workspace, RIZIN_REPORT_DIR).canonicalFile
    private val rizinDisassemblyJson = File(workspace, RIZIN_DISASM_JSON).canonicalFile
    private val rizinFunctionsJson = File(workspace, RIZIN_FUNCTIONS_JSON).canonicalFile

    init {
        require(
            layout.isManagedPath(workspace) &&
                layout.isManagedPath(stagedSo) &&
                layout.isManagedPath(rizinReportDir) &&
                layout.isManagedPath(rizinDisassemblyJson) &&
                layout.isManagedPath(rizinFunctionsJson),
        ) {
            "Native-analysis workspace escaped AutoCrack managed storage"
        }
        extraction.artifacts.forEach { artifact ->
            val file = File(artifact.localPath).canonicalFile
            require(layout.isManagedPath(file) && file.isFile) { "APK extraction artifact is missing or unmanaged" }
        }
    }

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_LIST_LIBRARIES -> listLibraries()
            TOOL_ELF_INFO -> elfInfo(arguments.requireLibrary())
            TOOL_JNI_MAP -> jniMap(arguments.requireLibrary())
            TOOL_FUNCTIONS -> functions(
                library = arguments.requireLibrary(),
                maxCount = arguments.optInt("max_count", DEFAULT_FUNCTION_COUNT).coerceIn(1, MAX_FUNCTION_COUNT),
            )
            TOOL_DISASSEMBLE -> disassemble(
                library = arguments.requireLibrary(),
                location = arguments.requireLocation(),
                instructionCount = arguments.optInt("instruction_count", DEFAULT_INSTRUCTION_COUNT)
                    .coerceIn(1, MAX_INSTRUCTION_COUNT),
            )
            TOOL_DEEP_REPORT -> deepReport(arguments.requireLibrary())
            TOOL_IMPORT_RISK -> importRiskSummary(arguments.requireLibrary())
            TOOL_STRINGS_CLUSTER -> stringsCluster(
                library = arguments.requireLibrary(),
                maxCount = arguments.optInt("max_count", DEFAULT_STRING_CLUSTER_COUNT).coerceIn(1, MAX_STRING_CLUSTER_COUNT),
            )
            else -> error("Unknown or unauthorized native Agent tool: $toolName")
        }
        return result.put("ok", true).put("tool", toolName).put("packageName", extraction.packageName).toString()
    }

    private fun listLibraries(): JSONObject {
        val libraries = enumerateLibraries()
        return JSONObject()
            .put("count", libraries.size)
            .put("libraries", JSONArray(libraries.take(MAX_LIBRARY_COUNT).map { target ->
                JSONObject()
                    .put("apk", target.apkFileName)
                    .put("entry", target.entryName)
                    .put("fileName", target.fileName)
                    .put("abi", target.abi)
                    .put("sizeBytes", target.sizeBytes)
            }))
            .put("truncated", libraries.size > MAX_LIBRARY_COUNT)
    }

    private fun elfInfo(library: String): JSONObject {
        val target = selectLibrary(library)
        val bytes = readTargetBytes(target)
        val report = elfInspector.inspect(bytes, "${target.apkFileName}!${target.entryName}")
        return JSONObject()
            .put("library", target.fileName)
            .put("entry", target.entryName)
            .put("abi", target.abi)
            .put("fileSizeBytes", report.fileSizeBytes)
            .put("elfClass", report.elfClass)
            .put("byteOrder", report.byteOrder)
            .put("objectType", report.objectType)
            .put("machine", report.machine)
            .put("entryPoint", "0x${report.entryPoint.toString(16)}")
            .put("buildId", report.buildId ?: JSONObject.NULL)
            .put("soname", report.soname ?: JSONObject.NULL)
            .put("neededLibraries", JSONArray(report.neededLibraries))
            .put("importedSymbols", JSONArray(report.importedSymbols.take(MAX_SYMBOL_COUNT)))
            .put("exportedSymbols", JSONArray(report.exportedSymbols.take(MAX_SYMBOL_COUNT)))
            .put("jniSymbols", JSONArray(report.jniSymbols))
            .put("interestingStrings", JSONArray(report.interestingStrings.take(MAX_STRING_COUNT)))
            .put("hardening", JSONObject()
                .put("nx", report.hardening.nx)
                .put("relro", report.hardening.relro)
                .put("bindNow", report.hardening.bindNow)
                .put("stackCanary", report.hardening.stackCanary)
                .put("fortifiedFunctions", report.hardening.fortifiedFunctions)
                .put("stripped", report.hardening.stripped)
                .put("positionIndependent", report.hardening.positionIndependent))
            .put("diagnostics", JSONArray(report.diagnostics))
    }

    private fun jniMap(library: String): JSONObject {
        val target = selectLibrary(library)
        val report = elfInspector.inspect(readTargetBytes(target), "${target.apkFileName}!${target.entryName}")
        val mappings = report.exportedSymbols
            .asSequence()
            .filter { it.startsWith("Java_") }
            .take(MAX_JNI_MAPPING_COUNT)
            .map(::decodeStaticJniSymbol)
            .toList()
        return JSONObject()
            .put("library", target.fileName)
            .put("staticMappingCount", mappings.size)
            .put("staticMappings", JSONArray(mappings.map { mapping ->
                JSONObject()
                    .put("symbol", mapping.symbol)
                    .put("className", mapping.className)
                    .put("methodName", mapping.methodName)
                    .put("signatureEncoded", mapping.signatureEncoded ?: JSONObject.NULL)
            }))
            .put("jniOnLoad", report.jniSymbols.contains("JNI_OnLoad"))
            .put(
                "dynamicRegistrationHint",
                report.importedSymbols.any { it.contains("RegisterNatives", ignoreCase = true) } ||
                    report.interestingStrings.any { it.contains("RegisterNatives", ignoreCase = true) },
            )
            .put(
                "note",
                "Static Java_* mappings are exact symbol-derived candidates. RegisterNatives mapping requires deeper string/xref analysis and is not guessed here.",
            )
    }

    private suspend fun functions(library: String, maxCount: Int): JSONObject {
        val target = selectLibrary(library)
        stageTarget(target)
        rizinFunctionsJson.delete()
        val command = """
            set -eu
            rz-functions /workspace/$STAGED_SO \
              | jq -c '{functionCountObserved:length, functions:[.[0:$maxCount][] | {offset,name,size,type,nbbs}]}' \
              > /workspace/$RIZIN_FUNCTIONS_JSON
            test -s /workspace/$RIZIN_FUNCTIONS_JSON
            chown ${Process.myUid()}:${Process.myUid()} /workspace/$RIZIN_FUNCTIONS_JSON
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = RIZIN_FUNCTION_TIMEOUT_MILLIS,
                outputMode = ShellOutputMode.DISCARD,
            ),
        )
        requireRizinSucceeded(
            succeeded = execution.exitCode == 0 && !execution.timedOut && !execution.cancelled,
            timedOut = execution.timedOut,
            failure = if (execution.exitCode == 0) null else execution.failure,
            stderr = execution.stderr,
            label = "Rizin function analysis",
        )
        require(layout.isManagedPath(rizinFunctionsJson) && rizinFunctionsJson.isFile && rizinFunctionsJson.length() > 0L) {
            "Rizin functions JSON was not returned to the App workspace"
        }
        val parsed = JSONObject(rizinFunctionsJson.readText(Charsets.UTF_8))
        val bounded = parsed.getJSONArray("functions")
        val observed = parsed.getInt("functionCountObserved")
        return JSONObject()
            .put("library", target.fileName)
            .put("functionCountObserved", observed)
            .put("functions", bounded)
            .put("truncated", observed > bounded.length())
            .put("durationMillis", execution.durationMillis)
    }

    private suspend fun disassemble(library: String, location: String, instructionCount: Int): JSONObject {
        val target = selectLibrary(library)
        stageTarget(target)
        rizinDisassemblyJson.delete()
        val command = """
            set -eu
            rz-disasm /workspace/$STAGED_SO ${ShellEscaper.quote(location)} $instructionCount \
              | jq -c '[.[] | {offset,bytes,opcode,type,jump,fail}]' > /workspace/$RIZIN_DISASM_JSON
            test -s /workspace/$RIZIN_DISASM_JSON
            chown ${Process.myUid()}:${Process.myUid()} /workspace/$RIZIN_DISASM_JSON
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = RIZIN_DISASM_TIMEOUT_MILLIS,
                outputMode = ShellOutputMode.DISCARD,
            ),
        )
        requireRizinSucceeded(
            succeeded = execution.exitCode == 0 && !execution.timedOut && !execution.cancelled,
            timedOut = execution.timedOut,
            failure = if (execution.exitCode == 0) null else execution.failure,
            stderr = execution.stderr,
            label = "Rizin disassembly",
        )
        require(layout.isManagedPath(rizinDisassemblyJson) && rizinDisassemblyJson.isFile && rizinDisassemblyJson.length() > 0L) {
            "Rizin disassembly JSON was not returned to the App workspace"
        }
        val instructions = JSONArray(rizinDisassemblyJson.readText(Charsets.UTF_8))
        return JSONObject()
            .put("library", target.fileName)
            .put("location", location)
            .put("instructionCount", instructions.length())
            .put("instructions", instructions)
            .put("durationMillis", execution.durationMillis)
    }

    private suspend fun deepReport(library: String): JSONObject {
        val target = selectLibrary(library)
        stageTarget(target)
        rizinReportDir.deleteRecursively()
        val command = """
            set -eu
            rz-deep-report /workspace/$STAGED_SO /workspace/$RIZIN_REPORT_DIR >/dev/null
            test -s /workspace/$RIZIN_REPORT_DIR/summary.json
            chown -R ${Process.myUid()}:${Process.myUid()} /workspace/$RIZIN_REPORT_DIR
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = RIZIN_REPORT_TIMEOUT_MILLIS,
                outputMode = ShellOutputMode.DISCARD,
            ),
        )
        requireRizinSucceeded(
            succeeded = execution.exitCode == 0 && !execution.timedOut && !execution.cancelled,
            timedOut = execution.timedOut,
            failure = if (execution.exitCode == 0) null else execution.failure,
            stderr = execution.stderr,
            label = "Rizin deep report",
        )
        val summary = File(rizinReportDir, "summary.json").canonicalFile
        require(layout.isManagedPath(summary) && summary.isFile && summary.length() > 0L) { "Rizin summary.json was not returned to the App workspace" }
        val parsed = JSONObject(summary.readText(Charsets.UTF_8))
        return JSONObject()
            .put("library", target.fileName)
            .put("summary", parsed)
            .put("durationMillis", execution.durationMillis)
    }

    private fun importRiskSummary(library: String): JSONObject {
        val target = selectLibrary(library)
        val report = elfInspector.inspect(readTargetBytes(target), "${target.apkFileName}!${target.entryName}")
        val imports = report.importedSymbols
        val strings = report.interestingStrings
        val categories = JSONArray()
        fun add(id: String, reason: String, evidence: List<String>) {
            if (evidence.isNotEmpty()) {
                categories.put(JSONObject().put("id", id).put("reason", reason).put("evidence", JSONArray(evidence.distinct().take(24))))
            }
        }
        add("dynamic_loader", "dlopen/dlsym-style dynamic loading may hide late-bound native behavior", imports.filterToken("dlopen", "dlsym", "android_dlopen_ext"))
        add("network_io", "native socket or resolver APIs indicate direct network behavior", (imports + strings).filterToken("socket", "connect", "send", "recv", "getaddrinfo", "inet_"))
        add("crypto", "crypto/hash/TLS symbols or strings suggest native cryptography", (imports + strings).filterToken("SSL", "TLS", "EVP_", "AES", "SHA", "MD5", "crypto"))
        add("anti_debug", "ptrace/prctl/syscall/debug strings may indicate anti-debug or process-control logic", (imports + strings).filterToken("ptrace", "prctl", "seccomp", "TracerPid", "anti", "debug"))
        add("jni_dynamic_registration", "JNI_OnLoad or RegisterNatives can bind Java methods dynamically", (report.jniSymbols + imports + strings).filterToken("JNI_OnLoad", "RegisterNatives"))
        return JSONObject()
            .put("library", target.fileName)
            .put("entry", target.entryName)
            .put("abi", target.abi)
            .put("importCount", imports.size)
            .put("interestingStringCount", strings.size)
            .put("riskCategoryCount", categories.length())
            .put("categories", categories)
            .put("heuristic", true)
            .put("note", "This is a bounded static hint summary, not a proof of runtime execution.")
    }

    private fun stringsCluster(library: String, maxCount: Int): JSONObject {
        val target = selectLibrary(library)
        val report = elfInspector.inspect(readTargetBytes(target), "${target.apkFileName}!${target.entryName}")
        val clusters = linkedMapOf(
            "network" to listOf("http", "https", "socket", "dns", "connect", "proxy"),
            "crypto" to listOf("ssl", "tls", "aes", "sha", "md5", "rsa", "crypto"),
            "jni" to listOf("jni", "Java_", "RegisterNatives", "JNI_OnLoad"),
            "debug_or_root" to listOf("ptrace", "TracerPid", "debug", "su", "magisk", "ksu"),
            "dynamic_loading" to listOf("dlopen", "dlsym", ".so", "linker"),
        )
        val output = JSONArray()
        clusters.forEach { (name, needles) ->
            val matches = report.interestingStrings.filter { value -> needles.any { needle -> value.contains(needle, ignoreCase = true) } }
                .distinct()
                .take(maxCount)
            if (matches.isNotEmpty()) output.put(JSONObject().put("cluster", name).put("strings", JSONArray(matches)))
        }
        return JSONObject()
            .put("library", target.fileName)
            .put("entry", target.entryName)
            .put("clusterCount", output.length())
            .put("clusters", output)
            .put("maxPerCluster", maxCount)
    }

    private fun List<String>.filterToken(vararg tokens: String): List<String> = filter { value ->
        tokens.any { token -> value.contains(token, ignoreCase = true) }
    }.distinct().take(32)

    private fun enumerateLibraries(): List<NativeLibraryTarget> = buildList {
        extraction.artifacts.forEach { artifact ->
            val apk = File(artifact.localPath).canonicalFile
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val match = LIB_ENTRY_REGEX.matchEntire(entry.name) ?: continue
                    if (entry.isDirectory) continue
                    add(
                        NativeLibraryTarget(
                            apkFileName = artifact.fileName,
                            apkPath = apk,
                            entryName = entry.name,
                            abi = match.groupValues[1],
                            fileName = match.groupValues[2],
                            sizeBytes = entry.size.coerceAtLeast(0L),
                        ),
                    )
                }
            }
        }
    }.sortedWith(compareBy(NativeLibraryTarget::abi, NativeLibraryTarget::fileName, NativeLibraryTarget::apkFileName))

    private fun selectLibrary(query: String): NativeLibraryTarget {
        val libraries = enumerateLibraries()
        val exact = libraries.filter { it.fileName.equals(query, true) || it.entryName.equals(query, true) }
        val matches = if (exact.isNotEmpty()) exact else libraries.filter {
            it.fileName.contains(query, true) || it.entryName.contains(query, true)
        }
        require(matches.isNotEmpty()) { "No native library matched: $query" }
        require(matches.size == 1) {
            "Native library name is ambiguous: ${matches.take(8).joinToString { it.entryName }}"
        }
        return matches.single()
    }

    private fun readTargetBytes(target: NativeLibraryTarget): ByteArray {
        require(target.sizeBytes in 1..MAX_SO_BYTES) { "SO exceeds bounded native-analysis size: ${target.sizeBytes} B" }
        return ZipFile(target.apkPath).use { zip ->
            val entry = zip.getEntry(target.entryName) ?: error("SO entry disappeared: ${target.entryName}")
            zip.getInputStream(entry).use { input ->
                val bytes = input.readBytes()
                require(bytes.size.toLong() <= MAX_SO_BYTES) { "SO exceeds bounded native-analysis size after extraction" }
                bytes
            }
        }
    }

    private fun stageTarget(target: NativeLibraryTarget) {
        val bytes = readTargetBytes(target)
        stagedSo.writeBytes(bytes)
        require(stagedSo.isFile && stagedSo.length() == bytes.size.toLong()) { "Failed to stage native library" }
    }

    private fun decodeStaticJniSymbol(symbol: String): StaticJniMapping {
        val body = symbol.removePrefix("Java_")
        val parts = body.split("__", limit = 2)
        val ownerAndMethod = parts[0]
        val separator = ownerAndMethod.lastIndexOf('_')
        if (separator <= 0 || separator == ownerAndMethod.lastIndex) {
            return StaticJniMapping(symbol, "<unresolved>", "<unresolved>", parts.getOrNull(1))
        }
        val owner = decodeJniComponent(ownerAndMethod.substring(0, separator), classMode = true)
        val method = decodeJniComponent(ownerAndMethod.substring(separator + 1), classMode = false)
        return StaticJniMapping(symbol, owner, method, parts.getOrNull(1))
    }

    private fun decodeJniComponent(encoded: String, classMode: Boolean): String {
        val out = StringBuilder()
        var index = 0
        while (index < encoded.length) {
            val c = encoded[index]
            if (c != '_') {
                out.append(c)
                index++
                continue
            }
            when {
                encoded.startsWith("_1", index) -> { out.append('_'); index += 2 }
                encoded.startsWith("_2", index) -> { out.append(';'); index += 2 }
                encoded.startsWith("_3", index) -> { out.append('['); index += 2 }
                index + 6 <= encoded.length && encoded[index + 1] == '0' -> {
                    val hex = encoded.substring(index + 2, index + 6)
                    val code = hex.toIntOrNull(16)
                    if (code != null) { out.append(code.toChar()); index += 6 } else { out.append('_'); index++ }
                }
                else -> { out.append(if (classMode) '.' else '_'); index++ }
            }
        }
        return out.toString()
    }

    private fun JSONObject.requireLibrary(): String = requireBoundedText("library", MAX_LIBRARY_QUERY_CHARS)

    private fun JSONObject.requireLocation(): String = requireBoundedText("location", MAX_LOCATION_CHARS).also {
        require(LOCATION_REGEX.matches(it)) { "location must be a bounded Rizin symbol/address token" }
    }

    private fun JSONObject.requireBoundedText(name: String, maxChars: Int): String = getString(name).trim().also {
        require(it.isNotEmpty() && it.length <= maxChars && '\u0000' !in it && '\n' !in it && '\r' !in it) {
            "$name is blank, too long, or contains an invalid character"
        }
    }

    private fun requireRizinSucceeded(
        succeeded: Boolean,
        timedOut: Boolean,
        failure: String?,
        stderr: String,
        label: String,
    ) {
        require(succeeded) {
            when {
                timedOut -> "$label timed out and bounded orphan cleanup was requested"
                failure != null -> "$label failed: $failure"
                stderr.isNotBlank() -> "$label failed: ${stderr.take(MAX_ERROR_CHARS)}"
                else -> "$label failed"
            }
        }
    }

    private fun buildDefinitions(): List<AgentToolDefinition> {
        fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)
        fun integerProperty(description: String, min: Int, max: Int) = JSONObject()
            .put("type", "integer").put("description", description).put("minimum", min).put("maximum", max)
        return listOf(
            AgentToolDefinition(
                TOOL_LIST_LIBRARIES,
                "List native .so libraries in the already-selected APK workspace. No arbitrary paths are accepted.",
                AgentJsonSchema.emptyObject(),
            ),
            AgentToolDefinition(
                TOOL_ELF_INFO,
                "Parse one selected APK native library with AutoCrack's bounded ELF parser: architecture, dependencies, imports/exports, hardening, JNI symbols and interesting strings.",
                AgentJsonSchema.objectSchema(JSONObject().put("library", stringProperty("Exact .so filename or APK lib/... entry")), listOf("library")),
            ),
            AgentToolDefinition(
                TOOL_JNI_MAP,
                "Map exported Java_* JNI symbols from one selected native library back to Java class/method candidates and report whether dynamic RegisterNatives evidence exists. No guessed dynamic mapping is fabricated.",
                AgentJsonSchema.objectSchema(JSONObject().put("library", stringProperty("Exact .so filename or APK lib/... entry")), listOf("library")),
            ),
            AgentToolDefinition(
                TOOL_FUNCTIONS,
                "Run trusted Rizin analysis on one selected APK native library and return a bounded function inventory.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("library", stringProperty("Exact .so filename or APK lib/... entry"))
                        .put("max_count", integerProperty("Maximum functions returned", 1, MAX_FUNCTION_COUNT)),
                    listOf("library"),
                ),
            ),
            AgentToolDefinition(
                TOOL_DISASSEMBLE,
                "Disassemble a bounded number of instructions at one Rizin-discovered symbol/address in the selected native library. No raw Rizin command is exposed.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("library", stringProperty("Exact .so filename or APK lib/... entry"))
                        .put("location", stringProperty("Rizin symbol or numeric address returned by native_rizin_functions"))
                        .put("instruction_count", integerProperty("Maximum instructions", 1, MAX_INSTRUCTION_COUNT)),
                    listOf("library", "location"),
                ),
            ),
            AgentToolDefinition(
                TOOL_DEEP_REPORT,
                "Generate the reviewed AutoCrack Rizin deep report for one selected native library: info, sections, imports, exports, symbols, strings, relocations, functions and call graph summary.",
                AgentJsonSchema.objectSchema(JSONObject().put("library", stringProperty("Exact .so filename or APK lib/... entry")), listOf("library")),
            ),
            AgentToolDefinition(
                TOOL_IMPORT_RISK,
                "Generate a bounded static native import-risk summary for one selected APK library. This reports heuristics only and does not claim runtime execution.",
                AgentJsonSchema.objectSchema(JSONObject().put("library", stringProperty("Exact .so filename or APK lib/... entry")), listOf("library")),
            ),
            AgentToolDefinition(
                TOOL_STRINGS_CLUSTER,
                "Cluster bounded interesting strings from one selected APK native library into network, crypto, JNI, debug/root, and dynamic-loading hints.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("library", stringProperty("Exact .so filename or APK lib/... entry"))
                        .put("max_count", integerProperty("Maximum strings per cluster", 1, MAX_STRING_CLUSTER_COUNT)),
                    listOf("library"),
                ),
            ),
        )
    }

    private data class NativeLibraryTarget(
        val apkFileName: String,
        val apkPath: File,
        val entryName: String,
        val abi: String,
        val fileName: String,
        val sizeBytes: Long,
    )

    private data class StaticJniMapping(
        val symbol: String,
        val className: String,
        val methodName: String,
        val signatureEncoded: String?,
    )

    companion object {
        const val TOOLPACK_ID = "rizin-deep-static"
        const val TOOLPACK_VERSION = "rizin-0.9.1_autocrack-1.0.1"
        private const val TOOL_LIST_LIBRARIES = "native_list_libraries"
        private const val TOOL_ELF_INFO = "native_elf_info"
        private const val TOOL_JNI_MAP = "native_jni_map"
        private const val TOOL_FUNCTIONS = "native_rizin_functions"
        private const val TOOL_DISASSEMBLE = "native_rizin_disassemble"
        private const val TOOL_DEEP_REPORT = "native_rizin_report"
        private const val TOOL_IMPORT_RISK = "native_import_risk_summary"
        private const val TOOL_STRINGS_CLUSTER = "native_strings_cluster"
        val NATIVE_TOOL_NAMES = listOf(
            TOOL_LIST_LIBRARIES,
            TOOL_ELF_INFO,
            TOOL_JNI_MAP,
            TOOL_FUNCTIONS,
            TOOL_DISASSEMBLE,
            TOOL_DEEP_REPORT,
            TOOL_IMPORT_RISK,
            TOOL_STRINGS_CLUSTER,
        )
        private const val STAGED_SO = "agent-native-target.so"
        private const val RIZIN_REPORT_DIR = "agent-rizin-report"
        private const val RIZIN_DISASM_JSON = "agent-rizin-disassembly.json"
        private const val RIZIN_FUNCTIONS_JSON = "agent-rizin-functions.json"
        private const val DEFAULT_FUNCTION_COUNT = 64
        private const val MAX_FUNCTION_COUNT = 128
        private const val DEFAULT_INSTRUCTION_COUNT = 48
        private const val MAX_INSTRUCTION_COUNT = 128
        private const val DEFAULT_STRING_CLUSTER_COUNT = 24
        private const val MAX_STRING_CLUSTER_COUNT = 64
        private const val MAX_LIBRARY_COUNT = 128
        private const val MAX_SYMBOL_COUNT = 96
        private const val MAX_STRING_COUNT = 48
        private const val MAX_JNI_MAPPING_COUNT = 128
        private const val MAX_LIBRARY_QUERY_CHARS = 256
        private const val MAX_LOCATION_CHARS = 256
        private const val MAX_SO_BYTES = 128L * 1024L * 1024L
        private const val RIZIN_FUNCTION_TIMEOUT_MILLIS = 90_000L
        private const val RIZIN_DISASM_TIMEOUT_MILLIS = 60_000L
        private const val RIZIN_REPORT_TIMEOUT_MILLIS = 120_000L
        private const val MAX_ERROR_CHARS = 2_000
        private val LIB_ENTRY_REGEX = Regex("^lib/([^/]+)/([^/]+\\.so)$")
        private val LOCATION_REGEX = Regex("^[A-Za-z0-9_.$:+@-]{1,256}$")
    }
}
