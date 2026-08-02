package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountScriptBuilderTest {
    @Test
    fun cleanupUnmountsDescendantsBeforeParentsAndVerifiesResult() {
        val script = MountScriptBuilder.cleanup("/data/rootfs/current")

        assertTrue(script.contains("/proc/mounts"))
        assertTrue(script.contains("ROOTFS_MOUNTS_REMAIN"))
        assertTrue(script.contains("ROOTFS_MOUNTS_CLEAN"))
        assertTrue(script.contains("umount -l"))
        assertFalse(script.contains("for TARGET in apex vendor system root workspace sys proc dev"))
    }
}
