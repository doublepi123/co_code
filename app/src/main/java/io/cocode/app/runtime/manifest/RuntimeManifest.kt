package io.cocode.app.runtime.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `assets/runtime-manifest.json`: which Alpine rootfs, which T3 Code server release and which agent
 * CLIs the app installs. It also supplies the port and the per-ABI rootfs pins.
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

    /** The T3 bundle architecture name for [abi] (`arm64`, `x64`). */
    fun t3Arch(abi: String): String =
        requireNotNull(t3.architectures[abi]) { "T3 Code does not support ABI $abi" }

    fun agent(id: String): AgentSpec? = agents.firstOrNull { it.id == id }

    fun validate() {
        require(schemaVersion == 1) { "Unsupported runtime manifest schema: $schemaVersion" }
        require(runtimeVersion.isNotBlank()) { "Runtime version is missing" }
        require(alpineVersion.isNotBlank()) { "Alpine version is missing" }
        require(port in 1024..65535) { "Invalid T3 Code port: $port" }
        require(architectures.isNotEmpty()) { "Runtime manifest has no architectures" }
        architectures.forEach { (abi, item) -> item.validate(abi) }
        t3.validate()
        require(t3.architectures.keys.containsAll(architectures.keys)) {
            "T3 Code has no bundle for: ${architectures.keys - t3.architectures.keys}"
        }
        require(agents.isNotEmpty()) { "Runtime manifest has no agents" }
        require(agents.map(AgentSpec::id).distinct().size == agents.size) { "Agent ids must be unique" }
        agents.forEach(AgentSpec::validate)
    }
}

/** The on-device T3 Code server bundle, published as a GitHub release. */
@Serializable
data class T3Spec(
    val repository: String,
    /** Fallback when the GitHub API is unreachable. */
    val version: String,
    val assetPattern: String,
    /** ABI (`arm64-v8a`) to bundle arch (`arm64`). */
    val architectures: Map<String, String>,
) {
    fun assetName(
        version: String,
        arch: String,
    ): String = assetPattern.replace("{version}", version).replace("{arch}", arch)

    fun validate() {
        require(REPOSITORY.matches(repository)) { "Invalid T3 repository: $repository" }
        require(VERSION.matches(version)) { "Invalid T3 version: $version" }
        require(assetPattern.contains("{version}") && assetPattern.contains("{arch}")) {
            "Invalid T3 asset pattern: $assetPattern"
        }
        require(architectures.isNotEmpty()) { "T3 Code has no architectures" }
        architectures.forEach { (abi, arch) ->
            require(abi.isNotBlank() && arch.isNotBlank()) { "Invalid T3 architecture entry: $abi" }
        }
    }

    companion object {
        /** `v0.1.6-pw.5`: pre-release tags, but only this shape counts. */
        val TAG = Regex("""^v(\d+\.\d+\.\d+-pw\.\d+)$""")
        val VERSION = Regex("""^\d+\.\d+\.\d+-pw\.\d+$""")
        val REPOSITORY = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
    }
}

/** One user-selectable agent CLI, installed globally from npm. */
@Serializable
data class AgentSpec(
    val id: String,
    val npmPackage: String,
    val bin: String,
    val displayName: String,
) {
    fun validate() {
        require(ID.matches(id)) { "Invalid agent id: $id" }
        require(npmPackage.isNotBlank()) { "Agent $id has no npm package" }
        require(bin.isNotBlank()) { "Agent $id has no binary" }
        require(displayName.isNotBlank()) { "Agent $id has no display name" }
    }

    private companion object {
        val ID = Regex("""^[a-z][a-z0-9-]*$""")
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
