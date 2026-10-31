package io.cocode.app.runtime.install

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class T3ReleaseSourceTest {
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

    private fun source() =
        GithubT3ReleaseSource(
            client = client,
            repository = "doublepi123/t3code",
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            downloadBaseUrl = server.url("/download").toString().trimEnd('/'),
        )

    @Test
    fun `latest resolves a pw tag and its asset urls`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.1.6-pw.5","name":"x"}"""))

            val release = source().latest()!!

            assertEquals("0.1.6-pw.5", release.version)
            assertEquals("v0.1.6-pw.5", release.tag)
            assertEquals(
                server.url("/download").toString().trimEnd('/') +
                    "/doublepi123/t3code/releases/download/v0.1.6-pw.5/t3-0.1.6-pw.5-android-linux-arm64-musl.tar.gz",
                release.assetUrl("t3-0.1.6-pw.5-android-linux-arm64-musl.tar.gz"),
            )
            assertEquals("/repos/doublepi123/t3code/releases/latest", server.takeRequest().path)
        }

    @Test
    fun `a tag that is not a pw release is not a release`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"tag_name":"v1.0.0"}"""))

            assertNull(source().latest())
        }

    @Test
    fun `an unreachable api is null so the caller can use the manifest fallback`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(503))

            assertNull(source().latest())
        }

    @Test
    fun `checksums are parsed with an optional binary marker and lowercased`() =
        runBlocking {
            val hash = "a".repeat(64).uppercase()
            server.enqueue(
                MockResponse().setBody(
                    "$hash  t3-0.1.6-pw.5-android-linux-arm64-musl.tar.gz\n" +
                        "${"b".repeat(64)} *t3-0.1.6-pw.5-android-linux-x64-musl.tar.gz\n" +
                        "not-a-hash  ignored\n",
                ),
            )

            val sums = source().sha256Sums(T3Release("0.1.6-pw.5", "v0.1.6-pw.5", server.url("/download").toString().trimEnd('/')))

            assertEquals(hash.lowercase(), sums["t3-0.1.6-pw.5-android-linux-arm64-musl.tar.gz"])
            assertEquals("b".repeat(64), sums["t3-0.1.6-pw.5-android-linux-x64-musl.tar.gz"])
            assertEquals(2, sums.size)
        }

    @Test
    fun `an empty checksum list fails closed`() {
        server.enqueue(MockResponse().setBody(""))
        assert(
            runCatching {
                runBlocking { source().sha256Sums(T3Release("0.1.6-pw.5", "v0.1.6-pw.5", server.url("/download").toString().trimEnd('/'))) }
            }.isFailure,
        )
    }

    @Test
    fun `tag parsing handles extra json fields`() {
        assertEquals("0.1.6-pw.5", GithubT3ReleaseSource.parseVersionOrNull("""{"tag_name":"v0.1.6-pw.5","assets":[]}"""))
        assertNull(GithubT3ReleaseSource.parseVersionOrNull("""{"tag_name":"nightly"}"""))
        assertNull(GithubT3ReleaseSource.parseVersionOrNull("not json"))
    }
}
