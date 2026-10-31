package io.cocode.app.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for keeping projects and coding-agent directories in a public folder on shared storage
 * (`/sdcard/CoCode`), so they can be maintained with a file manager or over USB.
 *
 * Layout on shared storage (host side):
 * ```
 * /sdcard/CoCode/
 *   projects/          -> guest ~/projects            (home is the working directory)
 *   opencode/          -> guest /root/.config/opencode/{opencode.jsonc,AGENTS.md,skills,agents,commands}
 *   codex/             -> guest /root/.codex (including credentials and sessions)
 *   claude/            -> guest /root/.claude (including credentials and sessions)
 * ```
 * Private storage keeps ~/projects-private, ~/.claude.json, ~/.local/share/opencode (including
 * OpenCode authentication and sessions), the OpenCode service password/plugin node_modules, and
 * both servers' data. Codex and Claude directory sharing intentionally includes their login tokens
 * and history after explicit consent; other storage-authorized apps may read or copy them.
 * Disabling sharing preserves the public tree but does not copy changes back to the older private
 * copies. Re-enabling uses the public tree again.
 */
enum class SharedStorageStatus {
    /** The user has not turned the feature on. Guest ~/projects is private storage. */
    Off,

    /** Turned on, but all-files access is not granted (or was revoked). Falls back to private. */
    NeedsPermission,

    /** Turned on and granted: binds are active from the next server start. */
    Active,
}

data class SharedStorageState(
    val status: SharedStorageStatus,
    /** Host path of the shared root, e.g. /storage/emulated/0/CoCode. */
    val hostRoot: String,
    /** True when the running server was started with a different binding than the current setting. */
    val restartRequired: Boolean,
    /**
     * True when shared storage is active in the legacy projects/OpenCode-only mode because the user
     * has not confirmed the current full-directory (Codex/Claude credentials included) consent.
     * The UI must show the consent action before Codex/Claude folders are shared.
     */
    val needsConsent: Boolean = false,
)

interface SharedStorageController {
    val state: StateFlow<SharedStorageState>

    /** Turn the feature on or off. Takes effect on the next server start (UI offers a restart). */
    fun setEnabled(enabled: Boolean)

    /**
     * Explicit full-directory confirmation flow. Enabling through this method stores the current
     * consent version and is the only path that may share the Codex and Claude folders. The default
     * implementation preserves compatibility and deliberately stores no consent.
     */
    fun setEnabledWithFullDirectoryConsent(enabled: Boolean) {
        setEnabled(enabled)
    }

    /** Re-check the permission, e.g. after returning from the system settings screen. */
    fun refreshPermission()
}
