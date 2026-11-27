package com.example.printer.spooler

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.PowerManager
import com.example.data.SettingsRepository
import com.example.model.ActivePrinterState
import com.example.model.ConnectedClientDevice
import com.example.model.DeviceBusyState
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.LogCategory
import com.example.model.MdnsStatus
import com.example.model.NetworkServerState
import com.example.model.PaperAvailability
import com.example.model.PrintJobOptions
import com.example.model.PrintJobRecord
import com.example.model.PrintJobSource
import com.example.model.PrintJobStatus
import com.example.model.PrintResolution
import com.example.model.PrintServerSettings
import com.example.model.PrinterConnectionMode
import com.example.model.PrinterDiagnostics
import com.example.model.ProtocolLogEntry
import com.example.model.ScanColorOption
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
import com.example.model.ScannerAvailabilityState
import com.example.model.SupplyStatus
import com.example.model.UsbConnectionStatus
import com.example.model.UsbPrinterInfo
import com.example.printer.engine.BrotherLaserEncoder
import com.example.printer.ipp.DeviceBusyException
import com.example.printer.ipp.IppPrintServer
import com.example.printer.ipp.IppServerDelegate
import com.example.printer.ipp.RemoteIppClient
import com.example.printer.network.DeviceIdentityHelper
import com.example.printer.network.MdnsPrintAdvertiser
import com.example.printer.network.NetworkAddressResolver
import com.example.printer.network.NetworkPrinterDiscovery
import com.example.printer.network.PrintBridgeUdpResponder
import com.example.printer.render.DocumentRenderer
import com.example.printer.render.DocumentSource
import com.example.printer.render.NUpLayoutEngine
import com.example.printer.render.SupportedDocumentKind
import com.example.printer.render.TestPageGenerator
import com.example.printer.usb.UsbPrinterManager
import com.example.scanner.BackgroundScanManager
import com.example.scanner.BackgroundScanState
import com.example.scanner.BrotherDcpL2520dScanner
import com.example.scanner.IdCardMergeSpec
import com.example.scanner.ScanExportManager
import com.example.scanner.ScanHandoverEvent
import com.example.scanner.SimpleRemoteScanClient
import com.example.scanner.SimpleScanHostService
import com.example.scanner.SimpleScanHttpResponse
import com.example.scanner.UsbConnectionState
import com.example.scanner.UsbScannerMonitor
import com.example.service.PrintJobForegroundService
import com.example.service.PrintServerService
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private sealed interface SpoolTask {
    val jobId: Int
    val options: PrintJobOptions

    data class TestPageTask(
        override val jobId: Int,
        override val options: PrintJobOptions,
    ) : SpoolTask

    data class DocumentFileTask(
        override val jobId: Int,
        val source: DocumentSource,
        val kind: SupportedDocumentKind,
        val fileSizeBytes: Long,
        override val options: PrintJobOptions,
        val deleteFileAfter: Boolean,
    ) : SpoolTask
}

class PrintSpoolerEngine(private val context: Context) : IppServerDelegate {

