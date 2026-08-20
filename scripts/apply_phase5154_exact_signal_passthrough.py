#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(
            f"{path}: expected {expected} occurrence(s), found {actual}: {old[:180]!r}"
        )
    p.write_text(text.replace(old, new), encoding="utf-8")


gradle = "app/build.gradle.kts"
replace_exact(gradle, "versionCode = 54", "versionCode = 55")
replace_exact(
    gradle,
    'versionName = "0.5.15.3-phase5.15-live-auto-observation"',
    'versionName = "0.5.15.4-phase5.15-exact-signal-passthrough"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.15.3"', '"catalogVersion": "0.5.15.4"')
replace_exact(
    catalog,
    '    "signalsAllowed": false,',
    '    "signalsAllowed": false,\n    "stoppedSignalPassthroughAllowed": true,',
)
replace_exact(
    catalog,
    '''        {"id": "live_auto_observation", "changesTargetState": true, "strategy": "one-shot-anchor-capture-auto-resume-manual-pause", "timelineEntries": 64},''',
    '''        {"id": "live_auto_observation", "changesTargetState": true, "strategy": "one-shot-anchor-capture-auto-resume-manual-pause", "timelineEntries": 64, "stoppedSignalPolicy": "pass-through-exact-non-SIGTRAP-signal-before-heavy-context-capture"},''',
)
replace_exact(
    catalog,
    '        "a bounded 64-entry chronological control timeline preserves attach/continue/anchor/step/recovery/pause transitions with thread and code-location summaries",',
    '        "a bounded 64-entry chronological control timeline preserves attach/continue/anchor/step/recovery/pause transitions with thread and code-location summaries",\n'
    '        "while live auto-observation is running, a non-SIGTRAP signal stop is resumed only by passing through the exact signal number from the immediately preceding trusted stop reply; there is no user-supplied arbitrary signal API",\n'
    '        "live signal pass-through is decided before qRegisterInfo/maps/code-window capture so runtime-managed signals do not incur repeated heavy observation pauses",',
)

remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"
replace_exact(
    remote,
    '''    fun continueUntilStop(): String {
        val response = requestWithTimeout("vCont;c", timeoutMillis = 0)
        GdbRemoteRunReplyValidator.requireStopOrExit("continue", response)
        rememberStopReply(response)
        return response
    }

    /** Single-step only the thread identified by the most recent LLDB stop reply. */''',
    '''    fun continueUntilStop(): String {
        val response = requestWithTimeout("vCont;c", timeoutMillis = 0)
        GdbRemoteRunReplyValidator.requireStopOrExit("continue", response)
        rememberStopReply(response)
        return response
    }

    /**
     * Resume while delivering only the exact non-SIGTRAP signal from the immediately preceding
     * trusted stop reply. No caller can supply a signal number or arbitrary packet payload.
     */
    fun continuePassingLastStoppedSignal(): String {
        val stopReply = requireNotNull(lastStopReply) {
            "Cannot pass through a target signal before LLDB has reported a stopped signal"
        }
        val payload = GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stopReply)
        val response = requestWithTimeout(payload, timeoutMillis = 0)
        GdbRemoteRunReplyValidator.requireStopOrExit("stopped-signal passthrough", response)
        rememberStopReply(response)
        return response
    }

    /** Single-step only the thread identified by the most recent LLDB stop reply. */''',
)
replace_exact(
    remote,
    '''/** Validate only protocol-defined continue-class stop/exit replies. */
object GdbRemoteRunReplyValidator {''',
    '''/** Typed exact stopped-signal pass-through; there is no arbitrary signal-number input. */
object GdbRemoteStoppedSignalPassthrough {
    const val SIGTRAP = 5

    fun signalNumber(stopReply: String): Int? {
        if (stopReply.length < 3) return null
        if (stopReply[0] != 'T' && stopReply[0] != 'S') return null
        return stopReply.substring(1, 3).toIntOrNull(16)
    }

    fun packetFromStopReply(stopReply: String): String {
        val signal = requireNotNull(signalNumber(stopReply)) {
            "Stop reply does not contain a resumable target signal"
        }
        require(signal in 1..0xff) { "Stopped signal must fit one byte" }
        require(signal != SIGTRAP) { "SIGTRAP is a debugger event and is never auto-passed" }
        return "vCont;C${signal.toString(16).padStart(2, '0')}"
    }
}

/** Validate only protocol-defined continue-class stop/exit replies. */
object GdbRemoteRunReplyValidator {''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    '''    val autoAnchorAutoResumed: Boolean,
    val timeline: List<HostDebuggerTimelineEntry>,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
    '''    val autoAnchorAutoResumed: Boolean,
    val autoSignalPassthroughCount: Int,
    val lastAutoPassedSignal: Int?,
    val timeline: List<HostDebuggerTimelineEntry>,
    val registers: List<HostDebuggerRegisterSnapshot>,''',
)
replace_exact(
    bridge,
    '''        autoAnchorAutoResumed = mutable.autoAnchorAutoResumed,
        timeline = mutable.timeline,
        registers = mutable.registers,''',
    '''        autoAnchorAutoResumed = mutable.autoAnchorAutoResumed,
        autoSignalPassthroughCount = mutable.autoSignalPassthroughCount,
        lastAutoPassedSignal = mutable.lastAutoPassedSignal,
        timeline = mutable.timeline,
        registers = mutable.registers,''',
)
replace_exact(
    bridge,
    '''        var autoAnchorAutoResumed: Boolean = false,
        var pausePending: Boolean = false,
        var timelineSequence: Long = 0L,''',
    '''        var autoAnchorAutoResumed: Boolean = false,
        var autoSignalPassthroughCount: Int = 0,
        var lastAutoPassedSignal: Int? = null,
        var pausePending: Boolean = false,
        var timelineSequence: Long = 0L,''',
)

