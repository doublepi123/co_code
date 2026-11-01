package io.cocode.app.runtime.install

import io.cocode.app.runtime.manifest.T3Spec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One published Cox Android release. [version] has no `v`, [tag] is what the download URL uses. */
data class T3Release(
    val version: String,
    val tag: String,
    val downloadBaseUrl: String,
) {
    fun assetUrl(assetName: String): String = "$downloadBaseUrl/$assetName"

    val sha256SumsUrl: String get() = "$downloadBaseUrl/SHA256SUMS"
}

/**
 * Resolves the newest Cox release and its checksums. [latest] returns null when GitHub cannot be
 * reached or answers with a tag that is not a semver release (`v0.1.1`, or a legacy `v0.1.6-pw.5` as
 * well); the caller then falls back to the version pinned in the app's manifest. [sha256Sums]
 * failing is not a fallback: the checksum list is required to verify the bundle (fail closed).
 */
interface T3ReleaseSource {
    suspend fun latest(): T3Release?

    suspend fun sha256Sums(release: T3Release): Map<String, String>
}

/**
 * GitHub releases over HTTPS. The download URL redirects to objects.githubusercontent.com, which
 * OkHttp follows (the caller's client keeps its default redirect policy for GET requests).
 */
class GithubT3ReleaseSource(
    private val client: OkHttpClient,
    private val repository: String,
    private val apiBaseUrl: String = DEFAULT_API,
    private val downloadBaseUrl: String = DEFAULT_DOWNLOAD,
    /**
     * This device's bundle name for a version. When set, [latest] only returns a release whose
     * `SHA256SUMS` already lists that bundle: a new tag becomes `latest` on GitHub minutes before its
     * Android bundles are built and attached, and installing it then would fail closed.
     */
    private val bundleAssetName: ((version: String) -> String)? = null,
) : T3ReleaseSource {
    override suspend fun latest(): T3Release? =
        withContext(Dispatchers.IO) {
            try {
                val tag = fetchTag() ?: return@withContext null
                val release = releaseFor(T3Spec.TAG.matchEntire(tag)?.groupValues?.get(1)) ?: return@withContext null
                val asset = bundleAssetName?.invoke(release.version) ?: return@withContext release
                release.takeIf { asset in parseSums(fetch(release.sha256SumsUrl)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }

    override suspend fun sha256Sums(release: T3Release): Map<String, String> =
        withContext(Dispatchers.IO) {
            val body = fetch(release.sha256SumsUrl)
            parseSums(body).also {
                check(it.isNotEmpty()) { "Cox SHA256SUMS is empty" }
            }
        }

    private fun releaseFor(version: String?): T3Release? {
        if (version == null) return null
        return T3Release(version = version, tag = "v$version", downloadBaseUrl = "$downloadBaseUrl/$repository/releases/download/v$version")
    }

    private fun fetchTag(): String? {
        val body = fetch("${apiBaseUrl.trimEnd('/')}/repos/$repository/releases/latest")
        return Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body)
            .jsonObject["tag_name"]
            ?.jsonPrimitive
            ?.content
    }

    private fun fetch(url: String): String {
        val parsed = url.toHttpUrl()
        require(parsed.isHttps || parsed.host in LOOPBACK_HOSTS) { "Cox release URLs must use HTTPS" }
        val bounded =
            client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        val request =
            Request.Builder()
                .url(parsed)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CoCode-Android")
                .build()
        bounded.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub answered ${response.code} for $url" }
            val body = response.body?.string().orEmpty()
            check(body.length <= MAX_BODY_CHARS) { "Cox release metadata is too large" }
            return body
        }
    }

    companion object {
        const val DEFAULT_API = "https://api.github.com"
        const val DEFAULT_DOWNLOAD = "https://github.com"
        private const val TIMEOUT_SECONDS = 15L
        private const val MAX_BODY_CHARS = 1_000_000
        private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
        private val SHA256 = Regex("""^[a-f0-9A-F]{64}$""")

        /** The version of a valid `releases/latest` body, or null for any other tag shape. */
        fun parseVersionOrNull(body: String): String? =
            runCatching {
                val tag =
                    Json { ignoreUnknownKeys = true }
                        .parseToJsonElement(body)
                        .jsonObject["tag_name"]
                        ?.jsonPrimitive
                        ?.content
                        ?: return null
                T3Spec.TAG.matchEntire(tag)?.groupValues?.get(1)
            }.getOrNull()

        /** `SHA256SUMS` lines are `<sha256>  <file>` (an optional `*` marks binary mode). */
        fun parseSums(body: String): Map<String, String> =
            body.lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("""\s+"""), limit = 2)
                    if (parts.size != 2 || !SHA256.matches(parts[0])) return@mapNotNull null
                    val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
                    if (name.isEmpty()) null else name to parts[0].lowercase()
                }
                .toMap()
    }
}
