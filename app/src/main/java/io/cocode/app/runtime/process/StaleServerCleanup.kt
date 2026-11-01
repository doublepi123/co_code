package io.cocode.app.runtime.process

import io.cocode.app.runtime.RuntimePaths

/**
 * Clears what a crashed earlier run left behind before a new server start.
 *
 * Cox keeps its state under `COX_HOME` (`/root/.cox`; a legacy T3 Code bundle uses `/root/.t3`)
 * and does not use a pid file this app can trust. Cleanup is therefore owned-process-only:
 * [ProcessScanner] proves which PRoot trees belong to this app (uid + launcher + rootfs) and only
 * those are killed. The port wait then decides whether a foreign listener is in the way; a foreign
 * listener is never touched.
 */
object StaleServerCleanup {
    /**
     * Polls [isPortFree] every [POLL_MILLIS] for up to [timeoutMillis]. Returns true as soon as the
     * port is free. Time is injected so the wait is unit-tested without sleeping.
     */
    fun awaitPortFree(
        port: Int,
        timeoutMillis: Long = PORT_WAIT_MILLIS,
        isPortFree: (Int) -> Boolean,
        sleep: (Long) -> Unit = Thread::sleep,
        now: () -> Long = System::currentTimeMillis,
    ): Boolean {
        val deadline = now() + timeoutMillis
        while (true) {
            if (isPortFree(port)) return true
            if (now() >= deadline) return false
            sleep(POLL_MILLIS)
        }
    }

    const val PORT_WAIT_MILLIS = 5_000L
    const val POLL_MILLIS = 250L
}

/**
 * Performs one pre-start cleanup: kills this app's owned runtime processes and waits for the port.
 * Returns whether the port is free afterwards; the caller must refuse to launch when it is not.
 */
class ProotStaleServerCleaner(
    private val paths: RuntimePaths,
    private val scanner: ProcessScanner,
    private val signal: (Long) -> Unit,
    private val isPortFree: (Int) -> Boolean,
) {
    fun cleanBeforeStart(
        port: Int,
        prootPath: String,
    ): Boolean {
        killOwnedRuntime(scanner, prootPath, paths.rootfs.absolutePath, signal)
        return StaleServerCleanup.awaitPortFree(port, isPortFree = isPortFree)
    }
}

/** Kills every owned runtime PRoot tree (children first). Used by Stop, Repair and Uninstall too. */
fun killOwnedRuntime(
    scanner: ProcessScanner,
    prootPath: String,
    rootfsPath: String,
    signal: (Long) -> Unit,
) {
    val roots = scanner.ownedRoots(prootPath, rootfsPath)
    val targets = roots.flatMap(scanner::treePostOrder).distinctBy { it.pid }
    scanner.killOwned(targets, signal)
}