old_live_block = '''                        runCatching { activeClient.continueUntilStop() }
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
                            }'''
new_live_block = '''                        runCatching {
                            continueLiveAutoObservation(activeClient)
                        }.onFailure { exception ->
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
                        }'''
replace_exact(bridge, old_live_block, new_live_block)

replace_exact(
    bridge,
    '''    private fun recordTimeline(
        kind: String,''',
    '''    private suspend fun continueLiveAutoObservation(
        activeClient: HostDebuggerRemoteClient,
    ) {
        var stopReply = activeClient.continueUntilStop()
        while (true) {
            val stoppedThreadId = GdbRemoteStopReplyParser.threadId(stopReply)
            val manualPause = synchronized(lock) {
                val pending = mutable.pausePending
                if (pending) mutable.pausePending = false
                pending
            }

            if (manualPause) {
                if (stoppedThreadId != null) {
                    runCatching { activeClient.selectGeneralThread(stoppedThreadId) }
                }
                val contextResult = if (stoppedThreadId != null) {
                    runCatching { captureCodeContext(activeClient, stoppedThreadId, stopReply) }
                } else {
                    null
                }
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                    mutable.codeContext = contextResult?.getOrNull()
                    mutable.codeContextFailure = contextResult?.exceptionOrNull()?.message
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("auto_live_pause_stop")
                recordTimeline(
                    kind = "manual_pause_stop",
                    summary = "manual pause/interrupt produced a trusted stop",
                    threadId = stoppedThreadId,
                    codeContext = contextResult?.getOrNull(),
                )
                return
            }

            if (stopReply.startsWith('W') || stopReply.startsWith('X')) {
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("auto_live_target_exit")
                recordTimeline(
                    kind = "auto_live_exit",
                    summary = "target exited while live observation was running: $stopReply",
                )
                return
            }

            val stoppedSignal = GdbRemoteStoppedSignalPassthrough.signalNumber(stopReply)
            if (stoppedSignal != null && stoppedSignal != GdbRemoteStoppedSignalPassthrough.SIGTRAP) {
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                    mutable.autoSignalPassthroughCount += 1
                    mutable.lastAutoPassedSignal = stoppedSignal
                    mutable.targetRunning = true
                    mutable.failure = null
                }
                appendAudit("auto_live_exact_signal_passthrough")
                recordTimeline(
                    kind = "auto_signal_passthrough",
                    summary = "non-SIGTRAP stop 0x${stoppedSignal.toString(16).padStart(2, '0')} passed back exactly from the trusted stop reply before heavy context capture",
                    threadId = stoppedThreadId,
                )
                stopReply = activeClient.continuePassingLastStoppedSignal()
                continue
            }

            if (stoppedThreadId != null) {
                runCatching { activeClient.selectGeneralThread(stoppedThreadId) }
            }
            val contextResult = if (stoppedThreadId != null) {
                runCatching { captureCodeContext(activeClient, stoppedThreadId, stopReply) }
            } else {
                null
            }
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                mutable.codeContext = contextResult?.getOrNull()
                mutable.codeContextFailure = contextResult?.exceptionOrNull()?.message
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("auto_live_debug_stop")
            recordTimeline(
                kind = "auto_live_debug_stop",
                summary = "live observation reached SIGTRAP or another non-pass-through debugger stop",
                threadId = stoppedThreadId,
                codeContext = contextResult?.getOrNull(),
            )
            return
        }
    }

    private fun recordTimeline(
        kind: String,''',
)