    private val engineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("PrintSpoolerEngine", "Uncaught background engine error", throwable)
        runCatching {
            appendLog(
                LogCategory.USB,
                "Background error recovered: ${throwable.message ?: throwable.javaClass.simpleName}",
                true,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + engineExceptionHandler)
    private val spoolDir = File(context.cacheDir, "print_spool").apply { mkdirs() }

    // Core state, locks, atomics, and queues MUST be initialized BEFORE any collaborator
    // (SimpleScanHostService, UsbPrinterManager, etc.) whose init blocks or callbacks may log
    // or read engine state during startup.
    private val deviceLock = Any()
    private var activePrintJobId: Int? = null
    private var scannerHoldsDevice: Boolean = false

    private val _deviceBusyState = MutableStateFlow(DeviceBusyState.AVAILABLE)
    val deviceBusyState: StateFlow<DeviceBusyState> = _deviceBusyState.asStateFlow()

    // Single unified USB hardware mutex shared across USB Printing, Wireless HTTP Scans, and Local USB Scans.
    // All incoming print & scan requests enqueue immediately without blocking HTTP or returning busy errors!
    private val hardwareUsbMutex = Mutex()
    private val diagnosticsMutex = Mutex()
    private val diagnosticsRefreshRunning = AtomicBoolean(false)
    @Volatile private var cachedUsbDiagnostics: PrinterDiagnostics? = null
    @Volatile private var cachedUsbDiagnosticsAtMs: Long = 0L
    @Volatile private var lastScreenOffMdnsRefreshMs: Long = 0L
    private var scannerProbeJob: Job? = null
    private var clientHeartbeatJob: Job? = null
    private var clientPruneJob: Job? = null

    private val connectedClientsMap = ConcurrentHashMap<String, ConnectedClientDevice>()
    private val _connectedClients = MutableStateFlow<List<ConnectedClientDevice>>(emptyList())
    val connectedClients: StateFlow<List<ConnectedClientDevice>> = _connectedClients.asStateFlow()

    private val _recentScansVersion = MutableStateFlow(0L)
    val recentScansVersion: StateFlow<Long> = _recentScansVersion.asStateFlow()

    private val _settings = MutableStateFlow(PrintServerSettings())
    val settings: StateFlow<PrintServerSettings> = _settings.asStateFlow()

    private val _scannerState = MutableStateFlow(ScannerAvailabilityState())
    val scannerState: StateFlow<ScannerAvailabilityState> = _scannerState.asStateFlow()

    private val _serverState = MutableStateFlow(NetworkServerState())
    val serverState: StateFlow<NetworkServerState> = _serverState.asStateFlow()

    private val _connectedRemotePrinter = MutableStateFlow<DiscoveredNetworkPrinter?>(null)
    private val remoteRestoreMutex = Mutex()
    private val remotePersistMutex = Mutex()
    @Volatile
    private var usbManuallyDisconnectedDeviceId: Int? = null
    private val _activePrinter = MutableStateFlow(ActivePrinterState())
    val activePrinter: StateFlow<ActivePrinterState> = _activePrinter.asStateFlow()

    private var activeLocalScanner: BrotherDcpL2520dScanner? = null
    private val scanCancelFlag = AtomicBoolean(false)

    private val _jobs = MutableStateFlow<List<PrintJobRecord>>(emptyList())
    val jobs: StateFlow<List<PrintJobRecord>> = _jobs.asStateFlow()

    private val _logs = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val logs: StateFlow<List<ProtocolLogEntry>> = _logs.asStateFlow()

    private val nextJobId = AtomicInteger(100)
    private val nextLogId = AtomicLong(1)
    private val canceledJobIds = ConcurrentHashMap.newKeySet<Int>()
    private val spoolQueue = Channel<SpoolTask>(capacity = Channel.UNLIMITED)

    // Collaborator managers & services (safe to invoke appendLog / read state immediately)
    val settingsRepository = SettingsRepository(context)
    val usbPrinterManager = UsbPrinterManager(context) { cat, msg, err -> appendLog(cat, msg, err) }
    val networkDiscovery = NetworkPrinterDiscovery(context) { cat, msg, err -> appendLog(cat, msg, err) }
    private val mdnsAdvertiser = MdnsPrintAdvertiser(context) { cat, msg, err -> appendLog(cat, msg, err) }
    private val udpDiscoveryResponder = PrintBridgeUdpResponder(
        payloadProvider = {
            val net = _serverState.value
            if (!net.isRunning) return@PrintBridgeUdpResponder null
            val active = _activePrinter.value
            val s = _settings.value
            JSONObject().apply {
                put("server", "PrintBridge")
                put("printerName", s.printerName)
                put("model", if (active.isConnected) active.displayName else s.printerName)
                put("port", net.port)
                put("supportsPrint", true)
                put("scannerAvailable", active.supportsScan)
                put("supportsScan", active.supportsScan)
                put("supportsAdf", active.supportsAdf)
                put("supportsAdfDuplex", active.supportsAdfDuplex)
            }
        },
        onLog = { cat, msg, err -> appendLog(cat, msg, err) },
    )
    private val ippServer = IppPrintServer(spoolDir, this)

    val usbInfo: StateFlow<UsbPrinterInfo> = usbPrinterManager.printerInfo
    val discoveredPrinters: StateFlow<List<DiscoveredNetworkPrinter>> = networkDiscovery.discoveredPrinters
    val isDiscoveringNetwork: StateFlow<Boolean> = networkDiscovery.isDiscovering

    val usbScannerMonitor: UsbScannerMonitor = UsbScannerMonitor(
        context = context,
        onDeviceAuthorized = { device, info ->
            probeAuthorizedScanner(device, info)
        },
    )

    private val simpleScanHostService = SimpleScanHostService(
        vaultRootDir = File(context.filesDir, "host_scan_vault"),
        usbManager = usbScannerMonitor.usbManager,
        readyProvider = { usbScannerMonitor.connectionState.value as? UsbConnectionState.Ready },
        modelNameProvider = {
            val active = _activePrinter.value
            if (active.isConnected) active.displayName else _settings.value.printerName
        },
        externalBusyProvider = { currentDeviceBusyState() == DeviceBusyState.PRINTING },
        onScanHardwareStateChanged = { scanning ->
            synchronized(deviceLock) {
                scannerHoldsDevice = scanning
                refreshBusyStateLocked()
            }
        },
        hardwareMutex = hardwareUsbMutex,
        onLog = { cat, msg, err -> appendLog(cat, msg, err) },
        onRecoverToLostAndFound = { pageFiles, clientLabel ->
            try {
                val saved = ScanExportManager.appendPagesToLostAndFound(
                    context = context,
                    pageFiles = pageFiles,
                    clientLabel = clientLabel,
                )
                if (saved.isSuccess) {
                    _recentScansVersion.update { it + 1L }
                    true
                } else {
                    appendLog(
                        LogCategory.SCAN,
                        "Lost/Found recovery failed: ${saved.exceptionOrNull()?.message}",
                        true,
                    )
                    false
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("PrintSpoolerEngine", "Lost/Found recovery error", t)
                appendLog(
                    LogCategory.SCAN,
                    "Lost/Found recovery skipped: ${t.message ?: t.javaClass.simpleName}",
                    true,
                )
                false
            }
        },
    )

    private val backgroundScanManager = BackgroundScanManager(
        context = context,
        appScope = scope,
        settingsRepository = settingsRepository,
        activePrinterProvider = { _activePrinter.value },
        directUsbScanRunner = { params, onProgress ->
            scanViaDirectUsb(params, holdsSessionLock = false, onProgress = onProgress)
        },
        cancelDirectUsbScan = { cancelActiveScan() },
        onLog = { cat, msg, err -> appendLog(cat, msg, err) },
    )
    val backgroundScanState: StateFlow<BackgroundScanState> = backgroundScanManager.state
    val scanHandoverEvents: SharedFlow<ScanHandoverEvent> = backgroundScanManager.handoverEvents

    init {
        usbPrinterManager.register()
        usbScannerMonitor.register()
        scope.launch {
            settingsRepository.ensureServerUuid()
            val clientId = settingsRepository.ensureClientDeviceId()
            DeviceIdentityHelper.initialize(context, clientId)
            settingsRepository.settingsFlow.collectLatest { loaded ->
                _settings.value = loaded
                _serverState.update { state ->
                    state.copy(
                        port = loaded.ippPort,
                        mdnsServiceName = if (state.isRunning) state.mdnsServiceName else loaded.printerName,
                    )
                }
            }
        }
        scope.launch {
            mdnsAdvertiser.status.collectLatest { mdnsStatus ->
                _serverState.update { it.copy(mdnsStatus = mdnsStatus) }
                _scannerState.update { it.copy(mdnsStatus = mdnsStatus) }
            }
        }
        scope.launch {
            mdnsAdvertiser.registeredName.collectLatest { regName ->
                _serverState.update { it.copy(mdnsServiceName = regName) }
            }
        }
        scope.launch {
            simpleScanHostService.isScanning.collectLatest { scanning ->
                _scannerState.update { it.copy(isScanning = scanning) }
            }
        }
        scope.launch {
            usbScannerMonitor.connectionState.collectLatest { conn ->
                when (conn) {
                    is UsbConnectionState.Ready -> {
                        _scannerState.update {
                            it.copy(
                                isAvailable = true,
                                supportsAdf = conn.capabilities.supportsAdf,
                                supportsAdfDuplex = conn.capabilities.supportsAdfDuplex,
                            )
                        }
                        recomputeActivePrinter()
                    }
                    else -> {
                        val wasAvailable = _scannerState.value.isAvailable
                        _scannerState.update {
                            it.copy(
                                isAvailable = false,
                                supportsAdf = false,
                                supportsAdfDuplex = false,
                            )
                        }
                        if (wasAvailable) {
                            simpleScanHostService.cancelAllRunningJobs()
                        }
                        recomputeActivePrinter()
                    }
                }
            }
        }
        scope.launch {
            var lastAutoStartedUsbDeviceId: Int? = null
            combine(usbInfo, _settings) { usb, loadedSettings -> usb to loadedSettings }
                .collectLatest { (usb, loadedSettings) ->
                    usbScannerMonitor.refreshDevices()
                    if (usb.isConnected) {
                        val isUsbManuallyDisconnected =
                            usbManuallyDisconnectedDeviceId != null && usbManuallyDisconnectedDeviceId == usb.deviceId
                        if (!isUsbManuallyDisconnected) {
                            // Real physical USB OTG printer always takes precedence over wireless
                            _connectedRemotePrinter.value = null
                            networkDiscovery.stopDiscovery()
                            if (loadedSettings.autoStartServerOnOtg &&
                                lastAutoStartedUsbDeviceId != usb.deviceId &&
                                !_serverState.value.isRunning
                            ) {
                                lastAutoStartedUsbDeviceId = usb.deviceId
                                refreshNetworkInfo()
                                appendLog(
                                    LogCategory.USB,
                                    "USB OTG connected (${usb.displayTitle}) — auto-starting Wi-Fi server",
                                    false,
                                )
                                PrintServerService.start(context)
                            }
                        }
                    } else {
                        usbManuallyDisconnectedDeviceId = null
                        lastAutoStartedUsbDeviceId = null
                        cachedUsbDiagnostics = null
                        cachedUsbDiagnosticsAtMs = 0L
                        if (_serverState.value.isRunning) {
                            appendLog(LogCategory.USB, "USB printer disconnected — stopping Wi-Fi IPP server", true)
                            stopServer()
                        }
                    }
                    recomputeActivePrinter()
                }
        }
        scope.launch {
            refreshNetworkInfo()
            recomputeActivePrinter()
            ensurePersistedRemotePrinterRestored(verifyReachability = true)
        }
        startSpoolWorker()
    }

    // --- Unified Connection & Mode Switching ---

    fun canHostRemoteServer(): Boolean = _activePrinter.value.canHostRemoteServer

    fun disconnectActivePrinter() {
        if (_serverState.value.isRunning) {
            stopServer()
        }
        networkDiscovery.stopDiscovery()
        val currentUsb = usbInfo.value
        if (currentUsb.isConnected) {
            usbManuallyDisconnectedDeviceId = currentUsb.deviceId
        }
        val prevRemote = _connectedRemotePrinter.value
        clientHeartbeatJob?.cancel()
        clientHeartbeatJob = null
        _connectedRemotePrinter.value = null
        recomputeActivePrinter()
        scope.launch {
            remotePersistMutex.withLock {
                if (_connectedRemotePrinter.value == null) {
                    settingsRepository.clearConnectedRemotePrinter()
                }
            }
            if (prevRemote != null) {
                RemoteIppClient.sendClientDisconnect(prevRemote.host, prevRemote.port)
            }
        }
    }

    fun connectRemotePrinter(printer: DiscoveredNetworkPrinter) {
        if (_serverState.value.isRunning) {
            stopServer()
        }
        _connectedRemotePrinter.value = printer
        networkDiscovery.stopDiscovery()
        recomputeActivePrinter()
        appendLog(
            LogCategory.IPP,
            "Connected wirelessly to '${printer.name}' (${printer.displayAddress})",
            false,
        )
        persistConnectedRemotePrinter(printer)
        startClientHeartbeat(printer)
        // Enrich scanner & PrintBridge capabilities via HTTP /api/scan/capabilities
        // AND automatically check for any running or uncollected scans for this deviceId!
        scope.launch {
            enrichAndSyncRemotePrinter(printer)
        }
    }

    private fun persistConnectedRemotePrinter(printer: DiscoveredNetworkPrinter) {
        scope.launch {
            remotePersistMutex.withLock {
                if (_connectedRemotePrinter.value?.id == printer.id) {
                    settingsRepository.saveConnectedRemotePrinter(printer)
                }
            }
        }
    }

    private suspend fun enrichAndSyncRemotePrinter(printer: DiscoveredNetworkPrinter) {
        val statusJson = RemoteIppClient.fetchPrintBridgeStatusJson(printer.host, printer.port, timeoutMs = 5_000)
        val simpleScanCaps = SimpleRemoteScanClient.probeCapabilities(printer.host, printer.port, timeoutMs = 5_000)
        val current = _connectedRemotePrinter.value
        if (current != null && current.id == printer.id) {
            val enriched = current.copy(
                isPrintBridgeServer = current.isPrintBridgeServer || statusJson != null || simpleScanCaps != null,
                supportsScan = current.supportsScan || simpleScanCaps?.available == true || statusJson?.optBoolean("scannerAvailable", false) == true,
                supportsAdf = current.supportsAdf || simpleScanCaps?.supportsAdf == true || statusJson?.optBoolean("supportsAdf", false) == true,
                supportsAdfDuplex = current.supportsAdfDuplex || simpleScanCaps?.supportsAdfDuplex == true || statusJson?.optBoolean("supportsAdfDuplex", false) == true,
                model = statusJson?.optString("model")?.takeIf { it.isNotBlank() } ?: current.model,
            )
            if (enriched != current) {
                _connectedRemotePrinter.value = enriched
                recomputeActivePrinter()
                persistConnectedRemotePrinter(enriched)
                appendLog(
                    LogCategory.SCAN,
                    "Verified remote capabilities for '${enriched.name}': print=${enriched.supportsPrint}, scan=${enriched.supportsScan}, adf=${enriched.supportsAdf}",
                    false,
                )
            }
        }
        // Automatically recover any lost/left-over scans or resume an active background scan on host
        backgroundScanManager.syncPendingRemoteScans(printer.host, printer.port)
    }

    /**
     * Restores the previously connected wireless printer after the app was closed/restarted
     * (including when Android binds [com.example.service.PrintBridgePrintService] in the background),
     * keeping the connection alive until the host goes offline or the user disconnects manually.
     */
    suspend fun ensurePersistedRemotePrinterRestored(verifyReachability: Boolean = true) {
        remoteRestoreMutex.withLock {
            val usb = usbInfo.value
            val usbActive = usb.isConnected &&
                (usbManuallyDisconnectedDeviceId == null || usbManuallyDisconnectedDeviceId != usb.deviceId)
            if (usbActive) return

            val current = _connectedRemotePrinter.value
            if (current != null) {
                if (verifyReachability) {
                    val online = checkRemoteHostOnlineWithRetry(current)
                    if (!online && _connectedRemotePrinter.value?.id == current.id) {
                        handleRemoteHostOffline(current)
                    }
                }
                return
            }

            val saved = settingsRepository.loadConnectedRemotePrinter() ?: return
            val online = if (verifyReachability) {
                checkRemoteHostOnlineWithRetry(saved)
            } else {
                true
            }
            if (!online) {
                remotePersistMutex.withLock {
                    if (_connectedRemotePrinter.value == null) {
                        settingsRepository.clearConnectedRemotePrinter()
                    }
                }
                appendLog(
                    LogCategory.IPP,
                    "Saved wireless printer '${saved.name}' (${saved.displayAddress}) is offline — staying disconnected",
                    false,
                )
                return
            }

            if (_connectedRemotePrinter.value == null) {
                _connectedRemotePrinter.value = saved
                recomputeActivePrinter()
                appendLog(
                    LogCategory.IPP,
                    "Restored wireless connection to '${saved.name}' (${saved.displayAddress})",
                    false,
                )
                startClientHeartbeat(saved)
                scope.launch {
                    enrichAndSyncRemotePrinter(saved)
                }
            }
        }
    }

    private suspend fun checkRemoteHostOnlineWithRetry(printer: DiscoveredNetworkPrinter): Boolean =
        withContext(Dispatchers.IO) {
            if (RemoteIppClient.isHostReachable(printer, timeoutMs = 3_000)) {
                return@withContext true
            }
            delay(1_200L)
            RemoteIppClient.isHostReachable(printer, timeoutMs = 3_000)
        }

    private suspend fun handleRemoteHostOffline(printer: DiscoveredNetworkPrinter) {
        if (_connectedRemotePrinter.value?.id != printer.id) return
        clientHeartbeatJob?.cancel()
        clientHeartbeatJob = null
        _connectedRemotePrinter.value = null
        recomputeActivePrinter()
        remotePersistMutex.withLock {
            if (_connectedRemotePrinter.value == null) {
                settingsRepository.clearConnectedRemotePrinter()
            }
        }
        appendLog(
            LogCategory.IPP,
            "Wireless host '${printer.name}' (${printer.displayAddress}) went offline — disconnected",
            true,
        )
    }

    private fun startClientHeartbeat(printer: DiscoveredNetworkPrinter) {
        clientHeartbeatJob?.cancel()
        clientHeartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (_connectedRemotePrinter.value?.id == printer.id) {
                val reachable = withContext(Dispatchers.IO) {
                    RemoteIppClient.isHostReachable(printer, timeoutMs = 3_000)
                }
                if (reachable) {
                    consecutiveFailures = 0
                    delay(CLIENT_HEARTBEAT_INTERVAL_MS)
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= 2) {
                        handleRemoteHostOffline(printer)
                        break
                    }
                    delay(3_000L)
                }
            }
        }
    }

