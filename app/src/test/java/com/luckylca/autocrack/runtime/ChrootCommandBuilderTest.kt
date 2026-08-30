package com.luckylca.autocrack.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootCommandBuilderTest {
    @Test
    fun buildsIsolatedBashCommandWithGuestWorkingDirectory() {
        val command = ChrootCommandBuilder.build(
            rootfsPath = "/data/user/0/com.example/files/runtime/rootfs/current",
            request = ShellCommandRequest(
                command = "printf '%s' \"${'$'}VALUE\"",
                workingDirectory = "/workspace",
                environment = mapOf("VALUE" to "hello rootfs"),
                timeoutMillis = 1_000L,
            ),
        )

        assertTrue(command.contains("exec chroot"))
        assertTrue(command.contains("/usr/bin/env -i"))
        assertTrue(command.contains("VALUE='hello rootfs'"))
        assertTrue(command.contains("/bin/bash --noprofile --norc"))
        assertTrue(command.contains("/workspace"))
        assertTrue(command.contains("AUTOC_ROOTFS_HOST_PATH="))
        assertTrue(command.contains("/opt/autocrack/toolpacks/active"))
        assertTrue(command.contains("[ -L"))
        assertTrue(command.contains("PYTHONPATH="))
        assertTrue(command.contains("NODE_PATH="))
        assertTrue(command.contains("CLASSPATH="))
    }

    @Test
    fun mountScriptMapsManagedWorkspaceAndAndroidDirectories() {
        val script = MountScriptBuilder.prepare(
            rootfsPath = "/data/rootfs",
            workspacePath = "/data/workspace",
            homePath = "/data/home",
        )

        assertTrue(script.contains("mount --bind"))
        assertTrue(script.contains("/data/workspace"))
        assertTrue(script.contains("ROOTFS_MOUNTS_READY"))
        assertTrue(script.contains("/system"))
    }
}
