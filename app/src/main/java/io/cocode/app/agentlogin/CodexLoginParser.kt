package io.cocode.app.agentlogin

/** A complete device-code prompt: link to open, one-time code to type, and when it stops working. */
internal data class CodexDevicePrompt(
    val url: String,
    val code: String,
    val expiresAtMillis: Long,
)

/**
 * Recognises Codex's device-code instructions across output lines, tolerant of ANSI colors,
 * indentation and the leading PATH-alias warning:
 *
 * ```
 * 1. Open this link in your browser and sign in to your account
 *    https://auth.openai.com/codex/device
 *
 * 2. Enter this one-time code (expires in 15 minutes)
 *    ABCD-EFGH
 * ```
 */
internal class CodexLoginParser(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var url: String? = null
    private var code: String? = null
    private var lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS
    private var prompt: CodexDevicePrompt? = null

    /** Feeds one raw (possibly colored) line; returns the prompt once both parts have been seen. */
    fun accept(rawLine: String): CodexDevicePrompt? {
        prompt?.let { return it }
        val line = AnsiText.strip(rawLine).trim()
        if (line.isEmpty()) return null

        expiry.find(line)?.let { match ->
            match.groupValues.getOrNull(1)?.toLongOrNull()?.let { minutes ->
                lifetimeMillis = (minutes * 60_000L).coerceAtLeast(MIN_LIFETIME_MILLIS)
            }
        }
        if (url == null) {
            urlLink.find(line)?.value?.trimEnd('.', ',', ')', '\'', '"')?.let { url = it }
        }
        if (code == null) {
            oneTimeCode.find(line)?.value?.let { code = it }
        }
        val resolvedUrl = url
        val resolvedCode = code
        if (resolvedUrl != null && resolvedCode != null) {
            prompt =
                CodexDevicePrompt(
                    url = resolvedUrl,
                    code = resolvedCode,
                    expiresAtMillis = now() + lifetimeMillis,
                )
        }
        return prompt
    }

    companion object {
        const val DEFAULT_LIFETIME_MILLIS: Long = 15 * 60_000L
        private const val MIN_LIFETIME_MILLIS: Long = 60_000L

        private val urlLink = Regex("""https://\S+""")
        private val oneTimeCode = Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4,6}\b""")
        private val expiry = Regex("""expires in (\d+)\s+minutes?""", RegexOption.IGNORE_CASE)
    }
}
