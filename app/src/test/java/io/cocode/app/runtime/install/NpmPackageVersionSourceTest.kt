package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class NpmPackageVersionSourceTest {
    @Test
    fun `Cox updates never downgrade a newer installed Cox version`() = runBlocking {
        server.enqueue(MockResponse().setBody(metadata("0.1.14")))
        val combined = DefaultPackageVersionSource(source(), emptyList(), "0.1.14")
        val latest = combined.latest(PackageVersions("0.2.0", servers = mapOf(ServerKind.T3 to "0.2.0")))
        assertEquals("0.2.0", latest.t3)
    }

    @Test
    fun `Cox registry failure fails the update check instead of guessing a release`() {
        server.enqueue(MockResponse().setResponseCode(503))
        val combined = DefaultPackageVersionSource(source(), emptyList(), "0.1.14")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { combined.latest(PackageVersions("0.1.14")) }
        }
    }
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build()

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun source() = NpmPackageVersionSource(client, registryBaseUrl = server.url("/").toString())

    private fun metadata(vararg versions: String): String =
        """{"name":"x","dist-tags":{"latest":"x"},"versions":{${versions.joinToString(",") { "\"$it\":{}" }}}}"""

    private val codex = AgentSpec("codex", "@openai/codex", "codex", "Codex")

    @Test
    fun `picks the highest stable version across major versions and skips prereleases`() {
        server.enqueue(MockResponse().setBody(metadata("0.166.0", "0.167.0", "1.0.0-beta.1", "1.0.0", "2.0.0-rc.1")))

        val latest = runBlocking { source().latest(codex.npmPackage, pinned = "0.166.0") }

        assertEquals("1.0.0", latest)
    }

    @Test
    fun `never returns a version below the pin`() {
        server.enqueue(MockResponse().setBody(metadata("0.150.0")))

        val latest = runBlocking { source().latest(codex.npmPackage, pinned = "0.166.0") }

        assertEquals("0.166.0", latest)
    }

    @Test
    fun `an unknown pin takes the newest stable version`() {
        server.enqueue(MockResponse().setBody(metadata("1.0.0", "2.0.0")))

        val latest = runBlocking { source().latest(codex.npmPackage, pinned = null) }

        assertEquals("2.0.0", latest)
    }

    @Test
    fun `sends the abbreviated metadata accept header and percent-encodes the scope`() {
        server.enqueue(MockResponse().setBody(metadata("0.166.0")))

        runBlocking { source().latest(codex.npmPackage, pinned = null) }

        val request = server.takeRequest()
        assertEquals("application/vnd.npm.install-v1+json", request.getHeader("Accept"))
        assertEquals("/@openai%2fcodex", request.path)
    }

    @Test
    fun `a non-success answer throws`() {
        server.enqueue(MockResponse().setResponseCode(503))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { source().latest(codex.npmPackage, pinned = null) }
        }
    }

    @Test
    fun `a body without versions throws`() {
        server.enqueue(MockResponse().setBody("""{"name":"x"}"""))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { source().latest(codex.npmPackage, pinned = null) }
        }
    }

    @Test
    fun `selectLatest ignores build metadata and every prerelease form`() {
        val picked =
            NpmPackageVersionSource.selectLatest(
                candidates = listOf("2.4.0+build.5", "2.4.0-dev", "2.4.1", "2.4.2-0"),
                pinned = "2.4.0",
            )

        assertEquals("2.4.1", picked)
    }

    @Test
    fun `default source combines Cox and agent npm versions without comparing legacy product versions`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(metadata("0.1.14", "0.1.15")))
            server.enqueue(MockResponse().setBody(metadata("0.166.0", "0.167.0")))
            val combined =
                DefaultPackageVersionSource(
                    npm = source(),
                    agents = listOf(codex),
                    fallbackT3Version = "0.1.14",
                )

            val latest = combined.latest(PackageVersions(t3 = "0.1.6-pw.5", agents = mapOf("codex" to "0.166.0")))

            assertEquals("0.1.15", latest.t3)
            assertEquals(mapOf("codex" to "0.167.0"), latest.agents)
            assertEquals("/@doublepi123%2fcox", server.takeRequest().path)
            assertEquals("/@openai%2fcodex", server.takeRequest().path)
        }

    @Test
    fun `an OpenChamber-only pinned set never asks for Cox`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(metadata("2.5.3", "2.5.4")))
            val combined =
                DefaultPackageVersionSource(
                    npm = source(),
                    agents = emptyList(),
                    fallbackT3Version = "0.1.14",
                    openChamberPackage = "@openchamber/web",
                )

            val latest =
                combined.latest(
                    PackageVersions(
                        t3 = "0.1.6-pw.5",
                        agents = emptyMap(),
                        servers = mapOf(ServerKind.OpenChamber to "2.5.3"),
                    ),
                )

            assertEquals(1, server.requestCount)
            assertEquals("/@openchamber%2fweb", server.takeRequest().path)
            assertEquals("0.1.6-pw.5", latest.t3)
            assertEquals(mapOf(ServerKind.OpenChamber to "2.5.4"), latest.servers)
        }
}
