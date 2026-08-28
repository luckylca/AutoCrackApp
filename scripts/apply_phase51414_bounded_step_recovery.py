#!/usr/bin/env python3
from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(
            f"{path}: expected {expected} occurrence(s) of {old!r}, found {actual}"
        )
    p.write_text(text.replace(old, new), encoding="utf-8")


# Keep the already device-validated 1.3.0 seize-runtime-stop LLDB toolpack pinned.
OLD_VERSION = "ndk-r27d-clang-r522817d_autocrack-1.0.0"
NEW_VERSION = "android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop"
OLD_PAYLOAD_SHA = "2cc969ff785e8c0c3d4473649edaaf18f64faae4c7e016941dd0c0944944a14a"
NEW_PAYLOAD_SHA = "f2d3b3925ffc49419508dd97cd657d4a8a2e0b0b313f473105173b96ce31b899"
OLD_PAYLOAD_SIZE = "27_698_528L"
NEW_PAYLOAD_SIZE = "28_396_656L"
OLD_SOURCE_VERSION = "ndk-r27d-clang-r522817d"
NEW_SOURCE_VERSION = "android-llvm-r522817-autocrack-seize-runtime-stop"
OLD_SOURCE_URL = "https://github.com/android/ndk/releases/tag/r27d"
NEW_SOURCE_URL = "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262"
OLD_BINARY_SHA = "ff96d83baa872b2226bb1f4f38cd38aa2622416722fb76543cc536edfeea3018"
NEW_BINARY_SHA = "71d9ed6a90776d7dbdbcb315ea2171a763c071e5a370ec1b8b0c28157af41b20"
NEW_SELF_TEST_COMMAND = (
    'command = "test -x /opt/autocrack/toolpacks/packs/android-lldb-server/'
    'android-llvm-r522817_autocrack-1.3.0-seize-runtime-stop/bin/lldb-server-android '
    '&& printf \\"AUTOCRACK_LLDB_ANDROID_BINARY_OK\\\\n\\\"",'
)

trust = "app/src/main/java/com/luckylca/autocrack/runtime/BuiltInToolpackTrustPolicy.kt"
replace_exact(trust, OLD_VERSION, NEW_VERSION)
replace_exact(trust, OLD_PAYLOAD_SHA, NEW_PAYLOAD_SHA)
replace_exact(trust, OLD_PAYLOAD_SIZE, NEW_PAYLOAD_SIZE)
replace_exact(trust, OLD_SOURCE_VERSION, NEW_SOURCE_VERSION)
replace_exact(trust, OLD_SOURCE_URL, NEW_SOURCE_URL)
replace_exact(trust, OLD_BINARY_SHA, NEW_BINARY_SHA)
replace_exact(
    trust,
    '"lldb-server-version" to TrustedSelfTest(\n                    title = "Android LLDB server",',
    '"lldb-server-android-binary" to TrustedSelfTest(\n                    title = "Android LLDB server payload",',
)
replace_exact(trust, 'command = "lldb-server-android v",', NEW_SELF_TEST_COMMAND)
replace_exact(
    trust,
    'outputContains = listOf("lldb"),',
    'outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),',
)

test = "app/src/test/java/com/luckylca/autocrack/runtime/AndroidLldbToolpackTrustPolicyTest.kt"
replace_exact(test, OLD_VERSION, NEW_VERSION)
replace_exact(test, OLD_PAYLOAD_SHA, NEW_PAYLOAD_SHA)
replace_exact(test, OLD_PAYLOAD_SIZE, NEW_PAYLOAD_SIZE)
replace_exact(test, OLD_SOURCE_VERSION, NEW_SOURCE_VERSION)
replace_exact(test, OLD_SOURCE_URL, NEW_SOURCE_URL)
replace_exact(test, OLD_BINARY_SHA, NEW_BINARY_SHA)
replace_exact(
    test,
    'id = "lldb-server-version",\n                title = "Android LLDB server",',
    'id = "lldb-server-android-binary",\n                title = "Android LLDB server payload",',
)
replace_exact(test, 'command = "lldb-server-android v",', NEW_SELF_TEST_COMMAND)
replace_exact(
    test,
    'outputContains = listOf("lldb"),',
    'outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),',
)

