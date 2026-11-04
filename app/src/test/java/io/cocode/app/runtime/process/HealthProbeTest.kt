package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The loopback probe must read the health document of the requested kind and follow no redirect. */
class HealthProbeTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `t3 probe reads the environment document`() {
        server.enqueue(MockResponse().setBody("""{"environmentId":"env-1","serverVersion":"0.1.6-pw.5"}"""))

        val report = HealthProbe.loopback(ServerKind.T3).read(server.port)

        assertEquals("/.well-known/t3/environment", server.takeRequest().path)
        assertEquals("env-1", report?.environmentId)
        assertTrue(report?.ready == true)
    }

    @Test
    fun `a redirect is never followed`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/health"))

        assertNull(HealthProbe.loopback(ServerKind.T3).read(server.port))
        assertEquals(1, server.requestCount)
    }
}
