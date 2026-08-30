package com.luckylca.autocrack.debug

import android.app.Activity
import android.os.Bundle
import com.luckylca.autocrack.agent.AgentToolSessionFactory
import com.luckylca.autocrack.agent.DangerousOperationDecision
import com.luckylca.autocrack.root.ProcessRootCommandRunner
import com.luckylca.autocrack.root.RootDetector
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Debug-only real-device probe of the upstream LLDB CLI through the production Mobile Agent. */
class DebugStandardLldbValidationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val report = runCatching { runBlocking { validate() } }.getOrElse { error ->
                JSONObject()
                    .put("success", false)
                    .put("failure", error.message ?: error::class.java.name)
                    .put("exception", error::class.java.name)
            }
            val output = File(filesDir, REPORT_PATH)
            output.parentFile?.mkdirs()
            output.writeText(report.toString(2), Charsets.UTF_8)
            runOnUiThread { finish() }
        }.start()
    }

    private suspend fun validate(): JSONObject {
        val runner = ProcessRootCommandRunner()
        val detector = RootDetector(runner)
        val root = detector.inspect()
        check(root.isRootGranted) { root.diagnostic ?: "Root not granted" }
        val runtime = AgentToolSessionFactory(applicationContext, runner, detector).createMobileAgent(
            sessionId = UUID.randomUUID().toString(),
            knownRootStatus = root,
            dangerousOperationGate = { DangerousOperationDecision.ALLOW_ONCE },
        )
        val script = """
            set -eu
            command -v lldb
            command -v android-lldb-server
            lldb --version

            android-shell am start -n com.luckylca.autocrack/.debug.DebugFridaFixtureActivity >/dev/null
            sleep 1
            target_pid="${'$'}(android-shell pidof 'com.luckylca.autocrack:frida_fixture' | tr -d '\r\n')"
            test -n "${'$'}target_pid"
            printf 'AUTOCRACK_LLDB_TARGET_PID=%s\n' "${'$'}target_pid"

            server_client=''
            cleanup() {
              if [ -n "${'$'}server_client" ]; then
                kill "${'$'}server_client" >/dev/null 2>&1 || true
                wait "${'$'}server_client" >/dev/null 2>&1 || true
              fi
              android-shell sh -c "tracer=\${'$'}(awk '/^TracerPid:/ { print \\$2; exit }' /proc/${'$'}target_pid/status 2>/dev/null); case \"\${'$'}{tracer:-0}\" in ''|0) ;; *) cmd=\${'$'}(tr '\\000' ' ' < /proc/\${'$'}tracer/cmdline 2>/dev/null || true); case \"\${'$'}cmd\" in *'/host-bin/lldb-server-android'*) kill -TERM \${'$'}tracer >/dev/null 2>&1 || true ;; esac ;; esac; kill -CONT ${'$'}target_pid >/dev/null 2>&1 || true" >/dev/null 2>&1 || true
            }
            trap cleanup EXIT INT TERM

            rm -f /workspace/lldb-standard-server.log /workspace/lldb-standard-client.log /workspace/lldb-standard-probe.py
            cat > /workspace/lldb-standard-probe.py <<'PY'
            import lldb

            target = lldb.debugger.GetSelectedTarget()
            process = target.GetProcess()
            thread = process.GetSelectedThread()
            frame = thread.GetFrameAtIndex(0)
            if not process.IsValid() or not thread.IsValid() or not frame.IsValid():
                raise RuntimeError("invalid LLDB process/thread/frame")

            x0 = frame.FindRegister("x0")
            old_x0 = x0.GetValue()
            reg_error = lldb.SBError()
            reg_ok = bool(old_x0) and x0.SetValueFromCString(old_x0, reg_error)
            print("AUTOCRACK_LLDB_REGISTER_READ=" + str(old_x0))
            print("AUTOCRACK_LLDB_REGISTER_WRITE_OK=" + str(reg_ok and reg_error.Success()).lower())

            sp = frame.FindRegister("sp").GetValueAsUnsigned()
            read_error = lldb.SBError()
            original = process.ReadMemory(sp, 1, read_error)
            if not read_error.Success() or len(original) != 1:
                raise RuntimeError("stack memory read failed: " + str(read_error))
            write_error = lldb.SBError()
            written = process.WriteMemory(sp, original, write_error)
            print("AUTOCRACK_LLDB_MEMORY_READ_BYTE=" + original.hex())
            print("AUTOCRACK_LLDB_MEMORY_WRITE_OK=" + str(written == 1 and write_error.Success()).lower())

            breakpoint = target.BreakpointCreateBySBAddress(frame.GetPCAddress())
            bp_ok = breakpoint.IsValid() and breakpoint.GetNumLocations() > 0
            print("AUTOCRACK_LLDB_BREAKPOINT_OK=" + str(bp_ok).lower())
            if breakpoint.IsValid():
                target.BreakpointDelete(breakpoint.GetID())
            PY

            AUTOC_LLDB_SERVER_TIMEOUT_MS=120000 android-lldb-server \
              gdbserver 127.0.0.1:5039 --attach "${'$'}target_pid" \
              > /workspace/lldb-standard-server.log 2>&1 &
            server_client=${'$'}!

            ready=false
            for _ in ${'$'}(seq 1 100); do
              if awk '${'$'}2 == "0100007F:13AF" && ${'$'}4 == "0A" { found=1 } END { exit(found ? 0 : 1) }' /proc/net/tcp 2>/dev/null; then
                ready=true
                break
              fi
              sleep 0.1
            done
            test "${'$'}ready" = true
            printf 'AUTOCRACK_LLDB_SERVER_READY=true\n'

            lldb --batch \
              -o 'gdb-remote 127.0.0.1:5039' \
              -o 'process status' \
              -o 'register read pc sp x0' \
              -o 'memory read --count 16 --size 1 --format x ${'$'}sp' \
              -o 'command script import /workspace/lldb-standard-probe.py' \
              -o 'thread step-inst' \
              -o 'process status' \
              -o 'detach' \
              2>&1 | tee /workspace/lldb-standard-client.log

            grep -F 'AUTOCRACK_LLDB_REGISTER_WRITE_OK=true' /workspace/lldb-standard-client.log
            grep -F 'AUTOCRACK_LLDB_MEMORY_WRITE_OK=true' /workspace/lldb-standard-client.log
            grep -F 'AUTOCRACK_LLDB_BREAKPOINT_OK=true' /workspace/lldb-standard-client.log
            android-shell sh -c "test \"\${'$'}(awk '/^TracerPid:/ { print \\$2; exit }' /proc/${'$'}target_pid/status)\" = 0"
            test -n "${'$'}(android-shell pidof 'com.luckylca.autocrack:frida_fixture' | tr -d '\r\n')"
            printf 'AUTOCRACK_LLDB_DETACH_OK=true\n'
        """.trimIndent()

        val result = try {
            JSONObject(
                runtime.tools.dispatch(
                    "exec_bash",
                    JSONObject()
                        .put("script", script)
                        .put("cwd", "/workspace")
                        .put("timeout_ms", 120_000L),
                ),
            )
        } finally {
            runCatching { runtime.cleanupSessionProcesses() }
            runtime.cancelAllCommands()
        }
        return JSONObject()
            .put("success", result.optBoolean("ok"))
            .put("result", result)
    }

    private companion object {
        const val REPORT_PATH = "debug-validation/standard-lldb-report.json"
    }
}
