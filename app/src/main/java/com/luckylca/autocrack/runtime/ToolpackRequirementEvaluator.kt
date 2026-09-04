package com.luckylca.autocrack.runtime

/**
 * Host-side snapshot of the shared AutoCrack Runtime contract.
 *
 * RuntimeDispatcher is the protocol authority inside the LSPosed runtime APK. Keep this snapshot
 * intentionally boring and cover it with a source-contract test so the app-side Toolpack
 * compatibility evaluator cannot silently drift from RuntimeDispatcher.VERSION/capabilities().
 */
object ToolpackRuntimeContract {
    const val VERSION = "1.0.0"

    val CAPABILITIES: Set<String> = setOf(
        "ui.windows", "ui.tree", "ui.at", "ui.find", "ui.props", "ui.parent", "ui.children",
        "ui.siblings", "ui.listeners", "ui.stack", "ui.image", "ui.image.result", "ui.action",
        "ui.compose.status", "ui.compose.tree",
        "runtime.process", "runtime.doctor", "runtime.activities", "runtime.declared_activities",
        "runtime.classloaders", "runtime.class.search", "runtime.class.describe",
        "object.describe", "object.fields", "object.dump", "object.pin", "object.release",
        "object.clear_session",
        "memory.maps", "memory.modules", "memory.native.modules", "memory.read",
        "memory.native.probe", "memory.dladdr", "memory.module.dump", "memory.module.file_dump",
        "memory.elf.info", "memory.elf.symbols", "memory.elf.relocations", "memory.elf.dynamic",
        "memory.dex.list", "memory.dex.art_probe", "memory.dex.art_pointer_probe",
        "memory.dex.art_dump", "memory.dex.art_export.open", "memory.dex.art_export.chunk",
        "memory.dex.art_export.close", "memory.dex.info", "memory.dex.apk_index",
        "memory.dex.strings", "memory.dex.classes", "memory.dex.fields", "memory.dex.methods",
        "memory.dex.class_data", "memory.dex.scan", "memory.dex.dump", "memory.assets.list",
        "memory.assets.pull", "memory.xml.pull", "memory.xml.block_probe", "memory.xml.binary",
        "memory.xml.axml_decode", "memory.xml.axml_text", "memory.apk.entries", "memory.apk.pull",
        "memory.capabilities",
        "webview.list", "webview.info", "webview.debug", "webview.devtools_socket", "webview.eval",
        "webview.eval.result", "webview.load_url", "webview.reload", "webview.go_back",
        "webview.go_forward", "webview.clear_cache",
        "control.secure.status", "control.secure.diagnose", "control.secure.disable",
        "control.so.inject", "control.so.diagnose", "control.so.dlopen",
        "control.so.android_dlopen_ext", "control.so.dlsym", "control.activity.start",
        "control.process.kill", "control.object.field.set", "control.object.method.call",
        "hook.reload", "hook.inspect",
    )
}

data class ToolpackRequirementReport(
    val compatible: Boolean,
    val runtimeConstraint: String?,
    val runtimeVersion: String,
    val missingCapabilities: List<String>,
    val missingCommands: List<String>,
    val missingOptionalCapabilities: List<String>,
    val diagnostics: List<String>,
) {
    val warnings: List<String>
        get() = missingOptionalCapabilities.map { "可选 capability 不可用：$it" }

    fun requireCompatible(toolpackId: String) {
        require(compatible) {
            "工具包 $toolpackId 依赖不满足：${diagnostics.joinToString("；")}"
        }
    }
}

