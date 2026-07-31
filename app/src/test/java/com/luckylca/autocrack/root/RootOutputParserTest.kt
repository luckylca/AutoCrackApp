package com.luckylca.autocrack.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootOutputParserTest {
    @Test
    fun parseIdentity_readsUidGidAndSelinuxContext() {
        val identity = RootOutputParser.parseIdentity(
            "uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0",
        )

        assertEquals(0, identity?.uid)
        assertEquals(0, identity?.gid)
        assertEquals("u:r:ksu:s0", identity?.selinuxContext)
    }

    @Test
    fun parseIdentity_returnsNullForUnrelatedOutput() {
        assertNull(RootOutputParser.parseIdentity("permission denied"))
    }

    @Test
    fun detectProvider_recognizesKernelSuVersionText() {
        assertEquals(
            RootProvider.KERNEL_SU,
            RootOutputParser.detectProvider(listOf("KernelSU v1.0.0")),
        )
    }

    @Test
    fun detectProvider_recognizesKernelSuFilesystemMarker() {
        assertEquals(
            RootProvider.KERNEL_SU,
            RootOutputParser.detectProvider(listOf("KSU_MARKER")),
        )
    }

    @Test
    fun detectProvider_marksNonEmptyUnknownImplementationAsOther() {
        assertEquals(
            RootProvider.OTHER,
            RootOutputParser.detectProvider(listOf("su version 3.0")),
        )
    }
}
