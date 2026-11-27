package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PrintBridgeApp
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight foreground service active ONLY while a scan is running in the background (either
 * direct USB OTG or 1-second wireless polling & verified handover), allowing the user to navigate
 * away or leave the app without interrupting the scan.
 */
class ScanBackgroundService : Service() {

    companion object {
        const val ACTION_START = "com.brotherbridge.action.START_BG_SCAN"
        const val ACTION_CANCEL = "com.brotherbridge.action.CANCEL_BG_SCAN"
        const val ACTION_STOP = "com.brotherbridge.action.STOP_BG_SCAN"
        const val EXTRA_OPEN_SCAN_SCREEN = "com.brotherbridge.extra.OPEN_SCAN_SCREEN"
        private const val CHANNEL_ID = "printbridge_bg_scan"
        private const val NOTIFICATION_ID = 2521

        fun start(context: Context) {
            val intent = Intent(context, ScanBackgroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScanBackgroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LiveActivityNotificationHelper.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as PrintBridgeApp).spoolerEngine

        when (intent?.action) {
            ACTION_CANCEL -> {
                engine.cancelBackgroundScan()
                stopSelfCleanly()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
        }

        val current = engine.backgroundScanState.value
        val notif = buildNotification(
            status = current.statusText.ifBlank { getString(R.string.notif_bg_scan_running) },
            progressPct = (current.progress * 100f).toInt().coerceIn(0, 100),
            sessionTitle = current.sessionTitle,
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notif)
            }
        }.onFailure {
            stopSelf()
            return START_NOT_STICKY
        }

        observeScanProgress()
        return START_NOT_STICKY
    }

    private fun observeScanProgress() {
        observerJob?.cancel()
        val engine = (application as PrintBridgeApp).spoolerEngine
        val notifManager = getSystemService(NotificationManager::class.java)

        observerJob = serviceScope.launch {
            engine.backgroundScanState.collect { state ->
                if (!state.isScanning) {
                    stopSelfCleanly()
                } else {
                    val pct = (state.progress * 100f).toInt().coerceIn(0, 100)
                    val subtitle = state.statusText.ifBlank { getString(R.string.notif_bg_scan_running) }
                    notifManager?.notify(
                        NOTIFICATION_ID,
                        buildNotification(subtitle, pct, state.sessionTitle),
                    )
                }
            }
        }
    }

    private fun stopSelfCleanly() {
        observerJob?.cancel()
        observerJob = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(
        status: String,
        progressPct: Int,
        sessionTitle: String = "",
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SCAN_SCREEN, true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelIntent = Intent(this, ScanBackgroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPending = PendingIntent.getService(
            this,
            11,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val isIndeterminate = progressPct <= 2
        val chipLabel = if (isIndeterminate) "Scan" else "$progressPct%"
        val subtitle = if (isIndeterminate) status else "$status ($progressPct%)"

        return LiveActivityNotificationHelper.buildOngoingProgressNotification(
            context = this,
            smallIconRes = R.drawable.ic_notif_scan,
            title = getString(R.string.notif_bg_scan_title),
            subtitle = subtitle,
            subText = sessionTitle.ifBlank { null },
            progressPct = progressPct,
            indeterminate = isIndeterminate,
            shortChipLabel = chipLabel,
            accentColor = LiveActivityNotificationHelper.COLOR_SCAN_ACCENT,
            contentIntent = openPending,
            cancelActionLabel = getString(R.string.action_cancel),
            cancelIntent = cancelPending,
        )
    }
}
