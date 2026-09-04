package com.luckylca.autocrack.agent

import java.util.UUID

enum class DangerousOperationCategory(val label: String) {
    DESTRUCTIVE_DELETE("破坏性删除"),
    SYSTEM_WRITE("系统目录写入"),
    BLOCK_DEVICE_WRITE("块设备写入"),
    MOUNT_CONTROL("挂载控制"),
    DEVICE_CONTROL("设备级控制"),
    PACKAGE_DATA_CHANGE("应用安装卸载或数据/权限状态变更"),
    TARGET_RUNTIME_MUTATION("目标应用运行时修改"),
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
    // This is a last-mile guard for raw shell. It intentionally classifies mutation commands only;
    // observation and debugging commands remain available without confirmation noise.
    private val rmCommand = Regex("(?is)(^|[;&|\\n])\\s*(?:sudo\\s+)?rm\\s+([^\\n;&|]+)")
    private val recursiveFlag = Regex("(?i)(^|\\s)(?:--recursive|-\\S*r\\S*)(?=\\s|$)")
    private val forceFlag = Regex("(?i)(^|\\s)(?:--force|-\\S*f\\S*)(?=\\s|$)")
    private val criticalDeleteTarget = Regex(
        "(?i)(^|\\s|--\\s+)[\\\"']?/(?:data|system|vendor|product|odm|apex|proc|sys|dev)(?:/|[\\\"']?(?:\\s|$))|(^|\\s|--\\s+)[\\\"']?/[\\\"']?(?:\\s|$)",
    )
    private val blockWrite = Regex("(?is)(^|[;&|\\n])\\s*(?:sudo\\s+)?(?:dd\\s+[^\\n;&|]*\\bof=/dev/(?:block|sd|mmc|nvme)|(?:mkfs(?:\\.[A-Za-z0-9_-]+)?|mkswap|wipefs)\\b|(?:cat\\b[^\\n;&|]*>|tee\\s+)(?:\\s*)/dev/(?:block|sd|mmc|nvme))")
    private val deviceControl = Regex("(?im)(^|[;&|]\\s*)(reboot|poweroff|halt)\\b")
    private val mountControl = Regex("(?im)(^|[;&|]\\s*)(?:sudo\\s+)?(?:mount|umount)\\b")
    private val packageDataChange = Regex(
        "(?im)(^|[;&|]\\s*)(?:sudo\\s+)?(?:pm\\s+(?:install(?:-[a-z]+)?|uninstall|clear|enable|disable(?:-user)?|grant|revoke|reset-permissions)\\b|cmd\\s+(?:package\\s+(?:install|uninstall|clear|grant|revoke|set-enabled-setting|set-distracting-restriction)\\b|appops\\s+(?:set|reset)\\b))",
    )
    private val systemSettingChange = Regex(
        "(?im)(^|[;&|]\\s*)(?:sudo\\s+)?(?:settings\\s+(?:put|delete|reset)\\b|setprop\\s+persist(?:\\.|\\s))",
    )
    private val systemPathMutation = Regex(
        "(?im)(^|[;&|]\\s*)(?:sudo\\s+)?(?:(?:chmod|chown|touch|mkdir|rmdir|rm|mv|cp|ln|install|truncate)\\b[^\\n;&|]*|sed\\s+[^\\n;&|]*\\s-i(?:\\s|$)[^\\n;&|]*|(?:cat\\b[^\\n;&|]*>|tee(?:\\s+-a)?\\s+))['\"]?/(?:system|vendor|product|odm|apex|proc|sys)(?:/|['\"]?(?:\\s|$))",
    )
    private val systemPathRedirect = Regex(
        "(?im)(?:>|>>)\\s*['\"]?/(?:system|vendor|product|odm|apex|proc|sys)(?:/|['\"]?(?:\\s|$))",
    )
    private val targetRuntimeMutation = Regex(
        "(?im)(^|[;&|]\\s*)(?:env\\s+\\S+\\s+)*(?:\\S*/)?(?:" +
            "runtime-control\\s+(?:webview-debug|webview-eval|webview-load-url|webview-reload|webview-go-back|webview-go-forward|webview-clear-cache|secure-disable|so-inject|so-dlopen|so-android-dlopen-ext|activity-start|process-kill|object-field-set|object-method-call)\\b" +
            "|ui-inspect\\s+action\\b" +
            "|simplehook\\s+(?:apply|reload)\\b" +
            "|simplehook\\s+rules\\s+(?:add|update|enable|disable|remove)\\b" +
            ")",
    )
    private val explicitDebuggerMutation = Regex(
        "(?is)(?:^|[;&|\\n])[^\\n;&|]*(?:lldb\\b[^\\n;&|]*(?:memory\\s+write|register\\s+write)|frida\\b[^\\n;&|]*(?:Interceptor\\.replace|Memory\\.write[A-Za-z0-9_]*|\\.implementation\\s*=))",
    )

    fun classify(script: String): DangerousOperationCategory? = when {
        isDestructiveDelete(script) -> DangerousOperationCategory.DESTRUCTIVE_DELETE
        blockWrite.containsMatchIn(script) -> DangerousOperationCategory.BLOCK_DEVICE_WRITE
        deviceControl.containsMatchIn(script) -> DangerousOperationCategory.DEVICE_CONTROL
        packageDataChange.containsMatchIn(script) -> DangerousOperationCategory.PACKAGE_DATA_CHANGE
        targetRuntimeMutation.containsMatchIn(script) || explicitDebuggerMutation.containsMatchIn(script) -> DangerousOperationCategory.TARGET_RUNTIME_MUTATION
        mountControl.containsMatchIn(script) -> DangerousOperationCategory.MOUNT_CONTROL
        systemSettingChange.containsMatchIn(script) || systemPathMutation.containsMatchIn(script) ||
            systemPathRedirect.containsMatchIn(script) ->
            DangerousOperationCategory.SYSTEM_WRITE
        else -> null
    }

    private fun isDestructiveDelete(script: String): Boolean = rmCommand.findAll(script).any { match ->
        val arguments = match.groupValues[2]
        recursiveFlag.containsMatchIn(arguments) &&
            forceFlag.containsMatchIn(arguments) &&
            criticalDeleteTarget.containsMatchIn(arguments)
    }
}
