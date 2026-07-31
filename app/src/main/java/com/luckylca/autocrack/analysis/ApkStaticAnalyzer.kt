package com.luckylca.autocrack.analysis

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractionReport
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkStaticAnalyzer(
    context: Context,
    private val archiveInspector: ApkArchiveInspector = ApkArchiveInspector(),
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    suspend fun analyze(extraction: ExtractionReport): StaticAnalysisReport = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val workspace = validateWorkspace(extraction)
        val baseApk = extraction.artifacts.firstOrNull { it.kind == ApkArtifactKind.BASE }
            ?: throw StaticAnalysisException("提取结果中缺少 Base APK")

        val packageInfo = readPackageArchiveInfo(baseApk.localPath)
        val manifest = manifestSummary(packageInfo)
        val signing = signingSummary(packageInfo)
        val permissions = permissionSummary(packageInfo)
        val components = componentSummary(packageInfo)
        val archives = extraction.artifacts.map(archiveInspector::inspect)
        val reportFile = File(workspace, REPORT_FILE_NAME).canonicalFile
        ensureInsideWorkspace(workspace, reportFile)

        val report = StaticAnalysisReport(
            packageName = extraction.packageName,
            baseApkFileName = baseApk.fileName,
            manifest = manifest,
            signing = signing,
            permissions = permissions,
            components = components,
            archives = archives,
            reportFilePath = reportFile.path,
            startedAtEpochMillis = startedAt,
            completedAtEpochMillis = System.currentTimeMillis(),
        )

        StaticAnalysisReportWriter.write(report, reportFile)
        if (!reportFile.isFile || reportFile.length() <= 0L) {
            throw StaticAnalysisException("静态分析报告写入失败")
        }
        report
    }

    private fun validateWorkspace(extraction: ExtractionReport): File {
        val workspace = File(extraction.workspacePath).canonicalFile
        if (!workspace.isDirectory) {
            throw StaticAnalysisException("提取工作目录不存在：${extraction.workspacePath}")
        }
        extraction.artifacts.forEach { artifact ->
            val file = File(artifact.localPath).canonicalFile
            ensureInsideWorkspace(workspace, file)
            if (!file.isFile || file.length() <= 0L) {
                throw StaticAnalysisException("提取文件不存在或为空：${artifact.fileName}")
            }
            if (file.length() != artifact.sizeBytes) {
                throw StaticAnalysisException("提取文件大小发生变化：${artifact.fileName}")
            }
        }
        return workspace
    }

    private fun ensureInsideWorkspace(workspace: File, candidate: File) {
        val workspacePrefix = workspace.canonicalFile.path + File.separator
        if (!candidate.canonicalFile.path.startsWith(workspacePrefix)) {
            throw StaticAnalysisException("检测到工作目录路径越界：${candidate.path}")
        }
    }

    private fun readPackageArchiveInfo(apkPath: String): PackageInfo? {
        val baseFlags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_META_DATA
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            baseFlags or PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            baseFlags or PackageManager.GET_SIGNATURES
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apkPath, flags)
        }
    }

    private fun manifestSummary(packageInfo: PackageInfo?): ManifestSummary {
        if (packageInfo == null) {
            return ManifestSummary(
                parsed = false,
                diagnostic = "Android PackageManager 无法解析 Base APK Manifest",
                manifestPackageName = null,
                versionName = null,
                versionCode = null,
                minSdk = null,
                targetSdk = null,
                compileSdk = null,
                debuggable = null,
                allowBackup = null,
                usesCleartextTraffic = null,
                extractNativeLibs = null,
            )
        }

        val applicationInfo = packageInfo.applicationInfo
        return ManifestSummary(
            parsed = true,
            diagnostic = null,
            manifestPackageName = packageInfo.packageName,
            versionName = packageInfo.versionName,
            versionCode = packageVersionCode(packageInfo),
            minSdk = applicationInfo?.minSdkVersion,
            targetSdk = applicationInfo?.targetSdkVersion,
            compileSdk = compileSdk(applicationInfo),
            debuggable = applicationInfo?.hasFlag(ApplicationInfo.FLAG_DEBUGGABLE),
            allowBackup = applicationInfo?.hasFlag(ApplicationInfo.FLAG_ALLOW_BACKUP),
            usesCleartextTraffic = applicationInfo?.hasFlag(ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC),
            extractNativeLibs = applicationInfo?.hasFlag(ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS),
        )
    }

    private fun packageVersionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun compileSdk(applicationInfo: ApplicationInfo?): Int? {
        if (applicationInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return applicationInfo.compileSdkVersion.takeIf { it > 0 }
    }

    private fun ApplicationInfo.hasFlag(flag: Int): Boolean = flags and flag != 0

    private fun signingSummary(packageInfo: PackageInfo?): SigningSummary {
        if (packageInfo == null) {
            return SigningSummary(
                currentSignerSha256 = emptyList(),
                signingHistorySha256 = emptyList(),
                diagnostic = "Manifest 未解析，无法读取签名证书",
            )
        }

        val current: List<Signature>
        val history: List<Signature>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
            if (signingInfo == null) {
                return SigningSummary(emptyList(), emptyList(), "PackageManager 未返回 SigningInfo")
            }
            current = signingInfo.apkContentsSigners?.toList().orEmpty()
            history = signingInfo.signingCertificateHistory?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val legacy = packageInfo.signatures?.toList().orEmpty()
            current = legacy
            history = legacy
        }

        val currentFingerprints = current.map(::signatureSha256).distinct().sorted()
        val historyFingerprints = history.map(::signatureSha256).distinct().sorted()
        return SigningSummary(
            currentSignerSha256 = currentFingerprints,
            signingHistorySha256 = historyFingerprints,
            diagnostic = if (currentFingerprints.isEmpty()) "没有读取到 APK 签名证书" else null,
        )
    }

    private fun signatureSha256(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString(separator = ":") { byte -> "%02X".format(byte) }
    }

    private fun permissionSummary(packageInfo: PackageInfo?): PermissionSummary = PermissionSummary(
        requested = packageInfo?.requestedPermissions?.toList().orEmpty().distinct().sorted(),
        declared = packageInfo?.permissions
            ?.mapNotNull { permission -> permission.name }
            .orEmpty()
            .distinct()
            .sorted(),
    )

    private fun componentSummary(packageInfo: PackageInfo?): ComponentSummary = ComponentSummary(
        activities = componentKindSummary(packageInfo?.activities?.toList().orEmpty()),
        services = componentKindSummary(packageInfo?.services?.toList().orEmpty()),
        receivers = componentKindSummary(packageInfo?.receivers?.toList().orEmpty()),
        providers = componentKindSummary(packageInfo?.providers?.toList().orEmpty()),
    )

    private fun componentKindSummary(components: List<ComponentInfo>): ComponentKindSummary {
        val names = components.mapNotNull(ComponentInfo::name).distinct().sorted()
        val exportedNames = components
            .filter(ComponentInfo::exported)
            .mapNotNull(ComponentInfo::name)
            .distinct()
            .sorted()
        return ComponentKindSummary(
            total = components.size,
            exported = components.count(ComponentInfo::exported),
            names = names,
            exportedNames = exportedNames,
        )
    }

    private companion object {
        const val REPORT_FILE_NAME = "analysis-report.json"
    }
}
