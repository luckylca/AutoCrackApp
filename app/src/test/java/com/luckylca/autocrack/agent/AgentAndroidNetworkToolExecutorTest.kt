package com.luckylca.autocrack.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAndroidNetworkToolExecutorTest {
    @Test
    fun networkToolNamesStayAndroidRootfsScoped() {
        assertEquals("android_rootfs_only", AgentAndroidNetworkToolExecutor.RUNTIME_TARGET)
        assertEquals(
            listOf(
                "android_surfing_status",
                "android_net_environment",
                "android_net_target_connections",
                "android_pcap_start",
                "android_pcap_status",
                "android_pcap_stop",
            ),
            AgentAndroidNetworkToolExecutor.NETWORK_TOOL_NAMES,
        )
        assertFalse(AgentAndroidNetworkToolExecutor.NETWORK_TOOL_NAMES.any { name ->
            name.contains("desktop", ignoreCase = true) ||
                name.contains("mac", ignoreCase = true) ||
                name.contains("vpn", ignoreCase = true) ||
                name.contains("mitm", ignoreCase = true)
        })
    }

    @Test
    fun pcapStartAcceptsOnlyBoundedCaptureArguments() {
        AgentAndroidNetworkToolExecutor.requireKnownArguments(
            toolName = AgentAndroidNetworkToolExecutor.TOOL_PCAP_START,
            arguments = JSONObject()
                .put("duration_seconds", 30)
                .put("snaplen", 256)
                .put("max_bytes", 16L * 1024L * 1024L),
            allowedKeys = AgentAndroidNetworkToolExecutor.PCAP_START_ARGUMENT_KEYS,
        )

        val forbiddenArguments = listOf(
            "interface",
            "iptables_rule",
            "nft_rule",
            "proxy_config_path",
            "surfing_config_patch",
            "install_ca_certificate",
            "vpn_mode",
            "mitm_enabled",
            "raw_filter",
        )

        forbiddenArguments.forEach { argumentName ->
            val exception = assertThrows(IllegalArgumentException::class.java) {
                AgentAndroidNetworkToolExecutor.requireKnownArguments(
                    toolName = AgentAndroidNetworkToolExecutor.TOOL_PCAP_START,
                    arguments = JSONObject().put(argumentName, true),
                    allowedKeys = AgentAndroidNetworkToolExecutor.PCAP_START_ARGUMENT_KEYS,
                )
            }
            assertTrue(exception.message.orEmpty().contains(argumentName))
        }
    }
}
