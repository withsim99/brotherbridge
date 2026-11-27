package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PrintBridgeApp
import com.example.model.ActivePrinterState
import com.example.model.AppLanguage
import com.example.model.ConnectedClientDevice
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.DuplexMode
import com.example.model.IdCardMergeLayout
import com.example.model.NetworkServerState
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintColorScheme
import com.example.model.PrintJobOptions
import com.example.model.PrintJobRecord
import com.example.model.PrintJobStatus
import com.example.model.PrintOrientation
import com.example.model.PrintResolution
import com.example.model.PrintScaling
import com.example.model.PrintServerSettings
import com.example.model.PrinterDiagnostics
import com.example.model.PrinterLanguage
import com.example.model.ProtocolLogEntry
import com.example.model.RecentScanRecord
import com.example.model.RecentScansSortBy
import com.example.model.RecentScansViewMode
import com.example.model.ScanColorOption
import com.example.model.ScanExportMode
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
import com.example.model.ScannerAvailabilityState
import com.example.model.UsbConnectionStatus
import com.example.model.UsbPrinterInfo
import com.example.printer.render.DocumentMetadata
import com.example.printer.render.DocumentRenderer
import com.example.printer.render.DocumentSource
import com.example.printer.render.TestPageGenerator
import com.example.scanner.BackgroundScanState
import com.example.scanner.IdCardMergeSpec
import com.example.scanner.ScanExportManager
import com.example.scanner.ScanIdCardMerger
import com.example.service.PrintServerService
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class SelectedDocumentState(
    val id: String,
    val uri: Uri?,
    val localFile: File?,
    val source: DocumentSource,
    val cachedFallbackFile: File?,
    val displayName: String,
    val metadata: DocumentMetadata,
    val previewBitmap: Bitmap? = null,
    val isRenderingPreview: Boolean = false,
    val isTestPage: Boolean = false,
)

data class DirectPrintUiState(
    val selectedDocument: SelectedDocumentState? = null,
    val paperSize: PaperSize = PaperSize.A4,
    val pagesPerSheet: PagesPerSheet = PagesPerSheet.ONE,
    val duplexMode: DuplexMode = DuplexMode.SIMPLEX,
    val resolution: PrintResolution = PrintResolution.DPI_600,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val colorScheme: PrintColorScheme = PrintColorScheme.GRAYSCALE,
    val tonerSave: Boolean = false,
    val copies: Int = 1,
    val drawBorders: Boolean = true,
    val imageMarginMm: Float = PageMargins.DEFAULT_MARGIN_MM,
    val imageScaling: PrintScaling = PrintScaling.AUTO_FIT,
    val imagePanX: Float = 0f,
    val imagePanY: Float = 0f,
    val errorMessage: String? = null,
    val isSubmitting: Boolean = false,
)

data class PrinterInfoUiState(
    val isLoading: Boolean = false,
    val diagnostics: PrinterDiagnostics? = null,
    val errorMessage: String? = null,
)

data class ScannedPageItem(
    val id: Long,
    val bitmap: Bitmap,
    val originalBitmap: Bitmap? = null,
    val dpi: Int,
    val colorLabel: String,
    val pageSizeLabel: String,
    val pageSize: ScanPageSizeOption = ScanPageSizeOption.A4,
)

data class PendingIdCardFront(
    val pageId: Long,
    val frontBitmap: Bitmap,
    val pageSize: ScanPageSizeOption,
    val layout: IdCardMergeLayout,
    val dpi: Int,
    val colorLabel: String,
)

enum class ScanPhase {
    PARAMETERS,
    EXPORT_READY,
}

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.PARAMETERS,
    val activeCollectionId: String? = null,
    val sessionTitle: String = "",
    val params: ScanParameters = ScanParameters(),
    val pages: List<ScannedPageItem> = emptyList(),
    val selectedPageIndex: Int = 0,
    val isActivelyScanningPage: Boolean = false,
    val currentPageProgress: Float = 0f,
    val currentPageStatus: String = "",
    val pendingIdCardFront: PendingIdCardFront? = null,
    val isMergingIdCard: Boolean = false,
    val viewMode: RecentScansViewMode = RecentScansViewMode.GRID,
    val isExporting: Boolean = false,
    val exportSuccessMessage: String? = null,
    val errorMessage: String? = null,
)

private sealed interface ScanPassIntent {
    data object Normal : ScanPassIntent
    data class IdFront(val params: ScanParameters) : ScanPassIntent
    data class IdBack(val pending: PendingIdCardFront) : ScanPassIntent
}

class PrintBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = (application as PrintBridgeApp).spoolerEngine
    private val nextScanPageId = AtomicLong(1L)
    private var activeScanIntent: ScanPassIntent = ScanPassIntent.Normal
    private var previewRenderJob: Job? = null

    val settings: StateFlow<PrintServerSettings> = engine.settings
    val usbInfo: StateFlow<UsbPrinterInfo> = engine.usbInfo
    val activePrinter: StateFlow<ActivePrinterState> = engine.activePrinter
    val discoveredPrinters: StateFlow<List<DiscoveredNetworkPrinter>> = engine.discoveredPrinters
    val isDiscoveringNetwork: StateFlow<Boolean> = engine.isDiscoveringNetwork
    val scannerState: StateFlow<ScannerAvailabilityState> = engine.scannerState
    val backgroundScanState: StateFlow<BackgroundScanState> = engine.backgroundScanState
    val serverState: StateFlow<NetworkServerState> = engine.serverState
    val connectedClients: StateFlow<List<ConnectedClientDevice>> = engine.connectedClients
    val jobs: StateFlow<List<PrintJobRecord>> = engine.jobs
    val logs: StateFlow<List<ProtocolLogEntry>> = engine.logs

    private val _directPrintState = MutableStateFlow(DirectPrintUiState())
    val directPrintState: StateFlow<DirectPrintUiState> = _directPrintState.asStateFlow()

    private val _scanState = MutableStateFlow(
        ScanUiState(viewMode = engine.settings.value.scanPagesViewMode),
    )
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private val _recentScans = MutableStateFlow<List<RecentScanRecord>>(emptyList())
    val recentScans: StateFlow<List<RecentScanRecord>> = _recentScans.asStateFlow()

    private val _recentScansViewMode = MutableStateFlow(engine.settings.value.recentScansViewMode)
    val recentScansViewMode: StateFlow<RecentScansViewMode> = _recentScansViewMode.asStateFlow()

    private val _recentScansSortBy = MutableStateFlow(RecentScansSortBy.NEWEST)
    val recentScansSortBy: StateFlow<RecentScansSortBy> = _recentScansSortBy.asStateFlow()

    private val _testPagePreview = MutableStateFlow<Bitmap?>(null)
    val testPagePreview: StateFlow<Bitmap?> = _testPagePreview.asStateFlow()

    private val _printerInfoState = MutableStateFlow(PrinterInfoUiState())
    val printerInfoState: StateFlow<PrinterInfoUiState> = _printerInfoState.asStateFlow()

    private val _isConnectingManual = MutableStateFlow(false)
    val isConnectingManual: StateFlow<Boolean> = _isConnectingManual.asStateFlow()

    private val _dismissedProgressJobId = MutableStateFlow<Int?>(null)
    val dismissedProgressJobId: StateFlow<Int?> = _dismissedProgressJobId.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _isInitialLoadComplete = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = _isInitialLoadComplete.asStateFlow()

    init {
        viewModelScope.launch {
            val app = getApplication<Application>()
            _recentScans.value = ScanExportManager.loadRecentScans(app)
            _isInitialLoadComplete.value = true
        }

        // Keep Direct Print, Scan defaults & Grid/List view preferences in sync whenever persisted Settings load or change
        viewModelScope.launch {
            var lastDefaultPrintRes: PrintResolution? = null
            var lastDefaultScanDpi: Int? = null
            var lastRecentViewMode: RecentScansViewMode? = null
            var lastScanPagesViewMode: RecentScansViewMode? = null
            settings.collectLatest { s ->
                if (lastDefaultPrintRes != s.defaultPrintResolution) {
                    lastDefaultPrintRes = s.defaultPrintResolution
                    _directPrintState.update { it.copy(resolution = s.defaultPrintResolution) }
                }
                if (lastDefaultScanDpi != s.defaultScanDpi) {
                    lastDefaultScanDpi = s.defaultScanDpi
                    if (!_scanState.value.isActivelyScanningPage) {
                        _scanState.update { it.copy(params = it.params.copy(dpi = s.defaultScanDpi)) }
                    }
                }
                if (lastRecentViewMode != s.recentScansViewMode) {
                    lastRecentViewMode = s.recentScansViewMode
                    _recentScansViewMode.value = s.recentScansViewMode
                }
                if (lastScanPagesViewMode != s.scanPagesViewMode) {
                    lastScanPagesViewMode = s.scanPagesViewMode
                    _scanState.update { it.copy(viewMode = s.scanPagesViewMode) }
                }
            }
        }

        // Automatically refresh Recent Scans whenever Host recovers undelivered remote scans into Lost/Found
        viewModelScope.launch {
            engine.recentScansVersion.drop(1).collect {
                reloadRecentScans()
            }
        }

        viewModelScope.launch {
            var lastKey = ""
            activePrinter.collectLatest { printer ->
                val key = "${printer.mode}:${printer.addressOrId}"
                if (printer.isConnected) {
                    if (!printer.supportsAdf && _scanState.value.params.source != ScanSourceOption.FLATBED) {
                        _scanState.update { it.copy(params = it.params.copy(source = ScanSourceOption.FLATBED)) }
                    } else if (!printer.supportsAdfDuplex && _scanState.value.params.source == ScanSourceOption.ADF_DUPLEX) {
                        _scanState.update { it.copy(params = it.params.copy(source = ScanSourceOption.ADF_SIMPLEX)) }
                    }
                    if (key != lastKey) {
                        lastKey = key
                        refreshPrinterInfo(silent = true)
                    }
                } else {
                    lastKey = ""
                    _printerInfoState.value = PrinterInfoUiState()
                }
            }
        }

        // Mirror application-scoped background scan progress into ScanUiState so the user can
        // freely leave ScanScreen or close the app while scanning continues seamlessly.
        viewModelScope.launch {
            engine.backgroundScanState.collect { bg ->
                if (bg.isScanning) {
                    _scanState.update { cur ->
                        val matchesSession = bg.targetCollectionId == null ||
                            cur.activeCollectionId == null ||
                            cur.activeCollectionId == bg.targetCollectionId
                        if (matchesSession) {
                            cur.copy(
                                phase = ScanPhase.EXPORT_READY,
                                sessionTitle = cur.sessionTitle.ifBlank { bg.sessionTitle },
                                isActivelyScanningPage = true,
                                currentPageProgress = bg.progress.coerceIn(0f, 1f),
                                currentPageStatus = bg.statusText,
                                errorMessage = null,
                            )
                        } else {
                            cur
                        }
                    }
                } else {
                    val err = bg.errorMessage
                    // If Side 2 of a 2-in-1 ID scan failed (e.g., transient timeout), restore the
                    // pending Side 1 state so the user can retry Side 2 and still merge into 1 page!
                    val failedIdBackPending = if (!err.isNullOrBlank()) {
                        (activeScanIntent as? ScanPassIntent.IdBack)?.pending
                    } else {
                        null
                    }
                    if (failedIdBackPending != null) {
                        activeScanIntent = ScanPassIntent.Normal
                    }
                    _scanState.update { cur ->
                        val nextPhase = if (cur.pages.isNotEmpty()) ScanPhase.EXPORT_READY else ScanPhase.PARAMETERS
                        cur.copy(
                            phase = if (cur.isActivelyScanningPage) nextPhase else cur.phase,
                            isActivelyScanningPage = false,
                            currentPageProgress = 0f,
                            currentPageStatus = "",
                            pendingIdCardFront = failedIdBackPending ?: cur.pendingIdCardFront,
                            errorMessage = err ?: cur.errorMessage,
                        )
                    }
                    if (!err.isNullOrBlank()) {
                        _snackbarMessage.value = err
                        engine.clearBackgroundScanError()
                    }
                }
            }
        }

        // Receive completed & recovered scan handovers from BackgroundScanManager
        viewModelScope.launch {
            engine.scanHandoverEvents.collect { event ->
                val intent = when {
                    event.isMergedTwoInOne -> {
                        val currentIntent = activeScanIntent
                        activeScanIntent = ScanPassIntent.Normal
                        currentIntent
                    }
                    event.isRecovered -> ScanPassIntent.Normal
                    else -> {
                        val currentIntent = activeScanIntent
                        activeScanIntent = ScanPassIntent.Normal
                        currentIntent
                    }
                }

                // Preferred path: BackgroundScanManager already auto-cropped, merged, and saved
                // the single 2-in-1 ID page to disk (works across foreground, background & recovery!)
                if (event.isMergedTwoInOne && event.pages.isNotEmpty()) {
                    reloadRecentScans()
                    val lang = settings.value.appLanguage
                    val mergedItems = event.pages.map { bmp ->
                        ScannedPageItem(
                            id = nextScanPageId.getAndIncrement(),
                            bitmap = bmp,
                            originalBitmap = bmp,
                            dpi = event.dpi,
                            colorLabel = event.colorLabel,
                            pageSizeLabel = event.pageSizeLabel,
                            pageSize = event.pageSize,
                        )
                    }
                    _scanState.update { cur ->
                        val shouldAttach = cur.isActivelyScanningPage ||
                            cur.activeCollectionId == null ||
                            cur.activeCollectionId == event.collectionId ||
                            event.replacedPageId != null ||
                            event.replacedPageIndex != null
                        if (!shouldAttach) {
                            cur.copy(
                                isActivelyScanningPage = false,
                                isMergingIdCard = false,
                                currentPageProgress = 0f,
                                currentPageStatus = "",
                            )
                        } else {
                            val replaceIdx = when {
                                event.replacedPageId != null && event.replacedPageId > 0L ->
                                    cur.pages.indexOfFirst { it.id == event.replacedPageId }
                                event.replacedPageIndex != null && event.replacedPageIndex in cur.pages.indices ->
                                    event.replacedPageIndex
                                else -> -1
                            }
                            val updatedPages = if (replaceIdx >= 0) {
                                cur.pages.toMutableList().apply {
                                    set(replaceIdx, mergedItems.first())
                                    if (mergedItems.size > 1) {
                                        addAll(mergedItems.drop(1))
                                    }
                                }
                            } else {
                                cur.pages + mergedItems
                            }
                            cur.copy(
                                phase = ScanPhase.EXPORT_READY,
                                activeCollectionId = event.collectionId.ifBlank { cur.activeCollectionId },
                                sessionTitle = cur.sessionTitle.ifBlank { event.sessionTitle },
                                pages = updatedPages,
                                selectedPageIndex = if (replaceIdx >= 0) replaceIdx else (updatedPages.size - 1).coerceAtLeast(0),
                                pendingIdCardFront = null,
                                isMergingIdCard = false,
                                isActivelyScanningPage = false,
                                currentPageProgress = 0f,
                                currentPageStatus = "",
                                errorMessage = null,
                            )
                        }
                    }
                    val layoutLabel = (event.mergedLayout ?: _scanState.value.params.idCardLayout).localizedLabel(lang)
                    _snackbarMessage.value = tr(
                        lang,
                        "2-in-1 ID merged (${event.pageSize.displayName} • $layoutLabel)",
                        "2-इन-1 आईडी मर्ज हो गया (${event.pageSize.displayName} • $layoutLabel)",
                    )
                    return@collect
                }

                when (intent) {
                    is ScanPassIntent.IdBack -> {
                        val backBitmap = event.pages.firstOrNull()
                        if (backBitmap == null) {
                            reloadRecentScans()
                            _scanState.update {
                                it.copy(
                                    pendingIdCardFront = null,
                                    isActivelyScanningPage = false,
                                    currentPageProgress = 0f,
                                    currentPageStatus = "",
                                )
                            }
                            return@collect
                        }

                        val pending = intent.pending
                        val lang = settings.value.appLanguage
                        _scanState.update {
                            it.copy(
                                isActivelyScanningPage = false,
                                currentPageProgress = 0f,
                                currentPageStatus = "",
                                pendingIdCardFront = null,
                                isMergingIdCard = true,
                                errorMessage = null,
                            )
                        }

                        val mergedBitmap = runCatching {
                            withContext(Dispatchers.Default) {
                                ScanIdCardMerger.autoCropAndMergeIdCards(
                                    frontRaw = pending.frontBitmap,
                                    backRaw = backBitmap,
                                    pageSize = pending.pageSize,
                                    layout = pending.layout,
                                    dpi = pending.dpi,
                                )
                            }
                        }.getOrDefault(backBitmap)

                        val mergedItem = ScannedPageItem(
                            id = nextScanPageId.getAndIncrement(),
                            bitmap = mergedBitmap,
                            originalBitmap = mergedBitmap,
                            dpi = pending.dpi,
                            colorLabel = pending.colorLabel,
                            pageSizeLabel = "${pending.pageSize.displayName} • 2-in-1 ID",
                            pageSize = pending.pageSize,
                        )

                        _scanState.update { cur ->
                            val existingIdx = cur.pages.indexOfFirst { it.id == pending.pageId }
                            val updatedPages = if (existingIdx >= 0) {
                                cur.pages.toMutableList().apply { set(existingIdx, mergedItem) }
                            } else {
                                cur.pages + mergedItem
                            }
                            cur.copy(
                                phase = ScanPhase.EXPORT_READY,
                                activeCollectionId = event.collectionId.ifBlank { cur.activeCollectionId },
                                sessionTitle = cur.sessionTitle.ifBlank { event.sessionTitle },
                                pages = updatedPages,
                                selectedPageIndex = if (existingIdx >= 0) existingIdx else (updatedPages.size - 1).coerceAtLeast(0),
                                pendingIdCardFront = null,
                                isMergingIdCard = false,
                                isActivelyScanningPage = false,
                                currentPageProgress = 0f,
                                currentPageStatus = "",
                                errorMessage = null,
                            )
                        }

                        val layoutLabel = pending.layout.localizedLabel(lang)
                        val banner = tr(
                            lang,
                            "2-in-1 ID merged (${pending.pageSize.displayName} • $layoutLabel)",
                            "2-इन-1 आईडी मर्ज हो गया (${pending.pageSize.displayName} • $layoutLabel)",
                        )
                        persistActiveScanCollection(successBanner = banner)
                    }

                    is ScanPassIntent.IdFront -> {
                        reloadRecentScans()
                        val capturedParams = intent.params
                        val newItems = event.pages.map { bmp ->
                            ScannedPageItem(
                                id = nextScanPageId.getAndIncrement(),
                                bitmap = bmp,
                                originalBitmap = bmp,
                                dpi = capturedParams.dpi,
                                colorLabel = event.colorLabel,
                                pageSizeLabel = capturedParams.pageSize.displayName,
                                pageSize = capturedParams.pageSize,
                            )
                        }
                        val firstFront = newItems.firstOrNull()
                        val pending = if (firstFront != null && _scanState.value.params.twoInOneIdScan) {
                            PendingIdCardFront(
                                pageId = firstFront.id,
                                frontBitmap = firstFront.bitmap,
                                pageSize = capturedParams.pageSize,
                                layout = capturedParams.idCardLayout,
                                dpi = capturedParams.dpi,
                                colorLabel = event.colorLabel,
                            )
                        } else {
                            null
                        }
                        _scanState.update { cur ->
                            val updated = cur.pages + newItems
                            cur.copy(
                                phase = if (updated.isNotEmpty()) ScanPhase.EXPORT_READY else cur.phase,
                                activeCollectionId = event.collectionId.ifBlank { cur.activeCollectionId },
                                sessionTitle = cur.sessionTitle.ifBlank { event.sessionTitle },
                                pages = updated,
                                selectedPageIndex = (updated.size - 1).coerceAtLeast(0),
                                pendingIdCardFront = pending,
                                isActivelyScanningPage = false,
                                currentPageProgress = 0f,
                                currentPageStatus = "",
                                errorMessage = null,
                            )
                        }
                    }

                    ScanPassIntent.Normal -> {
                        reloadRecentScans()
                        val newItems = event.pages.map { bmp ->
                            ScannedPageItem(
                                id = nextScanPageId.getAndIncrement(),
                                bitmap = bmp,
                                originalBitmap = bmp,
                                dpi = event.dpi,
                                colorLabel = event.colorLabel,
                                pageSizeLabel = event.pageSizeLabel,
                                pageSize = event.pageSize,
                            )
                        }
                        _scanState.update { cur ->
                            val shouldAttachToOpenSession = when {
                                cur.isActivelyScanningPage -> true
                                cur.activeCollectionId == event.collectionId -> true
                                !event.isRecovered && cur.activeCollectionId == null -> true
                                !event.isRecovered && event.collectionId.isBlank() -> true
                                event.isRecovered && cur.activeCollectionId == null && cur.pages.isEmpty() -> true
                                else -> false
                            }
                            if (shouldAttachToOpenSession) {
                                val updated = cur.pages + newItems
                                cur.copy(
                                    phase = if (updated.isNotEmpty()) ScanPhase.EXPORT_READY else cur.phase,
                                    activeCollectionId = event.collectionId.ifBlank { cur.activeCollectionId },
                                    sessionTitle = cur.sessionTitle.ifBlank { event.sessionTitle },
                                    pages = updated,
                                    selectedPageIndex = (updated.size - 1).coerceAtLeast(0),
                                    isActivelyScanningPage = false,
                                    currentPageProgress = 0f,
                                    currentPageStatus = "",
                                    errorMessage = null,
                                )
                            } else {
                                cur.copy(
                                    isActivelyScanningPage = false,
                                    currentPageProgress = 0f,
                                    currentPageStatus = "",
                                )
                            }
                        }
                        if (event.bannerMessage.isNotBlank()) {
                            _snackbarMessage.value = event.bannerMessage
                        }
                    }
                }
            }
        }
    }

    // --- Recent Scans (Home Screen) ---

    fun reloadRecentScans() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            _recentScans.value = ScanExportManager.loadRecentScans(app)
        }
    }

    fun setRecentScansViewMode(mode: RecentScansViewMode) {
        _recentScansViewMode.value = mode
        engine.updateSettings { it.copy(recentScansViewMode = mode) }
    }

    fun setRecentScansSortBy(sortBy: RecentScansSortBy) {
        _recentScansSortBy.value = sortBy
    }

    fun deleteRecentScan(id: String) {
        deleteRecentScans(setOf(id))
    }

    fun deleteRecentScans(ids: Set<String>) {
        if (ids.isEmpty()) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _recentScans.value = ScanExportManager.deleteRecentScans(app, ids)
            val lang = settings.value.appLanguage
            _snackbarMessage.value = if (ids.size == 1) {
                tr(lang, "Deleted 1 scan", "1 स्कैन हटाया गया")
            } else {
                tr(lang, "Deleted ${ids.size} scans", "${ids.size} स्कैन हटाए गए")
            }
        }
    }

    fun openRecentScanCollection(record: RecentScanRecord, onReady: () -> Unit) {
        viewModelScope.launch {
            val bitmaps = ScanExportManager.loadCollectionPages(record)
            if (bitmaps.isEmpty()) {
                _snackbarMessage.value = "Could not open saved scan pages"
                return@launch
            }
            val preservedParams = _scanState.value.params.copy(twoInOneIdScan = false)
            val preservedViewMode = _scanState.value.viewMode
            val bg = engine.backgroundScanState.value
            val isScanningThis = bg.isScanning && bg.targetCollectionId == record.id
            val items = bitmaps.map { bmp ->
                ScannedPageItem(
                    id = nextScanPageId.getAndIncrement(),
                    bitmap = bmp,
                    originalBitmap = bmp,
                    dpi = preservedParams.dpi,
                    colorLabel = preservedParams.colorMode.shortLabel,
                    pageSizeLabel = preservedParams.pageSize.displayName,
                    pageSize = preservedParams.pageSize,
                )
            }
            _scanState.value = ScanUiState(
                phase = ScanPhase.EXPORT_READY,
                activeCollectionId = record.id,
                sessionTitle = record.title,
                params = preservedParams,
                pages = items,
                selectedPageIndex = 0,
                isActivelyScanningPage = isScanningThis,
                currentPageProgress = if (isScanningThis) bg.progress else 0f,
                currentPageStatus = if (isScanningThis) bg.statusText else "",
                pendingIdCardFront = null,
                viewMode = preservedViewMode,
            )
            onReady()
        }
    }

    fun refreshAllConnections() {
        val lang = settings.value.appLanguage
        val usb = engine.scanUsbDevices(clearManualDisconnect = true)
        engine.refreshNetworkInfo()
        if (usb.status == UsbConnectionStatus.PERMISSION_REQUIRED) {
            engine.usbPrinterManager.requestPermissionForActivePrinter()
            _snackbarMessage.value = tr(
                lang,
                "Requesting USB OTG permission for ${usb.displayTitle}...",
                "${usb.displayTitle} के लिए USB OTG अनुमति का अनुरोध किया जा रहा है...",
            )
            return
        }
        if (activePrinter.value.isConnected) {
            refreshPrinterInfo(silent = false)
            engine.syncPendingRemoteScansIfConnected()
            _snackbarMessage.value = tr(
                lang,
                "Refreshed ${activePrinter.value.displayName}",
                "${activePrinter.value.displayName} रीफ़्रेश किया गया",
            )
        } else {
            engine.startNetworkDiscovery()
            _snackbarMessage.value = tr(
                lang,
                "Scanning USB OTG & Wi-Fi printers...",
                "USB OTG और वाई-फ़ाई प्रिंटर खोजे जा रहे हैं...",
            )
        }
    }

    fun selectRecentScanForPrint(record: RecentScanRecord, onReady: () -> Unit) {
        val path = record.localFilePath ?: record.thumbnailPath
        val file = File(path)
        if (!file.exists()) {
            _snackbarMessage.value = "Local scan file is no longer available"
            return
        }
        val mime = if (file.extension.equals("pdf", true)) "application/pdf" else "image/jpeg"
        selectLocalFileForPrint(file, record.title, mime, onReady)
    }

    fun printActiveScanCollection(selectedPageIds: Set<Long> = emptySet(), onReady: () -> Unit) {
        val cur = _scanState.value
        val targetPages = if (selectedPageIds.isNotEmpty()) {
            cur.pages.filter { it.id in selectedPageIds }
        } else {
            cur.pages
        }
        if (targetPages.isEmpty()) return

        // If printing the full already-saved collection, reuse its local PDF directly
        if (selectedPageIds.isEmpty() || targetPages.size == cur.pages.size) {
            val collectionId = cur.activeCollectionId
            val existing = _recentScans.value.firstOrNull { it.id == collectionId }
            if (existing != null) {
                selectRecentScanForPrint(existing, onReady)
                return
            }
        }

        val app = getApplication<Application>()
        val bitmaps = targetPages.map { it.bitmap }
        val pageSizes = targetPages.map { it.pageSize }
        val title = if (selectedPageIds.isNotEmpty() && targetPages.size < cur.pages.size) {
            "${cur.sessionTitle.ifBlank { "Scan" }}_${targetPages.size}p"
        } else {
            cur.sessionTitle.ifBlank { "Scan" }
        }
        viewModelScope.launch {
            ScanExportManager.createTempPdfForPrint(app, bitmaps, title, pageSizes).fold(
                onSuccess = { pdfFile ->
                    selectLocalFileForPrint(pdfFile, "$title.pdf", "application/pdf", onReady)
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: "Could not prepare selected pages for print"
                },
            )
        }
    }

    // --- Connection & Mode Management (Case I OTG, Case II Wireless) ---

    fun disconnectActivePrinter(cancelRunningProcesses: Boolean = false) {
        val lang = settings.value.appLanguage
        if (cancelRunningProcesses) {
            jobs.value.forEach { job ->
                if (job.status == PrintJobStatus.PENDING ||
                    job.status == PrintJobStatus.RENDERING ||
                    job.status == PrintJobStatus.TRANSMITTING
                ) {
                    engine.cancelJob(job.id)
                }
            }
            if (backgroundScanState.value.isScanning || _scanState.value.isActivelyScanningPage) {
                cancelActiveScan()
            }
        }
        abortScanSessionIfLocked()
        engine.disconnectActivePrinter()
        _snackbarMessage.value = tr(lang, "Device disconnected", "डिवाइस डिस्कनेक्ट हो गया")
    }

    fun connectRemotePrinter(printer: DiscoveredNetworkPrinter) {
        val lang = settings.value.appLanguage
        engine.connectRemotePrinter(printer)
        _snackbarMessage.value = tr(
            lang,
            "Connected wirelessly to ${printer.name}",
            "${printer.name} से वायरलेस कनेक्ट हो गया",
        )
    }

    fun connectManualRemoteHost(host: String, port: Int, onConnected: () -> Unit = {}) {
        viewModelScope.launch {
            val lang = settings.value.appLanguage
            _isConnectingManual.value = true
            val result = engine.connectManualRemoteHost(host, port)
            _isConnectingManual.value = false
            result.fold(
                onSuccess = { printer ->
                    _snackbarMessage.value = tr(
                        lang,
                        "Connected to ${printer.name} (${printer.displayAddress})",
                        "${printer.name} (${printer.displayAddress}) से कनेक्ट हो गया",
                    )
                    onConnected()
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: tr(
                        lang,
                        "Could not reach printer at $host:$port",
                        "$host:$port पर प्रिंटर नहीं मिल सका",
                    )
                },
            )
        }
    }

    fun startNetworkDiscovery() {
        engine.startNetworkDiscovery()
    }

    fun stopNetworkDiscovery() {
        engine.stopNetworkDiscovery()
    }

    fun toggleServer() {
        val ctx = getApplication<Application>()
        val lang = settings.value.appLanguage
        if (serverState.value.isRunning) {
            PrintServerService.stop(ctx)
            _snackbarMessage.value = tr(lang, "Remote Wi-Fi server stopped", "रिमोट वाई-फ़ाई सर्वर बंद हो गया")
        } else {
            if (!engine.canHostRemoteServer()) {
                _snackbarMessage.value = tr(
                    lang,
                    "Direct USB OTG connection is required to host the remote server",
                    "रिमोट सर्वर चलाने के लिए डायरेक्ट USB OTG कनेक्शन आवश्यक है",
                )
                return
            }
            engine.refreshNetworkInfo()
            PrintServerService.start(ctx)
            _snackbarMessage.value = tr(lang, "Starting Wi-Fi server...", "वाई-फ़ाई सर्वर शुरू हो रहा है...")
        }
    }

    fun refreshNetwork() {
        val lang = settings.value.appLanguage
        val ip = engine.refreshNetworkInfo()
        _snackbarMessage.value = if (ip != null) {
            tr(lang, "Active Wi-Fi IP: $ip", "सक्रिय वाई-फ़ाई IP: $ip")
        } else {
            tr(lang, "No Wi-Fi / Hotspot IPv4 detected", "कोई वाई-फ़ाई / हॉटस्पॉट IPv4 नहीं मिला")
        }
    }

    fun scanUsbPrinters() {
        val info = engine.scanUsbDevices()
        _snackbarMessage.value = info.statusMessage
    }

    fun requestUsbPermission() {
        engine.usbPrinterManager.requestPermissionForActivePrinter()
    }

    // --- Printer Health & Status (Full-Screen Page) ---

    fun refreshPrinterInfo(silent: Boolean = false) {
        if (!activePrinter.value.isConnected) {
            _printerInfoState.value = PrinterInfoUiState()
            return
        }
        viewModelScope.launch {
            _printerInfoState.update { it.copy(isLoading = !silent, errorMessage = null) }
            val result = engine.queryPrinterDiagnostics()
            result.fold(
                onSuccess = { diag ->
                    _printerInfoState.update {
                        it.copy(
                            isLoading = false,
                            diagnostics = diag,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { err ->
                    _printerInfoState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (silent) it.errorMessage else (err.message ?: "Unable to query live printer status"),
                        )
                    }
                },
            )
        }
    }

    // --- Printing & Direct Print Parameters ---

    fun printTestPage() {
        val active = activePrinter.value
        if (!active.isConnected) {
            _snackbarMessage.value = "Connect a printer first"
            return
        }
        val s = settings.value
        val dp = _directPrintState.value
        val options = PrintJobOptions(
            pagesPerSheet = PagesPerSheet.ONE,
            duplexMode = DuplexMode.SIMPLEX,
            paperSize = dp.paperSize,
            resolution = dp.resolution,
            printerLanguage = s.printerLanguage,
            copies = 1,
            tonerSave = dp.tonerSave,
            drawNUpBorders = dp.drawBorders,
            ditherImages = dp.colorScheme.ditherImages,
            orientation = dp.orientation,
        )
        engine.printTestPage(options).fold(
            onSuccess = { job ->
                _dismissedProgressJobId.value = null
                _snackbarMessage.value = "Sending Test Page (#${job.id}) to ${active.displayName}"
            },
            onFailure = { err ->
                _snackbarMessage.value = err.message ?: "Printer is busy — please wait"
            },
        )
    }

    fun selectTestPageForPrint(onReady: () -> Unit = {}) {
        val active = activePrinter.value
        if (!active.isConnected) {
            _snackbarMessage.value = "Connect a printer first"
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val s = settings.value
            val dp = _directPrintState.value
            val options = PrintJobOptions(
                pagesPerSheet = dp.pagesPerSheet,
                duplexMode = dp.duplexMode,
                paperSize = dp.paperSize,
                resolution = dp.resolution,
                printerLanguage = s.printerLanguage,
                copies = dp.copies,
                tonerSave = dp.tonerSave,
                drawNUpBorders = dp.drawBorders,
                ditherImages = dp.colorScheme.ditherImages,
                orientation = dp.orientation,
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pdfFile = TestPageGenerator.generateTestPagePdf(
                        context = app,
                        options = options,
                        serverState = serverState.value,
                        usbInfo = engine.currentUsbInfo(),
                        dpi = 150,
                    )
                    val meta = DocumentRenderer.inspectFile(pdfFile, "application/pdf")
                    Pair(pdfFile, meta)
                }
            }
            result.fold(
                onSuccess = { (pdfFile, meta) ->
                    _directPrintState.value.selectedDocument?.cachedFallbackFile?.delete()
                    _directPrintState.update {
                        it.copy(
                            selectedDocument = SelectedDocumentState(
                                id = "printbridge_test_page_${System.currentTimeMillis()}",
                                uri = null,
                                localFile = pdfFile,
                                source = DocumentSource.LocalFile(pdfFile),
                                cachedFallbackFile = null,
                                displayName = "${active.displayName} Test Page",
                                metadata = meta,
                                isRenderingPreview = true,
                                isTestPage = true,
                            ),
                            imageMarginMm = PageMargins.DEFAULT_MARGIN_MM,
                            imageScaling = PrintScaling.AUTO_FIT,
                            imagePanX = 0f,
                            imagePanY = 0f,
                            errorMessage = null,
                        )
                    }
                    regenerateDirectPreview()
                    onReady()
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: "Could not generate test page preview"
                },
            )
        }
    }

    fun openTestPagePreview() {
        selectTestPageForPrint()
    }

    fun dismissTestPagePreview() {
        _testPagePreview.value = null
    }

    fun selectDocumentUri(uri: Uri) {
        viewModelScope.launch {
            _directPrintState.update { it.copy(errorMessage = null) }
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val mime = app.contentResolver.getType(uri)
                    val displayName = resolveDisplayName(uri) ?: "Document"
                    val resolved = DocumentRenderer.resolveUriZeroCopy(
                        context = app,
                        uri = uri,
                        declaredMime = mime,
                        fallbackPrefix = "preview_",
                    )
                    Pair(displayName, resolved)
                }
            }

            result.fold(
                onSuccess = { (name, resolved) ->
                    _directPrintState.value.selectedDocument?.cachedFallbackFile?.delete()
                    _directPrintState.update {
                        it.copy(
                            selectedDocument = SelectedDocumentState(
                                id = uri.toString(),
                                uri = uri,
                                localFile = null,
                                source = resolved.source,
                                cachedFallbackFile = resolved.cachedFallbackFile,
                                displayName = name,
                                metadata = resolved.metadata,
                                isRenderingPreview = true,
                            ),
                            imageMarginMm = PageMargins.DEFAULT_MARGIN_MM,
                            imageScaling = PrintScaling.AUTO_FIT,
                            imagePanX = 0f,
                            imagePanY = 0f,
                            errorMessage = null,
                        )
                    }
                    regenerateDirectPreview()
                },
                onFailure = { err ->
                    _directPrintState.update {
                        it.copy(errorMessage = err.message ?: "Unsupported file — please choose a PDF or image")
                    }
                    _snackbarMessage.value = err.message ?: "Unable to open document"
                },
            )
        }
    }

    private fun selectLocalFileForPrint(
        file: File,
        displayName: String,
        mime: String,
        onReady: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _directPrintState.update { it.copy(errorMessage = null) }
            val result = withContext(Dispatchers.IO) {
                runCatching { DocumentRenderer.inspectFile(file, mime) }
            }
            result.fold(
                onSuccess = { meta ->
                    _directPrintState.value.selectedDocument?.cachedFallbackFile?.delete()
                    _directPrintState.update {
                        it.copy(
                            selectedDocument = SelectedDocumentState(
                                id = file.absolutePath,
                                uri = null,
                                localFile = file,
                                source = DocumentSource.LocalFile(file),
                                cachedFallbackFile = null,
                                displayName = displayName,
                                metadata = meta,
                                isRenderingPreview = true,
                            ),
                            imageMarginMm = PageMargins.DEFAULT_MARGIN_MM,
                            imageScaling = PrintScaling.AUTO_FIT,
                            imagePanX = 0f,
                            imagePanY = 0f,
                            errorMessage = null,
                        )
                    }
                    regenerateDirectPreview()
                    onReady()
                },
                onFailure = { err ->
                    _snackbarMessage.value = err.message ?: "Could not load document for printing"
                },
            )
        }
    }

    fun clearSelectedDocument() {
        _directPrintState.value.selectedDocument?.cachedFallbackFile?.delete()
        _directPrintState.update { it.copy(selectedDocument = null, errorMessage = null) }
    }

    fun setDirectPaperSize(size: PaperSize) {
        _directPrintState.update { it.copy(paperSize = size) }
        regenerateDirectPreview()
    }

    fun setDirectPagesPerSheet(nUp: PagesPerSheet) {
        _directPrintState.update { it.copy(pagesPerSheet = nUp) }
        regenerateDirectPreview()
    }

    fun setDirectDuplexMode(duplex: DuplexMode) {
        _directPrintState.update { it.copy(duplexMode = duplex) }
        if (_directPrintState.value.selectedDocument?.isTestPage == true) {
            regenerateDirectPreview()
        }
    }

    fun setDirectResolution(resolution: PrintResolution) {
        _directPrintState.update { it.copy(resolution = resolution) }
        if (_directPrintState.value.selectedDocument?.isTestPage == true) {
            regenerateDirectPreview()
        }
    }

    fun setDirectOrientation(orientation: PrintOrientation) {
        _directPrintState.update { it.copy(orientation = orientation) }
        regenerateDirectPreview()
    }

    fun setDirectColorScheme(colorScheme: PrintColorScheme) {
        _directPrintState.update { it.copy(colorScheme = colorScheme) }
    }

    fun setDirectTonerSave(enabled: Boolean) {
        _directPrintState.update { it.copy(tonerSave = enabled) }
        if (_directPrintState.value.selectedDocument?.isTestPage == true) {
            regenerateDirectPreview()
        }
    }

    fun setDirectCopies(copies: Int) {
        _directPrintState.update { it.copy(copies = copies.coerceIn(1, 99)) }
    }

    fun setDirectDrawBorders(draw: Boolean) {
        _directPrintState.update { it.copy(drawBorders = draw) }
        regenerateDirectPreview()
    }

    fun setDirectImageMarginMm(marginMm: Float) {
        val clamped = marginMm.coerceIn(0f, 25f)
        _directPrintState.update { it.copy(imageMarginMm = clamped) }
        regenerateDirectPreview(debounceMs = 45L)
    }

    fun setDirectImageScaling(scaling: PrintScaling) {
        _directPrintState.update {
            it.copy(
                imageScaling = scaling,
                imagePanX = if (scaling == PrintScaling.FILL) it.imagePanX else 0f,
                imagePanY = if (scaling == PrintScaling.FILL) it.imagePanY else 0f,
            )
        }
        regenerateDirectPreview()
    }

    fun setDirectImagePan(panX: Float, panY: Float) {
        _directPrintState.update {
            it.copy(
                imagePanX = panX.coerceIn(-1f, 1f),
                imagePanY = panY.coerceIn(-1f, 1f),
            )
        }
        regenerateDirectPreview(debounceMs = 45L)
    }

    fun resetDirectImagePan() {
        _directPrintState.update { it.copy(imagePanX = 0f, imagePanY = 0f) }
        regenerateDirectPreview()
    }

    fun printSelectedDocument() {
        val state = _directPrintState.value
        val doc = state.selectedDocument ?: return
        val s = settings.value
        val isImage = doc.metadata.kind.isImage
        val effectiveMargins = if (isImage) {
            val m = state.imageMarginMm.coerceIn(0f, 25f)
            PageMargins(topMm = m, bottomMm = m, leftMm = m, rightMm = m)
        } else {
            PageMargins.DEFAULT
        }
        val effectiveScaling = if (isImage) state.imageScaling else PrintScaling.AUTO_FIT
        val options = PrintJobOptions(
            pagesPerSheet = state.pagesPerSheet,
            duplexMode = state.duplexMode,
            paperSize = state.paperSize,
            resolution = state.resolution,
            printerLanguage = s.printerLanguage,
            copies = state.copies,
            tonerSave = state.tonerSave,
            drawNUpBorders = state.drawBorders,
            ditherImages = state.colorScheme.ditherImages,
            margins = effectiveMargins,
            scaling = effectiveScaling,
            orientation = state.orientation,
            imagePanX = if (isImage && effectiveScaling == PrintScaling.FILL) state.imagePanX else 0f,
            imagePanY = if (isImage && effectiveScaling == PrintScaling.FILL) state.imagePanY else 0f,
        )

        viewModelScope.launch {
            _directPrintState.update { it.copy(isSubmitting = true, errorMessage = null) }
            val result = when {
                doc.isTestPage -> engine.printTestPage(options)
                doc.localFile != null -> engine.enqueueLocalFileDocument(
                    file = doc.localFile,
                    displayName = doc.displayName,
                    declaredMime = doc.metadata.kind.canonicalMime,
                    options = options,
                )
                doc.uri != null -> engine.enqueueLocalDocument(
                    uri = doc.uri,
                    displayName = doc.displayName,
                    declaredMime = doc.metadata.kind.canonicalMime,
                    options = options,
                )
                else -> Result.failure(IllegalStateException("No document selected"))
            }
            _directPrintState.update { it.copy(isSubmitting = false) }
            result.fold(
                onSuccess = { job ->
                    _dismissedProgressJobId.value = null
                    _snackbarMessage.value = "Printing ${doc.displayName} (#${job.id})"
                },
                onFailure = { err ->
                    _directPrintState.update { it.copy(errorMessage = err.message) }
                    _snackbarMessage.value = err.message ?: "Failed to start print job"
                },
            )
        }
    }

    fun dismissPrintProgressBar(jobId: Int) {
        _dismissedProgressJobId.value = jobId
    }

    fun cancelJob(jobId: Int) {
        if (engine.cancelJob(jobId)) {
            _snackbarMessage.value = "Print job #$jobId canceled"
        }
    }

    fun clearCompletedJobs() {
        engine.clearCompletedJobs()
    }

    fun clearLogs() {
        engine.clearLogs()
    }

    // --- On-Demand Scanning Workflow (Scan only when user presses Scan -> Auto-save to Recent Scans -> Export) ---

    /**
     * Starts a brand-new scan session when idle, or resumes viewing the active background scan
     * if one is already running so navigating back and re-entering Scan never cancels progress.
     */
    fun startNewScanSession() {
        val bg = engine.backgroundScanState.value
        if (bg.isScanning) {
            _scanState.update { cur ->
                cur.copy(
                    phase = ScanPhase.EXPORT_READY,
                    sessionTitle = cur.sessionTitle.ifBlank { bg.sessionTitle },
                    isActivelyScanningPage = true,
                    currentPageProgress = bg.progress,
                    currentPageStatus = bg.statusText,
                    errorMessage = null,
                )
            }
            return
        }
        val preservedParams = _scanState.value.params.copy(
            dpi = settings.value.defaultScanDpi,
            twoInOneIdScan = false,
        )
        val preservedViewMode = _scanState.value.viewMode
        activeScanIntent = ScanPassIntent.Normal
        _scanState.value = ScanUiState(
            phase = ScanPhase.PARAMETERS,
            activeCollectionId = null,
            sessionTitle = "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}",
            params = preservedParams,
            pendingIdCardFront = null,
            viewMode = preservedViewMode,
        )
    }

    /**
     * When the user modifies scan parameters inside an existing scan collage (`EXPORT_READY`),
     * revert any pending 2-in-1 ID workflow back to normal scanning so subsequent scans behave
     * as regular pages unless the user explicitly enables "2-in-1 ID Scanning" again.
     */
    private fun updateRegularScanParameter(transform: (ScanParameters) -> ScanParameters) {
        _scanState.update { cur ->
            val updatedParams = transform(cur.params)
            val inCollage = cur.phase == ScanPhase.EXPORT_READY
            if (inCollage) {
                cur.copy(
                    params = updatedParams.copy(twoInOneIdScan = false),
                    pendingIdCardFront = null,
                    errorMessage = null,
                )
            } else {
                cur.copy(
                    params = updatedParams,
                    errorMessage = null,
                )
            }
        }
    }

    fun updateScanSource(source: ScanSourceOption) {
        updateRegularScanParameter { it.copy(source = source) }
    }

    fun updateScanColor(color: ScanColorOption) {
        updateRegularScanParameter { it.copy(colorMode = color) }
    }

    fun updateScanDpi(dpi: Int) {
        updateRegularScanParameter { it.copy(dpi = dpi) }
    }

    fun updateScanPageSize(size: ScanPageSizeOption) {
        updateRegularScanParameter { it.copy(pageSize = size) }
    }

    fun updateScanBrightness(brightness: Int) {
        updateRegularScanParameter { it.copy(brightness = brightness.coerceIn(0, 100)) }
    }

    fun updateScanContrast(contrast: Int) {
        updateRegularScanParameter { it.copy(contrast = contrast.coerceIn(0, 100)) }
    }

    fun updateScanTwoInOneId(enabled: Boolean) {
        _scanState.update { cur ->
            cur.copy(
                params = cur.params.copy(twoInOneIdScan = enabled),
                pendingIdCardFront = if (enabled) cur.pendingIdCardFront else null,
                errorMessage = null,
            )
        }
    }

    fun updateScanIdCardLayout(layout: IdCardMergeLayout) {
        _scanState.update { cur ->
            cur.copy(
                params = cur.params.copy(idCardLayout = layout),
                errorMessage = null,
            )
        }
    }

    fun setScanViewMode(mode: RecentScansViewMode) {
        _scanState.update { it.copy(viewMode = mode) }
        engine.updateSettings { it.copy(scanPagesViewMode = mode) }
    }

    /**
     * Starts a background scan pass via [BackgroundScanManager]:
     * - If 2-in-1 ID scanning is enabled (and not waiting for Side 2), scans Side 1 and prompts
     *   the user to turn the ID card over before scanning Side 2 and auto-cropping/merging.
     * - Otherwise runs a normal single-page scan.
     */
    fun scanSinglePass() {
        if (_scanState.value.isActivelyScanningPage ||
            _scanState.value.isMergingIdCard ||
            engine.backgroundScanState.value.isScanning
        ) return

        val cur = _scanState.value
        val pendingFront = cur.pendingIdCardFront
        if (pendingFront != null) {
            confirmScanIdBack()
            return
        }

        val freshTitle = cur.sessionTitle.ifBlank {
            "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
        }
        val currentParams = cur.params
        val nextPageNum = cur.pages.size + 1
        val lang = settings.value.appLanguage

        activeScanIntent = if (currentParams.twoInOneIdScan) {
            ScanPassIntent.IdFront(currentParams)
        } else {
            ScanPassIntent.Normal
        }

        val statusLabel = if (currentParams.twoInOneIdScan) {
            tr(lang, "Scanning ID Side 1...", "आईडी साइड 1 स्कैन हो रहा है...")
        } else {
            tr(lang, "Starting background scan (Page $nextPageNum)...", "बैकग्राउंड स्कैन शुरू हो रहा है (पेज $nextPageNum)...")
        }

        _scanState.update {
            it.copy(
                phase = ScanPhase.EXPORT_READY,
                sessionTitle = freshTitle,
                isActivelyScanningPage = true,
                currentPageProgress = 0.04f,
                currentPageStatus = statusLabel,
                pendingIdCardFront = null,
                errorMessage = null,
                exportSuccessMessage = null,
            )
        }

        engine.startBackgroundScan(
            params = currentParams,
            sessionTitle = freshTitle,
            targetCollectionId = cur.activeCollectionId,
            existingPagesSnapshot = cur.pages.map { it.bitmap },
            nextPageNumber = nextPageNum,
        )
    }

    /**
     * User turned the ID card over and pressed "Scan" to capture Side 2, auto-crop both sides,
     * and merge them cleanly into the selected output page size (A4 / A3 / Letter).
     */
    fun confirmScanIdBack() {
        val cur = _scanState.value
        val pending = cur.pendingIdCardFront ?: return
        if (cur.isActivelyScanningPage || cur.isMergingIdCard || engine.backgroundScanState.value.isScanning) return

        val lang = settings.value.appLanguage
        val freshTitle = cur.sessionTitle.ifBlank {
            "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
        }
        val side2Params = cur.params.copy(
            pageSize = pending.pageSize,
            dpi = pending.dpi,
        )
        activeScanIntent = ScanPassIntent.IdBack(pending)

        _scanState.update {
            it.copy(
                phase = ScanPhase.EXPORT_READY,
                sessionTitle = freshTitle,
                isActivelyScanningPage = true,
                currentPageProgress = 0.04f,
                currentPageStatus = tr(lang, "Scanning ID Side 2...", "आईडी साइड 2 स्कैन हो रहा है..."),
                pendingIdCardFront = null,
                errorMessage = null,
                exportSuccessMessage = null,
            )
        }

        val frontIdx = cur.pages.indexOfFirst { it.id == pending.pageId }
            .let { if (it >= 0) it else (cur.pages.lastIndex).coerceAtLeast(0) }
        val mergeSpec = IdCardMergeSpec(
            frontPageIndex = frontIdx,
            frontPageId = pending.pageId,
            frontBitmap = pending.frontBitmap,
            pageSize = pending.pageSize,
            layout = pending.layout,
            dpi = pending.dpi,
        )

        engine.startBackgroundScan(
            params = side2Params,
            sessionTitle = freshTitle,
            targetCollectionId = cur.activeCollectionId,
            existingPagesSnapshot = cur.pages.map { it.bitmap },
            nextPageNumber = cur.pages.size + 1,
            idCardMergeSpec = mergeSpec,
        )
    }

    /**
     * User declined to turn the ID card over — keep Side 1 cleanly as a regular normal page.
     */
    fun declineIdBackScan() {
        val hadPending = _scanState.value.pendingIdCardFront != null
        activeScanIntent = ScanPassIntent.Normal
        _scanState.update { cur ->
            cur.copy(pendingIdCardFront = null)
        }
        if (hadPending) {
            val lang = settings.value.appLanguage
            _snackbarMessage.value = tr(
                lang,
                "Kept scanned page as a normal page",
                "स्कैन किया गया पेज सामान्य पेज के रूप में रखा गया",
            )
        }
    }

    fun cancelActiveScan() {
        activeScanIntent = ScanPassIntent.Normal
        engine.cancelBackgroundScan()
        _scanState.update { cur ->
            cur.copy(
                phase = if (cur.pages.isNotEmpty()) ScanPhase.EXPORT_READY else ScanPhase.PARAMETERS,
                isActivelyScanningPage = false,
                currentPageProgress = 0f,
                currentPageStatus = "",
                pendingIdCardFront = null,
                isMergingIdCard = false,
            )
        }
    }

    private fun persistActiveScanCollection(successBanner: String? = null) {
        val snapshot = _scanState.value
        val bitmaps = snapshot.pages.map { it.bitmap }
        val app = getApplication<Application>()
        if (bitmaps.isEmpty()) {
            val colId = snapshot.activeCollectionId
            if (colId != null) {
                viewModelScope.launch {
                    _recentScans.value = ScanExportManager.deleteRecentScan(app, colId)
                    _scanState.update { it.copy(activeCollectionId = null) }
                }
            }
            return
        }
        viewModelScope.launch {
            val result = ScanExportManager.saveOrUpdateScanCollection(
                context = app,
                existingId = snapshot.activeCollectionId,
                title = snapshot.sessionTitle,
                pages = bitmaps,
            )
            result.onSuccess { savedRecord ->
                _scanState.update { it.copy(activeCollectionId = savedRecord.id) }
                reloadRecentScans()
                if (!successBanner.isNullOrBlank()) {
                    _snackbarMessage.value = successBanner
                }
            }
        }
    }

    /**
     * Intentionally preserves active background scans so the user can navigate back or close the
     * app while scanning continues in the background. Only releases any legacy continuous lock.
     */
    fun abortScanSessionIfLocked() {
        engine.endContinuousScanSession()
    }

    fun selectScanPage(index: Int) {
        _scanState.update { cur ->
            if (cur.pages.isEmpty()) cur
            else cur.copy(selectedPageIndex = index.coerceIn(0, cur.pages.lastIndex))
        }
    }

    fun rotateScanPageAt(index: Int) {
        val cur = _scanState.value
        if (index !in cur.pages.indices) return
        val item = cur.pages[index]
        viewModelScope.launch(Dispatchers.Default) {
            val rotated = ScanExportManager.rotateBitmap(item.bitmap, 90f)
            _scanState.update { state ->
                if (index !in state.pages.indices || state.pages[index].id != item.id) {
                    state
                } else {
                    val mutable = state.pages.toMutableList()
                    mutable[index] = item.copy(
                        bitmap = rotated,
                        originalBitmap = item.originalBitmap ?: item.bitmap,
                    )
                    state.copy(pages = mutable)
                }
            }
            if (!_scanState.value.isActivelyScanningPage && _scanState.value.activeCollectionId != null) {
                persistActiveScanCollection()
            }
        }
    }

    /**
     * Applies a free-style perspective-cropped or auto-adjusted bitmap to the specified scan page
     * while preserving [ScannedPageItem.originalBitmap] so the user can always restore the original.
     */
    fun applyCroppedPageAt(index: Int, croppedBitmap: Bitmap) {
        val cur = _scanState.value
        if (index !in cur.pages.indices) return
        val item = cur.pages[index]
        _scanState.update { state ->
            if (index !in state.pages.indices || state.pages[index].id != item.id) {
                state
            } else {
                val mutable = state.pages.toMutableList()
                mutable[index] = item.copy(
                    bitmap = croppedBitmap,
                    originalBitmap = item.originalBitmap ?: item.bitmap,
                )
                state.copy(pages = mutable)
            }
        }
        if (!_scanState.value.isActivelyScanningPage) {
            val lang = settings.value.appLanguage
            persistActiveScanCollection(
                tr(lang, "Saved cropped Page ${index + 1}", "पेज ${index + 1} क्रॉप और सेव हो गया"),
            )
        }
    }

    fun deleteScanPageAt(index: Int) {
        _scanState.update { cur ->
            if (index !in cur.pages.indices) return@update cur
            val mutable = cur.pages.toMutableList()
            mutable.removeAt(index)
            cur.copy(
                pages = mutable,
                selectedPageIndex = index.coerceAtMost((mutable.size - 1).coerceAtLeast(0)),
                phase = if (mutable.isEmpty() && !cur.isActivelyScanningPage) ScanPhase.PARAMETERS else cur.phase,
            )
        }
        if (!_scanState.value.isActivelyScanningPage && _scanState.value.activeCollectionId != null) {
            persistActiveScanCollection()
        }
    }

    fun deleteSelectedScanPages(selectedPageIds: Set<Long>) {
        if (selectedPageIds.isEmpty()) return
        val removedCount = _scanState.value.pages.count { it.id in selectedPageIds }
        if (removedCount == 0) return
        _scanState.update { cur ->
            val remaining = cur.pages.filterNot { it.id in selectedPageIds }
            cur.copy(
                pages = remaining,
                selectedPageIndex = cur.selectedPageIndex.coerceAtMost((remaining.size - 1).coerceAtLeast(0)),
                phase = if (remaining.isEmpty() && !cur.isActivelyScanningPage) ScanPhase.PARAMETERS else cur.phase,
            )
        }
        if (!_scanState.value.isActivelyScanningPage && _scanState.value.activeCollectionId != null) {
            persistActiveScanCollection()
        }
        _snackbarMessage.value = if (removedCount == 1) {
            "Deleted 1 page"
        } else {
            "Deleted $removedCount pages"
        }
    }

    /**
     * Auto-identifies and crops objects from all selected pages, merges them onto a single new
     * page, and appends the merged page to the current scan collection (keeping originals intact).
     */
    fun mergeSelectedScanPages(
        selectedPageIds: Set<Long>,
        onMerged: () -> Unit = {},
    ) {
        val cur = _scanState.value
        if (cur.isMergingIdCard || cur.isActivelyScanningPage) return
        val selectedPages = cur.pages.filter { it.id in selectedPageIds }
        if (selectedPages.size < 2) {
            val lang = settings.value.appLanguage
            _snackbarMessage.value = tr(
                lang,
                "Select at least 2 pages to merge",
                "मर्ज करने के लिए कम से कम 2 पेज चुनें",
            )
            return
        }

        val targetPageSize = cur.params.pageSize
        val targetDpi = selectedPages.maxOf { it.dpi }.coerceIn(150, 600)
        val colorLabel = selectedPages.first().colorLabel
        val bitmapsToMerge = selectedPages.map { it.bitmap }

        viewModelScope.launch {
            _scanState.update { it.copy(isMergingIdCard = true, errorMessage = null) }
            val mergeResult = withContext(Dispatchers.Default) {
                runCatching {
                    ScanIdCardMerger.autoCropAndMergePages(
                        rawPages = bitmapsToMerge,
                        pageSize = targetPageSize,
                        dpi = targetDpi,
                    )
                }
            }
            mergeResult.fold(
                onSuccess = { mergedBitmap ->
                    val newItem = ScannedPageItem(
                        id = nextScanPageId.getAndIncrement(),
                        bitmap = mergedBitmap,
                        originalBitmap = mergedBitmap,
                        dpi = targetDpi,
                        colorLabel = colorLabel,
                        pageSizeLabel = "${targetPageSize.displayName} • Merged",
                        pageSize = targetPageSize,
                    )
                    var newPageNumber = 1
                    _scanState.update { state ->
                        val updatedPages = state.pages + newItem
                        newPageNumber = updatedPages.size
                        state.copy(
                            pages = updatedPages,
                            selectedPageIndex = updatedPages.lastIndex,
                            isMergingIdCard = false,
                        )
                    }
                    val lang = settings.value.appLanguage
                    persistActiveScanCollection(
                        tr(
                            lang,
                            "Merged ${selectedPages.size} pages into Page $newPageNumber",
                            "${selectedPages.size} पेज मर्ज होकर पेज $newPageNumber बन गया",
                        ),
                    )
                    onMerged()
                },
                onFailure = { err ->
                    _scanState.update {
                        it.copy(
                            isMergingIdCard = false,
                            errorMessage = err.message ?: "Could not merge selected pages",
                        )
                    }
                },
            )
        }
    }

    fun exportScannedPages(
        mode: ScanExportMode,
        selectedPageIds: Set<Long> = emptySet(),
        onDone: () -> Unit = {},
    ) {
        val snapshot = _scanState.value
        val targetItems = if (selectedPageIds.isNotEmpty()) {
            snapshot.pages.filter { it.id in selectedPageIds }
        } else {
            snapshot.pages
        }
        val pages = targetItems.map { it.bitmap }
        val pageSizes = targetItems.map { it.pageSize }
        if (pages.isEmpty()) return
        val isSubset = selectedPageIds.isNotEmpty() && targetItems.size < snapshot.pages.size
        val autoBaseName = snapshot.sessionTitle.ifBlank {
            "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            _scanState.update { it.copy(isExporting = true, errorMessage = null, exportSuccessMessage = null) }
            val result = ScanExportManager.exportPages(
                context = app,
                pages = pages,
                baseName = autoBaseName,
                mode = mode,
                existingCollectionId = snapshot.activeCollectionId,
                isSubsetExport = isSubset,
                pageSizes = pageSizes,
            )
            _scanState.update { it.copy(isExporting = false) }
            result.fold(
                onSuccess = { res ->
                    res.recentRecord?.let { rec ->
                        _scanState.update { it.copy(activeCollectionId = rec.id, sessionTitle = rec.title) }
                    }
                    if (!isSubset) reloadRecentScans()
                    val msg = when (mode) {
                        ScanExportMode.SINGLE_PDF -> "Saved PDF (${pages.size} page${if (pages.size == 1) "" else "s"}) to ${res.summaryLocation}"
                        ScanExportMode.SEPARATE_JPEG, ScanExportMode.SEPARATE_PNG -> "Saved ${res.savedCount} ${mode.shortFormat} file(s) to ${res.summaryLocation}"
                        ScanExportMode.SEPARATE_PDF -> "Saved ${res.savedCount} PDF file(s) to ${res.summaryLocation}"
                    }
                    _scanState.update { it.copy(exportSuccessMessage = msg) }
                    _snackbarMessage.value = msg
                    onDone()
                },
                onFailure = { err ->
                    val msg = err.message ?: "Failed to export scanned pages"
                    _scanState.update { it.copy(errorMessage = msg) }
                    _snackbarMessage.value = msg
                },
            )
        }
    }

    fun exportScannedPdfToUri(
        targetUri: Uri,
        selectedPageIds: Set<Long> = emptySet(),
        onDone: () -> Unit = {},
    ) {
        val snapshot = _scanState.value
        val targetItems = if (selectedPageIds.isNotEmpty()) {
            snapshot.pages.filter { it.id in selectedPageIds }
        } else {
            snapshot.pages
        }
        val pages = targetItems.map { it.bitmap }
        val pageSizes = targetItems.map { it.pageSize }
        if (pages.isEmpty()) return
        val isSubset = selectedPageIds.isNotEmpty() && targetItems.size < snapshot.pages.size
        val app = getApplication<Application>()
        val title = snapshot.sessionTitle.ifBlank { "Scanned_Document" }
        viewModelScope.launch {
            _scanState.update { it.copy(isExporting = true, errorMessage = null, exportSuccessMessage = null) }
            val result = ScanExportManager.writeSinglePdfToUri(
                context = app,
                pages = pages,
                targetUri = targetUri,
                displayTitle = title,
                existingCollectionId = snapshot.activeCollectionId,
                isSubsetExport = isSubset,
                pageSizes = pageSizes,
            )
            _scanState.update { it.copy(isExporting = false) }
            result.fold(
                onSuccess = { rec ->
                    if (rec != null) {
                        _scanState.update { it.copy(activeCollectionId = rec.id, sessionTitle = rec.title) }
                    }
                    if (!isSubset) reloadRecentScans()
                    val msg = "Saved ${pages.size}-page PDF to chosen folder"
                    _scanState.update { it.copy(exportSuccessMessage = msg) }
                    _snackbarMessage.value = msg
                    onDone()
                },
                onFailure = { err ->
                    val msg = err.message ?: "Failed to write PDF file"
                    _scanState.update { it.copy(errorMessage = msg) }
                    _snackbarMessage.value = msg
                },
            )
        }
    }

    // --- Settings ---

    fun updatePrinterName(name: String) {
        engine.updateSettings { it.copy(printerName = name) }
    }

    fun updateIppPort(port: Int) {
        engine.updateSettings { it.copy(ippPort = port.coerceIn(1024, 65535)) }
    }

    fun updateMdnsEnabled(enabled: Boolean) {
        engine.updateSettings { it.copy(mdnsEnabled = enabled) }
    }

    fun updatePrinterLanguage(lang: PrinterLanguage) {
        engine.updateSettings { it.copy(printerLanguage = lang) }
    }

    fun updateAutoStartServerOnOtg(enabled: Boolean) {
        engine.updateSettings { it.copy(autoStartServerOnOtg = enabled) }
    }

    fun updateDefaultPrintResolution(resolution: PrintResolution) {
        engine.updateSettings { it.copy(defaultPrintResolution = resolution) }
        _directPrintState.update { it.copy(resolution = resolution) }
    }

    fun updateDefaultScanDpi(dpi: Int) {
        val normalized = ScanParameters.normalizeDpi(dpi)
        engine.updateSettings { it.copy(defaultScanDpi = normalized) }
        if (!_scanState.value.isActivelyScanningPage) {
            _scanState.update { it.copy(params = it.params.copy(dpi = normalized)) }
        }
    }

    fun updateAppLanguage(language: AppLanguage) {
        engine.updateSettings { it.copy(appLanguage = language) }
    }

    fun showMessage(message: String) {
        _snackbarMessage.value = message
    }

    fun consumeSnackbar() {
        _snackbarMessage.value = null
    }

    private fun regenerateDirectPreview(debounceMs: Long = 0L) {
        val state = _directPrintState.value
        val doc = state.selectedDocument ?: return
        val app = getApplication<Application>()

        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            val latestState = _directPrintState.value
            val latestDoc = latestState.selectedDocument ?: return@launch
            val isImage = latestDoc.metadata.kind.isImage
            val effectiveMargins = if (isImage) {
                val m = latestState.imageMarginMm.coerceIn(0f, 25f)
                PageMargins(topMm = m, bottomMm = m, leftMm = m, rightMm = m)
            } else {
                PageMargins.DEFAULT
            }
            val effectiveScaling = if (isImage) latestState.imageScaling else PrintScaling.AUTO_FIT
            val effectivePanX = if (isImage && effectiveScaling == PrintScaling.FILL) latestState.imagePanX else 0f
            val effectivePanY = if (isImage && effectiveScaling == PrintScaling.FILL) latestState.imagePanY else 0f

            if (latestDoc.previewBitmap == null) {
                _directPrintState.update {
                    it.copy(selectedDocument = it.selectedDocument?.copy(isRenderingPreview = true))
                }
            }
            val bitmap = withContext(Dispatchers.Default) {
                runCatching {
                    if (latestDoc.isTestPage) {
                        val s = settings.value
                        val opts = PrintJobOptions(
                            pagesPerSheet = latestState.pagesPerSheet,
                            duplexMode = latestState.duplexMode,
                            paperSize = latestState.paperSize,
                            resolution = latestState.resolution,
                            printerLanguage = s.printerLanguage,
                            copies = latestState.copies,
                            tonerSave = latestState.tonerSave,
                            drawNUpBorders = latestState.drawBorders,
                            ditherImages = latestState.colorScheme.ditherImages,
                            orientation = latestState.orientation,
                        )
                        val pdfFile = TestPageGenerator.generateTestPagePdf(
                            context = app,
                            options = opts,
                            serverState = serverState.value,
                            usbInfo = engine.currentUsbInfo(),
                            dpi = 150,
                        )
                        DocumentRenderer.renderFirstSheetPreview(
                            context = app,
                            source = DocumentSource.LocalFile(pdfFile),
                            kind = latestDoc.metadata.kind,
                            pagesPerSheet = latestState.pagesPerSheet,
                            paperSize = latestState.paperSize,
                            drawCellBorders = latestState.drawBorders,
                            orientation = latestState.orientation,
                        )
                    } else {
                        DocumentRenderer.renderFirstSheetPreview(
                            context = app,
                            source = latestDoc.source,
                            kind = latestDoc.metadata.kind,
                            pagesPerSheet = latestState.pagesPerSheet,
                            paperSize = latestState.paperSize,
                            drawCellBorders = latestState.drawBorders,
                            margins = effectiveMargins,
                            scaling = effectiveScaling,
                            orientation = latestState.orientation,
                            panX = effectivePanX,
                            panY = effectivePanY,
                        )
                    }
                }.getOrNull()
            }
            _directPrintState.update { current ->
                if (current.selectedDocument?.id == latestDoc.id) {
                    current.copy(
                        selectedDocument = current.selectedDocument.copy(
                            previewBitmap = bitmap ?: current.selectedDocument.previewBitmap,
                            isRenderingPreview = false,
                        ),
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    override fun onCleared() {
        // Do NOT cancel active background scan when Activity/ViewModel is cleared:
        // BackgroundScanManager + ScanBackgroundService keep scanning and polling safely.
        _directPrintState.value.selectedDocument?.cachedFallbackFile?.delete()
        super.onCleared()
    }
}
