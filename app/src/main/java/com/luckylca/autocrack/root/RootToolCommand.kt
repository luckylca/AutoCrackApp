package com.luckylca.autocrack.root

import com.luckylca.autocrack.apk.PackageOutputParser

sealed interface RootToolCommand {
    val label: String
    val timeoutMillis: Long

    data class ListInstalledPackages(
        val androidUserId: Int,
    ) : RootToolCommand {
        override val label: String = "List installed packages for user $androidUserId"
        override val timeoutMillis: Long = 20_000L
    }

    data class ReadPackageApkPaths(
        val packageName: String,
        val androidUserId: Int,
    ) : RootToolCommand {
        override val label: String = "Read APK paths for $packageName"
        override val timeoutMillis: Long = 10_000L
    }

    data class CopyApkToWorkspace(
        val sourcePath: String,
        val destinationPath: String,
        val ownerUid: Int,
        val ownerGid: Int,
    ) : RootToolCommand {
        override val label: String = "Copy ${sourcePath.substringAfterLast('/')} to workspace"
        override val timeoutMillis: Long = 120_000L
    }
}

class RootToolExecutor(
    private val runner: RootCommandRunner,
    private val suPath: String,
) {
    init {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        require('\u0000' !in suPath && '\n' !in suPath && '\r' !in suPath) {
            "su path contains an invalid character"
        }
    }

    suspend fun execute(command: RootToolCommand): CommandResult = runner.run(
        command = RootToolCommandFactory.build(suPath, command),
        label = command.label,
        timeoutMillis = command.timeoutMillis,
    )
}

object RootToolCommandFactory {
    fun build(suPath: String, command: RootToolCommand): List<String> {
        require(suPath.isNotBlank()) { "su path must not be blank" }
        val shellCommand = when (command) {
            is RootToolCommand.ListInstalledPackages -> {
                require(command.androidUserId >= 0) { "Android user id must not be negative" }
                "pm list packages -f -U --user ${command.androidUserId}"
            }

            is RootToolCommand.ReadPackageApkPaths -> {
                PackageOutputParser.requireValidPackageName(command.packageName)
                require(command.androidUserId >= 0) { "Android user id must not be negative" }
                "pm path --user ${command.androidUserId} ${command.packageName}"
            }

            is RootToolCommand.CopyApkToWorkspace -> buildCopyCommand(command)
        }
        return listOf(suPath, "-c", shellCommand)
    }

    fun shellQuote(value: String): String {
        require('\u0000' !in value && '\n' !in value && '\r' !in value) {
            "Shell argument contains an invalid control character"
        }
        return "'${value.replace("'", "'\"'\"'")}'"
    }

    private fun buildCopyCommand(command: RootToolCommand.CopyApkToWorkspace): String {
        require(command.sourcePath.startsWith('/')) { "APK source path must be absolute" }
        require(command.sourcePath.endsWith(".apk", ignoreCase = true)) {
            "APK source path must end with .apk"
        }
        require(command.destinationPath.startsWith('/')) { "Destination path must be absolute" }
        require(command.destinationPath.endsWith(".apk", ignoreCase = true)) {
            "Destination path must end with .apk"
        }
        require(command.ownerUid >= 0 && command.ownerGid >= 0) {
            "Workspace owner ids must not be negative"
        }

        val source = shellQuote(command.sourcePath)
        val destination = shellQuote(command.destinationPath)
        return buildString {
            append("umask 077; cp -- ")
            append(source)
            append(' ')
            append(destination)
            append(" && chown ")
            append(command.ownerUid)
            append(':')
            append(command.ownerGid)
            append(' ')
            append(destination)
            append(" && chmod 600 ")
            append(destination)
        }
    }
}
