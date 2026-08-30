package com.luckylca.autocrack.runtime

import android.content.Context
import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootToolCommand
import com.luckylca.autocrack.root.RootToolCommandFactory
import com.luckylca.autocrack.root.RootToolExecutor
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class HostDebuggerRecoverySnapshot(
    val packageName: String,
    val pid: Int,
    val tracerPid: Int?,
    val orphanVerified: Boolean,
    val authorizationVerified: Boolean,
    val helperSignalSent: Boolean,
    val detachVerified: Boolean,
    val targetSignalAttempted: Boolean,
    val failure: String?,
)

object HostDebuggerRecoveryAuthorization {
    fun expected(packageName: String, pid: Int, tracerPid: Int): String {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        require(tracerPid > 0) { "Tracer PID must be positive" }
        return "RECOVER $packageName $pid $tracerPid"
    }

    fun requireAuthorized(packageName: String, pid: Int, tracerPid: Int, supplied: String) {
        val expected = expected(packageName, pid, tracerPid)
        require(supplied.trim() == expected) {
            "恢复操作只会终止经过身份复核的 AutoCrack LLDB helper；请输入精确授权短语：$expected"
        }
    }
}

object HostDebuggerRecoveryCommandFactory {
    fun buildInspect(
        suPath: String,
        binaryPath: String,
        packageName: String,
        pid: Int,
    ): List<String> = buildValidatedCommand(
        suPath = suPath,
        binaryPath = binaryPath,
        packageName = packageName,
        pid = pid,
    )

    fun buildDetach(
        suPath: String,
        binaryPath: String,
        packageName: String,
        pid: Int,
        expectedTracerPid: Int,
    ): List<String> {
        require(expectedTracerPid > 0) { "Tracer PID must be positive" }
        val base = buildValidatedShell(binaryPath, packageName, pid)
        val shell = """
            $base
            [ "${'$'}tracer" = "$expectedTracerPid" ] || {
              echo "RECOVERY_TRACER_CHANGED expected=$expectedTracerPid actual=${'$'}tracer" >&2
              exit 55
            }
            kill -TERM "${'$'}tracer"
            printf 'orphan_verified=true\ntracer_pid=%s\nhelper_signal_sent=true\n' "${'$'}tracer"
        """.trimIndent()
        requireSuPath(suPath)
        return listOf(suPath, "-c", shell)
    }

    private fun buildValidatedCommand(
        suPath: String,
        binaryPath: String,
        packageName: String,
        pid: Int,
    ): List<String> {
        requireSuPath(suPath)
        return listOf(
            suPath,
            "-c",
            buildValidatedShell(binaryPath, packageName, pid) +
                "\nprintf 'orphan_verified=true\\ntracer_pid=%s\\nhelper_signal_sent=false\\n' \"${'$'}tracer\"",
        )
    }

