package io.cocode.app.service

import android.content.Context
import android.os.PowerManager

/** Low-level partial wake lock; policy lives in RuntimeWakeCoordinator. */
object WakeHold {
    private const val TAG = "CoCode:runtime"

    private val lock = Any()
    private var wakeLock: PowerManager.WakeLock? = null

    /** No timeout: callers hold it only while native work or unconfirmed/active Cox work requires CPU. */
    fun acquire(context: Context) {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) return
            val power = context.applicationContext.getSystemService(PowerManager::class.java)
            wakeLock =
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    fun release() {
        synchronized(lock) {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    /** True while the lock is held; for diagnostics and tests. */
    fun isHeld(): Boolean = synchronized(lock) { wakeLock?.isHeld == true }
}
