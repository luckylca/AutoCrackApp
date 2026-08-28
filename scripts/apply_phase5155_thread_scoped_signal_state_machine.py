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
replace_exact(gradle, "versionCode = 55", "versionCode = 56")
replace_exact(
    gradle,
    'versionName = "0.5.15.4-phase5.15-exact-signal-passthrough"',
    'versionName = "0.5.15.5-phase5.15-thread-scoped-signal-state-machine"',
)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.15.4"', '"catalogVersion": "0.5.15.5"')
replace_exact(
    catalog,
    '    "stoppedSignalPassthroughAllowed": true,',
    '    "stoppedSignalPassthroughAllowed": true,\n'
    '    "stoppedSignalPassthroughScope": "stopped_thread_only",\n'
    '    "explicitSignalReasonTrapPassthroughAllowed": true,',
)
replace_exact(
    catalog,
    '"stoppedSignalPolicy": "pass-through-exact-non-SIGTRAP-signal-before-heavy-context-capture"',
    '"stoppedSignalPolicy": "thread-scoped-exact-target-signal-before-heavy-context-capture"',
)
replace_exact(
    catalog,
    '        "while live auto-observation is running, a non-SIGTRAP signal stop is resumed only by passing through the exact signal number from the immediately preceding trusted stop reply; there is no user-supplied arbitrary signal API",',
    '        "while live auto-observation is running, a target signal stop is resumed only on the exact thread named by the immediately preceding trusted stop reply using vCont;Cxx:<stopped-thread>;c; the signal is never applied as a process-wide default action",\n'
    '        "SIGTRAP is auto-passed only when the trusted stop reply explicitly classifies it as reason:signal; hardware-breakpoint and single-step trap stops remain debugger stops",',
)

remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"
replace_exact(
    remote,
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
''',
    '''/**
 * Typed exact stopped-signal pass-through.
 *
 * The signal number and thread ID are both taken from the same trusted stop reply. Callers cannot
 * choose either value. The signal action is scoped to only that stopped thread, while all other
 * threads receive a plain continue action. This avoids accidentally delivering one thread's signal
 * to every thread in an all-stop Android process.
 */
object GdbRemoteStoppedSignalPassthrough {
    const val SIGTRAP = 5

    fun signalNumber(stopReply: String): Int? {
        if (stopReply.length < 3) return null
        if (stopReply[0] != 'T' && stopReply[0] != 'S') return null
        return stopReply.substring(1, 3).toIntOrNull(16)
    }

    fun hasExplicitSignalReason(stopReply: String): Boolean =
        stopReply.contains(";reason:signal;") || stopReply.endsWith(";reason:signal")

    fun isAutoPassableTargetSignal(stopReply: String): Boolean {
        val signal = signalNumber(stopReply) ?: return false
        if (signal !in 1..0xff) return false
        return signal != SIGTRAP || hasExplicitSignalReason(stopReply)
    }

    fun packetFromStopReply(stopReply: String): String {
        val signal = requireNotNull(signalNumber(stopReply)) {
            "Stop reply does not contain a resumable target signal"
        }
        require(signal in 1..0xff) { "Stopped signal must fit one byte" }
        require(isAutoPassableTargetSignal(stopReply)) {
            "SIGTRAP without explicit reason:signal is reserved for debugger stops"
        }
        val stoppedThreadId = GdbRemoteStopReplyParser.requireThreadId(stopReply)
        return "vCont;C${signal.toString(16).padStart(2, '0')}:$stoppedThreadId;c"
    }
}
''',
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    '''            val stoppedSignal = GdbRemoteStoppedSignalPassthrough.signalNumber(stopReply)
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
''',
    '''            val stoppedSignal = GdbRemoteStoppedSignalPassthrough.signalNumber(stopReply)
            if (
                stoppedSignal != null &&
                GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(stopReply)
            ) {
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    if (stoppedThreadId != null) mutable.selectedThreadId = stoppedThreadId
                    mutable.autoSignalPassthroughCount += 1
                    mutable.lastAutoPassedSignal = stoppedSignal
                    mutable.targetRunning = true
                    mutable.failure = null
                }
                appendAudit("auto_live_exact_thread_scoped_signal_passthrough")
                recordTimeline(
                    kind = "auto_signal_passthrough",
                    summary = "target signal 0x${stoppedSignal.toString(16).padStart(2, '0')} passed only to stopped thread=${stoppedThreadId ?: "unknown"}; all other threads plain-continued before heavy context capture",
                    threadId = stoppedThreadId,
                )
                stopReply = activeClient.continuePassingLastStoppedSignal()
                continue
            }
