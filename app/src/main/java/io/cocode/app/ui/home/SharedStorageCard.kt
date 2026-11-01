package io.cocode.app.ui.home

import androidx.compose.ui.res.stringResource
import io.cocode.app.R
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.cocode.app.runtime.SharedStorageState
import io.cocode.app.runtime.SharedStorageStatus
import io.cocode.app.ui.components.ChamberCard
import io.cocode.app.ui.components.ConfirmDialog
import io.cocode.app.ui.components.IconTile
import io.cocode.app.ui.hasStorageAccess
import io.cocode.app.ui.rememberCopyAction
import io.cocode.app.ui.theme.ChamberTextStyles
import io.cocode.app.ui.theme.ChamberTheme
import io.cocode.app.ui.theme.MonoFamily
import kotlinx.coroutines.delay

/** How far the folder tree's rail reaches up toward the root folder icon (stops just below it). */
private val RailReach = 6.dp

/** Time the controller gets to pick up newly granted access before the card blames the folder. */
private const val STATUS_SETTLE_MS = 900L

/**
 * "Projects and agent folders": keeps ~/projects, OpenCode settings, Codex config and the Claude Code
 * folder in CoCode/ on shared storage. Claude Code is shared in full, sign-ins and session history
 * included (the user's explicit choice); Codex shares config only via ~/.codex-shared, its sign-in
 * and sessions stay in the app. Turning it on asks first with a
 * prominent warning and an acknowledgement, and the active body keeps that warning in view.
 * The switch is the setting; a notice offers a restart when the running server still uses the old
 * folders.
 *
 * [SharedStorageState.needsConsent]: shared storage was turned on in an older version, which shared
 * only projects/ and opencode/. Until the user accepts the full Codex and Claude Code sharing (the
 * same dialog and acknowledgement as a fresh turn-on), the card says it runs in that partial mode,
 * lists only those two folders and offers the upgrade as its own action, so the switch never has to
 * be turned off and on to find it. Nothing is migrated until the user accepts.
 */
