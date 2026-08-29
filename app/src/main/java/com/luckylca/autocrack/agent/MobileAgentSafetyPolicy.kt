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
    private val destructiveDelete = Regex("(?is)(^|[;&|\\n])\\s*(sudo\\s+)?rm\\s+[^\\n;&|]*(-[^\\s]*r[^\\s]*f|-rf|-fr)[^\\n;&|]*(/(?:data|system|vendor|product|odm|apex|proc|sys|dev)(?:/|\\s|$)|/\\s*(?:$|[;&|]))")
    private val systemWrite = Regex("(?is)(>|>>|tee\\s+|cp\\s+|mv\\s+|install\\s+|chmod\\s+|chown\\s+)[^\\n;&|]*(/(?:system|vendor|product|odm|apex)(?:/|\\s|$))")
    private val blockWrite = Regex("(?is)(dd\\s+[^\\n;&|]*\\bof=/dev/(?:block|sd|mmc|nvme)|mkfs(?:\\.|\\s)|mkswap\\s+|wipefs\\s+)")
    private val mountControl = Regex("(?im)(^|[;&|]\\s*)(mount|umount)\\b")
    private val deviceControl = Regex("(?im)(^|[;&|]\\s*)(reboot|poweroff|halt)\\b")
    private val packageDataChange = Regex("(?im)(^|[;&|]\\s*)((?:pm|cmd\\s+package)\\s+(?:install(?:-existing)?|uninstall|clear|enable|disable(?:-user)?|grant|revoke|suspend|unsuspend|hide|unhide)\\b)")
    private val settingsWrite = Regex("(?im)(^|[;&|]\\s*)(settings\\s+(?:put|delete)\\b|setprop\\s+)")

    fun classify(script: String): DangerousOperationCategory? = when {
        destructiveDelete.containsMatchIn(script) -> DangerousOperationCategory.DESTRUCTIVE_DELETE
        blockWrite.containsMatchIn(script) -> DangerousOperationCategory.BLOCK_DEVICE_WRITE
        systemWrite.containsMatchIn(script) -> DangerousOperationCategory.SYSTEM_WRITE
        mountControl.containsMatchIn(script) -> DangerousOperationCategory.MOUNT_CONTROL
        deviceControl.containsMatchIn(script) -> DangerousOperationCategory.DEVICE_CONTROL
        packageDataChange.containsMatchIn(script) -> DangerousOperationCategory.PACKAGE_DATA_CHANGE
        settingsWrite.containsMatchIn(script) -> DangerousOperationCategory.SYSTEM_WRITE
        else -> null
    }
}