manager = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerSessionManager.kt"
replace_exact(manager, OLD_VERSION, NEW_VERSION)

catalog = "app/src/main/assets/runtime/dynamic-host-tool-catalog-v1.json"
replace_exact(catalog, '"catalogVersion": "0.5.14.5"', '"catalogVersion": "0.5.14.14"')
replace_exact(catalog, OLD_VERSION, NEW_VERSION, expected=2)

gradle = "app/build.gradle.kts"
replace_exact(gradle, "versionCode = 42", "versionCode = 49")
replace_exact(
    gradle,
    'versionName = "0.5.14.7-phase5.14-server-ptrace-trace"',
    'versionName = "0.5.14.14-phase5.14-bounded-step-recovery"',
)

# A genuine single-step can remain inside a blocking syscall (for example Android's
# epoll-based Looper wait). Do not leave the UI waiting 30 seconds before the already
# validated protocol interrupt recovery path becomes available.
remote = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClient.kt"
replace_exact(
    remote,
    "        private const val STEP_WAIT_TIMEOUT_MILLIS = 30_000",
    "        internal const val STEP_WAIT_TIMEOUT_MILLIS = 2_000",
)

bridge = "app/src/main/java/com/luckylca/autocrack/runtime/HostDebuggerControlBridge.kt"
replace_exact(
    bridge,
    "    val stepCommandSent: Boolean,\n    val interruptCommandSent: Boolean,",
    "    val stepCommandSent: Boolean,\n    val stepAutoInterruptRecovered: Boolean,\n    val interruptCommandSent: Boolean,",
)

old_step = '''    suspend fun step(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        requireStoppedClient()
        synchronized(lock) {
            mutable.stepCommandSent = true
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("step_start")
        try {
            val stopReply = requireNotNull(client).step()
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("step_stop")
            snapshot()
        } catch (exception: Exception) {
            val unresolved = exception is GdbRemoteRunTimeoutException
            synchronized(lock) {
                // A continue-class timeout means only that AutoCrack's bounded reader expired.
                // The packet was already sent and the target/server may still be stepping, so it
                // is unsafe to expose another step/continue as if the target were known stopped.
                mutable.targetRunning = unresolved
                mutable.failure = if (unresolved) {
                    "${exception.message}；step 已发送但目标状态仍未确认，请使用 interrupt 恢复 stop reply，暂勿再次 step/continue"
                } else {
                    exception.message ?: exception::class.java.simpleName
                }
            }
            appendAudit(if (unresolved) "step_timeout_target_state_unresolved" else "step_failed")
            throw exception
        }
    }
'''
new_step = '''    suspend fun step(): HostDebuggerControlSnapshot = withContext(Dispatchers.IO) {
        requireStoppedClient()
        val activeClient = requireNotNull(client)
        synchronized(lock) {
            mutable.stepCommandSent = true
            mutable.stepAutoInterruptRecovered = false
            mutable.targetRunning = true
            mutable.failure = null
        }
        appendAudit("step_start")
        try {
            val stopReply = activeClient.step()
            synchronized(lock) {
                mutable.lastStopReply = stopReply
                mutable.targetRunning = false
                mutable.failure = null
            }
            appendAudit("step_stop")
            snapshot()
        } catch (timeout: GdbRemoteRunTimeoutException) {
            // PTRACE_SINGLESTEP is allowed to remain inside a blocking syscall. After a short,
            // bounded wait, use the already-authorized fixed gdb-remote interrupt byte to recover
            // a trustworthy stop state instead of forcing the user to wait 30 seconds and press
            // Interrupt manually. This adds no raw packet, signal, write, or breakpoint surface.
            appendAudit("step_wait_timeout_auto_interrupt_start")
            try {
                activeClient.interrupt()
                synchronized(lock) { mutable.interruptCommandSent = true }
                appendAudit("step_auto_interrupt_sent")

                val stopReply = activeClient.awaitStopAfterInterrupt()
                synchronized(lock) {
                    mutable.lastStopReply = stopReply
                    mutable.stepAutoInterruptRecovered = true
                    mutable.targetRunning = false
                    mutable.failure = null
                }
                appendAudit("step_timeout_auto_interrupt_recovered")
                snapshot()
            } catch (recovery: Exception) {
                recovery.addSuppressed(timeout)
                synchronized(lock) {
                    mutable.targetRunning = true
                    mutable.failure =
                        "step 超时且自动 interrupt 恢复失败；目标状态仍未确认：${recovery.message ?: recovery::class.java.simpleName}"
                }
                appendAudit("step_timeout_auto_interrupt_recovery_failed")
                throw recovery
            }
        } catch (exception: Exception) {
            synchronized(lock) {
                mutable.targetRunning = false
                mutable.failure = exception.message ?: exception::class.java.simpleName
            }
            appendAudit("step_failed")
            throw exception
        }
    }
'''
replace_exact(bridge, old_step, new_step)
replace_exact(
    bridge,
    '                .put("stepCommandSent", mutable.stepCommandSent)\n                .put("interruptCommandSent", mutable.interruptCommandSent)',
    '                .put("stepCommandSent", mutable.stepCommandSent)\n                .put("stepAutoInterruptRecovered", mutable.stepAutoInterruptRecovered)\n                .put("interruptCommandSent", mutable.interruptCommandSent)',
)
replace_exact(
    bridge,
    "        stepCommandSent = mutable.stepCommandSent,\n        interruptCommandSent = mutable.interruptCommandSent,",
    "        stepCommandSent = mutable.stepCommandSent,\n        stepAutoInterruptRecovered = mutable.stepAutoInterruptRecovered,\n        interruptCommandSent = mutable.interruptCommandSent,",
)
replace_exact(
    bridge,
    "        var stepCommandSent: Boolean = false,\n        var interruptCommandSent: Boolean = false,",
    "        var stepCommandSent: Boolean = false,\n        var stepAutoInterruptRecovered: Boolean = false,\n        var interruptCommandSent: Boolean = false,",
)

