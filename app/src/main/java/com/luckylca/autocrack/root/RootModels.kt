package com.luckylca.autocrack.root

enum class RootAccessState {
    NOT_AVAILABLE,
    PERMISSION_REQUIRED,
    GRANTED,
    DENIED,
    ERROR,
}

enum class RootProvider {
    KERNEL_SU,
    OTHER,
    UNKNOWN,
}

data class UnixIdentity(
    val uid: Int?,
    val gid: Int?,
    val selinuxContext: String?,
)

data class RootStatus(
    val accessState: RootAccessState,
    val provider: RootProvider,
    val suPath: String?,
    val versionName: String?,
    val versionCode: String?,
    val identity: UnixIdentity?,
    val evidence: List<String>,
    val diagnostic: String?,
) {
    val isRootGranted: Boolean
        get() = accessState == RootAccessState.GRANTED && identity?.uid == 0
}

data class CommandResult(
    val commandLabel: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val failure: String?,
) {
    val succeeded: Boolean
        get() = !timedOut && failure == null && exitCode == 0

    fun combinedOutput(): String = sequenceOf(stdout, stderr)
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .trim()
}

object RootOutputParser {
    private val uidRegex = Regex("(?:^|\\s)uid=(\\d+)")
    private val gidRegex = Regex("(?:^|\\s)gid=(\\d+)")
    private val contextRegex = Regex("(?:^|\\s)context=([^\\s]+)")

    fun parseIdentity(output: String): UnixIdentity? {
        val uid = uidRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val gid = gidRegex.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val context = contextRegex.find(output)?.groupValues?.getOrNull(1)

        if (uid == null && gid == null && context == null) {
            return null
        }
        return UnixIdentity(uid = uid, gid = gid, selinuxContext = context)
    }

    fun detectProvider(evidence: Iterable<String>): RootProvider {
        val normalized = evidence.joinToString("\n").lowercase()
        return when {
            "kernelsu" in normalized || "kernel su" in normalized || "ksu_marker" in normalized -> {
                RootProvider.KERNEL_SU
            }

            normalized.isBlank() -> RootProvider.UNKNOWN
            else -> RootProvider.OTHER
        }
    }
}
