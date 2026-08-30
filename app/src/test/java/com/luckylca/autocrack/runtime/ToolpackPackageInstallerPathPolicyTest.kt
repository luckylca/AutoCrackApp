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

    @Test
    fun lldbUpgrade_marksOnlyLegacyCommandShimAsObsolete() {
        val previous = listOf(
            ToolpackCommand("lldb-server-android", "bin/lldb-server-android"),
        )
        val current = listOf(
            ToolpackCommand("lldb", "bin/lldb"),
            ToolpackCommand("android-lldb-server", "bin/android-lldb-server"),
        )

        val obsolete = obsoleteToolpackCommandNames(previous, current)

        assertTrue("lldb-server-android" in obsolete)
        assertFalse("lldb" in obsolete)
        assertFalse("android-lldb-server" in obsolete)
    }
}