    suspend fun connectManualRemoteHost(host: String, port: Int): Result<DiscoveredNetworkPrinter> {
        val result = networkDiscovery.probeManualHost(host, port)
        result.onSuccess { printer ->
            connectRemotePrinter(printer)
        }
        return result
    }

    fun startNetworkDiscovery() {
        val localIp = _serverState.value.ipAddress ?: refreshNetworkInfo()
        val localPort = if (_serverState.value.isRunning) _serverState.value.port else null
        networkDiscovery.startDiscovery(
            excludeLocalIp = localIp,
            excludeLocalPort = localPort,
            preferredPort = _settings.value.ippPort,
        )
    }

    fun stopNetworkDiscovery() {
        networkDiscovery.stopDiscovery()
    }

    private fun recomputeActivePrinter() {
        val usb = usbInfo.value
        val scan = _scannerState.value
        val remote = _connectedRemotePrinter.value
        val usbActive = usb.isConnected &&
            (usbManuallyDisconnectedDeviceId == null || usbManuallyDisconnectedDeviceId != usb.deviceId)

        _activePrinter.value = when {
            usbActive -> ActivePrinterState(
                mode = PrinterConnectionMode.USB_OTG,
                displayName = usb.displayTitle,
                subtitle = usb.statusMessage,
                addressOrId = usb.vidPidHex,
                supportsPrint = true,
                supportsScan = scan.isAvailable,
                supportsAdf = scan.supportsAdf,
                supportsAdfDuplex = scan.supportsAdfDuplex,
                canHostRemoteServer = true,
            )
            remote != null -> ActivePrinterState(
                mode = PrinterConnectionMode.WIRELESS_REMOTE,
                displayName = remote.name,
                subtitle = "Wireless Server • ${remote.displayAddress}",
                addressOrId = remote.displayAddress,
                supportsPrint = remote.supportsPrint,
                supportsScan = remote.supportsScan,
                supportsAdf = remote.supportsAdf,
                supportsAdfDuplex = remote.supportsAdfDuplex,
                canHostRemoteServer = false,
                remotePrinter = remote,
            )
            else -> ActivePrinterState(
                mode = PrinterConnectionMode.NONE,
                displayName = "No Printer Connected",
                subtitle = "Connect via USB OTG or find a wireless server on Wi-Fi / Hotspot",
            )
        }
    }

    private fun probeAuthorizedScanner(
        device: android.hardware.usb.UsbDevice,
        info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo,
    ) {
        val usbMgr = usbScannerMonitor.usbManager ?: return
        scannerProbeJob?.cancel()
        scannerProbeJob = scope.launch {
            delay(350L)
            for (attempt in 1..2) {
                try {
                    val caps = hardwareUsbMutex.withLock {
                        val driver = BrotherDcpL2520dScanner(usbMgr, device)
                        driver.probeCapabilities()
                    }
                    usbScannerMonitor.markReady(device, info, caps)
                    appendLog(
                        LogCategory.SCAN,
                        "Brother brscan4 scanner connected (${info.vidPidHex}, flatbed=${caps.supportsFlatbed}, adf=${caps.supportsAdf})",
                        false,
                    )
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BrotherDcpL2520dScanner.UnsupportedScannerException) {
                    usbScannerMonitor.markUnsupported(info, e.message ?: "Unsupported scanner protocol")
                    return@launch
                } catch (t: Throwable) {
                    if (attempt == 1 && usbMgr.hasPermission(device)) {
                        appendLog(
                            LogCategory.SCAN,
                            "brscan4 scanner probe transient failure (${t.message}) — retrying in 2.5s",
                            false,
                        )
                        delay(2_500L)
                    } else if (info.isBrotherVendor && info.scannerInterfaceId != null && usbMgr.hasPermission(device)) {
                        val fallbackCaps = BrotherDcpL2520dScanner.ScannerCapabilities(
                            isBrscan4Supported = true,
                            supportsFlatbed = true,
                            supportsAdf = false,
                            supportsAdfDuplex = false,
                        )
                        usbScannerMonitor.markReady(device, info, fallbackCaps)
                        appendLog(
                            LogCategory.SCAN,
                            "brscan4 probe timed out (${t.message}) — enabling Brother Flatbed fallback on ${info.vidPidHex}",
                            false,
                        )
                        return@launch
                    } else {
                        usbScannerMonitor.markProbeFailed()
                        appendLog(LogCategory.SCAN, "brscan4 scanner probe failed transiently: ${t.message}", true)
                        return@launch
                    }
                }
            }
        }
    }

    fun scanUsbDevices(clearManualDisconnect: Boolean = false): UsbPrinterInfo {
        if (clearManualDisconnect) {
            usbManuallyDisconnectedDeviceId = null
        }
        val info = usbPrinterManager.scanForPrinters()
        usbScannerMonitor.refreshDevices()
        recomputeActivePrinter()
        return info
    }

    fun refreshNetworkInfo(): String? {
        val ip = NetworkAddressResolver.getLocalWifiIpv4Address()
        _serverState.update { it.copy(ipAddress = ip) }
        return ip
    }

    fun updateKeepAliveLockState(wakeLockActive: Boolean, wifiLockActive: Boolean) {
        _serverState.update {
            it.copy(
                wakeLockActive = wakeLockActive,
                wifiLockActive = wifiLockActive,
            )
        }
    }

