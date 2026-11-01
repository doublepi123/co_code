package io.cocode.app.runtime.process

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Moves a legacy T3 Code data directory to Cox's location before Cox starts.
 *
 * T3 Code kept `userdata/` (statev2.sqlite, settings.json, secrets) under `~/.t3`; Cox reads the same
 * layout from `~/.cox`. Both live under the bind-mounted host home, so a rename on the host is a
 * same-filesystem move in the guest. Nothing is ever merged or overwritten: when both directories
 * exist, Cox uses `~/.cox` and the old `~/.t3` is left untouched.
 */
object CoxHomeMigration {
    const val LEGACY_DIR = ".t3"
    const val COX_DIR = ".cox"

    /**
     * Returns the directory name Cox should use ([COX_DIR]) and renames `home/.t3` to `home/.cox` when
     * that is unambiguous. [log] receives one line when the old directory is kept.
     *
     * Existence checks do not follow symlinks: renaming a symlinked `.t3` moves the link itself, never
     * its target.
     */
    fun prepare(
        home: File,
        log: (String) -> Unit = {},
    ): String {
        val legacy = File(home, LEGACY_DIR)
        val cox = File(home, COX_DIR)
        val legacyExists = Files.exists(legacy.toPath(), LinkOption.NOFOLLOW_LINKS)
        val coxExists = Files.exists(cox.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (!legacyExists) return COX_DIR
        if (coxExists) {
            log("Both ${LEGACY_DIR} and ${COX_DIR} exist; Cox uses ${COX_DIR} and ${LEGACY_DIR} is left as it is")
            return COX_DIR
        }
        return try {
            Files.move(legacy.toPath(), cox.toPath())
            log("Moved ${LEGACY_DIR} to ${COX_DIR}")
            COX_DIR
        } catch (error: Throwable) {
            // A failed rename must never block the start; Cox will create a fresh .cox and the old
            // directory stays available for a later manual recovery.
            log("Could not move ${LEGACY_DIR} to ${COX_DIR}: ${error.message}")
            COX_DIR
        }
    }
}
