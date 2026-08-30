package com.luckylca.autocrack.runtime

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val INSTALLED_RECORD_SCHEMA_VERSION = 1

private data class ToolpackActivation(
    val target: File,
    val backup: File?,
)

private data class ActiveLinkActivation(
    val link: File,
    val previousTarget: Path?,
)

internal fun parseInstalledToolpackRecord(
    text: String,
    requireTrusted: Boolean,
): InstalledToolpack {
    val json = JSONObject(text)
    require(json.getInt("schemaVersion") == INSTALLED_RECORD_SCHEMA_VERSION) {
        "不支持的已安装工具包记录 schema"
    }
    val manifest = ToolpackPackageManifest.parse(json.getJSONObject("manifest").toString())
    if (requireTrusted) BuiltInToolpackTrustPolicy.requireTrusted(manifest)
    return InstalledToolpack(
        manifest = manifest,
        packagePath = json.getString("packagePath"),
        installedPath = json.getString("installedPath"),
        rootfsVersion = json.optString("rootfsVersion")
            .takeIf { value -> value.isNotBlank() && value != "null" },
        installedAtEpochMillis = json.getLong("installedAtEpochMillis"),
    )
}

internal fun obsoleteToolpackCommandNames(
    previous: List<ToolpackCommand>,
    current: List<ToolpackCommand>,
): Set<String> {
    val currentNames = current.map(ToolpackCommand::name).toSet()
    return previous
        .map(ToolpackCommand::name)
        .filterNot(currentNames::contains)
        .toSet()
}

