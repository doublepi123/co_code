package io.cocode.app.agentlogin

/**
 * Defensive ANSI stripping for guest output. Codex is launched with NO_COLOR/TERM=dumb, but a
 * native binary may still emit colors or an OSC-8 hyperlink; the URL and code parser must never
 * see escape bytes.
 */
internal object AnsiText {
    /** CSI sequences: colors, cursor movement, erase, `ESC [ ... final byte`. */
    private val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    /** OSC sequences (hyperlinks, window titles): `ESC ] ... BEL` or `ESC ] ... ESC \`. */
    private val osc = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

    /** Remaining two-byte escapes. */
    private val twoByte = Regex("\u001B[@-_]")

    fun strip(line: String): String =
        line
            .replace(osc, "")
            .replace(csi, "")
            .replace(twoByte, "")
            .replace("\r", "")
}