data class ToolpackReadinessReport(
    val id: String,
    val title: String,
    val version: String?,
    val trusted: Boolean,
    val requirements: ToolpackRequirementReport?,
    val missingPaths: List<String>,
    val invalidCommandShims: List<String>,
    val failure: String? = null,
) {
    val healthy: Boolean
        get() = trusted && failure == null && requirements?.compatible != false &&
            missingPaths.isEmpty() && invalidCommandShims.isEmpty()

    val diagnostics: List<String>
        get() = buildList {
            failure?.let(::add)
            if (!trusted) add("manifest/trust 校验失败")
            requirements?.diagnostics?.let(::addAll)
            missingPaths.forEach { add("缺少已安装路径：$it") }
            invalidCommandShims.forEach { add("命令 shim 无效：$it") }
            requirements?.warnings?.let(::addAll)
        }

    fun summary(): String = when {
        healthy && diagnostics.isEmpty() -> "ready"
        healthy -> "ready（${diagnostics.joinToString("；")}）"
        else -> diagnostics.joinToString("；").ifBlank { "not ready" }
    }
}

object ToolpackRequirementEvaluator {
    fun evaluate(
        manifest: ToolpackPackageManifest,
        availableCommands: Set<String>,
        runtimeVersion: String = ToolpackRuntimeContract.VERSION,
        runtimeCapabilities: Set<String> = ToolpackRuntimeContract.CAPABILITIES,
    ): ToolpackRequirementReport {
        val requirements = manifest.requires
        if (manifest.schemaVersion == 1 || requirements == ToolpackRequirements()) {
            return ToolpackRequirementReport(
                compatible = true,
                runtimeConstraint = requirements.runtime,
                runtimeVersion = runtimeVersion,
                missingCapabilities = emptyList(),
                missingCommands = emptyList(),
                missingOptionalCapabilities = emptyList(),
                diagnostics = emptyList(),
            )
        }

        val runtimeOk = requirements.runtime?.let { constraint ->
            SemVerConstraint.matches(runtimeVersion, constraint)
        } ?: true
        val missingCapabilities = requirements.capabilities
            .filterNot(runtimeCapabilities::contains)
            .sorted()
        val ownCommands = manifest.commands.mapTo(mutableSetOf(), ToolpackCommand::name)
        val missingCommands = requirements.commands
            .filterNot(ownCommands::contains)
            .filterNot(availableCommands::contains)
            .sorted()
        val missingOptionalCapabilities = requirements.optionalCapabilities
            .filterNot(runtimeCapabilities::contains)
            .sorted()
        val diagnostics = buildList {
            if (!runtimeOk) {
                add("runtime ${requirements.runtime} 未满足，当前协议版本 $runtimeVersion")
            }
            missingCapabilities.forEach { add("缺少 required capability：$it") }
            missingCommands.forEach { add("缺少 required command：$it") }
        }
        return ToolpackRequirementReport(
            compatible = diagnostics.isEmpty(),
            runtimeConstraint = requirements.runtime,
            runtimeVersion = runtimeVersion,
            missingCapabilities = missingCapabilities,
            missingCommands = missingCommands,
            missingOptionalCapabilities = missingOptionalCapabilities,
            diagnostics = diagnostics,
        )
    }
}

internal object SemVerConstraint {
    private val pattern = Regex("""^\s*(>=|<=|>|<|=|==)?\s*([0-9]+(?:\.[0-9]+){0,2})(?:[-+][A-Za-z0-9.-]+)?\s*$""")

    fun matches(version: String, constraint: String): Boolean {
        val versionParts = parseVersion(version) ?: return false
        val match = pattern.matchEntire(constraint) ?: return false
        val operator = match.groupValues[1].ifBlank { "=" }
        val requiredParts = parseVersion(match.groupValues[2]) ?: return false
        val comparison = compare(versionParts, requiredParts)
        return when (operator) {
            ">=" -> comparison >= 0
            "<=" -> comparison <= 0
            ">" -> comparison > 0
            "<" -> comparison < 0
            "=", "==" -> comparison == 0
            else -> false
        }
    }

    private fun parseVersion(value: String): List<Int>? {
        val core = value.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 3) return null
        val parsed = parts.map { it.toIntOrNull() ?: return null }.toMutableList()
        while (parsed.size < 3) parsed += 0
        return parsed
    }

    private fun compare(left: List<Int>, right: List<Int>): Int {
        for (index in 0..2) {
            val value = left[index].compareTo(right[index])
            if (value != 0) return value
        }
        return 0
    }
}
