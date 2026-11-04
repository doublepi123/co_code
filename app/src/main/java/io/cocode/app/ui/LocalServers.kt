package io.cocode.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.cocode.app.R
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServersState

/**
 * The on-device servers as the UI addresses them. Each [ServerKind] has its own target id
 * (`local-t3`), so it keeps its own WebView profile, cookies and last page; only
 * the pooled WebView object is shared (see `WebViewSlot`).
 */

/** The original local target id; it always meant the Cox/T3 protocol. */
internal const val LEGACY_LOCAL_TARGET_ID = "local"

/** Every on-device target id. */
internal val LOCAL_TARGET_IDS: Set<String> = ServerKind.entries.mapTo(LinkedHashSet()) { it.localTargetId }

/** The kind behind an on-device target id (the legacy `local` is T3 Code), or null for a remote id. */
internal fun localKindOf(targetId: String?): ServerKind? =
    when (targetId) {
        null -> null
        LEGACY_LOCAL_TARGET_ID -> ServerKind.T3
        else -> ServerKind.entries.firstOrNull { it.localTargetId == targetId }
    }

/** True for any on-device target id, including the legacy one. */
internal fun isLocalTargetId(targetId: String?): Boolean = localKindOf(targetId) != null

/** The legacy local id becomes T3 Code's id; every other id is unchanged. */
internal fun migratedTargetId(targetId: String): String =
    if (targetId == LEGACY_LOCAL_TARGET_ID) ServerKind.T3.localTargetId else targetId

/**
 * The server Home and a launch open: the active one. A server that is known not to be installed is
 * never opened; the other installed one stands in (the runtime switches to it). Before the runtime
 * has reported what is installed the active one is used, and the runtime state decides as before.
 */
internal fun ServersState.openableKind(): ServerKind? =
    when {
        installed.isEmpty() || active in installed -> active
        else -> ServerKind.entries.firstOrNull { it in installed }
    }

/** Product names stay as they are in every language; resources keep them in one place. */
@Composable
internal fun ServerKind.label(): String =
    stringResource(
        when (this) {
            ServerKind.T3 -> R.string.server_t3
        },
    )

/**
 * The label for a server kind as a remote protocol. Cox and T3 Code share the same HTTP contract, so
 * an address that runs either one is described by both names.
 */
@Composable
internal fun ServerKind.protocolLabel(): String =
    stringResource(
        when (this) {
            ServerKind.T3 -> R.string.server_t3_remote
        },
    )