    /**
     * Accept both the legacy server-side --attach command and the newer targetless gdbserver that
     * subsequently became this target's TracerPid through the typed client vAttach operation.
     */
    private fun buildValidatedShell(
        binaryPath: String,
        packageName: String,
        pid: Int,
    ): String {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID must be positive" }
        require(binaryPath.startsWith('/')) { "Debugger binary path must be absolute" }
        val binary = RootToolCommandFactory.shellQuote(binaryPath)
        val expectedPackage = RootToolCommandFactory.shellQuote(packageName)
        return """
            target_pid=$pid
            target_package=$expectedPackage
            binary=$binary
            target_proc=/proc/${'$'}target_pid
            [ -d "${'$'}target_proc" ] || { echo 'RECOVERY_TARGET_NOT_FOUND' >&2; exit 50; }
            target_argv0=${'$'}(tr '\000' '\n' < "${'$'}target_proc/cmdline" 2>/dev/null | head -n 1)
            case "${'$'}target_argv0" in
              "${'$'}target_package"|"${'$'}target_package":*) ;;
              *) echo "RECOVERY_TARGET_IDENTITY_MISMATCH argv0=${'$'}target_argv0" >&2; exit 51 ;;
            esac
            tracer=${'$'}(awk '/^TracerPid:/ { print ${'$'}2; exit }' "${'$'}target_proc/status" 2>/dev/null)
            [ -n "${'$'}tracer" ] && [ "${'$'}tracer" -gt 0 ] 2>/dev/null || {
              echo 'RECOVERY_TARGET_NOT_TRACED' >&2
              exit 52
            }
            tracer_proc=/proc/${'$'}tracer
            [ -d "${'$'}tracer_proc" ] || { echo "RECOVERY_TRACER_NOT_FOUND pid=${'$'}tracer" >&2; exit 53; }
            tracer_argv0=${'$'}(tr '\000' '\n' < "${'$'}tracer_proc/cmdline" 2>/dev/null | head -n 1)
            [ "${'$'}tracer_argv0" = "${'$'}binary" ] || {
              echo "RECOVERY_HELPER_IDENTITY_MISMATCH tracer=${'$'}tracer argv0=${'$'}tracer_argv0" >&2
              exit 54
            }
            tracer_cmdline=${'$'}(tr '\000' ' ' < "${'$'}tracer_proc/cmdline" 2>/dev/null | sed 's/[[:space:]]*$//')
            case "${'$'}tracer_cmdline" in
              "${'$'}binary gdbserver 127.0.0.1:"*" --attach ${'$'}target_pid"*) ;;
              "${'$'}binary gdbserver 127.0.0.1:"*)
                case "${'$'}tracer_cmdline" in
                  *" --attach "*) echo "RECOVERY_HELPER_COMMAND_MISMATCH tracer=${'$'}tracer" >&2; exit 54 ;;
                  *) ;;
                esac
                ;;
              *) echo "RECOVERY_HELPER_COMMAND_MISMATCH tracer=${'$'}tracer" >&2; exit 54 ;;
            esac
        """.trimIndent()
    }

    private fun requireSuPath(suPath: String) {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) {
            "su path contains an invalid character"
        }
    }
}

