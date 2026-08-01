package com.luckylca.autocrack.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SqlLikePatternEscaperTest {
    @Test
    fun escape_usesSingleCharacterEscapeAndProtectsWildcards() {
        assertEquals(
            "login!!token!%!_",
            SqlLikePatternEscaper.escape("login!token%_"),
        )
        assertEquals('!', SqlLikePatternEscaper.ESCAPE_CHARACTER)
    }
}
