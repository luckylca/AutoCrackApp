package com.luckylca.autocrack.runtime

import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidLldbToolpackTrustPolicyTest {
    @Test
    fun acceptsPinnedAndroidLldbServerToolpack() {
        BuiltInToolpackTrustPolicy.requireTrusted(trustedManifest())
    }

    @Test
    fun rejectsLldbServerPayloadSubstitution() {
        val manifest = trustedManifest().copy(
            payloadSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    @Test
    fun rejectsAndroidLldbServerSourceSubstitution() {
        assertSourceSubstitutionRejected("lldb-server")
    }

    @Test
    fun rejectsDebianLldbClientSourceSubstitution() {
        assertSourceSubstitutionRejected("debian-lldb-14-arm64")
    }

    @Test
    fun rejectsBundledSixSourceSubstitution() {
        assertSourceSubstitutionRejected("debian-python3-six")
    }

    private fun assertSourceSubstitutionRejected(sourceName: String) {
        val trusted = trustedManifest()
        val manifest = trusted.copy(
            sources = trusted.sources.map { source ->
                if (source.name == sourceName) {
                    source.copy(
                        sha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    )
                } else {
                    source
                }
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            BuiltInToolpackTrustPolicy.requireTrusted(manifest)
        }
    }

    private fun trustedManifest(): ToolpackPackageManifest = ToolpackPackageManifest(
        schemaVersion = 1,
        id = "android-lldb-server",
        title = "Standard LLDB client and Android server",
        version = "android-llvm-r522817_lldb-14_autocrack-2.0.0",
        architecture = "arm64",
        payloadEntry = "payload.zip",
        payloadSha256 = "7e2330f33fe458fce5888c1cd65d604b9e0ff4af7c3e17453c1ec40f169cfdd4",
        payloadSizeBytes = 283_870_902L,
        requiredPaths = listOf(
            "bin/lldb",
            "bin/android-lldb-server",
            "host-bin/lldb-server-android",
            "lib/llvm-14/bin/lldb",
            "lib/llvm-14/lib/python3.11/dist-packages/six.py",
        ),
        commands = listOf(
            ToolpackCommand("lldb", "bin/lldb"),
            ToolpackCommand("android-lldb-server", "bin/android-lldb-server"),
        ),
        selfTests = listOf(
            ToolpackSelfTest(
                id = "lldb-server-android-binary",
                title = "Android LLDB server payload",
                command = "test -x /opt/autocrack/toolpacks/active/android-lldb-server/host-bin/lldb-server-android && printf 'AUTOCRACK_LLDB_ANDROID_BINARY_OK\n'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_LLDB_ANDROID_BINARY_OK"),
            ),
            ToolpackSelfTest(
                id = "lldb-client-version",
                title = "Standard Debian LLDB client",
                command = "lldb --version",
                expectedExitCodes = setOf(0),
                outputContains = listOf("lldb version 14.0.6"),
            ),
            ToolpackSelfTest(
                id = "lldb-python-runtime",
                title = "LLDB Python runtime",
                command = "lldb --batch -o 'script import lldb, six; print(\"AUTOCRACK_LLDB_PYTHON_OK\", six.__version__)'",
                expectedExitCodes = setOf(0),
                outputContains = listOf("AUTOCRACK_LLDB_PYTHON_OK 1.16.0"),
            ),
        ),
        sources = listOf(
            ToolpackSourceArtifact(
                name = "lldb-server",
                version = "android-llvm-r522817-autocrack-seize-runtime-stop",
                url = "https://android.googlesource.com/toolchain/llvm-project/+/d8003a456d14a3deb8054cdaa529ffbf02d9b262",
                sha256 = "71d9ed6a90776d7dbdbcb315ea2171a763c071e5a370ec1b8b0c28157af41b20",
            ),
            ToolpackSourceArtifact(
                name = "debian-lldb-14-arm64",
                version = "1:14.0.6-12",
                url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/lldb-14_14.0.6-12_arm64.deb",
                sha256 = "b05d6bc6ba4ee60746fa1cc2af0c763a79c61cce4c3c6471521dabff8c088551",
            ),
            ToolpackSourceArtifact(
                name = "debian-python3-lldb-14-arm64",
                version = "1:14.0.6-12",
                url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/python3-lldb-14_14.0.6-12_arm64.deb",
                sha256 = "1b0c76c86c52568513f07dcf9412ac038ef7b88a4755c2b1aa667a8b02f4377a",
            ),
            ToolpackSourceArtifact(
                name = "debian-liblldb-14-arm64",
                version = "1:14.0.6-12",
                url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/liblldb-14_14.0.6-12_arm64.deb",
                sha256 = "acdaa8e8c06b7ee643aec4326b96b45d76d26e3112bd08cadce0c7a1f54de813",
            ),
            ToolpackSourceArtifact(
                name = "debian-libclang-cpp14-arm64",
                version = "1:14.0.6-12",
                url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libclang-cpp14_14.0.6-12_arm64.deb",
                sha256 = "dc983fc6aa0c1f7ef3f51aa3a2734ea6285ad5e7a283fe32d3239c85f718872d",
            ),
            ToolpackSourceArtifact(
                name = "debian-libllvm14-arm64",
                version = "1:14.0.6-12",
                url = "https://deb.debian.org/debian/pool/main/l/llvm-toolchain-14/libllvm14_14.0.6-12_arm64.deb",
                sha256 = "f22c3e843b12de66d642dceddc1db0de02934a4028dd60aecc4722f8bf04e6d6",
            ),
            ToolpackSourceArtifact(
                name = "debian-python3-six",
                version = "1.16.0-4",
                url = "https://deb.debian.org/debian/pool/main/s/six/python3-six_1.16.0-4_all.deb",
                sha256 = "fd189e9cecbcf17a1fc20aec30055c8afa9c1eec00cd6e7ab385087a2ab3b0d3",
            ),
        ),
    )
}
