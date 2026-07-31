package com.luckylca.autocrack.root

class RootDetector(
    private val runner: RootCommandRunner,
) {
    suspend fun inspect(): RootStatus {
        val suPathResult = runner.run(
            command = listOf("sh", "-c", "command -v su 2>/dev/null || which su 2>/dev/null"),
            label = "Locate su",
            timeoutMillis = QUICK_TIMEOUT_MILLIS,
        )
        val suPath = suPathResult.stdout.lineSequence().firstOrNull { it.isNotBlank() }

        if (suPath.isNullOrBlank()) {
            return RootStatus(
                accessState = RootAccessState.NOT_AVAILABLE,
                provider = RootProvider.UNKNOWN,
                suPath = null,
                versionName = null,
                versionCode = null,
                identity = null,
                evidence = emptyList(),
                diagnostic = suPathResult.failure ?: "未在 PATH 中找到 su",
            )
        }

        val versionNameResult = runner.run(
            command = listOf(suPath, "-v"),
            label = "Read su version name",
            timeoutMillis = QUICK_TIMEOUT_MILLIS,
        )
        val versionCodeResult = runner.run(
            command = listOf(suPath, "-V"),
            label = "Read su version code",
            timeoutMillis = QUICK_TIMEOUT_MILLIS,
        )
        val identityResult = runner.run(
            command = listOf(suPath, "-c", "id"),
            label = "Request root identity",
            timeoutMillis = ROOT_GRANT_TIMEOUT_MILLIS,
        )

        val identity = RootOutputParser.parseIdentity(identityResult.combinedOutput())
        val accessState = when {
            identityResult.timedOut -> RootAccessState.PERMISSION_REQUIRED
            identityResult.failure != null -> RootAccessState.ERROR
            identityResult.exitCode != 0 -> RootAccessState.DENIED
            identity?.uid == 0 -> RootAccessState.GRANTED
            else -> RootAccessState.DENIED
        }

        val markerResult = if (accessState == RootAccessState.GRANTED) {
            runner.run(
                command = listOf(
                    suPath,
                    "-c",
                    "if [ -d /data/adb/ksu ] || [ -x /data/adb/ksud ]; then echo KSU_MARKER; fi",
                ),
                label = "Check KernelSU marker",
                timeoutMillis = QUICK_TIMEOUT_MILLIS,
            )
        } else {
            null
        }

        val evidence = buildList {
            add(versionNameResult.combinedOutput())
            add(versionCodeResult.combinedOutput())
            markerResult?.combinedOutput()?.let(::add)
        }.filter { it.isNotBlank() }

        return RootStatus(
            accessState = accessState,
            provider = RootOutputParser.detectProvider(evidence),
            suPath = suPath,
            versionName = versionNameResult.combinedOutput().ifBlank { null },
            versionCode = versionCodeResult.combinedOutput().ifBlank { null },
            identity = identity,
            evidence = evidence,
            diagnostic = buildDiagnostic(accessState, identityResult),
        )
    }

    private fun buildDiagnostic(
        accessState: RootAccessState,
        identityResult: CommandResult,
    ): String? = when (accessState) {
        RootAccessState.NOT_AVAILABLE -> "设备上没有可用的 su"
        RootAccessState.PERMISSION_REQUIRED -> "Root 授权请求超时，请在 KernelSU 管理器中允许后重试"
        RootAccessState.GRANTED -> null
        RootAccessState.DENIED -> identityResult.combinedOutput().ifBlank { "Root 权限被拒绝" }
        RootAccessState.ERROR -> identityResult.failure ?: "Root 探测发生未知错误"
    }

    private companion object {
        const val QUICK_TIMEOUT_MILLIS = 2_000L
        const val ROOT_GRANT_TIMEOUT_MILLIS = 12_000L
    }
}
