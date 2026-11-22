package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
    internal val httpClient: OkHttpClient get() = client

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

class GithubReleaseVersionSource(
    private val client: OkHttpClient,
    private val apiBaseUrl: String = DEFAULT_GITHUB_API,
) {
    suspend fun latest(
        repository: String,
        pinned: String?,
    ): String? =
        withContext(Dispatchers.IO) {
            NpmPackageVersionSource.selectLatest(fetchLatestRelease(repository), pinned)
        }

    private fun fetchLatestRelease(repository: String): Collection<String> {
        val request =
            Request.Builder()
                .url("${apiBaseUrl.trimEnd('/')}/repos/$repository/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CoCode-Android")
                .build()
        val bounded =
            client.newBuilder()
                .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
        bounded.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub API answered ${response.code} for $repository" }
            val body = response.body?.string() ?: error("GitHub API sent no body for $repository")
            return parseReleaseTag(body)
        }
    }

    companion object {
        const val DEFAULT_GITHUB_API = "https://api.github.com"
        private const val TIMEOUT_SECONDS = 15L
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseReleaseTag(body: String): List<String> {
            val root = json.parseToJsonElement(body).jsonObject
            val draft = (root["draft"] as? JsonPrimitive)?.booleanOrNull == true
            val prerelease = (root["prerelease"] as? JsonPrimitive)?.booleanOrNull == true
            if (draft || prerelease) return emptyList()
            val tag =
                (root["tag_name"] as? JsonPrimitive)?.content?.trim()?.removePrefix("v")
                    ?.takeIf(String::isNotBlank)
                    ?: error("GitHub release metadata has no tag_name")
            return listOf(tag)
        }
    }
}

data class VersionSourceCatalog(
    val agents: List<AgentSpec>,
    val fallbackT3Version: String,
    val coxPackage: String = "@doublepi123/cox",
)

data class AgentVersionResolvers(
    val github: GithubReleaseVersionSource? = null,
    val coxOmoVersion: () -> String? = { null },
)

class DefaultPackageVersionSource(
    private val npm: NpmPackageVersionSource,
    private val catalog: VersionSourceCatalog,
    private val resolvers: AgentVersionResolvers = AgentVersionResolvers(),
) : PackageVersionSource {
    constructor(
        npm: NpmPackageVersionSource,
        agents: List<AgentSpec>,
        fallbackT3Version: String,
        coxPackage: String = "@doublepi123/cox",
    ) : this(npm, VersionSourceCatalog(agents, fallbackT3Version, coxPackage))

    override suspend fun latest(pinned: PackageVersions): PackageVersions {
        val t3Version =
            if (pinned.servers.isEmpty() || ServerKind.T3 in pinned.servers) {
                val current = pinned.servers[ServerKind.T3] ?: pinned.t3
                val minimum = current.takeUnless { it.contains("-pw.") }
                    ?.let { NpmPackageVersionSource.selectLatest(listOf(it, catalog.fallbackT3Version), null) }
                    ?: catalog.fallbackT3Version
                npm.latest(catalog.coxPackage, minimum) ?: minimum
            } else {
                pinned.t3
            }
        val github = resolvers.github ?: GithubReleaseVersionSource(npm.httpClient)
        val installedAgents =
            catalog.agents
                .filter { it.id in pinned.agents }
                .map { spec ->
                    val resolved =
                        when (spec.installation) {
                            AgentSpec.INSTALLATION_BUNDLED -> RuntimeEnvironmentInstaller.BUNDLED_AGENT_VERSION
                            AgentSpec.INSTALLATION_GITHUB_RELEASE -> {
                                val minimum =
                                    NpmPackageVersionSource.selectLatest(
                                        listOfNotNull(pinned.agents[spec.id], spec.fallbackVersion.takeIf(String::isNotBlank)),
                                        null,
                                    )
                                github.latest(spec.githubRepo, minimum) ?: minimum
                            }
                            else ->
                                if (spec.versionPolicy == AgentSpec.VERSION_POLICY_COX) {
                                    resolvers.coxOmoVersion()
                                        ?: spec.fallbackVersion.takeIf(String::isNotBlank)
                                        ?: pinned.agents[spec.id]
                                } else {
                                    npm.latest(spec.npmPackage, pinned.agents[spec.id])
                                }
                        }
                    spec.id to resolved
                }
                .filter { (_, version) -> !version.isNullOrBlank() }
                .associate { (id, version) -> id to version!! }
        return PackageVersions(t3 = t3Version, agents = installedAgents)
    }
}
