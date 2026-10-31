package io.cocode.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AppReleaseCheckerTest {
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

    private fun checker(
        currentVersion: String = "0.9.0",
        nowMillis: () -> Long = { 0L },
    ) = AppReleaseChecker(
        currentVersion = currentVersion,
        client = client,
        nowMillis = nowMillis,
        apiBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Test
    fun `picks the greatest numeric release across stable and prerelease`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    releases(
                        release("0.9.0"),
                        release("0.10.0", prerelease = true, body = "new in 0.10.0"),
                        release("0.2.0"),
                    ),
                ),
            )
            val checker = checker(currentVersion = "0.9.0")

            checker.check()

            val update = checker.available.value
            assertNotNull(update)
            assertEquals("0.10.0", update!!.version)
            assertEquals("$PAGE/releases/tag/v0.10.0", update.pageUrl)
            assertEquals("new in 0.10.0", update.notes)

            val request = server.takeRequest()
            assertEquals("/repos/doublepi123/co_code/releases?per_page=30", request.path)
            assertNull(request.getHeader("Authorization"))
        }

    @Test
    fun `ignores drafts missing apk wrong tags older equal and external urls`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    releases(
                        release("0.11.0", draft = true),
                        release("0.10.0", apkName = null),
                        release(
                            "0.9.5",
                            assetUrl = "https://evil.example/CoCode-0.9.5.apk",
                        ),
                        release("0.9.4", htmlUrl = "https://evil.example/release"),
                        release("0.9.3", htmlUrl = "https://github.com.evil.example/doublepi123/co_code/releases/tag/v0.9.3"),
                        release("0.9.2", apkName = "CoCode-9.2.apk"),
                        release("0.9.1", body = "valid"),
                        release("0.8.0"),
                        release("0.9.0"),
                        release("0.10.x"),
                    ),
                ),
            )
            val checker = checker(currentVersion = "0.9.0")

            checker.check()

            assertEquals("0.9.1", checker.available.value?.version)
            assertEquals("valid", checker.available.value?.notes)
        }

    @Test
    fun `malformed json and network failure keep the valid cached update`() =
        runBlocking {
            var now = 0L
            val checker = checker(nowMillis = { now })
            server.enqueue(MockResponse().setBody(releases(release("0.10.0"))))
            checker.check()
            assertEquals("0.10.0", checker.available.value?.version)

            now = AppReleaseChecker.COOLDOWN_MILLIS + 1
            server.enqueue(MockResponse().setBody("not json"))
            checker.check()
            assertEquals("0.10.0", checker.available.value?.version)

            now += AppReleaseChecker.COOLDOWN_MILLIS + 1
            server.enqueue(MockResponse().setResponseCode(503))
            checker.check()
            assertEquals("0.10.0", checker.available.value?.version)

            assertEquals(3, server.requestCount)
        }

    @Test
    fun `checks are rate limited to one request per cooldown`() =
        runBlocking {
            var now = 0L
            val checker = checker(nowMillis = { now })
            server.enqueue(MockResponse().setBody(releases(release("0.10.0"))))
            checker.check()

            now = AppReleaseChecker.COOLDOWN_MILLIS - 1
            server.enqueue(MockResponse().setBody(releases(release("0.11.0"))))
            checker.check()
            assertEquals("0.10.0", checker.available.value?.version)
            assertEquals(1, server.requestCount)

            now = AppReleaseChecker.COOLDOWN_MILLIS + 1
            checker.check()
            assertEquals("0.11.0", checker.available.value?.version)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `dismiss hides the same version for the process but not a newer one`() =
        runBlocking {
            var now = 0L
            val checker = checker(nowMillis = { now })
            server.enqueue(MockResponse().setBody(releases(release("0.10.0"))))
            checker.check()
            checker.dismiss()
            assertNull(checker.available.value)

            now = AppReleaseChecker.COOLDOWN_MILLIS + 1
            server.enqueue(MockResponse().setBody(releases(release("0.10.0"))))
            checker.check()
            assertNull(checker.available.value)

            now += AppReleaseChecker.COOLDOWN_MILLIS + 1
            server.enqueue(MockResponse().setBody(releases(release("0.11.0"))))
            checker.check()
            assertEquals("0.11.0", checker.available.value?.version)
        }

    @Test
    fun `concurrent checks issue a single request`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setBody(releases(release("0.10.0")))
                    .setBodyDelay(500, TimeUnit.MILLISECONDS),
            )
            val checker = checker()

            coroutineScope {
                val first = async(Dispatchers.Default) { checker.check() }
                val second = async(Dispatchers.Default) { checker.check() }
                awaitAll(first, second)
            }

            assertEquals(1, server.requestCount)
            assertEquals("0.10.0", checker.available.value?.version)
        }

    @Test
    fun `release notes are bounded`() {
        val checker = checker()

        val update = checker.selectNewest(releases(release("0.10.0", body = "x".repeat(10_000))))

        assertEquals(AppReleaseChecker.MAX_NOTES_CHARS, update?.notes?.length)
    }

    private fun release(
        version: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        body: String = "notes $version",
        apkName: String? = "CoCode-$version.apk",
        htmlUrl: String = "$PAGE/releases/tag/v$version",
        assetUrl: String? = "$PAGE/releases/download/v$version/CoCode-$version.apk",
    ): String {
        val assets =
            if (apkName == null || assetUrl == null) {
                "[]"
            } else {
                """[{"name":"$apkName","browser_download_url":"$assetUrl"}]"""
            }
        return """{"tag_name":"v$version","draft":$draft,"prerelease":$prerelease,""" +
            """"html_url":"$htmlUrl","body":"$body","assets":$assets}"""
    }

    private fun releases(vararg items: String): String =
        items.joinToString(prefix = "[", postfix = "]", separator = ",")

    companion object {
        private const val PAGE = "https://github.com/doublepi123/co_code"
    }
}