ui = "app/src/main/java/com/luckylca/autocrack/ui/DebuggerSessionScreen.kt"
replace_exact(
    ui,
    '            status = "正在单步执行 1 条指令并等待再次停止"',
    '            status = "正在单步执行 1 条指令；2 秒内无 stop reply 时将自动 protocol interrupt 恢复"',
)
replace_exact(
    ui,
    '                    status = "单步完成：stop=${result.lastStopReply ?: "未知"}"',
    '''                    status = if (result.stepAutoInterruptRecovered) {
                        "单步未在 2 秒窗口内返回，已自动 interrupt 恢复可信 stop：${result.lastStopReply ?: "未知"}"
                    } else {
                        "单步完成：stop=${result.lastStopReply ?: "未知"}"
                    }''',
)
replace_exact(
    ui,
    '        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} interrupt=${snapshot.interruptCommandSent}",',
    '        "continue=${snapshot.continueCommandSent} step=${snapshot.stepCommandSent} stepAutoInterruptRecovered=${snapshot.stepAutoInterruptRecovered} interrupt=${snapshot.interruptCommandSent}",',
)
replace_exact(
    ui,
    '    appendLine("stepCommandSent=${control.stepCommandSent}")\n    appendLine("interruptCommandSent=${control.interruptCommandSent}")',
    '    appendLine("stepCommandSent=${control.stepCommandSent}")\n    appendLine("stepAutoInterruptRecovered=${control.stepAutoInterruptRecovered}")\n    appendLine("interruptCommandSent=${control.interruptCommandSent}")',
)

remote_test = "app/src/test/java/com/luckylca/autocrack/runtime/HostDebuggerRemoteClientTest.kt"
replace_exact(
    remote_test,
    '        assertEquals(5_000, HostDebuggerRemoteClient.RUN_REPLY_POLL_TIMEOUT_MILLIS)\n        assertEquals(30_000, HostDebuggerRemoteClient.INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS)',
    '        assertEquals(5_000, HostDebuggerRemoteClient.RUN_REPLY_POLL_TIMEOUT_MILLIS)\n        assertEquals(2_000, HostDebuggerRemoteClient.STEP_WAIT_TIMEOUT_MILLIS)\n        assertEquals(30_000, HostDebuggerRemoteClient.INTERRUPT_RECOVERY_WAIT_TIMEOUT_MILLIS)',
)

print("Applied AutoCrackApp phase 5.14.14 bounded-step automatic interrupt recovery patch")
