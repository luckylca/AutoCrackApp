package com.luckylca.autocrack.agent

import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ShellCommandRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Bounded JADX/Apktool tools permanently bound to one already-extracted base APK. */
class AgentApkDexToolExecutor(
    private val packageName: String,
    private val baseApk: File,
    private val layout: RuntimeLayout,
    private val chroot: ChrootRuntimeEngine,
    private val appUid: Int,
    private val onStage: (String) -> Unit = {},
) : AgentToolExecutor {
    override val tools: List<AgentToolDefinition> = buildDefinitions()

    private val workspace = layout.createRuntimeWorkspace().canonicalFile
    private val sampleApk = File(workspace, SAMPLE_APK).canonicalFile
    private val jadxOutput = File(workspace, JADX_OUTPUT_DIR).canonicalFile
    private val apktoolOutput = File(workspace, APKTOOL_OUTPUT_DIR).canonicalFile

    init {
        PackageOutputParser.requireValidPackageName(packageName)
        require(baseApk.isFile && baseApk.length() > 0L) { "Base APK is missing" }
        require(layout.isManagedPath(baseApk)) { "Base APK is outside the managed AutoCrack workspace" }
        require(layout.isManagedPath(workspace)) { "Static-analysis workspace is not managed by AutoCrack" }
        listOf(sampleApk, jadxOutput, apktoolOutput).forEach { path ->
            require(layout.isManagedPath(path)) { "Static-analysis path escaped the managed workspace" }
        }
    }

    override suspend fun dispatch(toolName: String, arguments: JSONObject): String {
        val result = when (toolName) {
            TOOL_JADX_CLASS -> decompileClass(arguments.requireClassName())
            TOOL_APKTOOL_DECODE -> decodeWithApktool()
            TOOL_SMALI_SEARCH -> searchSmali(
                query = arguments.requireBoundedText("query", MAX_SEARCH_QUERY_CHARS),
                maxCount = arguments.optInt("max_count", DEFAULT_SEARCH_COUNT).coerceIn(1, MAX_SEARCH_COUNT),
            )
            else -> error("Unknown or unauthorized APK/DEX Agent tool: $toolName")
        }
        return result.put("ok", true).put("tool", toolName).put("packageName", packageName).toString()
    }

    private suspend fun decompileClass(className: String): JSONObject {
        onStage("jadx_prepare_enter")
        prepareSampleApk()
        onStage("jadx_prepare_done")
        jadxOutput.deleteRecursively()
        val command = """
            set -eu
            rm -rf -- /workspace/$JADX_OUTPUT_DIR
            mkdir -p /workspace/$JADX_OUTPUT_DIR
            export JADX_OPTS='-Xms64M -Xmx512M -XX:ActiveProcessorCount=2'
            jadx --threads-count 1 --no-res \
              --single-class "${'$'}AUTOC_CLASS_NAME" \
              --single-class-output /workspace/$JADX_OUTPUT_FILE \
              /workspace/$SAMPLE_APK
            test -s /workspace/$JADX_OUTPUT_FILE
            chown -R $appUid:$appUid /workspace/$JADX_OUTPUT_DIR
            echo JADX_CLASS_DONE
        """.trimIndent()
        onStage("jadx_chroot_enter")
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                environment = mapOf("AUTOC_CLASS_NAME" to className),
                timeoutMillis = JADX_TIMEOUT_MILLIS,
            ),
        )
        onStage("jadx_chroot_return")
        requireSucceeded(execution.succeeded, execution.timedOut, execution.failure, execution.stderr, "JADX")
        val output = File(workspace, JADX_OUTPUT_FILE).canonicalFile
        require(layout.isManagedPath(output) && output.isFile && output.length() > 0L) { "JADX output is missing" }
        onStage("jadx_output_verified")
        return JSONObject()
            .put("className", className)
            .put("bytes", output.length())
            .put("durationMillis", execution.durationMillis)
            .put("source", output.readText(Charsets.UTF_8).take(MAX_SOURCE_CHARS))
            .put("truncated", output.length() > MAX_SOURCE_CHARS)
    }

    private suspend fun decodeWithApktool(): JSONObject {
        prepareSampleApk()
        apktoolOutput.deleteRecursively()
        val command = """
            set -eu
            rm -rf -- /workspace/$APKTOOL_OUTPUT_DIR
            apktool decode --force --output /workspace/$APKTOOL_OUTPUT_DIR /workspace/$SAMPLE_APK
            test -f /workspace/$APKTOOL_OUTPUT_DIR/AndroidManifest.xml
            smali_count=${'$'}(find /workspace/$APKTOOL_OUTPUT_DIR -type f -name '*.smali' | wc -l)
            manifest_bytes=${'$'}(wc -c < /workspace/$APKTOOL_OUTPUT_DIR/AndroidManifest.xml)
            chown -R $appUid:$appUid /workspace/$APKTOOL_OUTPUT_DIR
            printf 'SMALI_FILES=%s\nMANIFEST_BYTES=%s\n' "${'$'}smali_count" "${'$'}manifest_bytes"
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                timeoutMillis = APKTOOL_TIMEOUT_MILLIS,
            ),
        )
        requireSucceeded(execution.succeeded, execution.timedOut, execution.failure, execution.stderr, "Apktool")
        val manifest = File(apktoolOutput, "AndroidManifest.xml").canonicalFile
        require(layout.isManagedPath(manifest) && manifest.isFile) { "Decoded manifest is missing" }
        return JSONObject()
            .put("smaliFileCount", metric(execution.stdout, "SMALI_FILES"))
            .put("manifestBytes", metric(execution.stdout, "MANIFEST_BYTES"))
            .put("durationMillis", execution.durationMillis)
            .put("manifestPreview", manifest.readText(Charsets.UTF_8).take(MAX_MANIFEST_CHARS))
    }

    private suspend fun searchSmali(query: String, maxCount: Int): JSONObject {
        require(apktoolOutput.isDirectory) { "Run apktool_decode_summary before searching smali" }
        val command = """
            set -eu
            grep -RFn --include='*.smali' -F -- "${'$'}AUTOC_SMALI_QUERY" /workspace/$APKTOOL_OUTPUT_DIR \
              | head -n $maxCount || true
        """.trimIndent()
        val execution = chroot.execute(
            ShellCommandRequest(
                command = command,
                workingDirectory = "/workspace",
                environment = mapOf("AUTOC_SMALI_QUERY" to query),
                timeoutMillis = SEARCH_TIMEOUT_MILLIS,
            ),
        )
        requireSucceeded(execution.succeeded, execution.timedOut, execution.failure, execution.stderr, "Smali search")
        val matches = execution.stdout.lineSequence().filter(String::isNotBlank).take(maxCount).toList()
        return JSONObject()
            .put("query", query)
            .put("matchCount", matches.size)
            .put("matches", org.json.JSONArray(matches.map { it.take(MAX_MATCH_CHARS) }))
            .put("durationMillis", execution.durationMillis)
    }

    private fun prepareSampleApk() {
        onStage("prepare_sample_start")
        if (sampleApk.exists()) {
            onStage("prepare_sample_delete_existing")
            if (!sampleApk.delete()) error("Unable to replace static-analysis sample APK")
        }
        onStage("prepare_sample_link_attempt")
        val linked = runCatching {
            Files.createLink(sampleApk.toPath(), baseApk.toPath())
        }.isSuccess
        if (!linked) {
            onStage("prepare_sample_copy_attempt")
            Files.copy(baseApk.toPath(), sampleApk.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        onStage(if (linked) "prepare_sample_link_done" else "prepare_sample_copy_done")
        require(sampleApk.isFile && sampleApk.length() == baseApk.length()) { "Failed to stage base APK" }
        onStage("prepare_sample_verified")
    }

    private fun JSONObject.requireClassName(): String = requireBoundedText("class_name", MAX_CLASS_NAME_CHARS).also {
        require(CLASS_NAME_REGEX.matches(it)) { "Invalid Java class name" }
    }

    private fun JSONObject.requireBoundedText(name: String, maxChars: Int): String = getString(name).trim().also {
        require(it.isNotEmpty() && it.length <= maxChars && '\u0000' !in it && '\n' !in it && '\r' !in it) {
            "$name is blank, too long, or contains an invalid character"
        }
    }

    private fun requireSucceeded(
        succeeded: Boolean,
        timedOut: Boolean,
        failure: String?,
        stderr: String,
        label: String,
    ) {
        require(succeeded) {
            when {
                timedOut -> "$label timed out and AutoCrack requested bounded orphan cleanup"
                failure != null -> "$label failed: $failure"
                stderr.isNotBlank() -> "$label failed: ${stderr.take(MAX_ERROR_CHARS)}"
                else -> "$label failed"
            }
        }
    }

    private fun metric(text: String, name: String): Long = text.lineSequence()
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.trim()
        ?.toLongOrNull()
        ?: error("Missing $name metric")

    private fun buildDefinitions(): List<AgentToolDefinition> {
        fun stringProperty(description: String) = JSONObject().put("type", "string").put("description", description)
        fun integerProperty(description: String, min: Int, max: Int) = JSONObject()
            .put("type", "integer").put("description", description).put("minimum", min).put("maximum", max)
        return listOf(
            AgentToolDefinition(
                TOOL_JADX_CLASS,
                "Decompile one exact Java/Kotlin class from the already-selected base APK using trusted JADX. The model cannot choose files or shell commands.",
                AgentJsonSchema.objectSchema(
                    JSONObject().put("class_name", stringProperty("Exact fully-qualified class name, for example com.example.MainActivity")),
                    listOf("class_name"),
                ),
            ),
            AgentToolDefinition(
                TOOL_APKTOOL_DECODE,
                "Decode the already-selected base APK into the managed workspace with trusted Apktool and return bounded structural counts and a manifest preview.",
                AgentJsonSchema.emptyObject(),
            ),
            AgentToolDefinition(
                TOOL_SMALI_SEARCH,
                "Search decoded smali with a fixed-string bounded query. Run apktool_decode_summary first. No regex or arbitrary path is exposed.",
                AgentJsonSchema.objectSchema(
                    JSONObject()
                        .put("query", stringProperty("Literal smali text to search for"))
                        .put("max_count", integerProperty("Maximum returned matches", 1, MAX_SEARCH_COUNT)),
                    listOf("query"),
                ),
            ),
        )
    }

    companion object {
        const val TOOLPACK_ID = "apk-dex-static"
        const val TOOLPACK_VERSION = "jadx-1.5.6_apktool-3.0.3"
        private const val TOOL_JADX_CLASS = "apk_jadx_class"
        private const val TOOL_APKTOOL_DECODE = "apktool_decode_summary"
        private const val TOOL_SMALI_SEARCH = "apktool_smali_search"
        private const val SAMPLE_APK = "agent-static-base.apk"
        private const val JADX_OUTPUT_DIR = "agent-jadx-class"
        private const val JADX_OUTPUT_FILE = "$JADX_OUTPUT_DIR/SelectedClass.java"
        private const val APKTOOL_OUTPUT_DIR = "agent-apktool-output"
        private const val JADX_TIMEOUT_MILLIS = 120_000L
        private const val APKTOOL_TIMEOUT_MILLIS = 120_000L
        private const val SEARCH_TIMEOUT_MILLIS = 15_000L
        private const val MAX_CLASS_NAME_CHARS = 256
        private const val MAX_SEARCH_QUERY_CHARS = 256
        private const val DEFAULT_SEARCH_COUNT = 32
        private const val MAX_SEARCH_COUNT = 100
        private const val MAX_SOURCE_CHARS = 24_000
        private const val MAX_MANIFEST_CHARS = 12_000
        private const val MAX_MATCH_CHARS = 2_000
        private const val MAX_ERROR_CHARS = 2_000
        private val CLASS_NAME_REGEX = Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$")
    }
}
