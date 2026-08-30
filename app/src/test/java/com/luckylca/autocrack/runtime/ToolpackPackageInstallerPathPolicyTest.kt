package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackPackageInstallerPathPolicyTest {
    @Test
    fun executableNamespaces_includeRootfsAndAndroidHostBins() {
        assertTrue(isToolpackExecutableRequiredPath("bin/frida"))
        assertTrue(isToolpackExecutableRequiredPath("host-bin/tcpdump"))
        assertTrue(isToolpackExecutableRequiredPath("host-bin/lldb-server-android"))
    }

    @Test
    fun dataAndLibraryPaths_areNotMadeExecutableByConvention() {
        assertFalse(isToolpackExecutableRequiredPath("python/frida/__init__.py"))
        assertFalse(isToolpackExecutableRequiredPath("lib/liblldb.so"))
        assertFalse(isToolpackExecutableRequiredPath("libexec/agent.js"))
    }
}
