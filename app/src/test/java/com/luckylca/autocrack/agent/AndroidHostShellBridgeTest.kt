package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidHostShellBridgeTest {
    @Test
    fun policyCommandPreservesSimpleAndroidArgv() {
        assertEquals(
            "pm list packages com.ss.android.ugc.aweme",
            AndroidHostShellBridge.commandForPolicy(
                listOf("pm", "list", "packages", "com.ss.android.ugc.aweme"),
            ),
        )
    }

    @Test
    fun policyCommandUnwrapsAndroidShellDashC() {
        assertEquals(
            "pm list packages | grep aweme",
            AndroidHostShellBridge.commandForPolicy(
                listOf("sh", "-c", "pm list packages | grep aweme"),
            ),
        )
    }

    @Test
    fun observationAndDebuggingCommandsAreNotCapabilityGated() {
        assertNull(MobileAgentDangerousCommandClassifier.classify("frida -H 127.0.0.1:27042 -l /workspace/test.js Settings"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("lldb -o 'gdb-remote 127.0.0.1:12345'"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("tcpdump -i any 'tcp port 443 or udp port 443'"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("pm list packages"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("settings get global airplane_mode_on"))
    }

    @Test
    fun runtimeToolpackMutationsAreGatedButInspectionRemainsReadOnly() {
        assertNull(MobileAgentDangerousCommandClassifier.classify("runtime-inspect process --package com.example --json"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("ui-inspect tree --package com.example --json"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("runtime-control secure-status --package com.example --json"))
        assertNull(MobileAgentDangerousCommandClassifier.classify("simplehook rules list --json"))

        listOf(
            "runtime-control process-kill --package com.example --json",
            "runtime-control webview-eval --package com.example obj_1 'window.x=1' --json",
            "runtime-control so-inject --package com.example /data/local/tmp/libx.so --json",
            "ui-inspect action --package com.example obj_1 --action-json '{\"type\":\"perform_click\"}' --json",
            "simplehook reload --json",
            "simplehook rules add /workspace/rule.json --json",
        ).forEach { command ->
            assertEquals(
                DangerousOperationCategory.TARGET_RUNTIME_MUTATION,
                MobileAgentDangerousCommandClassifier.classify(command),
            )
        }
    }

    @Test
    fun onlyDeviceDestructiveShellOperationsRemainGated() {
        assertEquals(
            DangerousOperationCategory.DESTRUCTIVE_DELETE,
            MobileAgentDangerousCommandClassifier.classify("rm -rf /system/example"),
        )
        assertEquals(
            DangerousOperationCategory.DESTRUCTIVE_DELETE,
            MobileAgentDangerousCommandClassifier.classify("rm -r -f -- /data"),
        )
        assertEquals(
            DangerousOperationCategory.DESTRUCTIVE_DELETE,
            MobileAgentDangerousCommandClassifier.classify("rm --recursive --force '/vendor/lib'"),
        )
        assertEquals(
            DangerousOperationCategory.BLOCK_DEVICE_WRITE,
            MobileAgentDangerousCommandClassifier.classify("dd if=/tmp/image of=/dev/block/by-name/system"),
        )
        assertEquals(
            DangerousOperationCategory.BLOCK_DEVICE_WRITE,
            MobileAgentDangerousCommandClassifier.classify("wipefs -a /dev/block/by-name/vendor"),
        )
        assertEquals(
            DangerousOperationCategory.DEVICE_CONTROL,
            MobileAgentDangerousCommandClassifier.classify("reboot"),
        )
        assertEquals(
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("pm clear com.example.target"),
        )
        assertEquals(
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("cmd package uninstall com.example.target"),
        )
        assertEquals(
            DangerousOperationCategory.MOUNT_CONTROL,
            MobileAgentDangerousCommandClassifier.classify("mount -o remount,rw /system"),
        )
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("settings put global example 1"),
        )
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("chmod 0755 /system/bin/example"),
        )
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("echo enabled > /sys/devices/example"),
        )
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("rm /vendor/etc/example.conf"),
        )
    }
}
