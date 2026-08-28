package com.luckylca.autocrack.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootShellProcessCommandFactoryTest {
    @Test
    fun rootExecutionInvokesSuDirectlyWithoutOuterShellWrapper() {
        val command = RootShellProcessCommandFactory.build(
            identity = HostExecutionIdentity.ROOT,
            suPath = "/system/bin/su",
            script = "printf test",
        )

        assertEquals(listOf("/system/bin/su", "-c", "printf test"), command)
        assertFalse(command.contains("/system/bin/sh"))
    }

    @Test
    fun appExecutionStillUsesAndroidShell() {
        assertEquals(
            listOf("/system/bin/sh", "-c", "printf test"),
            RootShellProcessCommandFactory.build(
                identity = HostExecutionIdentity.APP,
                suPath = "/system/bin/su",
                script = "printf test",
            ),
        )
    }
}
