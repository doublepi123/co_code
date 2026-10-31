package io.cocode.app.runtime.process

import io.cocode.app.runtime.fs.writeFileReplacing
import java.io.File
import java.nio.file.Files

/**
 * OpenChamber's machine policy, written into the guest at `/etc/openchamber/policy.json` before each
 * OpenChamber launch.
 *
 * Pinning `opencodeBinary` makes OpenChamber launch exactly the OpenCode installed by this app and
 * report its own upgrade as unavailable (manager `administrator`, reason `policy`), so
 * `/api/opencode/upgrade` refuses with 409. Cocode owns OpenCode updates: an in-place
 * `opencode upgrade` would break the PRoot link2symlink layout.
 *
 * Invalid JSON there turns OpenChamber's enterprise mode on (fail-closed), so the file is always
 * written whole and atomically through [writeFileReplacing]. A symlink at the target is replaced by
 * the file itself; the link's destination is never written.
 */
object OpenChamberPolicy {
    /** Guest path of the policy file, relative to the rootfs. */
    const val RELATIVE_PATH = "etc/openchamber/policy.json"

    /** The exact content. Built by hand so the bytes do not depend on a JSON library's formatting. */
    val CONTENT: String = "{\"opencodeBinary\":\"${ProotCommands.GUEST_OPENCODE}\"}\n"

    /**
     * Writes the policy into [rootfs]. Skips the write when the file already has exactly [CONTENT] and
     * is a regular file, so its modification time stays put. Throws on I/O failure.
     */
    fun write(rootfs: File) {
        val target = File(rootfs, RELATIVE_PATH)
        if (target.isFile &&
            !Files.isSymbolicLink(target.toPath()) &&
            target.readText() == CONTENT
        ) {
            return
        }
        writeFileReplacing(target, CONTENT)
    }
}
