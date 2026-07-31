package com.luckylca.autocrack.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageOutputParserTest {
    @Test
    fun parseInstalledPackages_handlesEqualsCharactersInDataAppPath() {
        val apps = PackageOutputParser.parseInstalledPackages(
            "package:/data/app/~~token==/com.example.demo-random==/base.apk=com.example.demo uid:10234",
        )

        assertEquals(1, apps.size)
        assertEquals("com.example.demo", apps.single().packageName)
        assertEquals(
            "/data/app/~~token==/com.example.demo-random==/base.apk",
            apps.single().primaryApkPath,
        )
        assertEquals(10234, apps.single().uid)
        assertEquals(InstalledAppKind.USER, apps.single().kind)
    }

    @Test
    fun parseInstalledPackages_classifiesReadOnlySystemPaths() {
        val apps = PackageOutputParser.parseInstalledPackages(
            "package:/system_ext/priv-app/Settings/Settings.apk=com.android.settings uid:1000",
        )

        assertEquals(InstalledAppKind.SYSTEM, apps.single().kind)
    }

    @Test
    fun parseInstalledPackages_ignoresMalformedLinesAndSortsPackages() {
        val apps = PackageOutputParser.parseInstalledPackages(
            """
            random output
            package:/data/app/z/base.apk=z.example uid:10002
            package:/data/app/a/base.apk=a.example uid:10001
            package:/data/app/b/base.apk=bad package uid:10003
            """.trimIndent(),
        )

        assertEquals(listOf("a.example", "z.example"), apps.map(InstalledApp::packageName))
    }

    @Test
    fun parseApkSources_returnsBaseFirstAndPreservesSplits() {
        val sources = PackageOutputParser.parseApkSources(
            """
            package:/data/app/example/split_config.arm64_v8a.apk
            package:/data/app/example/base.apk
            package:/data/app/example/split_config.xxhdpi.apk
            """.trimIndent(),
        )

        assertEquals(3, sources.size)
        assertEquals(ApkArtifactKind.BASE, sources.first().kind)
        assertEquals("base.apk", sources.first().fileName)
        assertEquals(2, sources.count { it.kind == ApkArtifactKind.SPLIT })
    }

    @Test
    fun parseApkSources_rejectsNonApkAndRelativePaths() {
        val sources = PackageOutputParser.parseApkSources(
            """
            package:relative/base.apk
            package:/data/app/example/readme.txt
            package:/data/app/example/base.apk
            """.trimIndent(),
        )

        assertEquals(1, sources.size)
        assertEquals("base.apk", sources.single().fileName)
    }

    @Test
    fun packageNameValidation_blocksShellSyntax() {
        assertTrue(PackageOutputParser.isValidPackageName("com.example.valid_2"))
        assertFalse(PackageOutputParser.isValidPackageName("com.example;id"))
        assertFalse(PackageOutputParser.isValidPackageName("com.example app"))
    }
}
