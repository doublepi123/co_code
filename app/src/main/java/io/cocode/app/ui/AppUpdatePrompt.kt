package io.cocode.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cocode.app.BuildConfig
import io.cocode.app.R
import io.cocode.app.ui.components.CoCodeMark
import io.cocode.app.ui.theme.ChamberTextStyles
import io.cocode.app.ui.theme.MonoFamily
import io.cocode.app.update.AppReleaseChecker
import io.cocode.app.update.AppReleaseUpdate
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext

/**
 * One checker for the whole process, so a dismissed version and the check cooldown survive a
 * rotation or a recreated activity. Created on first use, never in previews.
 */
private object AppUpdates {
    val checker: AppReleaseChecker by lazy {
        AppReleaseChecker(
            client = GlobalContext.get().get<OkHttpClient>(),
            currentVersion = BuildConfig.VERSION_NAME,
        )
    }
}

/**
 * Asks GitHub for a newer CoCode release each time the app comes to the front (the checker keeps a
 * cooldown and stays silent offline), and shows the offer as a dialog over whatever is on screen:
 * Home or a server's page. [ready] holds the dialog back while the launch cover is up.
 *
 * Only points at the release page; nothing is downloaded or installed by the app.
 */
@Composable
internal fun AppUpdatePrompt(ready: Boolean) {
    if (LocalInspectionMode.current) return
    val checker = remember { AppUpdates.checker }
    val scope = rememberCoroutineScope()
    // The check runs on OkHttp's threads; nothing here waits for it.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch { checker.check() } }

    val update by checker.available.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shown = update
    if (ready && shown != null) {
        AppUpdateDialog(
            update = shown,
            currentVersion = BuildConfig.VERSION_NAME,
            onLater = checker::dismiss,
            onViewRelease = {
                // Hidden for this run either way: the user has seen it and knows where it is.
                checker.dismiss()
                openReleasePage(context, shown.pageUrl)
            },
        )
    }
}

/** The update offer: versions, the start of the release notes, Later and View release. */
@Composable
internal fun AppUpdateDialog(
    update: AppReleaseUpdate,
    currentVersion: String,
    onLater: () -> Unit,
    onViewRelease: () -> Unit,
) {
    Dialog(onDismissRequest = onLater) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                CoCodeMark(size = 40.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.app_update_title, update.version),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                val accent = MaterialTheme.colorScheme.primary
                Text(
                    text =
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontFamily = MonoFamily)) {
                                append(currentVersion)
                                append("  →  ")
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                                    append(update.version)
                                }
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_update_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notes = remember(update.notes) { releaseNotesPreview(update.notes) }
                if (notes.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.app_update_notes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = notes,
                            style = ChamberTextStyles.codeSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier =
                                Modifier
                                    .heightIn(max = 168.dp)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onLater) { Text(stringResource(R.string.app_update_later)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onViewRelease, shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.app_update_view))
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * GitHub release notes are Markdown; the dialog shows them as plain text. Drops heading and emphasis
 * markers, turns list bullets into "•", collapses blank runs and keeps at most [maxLines] lines.
 */
internal fun releaseNotesPreview(
    notes: String,
    maxLines: Int = 12,
): String {
    val lines =
        notes
            .replace("\r\n", "\n")
            .lines()
            .map { line ->
                line
                    .trimEnd()
                    .replace(HEADING, "")
                    .replace(BULLET, "$1• ")
                    .replace(LINK, "$1")
                    .replace(EMPHASIS, "")
                    .replace("`", "")
            }
    val compact = mutableListOf<String>()
    for (line in lines) {
        if (line.isBlank() && (compact.isEmpty() || compact.last().isBlank())) continue
        compact += line
    }
    while (compact.isNotEmpty() && compact.last().isBlank()) compact.removeAt(compact.lastIndex)
    if (compact.size <= maxLines) return compact.joinToString("\n")
    return (compact.take(maxLines) + "…").joinToString("\n")
}

private val HEADING = Regex("""^\s{0,3}#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*+]\s+""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val EMPHASIS = Regex("""\*\*|__""")

/** Opens the official release page in the user's browser; only https GitHub pages are opened. */
private fun openReleasePage(
    context: Context,
    url: String,
) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https" || uri.host != "github.com") return
    val intent =
        Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
    }
}
