package com.luckylca.autocrack.root

import org.junit.Assert.assertEquals
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
}
