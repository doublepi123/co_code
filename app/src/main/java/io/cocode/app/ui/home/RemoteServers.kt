package io.cocode.app.ui.home

import androidx.compose.ui.res.stringResource
import io.cocode.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import io.cocode.app.ui.AddressCheck
import io.cocode.app.ui.checkServerAddress
import io.cocode.app.ui.components.ChamberCard
import io.cocode.app.ui.components.ConfirmDialog
import io.cocode.app.ui.components.IconTile
import io.cocode.app.ui.displayAddress
import io.cocode.app.ui.isLoopbackHost
import io.cocode.app.ui.label
import io.cocode.app.ui.theme.ChamberTextStyles
import io.cocode.app.ui.theme.ChamberTheme
import java.util.UUID

/**
 * The saved servers, in the user's order. With two or more, a row can be moved: touch and hold,
 * then drag (the others step aside, release settles it), or Move up / Move down in its menu and
 * as accessibility actions. [onMove] is called once per move, with the server's new index.
 */
@Composable
internal fun RemoteServersSection(
    servers: List<ServerTarget.Remote>,
    onOpen: (ServerTarget.Remote) -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerTarget.Remote) -> Unit,
    onRemove: (ServerTarget.Remote) -> Unit,
    modifier: Modifier = Modifier,
    onMove: (server: ServerTarget.Remote, toIndex: Int) -> Unit = { _, _ -> },
) {
    if (servers.isEmpty()) {
        EmptyServersCard(onAdd = onAdd, modifier = modifier)
        return
    }
    val order = rememberServerOrderState()
    val currentServers by rememberUpdatedState(servers)
    val currentOnMove by rememberUpdatedState(onMove)
    val savedIds = servers.map { it.id }
    LaunchedEffect(savedIds) { order.onSavedOrder(savedIds) }
    val shown = order.shownOrder(servers) { it.id }
    val shownIds = shown.map { it.id }
    SideEffect {
        order.order = shownIds
        order.onCommit = { id, to -> currentServers.firstOrNull { it.id == id }?.let { currentOnMove(it, to) } }
    }
    val reorderable = shown.size >= 2
    // Dividers would stay put while rows slide past them, so they fade out during a move.
    val dividerAlpha by animateFloatAsState(if (order.raisedId == null) 1f else 0f, label = "dividers")

    ChamberCard(modifier = modifier) {
        shown.forEachIndexed { index, server ->
            key(server.id) {
                if (index > 0) {
                    HorizontalDivider(
                        color = ChamberTheme.colors.cardBorder,
                        modifier = Modifier.padding(start = 68.dp).graphicsLayer { alpha = dividerAlpha },
                    )
                }
                RemoteServerRow(
                    server = server,
                    onOpen = { onOpen(server) },
                    onEdit = { onEdit(server) },
                    onRemove = { onRemove(server) },
                    onMoveUp = if (reorderable && index > 0) ({ order.move(server.id, index - 1) }) else null,
                    onMoveDown = if (reorderable && index < shown.lastIndex) ({ order.move(server.id, index + 1) }) else null,
                    reorder = if (reorderable) order else null,
                )
            }
        }
    }
}

@Composable
private fun EmptyServersCard(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChamberCard(modifier = modifier) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            IconTile {
                Icon(Icons.Rounded.Computer, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.empty_servers_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.empty_servers_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = onAdd, shape = MaterialTheme.shapes.medium) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_server))
                }
            }
        }
    }
}

