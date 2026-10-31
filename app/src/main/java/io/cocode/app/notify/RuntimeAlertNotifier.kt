package io.cocode.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.cocode.app.MainActivity
import io.cocode.app.R

/**
 * Posts CoCode's own runtime alerts (see [RuntimeErrorWatch]). These are the only native
 * notifications the app posts besides the foreground service; the web UI's agent events have no
 * native notification bridge.
 */
class RuntimeAlertNotifier(
    private val context: Context,
) {
    /**
     * Creates the channel, and re-applies its name on every call. A channel's name is fixed when it
     * is created, so a change of system language would otherwise leave the old name shown in
     * settings.
     */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val label = context.getString(R.string.channel_errors)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_HIGH))
        } else if (existing.name != label) {
            // Same id, new name: the user's importance and mute settings are kept.
            existing.name = label
            manager.createNotificationChannel(existing)
        }
    }

    /** Posts a runtime alert. Tapping it opens the app; the same key replaces the earlier alert. */
    fun postAlert(alert: RuntimeAlert) {
        if (!canPost()) return
        ensureChannel()
        val id = notificationId("alert:${alert.key}")
        val contentIntent =
            PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(Color.parseColor(ACCENT))
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private companion object {
        const val CHANNEL_ID = "errors"
        const val ACCENT = "#EDB449"
    }
}

/**
 * Stable notification id for a key. Same key → same id, so a later alert replaces the earlier one.
 * FNV-1a over the UTF-8 bytes, kept non-negative for NotificationManager.
 */
internal fun notificationId(key: String): Int {
    var hash = -0x7ee3623b // FNV-1a offset basis 0x811c9dc5, as a signed Int
    for (byte in key.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toInt() and 0xff)
        hash *= 0x01000193
    }
    return hash and 0x7fffffff
}
