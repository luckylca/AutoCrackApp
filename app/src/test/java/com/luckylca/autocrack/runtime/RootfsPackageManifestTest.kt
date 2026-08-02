package com.luckylca.autocrack.runtime

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsPackageManifestTest {
    @Test
    fun manifestAcceptsPinnedArm64Archive() {
        val manifest = RootfsPackageManifest(
            schemaVersion = 1,
            id = "autocrack-debian-bookworm-arm64",
            version = "bookworm-test",
            architecture = "arm64",
            archiveEntry = "rootfs.tar.xz",
            archiveSha256 = "a".repeat(64),
            archiveSizeBytes = 1234L,
            compression = "xz",
            sourceImage = "debian:bookworm-slim",
            requiredPaths = listOf("/bin/bash", "/usr/bin/env"),
        )

        assertEquals("arm64", manifest.architecture)
        assertEquals("rootfs.tar.xz", manifest.archiveEntry)
    }

    @Test
    fun manifestRejectsWrongArchitecture() {
        assertThrows(IllegalArgumentException::class.java) {
            RootfsPackageManifest(
                schemaVersion = 1,
                id = "wrong-arch",
                version = "test",
                architecture = "amd64",
                archiveEntry = "rootfs.tar.xz",
                archiveSha256 = "b".repeat(64),
                archiveSizeBytes = 1234L,
                compression = "xz",
                sourceImage = null,
                requiredPaths = listOf("/bin/bash"),
            )
        }
    }

    @Test
    fun pathPolicyRejectsTraversalAndAbsolutePaths() {
        val root = Files.createTempDirectory("rootfs-policy-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPathPolicy.resolveEntry(root, "../escape")
            }
            assertThrows(IllegalArgumentException::class.java) {
                RootfsPathPolicy.resolveEntry(root, "/etc/passwd")
            }
            val valid = RootfsPathPolicy.resolveEntry(root, "usr/bin/bash")
            assertTrue(valid.path.startsWith(root.canonicalPath))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hardLinkPolicyMaterializesPlatformRestrictionFailures() {
        assertTrue(RootfsHardLinkPolicy.shouldMaterialize(13)) // EACCES
        assertTrue(RootfsHardLinkPolicy.shouldMaterialize(1)) // EPERM
        assertTrue(RootfsHardLinkPolicy.shouldMaterialize(18)) // EXDEV
        assertFalse(RootfsHardLinkPolicy.shouldMaterialize(2)) // ENOENT
    }
}
