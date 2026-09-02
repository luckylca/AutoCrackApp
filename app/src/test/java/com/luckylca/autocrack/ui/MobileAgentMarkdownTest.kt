package com.luckylca.autocrack.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileAgentMarkdownTest {
    @Test
    fun `parses common model response blocks`() {
        val blocks = parseMarkdownBlocks(
            """
            ## Result

            1. first
            - [x] finished
            > note
            ---

            ```kotlin
            val answer = 42
            ```
            """.trimIndent(),
        )

        assertEquals(MarkdownBlock.Heading(2, "Result"), blocks[0])
        assertEquals(MarkdownBlock.ListItem("1.", "first"), blocks[1])
        assertEquals(MarkdownBlock.ListItem("☑", "finished"), blocks[2])
        assertEquals(MarkdownBlock.Quote("note"), blocks[3])
        assertEquals(MarkdownBlock.HorizontalRule, blocks[4])
        assertEquals(MarkdownBlock.Code("kotlin", "val answer = 42"), blocks[5])
    }

    @Test
    fun `keeps an unfinished streaming code fence renderable`() {
        val blocks = parseMarkdownBlocks("before\n\n```sh\necho hello")

        assertEquals(MarkdownBlock.Paragraph("before"), blocks[0])
        assertEquals(MarkdownBlock.Code("sh", "echo hello"), blocks[1])
    }

    @Test
    fun `supports tilde code fences and windows line endings`() {
        val blocks = parseMarkdownBlocks("~~~json\r\n{\\\"ok\\\":true}\r\n~~~")

        assertTrue(blocks.single() is MarkdownBlock.Code)
        assertEquals("{\\\"ok\\\":true}", (blocks.single() as MarkdownBlock.Code).code)
    }

    @Test
    fun `does not close a code block with a different fence`() {
        val blocks = parseMarkdownBlocks("```text\n~~~\nstill code\n```")

        assertEquals(MarkdownBlock.Code("text", "~~~\nstill code"), blocks.single())
    }
}
