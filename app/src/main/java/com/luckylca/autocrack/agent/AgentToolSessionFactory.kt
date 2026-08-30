package com.luckylca.autocrack.agent

import android.content.Context
import android.os.Process
import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractionReport
import com.luckylca.autocrack.apk.PackageOutputParser
import com.luckylca.autocrack.root.RootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import com.luckylca.autocrack.root.RootStatus
import com.luckylca.autocrack.runtime.ChrootRuntimeEngine
import com.luckylca.autocrack.runtime.DynamicHostReadBridge
import com.luckylca.autocrack.runtime.HostDebuggerAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlAuthorization
import com.luckylca.autocrack.runtime.HostDebuggerControlBridge
import com.luckylca.autocrack.runtime.HostDebuggerSessionManager
import com.luckylca.autocrack.runtime.HostFridaAuthorization
import com.luckylca.autocrack.runtime.HostFridaSessionManager
import com.luckylca.autocrack.runtime.HostProcessSummary
import com.luckylca.autocrack.runtime.RootShellRuntimeEngine
import com.luckylca.autocrack.runtime.RuntimeLayout
import com.luckylca.autocrack.runtime.ToolpackPackageInstaller
import com.luckylca.autocrack.runtime.WorkspaceFileService
import java.io.File

/** Builds one bounded Agent tool set permanently scoped to the user's selected APK workspace. */
class AgentToolSessionFactory(
    context: Context,
    private val runner: RootCommandRunner,
    private val rootDetector: RootDetector,
) {
    private val appContext = context.applicationContext

    suspend fun create(
        extraction: ExtractionReport,
        allowDynamicTools: Boolean,
        knownRootStatus: RootStatus? = null,
        onStage: (String) -> Unit = {},
    ): AgentToolSession {
        PackageOutputParser.requireValidPackageName(extraction.packageName)
        onStage("factory_package_validated")
        val layout = RuntimeLayout(appContext).initialize()
        onStage("factory_layout_ready")
        val root = knownRootStatus ?: rootDetector.inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Agent tools require Root" }
        val suPath = requireNotNull(root.suPath) { "Root granted without a usable su path" }
        onStage("factory_root_ready")
        val host = RootShellRuntimeEngine(
            layout = layout,
            suPath = suPath,
            onStage = { stage -> onStage("root_$stage") },
        )
        val chroot = ChrootRuntimeEngine(layout, host) { stage -> onStage("chroot_$stage") }
        val installer = ToolpackPackageInstaller(appContext, layout)
        onStage("factory_installer_ready")
        val installed = installer.listInstalled().associateBy { it.manifest.id }
        onStage("factory_installed_listed:${installed.keys.sorted().joinToString(",")}")
        val executors = mutableListOf<AgentToolExecutor>()

        val base = extraction.artifacts.singleOrNull { it.kind == ApkArtifactKind.BASE }
            ?: error("Selected workspace does not contain exactly one base APK")
        val baseFile = File(base.localPath).canonicalFile
        require(layout.isManagedPath(baseFile)) { "Selected base APK is outside AutoCrack managed storage" }
        onStage("factory_base_ready")

        executors += AgentDeviceDiagnosticsToolExecutor(
            rootStatus = root,
            host = host,
            layout = layout,
            appUid = Process.myUid(),
        )
        onStage("factory_device_diagnostics_registered")

        executors += AgentAndroidNetworkToolExecutor(
            packageName = extraction.packageName,
            host = host,
            layout = layout,
            appUid = Process.myUid(),
            allowCapture = allowDynamicTools,
        )
        onStage("factory_android_network_registered")

        installed[AgentApkDexToolExecutor.TOOLPACK_ID]
            ?.takeIf { it.manifest.version == AgentApkDexToolExecutor.TOOLPACK_VERSION }
            ?.let {
                executors += AgentApkDexToolExecutor(
                    packageName = extraction.packageName,
                    baseApk = baseFile,
                    layout = layout,
                    chroot = chroot,
                    appUid = Process.myUid(),
                    onStage = { stage -> onStage("apkdex_$stage") },
                )
                onStage("factory_apk_dex_registered")
            }

        installed[AgentNativeToolExecutor.TOOLPACK_ID]
            ?.takeIf { it.manifest.version == AgentNativeToolExecutor.TOOLPACK_VERSION }
            ?.let {
                executors += AgentNativeToolExecutor(
                    extraction = extraction,
                    layout = layout,
                    chroot = chroot,
                )
                onStage("factory_native_registered")
            }

        installed[AgentPcapToolExecutor.TOOLPACK_ID]
            ?.takeIf { it.manifest.version == AgentPcapToolExecutor.TOOLPACK_VERSION }
            ?.let {
                executors += AgentPcapToolExecutor(
                    packageName = extraction.packageName,
                    layout = layout,
                    chroot = chroot,
                )
                onStage("factory_pcap_registered")
            }

        installed[AgentPerfettoToolExecutor.TOOLPACK_ID]
            ?.takeIf { it.manifest.version == AgentPerfettoToolExecutor.TOOLPACK_VERSION }
            ?.let {
                executors += AgentPerfettoToolExecutor(
                    packageName = extraction.packageName,
                    layout = layout,
                    host = host,
                    chroot = chroot,
                    appUid = Process.myUid(),
                    allowTargetLaunch = allowDynamicTools,
                )
                onStage("factory_perfetto_registered")
            }

        if (allowDynamicTools) {
            onStage("factory_dynamic_requested")
            val readBridge = DynamicHostReadBridge(layout, rootDetector, runner)
            val processReport = readBridge.listProcesses(filter = extraction.packageName, maxCount = MAX_PROCESS_CANDIDATES)
            val pid = if (processReport.commandResult.succeeded) {
                selectExactTargetPid(extraction.packageName, processReport.processes)
            } else {
                null
            }
            if (pid != null) {
                installed[FRIDA_TOOLPACK_ID]
                    ?.takeIf { it.manifest.version == FRIDA_TOOLPACK_VERSION }
                    ?.let {
                        val manager = HostFridaSessionManager(appContext, layout, rootDetector, runner)
                        executors += AgentFridaToolExecutor.authorized(
                            packageName = extraction.packageName,
                            pid = pid,
                            authorizationPhrase = HostFridaAuthorization.expected(extraction.packageName, pid),
                            manager = manager,
                        )
                    }

                installed[LLDB_TOOLPACK_ID]
                    ?.takeIf { it.manifest.version == LLDB_TOOLPACK_VERSION }
                    ?.let {
                        val manager = HostDebuggerSessionManager(appContext, layout, rootDetector, runner)
                        val control = HostDebuggerControlBridge(manager, readBridge)
                        executors += AgentDebuggerToolExecutor.authorized(
                            packageName = extraction.packageName,
                            pid = pid,
                            attachAuthorization = HostDebuggerAuthorization.expected(extraction.packageName, pid),
                            controlAuthorization = HostDebuggerControlAuthorization.expected(extraction.packageName, pid),
                            readBridge = readBridge,
                            manager = manager,
                            control = control,
                        )
                    }
            }
        }

        onStage("factory_return:${executors.sumOf { it.tools.size }}")
        return AgentToolSession(executors)
    }

    suspend fun createMobileAgent(
        sessionId: String,
        knownRootStatus: RootStatus? = null,
        dangerousOperationGate: (suspend (DangerousOperationRequest) -> DangerousOperationDecision)? = null,
        onStage: (String) -> Unit = {},
    ): MobileAgentRuntimeSession {
        val layout = RuntimeLayout(appContext).initialize()
        require(layout.readRootfsState() == com.luckylca.autocrack.runtime.RuntimeRootfsState.INSTALLED) { "Debian rootfs 尚未安装完成" }
        val root = knownRootStatus ?: rootDetector.inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Mobile Agent 需要 Root" }
        val suPath = requireNotNull(root.suPath) { "Root granted without a usable su path" }
        val host = RootShellRuntimeEngine(layout = layout, suPath = suPath, onStage = { onStage("mobile_root_$it") })
        val sessionWorkspace = layout.createAgentWorkspace(sessionId)
        val workspaceRoot = MobileAgentWorkspacePolicy.resolve(
            sessionWorkspace = sessionWorkspace,
            legacyWorkspace = layout.createRuntimeWorkspace(),
        )
        val chroot = ChrootRuntimeEngine(
            layout = layout,
            hostEngine = host,
            workspaceRoot = workspaceRoot,
            onStage = { onStage("mobile_chroot_$it") },
        )
        val installed = ToolpackPackageInstaller(appContext, layout).listInstalled()
        val commands = installed.flatMap { it.manifest.commands }.map { it.name }.distinct().sorted()
        val workspace = WorkspaceFileService(workspaceRoot)
        val hostShellBridge = installed.firstOrNull { toolpack ->
            toolpack.manifest.id == AndroidHostShellBridge.TOOLPACK_ID &&
                toolpack.manifest.version == AndroidHostShellBridge.TOOLPACK_VERSION
        }?.let { toolpack ->
            com.luckylca.autocrack.runtime.BuiltInToolpackTrustPolicy.requireTrusted(toolpack.manifest)
            AndroidHostShellBridge(
                host = host,
                sessionId = sessionId,
                hostWorkspacePath = workspaceRoot.path,
                dangerousOperationGate = dangerousOperationGate,
            ).start()
        }
        val executor = RawBashAgentToolExecutor(
            chroot = chroot,
            host = host,
            workspaceFiles = workspace,
            availableToolCommands = commands,
            sessionId = sessionId,
            dangerousOperationGate = dangerousOperationGate,
            extraEnvironment = hostShellBridge?.environment().orEmpty(),
        )
        onStage("mobile_ready:${commands.joinToString(",")}")
        return MobileAgentRuntimeSession(
            tools = AgentToolSession(listOf(executor)),
            installedToolpacks = installed,
            workspacePath = RawBashAgentToolExecutor.DEFAULT_CHROOT_CWD,
            cancelAllCommands = {
                hostShellBridge?.close()
                host.cancelAll()
            },
            cleanupSessionProcesses = {
                val result = chroot.cleanupAgentSessionProcesses(sessionId)
                check(result.succeeded) {
                    result.failure ?: result.stderr.ifBlank { "Agent session 子进程清理失败：exit=${result.exitCode}" }
                }
            },
        )
    }

    suspend fun createRawBash(
        extraction: ExtractionReport,
        knownRootStatus: RootStatus? = null,
        dynamicToolsAllowed: Boolean = false,
        onStage: (String) -> Unit = {},
    ): AgentToolSession {
        PackageOutputParser.requireValidPackageName(extraction.packageName)
        onStage("raw_factory_package_validated")
        val layout = RuntimeLayout(appContext).initialize()
        onStage("raw_factory_layout_ready")
        val root = knownRootStatus ?: rootDetector.inspect()
        require(root.isRootGranted) { root.diagnostic ?: "Raw Bash Agent tools require Root" }
        val suPath = requireNotNull(root.suPath) { "Root granted without a usable su path" }
        onStage("raw_factory_root_ready")
        val host = RootShellRuntimeEngine(
            layout = layout,
            suPath = suPath,
            onStage = { stage -> onStage("raw_root_$stage") },
        )
        val chroot = ChrootRuntimeEngine(layout, host) { stage -> onStage("raw_chroot_$stage") }
        val workspace = WorkspaceFileService(layout.createRuntimeWorkspace())
        onStage("raw_factory_workspace_ready")
        val executor = RawBashAgentToolExecutor(
            packageName = extraction.packageName,
            chroot = chroot,
            host = host,
            workspaceFiles = workspace,
            dynamicToolsAllowed = dynamicToolsAllowed,
        )
        onStage("raw_factory_return:${executor.tools.size}")
        return AgentToolSession(listOf(executor))
    }

    companion object {
        private const val MAX_PROCESS_CANDIDATES = 32
        private const val FRIDA_TOOLPACK_ID = "android-frida"
        private const val FRIDA_TOOLPACK_VERSION = "frida-17.17.0-autocrack-1.1.0"
        private const val LLDB_TOOLPACK_ID = "android-lldb-server"
        private const val LLDB_TOOLPACK_VERSION = "android-llvm-r522817_lldb-14_autocrack-2.0.0"

        internal fun selectExactTargetPid(
            packageName: String,
            processes: List<HostProcessSummary>,
        ): Int? {
            PackageOutputParser.requireValidPackageName(packageName)
            val exact = processes.filter { process ->
                process.commandLine.substringBefore(' ').trim() == packageName
            }.map(HostProcessSummary::pid).distinct()
            return exact.singleOrNull()
        }
    }
}
