package com.example.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PrintBridgeApp
import com.example.R
import com.example.model.PrintJobRecord
import com.example.model.PrintJobStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service active while any print job is queued, rendering, or transmitting.
 * Shows a permanent ongoing notification and promotes into Android 16+ Live Updates / Dynamic
 * Island status chips on supported modern devices, automatically clearing once printing finishes.
 */
class PrintJobForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.brotherbridge.action.START_PRINT_LIVE_NOTIF"
        const val ACTION_CANCEL_JOB = "com.brotherbridge.action.CANCEL_PRINT_JOB"
        const val ACTION_STOP = "com.brotherbridge.action.STOP_PRINT_LIVE_NOTIF"
        const val EXTRA_JOB_ID = "com.brotherbridge.extra.PRINT_JOB_ID"
        const val EXTRA_OPEN_QUEUE_SCREEN = "com.brotherbridge.extra.OPEN_QUEUE_SCREEN"
        private const val NOTIFICATION_ID = 2522

        fun start(context: Context) {
            val intent = Intent(context, PrintJobForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PrintJobForegroundService::class.java).apply {
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
            ACTION_CANCEL_JOB -> {
                val jobId = intent.getIntExtra(EXTRA_JOB_ID, -1)
                if (jobId > 0) {
                    engine.cancelJob(jobId)
                }
                val stillActive = findActiveJob(engine.jobs.value)
                if (stillActive == null) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> {
                stopSelfCleanly()
                return START_NOT_STICKY
            }
        }

        val activeJob = findActiveJob(engine.jobs.value)
        val notif = buildPrintNotification(activeJob)
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

        if (activeJob == null) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        observePrintJobs()
        return START_NOT_STICKY
    }

    private fun observePrintJobs() {
        observerJob?.cancel()
        val engine = (application as PrintBridgeApp).spoolerEngine
        val notifManager = getSystemService(NotificationManager::class.java)

        observerJob = serviceScope.launch {
            engine.jobs.collect { jobList ->
                val active = findActiveJob(jobList)
                if (active == null) {
                    stopSelfCleanly()
                } else {
                    notifManager?.notify(NOTIFICATION_ID, buildPrintNotification(active))
                }
            }
        }
    }

    private fun findActiveJob(jobs: List<PrintJobRecord>): PrintJobRecord? =
        jobs.firstOrNull {
            it.status == PrintJobStatus.TRANSMITTING ||
                it.status == PrintJobStatus.RENDERING ||
                it.status == PrintJobStatus.PENDING
        }

    private fun buildPrintNotification(job: PrintJobRecord?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_QUEUE_SCREEN, true)
        }
        val openPending = PendingIntent.getActivity(
            this,
            20,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val cancelPending = job?.let { active ->
            val cancelIntent = Intent(this, PrintJobForegroundService::class.java).apply {
                action = ACTION_CANCEL_JOB
                putExtra(EXTRA_JOB_ID, active.id)
            }
            PendingIntent.getService(
                this,
                21,
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val pct = ((job?.progress ?: 0f) * 100f).toInt().coerceIn(0, 100)
        val isIndeterminate = job == null || job.status == PrintJobStatus.PENDING || pct <= 2
        val title = job?.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.notif_print_active_title)
        val statusLabel = job?.status?.label ?: getString(R.string.notif_print_active_running)
        val subtitle = if (isIndeterminate) statusLabel else "$statusLabel • $pct%"
        val chipLabel = if (isIndeterminate) "Print" else "$pct%"

        return LiveActivityNotificationHelper.buildOngoingProgressNotification(
            context = this,
            smallIconRes = R.drawable.ic_notif_print,
            title = "${getString(R.string.notif_print_active_title)} • $title",
            subtitle = subtitle,
            subText = if (job != null && job.imposedSheets > 0) "${job.imposedSheets}p • ${job.copies}x" else null,
            progressPct = pct,
            indeterminate = isIndeterminate,
            shortChipLabel = chipLabel,
            accentColor = LiveActivityNotificationHelper.COLOR_PRINT_ACCENT,
            contentIntent = openPending,
            cancelActionLabel = if (cancelPending != null) getString(R.string.action_cancel) else null,
            cancelIntent = cancelPending,
        )
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
}
