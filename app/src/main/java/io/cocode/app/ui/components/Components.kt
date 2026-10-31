package io.cocode.app.ui.components

import androidx.compose.ui.res.stringResource
import io.cocode.app.R
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.cocode.app.ui.theme.ChamberTextStyles
import io.cocode.app.ui.theme.ChamberTheme

/** A visual tone for runtime/server status. */
internal enum class StatusTone { Idle, Busy, Live, Error }

@Composable
internal fun StatusTone.color(): Color =
    when (this) {
        StatusTone.Idle -> ChamberTheme.colors.idle
        StatusTone.Busy -> ChamberTheme.colors.warning
        StatusTone.Live -> ChamberTheme.colors.success
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }

/**
 * A small status dot. Busy pulses (work in progress); Live gets a steady halo, so a running
 * server doesn't keep the screen animating.
 */
@Composable
internal fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val color by animateColorAsState(tone.color(), label = "statusDotColor")
    if (tone == StatusTone.Busy) {
        val transition = rememberInfiniteTransition(label = "statusDotPulse")
        val pulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "statusDotPulseValue",
        )
        Canvas(modifier = modifier.size(size * 2.2f)) {
            val radius = size.toPx() / 2f
            drawCircle(color = color.copy(alpha = 0.4f * (1f - pulse)), radius = radius * (1f + 1.2f * pulse))
            drawCircle(color = color, radius = radius)
        }
    } else {
        val halo = tone == StatusTone.Live
        Canvas(modifier = modifier.size(size * 2.2f)) {
            val radius = size.toPx() / 2f
            if (halo) drawCircle(color = color.copy(alpha = 0.22f), radius = radius * 1.9f)
            drawCircle(color = color, radius = radius)
        }
    }
}

/** Status label in a tinted capsule: "● Running". */
@Composable
internal fun StatusPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color by animateColorAsState(tone.color(), label = "statusPillColor")
    Row(
        modifier =
            modifier
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f))
                .padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone = tone, size = 6.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Uppercase mono section header, like a terminal heading. */
@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Flat card with a hairline border; the base surface of the control room. */
@Composable
internal fun ChamberCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val border = BorderStroke(1.dp, accent?.copy(alpha = 0.45f) ?: ChamberTheme.colors.cardBorder)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = ChamberTheme.colors.card,
            border = border,
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = ChamberTheme.colors.card,
            border = border,
        ) {
            Column(content = content)
        }
    }
}

/** Label / value row for the details section. */
@Composable
internal fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = ChamberTextStyles.code,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * The CoCode mark drawn with Canvas: a phone outline with a terminal prompt (">_") on its screen.
 * Same geometry as the launcher icon (res/drawable/ic_launcher_foreground.xml) without bundling an
 * image: [color] draws the phone and cursor, [accent] the prompt chevron.
 */
@Composable
internal fun CoCodeMark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onBackground,
    accent: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 28.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        // Geometry uses the launcher icon's 108-unit canvas; the 64-unit box centred on (54, 54)
        // holds the phone (x 35..73, y 24..84) and is scaled to fill the requested size.
        val unit = this.size.minDimension / 64f
        translate(left = this.size.width / 2f, top = this.size.height / 2f) {
            scale(unit, unit, pivot = Offset.Zero) {
                translate(left = -54f, top = -54f) {
                    val outer = RoundRect(35f, 24f, 73f, 84f, CornerRadius(11f))
                    val inner = RoundRect(40f, 29f, 68f, 79f, CornerRadius(6f))
                    val screen = Path().apply { addRoundRect(inner) }
                    val frame =
                        Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRoundRect(outer)
                            addRoundRect(inner)
                        }
                    val chevron =
                        Path().apply {
                            moveTo(45.5f, 48.5f)
                            lineTo(51.5f, 54.5f)
                            lineTo(45.5f, 60.5f)
                        }
                    drawPath(screen, color.copy(alpha = 0.08f), style = Fill)
                    drawPath(frame, color, style = Fill)
                    drawRoundRect(
                        color = color.copy(alpha = 0.45f),
                        topLeft = Offset(50f, 32.25f),
                        size = Size(8f, 2.5f),
                        cornerRadius = CornerRadius(1.25f),
                    )
                    drawPath(
                        chevron,
                        accent,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Miter),
                    )
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(56.5f, 58.5f),
                        size = Size(8f, 4f),
                        cornerRadius = CornerRadius(1.25f),
                    )
                }
            }
        }
    }
}

/** Two-button confirmation dialog. [destructive] tints the confirm action red. */
@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = stringResource(R.string.cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(confirmLabel, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}

/** Rounded icon tile used at the start of list rows. */
@Composable
internal fun IconTile(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            content()
        }
    }
}
