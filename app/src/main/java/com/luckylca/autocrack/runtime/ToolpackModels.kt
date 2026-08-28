package com.luckylca.autocrack.runtime

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class ToolpackSourceArtifact(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    init {
        require(name.matches(TOOLPACK_SAFE_ID_REGEX)) { "非法来源名称：$name" }
        require(version.isNotBlank() && version.length <= TOOLPACK_MAX_VERSION_CHARS) {
            "来源版本非法：$version"
        }
        require(url.startsWith("https://")) { "工具来源必须使用 HTTPS：$url" }
        require(sha256.matches(TOOLPACK_SHA256_REGEX)) { "来源 SHA-256 格式非法：$name" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("version", version)
        .put("url", url)
        .put("sha256", sha256)
}

data class ToolpackCommand(
    val name: String,
    val relativePath: String,
    val description: String = "",
) {
    init {
        require(name.matches(TOOLPACK_COMMAND_NAME_REGEX)) { "非法工具命令名：$name" }
        ToolpackPathPolicy.validateRelativePath(relativePath)
        require(description.length <= TOOLPACK_MAX_DESCRIPTION_CHARS) { "工具命令描述过长：$name" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("relativePath", relativePath)
        .put("description", description)
}

data class ToolpackSelfTest(
    val id: String,
    val title: String,
    val command: String,
    val expectedExitCodes: Set<Int>,
    val outputContains: List<String>,
) {
    init {
        require(id.matches(TOOLPACK_SAFE_ID_REGEX)) { "非法自检 ID：$id" }
        require(title.isNotBlank() && title.length <= TOOLPACK_MAX_TITLE_CHARS) {
            "自检标题非法：$id"
        }
        require(command.isNotBlank() && command.length <= TOOLPACK_MAX_SELF_TEST_COMMAND_CHARS) {
            "自检命令非法：$id"
        }
        require(expectedExitCodes.isNotEmpty()) { "自检必须声明允许退出码：$id" }
        expectedExitCodes.forEach { code ->
            require(code in 0..255) { "自检退出码非法：$code" }
        }
        outputContains.forEach { expected ->
            require(expected.isNotEmpty() && expected.length <= TOOLPACK_MAX_EXPECTED_OUTPUT_CHARS) {
                "自检输出断言非法：$id"
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("command", command)
        .put("expectedExitCodes", JSONArray(expectedExitCodes.sorted()))
        .put("outputContains", JSONArray(outputContains))
}

data class ToolpackPackageManifest(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val version: String,
    val architecture: String,
    val payloadEntry: String,
    val payloadSha256: String,
    val payloadSizeBytes: Long,
    val requiredPaths: List<String>,
    val commands: List<ToolpackCommand>,
    val selfTests: List<ToolpackSelfTest>,
    val sources: List<ToolpackSourceArtifact>,
    val description: String = "",
) {
    init {
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "不支持的 toolpack manifest schema：$schemaVersion"
        }
        require(id.matches(TOOLPACK_SAFE_ID_REGEX)) { "非法 toolpack id：$id" }
        require(title.isNotBlank() && title.length <= TOOLPACK_MAX_TITLE_CHARS) {
            "toolpack 标题非法"
        }
        require(description.length <= TOOLPACK_MAX_DESCRIPTION_CHARS) { "toolpack 描述过长" }
        require(version.matches(TOOLPACK_SAFE_VERSION_REGEX)) {
            "非法 toolpack 版本：$version"
        }
        require(architecture.lowercase(Locale.US) in SUPPORTED_ARCHITECTURES) {
            "不支持的 toolpack 架构：$architecture"
        }
        require(payloadEntry == PAYLOAD_ENTRY) { "不支持的 payloadEntry：$payloadEntry" }
        require(payloadSha256.matches(TOOLPACK_SHA256_REGEX)) {
            "toolpack payload SHA-256 格式非法"
        }
        require(payloadSizeBytes in 1..MAX_PAYLOAD_BYTES) { "toolpack payload 大小非法" }
        require(requiredPaths.isNotEmpty()) { "toolpack requiredPaths 不能为空" }
        requiredPaths.forEach(ToolpackPathPolicy::validateRelativePath)
        require(requiredPaths.distinct().size == requiredPaths.size) {
            "toolpack requiredPaths 包含重复项"
        }
        require(commands.isNotEmpty()) { "toolpack commands 不能为空" }
        require(commands.map(ToolpackCommand::name).distinct().size == commands.size) {
            "toolpack 命令名重复"
        }
        require(selfTests.isNotEmpty()) { "toolpack selfTests 不能为空" }
        require(selfTests.map(ToolpackSelfTest::id).distinct().size == selfTests.size) {
            "toolpack 自检 ID 重复"
        }
        require(sources.isNotEmpty()) { "toolpack sources 不能为空" }
        require(sources.map(ToolpackSourceArtifact::name).distinct().size == sources.size) {
            "toolpack 来源名称重复"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("id", id)
        .put("title", title)
        .put("version", version)
        .put("description", description)
        .put("architecture", architecture)
        .put("payloadEntry", payloadEntry)
        .put("payloadSha256", payloadSha256)
        .put("payloadSizeBytes", payloadSizeBytes)
        .put("requiredPaths", JSONArray(requiredPaths))
        .put("commands", JSONArray(commands.map(ToolpackCommand::toJson)))
        .put("selfTests", JSONArray(selfTests.map(ToolpackSelfTest::toJson)))
        .put("sources", JSONArray(sources.map(ToolpackSourceArtifact::toJson)))

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val PAYLOAD_ENTRY = "payload.zip"
        const val MAX_PAYLOAD_BYTES = 1_500_000_000L
        private val SUPPORTED_ARCHITECTURES = setOf("all", "arm64", "aarch64")

        fun parse(text: String): ToolpackPackageManifest {
            val json = JSONObject(text)
            return ToolpackPackageManifest(
                schemaVersion = json.getInt("schemaVersion"),
                id = json.getString("id"),
                title = json.getString("title"),
                version = json.getString("version"),
                description = json.optString("description"),
                architecture = json.getString("architecture").lowercase(Locale.US),
                payloadEntry = json.getString("payloadEntry"),
                payloadSha256 = json.getString("payloadSha256").lowercase(Locale.US),
                payloadSizeBytes = json.getLong("payloadSizeBytes"),
                requiredPaths = json.getJSONArray("requiredPaths").toStringList(),
                commands = json.getJSONArray("commands").toObjectList { item ->
                    ToolpackCommand(
                        name = item.getString("name"),
                        relativePath = item.getString("relativePath"),
                        description = item.optString("description"),
                    )
                },
                selfTests = json.getJSONArray("selfTests").toObjectList { item ->
                    ToolpackSelfTest(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        command = item.getString("command"),
                        expectedExitCodes = item.getJSONArray("expectedExitCodes")
                            .toIntList()
                            .toSet(),
                        outputContains = item.optJSONArray("outputContains")
                            ?.toStringList()
                            .orEmpty(),
                    )
                },
                sources = json.getJSONArray("sources").toObjectList { item ->
                    ToolpackSourceArtifact(
                        name = item.getString("name"),
                        version = item.getString("version"),
                        url = item.getString("url"),
                        sha256 = item.getString("sha256").lowercase(Locale.US),
                    )
                },
            )
        }
    }
}

data class ToolpackInstallResult(
    val manifest: ToolpackPackageManifest,
    val packagePath: String,
    val installedPath: String,
    val payloadBytes: Long,
    val extractedEntries: Int,
    val extractedBytes: Long,
    val durationMillis: Long,
)

data class InstalledToolpack(
    val manifest: ToolpackPackageManifest,
    val packagePath: String,
    val installedPath: String,
    val rootfsVersion: String?,
    val installedAtEpochMillis: Long,
)

data class ToolpackSelfTestResult(
    val test: ToolpackSelfTest,
    val commandResult: ShellCommandResult,
    val passed: Boolean,
    val failure: String?,
)

data class ToolpackSelfTestReport(
    val manifest: ToolpackPackageManifest,
    val results: List<ToolpackSelfTestResult>,
) {
    val passed: Boolean
        get() = results.isNotEmpty() && results.all(ToolpackSelfTestResult::passed)
}

object ToolpackPathPolicy {
    fun validateRelativePath(path: String) {
        require(path.isNotBlank()) { "toolpack 路径不能为空" }
        require(path.length <= TOOLPACK_MAX_RELATIVE_PATH_CHARS) { "toolpack 路径过长" }
        require(!path.startsWith('/')) { "toolpack 路径不能是绝对路径：$path" }
        require('\u0000' !in path) { "toolpack 路径包含 NUL" }
        require(
            path.split('/').none { segment ->
                segment.isEmpty() || segment == "." || segment == ".."
            },
        ) { "toolpack 路径非法：$path" }
    }

    fun resolve(root: File, relativePath: String): File {
        validateRelativePath(relativePath)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        require(target.path.startsWith("${canonicalRoot.path}${File.separator}")) {
            "toolpack 路径越界：$relativePath"
        }
        return target
    }
}

internal val TOOLPACK_SAFE_ID_REGEX = Regex("[A-Za-z0-9._-]{1,120}")
private val TOOLPACK_SAFE_VERSION_REGEX = Regex("[A-Za-z0-9._+-]{1,160}")
private val TOOLPACK_COMMAND_NAME_REGEX = Regex("[A-Za-z0-9._+-]{1,64}")
private val TOOLPACK_SHA256_REGEX = Regex("[a-fA-F0-9]{64}")
private const val TOOLPACK_MAX_VERSION_CHARS = 160
private const val TOOLPACK_MAX_TITLE_CHARS = 200
private const val TOOLPACK_MAX_DESCRIPTION_CHARS = 2_000
private const val TOOLPACK_MAX_SELF_TEST_COMMAND_CHARS = 4_096
private const val TOOLPACK_MAX_EXPECTED_OUTPUT_CHARS = 512
private const val TOOLPACK_MAX_RELATIVE_PATH_CHARS = 512

private fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) add(getString(index))
}

private fun JSONArray.toIntList(): List<Int> = buildList {
    for (index in 0 until length()) add(getInt(index))
}

private fun <T> JSONArray.toObjectList(transform: (JSONObject) -> T): List<T> = buildList {
    for (index in 0 until length()) add(transform(getJSONObject(index)))
}
