package com.luckylca.autocrack.runtime

import com.luckylca.autocrack.root.CommandResult

data class HostProcessSummary(
    val pid: Int,
    val parentPid: Int?,
    val uid: Int?,
    val state: String,
    val name: String,
    val commandLine: String,
)

data class HostLoadedModule(
    val path: String,
    val firstAddress: Long,
    val lastAddressExclusive: Long,
    val segmentCount: Int,
    val executable: Boolean,
) {
    val mappedBytes: Long
        get() = (lastAddressExclusive - firstAddress).coerceAtLeast(0L)
}

data class HostProcessListReport(
    val filter: String,
    val capturedAtEpochMillis: Long,
    val commandResult: CommandResult,
    val processes: List<HostProcessSummary>,
)

data class HostProcessInspectionReport(
    val pid: Int,
    val capturedAtEpochMillis: Long,
    val identity: CommandResult,
    val attachPreflight: CommandResult,
    val maps: CommandResult,
    val threads: CommandResult,
    val fileDescriptors: CommandResult,
    val loadedModules: List<HostLoadedModule>,
) {
    val succeeded: Boolean
        get() = listOf(identity, attachPreflight, maps, threads, fileDescriptors)
            .all(CommandResult::succeeded)
}

object DynamicHostOutputParser {
    fun parseProcesses(output: String): List<HostProcessSummary> = output
        .lineSequence()
        .dropWhile { line -> line.startsWith("pid\t") }
        .mapNotNull { line ->
            val fields = line.split('\t', limit = 6)
            if (fields.size != 6) return@mapNotNull null
            val pid = fields[0].toIntOrNull() ?: return@mapNotNull null
            HostProcessSummary(
                pid = pid,
                parentPid = fields[1].trim().toIntOrNull(),
                uid = fields[2].trim().toIntOrNull(),
                state = fields[3].trim(),
                name = fields[4].trim(),
                commandLine = fields[5].trim(),
            )
        }
        .sortedBy(HostProcessSummary::pid)
        .toList()

    fun parseLoadedModules(mapsOutput: String): List<HostLoadedModule> {
        data class MutableModule(
            var firstAddress: Long,
            var lastAddressExclusive: Long,
            var segmentCount: Int,
            var executable: Boolean,
        )

        val modules = linkedMapOf<String, MutableModule>()
        mapsOutput.lineSequence().forEach { line ->
            val match = MAPS_LINE_REGEX.matchEntire(line) ?: return@forEach
            val first = match.groupValues[1].toLongOrNull(16) ?: return@forEach
            val last = match.groupValues[2].toLongOrNull(16) ?: return@forEach
            val permissions = match.groupValues[3]
            val path = match.groupValues[4].trim()
            if (!path.startsWith('/')) return@forEach

            val current = modules[path]
            if (current == null) {
                modules[path] = MutableModule(
                    firstAddress = first,
                    lastAddressExclusive = last,
                    segmentCount = 1,
                    executable = 'x' in permissions,
                )
            } else {
                current.firstAddress = minOf(current.firstAddress, first)
                current.lastAddressExclusive = maxOf(current.lastAddressExclusive, last)
                current.segmentCount += 1
                current.executable = current.executable || 'x' in permissions
            }
        }

        return modules.map { (path, module) ->
            HostLoadedModule(
                path = path,
                firstAddress = module.firstAddress,
                lastAddressExclusive = module.lastAddressExclusive,
                segmentCount = module.segmentCount,
                executable = module.executable,
            )
        }.sortedWith(compareByDescending<HostLoadedModule> { it.executable }.thenBy { it.path })
    }

    private val MAPS_LINE_REGEX = Regex(
        "^([0-9a-fA-F]+)-([0-9a-fA-F]+)\\s+([rwxps-]{4})\\s+" +
            "[0-9a-fA-F]+\\s+\\S+\\s+\\d+\\s*(.*)$",
    )
}
