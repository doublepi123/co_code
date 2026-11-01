package io.cocode.app.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cocode.app.R
import io.cocode.app.agentlogin.CodexLogin
import io.cocode.app.agentlogin.CodexLoginState
import io.cocode.app.ui.rememberCopyAction
import io.cocode.app.ui.theme.ChamberTextStyles
import io.cocode.app.ui.theme.ChamberTheme
import io.cocode.app.ui.theme.MonoFamily
import kotlinx.coroutines.delay
import org.koin.core.context.GlobalContext

/** The Codex agent id in the manifest; its row carries the sign-in status. */
internal const val CODEX_AGENT_ID = "codex"

/**
 * Remaining time of a device code as `m:ss` (`14:59`, `0:05`), never negative. Pure, so it is
 * unit-tested; the dialog ticks it once a second.
 */
internal fun formatCountdown(
    expiresAtMillis: Long,
    nowMillis: Long,
): String {
    val seconds = ((expiresAtMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1000L
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** True when there is a sign-in flow in progress or a result to show (not Idle). */
internal val CodexLoginState.isActive: Boolean
    get() = this != CodexLoginState.Idle

/** Work that must not be interrupted by another start. */
internal val CodexLoginState.isRunning: Boolean
    get() =
        this == CodexLoginState.Starting ||
            this is CodexLoginState.WaitingForUser ||
            this == CodexLoginState.Verifying

/** What the Codex row and the dialog need from `CodexLogin`, so tests can drive them with a fake. */
internal class CodexSignInActions(
    val start: () -> Unit,
    val signInWithApiKey: (String) -> Unit,
    val cancel: () -> Unit,
    val dismiss: () -> Unit,
    /** Opens the sign-in page; returns false when nothing can open it. */
    val openUrl: (String) -> Boolean,
    /** Copies the code; null uses the clipboard of the dialog's own window. */
    val copyCode: ((String) -> Unit)? = null,
)

/** Opens [url] in the user's browser, in its own task. False when no app can open it. */
internal fun openInBrowser(
    context: Context,
    url: String,
): Boolean {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" && uri.scheme != "http") return false
    val intent =
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        false
    }
}

/**
 * The Codex row's status, at the end of the row: unknown shows nothing (a check runs in the
 * background), signed out offers "Sign in", signed in reads "Signed in" with "Sign in again" in a
 * menu. A flow the user stepped away from shows "Continue" instead, which reopens the dialog.
 */
@Composable
internal fun CodexSignInStatus(
    signedIn: Boolean?,
    inProgress: Boolean,
    enabled: Boolean,
    onSignIn: () -> Unit,
    onContinue: () -> Unit,
) {
    when {
        inProgress ->
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.codex_continue_sign_in))
            }
        signedIn == false ->
            TextButton(onClick = onSignIn, enabled = enabled) {
                Text(stringResource(R.string.codex_sign_in), fontWeight = FontWeight.SemiBold)
            }
        signedIn == true -> {
            var menuOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = ChamberTheme.colors.success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.codex_signed_in),
                    style = MaterialTheme.typography.labelMedium,
                    color = ChamberTheme.colors.success,
                )
                IconButton(onClick = { menuOpen = true }, enabled = enabled, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.codex_sign_in_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.codex_sign_in_again)) },
                        onClick = {
                            menuOpen = false
                            onSignIn()
                        },
                    )
                }
            }
        }
        else -> Unit
    }
}

/**
 * The sign-in dialog, driven by [state]. [onHide] closes it without stopping a running flow (the
 * card then offers Continue); Cancel stops it.
 */
@Composable
internal fun CodexSignInDialog(
    state: CodexLoginState,
    actions: CodexSignInActions,
    onHide: () -> Unit,
    /** Epoch millis; a seam for tests. */
    now: () -> Long = System::currentTimeMillis,
) {
    // Cancelled needs no screen of its own: the dialog simply goes away.
    if (state == CodexLoginState.Cancelled) {
        LaunchedEffect(Unit) { actions.dismiss() }
        return
    }
    if (state == CodexLoginState.Idle) return

    val running = state.isRunning
    AlertDialog(
        // Outside taps and Back only hide a running flow; a finished one is dismissed.
        onDismissRequest = if (running) onHide else actions.dismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                text =
                    stringResource(
                        when (state) {
                            is CodexLoginState.SignedIn -> R.string.codex_signed_in_title
                            is CodexLoginState.Failed -> R.string.codex_failed_title
                            else -> R.string.codex_sign_in_title
                        },
                    ),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    CodexLoginState.Starting -> BusyRow(stringResource(R.string.codex_starting))
                    CodexLoginState.Verifying -> BusyRow(stringResource(R.string.codex_verifying))
                    is CodexLoginState.WaitingForUser -> WaitingBody(state, actions, now)
                    is CodexLoginState.SignedIn -> SignedInBody(state.detail)
                    is CodexLoginState.Failed -> FailedBody(state)
                    CodexLoginState.Idle, CodexLoginState.Cancelled -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is CodexLoginState.SignedIn ->
                    TextButton(onClick = actions.dismiss) {
                        Text(stringResource(R.string.codex_done), fontWeight = FontWeight.SemiBold)
                    }
                is CodexLoginState.Failed ->
                    TextButton(onClick = actions.start) {
                        Text(stringResource(R.string.try_again), fontWeight = FontWeight.SemiBold)
                    }
                else ->
                    TextButton(onClick = onHide) {
                        Text(stringResource(R.string.codex_hide))
                    }
            }
        },
        dismissButton = {
            when (state) {
                is CodexLoginState.Failed ->
                    TextButton(onClick = actions.dismiss) { Text(stringResource(R.string.codex_close)) }
                is CodexLoginState.SignedIn -> Unit
                else ->
                    TextButton(
                        onClick = actions.cancel,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
            }
        },
    )
}

