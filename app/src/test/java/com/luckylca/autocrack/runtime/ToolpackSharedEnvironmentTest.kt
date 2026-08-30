package com.luckylca.autocrack.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackSharedEnvironmentTest {
    @Test
    fun loadsOnlyActiveVersionAndAllSharedLanguageDirectories() {
        val fixture = Fixture.create()
        fixture.addVersion("sample", "1.0.0", withLanguages = true)
        fixture.addVersion("sample", "1.0.0.backup-123", withLanguages = true)
        fixture.activate("sample", "1.0.0")

        val environment = fixture.readEnvironment()

        assertTrue(environment.contains("active/sample/python"))
        assertTrue(environment.contains("active/sample/node_modules"))
        assertTrue(environment.contains("active/sample/lib/node_modules"))
        assertTrue(environment.contains("active/sample/java"))
        assertTrue(environment.contains("active/sample/lib/java"))
        assertFalse(environment.contains("backup-123"))
        assertFalse(environment.contains("LD_LIBRARY_PATH"))
    }

    @Test
    fun upgradeReplacesPythonPathAndUninstallRemovesIt() {
        val fixture = Fixture.create()
        fixture.addVersion("sample", "1.0.0", withLanguages = true)
        fixture.addVersion("sample", "2.0.0", withLanguages = true)
        fixture.activate("sample", "1.0.0")
        assertTrue(fixture.resolveActivePython().contains("packs/sample/1.0.0/python"))

        fixture.activate("sample", "2.0.0")
        val upgraded = fixture.readEnvironment()
        assertTrue(upgraded.contains("active/sample/python"))
        assertTrue(fixture.resolveActivePython().contains("packs/sample/2.0.0/python"))
        assertFalse(fixture.resolveActivePython().contains("packs/sample/1.0.0/python"))

        Files.delete(fixture.active.resolve("sample"))
        val uninstalled = fixture.readEnvironment()
        assertFalse(uninstalled.contains("packs/sample/1.0.0/python"))
        assertFalse(uninstalled.contains("packs/sample/2.0.0/python"))
    }

    private class Fixture(
        val root: Path,
        val packs: Path,
        val active: Path,
    ) {
        fun addVersion(id: String, version: String, withLanguages: Boolean) {
            val versionRoot = packs.resolve(id).resolve(version)
            Files.createDirectories(versionRoot)
            if (withLanguages) {
                listOf("python", "node_modules", "lib/node_modules", "java", "lib/java")
                    .forEach { relative -> Files.createDirectories(versionRoot.resolve(relative)) }
            }
        }

        fun activate(id: String, version: String) {
            val link = active.resolve(id)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Path.of("..", "packs", id, version))
        }

        fun readEnvironment(): String {
            val script = ToolpackSharedEnvironment.shellBootstrap(active.toString()) + "\n" +
                "env | LC_ALL=C sort"
            val process = ProcessBuilder("/bin/sh", "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            return output
        }

        fun resolveActivePython(): String = active.resolve("sample/python").toRealPath().toString()

        companion object {
            fun create(): Fixture {
                val root = Files.createTempDirectory("autocrack-toolpack-environment-")
                val packs = Files.createDirectories(root.resolve("packs"))
                val active = Files.createDirectories(root.resolve("active"))
                return Fixture(root = root, packs = packs, active = active)
            }
        }
    }
}
