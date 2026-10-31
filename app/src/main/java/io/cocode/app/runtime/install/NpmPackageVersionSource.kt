package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Latest versions from the npm registry's abbreviated metadata (`GET /<name>`, with the
 * `application/vnd.npm.install-v1+json` accept header). [registryBaseUrl] is injectable for tests.
 *
 * Agent CLIs are not version-locked: the highest stable version wins, across major versions. A
 * registry that lags behind the installed version never causes a downgrade.
 */
class NpmPackageVersionSource(
    private val client: OkHttpClient,
    private val registryBaseUrl: String = DEFAULT_REGISTRY,
) {
    /** The newest stable version of [packageName], or [pinned] when the registry has nothing newer. */
    suspend fun latest(
        packageName: String,
        pinned: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            selectLatest(fetchVersions(packageName), pinned)
        }

    private fun fetchVersions(name: String): Collection<String> {
        // The scope slash must be percent-encoded for the registry's path routing.
        val encoded = name.replace("/", "%2f")
        val request =
            Request.Builder()
                .url("${registryBaseUrl.trimEnd('/')}/$encoded")
                .header("Accept", "application/vnd.npm.install-v1+json")
                .header("User-Agent", "CoCode-Android")
                .build()
        // Derived client: keeps the shared connection pool, but bounds this call to the registry timeout.
        val bounded =
            client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        bounded.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "npm registry answered ${response.code} for $name" }
            val body = response.body?.string() ?: error("npm registry sent no body for $name")
            return parseVersionKeys(body)
        }
    }

    companion object {
        const val DEFAULT_REGISTRY = "https://registry.npmjs.org"
        private const val TIMEOUT_SECONDS = 15L

        private val json = Json { ignoreUnknownKeys = true }

        /** The keys of the registry document's `versions` object. Throws when the document is malformed. */
        internal fun parseVersionKeys(body: String): Set<String> {
            val root: JsonObject = json.parseToJsonElement(body).jsonObject
            val versions = root["versions"]?.jsonObject ?: error("npm metadata has no versions")
            return versions.keys
        }

        /**
         * The highest stable version among [candidates]. Pre-releases (any `-` or `+` suffix) are
         * excluded. Returns [pinned] when nothing qualifies, and never a version below it.
         */
        internal fun selectLatest(
            candidates: Collection<String>,
            pinned: String?,
        ): String? {
            val best =
                candidates
                    .filter { STABLE.matches(it) }
                    .maxWithOrNull { a, b -> RuntimeEnvironmentInstaller.compareVersions(a, b) }
            if (best == null) return pinned
            if (pinned != null && RuntimeEnvironmentInstaller.compareVersions(best, pinned) < 0) return pinned
            return best
        }

        private val STABLE = Regex("""^\d+(\.\d+)+$""")
    }
}

/**
 * Combines the T3 Code release channel with npm for the agent CLIs. `latest()` throws when npm is
 * unreachable (the controller reports the check as failed and keeps the installed versions); a
 * GitHub failure falls back to the manifest's pinned T3 version.
 */
class DefaultPackageVersionSource(
    private val npm: NpmPackageVersionSource,
    private val t3: T3ReleaseSource,
    private val agents: List<AgentSpec>,
    private val fallbackT3Version: String,
    private val openChamberPackage: String = "@openchamber/web",
) : PackageVersionSource {
    override suspend fun latest(pinned: PackageVersions): PackageVersions {
        val t3Version =
            if (pinned.servers.isEmpty() || ServerKind.T3 in pinned.servers) {
                t3.latest()?.version ?: fallbackT3Version
            } else {
                pinned.t3
            }
        val installedAgents =
            agents
                .filter { it.id in pinned.agents }
                .map { spec -> spec.id to npm.latest(spec.npmPackage, pinned.agents[spec.id]) }
                .filter { (_, version) -> !version.isNullOrBlank() }
                .associate { (id, version) -> id to version!! }
        val openChamber =
            pinned.servers[ServerKind.OpenChamber]?.let { current ->
                npm.latest(openChamberPackage, current)?.let { mapOf(ServerKind.OpenChamber to it) }
            } ?: emptyMap()
        return PackageVersions(t3 = t3Version, agents = installedAgents, servers = openChamber)
    }
}
