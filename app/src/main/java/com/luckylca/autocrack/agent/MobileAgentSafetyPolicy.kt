package com.luckylca.autocrack.agent

import java.util.UUID

enum class DangerousOperationCategory(val label: String) {
    DESTRUCTIVE_DELETE("破坏性删除"),
    SYSTEM_WRITE("系统目录写入"),
    BLOCK_DEVICE_WRITE("块设备写入"),
    MOUNT_CONTROL("挂载控制"),
    DEVICE_CONTROL("设备级控制"),
    PACKAGE_DATA_CHANGE("应用安装卸载或数据/权限状态变更"),
}

data class DangerousOperationRequest(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val category: DangerousOperationCategory,
    val command: String,
    val reason: String?,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

enum class DangerousOperationDecision {
    DENY,
    ALLOW_ONCE,
    ALWAYS_ALLOW_CATEGORY,
}

object MobileAgentDangerousCommandClassifier {
    // Pi-Agent mode keeps only a minimal last-resort guardrail around operations that can destroy
    // the device/runtime itself. Normal root administration is intentionally not capability-gated.
    private val rmCommand = Regex("(?is)(^|[;&|\\n])\\s*(?:sudo\\s+)?rm\\s+([^\\n;&|]+)")
    private val recursiveFlag = Regex("(?i)(^|\\s)(?:--recursive|-\\S*r\\S*)(?=\\s|$)")
    private val forceFlag = Regex("(?i)(^|\\s)(?:--force|-\\S*f\\S*)(?=\\s|$)")
    private val criticalDeleteTarget = Regex(
        "(?i)(^|\\s|--\\s+)[\\\"']?/(?:data|system|vendor|product|odm|apex|proc|sys|dev)(?:/|[\\\"']?(?:\\s|$))|(^|\\s|--\\s+)[\\\"']?/[\\\"']?(?:\\s|$)",
    )
    private val blockWrite = Regex("(?is)(^|[;&|\\n])\\s*(?:sudo\\s+)?(?:dd\\s+[^\\n;&|]*\\bof=/dev/(?:block|sd|mmc|nvme)|(?:mkfs(?:\\.[A-Za-z0-9_-]+)?|mkswap|wipefs)\\b|(?:cat\\b[^\\n;&|]*>|tee\\s+)(?:\\s*)/dev/(?:block|sd|mmc|nvme))")
    private val deviceControl = Regex("(?im)(^|[;&|]\\s*)(reboot|poweroff|halt)\\b")

    fun classify(script: String): DangerousOperationCategory? = when {
        isDestructiveDelete(script) -> DangerousOperationCategory.DESTRUCTIVE_DELETE
        blockWrite.containsMatchIn(script) -> DangerousOperationCategory.BLOCK_DEVICE_WRITE
        deviceControl.containsMatchIn(script) -> DangerousOperationCategory.DEVICE_CONTROL
        else -> null
    }

    private fun isDestructiveDelete(script: String): Boolean = rmCommand.findAll(script).any { match ->
        val arguments = match.groupValues[2]
        recursiveFlag.containsMatchIn(arguments) &&
            forceFlag.containsMatchIn(arguments) &&
            criticalDeleteTarget.containsMatchIn(arguments)
    }
}
