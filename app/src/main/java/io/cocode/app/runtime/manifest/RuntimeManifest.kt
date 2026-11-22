package io.cocode.app.runtime.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `assets/runtime-manifest.json`: which Alpine rootfs, which Cox (T3 Code protocol) server release
 * and which agent CLIs the app installs. It also supplies the port and the per-ABI rootfs pins.
 */
@Serializable
data class RuntimeManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val alpineVersion: String,
    val port: Int,
    val t3: T3Spec,
    val agents: List<AgentSpec>,
    val architectures: Map<String, RuntimeArchitecture>,
) {
    fun architecture(abi: String): RuntimeArchitecture =
        requireNotNull(architectures[abi]) { "Linux runtime does not support ABI $abi" }

    fun agent(id: String): AgentSpec? = agents.firstOrNull { it.id == id }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported runtime manifest schema: $schemaVersion" }
        require(runtimeVersion.isNotBlank()) { "Runtime version is missing" }
        require(alpineVersion.isNotBlank()) { "Alpine version is missing" }
        require(port in 1024..65535) { "Invalid Cox port: $port" }
        require(architectures.isNotEmpty()) { "Runtime manifest has no architectures" }
        architectures.forEach { (abi, item) -> item.validate(abi) }
        t3.validate()
        require(agents.isNotEmpty()) { "Runtime manifest has no agents" }
        require(agents.map(AgentSpec::id).distinct().size == agents.size) { "Agent ids must be unique" }
        agents.forEach { agent ->
            agent.validate()
            if (agent.installation == AgentSpec.INSTALLATION_GITHUB_RELEASE) {
                val missing = architectures.keys - agent.assets.keys
                require(missing.isEmpty()) { "Agent ${agent.id} has no release asset for ${missing.sorted().joinToString()}" }
            }
        }
    }
}

@Serializable
data class T3Spec(
    val npmPackage: String = "@doublepi123/cox",
    val version: String,
) {
    fun validate() {
        require(npmPackage == "@doublepi123/cox") { "Unsupported Cox npm package: $npmPackage" }
        require(VERSION.matches(version)) { "Invalid Cox version: $version" }
    }

    companion object {
        val VERSION = Regex("""^\d+\.\d+\.\d+$""")
    }
}

/** A user-selectable npm CLI, a GitHub-released musl CLI, or an SDK bundled with Cox. */
@Serializable
data class AgentSpec(
    val id: String,
    val npmPackage: String = "",
    val bin: String,
    val displayName: String,
    val installation: String = INSTALLATION_NPM,
    val versionPolicy: String = VERSION_POLICY_LATEST,
    val fallbackVersion: String = "",
    val githubRepo: String = "",
    val assets: Map<String, String> = emptyMap(),
) {
    fun assetFor(abi: String): String =
        requireNotNull(assets[abi]) { "Agent $id does not support ABI $abi" }

    fun validate() {
        require(ID.matches(id)) { "Invalid agent id: $id" }
        require(displayName.isNotBlank()) { "Agent $id has no display name" }
        when (installation) {
            INSTALLATION_NPM -> {
                require(PACKAGE.matches(npmPackage)) { "Agent $id has an invalid npm package" }
                require(ID.matches(bin)) { "Agent $id has an invalid binary" }
                require(githubRepo.isEmpty() && assets.isEmpty()) { "Npm agent $id cannot declare release assets" }
                when (versionPolicy) {
                    VERSION_POLICY_LATEST ->
                        require(fallbackVersion.isEmpty()) { "Unpinned npm agent $id cannot set a fallback version" }
                    VERSION_POLICY_COX ->
                        require(id == "omo" && T3Spec.VERSION.matches(fallbackVersion)) {
                            "Cox-coupled agent $id requires a valid fallback version"
                        }
                    else -> throw IllegalArgumentException("Unknown version policy for $id: $versionPolicy")
                }
            }
            INSTALLATION_GITHUB_RELEASE -> {
                require(npmPackage.isEmpty() && versionPolicy == VERSION_POLICY_LATEST) {
                    "Release agent $id cannot set npm package fields"
                }
                require(ID.matches(bin)) { "Agent $id has an invalid binary" }
                require(REPO.matches(githubRepo)) { "Agent $id has an invalid GitHub repository" }
                require(T3Spec.VERSION.matches(fallbackVersion)) { "Agent $id has an invalid fallback version" }
                require(assets.isNotEmpty()) { "Agent $id has no release assets" }
                assets.forEach { (abi, asset) ->
                    require(ABI.matches(abi) && ASSET.matches(asset)) { "Agent $id has an invalid asset for $abi" }
                }
            }
            INSTALLATION_BUNDLED -> {
                require(
                    id == "cursor" &&
                        npmPackage.isEmpty() &&
                        bin.isEmpty() &&
                        versionPolicy == VERSION_POLICY_LATEST &&
                        fallbackVersion.isEmpty() &&
                        githubRepo.isEmpty() &&
                        assets.isEmpty(),
                ) { "Unsupported bundled agent: $id" }
            }
            else -> throw IllegalArgumentException("Unknown installation method for $id")
        }
    }

    companion object {
        const val INSTALLATION_NPM = "npm"
        const val INSTALLATION_GITHUB_RELEASE = "github-release"
        const val INSTALLATION_BUNDLED = "bundled"
        const val VERSION_POLICY_LATEST = "latest"
        const val VERSION_POLICY_COX = "cox"

        private val ID = Regex("""^[a-z][a-z0-9-]*$""")
        private val ABI = Regex("""^[a-z0-9_-]+$""")
        private val PACKAGE = Regex("""^(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*$""")
        private val REPO = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        private val ASSET = Regex("""^[A-Za-z0-9._-]+$""")
    }
}

@Serializable
data class RuntimeArchitecture(
    val alpineUrl: String,
    val alpineSha256: String,
) {
    fun validate(abi: String) {
        require(alpineUrl.startsWith("https://")) { "Alpine URL for $abi must use HTTPS" }
        require(SHA256.matches(alpineSha256)) { "Invalid Alpine SHA-256 for $abi" }
    }

    private companion object {
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

class RuntimeManifestReader(
    private val readAsset: () -> String,
) {
    fun read(): RuntimeManifest {
        val payload = readAsset()
        return JSON.decodeFromString<RuntimeManifest>(payload).also { it.validate() }
    }

    companion object {
        const val ASSET_NAME = "runtime-manifest.json"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
