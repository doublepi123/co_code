package io.cocode.app.agentlogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexLoginParserTest {
    @Test
    fun `returns nothing until both the link and the code are present`() {
        val parser = CodexLoginParser(now = { 1_000L })

        assertNull(parser.accept("1. Open this link in your browser and sign in to your account"))
        assertNull(parser.accept("   https://auth.openai.com/codex/device"))
        assertNull(parser.accept("2. Enter this one-time code (expires in 15 minutes)"))

        val prompt = parser.accept("   \u001B[1mABCD-EFGH\u001B[0m")
        assertEquals("https://auth.openai.com/codex/device", prompt!!.url)
        assertEquals("ABCD-EFGH", prompt.code)
        assertEquals(1_000L + 15 * 60_000L, prompt.expiresAtMillis)
    }

    @Test
    fun `honours the printed expiry and longer one-time codes`() {
        val parser = CodexLoginParser(now = { 0L })

        parser.accept("2. Enter this one-time code (expires in 20 minutes)")
        parser.accept("   WXYZ-ABCDEF")
        val prompt = parser.accept("   https://auth.openai.com/codex/device")!!

        assertEquals("WXYZ-ABCDEF", prompt.code)
        assertEquals(20 * 60_000L, prompt.expiresAtMillis)
    }

    @Test
    fun `ignores the PATH-alias warning and unrelated text`() {
        val parser = CodexLoginParser(now = { 0L })

        assertNull(parser.accept("WARNING: proceeding, even though we could not create PATH aliases"))
        assertNull(parser.accept("Follow these steps to sign in with ChatGPT using device code authorization:"))
        assertNull(parser.accept(""))
        assertTrue(parser.accept("   https://auth.openai.com/codex/device") == null)
    }
}
