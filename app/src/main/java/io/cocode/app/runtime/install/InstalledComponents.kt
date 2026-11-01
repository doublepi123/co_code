package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Guest paths that make a server usable; a metadata claim without them is not served. */
object InstalledComponents {
    const val T3_CLI = "opt/t3/bin.mjs"
    const val T3_UI = "opt/t3/client/index.html"
    const val T3_BUNDLE_INFO = "opt/t3/ANDROID-BUNDLE.json"
    /** `server` value of a Cox bundle; a legacy T3 Code bundle lacks it. */
    const val COX_SERVER = "cox"
    const val OPENCHAMBER_CLI = "usr/local/lib/node_modules/@openchamber/web/bin/cli.js"
    const val OPENCHAMBER_UI = "usr/local/lib/node_modules/@openchamber/web/dist/index.html"
    const val OPENCHAMBER_PACKAGE_JSON = "usr/local/lib/node_modules/@openchamber/web/package.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun serverAssetsPresent(
        rootfs: File,
        kind: ServerKind,
    ): Boolean =
        when (kind) {
            ServerKind.T3 -> File(rootfs, T3_CLI).isFile && File(rootfs, T3_UI).isFile
            ServerKind.OpenChamber ->
                File(rootfs, OPENCHAMBER_CLI).isFile &&
                    File(rootfs, OPENCHAMBER_UI).isFile &&
                    File(rootfs, "usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty").isDirectory
        }

    /** The fields of an installed server bundle's `ANDROID-BUNDLE.json`; null when unreadable. */
    data class BundleIdentity(
        val version: String? = null,
        val arch: String? = null,
        val libc: String? = null,
        val server: String? = null,
    )

    /** Reads `opt/t3/ANDROID-BUNDLE.json` under [rootfs]; null when it is missing or not a JSON object. */
    fun t3Bundle(rootfs: File): BundleIdentity? = bundleInfo(File(rootfs, T3_BUNDLE_INFO))

    /** Reads `ANDROID-BUNDLE.json` from an extracted bundle directory (the future `opt/t3`). */
    fun bundleInDirectory(bundleDir: File): BundleIdentity? = bundleInfo(File(bundleDir, BUNDLE_INFO_NAME))

    private const val BUNDLE_INFO_NAME = "ANDROID-BUNDLE.json"

    private fun bundleInfo(file: File): BundleIdentity? =
        runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            BundleIdentity(
                version = root.text("version"),
                arch = root.text("arch"),
                libc = root.text("libc"),
                server = root.text("server"),
            )
        }.getOrNull()

    /** True only for a bundle that identifies itself as Cox. */
    fun isCoxServerBundle(rootfs: File): Boolean = t3Bundle(rootfs)?.server == COX_SERVER

    /**
     * True for the T3 Code bundle this app used to install. A `-pw.N` version suffix alone is enough;
     * otherwise a readable bundle decides by its `server` field. A missing bundle with a plain
     * version has no legacy evidence and counts as Cox. Comparing versions across products is never
     * meaningful, so this check gates replacement.
     */
    fun isLegacyT3Bundle(
        rootfs: File,
        version: String?,
    ): Boolean {
        if (version?.contains("-pw.") == true) return true
        val bundle = t3Bundle(rootfs) ?: return false
        return bundle.server != COX_SERVER
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    fun agentFilesPresent(
        rootfs: File,
        spec: AgentSpec,
    ): Boolean =
        File(rootfs, "usr/local/lib/node_modules/${spec.npmPackage}").isDirectory &&
            File(rootfs, "usr/local/bin/${spec.bin}").exists()

    /** The version npm installed, read from the package's own manifest; null when unreadable. */
    fun openChamberVersion(rootfs: File): String? =
        runCatching {
            val root = json.parseToJsonElement(File(rootfs, OPENCHAMBER_PACKAGE_JSON).readText()).jsonObject
            root["version"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

/**
 * Server versions recorded in [RuntimeMetadata]. Legacy v0.1.0 metadata has no `servers` map but a
 * T3 version, which must still mean "T3 is installed".
 */
fun RuntimeMetadata.recordedServerVersions(): Map<ServerKind, String> {
    val explicit =
        servers.mapNotNull { (id, version) ->
            ServerKind.fromId(id)?.let { it to version }
        }.toMap()
    if (explicit.isNotEmpty()) return explicit
    return t3Version.takeIf { it.isNotBlank() }?.let { mapOf(ServerKind.T3 to it) } ?: emptyMap()
}

/** Recorded server versions filtered to those whose executable/web assets exist in [rootfs]. */
fun installedServerVersions(
    rootfs: File,
    metadata: RuntimeMetadata,
): Map<ServerKind, String> =
    metadata
        .recordedServerVersions()
        .filterKeys { kind -> InstalledComponents.serverAssetsPresent(rootfs, kind) }