@Composable
private fun BusyRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun WaitingBody(
    state: CodexLoginState.WaitingForUser,
    actions: CodexSignInActions,
    now: () -> Long,
) {
    val clipboardCopy = rememberCopyAction()
    val copy = actions.copyCode ?: clipboardCopy
    val remaining by produceState(formatCountdown(state.expiresAtMillis, now()), state.expiresAtMillis) {
        while (true) {
            value = formatCountdown(state.expiresAtMillis, now())
            if (state.expiresAtMillis <= now()) break
            delay(1_000)
        }
    }
    var useApiKey by rememberSaveable { mutableStateOf(false) }

    StepLabel(1, stringResource(R.string.codex_step_open))
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { actions.openUrl(state.url) },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
    ) {
        Text(stringResource(R.string.codex_open_page))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
    }
    Text(
        text = state.url,
        style = ChamberTextStyles.codeSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    Spacer(Modifier.height(16.dp))
    StepLabel(2, stringResource(R.string.codex_step_code))
    Spacer(Modifier.height(8.dp))
    val codeLabel = stringResource(R.string.codex_code_label, state.code)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.code,
                fontFamily = MonoFamily,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { contentDescription = codeLabel },
            )
            IconButton(onClick = { copy(state.code) }) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.codex_copy_code),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.codex_expires_in, remaining),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.codex_waiting_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(8.dp))
    if (!useApiKey) {
        TextButton(onClick = { useApiKey = true }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.codex_use_api_key))
        }
    }
    AnimatedVisibility(
        visible = useApiKey,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        ApiKeyForm(onSubmit = actions.signInWithApiKey)
    }
}

/**
 * The secondary API-key path. The key lives only in this field's state (not saveable, so it never
 * reaches saved instance state) and is handed straight to the sign-in call.
 */
@Composable
private fun ApiKeyForm(onSubmit: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val submit = {
        if (key.isNotBlank()) {
            onSubmit(key)
            key = ""
        }
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = stringResource(R.string.codex_api_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.codex_api_key_label)) },
            singleLine = true,
            textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = stringResource(if (visible) R.string.codex_hide_key else R.string.codex_show_key),
                    )
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false,
                ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = submit,
                enabled = key.isNotBlank(),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.codex_api_key_submit))
            }
        }
    }
}

@Composable
private fun StepLabel(
    number: Int,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SignedInBody(detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = ChamberTheme.colors.success,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                stringResource(R.string.codex_signed_in_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(detail, style = ChamberTextStyles.codeSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FailedBody(state: CodexLoginState.Failed) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
    val detail = state.detail
    if (!detail.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.codex_technical_details))
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f),
            )
        }
        AnimatedVisibility(visible = open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = ChamberTheme.colors.terminalBackground,
                contentColor = ChamberTheme.colors.terminalForeground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = detail,
                    style = ChamberTextStyles.log,
                    modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
    }
}

/** Builds [CodexSignInActions] on top of the real singleton, with the browser opened from [context]. */
@Composable
internal fun rememberCodexSignInActions(login: CodexLogin): CodexSignInActions {
    val context = LocalContext.current
    return remember(login, context) {
        CodexSignInActions(
            start = login::startDeviceLogin,
            signInWithApiKey = login::signInWithApiKey,
            cancel = login::cancel,
            dismiss = login::dismiss,
            openUrl = { url -> openInBrowser(context, url) },
        )
    }
}

/**
 * The app's Codex sign-in for the Home card: the singleton `CodexLogin` from Koin, its flows
 * collected here. Null in previews and when no sign-in is registered.
 */
@Composable
internal fun rememberCodexSignInUi(): CodexSignInUi? {
    val inspection = LocalInspectionMode.current
    val login =
        remember(inspection) {
            if (inspection) null else GlobalContext.getOrNull()?.getOrNull<CodexLogin>()
        } ?: return null
    val state by login.state.collectAsStateWithLifecycle()
    val signedIn by login.signedIn.collectAsStateWithLifecycle()
    val actions = rememberCodexSignInActions(login)
    return CodexSignInUi(state = state, signedIn = signedIn, actions = actions, refreshStatus = login::refreshStatus)
}
