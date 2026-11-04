package io.cocode.app.runtime

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/** Cox's loopback password, persisted in the historical app-private UI password file. */
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
