package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceDiagnosticsToolExecutorTest {
    @Test
    fun diagnosticToolsAreAndroidRootfsScoped() {
        assertEquals(
            listOf("android_device_diagnostics", "android_tooling_status"),
            AgentDeviceDiagnosticsToolExecutor.DIAGNOSTIC_TOOL_NAMES,
        )
        assertTrue(AgentDeviceDiagnosticsToolExecutor.DIAGNOSTIC_TOOL_NAMES.all { it.startsWith("android_") })
        assertFalse(AgentDeviceDiagnosticsToolExecutor.DIAGNOSTIC_TOOL_NAMES.any { name ->
            name.contains("desktop", ignoreCase = true) || name.contains("mac", ignoreCase = true)
        })
    }
}