@Composable
private fun RemoteServerRow(
    server: ServerTarget.Remote,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    /** Null at the top (or with a single server): no Move up. */
    onMoveUp: (() -> Unit)? = null,
    /** Null at the bottom (or with a single server): no Move down. */
    onMoveDown: (() -> Unit)? = null,
    /** Null when the list can't be reordered (one server). */
    reorder: ServerOrderState? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dragging = reorder?.draggingId == server.id
    val raised = reorder?.raisedId == server.id
    val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "lift")
    val moveUpLabel = stringResource(R.string.move_up)
    val moveDownLabel = stringResource(R.string.move_down)
    val rowShape = MaterialTheme.shapes.medium
    Surface(
        onClick = onOpen,
        // Opaque while it moves, so it passes over the other rows; a lighter tone while held.
        color =
            when {
                dragging -> MaterialTheme.colorScheme.surfaceContainerHigh
                raised -> ChamberTheme.colors.card
                else -> Color.Transparent
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .zIndex(if (raised) 1f else 0f)
                .then(
                    if (reorder == null) {
                        Modifier
                    } else {
                        Modifier
                            .onPlaced { reorder.place(server.id, it.positionInParent().y, it.size.height) }
                            .graphicsLayer {
                                translationY = reorder.offsetOf(server.id)
                                shadowElevation = lift * 8.dp.toPx()
                                shape = rowShape
                                clip = lift > 0f
                            }.reorderOnHold(server.id, reorder)
                    },
                ).semantics {
                    customActions =
                        listOfNotNull(
                            onMoveUp?.let { move -> CustomAccessibilityAction(moveUpLabel) { move(); true } },
                            onMoveDown?.let { move -> CustomAccessibilityAction(moveDownLabel) { move(); true } },
                        )
                },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(tint = MaterialTheme.colorScheme.tertiary) {
                Icon(Icons.Rounded.Computer, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!server.uiPassword.isNullOrEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = stringResource(R.string.password_saved),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Text(
                    // The Cox instance and its address.
                    text = server.kind.label() + "  ·  " + displayAddress(server.baseUrl),
                    style = ChamberTextStyles.codeSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (reorder != null) {
                // A cue only: the whole row is held to move it, and screen readers get actions.
                Icon(
                    Icons.Rounded.DragIndicator,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f + 0.55f * lift),
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    onMoveUp?.let { move ->
                        DropdownMenuItem(
                            text = { Text(moveUpLabel) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                move()
                            },
                        )
                    }
                    onMoveDown?.let { move ->
                        DropdownMenuItem(
                            text = { Text(moveDownLabel) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                move()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** Remove confirmation for a saved server. */
@Composable
internal fun RemoveServerDialog(
    server: ServerTarget.Remote,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(R.string.remove_server_title, server.displayName),
        body = stringResource(R.string.remove_server_body),
        confirmLabel = stringResource(R.string.remove),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
    )
}

/**
 * Add or edit a remote server. The address is normalized as the user types (http:// is added
 * when no scheme is given) and the result is previewed below the field.
 */
@Composable
internal fun ServerEditorDialog(
    initial: ServerTarget.Remote?,
    onSave: (ServerTarget.Remote) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(initial?.displayName.orEmpty()) }
    // Not saveable on purpose: the password must not end up in saved instance state.
    var password by remember { mutableStateOf(initial?.uiPassword.orEmpty()) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var allowInsecure by rememberSaveable { mutableStateOf(initial?.allowInsecureHttp == true) }
    val check = remember(address) { checkServerAddress(address) }
    val addressError: String? =
        when (check) {
            is AddressCheck.Invalid -> stringResource(check.messageRes)
            AddressCheck.Empty -> if (showErrors) stringResource(R.string.address_required) else null
            is AddressCheck.Valid -> null
        }
    // Plain http to a non-loopback host sends the UI password (and session) unencrypted.
    val insecureRemote = (check as? AddressCheck.Valid)?.let { it.url.startsWith("http://") && !isLoopbackHost(it.host) } == true
    val insecureBlocked = insecureRemote && password.isNotEmpty() && !allowInsecure
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (initial == null) runCatching { focus.requestFocus() }
    }

    fun submit() {
        val valid = check as? AddressCheck.Valid
        if (valid == null || insecureBlocked) {
            showErrors = true
            return
        }
        onSave(
            ServerTarget.Remote(
                id = initial?.id ?: UUID.randomUUID().toString(),
                displayName = name.trim().ifEmpty { valid.host },
                baseUrl = valid.url,
                uiPassword = password.takeIf { it.isNotEmpty() },
                allowInsecureHttp = insecureRemote && allowInsecure,
                kind = ServerKind.T3,
            ),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                if (initial == null) stringResource(R.string.add_server) else stringResource(R.string.edit_server),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            // Scrolls when the keyboard leaves little room on small screens.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    label = { Text(stringResource(R.string.field_address)) },
                    placeholder = { Text(stringResource(R.string.field_address_placeholder), style = ChamberTextStyles.code) },
                    textStyle = ChamberTextStyles.code.copy(color = MaterialTheme.colorScheme.onSurface),
                    singleLine = true,
                    isError = addressError != null,
                    supportingText = {
                        val text =
                            addressError
                                ?: (check as? AddressCheck.Valid)?.let { stringResource(R.string.address_preview, it.url) }
                                ?: stringResource(R.string.address_hint)
                        Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
                )
                if (insecureRemote) {
                    Column {
                        Text(
                            stringResource(R.string.insecure_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { allowInsecure = !allowInsecure }
                                    .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = allowInsecure, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.allow_insecure),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (insecureBlocked && showErrors) {
                            Text(
                                stringResource(R.string.insecure_needs_confirm),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_name)) },
                    placeholder = {
                        (check as? AddressCheck.Valid)?.let { Text(it.host) }
                    },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Next,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.field_password)) },
                    supportingText = { Text(stringResource(R.string.password_hint)) },
                    singleLine = true,
                    visualTransformation =
                        if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (passwordVisible) stringResource(R.string.hide_password) else stringResource(R.string.show_password),
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
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }) {
                Text(if (initial == null) stringResource(R.string.add) else stringResource(R.string.save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