class ToolpackPackageInstaller(
    context: Context,
    private val layout: RuntimeLayout,
) {
    private val appContext = context.applicationContext
    private val packagesRoot = File(layout.toolpacksRoot, "packages")
    private val installedRecordsRoot = File(layout.toolpacksRoot, "installed")
    private val auditFile = File(layout.toolpacksRoot, "toolpack-audit.jsonl")
    private val rootfsToolpacksRoot = File(layout.rootfsRoot, "opt/autocrack/toolpacks")
    private val rootfsPacksRoot = File(rootfsToolpacksRoot, "packs")
    private val rootfsActiveRoot = File(rootfsToolpacksRoot, "active")
    private val rootfsStagingRoot = File(rootfsToolpacksRoot, "staging")
    private val rootfsCommandRoot = File(layout.rootfsRoot, "usr/local/bin")

    suspend fun install(
        packageUri: Uri,
        onProgress: (String) -> Unit = {},
    ): ToolpackInstallResult = withContext(Dispatchers.IO) {
        requireRuntimeReady()
        initializeAppDirectories()
        initializeRootfsDirectories()

        val startedAt = System.currentTimeMillis()
        val packageFile = File(packagesRoot, "toolpack-${System.currentTimeMillis()}.zip")
        val payloadFile = File(layout.tempRoot, "toolpack-payload-${System.currentTimeMillis()}.zip")
        var manifest: ToolpackPackageManifest? = null
        var previousInstalled: InstalledToolpack? = null
        var stagingDirectory: File? = null
        var activation: ToolpackActivation? = null
        var activeLinkActivation: ActiveLinkActivation? = null

        try {
            onProgress("正在复制工具包到应用私有目录")
            copyUriToFile(packageUri, packageFile)
            require(packageFile.length() in 1..MAX_PACKAGE_BYTES) {
                "工具包大小非法：${packageFile.length()} B"
            }

            manifest = extractAndVerifyPayload(
                packageFile = packageFile,
                payloadFile = payloadFile,
                onProgress = onProgress,
            )
            previousInstalled = readInstalled(manifest.id, requireTrusted = false)
            validateCommandConflicts(manifest)

            val staging = File(
                rootfsStagingRoot,
                "${manifest.id}-${System.currentTimeMillis()}",
            )
            stagingDirectory = staging
            deleteTreeNoFollow(staging)
            check(staging.mkdirs()) { "无法创建工具包 staging 目录" }

            onProgress("正在安全解包 ${manifest.title}")
            val extraction = extractPayload(payloadFile, staging, onProgress)
            validateExtractedPayload(manifest, staging)

            val target = File(File(rootfsPacksRoot, manifest.id), manifest.version)
            onProgress("正在原子激活工具包 ${manifest.version}")
            activation = activateStaging(staging, target)
            stagingDirectory = null

            activeLinkActivation = activateLink(manifest.id, manifest.version)
            installCommandShims(manifest)
            val installed = InstalledToolpack(
                manifest = manifest,
                packagePath = packageFile.path,
                installedPath = target.path,
                rootfsVersion = layout.readRootfsVersion(),
                installedAtEpochMillis = System.currentTimeMillis(),
            )
            writeInstalledRecord(installed)
            removeObsoleteCommandShims(previousInstalled?.manifest, manifest)
            val obsoleteBackup = activation.backup
            activation = null
            activeLinkActivation = null
            runCatching { obsoleteBackup?.let(::deleteTreeNoFollow) }
            runCatching { pruneOldVersions(manifest.id, target) }

            appendAudit(
                event = "install",
                manifest = manifest,
                detail = JSONObject()
                    .put("packagePath", packageFile.path)
                    .put("installedPath", target.path)
                    .put("payloadBytes", payloadFile.length())
                    .put("extractedEntries", extraction.first)
                    .put("extractedBytes", extraction.second),
            )
            ToolpackInstallResult(
                manifest = manifest,
                packagePath = packageFile.path,
                installedPath = target.path,
                payloadBytes = payloadFile.length(),
                extractedEntries = extraction.first,
                extractedBytes = extraction.second,
                durationMillis = System.currentTimeMillis() - startedAt,
            )
        } catch (exception: Exception) {
            stagingDirectory?.let(::deleteTreeNoFollow)
            activeLinkActivation?.let { active -> runCatching { rollbackActiveLink(active) } }
            activation?.let { installedTarget -> runCatching { rollbackActivation(installedTarget) } }
            manifest?.let { failedManifest ->
                runCatching { restoreInstallMetadata(previousInstalled, failedManifest) }
            }
            appendAudit(
                event = "install_failed",
                manifest = manifest,
                detail = JSONObject()
                    .put("packagePath", packageFile.path)
                    .put("failure", exception.message ?: exception::class.java.name),
            )
            throw exception
        } finally {
            payloadFile.delete()
        }
    }

    suspend fun uninstall(
        toolpackId: String,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        require(toolpackId.matches(TOOLPACK_SAFE_ID_REGEX)) { "非法 toolpack id：$toolpackId" }
        initializeAppDirectories()
        val installed = readInstalled(toolpackId, requireTrusted = false)
            ?: error("工具包未安装：$toolpackId")

        onProgress("正在移除 ${installed.manifest.title}")
        installed.manifest.commands.forEach { command ->
            val shim = File(rootfsCommandRoot, command.name)
            val marker = toolpackMarker(toolpackId)
            if (shim.isFile && runCatching {
                    shim.readText(Charsets.UTF_8).contains(marker)
                }.getOrDefault(false)
            ) {
                shim.delete()
            }
        }
        removeActiveLink(toolpackId)
        deleteTreeNoFollow(File(rootfsPacksRoot, toolpackId))
        recordFile(toolpackId).delete()
        appendAudit(
            event = "uninstall",
            manifest = installed.manifest,
            detail = JSONObject().put("installedPath", installed.installedPath),
        )
        onProgress("工具包已卸载")
    }

    suspend fun listInstalled(): List<InstalledToolpack> = withContext(Dispatchers.IO) {
        initializeAppDirectories()
        val installed = installedRecordsRoot.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    parseInstalledToolpackRecord(file.readText(Charsets.UTF_8), requireTrusted = true)
                }.getOrNull()
            }
            .sortedBy { installed -> installed.manifest.id }
        if (layout.rootfsRoot.isDirectory) {
            initializeRootfsDirectories()
            reconcileActiveLinks(installed)
        }
        installed
    }

    suspend fun runSelfTests(
        installed: InstalledToolpack,
        chrootEngine: ChrootRuntimeEngine,
        onProgress: (String) -> Unit = {},
    ): ToolpackSelfTestReport {
        BuiltInToolpackTrustPolicy.requireTrusted(installed.manifest)
        require(File(installed.installedPath).isDirectory) {
            "工具包安装目录不存在；rootfs 更新后请重新安装工具包"
        }

        val results = installed.manifest.selfTests.map { test ->
            onProgress("正在执行自检：${test.title}")
            val commandResult = chrootEngine.execute(
                ShellCommandRequest(
                    command = test.command,
                    workingDirectory = "/workspace",
                    timeoutMillis = SELF_TEST_TIMEOUT_MILLIS,
                ),
            )
            val combinedOutput = commandResult.stdout + "\n" + commandResult.stderr
            val failure = when {
                commandResult.timedOut -> "自检超时"
                commandResult.cancelled -> "自检被取消"
                commandResult.failure != null -> commandResult.failure
                commandResult.exitCode !in test.expectedExitCodes ->
                    "退出码 ${commandResult.exitCode} 不在 ${test.expectedExitCodes.sorted()} 中"
                else -> test.outputContains
                    .firstOrNull { expected -> !combinedOutput.contains(expected) }
                    ?.let { expected -> "输出缺少：$expected" }
            }
            ToolpackSelfTestResult(
                test = test,
                commandResult = commandResult,
                passed = failure == null,
                failure = failure,
            )
        }
        val report = ToolpackSelfTestReport(installed.manifest, results)
        withContext(Dispatchers.IO) {
            appendAudit(
                event = "self_test",
                manifest = installed.manifest,
                detail = JSONObject()
                    .put("passed", report.passed)
                    .put(
                        "results",
                        JSONArray(results.map { result ->
                            JSONObject()
                                .put("id", result.test.id)
                                .put("passed", result.passed)
                                .put(
                                    "exitCode",
                                    result.commandResult.exitCode ?: JSONObject.NULL,
                                )
                                .put("failure", result.failure ?: JSONObject.NULL)
                        }),
                    ),
            )
        }
        return report
    }

    private fun requireRuntimeReady() {
        layout.initialize()
        require(layout.readRootfsState() == RuntimeRootfsState.INSTALLED) {
            "必须先安装 Debian rootfs"
        }
        require(layout.rootfsRoot.isDirectory) { "rootfs current 目录不存在" }
    }

    private fun initializeAppDirectories() {
        listOf(layout.toolpacksRoot, packagesRoot, installedRecordsRoot).forEach(::ensureDirectory)
    }

    private fun initializeRootfsDirectories() {
        listOf(
            rootfsToolpacksRoot,
            rootfsPacksRoot,
            rootfsActiveRoot,
            rootfsStagingRoot,
            rootfsCommandRoot,
        ).forEach(::ensureDirectory)
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists()) check(directory.mkdirs()) { "无法创建目录：${directory.path}" }
        require(directory.isDirectory) { "目标不是目录：${directory.path}" }
    }

    private fun copyUriToFile(uri: Uri, destination: File) {
        destination.parentFile?.mkdirs()
        appContext.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开所选工具包" }
            FileOutputStream(destination).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_PACKAGE_BYTES) { "工具包超过允许上限" }
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private fun extractAndVerifyPayload(
        packageFile: File,
        payloadFile: File,
        onProgress: (String) -> Unit,
    ): ToolpackPackageManifest = ZipFile(packageFile).use { outerZip ->
        val manifestEntry = outerZip.getEntry(MANIFEST_ENTRY)
            ?: error("工具包缺少 $MANIFEST_ENTRY")
        require(manifestEntry.size in 1..MAX_MANIFEST_BYTES) { "工具包 manifest 大小非法" }
        val parsed = outerZip.getInputStream(manifestEntry)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> ToolpackPackageManifest.parse(reader.readText()) }

        BuiltInToolpackTrustPolicy.requireTrusted(parsed)
        val payloadEntry = outerZip.getEntry(parsed.payloadEntry)
            ?: error("工具包缺少 ${parsed.payloadEntry}")

        onProgress("正在校验工具包 payload SHA-256")
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        outerZip.getInputStream(payloadEntry).use { input ->
            FileOutputStream(payloadFile).buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= ToolpackPackageManifest.MAX_PAYLOAD_BYTES) {
                        "工具包 payload 超过允许上限"
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        val actualSha256 = digest.digest().toHex()
        require(copied == parsed.payloadSizeBytes) {
            "工具包 payload 大小不匹配：manifest=${parsed.payloadSizeBytes}, actual=$copied"
        }
        require(actualSha256 == parsed.payloadSha256) {
            "工具包 payload SHA-256 不匹配：manifest=${parsed.payloadSha256}, actual=$actualSha256"
        }
        parsed
    }

    private fun extractPayload(
        payloadFile: File,
        destination: File,
        onProgress: (String) -> Unit,
    ): Pair<Int, Long> {
        var entryCount = 0
        var extractedBytes = 0L
        ZipFile(payloadFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= MAX_ENTRIES) { "工具包条目数量超过上限" }
                val relativePath = entry.name.removeSuffix("/")
                val target = ToolpackPathPolicy.resolve(destination, relativePath)
                if (entry.isDirectory) {
                    ensureDirectory(target)
                    continue
                }

                target.parentFile?.mkdirs()
                var fileBytes = 0L
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "工具包解包大小超过上限"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (entry.size >= 0L) {
                    require(fileBytes == entry.size) {
                        "工具包条目大小不匹配：${entry.name}"
                    }
                }
                if (entryCount % PROGRESS_ENTRY_INTERVAL == 0) {
                    onProgress(
                        "已解包 $entryCount 个条目，${extractedBytes / 1_048_576L} MiB",
                    )
                }
            }
        }
        return entryCount to extractedBytes
    }

    private fun validateExtractedPayload(
        manifest: ToolpackPackageManifest,
        staging: File,
    ) {
        manifest.requiredPaths.forEach { requiredPath ->
            val required = ToolpackPathPolicy.resolve(staging, requiredPath)
            require(required.exists()) { "工具包缺少必需路径：$requiredPath" }
        }
        restoreExecutablePayloadModes(staging)
        manifest.commands.forEach { command ->
            val executable = ToolpackPathPolicy.resolve(staging, command.relativePath)
            require(executable.isFile) { "工具命令不是普通文件：${command.relativePath}" }
            Os.chmod(executable.path, EXECUTABLE_MODE)
        }
    }

    private fun restoreExecutablePayloadModes(staging: File) {
        // java.util.zip extraction does not preserve Unix executable mode. Restore standard
        // executable namespaces for both public entrypoints and tool-private runtimes, e.g.
        // lib/llvm-14/bin/lldb and lib/llvm-14/bin/lldb-argdumper.
        staging.walkTopDown()
            .filter(File::isFile)
            .forEach { file ->
                val relativePath = file.relativeTo(staging).invariantSeparatorsPath
                if (shouldRestoreToolpackExecutableMode(file, relativePath)) {
                    Os.chmod(file.path, EXECUTABLE_MODE)
                }
            }
    }

    private fun validateCommandConflicts(manifest: ToolpackPackageManifest) {
        manifest.commands.forEach { command ->
            val shim = File(rootfsCommandRoot, command.name)
            if (!shim.exists()) return@forEach
            val marker = runCatching { shim.readText(Charsets.UTF_8) }.getOrDefault("")
            require(marker.contains(toolpackMarker(manifest.id))) {
                "工具命令冲突：/usr/local/bin/${command.name} 已由其他来源提供"
            }
        }
    }

    private fun activateStaging(staging: File, target: File): ToolpackActivation {
        target.parentFile?.mkdirs()
        val backup = if (target.exists()) {
            File(target.parentFile, "${target.name}.backup-${System.currentTimeMillis()}").also {
                safeMove(target, it)
            }
        } else {
            null
        }
        return try {
            safeMove(staging, target)
            ToolpackActivation(target = target, backup = backup)
        } catch (exception: Exception) {
            if (!target.exists() && backup?.exists() == true) safeMove(backup, target)
            throw exception
        }
    }

    private fun rollbackActivation(activation: ToolpackActivation) {
        deleteTreeNoFollow(activation.target)
        if (activation.backup?.exists() == true) {
            safeMove(activation.backup, activation.target)
        }
    }

    private fun activateLink(toolpackId: String, version: String): ActiveLinkActivation {
        val link = File(rootfsActiveRoot, toolpackId)
        val linkPath = link.toPath()
        require(!link.exists() || Files.isSymbolicLink(linkPath)) {
            "active toolpack 路径不是 symlink：${link.path}"
        }
        val previousTarget = if (Files.isSymbolicLink(linkPath)) {
            Files.readSymbolicLink(linkPath)
        } else {
            null
        }
        replaceActiveLink(link, activeLinkTarget(toolpackId, version))
        return ActiveLinkActivation(link = link, previousTarget = previousTarget)
    }

    private fun rollbackActiveLink(activation: ActiveLinkActivation) {
        val previous = activation.previousTarget
        if (previous == null) {
            Files.deleteIfExists(activation.link.toPath())
        } else {
            replaceActiveLink(activation.link, previous)
        }
    }

    private fun replaceActiveLink(link: File, target: Path) {
        rootfsActiveRoot.mkdirs()
        val temporary = File(rootfsActiveRoot, ".${link.name}.${System.nanoTime()}.tmp")
        Files.deleteIfExists(temporary.toPath())
        Files.createSymbolicLink(temporary.toPath(), target)
        try {
            safeMove(temporary, link)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun activeLinkTarget(toolpackId: String, version: String): Path =
        Path.of("..", "packs", toolpackId, version)

    private fun removeActiveLink(toolpackId: String) {
        val link = File(rootfsActiveRoot, toolpackId)
        if (Files.isSymbolicLink(link.toPath())) Files.deleteIfExists(link.toPath())
    }

    private fun reconcileActiveLinks(installed: List<InstalledToolpack>) {
        val installedById = installed.associateBy { item -> item.manifest.id }
        rootfsActiveRoot.listFiles().orEmpty().forEach { candidate ->
            if (Files.isSymbolicLink(candidate.toPath()) && candidate.name !in installedById) {
                Files.deleteIfExists(candidate.toPath())
            }
        }
        installed.forEach { item ->
            val installedDirectory = File(item.installedPath)
            if (!installedDirectory.isDirectory) return@forEach
            val expected = activeLinkTarget(item.manifest.id, item.manifest.version)
            val link = File(rootfsActiveRoot, item.manifest.id)
            val current = if (Files.isSymbolicLink(link.toPath())) {
                Files.readSymbolicLink(link.toPath())
            } else {
                null
            }
            if (current != expected) replaceActiveLink(link, expected)
        }
    }

    private fun installCommandShims(manifest: ToolpackPackageManifest) {
        rootfsCommandRoot.mkdirs()
        manifest.commands.forEach { command ->
            val chrootExecutable =
                "${ToolpackSharedEnvironment.ACTIVE_PACK_ROOT}/${manifest.id}/${command.relativePath}"
            val shim = File(rootfsCommandRoot, command.name)
            val temporary = File(
                rootfsCommandRoot,
                ".${command.name}.${System.nanoTime()}.tmp",
            )
            temporary.writeText(
                buildString {
                    appendLine("#!/bin/sh")
                    appendLine(toolpackMarker(manifest.id))
                    append("exec ")
                        .append(ShellEscaper.quote(chrootExecutable))
                        .appendLine(" \"${'$'}@\"")
                },
                Charsets.UTF_8,
            )
            Os.chmod(temporary.path, EXECUTABLE_MODE)
            safeMove(temporary, shim)
        }
    }

    private fun removeObsoleteCommandShims(
        previous: ToolpackPackageManifest?,
        current: ToolpackPackageManifest,
    ) {
        obsoleteToolpackCommandNames(previous?.commands.orEmpty(), current.commands)
            .forEach { commandName -> removeOwnedCommandShim(current.id, commandName) }
    }

    private fun removeOwnedCommandShim(toolpackId: String, commandName: String) {
        val shim = File(rootfsCommandRoot, commandName)
        val owned = shim.isFile && runCatching {
            shim.readText(Charsets.UTF_8).contains(toolpackMarker(toolpackId))
        }.getOrDefault(false)
        if (owned) Files.deleteIfExists(shim.toPath())
    }

    private fun restoreInstallMetadata(
        previous: InstalledToolpack?,
        failedManifest: ToolpackPackageManifest,
    ) {
        failedManifest.commands.forEach { command ->
            if (previous?.manifest?.commands?.none { old -> old.name == command.name } != false) {
                removeOwnedCommandShim(failedManifest.id, command.name)
            }
        }
        if (previous == null) {
            recordFile(failedManifest.id).delete()
        } else {
            installCommandShims(previous.manifest)
            writeInstalledRecord(previous)
        }
    }

    private fun pruneOldVersions(toolpackId: String, activeTarget: File) {
        val packRoot = File(rootfsPacksRoot, toolpackId)
        packRoot.listFiles()
            .orEmpty()
            .filter { candidate -> candidate.canonicalFile != activeTarget.canonicalFile }
            .forEach(::deleteTreeNoFollow)
    }

    private fun writeInstalledRecord(installed: InstalledToolpack) {
        val json = JSONObject()
            .put("schemaVersion", INSTALLED_RECORD_SCHEMA_VERSION)
            .put("manifest", installed.manifest.toJson())
            .put("packagePath", installed.packagePath)
            .put("installedPath", installed.installedPath)
            .put("rootfsVersion", installed.rootfsVersion ?: JSONObject.NULL)
            .put("installedAtEpochMillis", installed.installedAtEpochMillis)
        val destination = recordFile(installed.manifest.id)
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${System.nanoTime()}.tmp",
        )
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        safeMove(temporary, destination)
    }

    private fun readInstalled(toolpackId: String, requireTrusted: Boolean = true): InstalledToolpack? {
        val file = recordFile(toolpackId)
        return if (file.isFile) {
            parseInstalledToolpackRecord(file.readText(Charsets.UTF_8), requireTrusted)
        } else {
            null
        }
    }

    private fun recordFile(toolpackId: String): File =
        File(installedRecordsRoot, "$toolpackId.json")

    private fun appendAudit(
        event: String,
        manifest: ToolpackPackageManifest?,
        detail: JSONObject,
    ) {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("event", event)
            .put("toolpackId", manifest?.id ?: JSONObject.NULL)
            .put("toolpackVersion", manifest?.version ?: JSONObject.NULL)
            .put("rootfsVersion", layout.readRootfsVersion() ?: JSONObject.NULL)
            .put("timestampEpochMillis", System.currentTimeMillis())
            .put("detail", detail)
        synchronized(AUDIT_LOCK) {
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(json.toString() + "\n", Charsets.UTF_8)
        }
    }

    private fun safeMove(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        runCatching {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun deleteTreeNoFollow(root: File) {
        if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                exception: java.io.IOException?,
            ): FileVisitResult {
                if (exception != null) throw exception
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun toolpackMarker(id: String): String = "# AutoCrackApp toolpack:$id"

    private companion object {
        val AUDIT_LOCK = Any()
        const val MANIFEST_ENTRY = "manifest.json"
        const val MAX_MANIFEST_BYTES = 1_048_576L
        const val MAX_PACKAGE_BYTES = 1_600_000_000L
        const val MAX_EXTRACTED_BYTES = 2_500_000_000L
        const val MAX_ENTRIES = 100_000
        const val COPY_BUFFER_BYTES = 128 * 1024
        const val PROGRESS_ENTRY_INTERVAL = 500
        const val EXECUTABLE_MODE = 0b111101101
        const val SELF_TEST_TIMEOUT_MILLIS = 120_000L
    }
}

internal fun isToolpackExecutablePayloadPath(relativePath: String): Boolean {
    val parts = relativePath.split('/').filter(String::isNotEmpty)
    if (parts.size < 2) return false
    if (parts.first() == "bin" || parts.first() == "host-bin") return true
    return parts.first() == "lib" && parts.drop(1).dropLast(1).contains("bin")
}

internal fun shouldRestoreToolpackExecutableMode(file: File, relativePath: String): Boolean {
    if (isToolpackExecutablePayloadPath(relativePath)) return true
    // Trusted packages may contain private helper executables outside a conventional bin directory
    // (Debian python3-lldb does this for lldb-argdumper). Limit content probing to extensionless
    // files so ordinary shared libraries, Python modules and data files keep their normal modes.
    if (file.extension.isNotEmpty()) return false
    return runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            val isElf = read >= 4 &&
                header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            val hasShebang = read >= 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
            isElf || hasShebang
        }
    }.getOrDefault(false)
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}
