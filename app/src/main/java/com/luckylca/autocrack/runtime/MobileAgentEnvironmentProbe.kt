package com.luckylca.autocrack.runtime

import android.content.Context
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class EnvironmentCheckItem(
    val id: String,
    val label: String,
    val healthy: Boolean,
    val detail: String,
    val safelyRepairable: Boolean = false,
)

data class MobileAgentEnvironmentReport(
    val rootStatus: RootStatus,
    val checks: List<EnvironmentCheckItem>,
    val checkedAtEpochMillis: Long = System.currentTimeMillis(),
)

class MobileAgentEnvironmentProbe(context: Context) {
    private val appContext = context.applicationContext
    private val layout = RuntimeLayout(appContext).initialize()
    private val runner = ProcessRootCommandRunner()
    private val rootDetector = RootDetector(runner)

    suspend fun inspect(): MobileAgentEnvironmentReport = withContext(Dispatchers.IO) {
        val root = rootDetector.inspect()
        val checks = mutableListOf<EnvironmentCheckItem>()
        checks += EnvironmentCheckItem(
            id = "root",
            label = "Root",
            healthy = root.isRootGranted,
            detail = root.diagnostic ?: root.provider.name,
        )
        checks += EnvironmentCheckItem(
            id = "su",
            label = "su",
            healthy = root.suPath?.isNotBlank() == true,
            detail = root.suPath ?: "未找到 su",
        )

        val rootfsInstalled = layout.readRootfsState() == RuntimeRootfsState.INSTALLED && layout.rootfsRoot.isDirectory
        checks += EnvironmentCheckItem(
            id = "rootfs",
            label = "Debian RootFS",
            healthy = rootfsInstalled,
            detail = layout.readRootfsVersion() ?: layout.readRootfsState().name,
            safelyRepairable = !rootfsInstalled,
        )
        if (!root.isRootGranted || !rootfsInstalled) {
            listOf("chroot", "mount", "/proc", "/sys", "/dev", "/dev/pts", "Storage", "Network", "bash", "Python").forEach { label ->
                checks += EnvironmentCheckItem(label.lowercase(), label, false, "需要 Root 与已安装 RootFS")
            }
            return@withContext MobileAgentEnvironmentReport(root, checks)
        }

        val suPath = requireNotNull(root.suPath)
        val host = RootShellRuntimeEngine(layout, suPath)
        val hostCheck = host.execute(
            ShellCommandRequest(
                command = """
                    for c in chroot mount; do
                      if command -v "${'$'}c" >/dev/null 2>&1; then echo "${'$'}c=OK"; else echo "${'$'}c=MISSING"; fi
                    done
                """.trimIndent(),
                workingDirectory = "/",
                timeoutMillis = 5_000L,
                identity = HostExecutionIdentity.ROOT,
            ),
        )
        val hostValues = parseKeyValues(hostCheck.stdout)
        checks += commandCheck("chroot", "chroot", hostValues)
        checks += commandCheck("mount", "mount", hostValues)

        val workspace = layout.createRuntimeWorkspace()
        val chroot = ChrootRuntimeEngine(layout, host)
        val result = chroot.execute(
            ShellCommandRequest(
                command = """
                    check_path() { if [ -e "${'$'}1" ]; then echo "${'$'}2=OK"; else echo "${'$'}2=MISSING"; fi; }
                    check_path /proc/self/status proc
                    check_path /sys sys
                    check_path /dev/null dev
                    check_path /dev/pts devpts
                    if touch /workspace/.autocrack-permission-probe 2>/dev/null; then rm -f /workspace/.autocrack-permission-probe; echo storage=OK; else echo storage=FAILED; fi
                    if command -v bash >/dev/null 2>&1; then echo bash=OK; else echo bash=MISSING; fi
                    if command -v python3 >/dev/null 2>&1; then echo python=OK; else echo python=MISSING; fi
                    if command -v git >/dev/null 2>&1; then echo git=OK; else echo git=MISSING; fi
                    if command -v java >/dev/null 2>&1; then echo java=OK; else echo java=MISSING; fi
                    if command -v clang >/dev/null 2>&1; then echo clang=OK; else echo clang=MISSING; fi
                    if python3 - <<'PY' >/dev/null 2>&1
import socket
socket.getaddrinfo('example.com', 443)
PY
                    then echo network=OK; else echo network=FAILED; fi
                """.trimIndent(),
                workingDirectory = "/workspace",
                timeoutMillis = 12_000L,
            ),
        )
        val values = parseKeyValues(result.stdout)
        checks += pathCheck("proc", "/proc", values)
        checks += pathCheck("sys", "/sys", values)
        checks += pathCheck("dev", "/dev", values)
        checks += pathCheck("devpts", "/dev/pts", values)
        checks += pathCheck("storage", "Storage", values)
        checks += pathCheck("network", "Network", values)
        checks += pathCheck("bash", "bash", values)
        checks += pathCheck("python", "Python", values)
        checks += pathCheck("git", "Git", values)
        checks += pathCheck("java", "Java", values)
        checks += pathCheck("clang", "Clang", values)
        return@withContext MobileAgentEnvironmentReport(root, checks)
    }

    suspend fun baseEnvironment(): Map<String, Boolean> = inspect().checks
        .filter { it.label in setOf("bash", "Python") }
        .associate { it.label to it.healthy }

    private fun commandCheck(id: String, label: String, values: Map<String, String>): EnvironmentCheckItem {
        val value = values[id]
        return EnvironmentCheckItem(id, label, value == "OK", value ?: "无法确认")
    }

    private fun pathCheck(id: String, label: String, values: Map<String, String>): EnvironmentCheckItem {
        val value = values[id]
        return EnvironmentCheckItem(id, label, value == "OK", value ?: "无法确认")
    }

    private fun parseKeyValues(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
        }
        .toMap()
}
