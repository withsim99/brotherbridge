package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import com.example.R

/**
 * Builds permanent ongoing foreground notifications that also opt into Android 16+ Live Updates
 * (status-bar capsule / Dynamic Island / Now Bar / Live Alerts on supported modern devices) via
 * [NotificationCompat.Builder.setRequestPromotedOngoing], [NotificationCompat.Builder.setShortCriticalText],
 * and [NotificationCompat.ProgressStyle], while gracefully falling back to standard persistent
 * progress notifications on older Android versions.
 */
object LiveActivityNotificationHelper {

    const val CHANNEL_ID_LIVE_ACTIVITY = "printbridge_live_activity_v2"

    const val COLOR_PRINT_ACCENT = 0xFF2563EB.toInt()
    const val COLOR_SCAN_ACCENT = 0xFF10B981.toInt()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID_LIVE_ACTIVITY)
            if (existing != null) return

            // IMPORTANCE_DEFAULT (silent, no vibration) satisfies Android 16 Live Updates & OEM
            // Dynamic Island / Now Bar promotion requirements (which reject IMPORTANCE_MIN).
            val channel = NotificationChannel(
                CHANNEL_ID_LIVE_ACTIVITY,
                context.getString(R.string.notif_channel_live_activity_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_live_activity_desc)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun buildOngoingProgressNotification(
        context: Context,
        @DrawableRes smallIconRes: Int,
        title: String,
        subtitle: String,
        subText: String?,
        progressPct: Int,
        indeterminate: Boolean,
        shortChipLabel: String,
        accentColor: Int,
        contentIntent: PendingIntent,
        cancelActionLabel: String?,
        cancelIntent: PendingIntent?,
    ): Notification {
        ensureChannel(context)
        val clampedPct = progressPct.coerceIn(0, 100)
        // Android 16 status-bar / Dynamic Island chip shows full text when < 7 chars.
        val chipText = shortChipLabel.take(6)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_LIVE_ACTIVITY)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setColor(accentColor)
            .setColorized(false) // Required to be false for Android 16 Live Updates promotion
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chipText)

        if (!subText.isNullOrBlank()) {
            builder.setSubText(subText)
        }

        if (Build.VERSION.SDK_INT >= 36) {
            val style = NotificationCompat.ProgressStyle()
                .setStyledByProgress(true)
                .setProgressIndeterminate(indeterminate)
                .setProgressSegments(
                    listOf(
                        NotificationCompat.ProgressStyle.Segment(100).setColor(accentColor),
                    ),
                )
            if (!indeterminate) {
                style.setProgress(clampedPct)
            }
            builder.setStyle(style)
        } else {
            builder.setProgress(100, clampedPct, indeterminate)
        }

        if (!cancelActionLabel.isNullOrBlank() && cancelIntent != null) {
            builder.addAction(0, cancelActionLabel, cancelIntent)
        }

        val notif = builder.build()
        notif.flags = notif.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        return notif
    }
}