    fun onWifiNetworkChanged() {
        val previousIp = _serverState.value.ipAddress
        val currentIp = refreshNetworkInfo()
        if (!_serverState.value.isRunning) return

        val mdnsNeedsRestart = _serverState.value.mdnsStatus != MdnsStatus.ADVERTISING
        if (currentIp != null && (currentIp != previousIp || mdnsNeedsRestart)) {
            val currentSettings = _settings.value.copy(ippPort = _serverState.value.port)
            appendLog(
                LogCategory.MDNS,
                "Wi-Fi / Hotspot network updated ($currentIp) — refreshing Bonjour & UDP beacon",
                false,
            )
            mdnsAdvertiser.startAdvertising(currentSettings, currentIp)
            udpDiscoveryResponder.sendKeepAliveBeacon()
        }
    }

    /**
     * Invoked periodically by [PrintServerService] and immediately on `ACTION_SCREEN_OFF` so
     * mDNS multicast & UDP auto-discovery remain responsive even while the host screen is OFF.
     */
    fun onScreenOffKeepAliveTick(forceMdnsRefresh: Boolean) {
        val net = _serverState.value
        if (!net.isRunning) return

        mdnsAdvertiser.ensureMulticastLockHeld()
        if (!udpDiscoveryResponder.isRunning) {
            udpDiscoveryResponder.start()
        }
        udpDiscoveryResponder.sendKeepAliveBeacon()

        val now = System.currentTimeMillis()
        val ip = refreshNetworkInfo() ?: net.ipAddress
        val mdnsDegraded = net.mdnsStatus != MdnsStatus.ADVERTISING
        val refreshDue = forceMdnsRefresh && (now - lastScreenOffMdnsRefreshMs >= SCREEN_OFF_MDNS_REFRESH_INTERVAL_MS)
        if ((mdnsDegraded || refreshDue) && _settings.value.mdnsEnabled) {
            lastScreenOffMdnsRefreshMs = now
            val currentSettings = _settings.value.copy(ippPort = net.port)
            mdnsAdvertiser.startAdvertising(currentSettings, ip)
        }
    }

    fun startServer(): Result<Unit> {
        if (!canHostRemoteServer()) {
            val err = IllegalStateException("Connect a printer via USB OTG before starting the remote Wi-Fi server.")
            _serverState.update { it.copy(isRunning = false, lastError = err.message) }
            appendLog(LogCategory.IPP, err.message ?: "USB printer not connected", true)
            return Result.failure(err)
        }
        val current = _settings.value
        val ip = refreshNetworkInfo()
        val bindResult = ippServer.start(current.ippPort)
        return bindResult.fold(
            onSuccess = { boundPort ->
                _serverState.update {
                    it.copy(
                        isRunning = true,
                        ipAddress = ip,
                        port = boundPort,
                        lastError = null,
                    )
                }
                lastScreenOffMdnsRefreshMs = System.currentTimeMillis()
                mdnsAdvertiser.startAdvertising(current.copy(ippPort = boundPort), ip)
                udpDiscoveryResponder.start()
                udpDiscoveryResponder.sendKeepAliveBeacon()
                startClientPruneLoop()
                appendLog(
                    LogCategory.IPP,
                    "Remote print & scan server online at ipp://${ip ?: "0.0.0.0"}:$boundPort/ipp/print",
                    false,
                )
                Result.success(Unit)
            },
            onFailure = { err ->
                _serverState.update {
                    it.copy(
                        isRunning = false,
                        mdnsStatus = MdnsStatus.INACTIVE,
                        lastError = err.message,
                    )
                }
                Result.failure(err)
            },
        )
    }

    fun stopServer() {
        clientPruneJob?.cancel()
        clientPruneJob = null
        connectedClientsMap.clear()
        _connectedClients.value = emptyList()
        simpleScanHostService.cancelAllRunningJobs()
        udpDiscoveryResponder.stop()
        mdnsAdvertiser.stopAdvertising()
        ippServer.stop()
        _serverState.update {
            it.copy(
                isRunning = false,
                mdnsStatus = MdnsStatus.INACTIVE,
                activeRequests = 0,
            )
        }
        appendLog(LogCategory.IPP, "Remote Wi-Fi print & scan server stopped", false)
    }

    private fun startClientPruneLoop() {
        clientPruneJob?.cancel()
        clientPruneJob = scope.launch {
            while (_serverState.value.isRunning) {
                delay(15_000L)
                pruneStaleClients()
            }
        }
    }

