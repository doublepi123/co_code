package io.cocode.app.runtime

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * The password that protects an on-device web server (T3 Code or OpenChamber).
 *
 * Each server listens on loopback, which any app with INTERNET can reach. The password is persisted in
 * the app-private runtime directory so the server and the WebView login agree across restarts and is
 * passed to the server through its own environment variable. T3 keeps the historical `ui-password`
 * file; OpenChamber has a separate file, so a password is never reused across kinds.
 *
 * T3 requires at least 8 characters; generated secrets are much longer.
 */
object RuntimeSecrets {
    private const val SECRET_BYTES = 32

    /** Returns the persisted password for [kind], generating one on first use. */
    fun uiPassword(
        paths: RuntimePaths,
        kind: ServerKind = ServerKind.T3,
    ): String {
        existingUiPassword(paths, kind)?.let { return it }
        return synchronized(this) {
            existingUiPassword(paths, kind) ?: generate().also { secret ->
                val file = paths.uiPasswordFile(kind)
                file.parentFile?.mkdirs()
                file.writeText(secret)
            }
        }
    }

    /** The persisted password for [kind], or null when none has been generated yet. */
    fun existingUiPassword(
        paths: RuntimePaths,
        kind: ServerKind = ServerKind.T3,
    ): String? =
        runCatching { paths.uiPasswordFile(kind).takeIf(File::isFile)?.readText()?.trim() }
            .getOrNull()
            ?.takeIf(String::isNotEmpty)

    internal fun generate(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(SECRET_BYTES).also(random::nextBytes),
        )
}