class HostDebuggerRecoveryBridge(
    context: Context,
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
    private val runner: RootCommandRunner = ProcessRootCommandRunner(),
) {
    val auditFile: File = File(layout.auditRoot, "dynamic-debugger-recovery.jsonl")

    private val installer = ToolpackPackageInstaller(context, layout)

    suspend fun inspect(packageName: String, pid: Int): HostDebuggerRecoverySnapshot {
        PackageOutputParser.requireValidPackageName(packageName)
        require(pid > 0) { "PID 必须是正整数" }
        val environment = requireEnvironment()
        val result = runner.run(
            command = HostDebuggerRecoveryCommandFactory.buildInspect(
                suPath = environment.suPath,
                binaryPath = environment.binary.path,
                packageName = packageName,
                pid = pid,
            ),
            label = "Inspect orphaned LLDB helper for $packageName/$pid",
            timeoutMillis = COMMAND_TIMEOUT_MILLIS,
        )
        val tracerPid = parseTracerPid(result.stdout)
        val snapshot = HostDebuggerRecoverySnapshot(
            packageName = packageName,
            pid = pid,
            tracerPid = tracerPid,
            orphanVerified = result.succeeded && result.stdout.lineSequence().any { it == "orphan_verified=true" },
            authorizationVerified = false,
            helperSignalSent = false,
            detachVerified = false,
            targetSignalAttempted = false,
            failure = if (result.succeeded) null else {
                result.failure ?: result.stderr.ifBlank { "遗留 LLDB helper 身份复核失败" }
            },
        )
        appendAudit("inspect", snapshot)
        return snapshot
    }

    suspend fun recoverDetach(
        packageName: String,
        pid: Int,
        tracerPid: Int,
        authorizationPhrase: String,
    ): HostDebuggerRecoverySnapshot {
        HostDebuggerRecoveryAuthorization.requireAuthorized(packageName, pid, tracerPid, authorizationPhrase)
        val environment = requireEnvironment()
        val result = runner.run(
            command = HostDebuggerRecoveryCommandFactory.buildDetach(
                suPath = environment.suPath,
                binaryPath = environment.binary.path,
                packageName = packageName,
                pid = pid,
                expectedTracerPid = tracerPid,
            ),
            label = "Safely detach orphaned AutoCrack LLDB helper $tracerPid",
            timeoutMillis = COMMAND_TIMEOUT_MILLIS,
        )
        require(result.succeeded) {
            result.failure ?: result.stderr.ifBlank { "遗留 LLDB helper TERM 失败" }
        }

        val executor = RootToolExecutor(runner, environment.suPath)
        var detachVerified = false
        repeat(DETACH_VERIFY_ATTEMPTS) {
            val identity = executor.execute(RootToolCommand.ReadProcessIdentity(pid))
            val status = identity.takeIf { it.succeeded }
                ?.let { HostDebuggerTargetStatusParser.parse(it.stdout) }
            if (status?.tracerPid == 0) {
                detachVerified = true
                return@repeat
            }
            delay(DETACH_VERIFY_DELAY_MILLIS)
        }
        val snapshot = HostDebuggerRecoverySnapshot(
            packageName = packageName,
            pid = pid,
            tracerPid = tracerPid,
            orphanVerified = true,
            authorizationVerified = true,
            helperSignalSent = true,
            detachVerified = detachVerified,
            targetSignalAttempted = false,
            failure = if (detachVerified) null else "LLDB helper 已发送 TERM，但尚未观察到目标 TracerPid=0",
        )
        appendAudit(if (detachVerified) "recovery_detach_verified" else "recovery_detach_unverified", snapshot)
        return snapshot
    }

    private suspend fun requireEnvironment(): RecoveryEnvironment {
        layout.initialize()
        val rootStatus = rootDetector.inspect()
        require(rootStatus.isRootGranted) { rootStatus.diagnostic ?: "Debugger recovery 需要 Root 权限" }
        val suPath = requireNotNull(rootStatus.suPath) { "Root 已授权但没有可用的 su 路径" }
        val installed = installer.listInstalled().firstOrNull { toolpack ->
            toolpack.manifest.id == HostDebuggerSessionManager.TOOLPACK_ID &&
                toolpack.manifest.version == HostDebuggerSessionManager.TOOLPACK_VERSION
        } ?: error("未安装受信任 Android LLDB server 工具包")
        BuiltInToolpackTrustPolicy.requireTrusted(installed.manifest)
        val root = File(installed.installedPath).canonicalFile
        val binary = File(root, LLDB_SERVER_RELATIVE_PATH).canonicalFile
        require(binary.path.startsWith(root.path + File.separator)) { "LLDB server 路径越界" }
        require(binary.isFile && binary.canExecute()) { "LLDB server 二进制不可执行" }
        val expected = installed.manifest.sources.firstOrNull { it.name == "lldb-server" }?.sha256
            ?: error("LLDB server manifest 缺少 source SHA-256")
        require(sha256(binary) == expected) { "LLDB server 二进制 SHA-256 不匹配" }
        return RecoveryEnvironment(suPath = suPath, binary = binary)
    }

    private fun parseTracerPid(stdout: String): Int? = stdout.lineSequence()
        .firstOrNull { it.startsWith("tracer_pid=") }
        ?.substringAfter('=')?.toIntOrNull()

    private suspend fun appendAudit(event: String, snapshot: HostDebuggerRecoverySnapshot) =
        withContext(Dispatchers.IO) {
            auditFile.parentFile?.mkdirs()
            val record = JSONObject()
                .put("schemaVersion", 1)
                .put("timestampEpochMillis", System.currentTimeMillis())
                .put("event", event)
                .put("packageName", snapshot.packageName)
                .put("pid", snapshot.pid)
                .put("tracerPid", snapshot.tracerPid ?: JSONObject.NULL)
                .put("orphanVerified", snapshot.orphanVerified)
                .put("authorizationVerified", snapshot.authorizationVerified)
                .put("helperSignalSent", snapshot.helperSignalSent)
                .put("targetSignalAttempted", false)
                .put("detachVerified", snapshot.detachVerified)
                .put("failure", snapshot.failure ?: JSONObject.NULL)
            synchronized(AUDIT_LOCK) { auditFile.appendText(record.toString() + "\n", Charsets.UTF_8) }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private data class RecoveryEnvironment(val suPath: String, val binary: File)

    companion object {
        private const val LLDB_SERVER_RELATIVE_PATH = "host-bin/lldb-server-android"
        private const val COMMAND_TIMEOUT_MILLIS = 3_000L
        private const val DETACH_VERIFY_ATTEMPTS = 25
        private const val DETACH_VERIFY_DELAY_MILLIS = 120L
        private val AUDIT_LOCK = Any()
    }
}
