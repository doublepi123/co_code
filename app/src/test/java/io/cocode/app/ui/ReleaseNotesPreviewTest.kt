package io.cocode.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesPreviewTest {
    @Test
    fun `markdown markers are dropped and bullets become dots`() {
        val notes =
            """
            ## What's new

            - **Agent picker**: uncheck OpenCode before picking Codex
            * See [the docs](https://example.com/docs) for `setup`
            """.trimIndent()

        assertEquals(
            "What's new\n\n• Agent picker: uncheck OpenCode before picking Codex\n• See the docs for setup",
            releaseNotesPreview(notes),
        )
    }

    @Test
    fun `blank runs collapse and long notes are cut with an ellipsis`() {
        val notes = (1..20).joinToString("\r\n\r\n\r\n") { "line $it" }

        val preview = releaseNotesPreview(notes, maxLines = 5)

        assertEquals("line 1\n\nline 2\n\nline 3\n…", preview)
    }

    @Test
    fun `empty notes stay empty`() {
        assertEquals("", releaseNotesPreview("\n  \n"))
    }
}
