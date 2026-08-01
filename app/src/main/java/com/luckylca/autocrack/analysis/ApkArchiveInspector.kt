package com.luckylca.autocrack.analysis

import com.luckylca.autocrack.apk.ApkArtifactKind
import com.luckylca.autocrack.apk.ExtractedApk
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

class ApkArchiveInspector {
    fun inspect(artifact: ExtractedApk): ApkArchiveSummary {
        val apkFile = File(artifact.localPath)
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw StaticAnalysisException("APK 文件不存在或为空：${artifact.fileName}")
        }

        try {
            ZipFile(apkFile).use { zipFile ->
                var entryCount = 0
                var uncompressedBytes = 0L
                var compressedBytes = 0L
                var manifestPresent = false
                var resourcesArscPresent = false
                var resourceEntries = 0
                var assetEntries = 0
                var metaInfEntries = 0
                var signingEntries = 0
                var nestedApkEntries = 0

                val dexFiles = mutableListOf<DexFileSummary>()
                val nativeLibraries = mutableListOf<NativeLibrarySummary>()
                val warnings = mutableListOf<String>()
                val seenEntryNames = mutableSetOf<String>()

                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue

                    entryCount += 1
                    val size = normalizedSize(entry.size)
                    val compressedSize = normalizedSize(entry.compressedSize)
                    uncompressedBytes += size
                    compressedBytes += compressedSize

                    val name = entry.name
                    if (!seenEntryNames.add(name)) {
                        warnings += "ZIP 中存在重复条目：$name"
                    }
                    if (isUnsafeEntryName(name)) {
                        warnings += "ZIP 中存在可疑路径条目：$name"
                    }

                    when (name) {
                        ANDROID_MANIFEST_ENTRY -> manifestPresent = true
                        RESOURCES_ARSC_ENTRY -> resourcesArscPresent = true
                    }
                    if (name.startsWith(RES_PREFIX)) resourceEntries += 1
                    if (name.startsWith(ASSETS_PREFIX)) assetEntries += 1
                    if (name.startsWith(META_INF_PREFIX)) metaInfEntries += 1
                    if (isSigningEntry(name)) signingEntries += 1
                    if (name.endsWith(APK_SUFFIX, ignoreCase = true)) nestedApkEntries += 1

                    if (DEX_ENTRY_REGEX.matches(name)) {
                        val dex = inspectDex(zipFile, entry, size, compressedSize)
                        dexFiles += dex
                        if (!dex.validMagic) {
                            warnings += "DEX 魔数无效：$name"
                        }
                    }

                    val nativeMatch = NATIVE_LIBRARY_REGEX.matchEntire(name)
                    if (nativeMatch != null) {
                        val abi = nativeMatch.groupValues[1]
                        val fileName = nativeMatch.groupValues[2]
                        val nativeLibrary = inspectNativeLibrary(
                            zipFile = zipFile,
                            entry = entry,
                            abi = abi,
                            fileName = fileName,
                            size = size,
                            compressedSize = compressedSize,
                        )
                        nativeLibraries += nativeLibrary
                        nativeLibrary.diagnostic?.let { diagnostic ->
                            warnings += buildString {
                                append("SO 诊断：")
                                append(name)
                                append(" | size=")
                                append(size)
                                append(" B | header=")
                                append(nativeLibrary.headerHex.ifBlank { "<empty>" })
                                append(" | ")
                                append(diagnostic)
                            }
                        }
                    }
                }

                if (artifact.kind == ApkArtifactKind.BASE && !manifestPresent) {
                    warnings += "Base APK 中缺少 AndroidManifest.xml"
                }

                return ApkArchiveSummary(
                    artifactFileName = artifact.fileName,
                    artifactSha256 = artifact.sha256,
                    entryCount = entryCount,
                    uncompressedBytes = uncompressedBytes,
                    compressedBytes = compressedBytes,
                    manifestEntryPresent = manifestPresent,
                    resourcesArscPresent = resourcesArscPresent,
                    resourceEntryCount = resourceEntries,
                    assetEntryCount = assetEntries,
                    metaInfEntryCount = metaInfEntries,
                    signingEntryCount = signingEntries,
                    nestedApkEntryCount = nestedApkEntries,
                    dexFiles = dexFiles.sortedBy(DexFileSummary::entryName),
                    nativeLibraries = nativeLibraries.sortedWith(
                        compareBy(NativeLibrarySummary::abi, NativeLibrarySummary::fileName),
                    ),
                    warnings = warnings.distinct(),
                )
            }
        } catch (exception: ZipException) {
            throw StaticAnalysisException("无法读取 APK ZIP：${artifact.fileName}，${exception.message}", exception)
        } catch (exception: IOException) {
            throw StaticAnalysisException("读取 APK 时发生 I/O 错误：${artifact.fileName}，${exception.message}", exception)
        }
    }

    private fun inspectDex(
        zipFile: ZipFile,
        entry: ZipEntry,
        size: Long,
        compressedSize: Long,
    ): DexFileSummary {
        val header = readPrefix(zipFile, entry, DEX_HEADER_BYTES)
        val validMagic = header.size >= DEX_MAGIC_PREFIX.size &&
            DEX_MAGIC_PREFIX.indices.all { index -> header[index] == DEX_MAGIC_PREFIX[index] }
        val version = if (validMagic && header.size >= 7) {
            String(header, 4, 3, StandardCharsets.US_ASCII)
        } else {
            null
        }

        return DexFileSummary(
            entryName = entry.name,
            sizeBytes = size,
            compressedSizeBytes = compressedSize,
            dexVersion = version,
            validMagic = validMagic,
        )
    }

    private fun inspectNativeLibrary(
        zipFile: ZipFile,
        entry: ZipEntry,
        abi: String,
        fileName: String,
        size: Long,
        compressedSize: Long,
    ): NativeLibrarySummary {
        val header = readPrefix(zipFile, entry, ELF_HEADER_BYTES)
        val headerHex = header.take(ELF_PREVIEW_BYTES).joinToString(" ") { byte ->
            "%02X".format(byte.toInt() and 0xff)
        }
        val validMagic = header.size >= ELF_MAGIC.size &&
            ELF_MAGIC.indices.all { index -> header[index] == ELF_MAGIC[index] }
        val diagnostics = mutableListOf<String>()

        if (header.size < ELF_MAGIC.size) {
            diagnostics += "文件过短，无法读取完整的 4 字节 ELF 魔数"
        } else if (!validMagic) {
            diagnostics += "头部不是 7F 45 4C 46；可能是加壳或加密数据、占位文件，或仅使用了 .so 扩展名"
        }

        val classByte = header.getOrNull(4)?.toInt()?.and(0xff)
        val elfClass = if (validMagic) {
            when (classByte) {
                1 -> "ELF32"
                2 -> "ELF64"
                null -> {
                    diagnostics += "ELF 头部过短，缺少 EI_CLASS 字段"
                    null
                }
                else -> {
                    diagnostics += "未知 EI_CLASS：0x${classByte.toHexByte()}"
                    "未知"
                }
            }
        } else {
            null
        }

        val dataEncoding = header.getOrNull(5)?.toInt()?.and(0xff)
        if (validMagic && dataEncoding == null) {
            diagnostics += "ELF 头部过短，缺少 EI_DATA 字段"
        } else if (validMagic && dataEncoding != 1 && dataEncoding != 2) {
            diagnostics += "未知 EI_DATA：0x${dataEncoding.toHexByte()}"
        }

        val knownDataEncoding = dataEncoding == 1 || dataEncoding == 2
        val machine = if (validMagic && header.size >= ELF_HEADER_BYTES && knownDataEncoding) {
            val first = header[18].toInt() and 0xff
            val second = header[19].toInt() and 0xff
            val machineCode = if (dataEncoding == 1) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
            machineName(machineCode)
        } else {
            if (validMagic && header.size < ELF_HEADER_BYTES) {
                diagnostics += "ELF 头部不足 $ELF_HEADER_BYTES 字节，无法读取 e_machine"
            }
            null
        }

        abiMachineDiagnostic(abi, machine)?.let(diagnostics::add)
        val diagnostic = diagnostics
            .distinct()
            .joinToString("；")
            .takeIf { value -> value.isNotBlank() }

        return NativeLibrarySummary(
            entryName = entry.name,
            abi = abi,
            fileName = fileName,
            sizeBytes = size,
            compressedSizeBytes = compressedSize,
            elfClass = elfClass,
            machine = machine,
            validElfMagic = validMagic,
            headerHex = headerHex,
            diagnostic = diagnostic,
        )
    }

    private fun readPrefix(zipFile: ZipFile, entry: ZipEntry, maxBytes: Int): ByteArray {
        val buffer = ByteArray(maxBytes)
        var total = 0
        zipFile.getInputStream(entry).use { input ->
            while (total < maxBytes) {
                val count = input.read(buffer, total, maxBytes - total)
                if (count < 0) break
                total += count
            }
        }
        return buffer.copyOf(total)
    }

    private fun normalizedSize(value: Long): Long = value.coerceAtLeast(0L)

    private fun isUnsafeEntryName(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || WINDOWS_ABSOLUTE_REGEX.containsMatchIn(normalized)) {
            return true
        }
        return normalized.split('/').any { segment -> segment == ".." }
    }

    private fun isSigningEntry(name: String): Boolean {
        if (!name.startsWith(META_INF_PREFIX, ignoreCase = true)) return false
        return SIGNING_SUFFIXES.any { suffix -> name.endsWith(suffix, ignoreCase = true) }
    }

    private fun abiMachineDiagnostic(abi: String, machine: String?): String? {
        val expected = when (abi.lowercase()) {
            "arm64-v8a" -> "AArch64"
            "armeabi", "armeabi-v7a" -> "ARM"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            "mips", "mips64" -> "MIPS"
            else -> null
        }
        return if (expected != null && machine != null && expected != machine) {
            "ABI 目录为 $abi，但 ELF e_machine 为 $machine，二者不一致"
        } else {
            null
        }
    }

    private fun machineName(machineCode: Int): String = when (machineCode) {
        3 -> "x86"
        8 -> "MIPS"
        40 -> "ARM"
        62 -> "x86_64"
        183 -> "AArch64"
        243 -> "RISC-V"
        else -> "未知($machineCode)"
    }

    private fun Int?.toHexByte(): String = this?.let { "%02X".format(it and 0xff) } ?: "??"

    private companion object {
        const val ANDROID_MANIFEST_ENTRY = "AndroidManifest.xml"
        const val RESOURCES_ARSC_ENTRY = "resources.arsc"
        const val RES_PREFIX = "res/"
        const val ASSETS_PREFIX = "assets/"
        const val META_INF_PREFIX = "META-INF/"
        const val APK_SUFFIX = ".apk"
        const val DEX_HEADER_BYTES = 8
        const val ELF_HEADER_BYTES = 20
        const val ELF_PREVIEW_BYTES = 16

        val DEX_ENTRY_REGEX = Regex("^classes(?:[2-9][0-9]*)?\\.dex$")
        val NATIVE_LIBRARY_REGEX = Regex("^lib/([^/]+)/([^/]+\\.so)$")
        val WINDOWS_ABSOLUTE_REGEX = Regex("^[A-Za-z]:/")
        val DEX_MAGIC_PREFIX = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte())
        val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val SIGNING_SUFFIXES = listOf(".RSA", ".DSA", ".EC", ".SF", ".MF")
    }
}
