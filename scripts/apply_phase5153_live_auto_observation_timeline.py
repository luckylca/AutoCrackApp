#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(
            f"{path}: expected {expected} occurrence(s), found {actual}: {old[:160]!r}"
        )
    p.write_text(text.replace(old, new), encoding="utf-8")


gradle = "app/build.gradle.kts"
replace_exact(gradle, "versionCode = 53", "versionCode = 54")
replace_exact(
    gradle,
    'versionName = "0.5.15.2-phase5.15-automatic-code-context"',
    'versionName = "0.5.15.3-phase5.15-live-auto-observation"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.15.2"', '"catalogVersion": "0.5.15.3"')
replace_exact(
    catalog,
    '''        {"id": "automatic_code_context", "changesTargetState": false, "maxInstructionBytes": 36, "sources": ["qRegisterInfo/p", "bounded m", "/proc/<pid>/maps"]},
        {"id": "continue", "changesTargetState": true},''',
    '''        {"id": "automatic_code_context", "changesTargetState": false, "maxInstructionBytes": 36, "sources": ["qRegisterInfo/p", "bounded m", "/proc/<pid>/maps"]},
        {"id": "live_auto_observation", "changesTargetState": true, "strategy": "one-shot-anchor-capture-auto-resume-manual-pause", "timelineEntries": 64},
        {"id": "continue", "changesTargetState": true},''',
)
replace_exact(
    catalog,
    '        "after a trusted stop, code context is captured read-only from PC/LR/SP, at most 36 nearby instruction bytes, and /proc/<pid>/maps; no target mutation is used for context",',
    '        "after a trusted stop, code context is captured read-only from PC/LR/SP, at most 36 nearby instruction bytes, and /proc/<pid>/maps; no target mutation is used for context",\n'
    '        "the automatic one-shot anchor captures its stop context and automatically resumes the target unless the user explicitly requested pause, preventing the target app from remaining frozen after the anchor hit",\n'
    '        "a bounded 64-entry chronological control timeline preserves attach/continue/anchor/step/recovery/pause transitions with thread and code-location summaries",',
)

helper = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerCodeContext.kt"
replace_exact(
    helper,
    '''object HostDebuggerStopReplyDetails {
    fun threadName(stopReply: String): String? {
        if (!stopReply.startsWith('T')) return null
        return stopReply.split(';')
            .firstOrNull { it.startsWith("name:") }
            ?.substringAfter("name:")
            ?.takeIf(String::isNotBlank)
    }
}''',
    '''object HostDebuggerStopReplyDetails {
    fun threadName(stopReply: String): String? {
        if (!stopReply.startsWith('T')) return null
        val fields = stopReply.split(';')
        fields.firstOrNull { it.startsWith("name:") }
            ?.substringAfter("name:")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val encoded = fields.firstOrNull { it.startsWith("hexname:") }
            ?.substringAfter("hexname:")
            ?.takeIf(String::isNotBlank)
            ?: return null
        return runCatching {
            GdbRemotePacketCodec.decodeHex(encoded).toString(Charsets.UTF_8)
                .trimEnd('\\u0000')
                .takeIf(String::isNotBlank)
        }.getOrNull()
    }
}''',
)

