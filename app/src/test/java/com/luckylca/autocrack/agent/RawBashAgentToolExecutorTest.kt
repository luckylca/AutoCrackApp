package com.luckylca.autocrack.agent

import com.luckylca.autocrack.runtime.HostExecutionIdentity
import com.luckylca.autocrack.runtime.RuntimeCapabilityMode
import com.luckylca.autocrack.runtime.RuntimeEngine
import com.luckylca.autocrack.runtime.ShellCommandRequest
import com.luckylca.autocrack.runtime.ShellCommandResult
import com.luckylca.autocrack.runtime.WorkspaceFileService
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RawBashAgentToolExecutorTest {
    @Test
    fun exposesOnlyRawPrimitiveTools() {
        val names = RawBashAgentToolExecutor.buildDefinitions().map { it.name }

        assertEquals(
            listOf("exec_bash", "read_file", "write_file", "kill_process"),
            names,
        )
    }

    @Test
    fun writeThenReadFileStaysInsideWorkspace() = runBlocking {
        val workspace = WorkspaceFileService(tempWorkspace())
        val executor = executor(workspaceFiles = workspace)

        val write = JSONObject(
            executor.dispatch(
                "write_file",
                JSONObject()
                    .put("path", "notes/plan.txt")
                    .put("content", "raw bash agent"),
            ),
        )
        val read = JSONObject(
            executor.dispatch(
                "read_file",
                JSONObject()
                    .put("path", "notes/plan.txt")
                    .put("max_chars", 100),
            ),
        )

        assertEquals(true, write.getBoolean("ok"))
        assertEquals("notes/plan.txt", write.getJSONObject("entry").getString("relativePath"))
        assertEquals("/workspace", write.getString("workspaceRoot"))
        assertEquals("/workspace/notes/plan.txt", write.getString("agentPath"))
        assertEquals("raw bash agent", read.getString("content"))
        assertEquals("/workspace", read.getString("workspaceRoot"))
        assertEquals("/workspace/notes/plan.txt", read.getString("agentPath"))
        Unit
    }

    @Test
    fun execBashRunsInWorkspaceAndPassesGenericAgentEnvironment() = runBlocking {
        val chroot = FakeEngine(stdout = "done")
        val executor = RawBashAgentToolExecutor(
            packageName = null,
            chroot = chroot,
            host = FakeEngine(),
            workspaceFiles = WorkspaceFileService(tempWorkspace()),
            availableToolCommands = listOf("rizin", "jadx", "jadx"),
        )

        val result = JSONObject(
            executor.dispatch(
                "exec_bash",
                JSONObject()
                    .put("script", "python3 - <<'PY'\nprint('ok')\nPY")
                    .put("cwd", "/workspace/analysis")
                    .put("timeout_ms", 12_000),
            ),
        )

        assertEquals(true, result.getBoolean("ok"))
        assertEquals("/workspace", result.getString("workspace"))
        assertEquals("/workspace/analysis", chroot.lastRequest!!.workingDirectory)
        assertFalse(chroot.lastRequest!!.environment.containsKey("AUTOC_TARGET_PACKAGE"))
        assertEquals("jadx,rizin", chroot.lastRequest!!.environment["AUTOC_TOOLPACK_COMMANDS"])
        assertEquals("raw_bash", chroot.lastRequest!!.environment["AUTOC_AGENT_MODE"])
        assertEquals(12_000L, chroot.lastRequest!!.timeoutMillis)
    }

    @Test
    fun execBashRejectsCwdOutsideWorkspace() {
        assertThrows(IllegalArgumentException::class.java) {
            RawBashAgentToolExecutor.normalizeWorkspaceCwd("/system")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RawBashAgentToolExecutor.normalizeWorkspaceCwd("/workspace/../root")
        }
    }

    @Test
    fun killProcessUsesHostRuntimeKillSwitch() = runBlocking {
        val host = FakeEngine(stdout = "pid=123\nsignal=TERM\nbefore=present\nafter=missing\nkill_rc=0\n")
        val executor = executor(host = host)

        val result = JSONObject(
            executor.dispatch(
                "kill_process",
                JSONObject()
                    .put("pid", 123)
                    .put("signal", "term"),
            ),
        )

        assertEquals(true, result.getBoolean("ok"))
        assertTrue(host.lastRequest!!.command.contains("kill -TERM"))
        assertEquals("/", host.lastRequest!!.workingDirectory)
        assertEquals(HostExecutionIdentity.ROOT, host.lastRequest!!.identity)
    }

    @Test
    fun dangerousExecCanBeDeniedBeforeChrootExecution() = runBlocking {
        val chroot = FakeEngine(stdout = "should-not-run")
        var captured: DangerousOperationRequest? = null
        val executor = RawBashAgentToolExecutor(
            packageName = null,
            chroot = chroot,
            host = FakeEngine(),
            workspaceFiles = WorkspaceFileService(tempWorkspace()),
            sessionId = "session-1",
            dangerousOperationGate = { request ->
                captured = request
                DangerousOperationDecision.DENY
            },
        )

        val result = JSONObject(
            executor.dispatch(
                "exec_bash",
                JSONObject()
                    .put("script", "rm -rf /data/local/tmp/test")
                    .put("reason", "clean generated test files"),
            ),
        )

        assertFalse(result.getBoolean("ok"))
        assertEquals("DESTRUCTIVE_DELETE", result.getString("dangerousCategory"))
        val request = requireNotNull(captured)
        assertEquals("session-1", request.conversationId)
        assertEquals("clean generated test files", request.reason)
        assertNull(chroot.lastRequest)
    }

    private fun executor(
        chroot: RuntimeEngine = FakeEngine(),
        host: RuntimeEngine = FakeEngine(),
        workspaceFiles: WorkspaceFileService = WorkspaceFileService(tempWorkspace()),
        dynamicToolsAllowed: Boolean = false,
    ): RawBashAgentToolExecutor = RawBashAgentToolExecutor(
        packageName = "com.example.target",
        chroot = chroot,
        host = host,
        workspaceFiles = workspaceFiles,
        dynamicToolsAllowed = dynamicToolsAllowed,
    )

    private fun tempWorkspace(): File = Files.createTempDirectory("raw-bash-agent-test-").toFile().canonicalFile

    private class FakeEngine(
        private val stdout: String = "",
        private val stderr: String = "",
        private val exitCode: Int = 0,
    ) : RuntimeEngine {
        override val mode: RuntimeCapabilityMode = RuntimeCapabilityMode.FULL_ROOT
        var lastRequest: ShellCommandRequest? = null

        override suspend fun execute(request: ShellCommandRequest): ShellCommandResult {
            lastRequest = request
            return ShellCommandResult(
                requestId = "test-request",
                command = request.command,
                workingDirectory = request.workingDirectory,
                identity = request.identity,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                startedAtEpochMillis = 1_000L,
                completedAtEpochMillis = 1_010L,
                timedOut = false,
                cancelled = false,
                stdoutTruncated = false,
                stderrTruncated = false,
                failure = null,
                auditFilePath = "audit.jsonl",
            )
        }
    }
}
