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
    fun dangerousAndroidPackageMutationsRequireApproval() {
        assertEquals(
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("pm uninstall com.example.target"),
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
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("pm install /data/local/tmp/app.apk"),
        )
        assertEquals(
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("pm disable-user com.example.target"),
        )
        assertEquals(
            DangerousOperationCategory.PACKAGE_DATA_CHANGE,
            MobileAgentDangerousCommandClassifier.classify("cmd package grant com.example.target android.permission.CAMERA"),
        )
    }

    @Test
    fun AndroidSettingsWritesUseSystemWriteApproval() {
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("settings put global example 1"),
        )
        assertEquals(
            DangerousOperationCategory.SYSTEM_WRITE,
            MobileAgentDangerousCommandClassifier.classify("setprop persist.example 1"),
        )
        assertNull(MobileAgentDangerousCommandClassifier.classify("settings get global airplane_mode_on"))
    }
}
