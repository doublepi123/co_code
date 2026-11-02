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
    val openchamber: OpenChamberSpec = OpenChamberSpec(),
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
        openchamber.validate()
        require(agents.isNotEmpty()) { "Runtime manifest has no agents" }
        require(agents.map(AgentSpec::id).distinct().size == agents.size) { "Agent ids must be unique" }
        agents.forEach(AgentSpec::validate)
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

/** The on-device OpenChamber server, installed from npm. */
@Serializable
data class OpenChamberSpec(
    val npmPackage: String = "@openchamber/web",
    /** Bundled fallback when the registry cannot be reached during setup. */
    val version: String = "2.5.3",
    /** Fixed loopback port; must match [io.cocode.app.runtime.ServerKind.OpenChamber]. */
    val port: Int = 3000,
    /** Guest path of the CLI, after the npm global prefix. */
    val cliPath: String = "/usr/local/lib/node_modules/@openchamber/web/bin/cli.js",
    /** Guest path of the built web UI. */
    val uiPath: String = "/usr/local/lib/node_modules/@openchamber/web/dist/index.html",
) {
    fun validate() {
        require(npmPackage.isNotBlank()) { "OpenChamber npm package is missing" }
        require(VERSION.matches(version)) { "Invalid OpenChamber version: $version" }
        require(port in 1024..65535) { "Invalid OpenChamber port: $port" }
        require(cliPath.startsWith("/usr/local/lib/node_modules/")) { "Invalid OpenChamber CLI path: $cliPath" }
        require(uiPath.startsWith("/usr/local/lib/node_modules/")) { "Invalid OpenChamber UI path: $uiPath" }
    }

    private companion object {
        val VERSION = Regex("""^\d+(\.\d+)+$""")
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
