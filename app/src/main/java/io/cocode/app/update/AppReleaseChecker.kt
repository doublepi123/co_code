package io.cocode.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** A published CoCode APK release newer than the running app. */
data class AppReleaseUpdate(
    val version: String,
    val pageUrl: String,
    val notes: String,
)

/**
 * Checks the CoCode GitHub releases for a newer published APK.
 *
 * The release list is used instead of `/releases/latest` because the project publishes `0.x`
 * releases as prereleases. The check is best-effort: network, HTTP, malformed JSON and unexpected
 * payloads are all reported as "no update" so a launch never fails because GitHub is unreachable.
 * A previously found update is kept until [dismiss] is called for that version.
 *
 * The caller owns the coroutine scope and calls [check] on launch/resume. [check] is reentrancy-safe
 * and rate-limited to one network attempt per [COOLDOWN_MILLIS].
 */
class AppReleaseChecker private constructor(
    private val client: OkHttpClient,
    private val currentVersion: String,
    private val nowMillis: () -> Long,
    apiBaseUrl: String,
    pageBaseUrl: String,
) {
    constructor(
        client: OkHttpClient = OkHttpClient(),
        currentVersion: String,
        nowMillis: () -> Long = System::currentTimeMillis,
    ) : this(client, currentVersion, nowMillis, DEFAULT_API_BASE_URL, DEFAULT_PAGE_BASE_URL)

    /** Test seam: lets a local MockWebServer stand in for the GitHub API and release page host. */
    internal constructor(
        currentVersion: String,
        client: OkHttpClient = OkHttpClient(),
        nowMillis: () -> Long = System::currentTimeMillis,
        apiBaseUrl: String,
        pageBaseUrl: String = DEFAULT_PAGE_BASE_URL,
    ) : this(client, currentVersion, nowMillis, apiBaseUrl, pageBaseUrl)

    private val apiBase = apiBaseUrl.trimEnd('/')
    private val pageBase = pageBaseUrl.trimEnd('/')
    private val httpClient =
        client.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

    private val checkLock = Mutex()
    private var lastAttemptAt: Long? = null
    @Volatile private var dismissedVersion: String? = null

    private val _available = MutableStateFlow<AppReleaseUpdate?>(null)
    val available: StateFlow<AppReleaseUpdate?> = _available.asStateFlow()

    /**
     * Looks for a newer APK release. Reentrant calls return immediately, and calls within the
     * cooldown window do not touch the network. Failures and malformed responses are silent and
     * leave [available] unchanged.
     */
    suspend fun check() {
        if (!checkLock.tryLock()) return
        try {
            val now = nowMillis()
            val last = lastAttemptAt
            if (last != null && now - last in 0 until COOLDOWN_MILLIS) return
            lastAttemptAt = now

            val update = fetchUpdate() ?: return
            if (update.version == dismissedVersion) return
            _available.value = update
        } finally {
            checkLock.unlock()
        }
    }

    /** Hides the currently offered update for the rest of this process. A newer version prompts again. */
    fun dismiss() {
        val current = _available.value ?: return
        dismissedVersion = current.version
        _available.value = null
    }

    private suspend fun fetchUpdate(): AppReleaseUpdate? {
        val body = fetchReleases() ?: return null
        return withContext(Dispatchers.IO) { selectNewest(body) }
    }

    private suspend fun fetchReleases(): String? =
        suspendCancellableCoroutine { continuation ->
            val request = buildRequest()
            if (request == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val payload =
                            try {
                                response.use { readBoundedBody(it) }
                            } catch (_: Throwable) {
                                null
                            }
                        if (continuation.isActive) continuation.resume(payload)
                    }
                },
            )
        }

    private fun buildRequest(): Request? {
        val url =
            (apiBase + "/repos/" + REPOSITORY + "/releases")
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.addQueryParameter("per_page", RELEASE_PAGE_SIZE.toString())
                ?.build()
                ?: return null
        if (!url.isHttps && url.host !in LOOPBACK_HOSTS) return null
        return Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
    }

    /** Reads at most [MAX_BODY_BYTES] bytes so a hostile or broken response cannot exhaust memory. */
    private fun readBoundedBody(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > MAX_BODY_BYTES) return null

        val source = body.source()
        val buffer = Buffer()
        while (true) {
            val remaining = MAX_BODY_BYTES + 1 - buffer.size
            if (remaining <= 0) return null
            val read = source.read(buffer, minOf(READ_CHUNK_BYTES.toLong(), remaining))
            if (read == -1L) break
        }
        return buffer.readUtf8()
    }

    internal fun selectNewest(body: String): AppReleaseUpdate? {
        val current = parseCurrentVersion(currentVersion) ?: return null
        val releases =
            runCatching {
                Json.parseToJsonElement(body) as? JsonArray
            }.getOrNull() ?: return null

        var best: Candidate? = null
        for (element in releases) {
            val release = element as? JsonObject ?: continue
            val candidate = runCatching { candidateFor(release, current) }.getOrNull() ?: continue
            if (best == null || candidate.numeric > best.numeric) best = candidate
        }
        return best?.update
    }

    private fun candidateFor(release: JsonObject, current: NumericVersion): Candidate? {
        if (release.boolean("draft") == true) return null

        val tag = release.string("tag_name") ?: return null
        val match = TAG.matchEntire(tag) ?: return null
        val numeric =
            NumericVersion(
                major = BigInteger(match.groupValues[1]),
                minor = BigInteger(match.groupValues[2]),
                patch = BigInteger(match.groupValues[3]),
            )
        if (numeric <= current) return null

        val version = tag.substring(1)
        val pageUrl = "$pageBase/releases/tag/$tag"
        val htmlUrl = release.string("html_url") ?: return null
        if (htmlUrl.trimEnd('/') != pageUrl) return null

        val apkName = "CoCode-$version.apk"
        val expectedDownloadUrl = "$pageBase/releases/download/$tag/$apkName"
        val assets = release.array("assets") ?: return null
        val hasApk =
            assets.any { asset ->
                val obj = asset as? JsonObject ?: return@any false
                obj.string("name") == apkName && obj.string("browser_download_url") == expectedDownloadUrl
            }
        if (!hasApk) return null

        val notes = release.string("body").orEmpty().take(MAX_NOTES_CHARS)
        return Candidate(numeric, AppReleaseUpdate(version = version, pageUrl = pageUrl, notes = notes))
    }

    private fun parseCurrentVersion(value: String): NumericVersion? {
        val match = CURRENT_VERSION.matchEntire(value.trim()) ?: return null
        return NumericVersion(
            major = BigInteger(match.groupValues[1]),
            minor = BigInteger(match.groupValues[2]),
            patch = BigInteger(match.groupValues[3]),
        )
    }

    private data class Candidate(val numeric: NumericVersion, val update: AppReleaseUpdate)

    private data class NumericVersion(
        val major: BigInteger,
        val minor: BigInteger,
        val patch: BigInteger,
    ) : Comparable<NumericVersion> {
        override fun compareTo(other: NumericVersion): Int {
            major.compareTo(other.major).let { if (it != 0) return it }
            minor.compareTo(other.minor).let { if (it != 0) return it }
            return patch.compareTo(other.patch)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    companion object {
        const val DEFAULT_API_BASE_URL = "https://api.github.com"
        const val DEFAULT_PAGE_BASE_URL = "https://github.com/doublepi123/co_code"

        internal const val COOLDOWN_MILLIS = 10 * 60 * 1000L
        internal const val MAX_NOTES_CHARS = 4_000

        private const val REPOSITORY = "doublepi123/co_code"
        private const val RELEASE_PAGE_SIZE = 30
        private const val USER_AGENT = "CoCode-Android"
        private const val REQUEST_TIMEOUT_SECONDS = 10L
        private const val MAX_BODY_BYTES = 1_000_000L
        private const val READ_CHUNK_BYTES = 8_192
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

        private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
        private val CURRENT_VERSION = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")
    }
}
