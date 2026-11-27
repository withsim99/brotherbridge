package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.PrintBridgeApp
import com.example.R
import com.example.model.LogCategory
import com.example.model.MdnsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PrintServerService : Service() {

    companion object {
        const val ACTION_START = "com.aistudio.printbridge.action.START_SERVER"
        const val ACTION_STOP = "com.aistudio.printbridge.action.STOP_SERVER"
        private const val CHANNEL_ID = "printbridge_ipp_server"
        private const val NOTIFICATION_ID = 2520
        private const val KEEP_ALIVE_TICK_MS = 40_000L

        fun start(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PrintServerService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var screenAndApReceiver: BroadcastReceiver? = null
    private var statusObserverJob: Job? = null
    private var keepAliveJob: Job? = null
    private var hasSeenServerRunning = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = (application as PrintBridgeApp).spoolerEngine

        if (intent?.action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        val initialNotif = buildNotification(
            subtitle = "Starting IPP server & screen-off keep-alive...",
        )
        if (!startForegroundCompat(initialNotif)) {
            stopSelf()
            return START_NOT_STICKY
        }

        // If the OS restarted this sticky service after process death, re-scan USB first
        if (intent == null && !engine.canHostRemoteServer()) {
            engine.usbPrinterManager.scanForPrinters()
        }

        if (!engine.canHostRemoteServer()) {
            engine.onLog(
                LogCategory.IPP,
                "Stopping background service because no host printer is connected",
                false,
            )
            stopServerAndService()
            return START_NOT_STICKY
        }

        acquireKeepAliveLocks()
        registerWifiNetworkCallback()
        registerScreenAndApReceiver()

        if (!engine.serverState.value.isRunning) {
            val startResult = engine.startServer()
            if (startResult.isFailure) {
                stopServerAndService()
                return START_NOT_STICKY
            }
        }

        hasSeenServerRunning = engine.serverState.value.isRunning
        observeStatusUpdates()
        startScreenOffKeepAliveLoop()
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
    }

    private fun acquireKeepAliveLocks() {
        val engine = (application as PrintBridgeApp).spoolerEngine

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (wakeLock == null && powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "PrintBridge:IppServerWakeLock",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiLock == null && wifiManager != null) {
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(lockMode, "PrintBridge:IppServerWifiLock").apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }

        // Dedicated MulticastLock owned by foreground service so Android Wi-Fi chip does not
        // re-enable multicast packet filtering when the host screen turns off.
        if (multicastLock == null && wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("PrintBridge:ServiceMulticastLock").apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }

        val wakeHeld = wakeLock?.isHeld == true
        val wifiHeld = wifiLock?.isHeld == true
        val mcastHeld = multicastLock?.isHeld == true
        engine.updateKeepAliveLockState(wakeLockActive = wakeHeld, wifiLockActive = wifiHeld)
        engine.onLog(
            LogCategory.IPP,
            "Keep-alive locks active (CPU=${if (wakeHeld) "ON" else "OFF"}, Wi-Fi=${if (wifiHeld) "ON" else "OFF"}, Multicast=${if (mcastHeld) "ON" else "OFF"})",
            false,
        )
    }

    private fun releaseKeepAliveLocks() {
        val engine = (application as PrintBridgeApp).spoolerEngine
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        engine.updateKeepAliveLockState(wakeLockActive = false, wifiLockActive = false)
    }

    private fun registerScreenAndApReceiver() {
        if (screenAndApReceiver != null) return
        val engine = (application as PrintBridgeApp).spoolerEngine
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // Re-assert CPU, Wi-Fi low-latency, and Multicast locks as screen sleeps
                        reassertLocksQuietly()
                        engine.onScreenOffKeepAliveTick(forceMdnsRefresh = true)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        reassertLocksQuietly()
                        engine.onScreenOffKeepAliveTick(forceMdnsRefresh = false)
                    }
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        engine.onWifiNetworkChanged()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenAndApReceiver = receiver
        }
    }

    private fun unregisterScreenAndApReceiver() {
        val receiver = screenAndApReceiver ?: return
        screenAndApReceiver = null
        runCatching { unregisterReceiver(receiver) }
    }

    private fun reassertLocksQuietly() {
        runCatching { if (wakeLock?.isHeld == false) wakeLock?.acquire() }
        runCatching { if (wifiLock?.isHeld == false) wifiLock?.acquire() }
        runCatching { if (multicastLock?.isHeld == false) multicastLock?.acquire() }
    }

    private fun startScreenOffKeepAliveLoop() {
        keepAliveJob?.cancel()
        val engine = (application as PrintBridgeApp).spoolerEngine
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        keepAliveJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(KEEP_ALIVE_TICK_MS)
                if (!engine.serverState.value.isRunning) break
                reassertLocksQuietly()
                val screenOff = pm?.isInteractive == false
                engine.onScreenOffKeepAliveTick(forceMdnsRefresh = screenOff)
            }
        }
    }

    private fun registerWifiNetworkCallback() {
        if (wifiNetworkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val engine = (application as PrintBridgeApp).spoolerEngine

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                engine.onWifiNetworkChanged()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                engine.onWifiNetworkChanged()
            }

            override fun onLost(network: Network) {
                engine.refreshNetworkInfo()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        runCatching {
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
        }.onFailure { err ->
            engine.onLog(LogCategory.MDNS, "Wi-Fi callback registration skipped: ${err.message}", false)
        }
    }

    private fun unregisterWifiNetworkCallback() {
        val callback = wifiNetworkCallback ?: return
        wifiNetworkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(callback) }
    }

    private fun observeStatusUpdates() {
        statusObserverJob?.cancel()
        val engine = (application as PrintBridgeApp).spoolerEngine
        val notifManager = getSystemService(NotificationManager::class.java)

        statusObserverJob = serviceScope.launch {
            combine(engine.serverState, engine.activePrinter, engine.connectedClients) { net, printer, clients ->
                Triple(net, printer, clients.size)
            }.collect { (net, printer, clientCount) ->
                if (net.isRunning) {
                    hasSeenServerRunning = true
                    val mdnsTag = if (net.mdnsStatus == MdnsStatus.ADVERTISING) "Bonjour ON" else "mDNS ${net.mdnsStatus.label}"
                    val lockTag = if (net.wakeLockActive && net.wifiLockActive) "Screen-Off Ready" else "Keep-Alive Partial"
                    val clientTag = if (clientCount > 0) " • $clientCount device${if (clientCount == 1) "" else "s"}" else ""
                    val subtitle = "${net.ippUri} • $mdnsTag • $lockTag$clientTag • ${printer.displayName}"
                    notifManager?.notify(NOTIFICATION_ID, buildNotification(subtitle))
                } else if (hasSeenServerRunning) {
                    stopServerAndService()
                }
            }
        }
    }

    private fun stopServerAndService() {
        statusObserverJob?.cancel()
        statusObserverJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        hasSeenServerRunning = false
        unregisterScreenAndApReceiver()
        unregisterWifiNetworkCallback()
        releaseKeepAliveLocks()
        val engine = (application as PrintBridgeApp).spoolerEngine
        if (engine.serverState.value.isRunning) {
            engine.stopServer()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        statusObserverJob?.cancel()
        statusObserverJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        unregisterScreenAndApReceiver()
        unregisterWifiNetworkCallback()
        releaseKeepAliveLocks()
        serviceScope.cancel()
        val engine = (application as PrintBridgeApp).spoolerEngine
        if (engine.serverState.value.isRunning) {
            engine.stopServer()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(subtitle: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = Intent(this, PrintServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notif_server_running_title))
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPending)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_server_desc)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
