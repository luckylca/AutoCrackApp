package com.luckylca.autocrack.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackRequirementEvaluatorTest {
    @Test
    fun schemaV1WithoutRequiresIsCompatible() {
        val report = ToolpackRequirementEvaluator.evaluate(
            manifest = manifest(schemaVersion = 1, requires = ToolpackRequirements()),
            availableCommands = emptySet(),
        )

        assertTrue(report.compatible)
        assertTrue(report.diagnostics.isEmpty())
    }

    @Test
    fun evaluatesRuntimeCapabilitiesCommandsAndOptionalWarnings() {
        val requires = ToolpackRequirements(
            runtime = ">=1.0.0",
            capabilities = listOf("runtime.process", "missing.required"),
            commands = listOf("android-shell"),
            optionalCapabilities = listOf("missing.optional"),
        )
        val report = ToolpackRequirementEvaluator.evaluate(
            manifest = manifest(requires = requires),
            availableCommands = emptySet(),
        )

        assertFalse(report.compatible)
        assertEquals(listOf("missing.required"), report.missingCapabilities)
        assertEquals(listOf("android-shell"), report.missingCommands)
        assertEquals(listOf("missing.optional"), report.missingOptionalCapabilities)
        assertTrue(report.warnings.single().contains("missing.optional"))
    }

    @Test
    fun optionalCapabilitiesDoNotMakeToolpackIncompatible() {
        val report = ToolpackRequirementEvaluator.evaluate(
            manifest = manifest(
                requires = ToolpackRequirements(
                    runtime = ">=1.0.0",
                    optionalCapabilities = listOf("future.optional"),
                ),
            ),
            availableCommands = emptySet(),
        )

        assertTrue(report.compatible)
        assertEquals(listOf("future.optional"), report.missingOptionalCapabilities)
    }

    @Test
    fun ownCommandDoesNotNeedToPreexist() {
        val report = ToolpackRequirementEvaluator.evaluate(
            manifest = manifest(
                commands = listOf(ToolpackCommand("sample", "bin/sample")),
                requires = ToolpackRequirements(commands = listOf("sample")),
            ),
            availableCommands = emptySet(),
        )

        assertTrue(report.compatible)
        assertTrue(report.missingCommands.isEmpty())
    }

    @Test
    fun runtimeConstraintRejectsOlderContract() {
        val report = ToolpackRequirementEvaluator.evaluate(
            manifest = manifest(requires = ToolpackRequirements(runtime = ">=1.1.0")),
            availableCommands = emptySet(),
            runtimeVersion = "1.0.0",
        )

        assertFalse(report.compatible)
        assertTrue(report.diagnostics.single().contains(">=1.1.0"))
    }

    @Test
    fun semverOperatorsAreBoundedAndDeterministic() {
        assertTrue(SemVerConstraint.matches("1.2.3", ">=1.2.0"))
        assertTrue(SemVerConstraint.matches("1.2.3", "<2.0"))
        assertTrue(SemVerConstraint.matches("1.2.3", "1.2.3"))
        assertFalse(SemVerConstraint.matches("1.2.3", ">1.2.3"))
        assertFalse(SemVerConstraint.matches("1.2.3", "latest"))
    }

    @Test
    fun hostRuntimeContractMatchesRuntimeDispatcherSource() {
        val source = findRuntimeDispatcher()
        val text = source.readText(Charsets.UTF_8)
        val version = Regex("""public static final String VERSION = "([^"]+)";""")
            .find(text)
            ?.groupValues
            ?.get(1)
        val listBody = text.substringAfter("for (String capability : List.of(")
            .substringBefore(")) supported.put(capability);")
        val capabilities = Regex(""""([^"]+)"""")
            .findAll(listBody)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(ToolpackRuntimeContract.VERSION, version)
        assertEquals(ToolpackRuntimeContract.CAPABILITIES, capabilities)
    }

    private fun manifest(
        schemaVersion: Int = 2,
        commands: List<ToolpackCommand> = listOf(ToolpackCommand("sample", "bin/sample")),
        requires: ToolpackRequirements = ToolpackRequirements(),
    ): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = schemaVersion,
        id = "sample",
        title = "Sample",
        version = "1.0.0",
        architecture = "all",
        payloadEntry = ToolpackPackageManifest.PAYLOAD_ENTRY,
        payloadSha256 = "a".repeat(64),
        payloadSizeBytes = 1,
        requiredPaths = listOf("bin/sample"),
        commands = commands,
        selfTests = listOf(
            ToolpackSelfTest(
                id = "help",
                title = "help",
                command = "sample --help",
                expectedExitCodes = setOf(0),
                outputContains = emptyList(),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "sample-source",
                version = "1.0.0",
                url = "https://example.com/sample",
                sha256 = "b".repeat(64),
            ),
        ),
        requires = requires,
    )

    private fun findRuntimeDispatcher(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(8) {
            val candidate = File(
                current,
                "autocrack-runtime/src/main/java/com/luckylca/autocrack/runtime/shared/RuntimeDispatcher.java",
            )
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("RuntimeDispatcher.java not found from ${System.getProperty("user.dir")}")
    }
}
