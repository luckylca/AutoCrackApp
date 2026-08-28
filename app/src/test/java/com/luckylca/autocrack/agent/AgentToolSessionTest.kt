package com.luckylca.autocrack.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentToolSessionTest {
    @Test
    fun routesToolsToOwningExecutor() = runBlocking {
        val first = FakeExecutor("static_one")
        val second = FakeExecutor("perfetto_one")
        val session = AgentToolSession(listOf(first, second))

        assertEquals(2, session.tools.size)
        assertEquals("static_one", JSONObject(session.dispatch("static_one", JSONObject())).getString("tool"))
        assertEquals("perfetto_one", JSONObject(session.dispatch("perfetto_one", JSONObject())).getString("tool"))
    }

    @Test
    fun rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolSession(listOf(FakeExecutor("same"), FakeExecutor("same")))
        }
    }

    @Test
    fun dynamicBackendsAreMutuallyExclusiveUntilStopped() = runBlocking {
        val debugger = FakeExecutor("debugger_attach", "debugger_detach")
        val frida = FakeExecutor("frida_start", "frida_stop")
        val session = AgentToolSession(listOf(debugger, frida))

        session.dispatch("debugger_attach", JSONObject())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.dispatch("frida_start", JSONObject()) }
        }
        session.dispatch("debugger_detach", JSONObject())
        session.dispatch("frida_start", JSONObject())
        session.dispatch("frida_stop", JSONObject())
        Unit
    }

    @Test
    fun fridaTlsTraceActivatesFridaBackend() = runBlocking {
        val debugger = FakeExecutor("debugger_attach", "debugger_detach")
        val frida = FakeExecutor("frida_tls_trace", "frida_stop")
        val session = AgentToolSession(listOf(debugger, frida))

        session.dispatch("frida_tls_trace", JSONObject())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { session.dispatch("debugger_attach", JSONObject()) }
        }
        session.dispatch("frida_stop", JSONObject())
        session.dispatch("debugger_attach", JSONObject())
        Unit
    }

    @Test
    fun androidNetworkCaptureDoesNotActivateDynamicBackend() = runBlocking {
        val network = FakeExecutor("android_pcap_start", "android_pcap_status", "android_pcap_stop")
        val frida = FakeExecutor("frida_start", "frida_stop")
        val session = AgentToolSession(listOf(network, frida))

        session.dispatch("android_pcap_start", JSONObject().put("duration_seconds", 1))
        session.dispatch("frida_start", JSONObject())
        session.dispatch("frida_stop", JSONObject())
        session.dispatch("android_pcap_stop", JSONObject())
        Unit
    }

    private class FakeExecutor(vararg names: String) : AgentToolExecutor {
        override val tools = names.map { AgentToolDefinition(it, it, AgentJsonSchema.emptyObject()) }
        override suspend fun dispatch(toolName: String, arguments: JSONObject): String =
            JSONObject().put("ok", true).put("tool", toolName).toString()
    }
}
