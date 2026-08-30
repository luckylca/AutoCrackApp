package com.luckylca.autocrack.ui

internal sealed interface MobileAgentDestination {
    data object Conversations : MobileAgentDestination
    data class Settings(val page: AgentSettingsPage) : MobileAgentDestination
}

internal sealed interface MobileAgentLaunchRoute {
    data class Conversation(val conversationId: String) : MobileAgentLaunchRoute
    data object Terminal : MobileAgentLaunchRoute
}

internal data class MobileAgentRouteRequest(
    val sequence: Long,
    val route: MobileAgentLaunchRoute,
)

internal data class MobileAgentNavigationHistory(
    val entries: List<MobileAgentDestination> = listOf(MobileAgentDestination.Conversations),
) {
    init {
        require(entries.isNotEmpty()) { "Navigation history must not be empty" }
    }

    val current: MobileAgentDestination
        get() = entries.last()

    val previous: MobileAgentDestination?
        get() = entries.getOrNull(entries.lastIndex - 1)

    val canGoBack: Boolean
        get() = previous != null

    fun navigate(destination: MobileAgentDestination): MobileAgentNavigationHistory {
        if (destination == current) return this
        return copy(entries = (entries + destination).takeLast(MAX_HISTORY_ENTRIES))
    }

    fun back(): MobileAgentNavigationHistory = if (canGoBack) {
        copy(entries = entries.dropLast(1))
    } else {
        this
    }

    fun encode(): String = entries.joinToString(ENTRY_SEPARATOR) { destination ->
        when (destination) {
            MobileAgentDestination.Conversations -> CONVERSATIONS_KEY
            is MobileAgentDestination.Settings -> "$SETTINGS_PREFIX${destination.page.name}"
        }
    }

    companion object {
        fun initial(route: MobileAgentLaunchRoute?): MobileAgentNavigationHistory = MobileAgentNavigationHistory(
            entries = listOf(
                when (route) {
                    MobileAgentLaunchRoute.Terminal -> MobileAgentDestination.Settings(AgentSettingsPage.TERMINAL)
                    else -> MobileAgentDestination.Conversations
                },
            ),
        )

        fun decode(encoded: String): MobileAgentNavigationHistory {
            val entries = encoded.split(ENTRY_SEPARATOR)
                .mapNotNull { value ->
                    when {
                        value == CONVERSATIONS_KEY -> MobileAgentDestination.Conversations
                        value.startsWith(SETTINGS_PREFIX) -> value.removePrefix(SETTINGS_PREFIX)
                            .let { name -> runCatching { AgentSettingsPage.valueOf(name) }.getOrNull() }
                            ?.let(MobileAgentDestination::Settings)
                        else -> null
                    }
                }
            return MobileAgentNavigationHistory(entries.ifEmpty { listOf(MobileAgentDestination.Conversations) })
        }

        private const val MAX_HISTORY_ENTRIES = 64
        private const val ENTRY_SEPARATOR = "|"
        private const val CONVERSATIONS_KEY = "conversations"
        private const val SETTINGS_PREFIX = "settings:"
    }
}
