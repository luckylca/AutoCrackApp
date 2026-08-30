package com.luckylca.autocrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentNavigationHistoryTest {
    @Test
    fun backReturnsToExactSourceForSharedDestination() {
        val fromHome = MobileAgentNavigationHistory()
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.HOME))
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.ROOTFS))
        assertEquals(MobileAgentDestination.Settings(AgentSettingsPage.HOME), fromHome.back().current)

        val fromEnvironment = MobileAgentNavigationHistory()
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.HOME))
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.ENVIRONMENT))
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.ROOTFS))
        assertEquals(MobileAgentDestination.Settings(AgentSettingsPage.ENVIRONMENT), fromEnvironment.back().current)
    }

    @Test
    fun modelRedirectReturnsToConversation() {
        val history = MobileAgentNavigationHistory()
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.MODEL))

        assertTrue(history.canGoBack)
        assertEquals(MobileAgentDestination.Conversations, history.previous)
        assertEquals(MobileAgentDestination.Conversations, history.back().current)
    }

    @Test
    fun coldTerminalLaunchDoesNotInventConversationBackEntry() {
        val history = MobileAgentNavigationHistory.initial(MobileAgentLaunchRoute.Terminal)

        assertEquals(MobileAgentDestination.Settings(AgentSettingsPage.TERMINAL), history.current)
        assertFalse(history.canGoBack)
        assertEquals(null, history.previous)
    }

    @Test
    fun encodedHistoryRestoresSameRouteOrder() {
        val original = MobileAgentNavigationHistory()
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.HOME))
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.ADVANCED))
            .navigate(MobileAgentDestination.Settings(AgentSettingsPage.TERMINAL))

        assertEquals(original, MobileAgentNavigationHistory.decode(original.encode()))
    }
}
