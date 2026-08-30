package com.luckylca.autocrack.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InstalledToolpackRecordTest {
    @Test
    fun legacyRecordCanBeReadForUpgradeOrUninstallButNotExposedAsTrusted() {
        val manifest = JSONObject()
            .put("schemaVersion", 1)
            .put("id", "legacy-toolpack")
            .put("title", "Legacy toolpack")
            .put("version", "legacy-1.0.0")
            .put("architecture", "all")
            .put("payloadEntry", "payload.zip")
            .put("payloadSha256", "a".repeat(64))
            .put("payloadSizeBytes", 123)
            .put("requiredPaths", listOf("bin/legacy"))
            .put(
                "commands",
                listOf(JSONObject().put("name", "legacy").put("relativePath", "bin/legacy")),
            )
            .put(
                "selfTests",
                listOf(
                    JSONObject()
                        .put("id", "legacy-self-test")
                        .put("title", "Legacy self-test")
                        .put("command", "legacy --version")
                        .put("expectedExitCodes", listOf(0))
                        .put("outputContains", listOf("legacy")),
                ),
            )
            .put(
                "sources",
                listOf(
                    JSONObject()
                        .put("name", "legacy-source")
                        .put("version", "1.0.0")
                        .put("url", "https://example.invalid/legacy.zip")
                        .put("sha256", "b".repeat(64)),
                ),
            )
        val record = JSONObject()
            .put("schemaVersion", 1)
            .put("manifest", manifest)
            .put("packagePath", "/packages/legacy.zip")
            .put("installedPath", "/packs/legacy-toolpack/legacy-1.0.0")
            .put("rootfsVersion", "bookworm")
            .put("installedAtEpochMillis", 1234L)
            .toString()

        val installed = parseInstalledToolpackRecord(record, requireTrusted = false)
        assertEquals("legacy-toolpack", installed.manifest.id)

        try {
            parseInstalledToolpackRecord(record, requireTrusted = true)
            fail("An untrusted legacy record must not be exposed as installed")
        } catch (_: IllegalStateException) {
            // Only upgrade and uninstall paths may read obsolete records.
        }
    }
}
