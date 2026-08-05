package com.luckylca.autocrack.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootToolCommandFactoryTest {
    @Test
    fun buildListPackages_usesOnlyTheTypedAndroidUserId() {
        val command = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ListInstalledPackages(androidUserId = 10),
        )

        assertEquals(
            listOf("/system/bin/su", "-c", "pm list packages -f -U --user 10"),
            command,
        )
    }

    @Test
    fun buildReadPaths_rejectsShellMetacharactersInPackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.ReadPackageApkPaths(
                    packageName = "com.example;id",
                    androidUserId = 0,
                ),
            )
        }
    }

    @Test
    fun buildCopy_quotesApostrophesAndFixesWorkspaceOwnership() {
        val command = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.CopyApkToWorkspace(
                sourcePath = "/data/app/app's/base.apk",
                destinationPath = "/data/user/0/com.luckylca.autocrack/files/base.apk",
                ownerUid = 10234,
                ownerGid = 10234,
            ),
        )

        val shell = command.last()
        assertTrue(shell.contains("'/data/app/app'\"'\"'s/base.apk'"))
        assertTrue(shell.contains("chown 10234:10234"))
        assertTrue(shell.endsWith("chmod 600 '/data/user/0/com.luckylca.autocrack/files/base.apk'"))
    }

    @Test
    fun buildCopy_rejectsNonApkSources() {
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.CopyApkToWorkspace(
                    sourcePath = "/data/app/example/config.json",
                    destinationPath = "/data/user/0/app/files/config.apk",
                    ownerUid = 10000,
                    ownerGid = 10000,
                ),
            )
        }
    }

    @Test
    fun buildProcessList_quotesFilterAndRemainsReadOnly() {
        val command = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ListHostProcesses(
                filter = "com.example'app",
                maxCount = 25,
            ),
        )

        val shell = command.last()
        assertTrue(shell.contains("filter='com.example'\"'\"'app'"))
        assertTrue(shell.contains("/proc/[0-9]*"))
        assertReadOnlyDynamicShell(shell)
    }

    @Test
    fun buildProcessList_rejectsControlCharactersInFilter() {
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.ListHostProcesses(filter = "com.example\nid"),
            )
        }
    }

    @Test
    fun buildProcessInspection_rejectsInvalidPid() {
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.ReadProcessMaps(pid = 0),
            )
        }
    }

    @Test
    fun buildAttachPreflight_explicitlyDoesNotAttachOrChangeState() {
        val command = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ReadProcessAttachPreflight(pid = 1234),
        )

        val shell = command.last()
        assertTrue(shell.contains("attach_attempted=false"))
        assertTrue(shell.contains("state_changed=false"))
        assertTrue(shell.contains("/proc/1234/status"))
        assertTrue(shell.contains("ptrace_scope"))
        assertReadOnlyDynamicShell(shell)
    }

    @Test
    fun buildThreadAndFdCommands_applyBoundedLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.ListProcessThreads(pid = 123, maxCount = 0),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootToolCommandFactory.build(
                suPath = "/system/bin/su",
                command = RootToolCommand.ListProcessFileDescriptors(
                    pid = 123,
                    maxCount = 10_000,
                ),
            )
        }
    }

    private fun assertReadOnlyDynamicShell(shell: String) {
        val normalized = shell.lowercase()
        assertFalse(normalized.contains("ptrace("))
        assertFalse(normalized.contains(" ptrace "))
        assertFalse(normalized.contains("gdbserver"))
        assertFalse(normalized.contains("lldb-server"))
        assertFalse(normalized.contains("kill "))
        assertFalse(normalized.contains("/proc/") && normalized.contains(" > /proc/"))
    }
}
