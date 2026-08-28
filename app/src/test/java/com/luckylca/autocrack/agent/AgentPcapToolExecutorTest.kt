package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPcapToolExecutorTest {
    @Test
    fun toolpackPinMatchesRootfsPcapAnalyzer() {
        assertEquals("rootfs-pcap-analysis", AgentPcapToolExecutor.TOOLPACK_ID)
        assertEquals("pcap-summary-1.0.0", AgentPcapToolExecutor.TOOLPACK_VERSION)
    }

    @Test
    fun pcapToolsAreWorkspaceScopedAndDoNotExposeArbitraryPaths() {
        assertEquals(
            listOf(
                "rootfs_pcap_info",
                "rootfs_pcap_protocol_summary",
                "rootfs_pcap_dns_summary",
                "rootfs_pcap_tls_summary",
                "rootfs_pcap_top_connections",
            ),
            AgentPcapToolExecutor.PCAP_TOOL_NAMES,
        )
        assertFalse(AgentPcapToolExecutor.PCAP_TOOL_NAMES.any { it.contains("path") || it.contains("filter") })
        assertTrue(AgentPcapToolExecutor.PCAP_TOOL_NAMES.all { it.startsWith("rootfs_pcap_") })
    }
}
