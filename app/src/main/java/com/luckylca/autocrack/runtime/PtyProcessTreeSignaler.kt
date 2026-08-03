package com.luckylca.autocrack.runtime

internal data class PtyProcessSignalPlan(
    val processGroupIds: List<Int>,
)

internal data class PtyProcessTreeSignalResult(
    val succeeded: Boolean,
    val processTree: PtyProcessTreeSnapshot,
    val processGroupIds: List<Int>,
    val shellResult: ShellCommandResult,
)

internal class PtyProcessTreeSignaler(
    private val layout: RuntimeLayout,
    private val hostEngine: RootShellRuntimeEngine,
    private val processSupervisor: PtyProcessSupervisor,
) {
    suspend fun signal(
        rootPid: Int,
        signal: Int,
        fallbackTree: PtyProcessTreeSnapshot,
    ): PtyProcessTreeSignalResult {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        require(signal in MIN_SIGNAL..MAX_SIGNAL) { "进程信号非法" }

        val tree = runCatching { processSupervisor.inspect(rootPid) }
            .getOrElse { exception ->
                fallbackTree.copy(
                    rootPid = rootPid,
                    failure = fallbackTree.failure
                        ?: "发送信号前刷新进程树失败：${exception.message ?: exception::class.java.name}",
                )
            }
        val plan = PtyProcessSignalPlanner.build(tree.processes, rootPid)
        val shellResult = hostEngine.execute(
            PtyProcessSignalScriptBuilder.buildRequest(
                rootPid = rootPid,
                signal = signal,
                processGroupIds = plan.processGroupIds,
                workingDirectory = layout.runtimeRoot.path,
            ),
        )
        return PtyProcessTreeSignalResult(
            succeeded = shellResult.succeeded,
            processTree = tree,
            processGroupIds = plan.processGroupIds,
            shellResult = shellResult,
        )
    }

    private companion object {
        const val MIN_SIGNAL = 1
        const val MAX_SIGNAL = 64
    }
}

internal object PtyProcessSignalPlanner {
    fun build(processes: List<PtyProcessInfo>, rootPid: Int): PtyProcessSignalPlan {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        val processByPid = processes.associateBy(PtyProcessInfo::pid)
        val depths = mutableMapOf<Int, Int>()

        fun depth(pid: Int, visiting: MutableSet<Int> = mutableSetOf()): Int {
            depths[pid]?.let { return it }
            if (!visiting.add(pid)) return 0
            val process = processByPid[pid]
            val value = if (process == null || process.parentPid !in processByPid) {
                0
            } else {
                1 + depth(process.parentPid, visiting)
            }
            visiting.remove(pid)
            depths[pid] = value
            return value
        }

        val processGroupIds = processes
            .sortedWith(
                compareByDescending<PtyProcessInfo> { depth(it.pid) }
                    .thenByDescending(PtyProcessInfo::pid),
            )
            .map(PtyProcessInfo::processGroupId)
            .filter { it > 1 }
            .distinct()
            .ifEmpty { listOf(rootPid) }

        return PtyProcessSignalPlan(processGroupIds = processGroupIds)
    }
}

internal object PtyProcessSignalScriptBuilder {
    fun buildRequest(
        rootPid: Int,
        signal: Int,
        processGroupIds: List<Int>,
        workingDirectory: String,
    ): ShellCommandRequest = ShellCommandRequest(
        command = buildScript(rootPid, signal, processGroupIds),
        workingDirectory = workingDirectory,
        timeoutMillis = SIGNAL_TIMEOUT_MILLIS,
        identity = HostExecutionIdentity.ROOT,
        outputMode = ShellOutputMode.DISCARD,
    )

    fun buildScript(rootPid: Int, signal: Int, processGroupIds: List<Int>): String {
        require(rootPid > 1) { "PTY 根进程 PID 非法" }
        require(signal in MIN_SIGNAL..MAX_SIGNAL) { "进程信号非法" }
        require(processGroupIds.isNotEmpty()) { "进程组列表不能为空" }
        require(processGroupIds.all { it > 1 }) { "进程组 ID 非法" }
        val groupWords = processGroupIds.distinct().joinToString(" ")
        return """
            set -u
            ROOT_PID=$rootPid
            SIGNAL=$signal
            DELIVERED=0
            FAILED=0

            # A PTY subtree can contain multiple job-control process groups. Signal
            # leaf groups first, then the interactive shell and outer script group.
            for PGID in $groupWords; do
              if kill -"${'$'}SIGNAL" -- "-${'$'}PGID" 2>/dev/null; then
                DELIVERED=${'$'}((DELIVERED + 1))
              elif kill -0 -- "-${'$'}PGID" 2>/dev/null; then
                FAILED=${'$'}((FAILED + 1))
              fi
            done

            [ "${'$'}FAILED" -eq 0 ] || exit 1
            if [ "${'$'}DELIVERED" -eq 0 ]; then
              [ ! -r "/proc/${'$'}ROOT_PID/stat" ] && exit 0
              exit 2
            fi
            exit 0
        """.trimIndent()
    }

    private const val SIGNAL_TIMEOUT_MILLIS = 5_000L
    private const val MIN_SIGNAL = 1
    private const val MAX_SIGNAL = 64
}