replace_exact(
    bridge,
    '''                .put("autoAnchorAutoResumed", mutable.autoAnchorAutoResumed)
                .put("timelineCount", mutable.timeline.size)''',
    '''                .put("autoAnchorAutoResumed", mutable.autoAnchorAutoResumed)
                .put("autoSignalPassthroughCount", mutable.autoSignalPassthroughCount)
                .put("lastAutoPassedSignal", mutable.lastAutoPassedSignal ?: JSONObject.NULL)
                .put("timelineCount", mutable.timeline.size)''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '''                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved} autoResumed=${controlSnapshot.autoAnchorAutoResumed}",''',
    '''                    "autoPrepared=${controlSnapshot.autoAnchorPrepared} autoThread=${controlSnapshot.autoAnchorThreadId ?: "无"} autoPC=${controlSnapshot.autoAnchorAddress?.let { "0x${it.toString(16)}" } ?: "无"} stopObserved=${controlSnapshot.autoAnchorStopObserved} autoResumed=${controlSnapshot.autoAnchorAutoResumed} signalPass=${controlSnapshot.autoSignalPassthroughCount}",''',
)
replace_exact(
    ui,
    '''    appendLine("autoAnchorAutoResumed=${control.autoAnchorAutoResumed}")''',
    '''    appendLine("autoAnchorAutoResumed=${control.autoAnchorAutoResumed}")
    appendLine("autoSignalPassthroughCount=${control.autoSignalPassthroughCount}")
    appendLine("lastAutoPassedSignal=${control.lastAutoPassedSignal?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "无"}")''',
)
replace_exact(
    ui,
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；无 software breakpoint / register write / memory write / raw packet adapter")''',
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；自动运行期间仅允许把刚刚 trusted stop 中的 exact non-SIGTRAP signal 原样交还目标；无 arbitrary signal / software breakpoint / register write / memory write / raw packet adapter")''',
)

# Pure protocol regression: exact stop signal -> fixed vCont;Cxx, while SIGTRAP is never auto-passed.
test_path = Path("app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerStoppedSignalPassthroughTest.kt")
if test_path.exists():
    raise SystemExit(f"{test_path}: already exists")
test_path.write_text(r'''package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDebuggerStoppedSignalPassthroughTest {
    @Test
    fun parsesAndBuildsExactSigsegvPassThrough() {
        val stop = "T0bthread:5b6a;reason:signal;"
        assertEquals(11, GdbRemoteStoppedSignalPassthrough.signalNumber(stop))
        assertEquals("vCont;C0b", GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop))
    }

    @Test
    fun neverAutoPassesSigtrap() {
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply("T05thread:64b;reason:signal;")
        }
    }

    @Test
    fun exitReplyHasNoStoppedSignal() {
        assertNull(GdbRemoteStoppedSignalPassthrough.signalNumber("W00"))
    }
}
''', encoding="utf-8")

print("Applied AutoCrackApp phase 5.15.4 exact stopped-signal passthrough patch")
