package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.T3Spec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Guest paths that make a server usable; a metadata claim without them is not served. */
object InstalledComponents {
    const val COX_MODULE = "/usr/local/lib/node_modules/@doublepi123/cox"
    const val COX_CLI = "$COX_MODULE/dist/bin.mjs"
    const val COX_UI = "$COX_MODULE/dist/client/index.html"
    const val T3_CLI = "opt/t3/bin.mjs"
    const val T3_UI = "opt/t3/client/index.html"
    const val T3_BUNDLE_INFO = "opt/t3/ANDROID-BUNDLE.json"
    /** `server` value of a Cox bundle; a legacy T3 Code bundle lacks it. */
    const val COX_SERVER = "cox"

    private val json = Json { ignoreUnknownKeys = true }

    fun serverAssetsPresent(
        rootfs: File,
        kind: ServerKind,
    ): Boolean =
        when (kind) {
            ServerKind.T3 -> isCoxNpmInstall(rootfs) || (File(rootfs, T3_CLI).isFile && File(rootfs, T3_UI).isFile)
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
    fun isCoxServerBundle(rootfs: File): Boolean = isCoxNpmInstall(rootfs) || t3Bundle(rootfs)?.server == COX_SERVER

    fun coxNpmVersion(rootfs: File): String? =
        runCatching {
            val manifest = json.parseToJsonElement(File(rootfs, "$COX_MODULE/package.json".removePrefix("/")).readText()).jsonObject
            require(manifest.text("name") == "@doublepi123/cox")
            manifest.text("version")?.takeIf { T3Spec.VERSION.matches(it) }
        }.getOrNull()

    fun isCoxNpmInstall(rootfs: File): Boolean =
        coxNpmVersion(rootfs) != null &&
            File(rootfs, COX_CLI.removePrefix("/")).isFile &&
            File(rootfs, COX_UI.removePrefix("/")).isFile &&
            File(rootfs, "$COX_MODULE/node_modules/node-pty".removePrefix("/")).isDirectory

    fun coxModuleGuest(rootfs: File): String = if (isCoxNpmInstall(rootfs)) COX_MODULE else "/opt/t3"

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
        if (spec.installation == "bundled") {
            File(rootfs, "${coxModuleGuest(rootfs)}/node_modules/@cursor/sdk/package.json".removePrefix("/")).isFile
        } else {
            File(rootfs, "usr/local/lib/node_modules/${spec.npmPackage}").isDirectory &&
                File(rootfs, "usr/local/bin/${spec.bin}").exists()
        }
}

fun RuntimeMetadata.recordedServerVersions(): Map<ServerKind, String> {
    val explicit = servers.mapNotNull { (id, version) -> ServerKind.fromId(id)?.let { it to version } }.toMap()
    if (servers.isNotEmpty()) return explicit
    return t3Version.takeIf(String::isNotBlank)?.let { mapOf(ServerKind.T3 to it) } ?: emptyMap()
}

fun installedServerVersions(rootfs: File, metadata: RuntimeMetadata): Map<ServerKind, String> =
    metadata.recordedServerVersions().filterKeys { InstalledComponents.serverAssetsPresent(rootfs, it) }