helper_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerCodeContextTest.kt"
replace_exact(
    helper_test,
    '''    @Test
    fun decodesImportantAarch64ControlFlowWithoutExternalToolpack() {''',
    '''    @Test
    fun decodesHexEncodedStopReplyThreadName() {
        assertEquals(
            "AsyncTask #1",
            HostDebuggerStopReplyDetails.threadName(
                "T05thread:2401;hexname:4173796e635461736b202331;reason:signal;",
            ),
        )
    }

    @Test
    fun decodesImportantAarch64ControlFlowWithoutExternalToolpack() {''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    '''data class HostDebuggerControlSnapshot(''',
    '''data class HostDebuggerTimelineEntry(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val kind: String,
    val threadId: String?,
    val pc: Long?,
    val modulePath: String?,
    val moduleOffset: Long?,
    val instructionRawHex: String?,
    val instructionText: String?,
    val summary: String,
)

data class HostDebuggerControlSnapshot(''',
)
replace_exact(
    bridge,
    '''    val codeContext: HostDebuggerCodeContextSnapshot?,
    val codeContextFailure: String?,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val codeContext: HostDebuggerCodeContextSnapshot?,
    val codeContextFailure: String?,
    val autoAnchorAutoResumed: Boolean,
    val timeline: List<HostDebuggerTimelineEntry>,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)
replace_exact(
    bridge,
    '''        codeContext = mutable.codeContext,
        codeContextFailure = mutable.codeContextFailure,
        registers = mutable.registers,''',
    '''        codeContext = mutable.codeContext,
        codeContextFailure = mutable.codeContextFailure,
        autoAnchorAutoResumed = mutable.autoAnchorAutoResumed,
        timeline = mutable.timeline,
        registers = mutable.registers,''',
)
replace_exact(
    bridge,
    '''        var codeContext: HostDebuggerCodeContextSnapshot? = null,
        var codeContextFailure: String? = null,
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
    '''        var codeContext: HostDebuggerCodeContextSnapshot? = null,
        var codeContextFailure: String? = null,
        var autoAnchorAutoResumed: Boolean = false,
        var pausePending: Boolean = false,
        var timelineSequence: Long = 0L,
        var timeline: List<HostDebuggerTimelineEntry> = emptyList(),
        var registers: List<HostDebuggerRegisterSnapshot> = emptyList(),''',
)

replace_exact(
    bridge,
    '''    private suspend fun captureCodeContext(
        activeClient: HostDebuggerRemoteClient,''',
    '''    private fun recordTimeline(
        kind: String,
        summary: String,
        threadId: String? = null,
        codeContext: HostDebuggerCodeContextSnapshot? = null,
    ) {
        synchronized(lock) {
            mutable.timelineSequence += 1L
            val currentInstruction = codeContext?.instructions?.firstOrNull { it.current }
            val entry = HostDebuggerTimelineEntry(
                sequence = mutable.timelineSequence,
                timestampEpochMillis = System.currentTimeMillis(),
                kind = kind,
                threadId = threadId,
                pc = codeContext?.pc,
                modulePath = codeContext?.modulePath,
                moduleOffset = codeContext?.moduleOffset,
                instructionRawHex = currentInstruction?.rawHex,
                instructionText = currentInstruction?.text,
                summary = summary.replace('\\n', ' ').take(TIMELINE_SUMMARY_MAX_CHARS),
            )
            mutable.timeline = (mutable.timeline + entry).takeLast(MAX_TIMELINE_ENTRIES)
        }
    }

    private suspend fun captureCodeContext(
        activeClient: HostDebuggerRemoteClient,''',
)

replace_exact(
    bridge,
    '''            appendAudit("auto_steppable_anchor_prepared")
            snapshot()''',
    '''            appendAudit("auto_steppable_anchor_prepared")
            recordTimeline(
                kind = "auto_anchor_prepared",
                summary = "main-thread/current-PC one-shot anchor prepared at 0x${address.toString(16)}",
                threadId = selectedThreadId,
            )
            snapshot()''',
)

replace_exact(
    bridge,
    '''        appendAudit("continue_start")
        val activeClient = requireNotNull(client)''',
    '''        appendAudit("continue_start")
        recordTimeline(
            kind = "continue_start",
            summary = "continue sent from known stopped state",
            threadId = synchronized(lock) { mutable.selectedThreadId },
        )
        val activeClient = requireNotNull(client)''',
)

replace_exact(
    bridge,
    '''                    appendAudit(
                        if (autoAnchor.third) "continue_stop_auto_anchor_observed" else "continue_stop",
                    )''',
    '''                    appendAudit(
                        if (autoAnchor.third) "continue_stop_auto_anchor_observed" else "continue_stop",
                    )
                    recordTimeline(
                        kind = if (autoAnchor.third) "auto_anchor_stop" else "continue_stop",
                        summary = if (autoAnchor.third) {
                            "one-shot anchor stop captured; breakpoint cleanup=${if (autoRemoved) "ok" else "not-owned-or-failed"}"
                        } else {
                            "continue produced a trusted stop"
                        },
                        threadId = contextThreadId,
                        codeContext = contextResult.getOrNull(),
                    )

                    val pauseRequestedBeforeAutoResume = synchronized(lock) {
                        if (mutable.pausePending) {
                            mutable.pausePending = false
                            true
                        } else {
                            false
                        }
                    }
                    if (autoAnchor.third && !pauseRequestedBeforeAutoResume) {
                        synchronized(lock) {
                            mutable.targetRunning = true
                            mutable.autoAnchorAutoResumed = true
                            mutable.failure = null
                        }
                        appendAudit("auto_anchor_context_captured_auto_resume_start")
                        recordTimeline(
                            kind = "auto_anchor_auto_resume",
                            summary = "anchor context captured; target automatically resumed so the target app remains interactive",
                            threadId = contextThreadId,
                            codeContext = contextResult.getOrNull(),
                        )

                        runCatching { activeClient.continueUntilStop() }
                            .onSuccess { resumedStopReply ->
                                val resumedStoppedThreadId = GdbRemoteStopReplyParser.threadId(resumedStopReply)
                                if (resumedStoppedThreadId != null) {
                                    runCatching { activeClient.selectGeneralThread(resumedStoppedThreadId) }
                                }
                                val resumedContextResult = if (resumedStoppedThreadId != null) {
                                    runCatching {
                                        captureCodeContext(
                                            activeClient,
                                            resumedStoppedThreadId,
                                            resumedStopReply,
                                        )
                                    }
                                } else {
                                    null
                                }
                                val manualPause = synchronized(lock) {
                                    val pending = mutable.pausePending
                                    mutable.pausePending = false
                                    pending
                                }
                                synchronized(lock) {
                                    mutable.lastStopReply = resumedStopReply
                                    if (resumedStoppedThreadId != null) {
                                        mutable.selectedThreadId = resumedStoppedThreadId
                                    }
                                    mutable.codeContext = resumedContextResult?.getOrNull()
                                    mutable.codeContextFailure = resumedContextResult?.exceptionOrNull()?.message
                                    mutable.targetRunning = false
                                    mutable.failure = null
                                }
                                appendAudit(
                                    if (manualPause) "auto_live_pause_stop" else "auto_live_unexpected_stop",
                                )
                                recordTimeline(
                                    kind = if (manualPause) "manual_pause_stop" else "auto_live_stop",
                                    summary = if (manualPause) {
                                        "manual pause/interrupt produced a trusted stop"
                                    } else {
                                        "live auto-resumed target stopped before an explicit pause"
                                    },
                                    threadId = resumedStoppedThreadId,
                                    codeContext = resumedContextResult?.getOrNull(),
                                )
                            }
                            .onFailure { exception ->
                                synchronized(lock) {
                                    mutable.pausePending = false
                                    mutable.targetRunning = false
                                    mutable.failure = exception.message ?: exception::class.java.simpleName
                                }
                                appendAudit("auto_live_resume_failed")
                                recordTimeline(
                                    kind = "auto_live_resume_failed",
                                    summary = exception.message ?: exception::class.java.simpleName,
                                )
                            }
                    } else if (autoAnchor.third) {
                        synchronized(lock) { mutable.autoAnchorAutoResumed = false }
                        appendAudit("auto_anchor_stop_kept_for_explicit_pause")
                        recordTimeline(
                            kind = "auto_anchor_pause_stop",
                            summary = "explicit pause was already requested; anchor stop kept stopped instead of auto-resuming",
                            threadId = contextThreadId,
                            codeContext = contextResult.getOrNull(),
                        )
                    }''',
)

replace_exact(
    bridge,
    '''        appendAudit("step_start")
        try {''',
    '''        appendAudit("step_start")
        recordTimeline(
            kind = "step_start",
            summary = "single-step requested",
            threadId = stepThreadId,
        )
        try {''',
)
replace_exact(
    bridge,
    '''            appendAudit("step_stop")
            snapshot()''',
    '''            appendAudit("step_stop")
            recordTimeline(
                kind = "step_stop",
                summary = "single-step completed normally",
                threadId = stepThreadId,
                codeContext = contextResult.getOrNull(),
            )
            snapshot()''',
)
replace_exact(
    bridge,
    '''            appendAudit("step_wait_timeout_auto_interrupt_start")
            try {''',
    '''            appendAudit("step_wait_timeout_auto_interrupt_start")
            recordTimeline(
                kind = "step_timeout",
                summary = "single-step did not produce a stop within the bounded wait; automatic protocol interrupt recovery started",
                threadId = stepThreadId,
            )
            try {''',
)
replace_exact(
    bridge,
    '''                val stopReply = activeClient.awaitStopAfterInterrupt()
                runCatching { activeClient.selectGeneralThread(stepThreadId) }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.stepCompleted = false
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
                snapshot()''',
    '''                val stopReply = activeClient.awaitStopAfterInterrupt()
                val recoveredThreadId = GdbRemoteStopReplyParser.threadId(stopReply) ?: stepThreadId
                runCatching { activeClient.selectGeneralThread(recoveredThreadId) }
                val recoveryContextResult = runCatching {
                    captureCodeContext(activeClient, recoveredThreadId, stopReply)
                }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.selectedThreadId = recoveredThreadId
                    mutable.codeContext = recoveryContextResult.getOrNull()
                    mutable.codeContextFailure = recoveryContextResult.exceptionOrNull()?.message
                    mutable.stepCompleted = false
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
                recordTimeline(
                    kind = "step_timeout_recovered_stop",
                    summary = "step did not complete; automatic interrupt recovered a trusted stop on the actual stopped thread",
                    threadId = recoveredThreadId,
                    codeContext = recoveryContextResult.getOrNull(),
                )
                snapshot()''',
)

replace_exact(
    bridge,
    '''            mutable.interruptCommandSent = true
            requireNotNull(client)''',
    '''            mutable.interruptCommandSent = true
            mutable.pausePending = true
            requireNotNull(client)''',
)
replace_exact(
    bridge,
    '''        appendAudit("interrupt_sent")

        val activeContinueJob''',
    '''        appendAudit("interrupt_sent")
        recordTimeline(
            kind = "manual_pause_requested",
            summary = "protocol interrupt requested; waiting for a trusted stop",
            threadId = synchronized(lock) { mutable.selectedThreadId },
        )

        val activeContinueJob''',
)

replace_exact(
    bridge,
    '''                .put("codeContextFailure", mutable.codeContextFailure ?: JSONObject.NULL)
                .put("rawPacketAdapterExposed", false)''',
    '''                .put("codeContextFailure", mutable.codeContextFailure ?: JSONObject.NULL)
                .put("autoAnchorAutoResumed", mutable.autoAnchorAutoResumed)
                .put("timelineCount", mutable.timeline.size)
                .put("timelineLastKind", mutable.timeline.lastOrNull()?.kind ?: JSONObject.NULL)
                .put("rawPacketAdapterExposed", false)''',
)
replace_exact(
    bridge,
    '''        const val CODE_CONTEXT_MAX_BYTES = 36''',
    '''        const val CODE_CONTEXT_MAX_BYTES = 36
        const val MAX_TIMELINE_ENTRIES = 64
        const val TIMELINE_SUMMARY_MAX_CHARS = 240''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '''                    "已自动选择 thread=${result.autoAnchorThreadId ?: "未知"}，PC=${result.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "未知"} 并 Continue。现在直接操作目标 App；从阻塞 syscall 返回或再次执行该 PC 时会自动停下并切换到命中线程。"''',
    '''                    "自动观察已启动：thread=${result.autoAnchorThreadId ?: "未知"}，PC=${result.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "未知"}。一次性 anchor 命中后会自动抓上下文并继续运行，不会把目标 App 长期停住；操作目标 App 后回到这里点“暂停运行并抓取当前上下文”。"''',
)
replace_exact(
    ui,
    '''                Button(
                    onClick = ::autoRunToSteppablePosition,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("自动选择线程 + 地址并运行") }
                Text(
                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved}",''',
    '''                Button(
                    onClick = ::autoRunToSteppablePosition,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && !controlSnapshot.targetRunning,
                ) { Text("自动选择线程 + 地址并运行") }
                OutlinedButton(
                    onClick = ::interruptTarget,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading && controlSnapshot.connected && controlSnapshot.targetRunning,
                ) { Text("暂停运行并抓取当前上下文") }
                Text(
                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved} autoResumed=${controlSnapshot.autoAnchorAutoResumed}",''',
)
replace_exact(
    ui,
    '''                Text("continue/step 会改变目标执行状态；手工硬件断点仍可在上面的高级区域使用。")''',
    '''                Text("自动模式推荐流程：启动自动观察 → 切到目标 App 正常操作 → 回来点“暂停运行并抓取当前上下文” → 再 STEP。时间线会保留中间 stop，而不是只展示最后状态。")
                Text(
                    "timeline=${controlSnapshot.timeline.size} latest=${controlSnapshot.timeline.lastOrNull()?.kind ?: "无"}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )''',
)

replace_exact(
    ui,
    '''    appendLine("codeContextFailure=${control.codeContextFailure ?: "无"}")''',
    '''    appendLine("codeContextFailure=${control.codeContextFailure ?: "无"}")
    appendLine("autoAnchorAutoResumed=${control.autoAnchorAutoResumed}")''',
)
replace_exact(
    ui,
    '''    appendLine("rawPacketAdapterExposed=false")''',
    '''    appendLine("timelineCount=${control.timeline.size}")
    appendLine("timeline:")
    control.timeline.forEach { event ->
        appendLine(
            "  #${event.sequence} t=${event.timestampEpochMillis} kind=${event.kind} thread=${event.threadId ?: "无"} " +
                "pc=${event.pc?.let { "0x${it.toString(16)}" } ?: "无"} " +
                "module=${event.modulePath ?: "无"} offset=${event.moduleOffset?.let { "+0x${it.toString(16)}" } ?: "无"} " +
                "insn=${event.instructionRawHex ?: "无"}:${event.instructionText ?: "无"} summary=${event.summary}",
        )
    }
    appendLine("rawPacketAdapterExposed=false")''',
)

print("Applied AutoCrackApp phase 5.15.3 live auto observation + bounded timeline patch")
