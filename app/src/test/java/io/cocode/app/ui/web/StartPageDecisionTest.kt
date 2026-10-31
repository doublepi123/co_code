package io.cocode.app.ui.web

import org.junit.Assert.assertEquals
import org.junit.Test

class StartPageDecisionTest {
    @Test
    fun `remembered page on a sub-path instance is restored without repeating the base path`() {
        val url =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/t3",
                baseUrl = "http://127.0.0.1:3000/t3",
                resumePath = "/t3/?session=s1",
            )
        assertEquals("http://127.0.0.1:3000/t3/?session=s1", url)
    }

    @Test
    fun `remembered page at the root is unchanged`() {
        val url =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000",
                baseUrl = "http://127.0.0.1:3000",
                resumePath = "/?session=s1",
            )
        assertEquals("http://127.0.0.1:3000/?session=s1", url)
    }

    @Test
    fun `nothing pending loads the start URL`() {
        val url =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/t3",
                baseUrl = "http://127.0.0.1:3000/t3",
                resumePath = null,
            )
        assertEquals("http://127.0.0.1:3000/t3", url)
    }

    @Test
    fun `an unusable base never falls back to a duplicated saved path`() {
        val url =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/t3",
                baseUrl = "not a url",
                resumePath = "/t3/?session=s1",
            )
        assertEquals("http://127.0.0.1:3000/t3", url)
    }
}
