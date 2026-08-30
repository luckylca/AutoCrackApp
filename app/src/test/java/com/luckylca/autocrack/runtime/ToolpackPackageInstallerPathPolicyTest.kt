package com.luckylca.autocrack.runtime

import java.io.File

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackPackageInstallerPathPolicyTest {
    @Test
    fun executableNamespaces_includePublicHostAndPrivateRuntimeBins() {
        assertTrue(isToolpackExecutablePayloadPath("bin/frida"))
        assertTrue(isToolpackExecutablePayloadPath("host-bin/tcpdump"))
        assertTrue(isToolpackExecutablePayloadPath("host-bin/lldb-server-android"))
        assertTrue(isToolpackExecutablePayloadPath("lib/llvm-14/bin/lldb"))
        assertTrue(isToolpackExecutablePayloadPath("lib/llvm-14/bin/lldb-argdumper"))
        assertTrue(isToolpackExecutablePayloadPath("lib/node_modules/example/bin/tool"))
    }

    @Test
    fun dataAndLibraryPaths_areNotMadeExecutableByConvention() {
        assertFalse(isToolpackExecutablePayloadPath("python/frida/__init__.py"))
        assertFalse(isToolpackExecutablePayloadPath("lib/liblldb.so"))
        assertFalse(isToolpackExecutablePayloadPath("lib/llvm-14/lib/liblldb.so"))
        assertFalse(isToolpackExecutablePayloadPath("libexec/agent.js"))
    }

    @Test
    fun extensionlessPrivateHelpers_restoreElfAndShebangModes() {
        val root = File(System.getProperty("java.io.tmpdir"), "toolpack-mode-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        try {
            val elf = File(root, "lldb-argdumper").apply {
                writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0))
            }
            val script = File(root, "helper").apply { writeText("#!/bin/sh\nexit 0\n") }
            val data = File(root, "README").apply { writeText("plain data") }
            val library = File(root, "liblldb.so").apply {
                writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 0))
            }

            assertTrue(
                shouldRestoreToolpackExecutableMode(
                    elf,
                    "lib/llvm-14/lib/python3.11/dist-packages/lldb/lldb-argdumper",
                ),
            )
            assertTrue(shouldRestoreToolpackExecutableMode(script, "libexec/private/helper"))
            assertFalse(shouldRestoreToolpackExecutableMode(data, "share/README"))
            assertFalse(shouldRestoreToolpackExecutableMode(library, "lib/liblldb.so"))
        } finally {
            root.deleteRecursively()
        }
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
