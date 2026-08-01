package com.luckylca.autocrack.tools

enum class AnalysisToolId(
    val title: String,
    val description: String,
    val requiresInput: Boolean,
) {
    APK_OVERVIEW(
        title = "APK 总览",
        description = "汇总 Base/Split、Manifest、权限、组件、DEX、资源和 SO。",
        requiresInput = false,
    ),
    DEX_OVERVIEW(
        title = "DEX 索引总览",
        description = "读取当前 DEX 索引规模、文件位置和耗时。",
        requiresInput = false,
    ),
    DEX_SEARCH(
        title = "DEX 符号搜索",
        description = "直接搜索类、方法、字段和字符串；适合包名、类名、方法名和常量。",
        requiresInput = true,
    ),
    DEX_NATIVE_METHODS(
        title = "Native 方法清单",
        description = "列出 DEX 中声明为 native 的 Java/Kotlin 方法，为 JNI 与 SO 关联提供入口。",
        requiresInput = false,
    ),
    SO_OVERVIEW(
        title = "SO 清单",
        description = "按 APK、ABI 和文件名列出所有 Native 库及基础 ELF 诊断。",
        requiresInput = false,
    ),
    ELF_INSPECT(
        title = "ELF 深度分析",
        description = "解析一个 SO 的 ELF 头、依赖、符号、加固、JNI 和关键字符串。",
        requiresInput = true,
    ),
}

enum class AnalysisToolRisk {
    READ_ONLY,
}

data class AnalysisToolResult(
    val toolId: AnalysisToolId,
    val risk: AnalysisToolRisk,
    val title: String,
    val summary: String,
    val details: List<String>,
    val outputFilePath: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
) {
    val durationMillis: Long
        get() = (completedAtEpochMillis - startedAtEpochMillis).coerceAtLeast(0L)
}

class AnalysisToolException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
