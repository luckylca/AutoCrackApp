package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus

class KernelSuHostBridge(
    private val layout: RuntimeLayout,
    private val rootDetector: RootDetector,
) : HostBridge {
    override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.FULL_ROOT

    override suspend fun inspectHealth(): RuntimeHealthReport {
        layout.initialize()
        val rootStatus = rootDetector.inspect()
        if (!rootStatus.isRootGranted) {
            return unavailableReport(rootStatus)
        }

        val engine = RootShellRuntimeEngine(
            layout = layout,
            suPath = rootStatus.suPath ?: "/system/bin/su",
        )
        val workspace = layout.createRuntimeWorkspace()
        val result = engine.execute(
            ShellCommandRequest(
                command = HEALTH_COMMAND,
                workingDirectory = workspace.path,
                timeoutMillis = 15_000L,
            ),
        )

        val values = result.stdout.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val commandPaths = HEALTH_COMMAND_NAMES.associateWith { command -> values["CMD_$command"] }
        val available = commandPaths.filterValues { value -> !value.isNullOrBlank() }.keys.sorted()
        val missing = commandPaths.filterValues { value -> value.isNullOrBlank() }.keys.sorted()
        val diagnostics = buildList {
            rootStatus.diagnostic?.let(::add)
            if (!result.succeeded) {
                add("运行时健康检查命令未完全成功：exit=${result.exitCode}, failure=${result.failure}")
            }
            if (result.stderr.isNotBlank()) add(result.stderr.take(MAX_DIAGNOSTIC_CHARS))
            if (values["CMD_chroot"].isNullOrBlank()) add("未找到 chroot；真实 Debian rootfs 尚不能启动")
            if (values["CMD_mount"].isNullOrBlank()) add("未找到 mount；无法准备 rootfs bind mount")
            if (layout.readRootfsState() != RuntimeRootfsState.INSTALLED) {
                add("Debian rootfs 尚未安装；当前仅启用 Android Host Root Shell")
            }
        }

        return RuntimeHealthReport(
            capabilityMode = RuntimeCapabilityMode.FULL_ROOT,
            rootfsState = layout.readRootfsState(),
            runtimeRoot = layout.runtimeRoot.path,
            workspaceRoot = layout.workspacesRoot.path,
            rootIdentity = values["IDENTITY"] ?: rootStatus.identity?.uid?.let { "uid=$it" },
            architecture = values["ARCH"],
            selinuxContext = values["SELINUX"] ?: rootStatus.identity?.selinuxContext,
            shellPath = values["CMD_sh"],
            chrootPath = values["CMD_chroot"],
            mountPath = values["CMD_mount"],
            tarPath = values["CMD_tar"],
            availableCommands = available,
            missingCommands = missing,
            diagnostics = diagnostics,
        )
    }

    private fun unavailableReport(status: RootStatus): RuntimeHealthReport = RuntimeHealthReport(
        capabilityMode = RuntimeCapabilityMode.UNAVAILABLE,
        rootfsState = layout.readRootfsState(),
        runtimeRoot = layout.runtimeRoot.path,
        workspaceRoot = layout.workspacesRoot.path,
        rootIdentity = status.identity?.uid?.let { "uid=$it" },
        architecture = null,
        selinuxContext = status.identity?.selinuxContext,
        shellPath = null,
        chrootPath = null,
        mountPath = null,
        tarPath = null,
        availableCommands = emptyList(),
        missingCommands = HEALTH_COMMAND_NAMES,
        diagnostics = listOfNotNull(status.diagnostic ?: "Root 未授权"),
    )

    private companion object {
        val HEALTH_COMMAND_NAMES = listOf(
            "sh",
            "toybox",
            "chroot",
            "mount",
            "umount",
            "tar",
            "gzip",
            "xz",
            "zstd",
            "unzip",
            "readelf",
        )
        val HEALTH_COMMAND = buildString {
            append("echo IDENTITY=\"$(id)\"\n")
            append("echo ARCH=\"$(uname -m 2>/dev/null)\"\n")
            append("echo SELINUX=\"$(id -Z 2>/dev/null)\"\n")
            HEALTH_COMMAND_NAMES.forEach { command ->
                append("echo CMD_").append(command).append("=\"$(command -v ")
                    .append(command).append(" 2>/dev/null)\"\n")
            }
        }
        const val MAX_DIAGNOSTIC_CHARS = 2_000
    }
}

class PlannedShizukuHostBridge : HostBridge {
    override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.SHIZUKU

    override suspend fun inspectHealth(): RuntimeHealthReport {
        error("Shizuku HostBridge 尚未启用；当前开发优先完成 KernelSU Root 模式")
    }
}
