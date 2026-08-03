package com.luckylca.autocrack.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChrootPtyCommandBuilderTest {
    @Test
    fun putsRealJdkBinaryAheadOfDebianJavaSymlink() {
        val command = ChrootPtyCommandBuilder.build(
            "/data/data/com.luckylca.autocrack/files/runtime/rootfs/current",
        )

        assertTrue(
            command.contains(
                "JAVA_HOME='/usr/lib/jvm/java-17-openjdk-arm64'",
            ),
        )
        assertTrue(
            command.contains(
                "PATH='/usr/lib/jvm/java-17-openjdk-arm64/bin:" +
                    "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'",
            ),
        )
        assertFalse(command.contains("LD_LIBRARY_PATH="))
    }

    @Test
    fun keepsInteractiveShellAndWorkspaceContract() {
        val command = ChrootPtyCommandBuilder.build("/data/local/rootfs")

        assertTrue(command.contains("/usr/bin/script -q -e -f -c"))
        assertTrue(command.contains("exec /bin/bash --noprofile --norc -i"))
        assertTrue(command.contains("AUTOC_WORKSPACE='/workspace'"))
    }
}
