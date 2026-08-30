package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileAgentPreferencesTest {
    @Test
    fun defaultUsesAutomaticLongTaskMode() {
        assertEquals(0, MobileAgentPreferences().maxToolIterations)
    }

    @Test
    fun iterationLimitValidationSupportsAutomaticAndLargeManualBudgets() {
        assertEquals(0, MobileAgentPreferences(maxToolIterations = -1).validated().maxToolIterations)
        assertEquals(512, MobileAgentPreferences(maxToolIterations = 512).validated().maxToolIterations)
        assertEquals(2_048, MobileAgentPreferences(maxToolIterations = 9_999).validated().maxToolIterations)
    }
}