''',
)
replace_exact(
    bridge,
    '''                .put("lastAutoPassedSignal", mutable.lastAutoPassedSignal ?: JSONObject.NULL)
                .put("timelineCount", mutable.timeline.size)''',
    '''                .put("lastAutoPassedSignal", mutable.lastAutoPassedSignal ?: JSONObject.NULL)
                .put("autoSignalPassthroughScope", "stopped_thread_only")
                .put("explicitSignalReasonTrapPassthrough", true)
                .put("timelineCount", mutable.timeline.size)''',
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '''    appendLine("lastAutoPassedSignal=${control.lastAutoPassedSignal?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "无"}")''',
    '''    appendLine("lastAutoPassedSignal=${control.lastAutoPassedSignal?.let { "0x${it.toString(16).padStart(2, '0')}" } ?: "无"}")
    appendLine("autoSignalPassthroughScope=stopped_thread_only")
    appendLine("explicitSignalReasonTrapPassthrough=true")''',
)
replace_exact(
    ui,
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；自动运行期间仅允许把刚刚 trusted stop 中的 exact non-SIGTRAP signal 原样交还目标；无 arbitrary signal / software breakpoint / register write / memory write / raw packet adapter")''',
    '''    appendLine("边界：loopback only；寄存器/内存只读；允许显式授权 typed attach / continue / step / interrupt / AArch64 hardware execution breakpoint；支持自动 main-thread PC one-shot anchor；自动运行期间 signal 只可从同一 trusted stop 提取并仅交给该 stopped thread，其他线程 plain continue；SIGTRAP 仅在 reason:signal 时按目标信号处理；无 arbitrary signal / software breakpoint / register write / memory write / raw packet adapter")''',
)

# Protocol regression tests: never use a process-wide Cxx default action.
test_path = Path("app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerThreadScopedSignalStateMachineTest.kt")
if test_path.exists():
    raise SystemExit(f"{test_path}: already exists")
test_path.write_text(r'''package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class HostDebuggerThreadScopedSignalStateMachineTest {
    @Test
    fun scopesSigsegvToOnlyTheStoppedThread() {
        val stop = "T0bthread:7a23;reason:signal;"
        assertTrue(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(stop))
        assertEquals(
            "vCont;C0b:7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }

    @Test
    fun explicitSignalReasonSigtrapIsTargetSignalButGenericTrapIsReserved() {
        val targetTrap = "T05thread:7a23;reason:signal;"
        assertTrue(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(targetTrap))
        assertEquals(
            "vCont;C05:7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(targetTrap),
        )

        val debuggerTrap = "T05thread:7a23;reason:breakpoint;"
        assertFalse(GdbRemoteStoppedSignalPassthrough.isAutoPassableTargetSignal(debuggerTrap))
        assertThrows(IllegalArgumentException::class.java) {
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(debuggerTrap)
        }
    }

    @Test
    fun multiprocessThreadIdRemainsScoped() {
        val stop = "T0bthread:p6901.7a23;reason:signal;"
        assertEquals(
            "vCont;C0b:p6901.7a23;c",
            GdbRemoteStoppedSignalPassthrough.packetFromStopReply(stop),
        )
    }
}
''', encoding="utf-8")

print("Applied AutoCrackApp phase 5.15.5 thread-scoped signal state-machine patch")
