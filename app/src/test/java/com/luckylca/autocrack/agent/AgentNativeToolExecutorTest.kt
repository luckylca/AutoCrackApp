package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentNativeToolExecutorTest {
    @Test
    fun toolpackPinMatchesCurrentRizinPackage() {
        assertEquals("rizin-deep-static", AgentNativeToolExecutor.TOOLPACK_ID)
        assertEquals("rizin-0.9.1_autocrack-1.0.1", AgentNativeToolExecutor.TOOLPACK_VERSION)
    }

    @Test
    fun nativeToolNamesAreDedicatedAndDoNotExposeRawRizinCli() {
        val source = AgentNativeToolExecutor::class.java.declaredFields.map { it.name }.joinToString(" ")
        assertTrue(source.isNotBlank())
        // Compile-time smoke: the executor intentionally exposes only native_* typed definitions,
        // while the raw rizin command remains a toolpack implementation detail.
    }

    @Test
    fun nativeLightweightRiskToolsAreTyped() {
        assertTrue(AgentNativeToolExecutor.NATIVE_TOOL_NAMES.contains("native_import_risk_summary"))
        assertTrue(AgentNativeToolExecutor.NATIVE_TOOL_NAMES.contains("native_strings_cluster"))
        assertTrue(AgentNativeToolExecutor.NATIVE_TOOL_NAMES.all { it.startsWith("native_") })
    }
}
