package com.luckylca.autocrack.root

import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicReadOnlyExitStatusRegressionTest {
    @Test
    fun threadEnumeration_explicitlyNormalizesSuccessfulExitStatus() {
        val shell = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ListProcessThreads(pid = 1234, maxCount = 32),
        )[2]

        assertTrue(shell.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun fileDescriptorEnumeration_explicitlyNormalizesSuccessfulExitStatus() {
        val shell = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ListProcessFileDescriptors(pid = 1234, maxCount = 32),
        )[2]

        assertTrue(shell.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun legacyProcessEnumeration_explicitlyNormalizesSuccessfulExitStatus() {
        val shell = RootToolCommandFactory.build(
            suPath = "/system/bin/su",
            command = RootToolCommand.ListHostProcesses(filter = "", maxCount = 32),
        )[2]

        assertTrue(shell.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun optimizedUnfilteredProcessEnumeration_explicitlyNormalizesSuccessfulExitStatus() {
        val shell = DynamicHostProcessCommandFactory.build(
            suPath = "/system/bin/su",
            filter = "",
            maxCount = 32,
        )[2]

        assertTrue(shell.trimEnd().endsWith("exit 0"))
    }
}
