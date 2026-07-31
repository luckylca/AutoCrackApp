package com.luckylca.autocrack.apk

object PackageOutputParser {
    private val packageNameRegex = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val safeFileCharacterRegex = Regex("[^A-Za-z0-9._-]")
    private val systemPathPrefixes = listOf(
        "/system/",
        "/system_ext/",
        "/product/",
        "/vendor/",
        "/odm/",
        "/oem/",
        "/apex/",
    )

    fun isValidPackageName(packageName: String): Boolean =
        packageName.length in 1..255 && packageNameRegex.matches(packageName)

    fun requireValidPackageName(packageName: String) {
        require(isValidPackageName(packageName)) { "Invalid Android package name" }
    }

    fun parseInstalledPackages(output: String): List<InstalledApp> = output
        .lineSequence()
        .mapNotNull(::parseInstalledPackageLine)
        .distinctBy(InstalledApp::packageName)
        .sortedBy(InstalledApp::packageName)
        .toList()

    fun parseApkSources(output: String): List<ApkSource> {
        val paths = output
            .lineSequence()
            .map(String::trim)
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .map { it.removePrefix(PACKAGE_PREFIX).trim() }
            .filter(::isSafeApkSourcePath)
            .distinct()
            .toList()

        if (paths.isEmpty()) return emptyList()

        val baseIndex = paths.indexOfFirst { path ->
            path.substringAfterLast('/').equals("base.apk", ignoreCase = true)
        }.takeIf { it >= 0 } ?: 0

        val usedFileNames = mutableSetOf<String>()
        return paths.mapIndexed { index, path ->
            val kind = if (index == baseIndex) ApkArtifactKind.BASE else ApkArtifactKind.SPLIT
            val rawName = path.substringAfterLast('/').ifBlank {
                if (kind == ApkArtifactKind.BASE) "base.apk" else "split-$index.apk"
            }
            val sanitizedName = sanitizeApkFileName(rawName, kind, index)
            val uniqueName = makeUniqueFileName(sanitizedName, usedFileNames)

            ApkSource(
                sourcePath = path,
                fileName = uniqueName,
                kind = kind,
            )
        }.sortedWith(
            compareBy<ApkSource> { it.kind != ApkArtifactKind.BASE }
                .thenBy(ApkSource::fileName),
        )
    }

    private fun parseInstalledPackageLine(rawLine: String): InstalledApp? {
        val line = rawLine.trim()
        if (!line.startsWith(PACKAGE_PREFIX)) return null

        val payload = line.removePrefix(PACKAGE_PREFIX)
        val uidMarkerIndex = payload.lastIndexOf(UID_MARKER)
        val packageAndPath = if (uidMarkerIndex >= 0) {
            payload.substring(0, uidMarkerIndex).trim()
        } else {
            payload.trim()
        }
        val uid = if (uidMarkerIndex >= 0) {
            payload.substring(uidMarkerIndex + UID_MARKER.length).trim().toIntOrNull()
        } else {
            null
        }

        // APK installation directories can contain '=' characters, so split at the last '='.
        val separatorIndex = packageAndPath.lastIndexOf('=')
        val apkPath: String?
        val packageName: String
        if (separatorIndex > 0) {
            apkPath = packageAndPath.substring(0, separatorIndex).trim().ifBlank { null }
            packageName = packageAndPath.substring(separatorIndex + 1).trim()
        } else {
            apkPath = null
            packageName = packageAndPath
        }

        if (!isValidPackageName(packageName)) return null

        return InstalledApp(
            packageName = packageName,
            primaryApkPath = apkPath,
            uid = uid,
            kind = if (apkPath != null && systemPathPrefixes.any(apkPath::startsWith)) {
                InstalledAppKind.SYSTEM
            } else {
                InstalledAppKind.USER
            },
        )
    }

    private fun isSafeApkSourcePath(path: String): Boolean =
        path.startsWith('/') &&
            path.endsWith(".apk", ignoreCase = true) &&
            '\u0000' !in path &&
            '\n' !in path &&
            '\r' !in path

    private fun sanitizeApkFileName(
        rawName: String,
        kind: ApkArtifactKind,
        index: Int,
    ): String {
        val sanitized = rawName.replace(safeFileCharacterRegex, "_")
        val fallback = if (kind == ApkArtifactKind.BASE) "base.apk" else "split-$index.apk"
        val candidate = sanitized.ifBlank { fallback }
        return if (candidate.endsWith(".apk", ignoreCase = true)) candidate else "$candidate.apk"
    }

    private fun makeUniqueFileName(
        requestedName: String,
        usedNames: MutableSet<String>,
    ): String {
        if (usedNames.add(requestedName)) return requestedName

        val extensionIndex = requestedName.lastIndexOf('.')
        val stem = if (extensionIndex > 0) requestedName.substring(0, extensionIndex) else requestedName
        val extension = if (extensionIndex > 0) requestedName.substring(extensionIndex) else ""
        var suffix = 2
        while (true) {
            val candidate = "$stem-$suffix$extension"
            if (usedNames.add(candidate)) return candidate
            suffix += 1
        }
    }

    private const val PACKAGE_PREFIX = "package:"
    private const val UID_MARKER = " uid:"
}