    private fun pruneStaleClients() {
        val cutoff = System.currentTimeMillis() - CONNECTED_CLIENT_TIMEOUT_MS
        var changed = false
        val iter = connectedClientsMap.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.lastSeenMs < cutoff) {
                iter.remove()
                changed = true
            }
        }
        if (changed) {
            publishConnectedClients()
        }
    }

    private fun publishConnectedClients() {
        _connectedClients.value = connectedClientsMap.values
            .sortedByDescending { it.lastSeenMs }
    }

    fun updateSettings(transform: (PrintServerSettings) -> PrintServerSettings) {
        scope.launch {
            val before = _settings.value
            settingsRepository.updateSettings(transform)
            val after = _settings.value
            val networkChanged = before.ippPort != after.ippPort ||
                before.printerName != after.printerName ||
                before.mdnsEnabled != after.mdnsEnabled
            if (networkChanged && _serverState.value.isRunning) {
                appendLog(LogCategory.IPP, "Applying updated server settings (restarting IPP/mDNS)", false)
                startServer()
            }
        }
    }

    // --- Hardware Busy State Indicator (Non-Blocking Async Queue Coordinator) ---

    private fun refreshBusyStateLocked(): DeviceBusyState {
        val state = when {
            scannerHoldsDevice -> DeviceBusyState.SCANNING
            activePrintJobId != null -> DeviceBusyState.PRINTING
            else -> DeviceBusyState.AVAILABLE
        }
        _deviceBusyState.value = state
        return state
    }

    override fun currentDeviceBusyState(): DeviceBusyState = synchronized(deviceLock) {
        refreshBusyStateLocked()
    }

    private fun tryAcquireScannerLock(): String? = synchronized(deviceLock) {
        when (refreshBusyStateLocked()) {
            DeviceBusyState.SCANNING -> "Busy: Scanner is already scanning another job"
            DeviceBusyState.PRINTING -> "Busy: Printer is currently printing a job"
            DeviceBusyState.AVAILABLE -> {
                scannerHoldsDevice = true
                _deviceBusyState.value = DeviceBusyState.SCANNING
                null
            }
        }
    }

    private fun releaseScannerLock() = synchronized(deviceLock) {
        scannerHoldsDevice = false
        refreshBusyStateLocked()
    }

    private fun setActivePrintJobId(jobId: Int?) = synchronized(deviceLock) {
        activePrintJobId = jobId
        refreshBusyStateLocked()
    }

    // --- Unified Async Queued Printing (Case I OTG and Case II Wireless Remote) ---

    fun printTestPage(options: PrintJobOptions): Result<PrintJobRecord> {
        val active = _activePrinter.value
        if (!active.isConnected) {
            return Result.failure(IllegalStateException("Connect a printer first"))
        }
        val jobId = nextJobId.incrementAndGet()
        val source = when (active.mode) {
            PrinterConnectionMode.WIRELESS_REMOTE -> PrintJobSource.WIRELESS_CLIENT
            else -> PrintJobSource.TEST_PAGE
        }
        val record = PrintJobRecord(
            id = jobId,
            title = "${active.displayName} Test Page",
            source = source,
            clientName = "This Device",
            mimeType = "application/vnd.printbridge.testpage",
            sourcePages = 1,
            imposedSheets = 1,
            pagesPerSheet = options.pagesPerSheet,
            duplexMode = options.duplexMode,
            copies = options.copies.coerceIn(1, 99),
            status = PrintJobStatus.PENDING,
        )
        upsertJob(record)
        appendLog(LogCategory.SPOOLER, "Queued test page #$jobId (${active.mode.badgeLabel})", false)
        spoolQueue.trySend(SpoolTask.TestPageTask(jobId, options))
        return Result.success(record)
    }

    suspend fun enqueueLocalDocument(
        uri: Uri,
        displayName: String,
        declaredMime: String?,
        options: PrintJobOptions,
    ): Result<PrintJobRecord> = withContext(Dispatchers.IO) {
        val active = _activePrinter.value
        if (!active.isConnected) {
            return@withContext Result.failure(IllegalStateException("Connect a printer first"))
        }
        val jobId = nextJobId.incrementAndGet()
        try {
            val resolved = DocumentRenderer.resolveUriZeroCopy(
                context = context,
                uri = uri,
                declaredMime = declaredMime,
                fallbackPrefix = "local_",
            )
            val meta = resolved.metadata
            val imposedSides = NUpLayoutEngine.calculateImposedSides(meta.pageCount, options.pagesPerSheet)
            val source = when (active.mode) {
                PrinterConnectionMode.WIRELESS_REMOTE -> PrintJobSource.WIRELESS_CLIENT
                else -> PrintJobSource.DIRECT_LOCAL
            }
            val record = PrintJobRecord(
                id = jobId,
                title = displayName.ifBlank { meta.kind.label },
                source = source,
                clientName = "This Device",
                mimeType = meta.kind.canonicalMime,
                sourcePages = meta.pageCount,
                imposedSheets = imposedSides,
                pagesPerSheet = options.pagesPerSheet,
                duplexMode = options.duplexMode,
                copies = options.copies,
                status = PrintJobStatus.PENDING,
            )
            upsertJob(record)
            appendLog(
                LogCategory.SPOOLER,
                "Queued ${meta.kind.label} #$jobId via ${active.mode.badgeLabel} (${meta.pageCount}p → $imposedSides sides, ${options.resolution.dpi} DPI, ${options.pagesPerSheet.shortLabel}, ${options.duplexMode.shortLabel})",
                false,
            )
            spoolQueue.trySend(
                SpoolTask.DocumentFileTask(
                    jobId = jobId,
                    source = resolved.source,
                    kind = meta.kind,
                    fileSizeBytes = meta.fileSizeBytes,
                    options = options,
                    deleteFileAfter = resolved.cachedFallbackFile != null,
                ),
            )
            Result.success(record)
        } catch (t: Throwable) {
            appendLog(LogCategory.SPOOLER, "Failed to queue document: ${t.message}", true)
            Result.failure(t)
        }
    }

    suspend fun enqueueLocalFileDocument(
        file: File,
        displayName: String,
        declaredMime: String?,
        options: PrintJobOptions,
    ): Result<PrintJobRecord> = withContext(Dispatchers.IO) {
        val active = _activePrinter.value
        if (!active.isConnected) {
            return@withContext Result.failure(IllegalStateException("Connect a printer first"))
        }
        val jobId = nextJobId.incrementAndGet()
        try {
            val meta = DocumentRenderer.inspectFile(file, declaredMime)
            val imposedSides = NUpLayoutEngine.calculateImposedSides(meta.pageCount, options.pagesPerSheet)
            val source = when (active.mode) {
                PrinterConnectionMode.WIRELESS_REMOTE -> PrintJobSource.WIRELESS_CLIENT
                else -> PrintJobSource.DIRECT_LOCAL
            }
            val record = PrintJobRecord(
                id = jobId,
                title = displayName.ifBlank { meta.kind.label },
                source = source,
                clientName = "This Device",
                mimeType = meta.kind.canonicalMime,
                sourcePages = meta.pageCount,
                imposedSheets = imposedSides,
                pagesPerSheet = options.pagesPerSheet,
                duplexMode = options.duplexMode,
                copies = options.copies,
                status = PrintJobStatus.PENDING,
            )
            upsertJob(record)
            spoolQueue.trySend(
                SpoolTask.DocumentFileTask(
                    jobId = jobId,
                    source = DocumentSource.LocalFile(file),
                    kind = meta.kind,
                    fileSizeBytes = meta.fileSizeBytes,
                    options = options,
                    deleteFileAfter = false,
                ),
            )
            Result.success(record)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Enqueues a PDF received directly from Android's System PrintService (File Manager, Drive,
     * Chrome, Photos, etc.) and automatically deletes the temporary spool file once done.
     */
    suspend fun enqueueSystemPrintServiceJob(
        file: File,
        title: String,
        options: PrintJobOptions,
    ): Result<PrintJobRecord> = withContext(Dispatchers.IO) {
        if (!_activePrinter.value.isConnected) {
            ensurePersistedRemotePrinterRestored(verifyReachability = true)
        }
        val active = _activePrinter.value
        if (!active.isConnected) {
            return@withContext Result.failure(IllegalStateException("Connect a printer first"))
        }
        val jobId = nextJobId.incrementAndGet()
        try {
            val meta = DocumentRenderer.inspectFile(file, "application/pdf")
            val imposedSides = NUpLayoutEngine.calculateImposedSides(meta.pageCount, options.pagesPerSheet)
            val record = PrintJobRecord(
                id = jobId,
                title = title.ifBlank { "Android Print Job" },
                source = PrintJobSource.ANDROID_PRINT_SERVICE,
                clientName = "Android Print",
                mimeType = meta.kind.canonicalMime,
                sourcePages = meta.pageCount,
                imposedSheets = imposedSides,
                pagesPerSheet = options.pagesPerSheet,
                duplexMode = options.duplexMode,
                copies = options.copies,
                status = PrintJobStatus.PENDING,
            )
            upsertJob(record)
            appendLog(
                LogCategory.SPOOLER,
                "Android Print #$jobId: ${record.title} (${meta.pageCount}p → $imposedSides sides, ${options.paperSize.pjlName}, ${options.resolution.dpi} DPI, ${options.pagesPerSheet.shortLabel}, ${options.duplexMode.shortLabel}, ${options.copies}x)",
                false,
            )
            spoolQueue.trySend(
                SpoolTask.DocumentFileTask(
                    jobId = jobId,
                    source = DocumentSource.LocalFile(file),
                    kind = meta.kind,
                    fileSizeBytes = meta.fileSizeBytes,
                    options = options,
                    deleteFileAfter = true,
                ),
            )
            Result.success(record)
        } catch (t: Throwable) {
            appendLog(LogCategory.SPOOLER, "Android Print Service job failed: ${t.message}", true)
            Result.failure(t)
        }
    }

    // --- Unified Scanning (Case I Direct OTG & Case II Wireless HTTP /api/scan) ---

    private val continuousSessionLocked = AtomicBoolean(false)

    /**
     * Exclusively reserves the scanner for a continuous multi-page scan session (blocking other
     * Wi-Fi IPP/HTTP clients) until [endContinuousScanSession] is invoked when the user taps Done.
     * Returns null on success or a human-readable busy/error message on failure.
     */
    fun beginContinuousScanSession(): String? {
        val active = _activePrinter.value
        if (!active.isConnected) return "Connect a printer/scanner first"
        if (!active.supportsScan) return "Connected printer does not support scanning"
        if (continuousSessionLocked.get()) return null

        val busyReason = tryAcquireScannerLock()
        if (busyReason != null) return busyReason

        continuousSessionLocked.set(true)
        scanCancelFlag.set(false)
        _scannerState.update { it.copy(isScanning = true) }
        appendLog(
            LogCategory.SCAN,
            "Scanner locked for continuous scan session (${active.mode.badgeLabel}) — reserved until Done",
            false,
        )
        return null
    }

    /**
     * Stops any in-flight scan pass and immediately frees the hardware scanner lock so other
     * users/devices on the network can print or scan again.
     */
    fun endContinuousScanSession() {
        scanCancelFlag.set(true)
        activeLocalScanner?.requestCancel()
        if (continuousSessionLocked.compareAndSet(true, false)) {
            _scannerState.update { it.copy(isScanning = false) }
            releaseScannerLock()
            appendLog(LogCategory.SCAN, "Continuous scan finished — scanner is now free for other users", false)
        }
    }

    // --- Background Scanning (Direct USB OTG & Simple Async /api/scan/* Wireless Polling) ---

    fun startBackgroundScan(
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>,
        nextPageNumber: Int,
        idCardMergeSpec: IdCardMergeSpec? = null,
    ) {
        backgroundScanManager.startScan(
            params = params,
            sessionTitle = sessionTitle,
            targetCollectionId = targetCollectionId,
            existingPagesSnapshot = existingPagesSnapshot,
            nextPageNumber = nextPageNumber,
            idCardMergeSpec = idCardMergeSpec,
        )
    }

    fun cancelBackgroundScan() {
        backgroundScanManager.cancelActiveScan()
    }

    fun clearBackgroundScanError() {
        backgroundScanManager.clearError()
    }

    fun syncPendingRemoteScansIfConnected() {
        val remote = _connectedRemotePrinter.value ?: return
        backgroundScanManager.syncPendingRemoteScans(remote.host, remote.port)
    }

    fun cancelActiveScan() {
        scanCancelFlag.set(true)
        activeLocalScanner?.requestCancel()
    }

    private suspend fun scanViaDirectUsb(
        params: ScanParameters,
        holdsSessionLock: Boolean,
        onProgress: (Float, String) -> Unit,
    ): Result<List<Bitmap>> = withContext(Dispatchers.IO) {
        val ready = usbScannerMonitor.connectionState.value as? UsbConnectionState.Ready
            ?: return@withContext Result.failure(IllegalStateException("USB brscan4 scanner is not ready"))
        val usbMgr = usbScannerMonitor.usbManager
            ?: return@withContext Result.failure(IllegalStateException("USB Host service unavailable on this device"))

        val bitmaps = mutableListOf<Bitmap>()
        try {
            if (currentDeviceBusyState() != DeviceBusyState.AVAILABLE && !holdsSessionLock) {
                onProgress(0.03f, "Queued — waiting for active print/scan to finish...")
            }

            hardwareUsbMutex.withLock {
                if (!holdsSessionLock) {
                    synchronized(deviceLock) {
                        scannerHoldsDevice = true
                        refreshBusyStateLocked()
                    }
                }
                _scannerState.update { it.copy(isScanning = true) }
                onProgress(0.05f, "Warming up Brother USB scanner...")
                val driver = BrotherDcpL2520dScanner(usbMgr, ready.device)
                activeLocalScanner = driver

                val driverConfig = BrotherDcpL2520dScanner.ScanConfig(
                    dpi = params.dpi,
                    mode = when (params.colorMode) {
                        ScanColorOption.COLOR -> BrotherDcpL2520dScanner.ScanMode.COLOR_24BIT
                        ScanColorOption.GRAYSCALE -> BrotherDcpL2520dScanner.ScanMode.GRAYSCALE_8BIT
                        ScanColorOption.MONOCHROME -> BrotherDcpL2520dScanner.ScanMode.MONOCHROME_1BIT
                    },
                    pageSize = when (params.pageSize) {
                        ScanPageSizeOption.A4,
                        ScanPageSizeOption.A3 -> BrotherDcpL2520dScanner.PageSize.A4
                        ScanPageSizeOption.LETTER -> BrotherDcpL2520dScanner.PageSize.LETTER
                        ScanPageSizeOption.A5 -> BrotherDcpL2520dScanner.PageSize.A5
                        ScanPageSizeOption.ID_CARD -> BrotherDcpL2520dScanner.PageSize.ID_CARD
                    },
                    source = when (params.source) {
                        ScanSourceOption.FLATBED -> BrotherDcpL2520dScanner.ScanSource.FLATBED
                        ScanSourceOption.ADF_SIMPLEX -> BrotherDcpL2520dScanner.ScanSource.ADF_SIMPLEX
                        ScanSourceOption.ADF_DUPLEX -> BrotherDcpL2520dScanner.ScanSource.ADF_DUPLEX
                    },
                    brightness = params.brightness,
                    contrast = params.contrast,
                )

                appendLog(
                    LogCategory.SCAN,
                    "Starting direct USB scan (${params.source.displayName}, ${params.dpi} DPI, ${params.colorMode.shortLabel})",
                    false,
                )

                driver.scanPages(
                    config = driverConfig,
                    onPageProgress = { pageNum, line, total ->
                        val frac = if (total > 0) (line.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.1f
                        onProgress(0.08f + frac * 0.88f, "Scanning page $pageNum (${(frac * 100).toInt()}%)...")
                    },
                    onStatusUpdate = { stageProgress, statusMsg ->
                        onProgress(stageProgress, statusMsg)
                    },
                    onPageCaptured = { _, bitmap ->
                        bitmaps.add(bitmap)
                    },
                )
            }
            onProgress(1f, "Captured ${bitmaps.size} page(s)")
            Result.success(bitmaps)
        } catch (t: Throwable) {
            appendLog(LogCategory.SCAN, "Direct USB scan failed: ${t.message}", true)
            Result.failure(t)
        } finally {
            activeLocalScanner = null
            if (!holdsSessionLock) {
                _scannerState.update { it.copy(isScanning = false) }
                releaseScannerLock()
            }
        }
    }

    fun clearCompletedJobs() {
        _jobs.update { list ->
            list.filter {
                it.status == PrintJobStatus.PENDING ||
                    it.status == PrintJobStatus.RENDERING ||
                    it.status == PrintJobStatus.TRANSMITTING
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // --- Unified Printer Health & Diagnostics ---

    suspend fun queryPrinterDiagnostics(): Result<PrinterDiagnostics> {
        val active = _activePrinter.value
        if (!active.isConnected) {
            return Result.failure(IllegalStateException("No printer connected"))
        }

        return when (active.mode) {
            PrinterConnectionMode.USB_OTG -> {
                val busy = currentDeviceBusyState()
                if (busy != DeviceBusyState.AVAILABLE) {
                    val reason = when (busy) {
                        DeviceBusyState.PRINTING -> "Printer is currently printing — live status will refresh when idle"
                        DeviceBusyState.SCANNING -> "Scanner is currently active — live status will refresh when idle"
                        DeviceBusyState.AVAILABLE -> ""
                    }
                    return Result.failure(DeviceBusyException(reason))
                }
                diagnosticsMutex.withLock {
                    if (!hardwareUsbMutex.tryLock()) {
                        return@withLock Result.failure(
                            DeviceBusyException("Printer or scanner is busy — live status will refresh when idle")
                        )
                    }
                    try {
                        usbPrinterManager.queryPrinterDiagnostics().onSuccess { fresh ->
                            cachedUsbDiagnostics = fresh
                            cachedUsbDiagnosticsAtMs = System.currentTimeMillis()
                        }
                    } finally {
                        hardwareUsbMutex.unlock()
                    }
                }
            }
            PrinterConnectionMode.WIRELESS_REMOTE -> {
                val remote = active.remotePrinter
                    ?: return Result.failure(IllegalStateException("Wireless server not connected"))
                RemoteIppClient.queryDiagnostics(remote)
            }
            PrinterConnectionMode.NONE -> Result.failure(IllegalStateException("No printer connected"))
        }
    }

    // --- IppServerDelegate implementation ---

    override fun currentSettings(): PrintServerSettings = _settings.value

    override fun currentUsbInfo(): UsbPrinterInfo = usbPrinterManager.printerInfo.value

    override fun currentIpAddress(): String? = _serverState.value.ipAddress ?: refreshNetworkInfo()
    override fun currentJobs(): List<PrintJobRecord> = _jobs.value
    override fun isScannerAvailable(): Boolean = _activePrinter.value.supportsScan

    override suspend fun queryStatusJson(): String {
        val settings = _settings.value
        val active = _activePrinter.value
        val busy = currentDeviceBusyState()
        val now = System.currentTimeMillis()
        val cached = cachedUsbDiagnostics
        val cacheFresh = cached != null && (now - cachedUsbDiagnosticsAtMs) < DIAGNOSTICS_CACHE_TTL_MS

        // NEVER block the HTTP response on slow USB PJL telemetry!
        // Return instantly with cached diagnostics and refresh asynchronously in background when idle.
        if (active.isConnected && !cacheFresh && busy == DeviceBusyState.AVAILABLE) {
            triggerBackgroundDiagnosticsRefresh()
        }

        val queuedPrintJobs = _jobs.value.count {
            it.status == PrintJobStatus.PENDING ||
                it.status == PrintJobStatus.RENDERING ||
                it.status == PrintJobStatus.TRANSMITTING
        }
        val queuedScanJobs = simpleScanHostService.queuedCount.value

        return JSONObject().apply {
            put("server", "PrintBridge")
            put("printerName", settings.printerName)
            put("model", active.displayName)
            put("connected", active.isConnected)
            put("busyState", busy.name)
            put("queuedPrintJobs", queuedPrintJobs)
            put("queuedScanJobs", queuedScanJobs)
            put("scannerAvailable", active.supportsScan)
            put("supportsAdf", active.supportsAdf)
            put("supportsAdfDuplex", active.supportsAdfDuplex)
            if (cached != null) {
                put(
                    "diagnostics",
                    JSONObject().apply {
                        cached.online?.let { put("online", it) }
                        cached.hasHardwareError?.let { put("hasHardwareError", it) }
                        put("paperAvailability", cached.paperAvailability.name)
                        cached.loadedPaperSize?.let { put("loadedPaperSize", it) }
                        cached.loadedMediaType?.let { put("loadedMediaType", it) }
                        put("tonerStatus", cached.tonerStatus.name)
                        cached.tonerPercent?.let { put("tonerPercent", it) }
                        put("drumStatus", cached.drumStatus.name)
                        cached.drumPercent?.let { put("drumPercent", it) }
                        cached.lcdDisplayMessage?.let { put("lcdDisplayMessage", it) }
                        cached.pjlStatusCode?.let { put("pjlStatusCode", it) }
                        cached.totalPageCount?.let { put("totalPageCount", it) }
                        cached.economyMode?.let { put("economyMode", it) }
                        cached.duplexEnabled?.let { put("duplexEnabled", it) }
                        cached.firmwareId?.let { put("firmwareId", it) }
                        if (cached.commandLanguages.isNotEmpty()) {
                            put("commandLanguages", JSONArray(cached.commandLanguages))
                        }
                        put("pjlSupported", cached.pjlSupported)
                        put("portStatusSupported", cached.portStatusSupported)
                        put("queriedAtMs", cached.queriedAtMs)
                    },
                )
            }
        }.toString()
    }

    private fun triggerBackgroundDiagnosticsRefresh() {
        if (!diagnosticsRefreshRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                withTimeoutOrNull(4_500L) {
                    runCatching { queryPrinterDiagnostics() }
                }
            } finally {
                diagnosticsRefreshRunning.set(false)
            }
        }
    }

    override suspend fun handleSimpleScanRequest(
        method: String,
        path: String,
        body: ByteArray,
        remoteIp: String,
    ): SimpleScanHttpResponse = simpleScanHostService.handleHttpRequest(
        method = method,
        rawPath = path,
        body = body,
        remoteIp = remoteIp,
    )

    override fun createPendingIppJob(
        title: String,
        clientName: String,
        options: PrintJobOptions,
    ): Result<PrintJobRecord> {
        if (!currentUsbInfo().isConnected) {
            return Result.failure(IllegalStateException("USB printer is offline on host"))
        }
        val jobId = nextJobId.incrementAndGet()
        val record = PrintJobRecord(
            id = jobId,
            title = title,
            source = PrintJobSource.IPP_NETWORK,
            clientName = clientName,
            mimeType = "application/pdf",
            sourcePages = 0,
            imposedSheets = 0,
            pagesPerSheet = options.pagesPerSheet,
            duplexMode = options.duplexMode,
            copies = options.copies,
            status = PrintJobStatus.PENDING,
        )
        upsertJob(record)
        return Result.success(record)
    }

    override fun attachDocumentAndEnqueue(
        existingJobId: Int?,
        title: String,
        clientName: String,
        declaredMime: String?,
        file: File,
        options: PrintJobOptions,
    ): Result<PrintJobRecord> {
        if (!currentUsbInfo().isConnected) {
            return Result.failure(IllegalStateException("USB printer is offline on host"))
        }
        val jobId = existingJobId ?: nextJobId.incrementAndGet()
        return try {
            val meta = DocumentRenderer.inspectFile(file, declaredMime)
            val selectedPages = if (meta.kind == SupportedDocumentKind.PDF || meta.kind == SupportedDocumentKind.PWG_RASTER) {
                NUpLayoutEngine.resolvePageIndices(meta.pageCount, options.pageRanges, options.reversePageOrder).size
            } else {
                meta.pageCount
            }
            val imposedSides = NUpLayoutEngine.calculateImposedSides(selectedPages, options.pagesPerSheet)
            val record = PrintJobRecord(
                id = jobId,
                title = title,
                source = PrintJobSource.IPP_NETWORK,
                clientName = clientName,
                mimeType = meta.kind.canonicalMime,
                sourcePages = selectedPages,
                imposedSheets = imposedSides,
                pagesPerSheet = options.pagesPerSheet,
                duplexMode = options.duplexMode,
                copies = options.copies,
                status = PrintJobStatus.PENDING,
            )
            upsertJob(record)
            appendLog(
                LogCategory.SPOOLER,
                "Queued IPP Job #$jobId from $clientName: ${meta.kind.label} (${selectedPages}p → $imposedSides sides, ${options.paperSize.pjlName}, ${options.resolution.dpi} DPI, ${options.pagesPerSheet.shortLabel}, ${options.duplexMode.shortLabel})",
                false,
            )
            spoolQueue.trySend(
                SpoolTask.DocumentFileTask(
                    jobId = jobId,
                    source = DocumentSource.LocalFile(file),
                    kind = meta.kind,
                    fileSizeBytes = meta.fileSizeBytes,
                    options = options,
                    deleteFileAfter = true,
                ),
            )
            Result.success(record)
        } catch (t: Throwable) {
            appendLog(LogCategory.IPP, "Rejected IPP document ($declaredMime): ${t.message}", true)
            Result.failure(t)
        }
    }

    override fun cancelJob(jobId: Int): Boolean {
        val target = _jobs.value.firstOrNull { it.id == jobId } ?: return false
        if (target.status == PrintJobStatus.COMPLETED || target.status == PrintJobStatus.FAILED) {
            return false
        }
        canceledJobIds.add(jobId)
        updateJob(jobId) {
            it.copy(
                status = PrintJobStatus.CANCELED,
                completedAtMs = System.currentTimeMillis(),
            )
        }
        appendLog(LogCategory.SPOOLER, "Canceled print job #$jobId", false)
        return true
    }

    override fun onClientActivity(
        remoteIp: String,
        headers: Map<String, String>,
        activityLabel: String,
        explicitClientName: String?,
    ) {
        if (remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp.isBlank()) return
        val clientId = headers["x-client-id"]?.trim()?.takeIf { it.isNotBlank() } ?: remoteIp
        val headerName = headers["x-client-name"]?.trim()?.takeIf { it.isNotBlank() }
        val headerModel = headers["x-client-model"]?.trim()?.takeIf { it.isNotBlank() }
        val cleanedExplicit = explicitClientName?.trim()?.takeIf {
            it.isNotBlank() &&
                !it.equals("PrintBridge", ignoreCase = true) &&
                !it.equals("PrintBridge Android", ignoreCase = true)
        }
        val existing = connectedClientsMap[clientId]
        val resolvedName = headerName
            ?: cleanedExplicit
            ?: headerModel
            ?: existing?.displayName?.takeIf { it.isNotBlank() && it != remoteIp }
            ?: remoteIp
        val resolvedModel = headerModel ?: existing?.modelName.orEmpty()

        connectedClientsMap[clientId] = ConnectedClientDevice(
            id = clientId,
            displayName = resolvedName,
            modelName = resolvedModel,
            ipAddress = remoteIp,
            lastActivityLabel = activityLabel,
            lastSeenMs = System.currentTimeMillis(),
        )
        publishConnectedClients()
    }

    override fun onClientDisconnected(
        remoteIp: String,
        headers: Map<String, String>,
    ) {
        val clientId = headers["x-client-id"]?.trim()?.takeIf { it.isNotBlank() }
        val removed = if (clientId != null) {
            connectedClientsMap.remove(clientId)
        } else {
            connectedClientsMap.remove(remoteIp)
        }
        if (removed != null) {
            publishConnectedClients()
        }
        // Immediately rescue any un-acked scan files belonging to the disconnecting device into Host's Lost/Found collage!
        if (!clientId.isNullOrBlank()) {
            simpleScanHostService.flushUndeliveredToLostAndFoundAsync(
                deviceId = clientId,
                reason = "client disconnected",
            )
        }
    }

    override fun onLog(category: LogCategory, message: String, isError: Boolean) {
        appendLog(category, message, isError)
    }

    override fun onRequestMetricsChanged(activeCount: Int, totalCount: Int) {
        _serverState.update {
            it.copy(activeRequests = activeCount, totalIppRequests = totalCount)
        }
    }

    // --- Serialized Async Print Spooler Worker (USB OTG & Wireless Remote IPP) ---

    private fun startSpoolWorker() {
        scope.launch {
            for (task in spoolQueue) {
                if (canceledJobIds.remove(task.jobId)) {
                    cleanupTaskSource(task)
                    continue
                }
                processTask(task)
            }
        }
    }

    private fun cleanupTaskSource(task: SpoolTask) {
        if (task is SpoolTask.DocumentFileTask && task.deleteFileAfter) {
            val src = task.source
            if (src is DocumentSource.LocalFile) {
                src.file.delete()
            }
        }
    }

    private suspend fun processTask(task: SpoolTask) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val jobWakeLock = runCatching {
            powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrintBridge:JobSpoolWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(10L * 60L * 1000L)
            }
        }.getOrNull()

        fun renewWakeLock() {
            runCatching { jobWakeLock?.acquire(10L * 60L * 1000L) }
        }

        try {
            updateJob(task.jobId) { it.copy(status = PrintJobStatus.RENDERING, progress = 0.05f) }
            val title = _jobs.value.firstOrNull { it.id == task.jobId }?.title ?: "PrintBridge Job"
            val activeMode = _activePrinter.value.mode

            val finalResult: Result<Pair<Int, Long>> = when (activeMode) {
                PrinterConnectionMode.USB_OTG -> {
                    // Acquire the unified USB hardware mutex so prints and scans queue smoothly without conflicts
                    hardwareUsbMutex.withLock {
                        if (canceledJobIds.contains(task.jobId)) {
                            Result.failure(InterruptedException("Job canceled while queued"))
                        } else {
                            setActivePrintJobId(task.jobId)
                            try {
                                var pagesEmitted = 0
                                val result = streamTaskToUsb(task, title, task.options, ::renewWakeLock) { pagesEmitted++ }
                                if (result.isFailure &&
                                    result.exceptionOrNull() is OutOfMemoryError &&
                                    pagesEmitted == 0 &&
                                    task.options.resolution == PrintResolution.DPI_600
                                ) {
                                    System.gc()
                                    appendLog(
                                        LogCategory.SPOOLER,
                                        "Low memory at 600 DPI for job #${task.jobId} — retrying cleanly at 300 DPI",
                                        true,
                                    )
                                    streamTaskToUsb(
                                        task = task,
                                        title = title,
                                        effectiveOptions = task.options.copy(resolution = PrintResolution.DPI_300),
                                        renewWakeLock = ::renewWakeLock,
                                        onPageEmitted = {},
                                    )
                                } else {
                                    result
                                }
                            } finally {
                                setActivePrintJobId(null)
                            }
                        }
                    }
                }
                PrinterConnectionMode.WIRELESS_REMOTE -> {
                    sendTaskToWirelessServer(task, title)
                }
                PrinterConnectionMode.NONE -> {
                    Result.failure(IllegalStateException("Printer disconnected before job started"))
                }
            }

            if (canceledJobIds.remove(task.jobId)) {
                updateJob(task.jobId) {
                    it.copy(status = PrintJobStatus.CANCELED, completedAtMs = System.currentTimeMillis())
                }
                return
            }

            finalResult.fold(
                onSuccess = { (renderedSides, bytesSent) ->
                    updateJob(task.jobId) {
                        it.copy(
                            status = PrintJobStatus.COMPLETED,
                            progress = 1f,
                            bytesSent = bytesSent,
                            completedAtMs = System.currentTimeMillis(),
                            errorMessage = null,
                        )
                    }
                    appendLog(
                        LogCategory.SPOOLER,
                        "Job #${task.jobId} printed ($renderedSides side(s), ${DocumentRenderer.formatBytes(bytesSent)})",
                        false,
                    )
                },
                onFailure = { err ->
                    if (err is InterruptedException) {
                        updateJob(task.jobId) {
                            it.copy(status = PrintJobStatus.CANCELED, completedAtMs = System.currentTimeMillis())
                        }
                    } else {
                        val message = if (err is OutOfMemoryError) {
                            "Device memory limit reached while rendering — try 300 DPI"
                        } else {
                            err.message ?: "Print transmission failed"
                        }
                        updateJob(task.jobId) {
                            it.copy(
                                status = PrintJobStatus.FAILED,
                                errorMessage = message,
                                completedAtMs = System.currentTimeMillis(),
                            )
                        }
                        appendLog(LogCategory.SPOOLER, "Job #${task.jobId} failed: $message", true)
                    }
                },
            )
        } catch (_: InterruptedException) {
            updateJob(task.jobId) {
                it.copy(status = PrintJobStatus.CANCELED, completedAtMs = System.currentTimeMillis())
            }
        } catch (oom: OutOfMemoryError) {
            System.gc()
            val message = "Device memory limit reached while rendering — try 300 DPI"
            updateJob(task.jobId) {
                it.copy(
                    status = PrintJobStatus.FAILED,
                    errorMessage = message,
                    completedAtMs = System.currentTimeMillis(),
                )
            }
            appendLog(LogCategory.SPOOLER, "Job #${task.jobId} OOM guard: $message", true)
        } catch (t: Throwable) {
            updateJob(task.jobId) {
                it.copy(
                    status = PrintJobStatus.FAILED,
                    errorMessage = t.message ?: "Unexpected print error",
                    completedAtMs = System.currentTimeMillis(),
                )
            }
            appendLog(LogCategory.SPOOLER, "Job #${task.jobId} error: ${t.message}", true)
        } finally {
            cleanupTaskSource(task)
            setActivePrintJobId(null)
            runCatching {
                if (jobWakeLock?.isHeld == true) jobWakeLock.release()
            }
        }
    }

    private suspend fun sendTaskToWirelessServer(
        task: SpoolTask,
        title: String,
    ): Result<Pair<Int, Long>> {
        val remote = _activePrinter.value.remotePrinter
            ?: return Result.failure(IllegalStateException("Wireless server disconnected"))

        updateJob(task.jobId) { it.copy(status = PrintJobStatus.TRANSMITTING, progress = 0.1f) }
        return when (task) {
            is SpoolTask.TestPageTask -> {
                val bmp = TestPageGenerator.generateTestPage(
                    options = task.options,
                    serverState = _serverState.value,
                    usbInfo = currentUsbInfo(),
                    dpi = 150,
                )
                val pngBytes = ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    bmp.recycle()
                    out.toByteArray()
                }
                RemoteIppClient.printRawBytes(
                    printer = remote,
                    payload = pngBytes,
                    title = title,
                    mimeType = "image/png",
                    options = task.options,
                    onProgress = { pct, sent ->
                        updateJob(task.jobId) { it.copy(progress = pct, bytesSent = sent) }
                    },
                ).map { res -> 1 to res.bytesUploaded }
            }
            is SpoolTask.DocumentFileTask -> {
                val sides = _jobs.value.firstOrNull { it.id == task.jobId }?.imposedSheets ?: 1
                RemoteIppClient.printDocument(
                    context = context,
                    printer = remote,
                    source = task.source,
                    title = title,
                    mimeType = task.kind.canonicalMime,
                    fileSizeBytes = task.fileSizeBytes,
                    options = task.options,
                    isCanceled = { canceledJobIds.contains(task.jobId) },
                    onProgress = { pct, sent ->
                        updateJob(task.jobId) {
                            it.copy(
                                status = PrintJobStatus.TRANSMITTING,
                                progress = pct,
                                bytesSent = sent,
                            )
                        }
                    },
                ).map { res -> sides to res.bytesUploaded }
            }
        }
    }

    private suspend fun streamTaskToUsb(
        task: SpoolTask,
        title: String,
        effectiveOptions: PrintJobOptions,
        renewWakeLock: () -> Unit,
        onPageEmitted: () -> Unit,
    ): Result<Pair<Int, Long>> {
        return usbPrinterManager.withUsbOutputStream(
            onBytesSent = { sent ->
                updateJob(task.jobId) { it.copy(bytesSent = sent) }
            },
        ) { usbOut ->
            val jobRecord = _jobs.value.firstOrNull { it.id == task.jobId }
            val useSoftwareCollation = task is SpoolTask.DocumentFileTask &&
                effectiveOptions.copies > 1 &&
                effectiveOptions.collateCopies &&
                (jobRecord?.imposedSheets ?: 1) > 1
            val encoderOptions = if (useSoftwareCollation) {
                effectiveOptions.copy(copies = 1)
            } else {
                effectiveOptions
            }
            val copyPasses = if (useSoftwareCollation) effectiveOptions.copies.coerceIn(1, 99) else 1

            var renderedSides = 0
            BrotherLaserEncoder.openSession(usbOut, title, encoderOptions).use { session ->
                when (task) {
                    is SpoolTask.TestPageTask -> {
                        val testBitmap = TestPageGenerator.generateTestPage(
                            options = encoderOptions,
                            serverState = _serverState.value,
                            usbInfo = usbPrinterManager.printerInfo.value,
                            dpi = encoderOptions.resolution.dpi,
                        )
                        try {
                            updateJob(task.jobId) {
                                it.copy(status = PrintJobStatus.TRANSMITTING, progress = 0.45f)
                            }
                            session.writePage(testBitmap)
                            renderedSides = 1
                            onPageEmitted()
                        } finally {
                            if (!testBitmap.isRecycled) testBitmap.recycle()
                        }
                        session.writeBlankBackPageIfNeeded(renderedSides)
                    }

                    is SpoolTask.DocumentFileTask -> {
                        for (copyIdx in 0 until copyPasses) {
                            var sidesInPass = 0
                            DocumentRenderer.renderImposedSheets(
                                context = context,
                                source = task.source,
                                kind = task.kind,
                                pagesPerSheet = encoderOptions.pagesPerSheet,
                                paperSize = encoderOptions.paperSize,
                                targetDpi = encoderOptions.resolution.dpi,
                                drawCellBorders = encoderOptions.drawNUpBorders,
                                forPhysicalPortraitFeed = true,
                                margins = encoderOptions.margins,
                                scaling = encoderOptions.scaling,
                                orientation = encoderOptions.orientation,
                                nUpDirection = encoderOptions.nUpDirection,
                                pageRanges = encoderOptions.pageRanges,
                                reversePageOrder = encoderOptions.reversePageOrder,
                                panX = encoderOptions.imagePanX,
                                panY = encoderOptions.imagePanY,
                            ) { sheetIdx, totalSheets, sheetBitmap ->
                                if (canceledJobIds.contains(task.jobId)) {
                                    throw InterruptedException("Job canceled")
                                }
                                renewWakeLock()
                                updateJob(task.jobId) {
                                    it.copy(status = PrintJobStatus.TRANSMITTING)
                                }
                                session.writePage(sheetBitmap)
                                sidesInPass++
                                renderedSides++
                                onPageEmitted()
                                val completedUnits = copyIdx * totalSheets + (sheetIdx + 1)
                                val totalUnits = (copyPasses * totalSheets).coerceAtLeast(1)
                                val pct = 0.05f + 0.92f * (completedUnits.toFloat() / totalUnits)
                                updateJob(task.jobId) { it.copy(progress = pct.coerceAtMost(0.98f)) }
                            }
                            session.writeBlankBackPageIfNeeded(sidesInPass)
                        }
                    }
                }
            }
            renderedSides
        }
    }

    private fun upsertJob(record: PrintJobRecord) {
        _jobs.update { list ->
            val filtered = list.filterNot { it.id == record.id }
            (listOf(record) + filtered).take(50)
        }
        if (record.status == PrintJobStatus.PENDING ||
            record.status == PrintJobStatus.RENDERING ||
            record.status == PrintJobStatus.TRANSMITTING
        ) {
            PrintJobForegroundService.start(context)
        }
    }

    private fun updateJob(jobId: Int, transform: (PrintJobRecord) -> PrintJobRecord) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) transform(it) else it }
        }
    }

    private fun appendLog(category: LogCategory, message: String, isError: Boolean) {
        val entry = ProtocolLogEntry(
            id = nextLogId.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            category = category,
            message = message,
            isError = isError,
        )
        _logs.update { current -> (listOf(entry) + current).take(80) }
    }

    companion object {
        private const val DIAGNOSTICS_CACHE_TTL_MS = 8_000L
        private const val CLIENT_HEARTBEAT_INTERVAL_MS = 22_000L
        private const val CONNECTED_CLIENT_TIMEOUT_MS = 90_000L
        private const val SCREEN_OFF_MDNS_REFRESH_INTERVAL_MS = 180_000L
    }
}
