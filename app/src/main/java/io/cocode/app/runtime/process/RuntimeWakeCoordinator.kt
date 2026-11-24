package io.cocode.app.runtime.process

import java.security.SecureRandom

/** Serializes native runtime work with Cox's launch-scoped wake-lock protocol. */
class RuntimeWakeCoordinator(
    private val setWakeLockHeld: (Boolean) -> Unit,
) {
    private data class Launch(
        val id: Long,
        val nonce: String?,
        var lastSequence: Long = 0,
        var mode: Mode? = null,
        var failed: Boolean = false,
    )

    private enum class Mode { Hold, Idle }

    private var nativeBusy = false
    private var running = false
    private var transitionRequiresLock = true
    private var held = false
    private var launch: Launch? = null

    @Synchronized
    fun observeService(busy: Boolean, serverRunning: Boolean, transitionRequiresLock: Boolean) {
        nativeBusy = busy
        running = serverRunning
        this.transitionRequiresLock = transitionRequiresLock
        reconcile()
    }

    @Synchronized
    fun launchStarted(id: Long, nonce: String?) {
        require(nonce == null || NONCE.matches(nonce))
        launch = Launch(id, nonce)
        running = false
        transitionRequiresLock = true
        reconcile()
    }

    /** Returns true for protocol-looking lines, which must never enter diagnostic logs. */
    @Synchronized
    fun consumeStdoutLine(id: Long, line: String, writeAck: (String) -> Unit): Boolean {
        if (!line.startsWith(PROTOCOL_PREFIX)) return false
        val current = launch
        if (current == null || current.id != id) return true
        if (current.nonce == null || current.failed) {
            failClosed(current)
            return true
        }

        val match = FRAME.matchEntire(line)
        val nonce = match?.groupValues?.get(1)
        val sequence = match?.groupValues?.get(2)?.toLongOrNull()
        val mode = match?.groupValues?.get(3)
        if (
            nonce != current.nonce || sequence == null ||
            (current.lastSequence == 0L && sequence != 1L) || sequence <= current.lastSequence ||
            (mode != "hold" && mode != "idle")
        ) {
            failClosed(current)
            return true
        }

        current.lastSequence = sequence
        current.mode = if (mode == "hold") Mode.Hold else Mode.Idle
        try {
            reconcile()
            writeAck("$PROTOCOL_VERSION $nonce $sequence ack\n")
        } catch (_: Exception) {
            failClosed(current)
        }
        return true
    }

    @Synchronized
    fun stdoutClosed(id: Long) {
        launch?.takeIf { it.id == id }?.let(::failClosed)
    }

    @Synchronized
    fun launchStopped(id: Long) {
        if (launch?.id == id) {
            launch = null
            reconcile()
        }
    }

    private fun failClosed(current: Launch) {
        current.failed = true
        current.mode = null
        reconcile()
    }

    private fun reconcile() {
        val coxIdle = launch?.let { !it.failed && it.nonce != null && it.mode == Mode.Idle } == true
        val shouldHold = nativeBusy || transitionRequiresLock || ((running || launch != null) && !coxIdle)
        if (shouldHold != held) {
            setWakeLockHeld(shouldHold)
            held = shouldHold
        }
    }

    companion object {
        const val ENV_NONCE = "COX_ANDROID_WAKE_NONCE"
        const val PROTOCOL_VERSION = "@cox-android-wake/1"
        private const val PROTOCOL_PREFIX = "@cox-android-wake"
        private val NONCE = Regex("[0-9a-f]{32}")
        private val FRAME = Regex("@cox-android-wake/1 ([0-9a-f]{32}) ([1-9][0-9]*) (hold|idle)")
        private val random = SecureRandom()

        fun newNonce(): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            val hex = "0123456789abcdef"
            return buildString(32) {
                for (byte in bytes) {
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
        }
    }
}