@Composable
internal fun SharedStorageCard(
    state: SharedStorageState,
    serverRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onAllowAccess: () -> Unit,
    onCheckAgain: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = state.status != SharedStorageStatus.Off
    // Legacy partial sharing (projects + OpenCode only) until the user accepts the full mode.
    val partial = enabled && state.needsConsent
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    var confirmOn by rememberSaveable { mutableStateOf(false) }
    val toggle: (Boolean) -> Unit = { on ->
        when {
            // Sign-ins and sessions become readable by other apps: never without the warning.
            on -> confirmOn = true
            // Turning off makes T3 Code stop seeing the files there, so say so first.
            state.status == SharedStorageStatus.Active -> confirmOff = true
            else -> onEnabledChange(false)
        }
    }

    ChamberCard(modifier = modifier) {
        StorageHeader(status = state.status, enabled = enabled, partial = partial, onToggle = toggle)

        AnimatedVisibility(
            visible = partial,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            ConsentNeededBanner(
                onReview = { confirmOn = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        AnimatedVisibility(
            visible = state.restartRequired && serverRunning,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            RestartNotice(
                onRestart = onRestart,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        AnimatedContent(
            targetState = state.status,
            transitionSpec = {
                (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                    .using(SizeTransform(clip = false) { _, _ -> tween(260) })
            },
            label = "storageBody",
        ) { status ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                when (status) {
                    SharedStorageStatus.Off -> OffBody()
                    SharedStorageStatus.NeedsPermission ->
                        NeedsPermissionBody(onAllowAccess = onAllowAccess, onCheckAgain = onCheckAgain, partial = partial)
                    SharedStorageStatus.Active -> ActiveBody(hostRoot = state.hostRoot, partial = partial)
                }
            }
        }
    }

    if (confirmOn) {
        EnableSharedStorageDialog(
            hostRoot = state.hostRoot,
            onConfirm = {
                confirmOn = false
                onEnabledChange(true)
            },
            onDismiss = { confirmOn = false },
        )
    }

    if (confirmOff) {
        ConfirmDialog(
            title = stringResource(R.string.storage_off_dialog_title),
            // Partial mode never shared Codex or Claude Code, so there is nothing about them to say.
            body = stringResource(if (partial) R.string.storage_off_dialog_body_partial else R.string.storage_off_dialog_body),
            confirmLabel = stringResource(R.string.turn_off),
            onConfirm = { onEnabledChange(false) },
            onDismiss = { confirmOff = false },
        )
    }
}

@Composable
private fun StorageHeader(
    status: SharedStorageStatus,
    enabled: Boolean,
    /** On, but only projects and OpenCode (see [SharedStorageState.needsConsent]). */
    partial: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val target =
        when (status) {
            SharedStorageStatus.Off -> MaterialTheme.colorScheme.onSurfaceVariant
            SharedStorageStatus.NeedsPermission -> ChamberTheme.colors.warning
            SharedStorageStatus.Active -> ChamberTheme.colors.success
        }
    val tone by animateColorAsState(target, label = "storageTone")
    Row(
        // The whole header toggles, so the switch has a large touch target and one label.
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(tint = if (status == SharedStorageStatus.Off) MaterialTheme.colorScheme.onSurfaceVariant else tone) {
            Icon(
                if (status == SharedStorageStatus.Active) Icons.Rounded.FolderOpen else Icons.Rounded.Folder,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.storage_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    when (status) {
                        SharedStorageStatus.Off -> stringResource(R.string.storage_off)
                        SharedStorageStatus.NeedsPermission -> stringResource(R.string.storage_needs_access)
                        SharedStorageStatus.Active ->
                            stringResource(if (partial) R.string.storage_on_partial else R.string.storage_on)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = tone,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = null)
    }
}

@Composable
private fun RestartNotice(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One tap is enough; the notice goes away once the server stops.
    var requested by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.restart_to_apply),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            TextButton(
                onClick = {
                    requested = true
                    onRestart()
                },
                enabled = !requested,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Text(stringResource(R.string.restart))
            }
        }
    }
}

@Composable
private fun OffBody() {
    Text(
        text = codeSpans(stringResource(R.string.storage_intro), stringResource(R.string.storage_folder_name)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Worth knowing before turning it on, not only after.
    Spacer(Modifier.height(10.dp))
    ExposureWarning(prospective = true)
    Spacer(Modifier.height(10.dp))
    NoteRow(
        icon = Icons.Rounded.Info,
        text = codeSpans(stringResource(R.string.storage_caveat), stringResource(R.string.guest_private_projects), "node_modules"),
    )
}

@Composable
private fun NeedsPermissionBody(
    onAllowAccess: () -> Unit,
    onCheckAgain: () -> Unit,
    /** Legacy partial mode: the banner above already explains; no claim about Codex/Claude here. */
    partial: Boolean = false,
) {
    // Access can be granted while the folder still can't be created (storage unavailable). Asking
    // for access again wouldn't help then, so say what is actually wrong. Re-checked on resume.
    val context = LocalContext.current
    val inspection = LocalInspectionMode.current
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val unavailable by produceState(initialValue = false, checks) {
        if (inspection || !hasStorageAccess(context)) {
            value = false
            return@produceState
        }
        // Right after access is granted the status is still catching up (the controller refreshes
        // on resume too), so only conclude the folder is the problem once it has had a moment.
        if (!value) {
            delay(STATUS_SETTLE_MS)
            value = true
        }
    }
    if (unavailable) {
        Text(
            text = codeSpans(stringResource(R.string.storage_unavailable), stringResource(R.string.storage_folder_name)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        FilledTonalButton(
            onClick = onCheckAgain,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.heightIn(min = 44.dp),
        ) {
            Text(stringResource(R.string.check_again))
        }
        return
    }

    // Android 11+ grants "All files access" in system settings; older versions show a dialog.
    val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    if (!partial) {
        // Consent is given; the folders become public once access is granted.
        ExposureWarning(prospective = true)
        Spacer(Modifier.height(12.dp))
    }
    Text(
        text = if (allFiles) stringResource(R.string.storage_needs_all_files) else stringResource(R.string.storage_needs_legacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    FilledTonalButton(
        onClick = onAllowAccess,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Text(stringResource(R.string.allow_access))
        if (allFiles) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    NoteRow(
        icon = Icons.Rounded.Shield,
        text = AnnotatedString(if (allFiles) stringResource(R.string.storage_others_all_files) else stringResource(R.string.storage_others_legacy)),
    )
}

@Composable
private fun ActiveBody(
    hostRoot: String,
    /** Legacy partial mode: only projects/ and opencode/ are live; Codex and Claude stay in the app. */
    partial: Boolean = false,
) {
    if (!partial) {
        // The risk first: it is live, not a past choice.
        ExposureWarning()
        Spacer(Modifier.height(12.dp))
    }
    FolderLayout(hostRoot = hostRoot, partial = partial)
    Spacer(Modifier.height(12.dp))
    NoteRow(
        icon = Icons.Rounded.Lock,
        text =
            codeSpans(
                stringResource(if (partial) R.string.storage_kept_private_partial else R.string.storage_kept_private),
                stringResource(R.string.guest_private_projects),
                stringResource(R.string.guest_codex),
                stringResource(R.string.guest_claude),
                "~/.claude.json",
                "~/.local/share/opencode",
                "node_modules",
            ),
    )
    Spacer(Modifier.height(6.dp))
    NoteRow(
        icon = Icons.Rounded.Info,
        text = codeSpans(stringResource(R.string.storage_caveat), stringResource(R.string.guest_private_projects), "node_modules"),
    )
}

/** The shared folder and what each part becomes inside T3 Code, drawn as a small tree. */
@Composable
private fun FolderLayout(
    hostRoot: String,
    partial: Boolean = false,
) {
    val copy = rememberCopyAction()
    val rail = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                // Small mono so /storage/emulated/0/CoCode fits one line on a 360dp phone.
                Text(
                    text = hostRoot,
                    style = ChamberTextStyles.codeSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { copy(hostRoot) }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_folder_path),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Only what is actually mapped: partial mode never lists codex/ or claude/ as active.
            val folders = sharedFolders().filterNot { partial && it.fullModeOnly }
            folders.forEachIndexed { index, folder ->
                FolderRow(
                    folder = folder,
                    railColor = rail,
                    first = index == 0,
                    last = index == folders.lastIndex,
                )
            }
        }
    }
}

/** One shared folder: its name in CoCode/, where the guest sees it, and what is in it. */
private data class SharedFolder(
    val host: String,
    val guest: String,
    val caption: String,
    /** Holds sign-ins and history: drawn in the warning colour. */
    val sensitive: Boolean,
    /** Shared only after the full-sharing consent; never listed in the legacy partial mode. */
    val fullModeOnly: Boolean = false,
)

@Composable
private fun sharedFolders(): List<SharedFolder> =
    listOf(
        SharedFolder(stringResource(R.string.storage_projects_dir), stringResource(R.string.guest_projects), stringResource(R.string.storage_projects_caption), false),
        SharedFolder(stringResource(R.string.storage_opencode_dir), stringResource(R.string.guest_opencode), stringResource(R.string.storage_opencode_caption), false),
        // Codex shares config only (linked from ~/.codex); its sign-in and sessions stay in the app.
        SharedFolder(stringResource(R.string.storage_codex_dir), stringResource(R.string.guest_codex_shared), stringResource(R.string.storage_codex_caption), sensitive = false, fullModeOnly = true),
        // Claude Code is shared in full, sign-ins included.
        SharedFolder(stringResource(R.string.storage_claude_dir), stringResource(R.string.guest_claude), stringResource(R.string.storage_claude_caption), sensitive = true, fullModeOnly = true),
    )

@Composable
private fun FolderRow(
    folder: SharedFolder,
    railColor: Color,
    first: Boolean,
    last: Boolean,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .drawBehind {
                    // Same thin rail as the setup steps: a trunk from the root icon and a short branch.
                    val stroke = 1.5.dp.toPx()
                    val trunk = 9.dp.toPx()
                    val branch = 19.dp.toPx()
                    val (x, branchEnd) =
                        if (layoutDirection == LayoutDirection.Ltr) {
                            trunk to branch
                        } else {
                            (size.width - trunk) to (size.width - branch)
                        }
                    // The branch meets the first line (name and guest path), not the caption.
                    val mid = 15.dp.toPx()
                    val top = if (first) -RailReach.toPx() else 0f
                    drawLine(railColor, Offset(x, top), Offset(x, if (last) mid else size.height), stroke, StrokeCap.Round)
                    drawLine(railColor, Offset(x, mid), Offset(branchEnd, mid), stroke, StrokeCap.Round)
                },
        verticalAlignment = Alignment.Top,
    ) {
        Spacer(Modifier.width(26.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 6.dp, bottom = 4.dp, end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = folder.host, style = ChamberTextStyles.code, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = folder.guest, style = ChamberTextStyles.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.Top) {
                if (folder.sensitive) {
                    Icon(
                        Icons.Rounded.Key,
                        contentDescription = null,
                        tint = ChamberTheme.colors.warning,
                        modifier = Modifier.padding(top = 2.dp, end = 4.dp).size(12.dp),
                    )
                }
                Text(
                    text = folder.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (folder.sensitive) ChamberTheme.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The standing warning: Codex and Claude Code sign-ins and sessions are in the public folder and
 * other apps with storage access can read them. Drawn as an error-tinted box so it is never mistaken
 * for a footnote.
 */
@Composable
private fun ExposureWarning(
    modifier: Modifier = Modifier,
    /** Not on yet: says what turning it on does, rather than what is in the folder now. */
    prospective: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(if (prospective) R.string.storage_public_warning_off else R.string.storage_public_warning),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Legacy users: shared storage is on, but only for projects/ and opencode/. Explains that, and offers
 * the full Codex and Claude Code sharing as an explicit choice (it opens the consent dialog).
 */
@Composable
private fun ConsentNeededBanner(
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(stringResource(R.string.storage_consent_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text =
                            codeSpans(
                                stringResource(R.string.storage_consent_body),
                                stringResource(R.string.storage_projects_dir),
                                stringResource(R.string.storage_opencode_dir),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onReview,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                ) {
                    Text(stringResource(R.string.storage_consent_action), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Asked before shared storage is turned on. Shows exactly which folders become public and where the
 * agents see them, warns in plain words that sign-ins and sessions are included, and only enables
 * Turn on once the user ticks that they understand. Cancel changes nothing.
 */
@Composable
private fun EnableSharedStorageDialog(
    hostRoot: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = { Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.storage_on_dialog_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.storage_on_dialog_intro), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(text = hostRoot, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurface)
                        sharedFolders().forEach { folder ->
                            Text(
                                text = "  " + folder.host + "  →  " + folder.guest,
                                style = ChamberTextStyles.codeSmall,
                                color = if (folder.sensitive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.storage_on_dialog_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.storage_on_dialog_rest),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .toggleable(value = acknowledged, role = Role.Checkbox, onValueChange = { acknowledged = it })
                            .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = acknowledged, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.storage_on_dialog_ack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = acknowledged,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.storage_on_dialog_confirm), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun NoteRow(
    icon: ImageVector,
    text: AnnotatedString,
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.5.dp).size(14.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [text] with every occurrence of [tokens] set in the monospace face (paths, folder names). */
private fun codeSpans(
    text: String,
    vararg tokens: String,
): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (token in tokens) {
            var start = text.indexOf(token)
            while (start >= 0) {
                addStyle(SpanStyle(fontFamily = MonoFamily), start, start + token.length)
                start = text.indexOf(token, start + token.length)
            }
        }
    }
