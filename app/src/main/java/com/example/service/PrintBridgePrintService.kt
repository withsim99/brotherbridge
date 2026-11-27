package com.example.service

import android.os.Build
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrintJobId
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.PrintBridgeApp
import com.example.model.ActivePrinterState
import com.example.model.DeviceBusyState
import com.example.model.DuplexMode
import com.example.model.LogCategory
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintJobOptions
import com.example.model.PrintJobStatus
import com.example.model.PrintOrientation
import com.example.model.PrintResolution
import com.example.model.PrinterConnectionMode
import com.example.printer.spooler.PrintSpoolerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Android System PrintService for PrintBridge.
 *
 * Allows any Android app on the OTG host device (Files / File Manager, Google Drive, Chrome,
 * Photos, Gmail, etc.) to tap "Print" and select the USB OTG printer directly with full
 * parameter selection (Paper Size, Copies, Orientation, Duplex, DPI, N-Up, Toner Save)
 * without opening PrintBridge first.
 */
class PrintBridgePrintService : PrintService() {

    companion object {
        const val OPTION_NUP = "printbridge_nup"
        const val OPTION_DPI = "printbridge_dpi"
        const val OPTION_TONER_SAVE = "printbridge_toner_save"
        const val OPTION_DRAW_BORDERS = "printbridge_draw_borders"
        const val OPTION_DUPLEX_MODE = "printbridge_duplex"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeJobIdMap = ConcurrentHashMap<PrintJobId, Int>()
    private val observerJobs = ConcurrentHashMap<PrintJobId, Job>()

    private val engine: PrintSpoolerEngine
        get() = (application as PrintBridgeApp).spoolerEngine

    override fun onConnected() {
        super.onConnected()
        engine.scanUsbDevices()
        serviceScope.launch {
            engine.ensurePersistedRemotePrinterRestored(verifyReachability = true)
        }
        engine.onLog(LogCategory.SPOOLER, "Android System Print Service connected", false)
    }

    override fun onDisconnected() {
        engine.onLog(LogCategory.SPOOLER, "Android System Print Service disconnected", false)
        super.onDisconnected()
    }

    override fun onDestroy() {
        observerJobs.values.forEach { it.cancel() }
        observerJobs.clear()
        activeJobIdMap.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return PrintBridgePrinterDiscoverySession(this, engine)
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        val info = printJob.info
        val doc = printJob.document
        val pfd = doc.data
        if (pfd == null) {
            printJob.fail("No printable document stream provided")
            return
        }

        val spoolDir = File(cacheDir, "android_print_service").apply { mkdirs() }
        val jobTitle = info.label.takeIf { it.isNotBlank() } ?: "System Print Job"
        val options = mapPrintAttributesToOptions(info)

        if (printJob.isQueued) {
            printJob.start()
        }

        serviceScope.launch {
            val copiedFile = withContext(Dispatchers.IO) {
                copyPdfStreamToTempFile(pfd, spoolDir, info.id.toString())
            }
            if (copiedFile == null) {
                if (printJob.isStarted || printJob.isQueued) {
                    printJob.fail("Failed to read print document from Android spooler")
                }
                return@launch
            }

            val enqueueResult = engine.enqueueSystemPrintServiceJob(
                file = copiedFile,
                title = jobTitle,
                options = options,
            )

            enqueueResult.fold(
                onSuccess = { record ->
                    activeJobIdMap[printJob.id] = record.id
                    observeSpoolerJob(printJob, record.id)
                },
                onFailure = { err ->
                    copiedFile.delete()
                    if (printJob.isStarted || printJob.isQueued) {
                        printJob.fail(err.message ?: "Printer unavailable")
                    }
                },
            )
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        val engineJobId = activeJobIdMap.remove(printJob.id)
        observerJobs.remove(printJob.id)?.cancel()
        if (engineJobId != null) {
            engine.cancelJob(engineJobId)
        }
        if (!printJob.isCompleted && !printJob.isFailed && !printJob.isCancelled) {
            printJob.cancel()
        }
    }

    private fun observeSpoolerJob(printJob: PrintJob, engineJobId: Int) {
        observerJobs.remove(printJob.id)?.cancel()
        observerJobs[printJob.id] = serviceScope.launch {
            engine.jobs.collectLatest { allJobs ->
                val record = allJobs.firstOrNull { it.id == engineJobId } ?: return@collectLatest
                when (record.status) {
                    PrintJobStatus.PENDING -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            printJob.setStatus("Queued for printer...")
                        }
                    }
                    PrintJobStatus.RENDERING -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            printJob.setProgress(record.progress.coerceIn(0f, 0.95f))
                            printJob.setStatus("Preparing pages...")
                        }
                    }
                    PrintJobStatus.TRANSMITTING -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            printJob.setProgress(record.progress.coerceIn(0.05f, 0.99f))
                            printJob.setStatus("Sending to printer...")
                        }
                    }
                    PrintJobStatus.COMPLETED -> {
                        activeJobIdMap.remove(printJob.id)
                        if (printJob.isStarted || printJob.isQueued) {
                            printJob.complete()
                        }
                        observerJobs.remove(printJob.id)?.cancel()
                    }
                    PrintJobStatus.CANCELED -> {
                        activeJobIdMap.remove(printJob.id)
                        if (!printJob.isCancelled && !printJob.isCompleted && !printJob.isFailed) {
                            printJob.cancel()
                        }
                        observerJobs.remove(printJob.id)?.cancel()
                    }
                    PrintJobStatus.FAILED -> {
                        activeJobIdMap.remove(printJob.id)
                        if (!printJob.isFailed && !printJob.isCompleted && !printJob.isCancelled) {
                            printJob.fail(record.errorMessage ?: "Printing failed")
                        }
                        observerJobs.remove(printJob.id)?.cancel()
                    }
                }
            }
        }
    }

    private fun copyPdfStreamToTempFile(
        pfd: ParcelFileDescriptor,
        dir: File,
        jobKey: String,
    ): File? {
        return runCatching {
            val safeName = jobKey.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val outFile = File(dir, "sysprint_${safeName}_${System.currentTimeMillis()}.pdf")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                    output.flush()
                }
            }
            if (outFile.length() > 0L) outFile else {
                outFile.delete()
                null
            }
        }.getOrNull()
    }

    private fun mapPrintAttributesToOptions(info: android.print.PrintJobInfo): PrintJobOptions {
        val attrs = info.attributes
        val settings = engine.settings.value

        val paperSize = attrs.mediaSize?.let { media ->
            when (media.id) {
                PrintAttributes.MediaSize.ISO_A4.id -> PaperSize.A4
                PrintAttributes.MediaSize.NA_LETTER.id -> PaperSize.LETTER
                PrintAttributes.MediaSize.NA_LEGAL.id -> PaperSize.LEGAL
                PrintAttributes.MediaSize.ISO_A5.id -> PaperSize.A5
                PrintAttributes.MediaSize.ISO_A6.id -> PaperSize.A6
                PrintAttributes.MediaSize.JIS_B5.id -> PaperSize.B5
                PrintAttributes.MediaSize.NA_GOVT_LETTER.id -> PaperSize.EXECUTIVE
                else -> {
                    val widthMm100 = (media.widthMils * 2.54).toInt()
                    val heightMm100 = (media.heightMils * 2.54).toInt()
                    PaperSize.fromDimensionsHundredthsMm(widthMm100, heightMm100)
                }
            }
        } ?: PaperSize.A4

        val orientation = if (attrs.mediaSize?.isPortrait == false) {
            PrintOrientation.LANDSCAPE
        } else {
            PrintOrientation.PORTRAIT
        }

        val attrDpi = attrs.resolution?.horizontalDpi ?: 300
        val advancedDpi = if (info.hasAdvancedOption(OPTION_DPI)) {
            info.getAdvancedIntOption(OPTION_DPI)
        } else {
            attrDpi
        }
        val resolution = PrintResolution.fromDpi(advancedDpi)

        val duplexMode = when {
            info.hasAdvancedOption(OPTION_DUPLEX_MODE) -> {
                DuplexMode.fromIppKeyword(info.getAdvancedStringOption(OPTION_DUPLEX_MODE))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                when (attrs.duplexMode) {
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE -> DuplexMode.LONG_EDGE
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> DuplexMode.SHORT_EDGE
                    else -> DuplexMode.SIMPLEX
                }
            }
            else -> DuplexMode.SIMPLEX
        }

        val nUpCount = if (info.hasAdvancedOption(OPTION_NUP)) {
            info.getAdvancedIntOption(OPTION_NUP)
        } else {
            1
        }
        val pagesPerSheet = PagesPerSheet.fromCount(nUpCount)

        val tonerSave = info.hasAdvancedOption(OPTION_TONER_SAVE) &&
            info.getAdvancedIntOption(OPTION_TONER_SAVE) == 1

        val drawBorders = if (info.hasAdvancedOption(OPTION_DRAW_BORDERS)) {
            info.getAdvancedIntOption(OPTION_DRAW_BORDERS) == 1
        } else {
            pagesPerSheet.count > 1
        }

        val margins = attrs.minMargins?.let { m ->
            PageMargins(
                topMm = (m.topMils * 0.0254f).coerceAtLeast(0f),
                bottomMm = (m.bottomMils * 0.0254f).coerceAtLeast(0f),
                leftMm = (m.leftMils * 0.0254f).coerceAtLeast(0f),
                rightMm = (m.rightMils * 0.0254f).coerceAtLeast(0f),
            )
        } ?: PageMargins.DEFAULT

        return PrintJobOptions(
            pagesPerSheet = pagesPerSheet,
            duplexMode = duplexMode,
            paperSize = paperSize,
            resolution = resolution,
            printerLanguage = settings.printerLanguage,
            copies = info.copies.coerceIn(1, 99),
            tonerSave = tonerSave,
            drawNUpBorders = drawBorders,
            ditherImages = true,
            margins = margins,
            orientation = orientation,
        )
    }
}

private class PrintBridgePrinterDiscoverySession(
    private val service: PrintBridgePrintService,
    private val engine: PrintSpoolerEngine,
) : PrinterDiscoverySession() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null
    private val trackedIds = mutableSetOf<PrinterId>()

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        engine.scanUsbDevices()
        scope.launch {
            engine.ensurePersistedRemotePrinterRestored(verifyReachability = true)
        }
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            combine(engine.activePrinter, engine.deviceBusyState) { active, busy ->
                Pair(active, busy)
            }.collectLatest { (active, busy) ->
                publishPrinters(active, busy)
            }
        }
    }

    override fun onStopPrinterDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
        publishPrinters(engine.activePrinter.value, engine.deviceBusyState.value)
        scope.launch {
            engine.ensurePersistedRemotePrinterRestored(verifyReachability = true)
            publishPrinters(engine.activePrinter.value, engine.deviceBusyState.value)
        }
    }

    override fun onStartPrinterStateTracking(printerId: PrinterId) {
        trackedIds.add(printerId)
        publishPrinters(engine.activePrinter.value, engine.deviceBusyState.value)
        scope.launch {
            engine.ensurePersistedRemotePrinterRestored(verifyReachability = true)
            publishPrinters(engine.activePrinter.value, engine.deviceBusyState.value)
        }
    }

    override fun onStopPrinterStateTracking(printerId: PrinterId) {
        trackedIds.remove(printerId)
    }

    override fun onDestroy() {
        discoveryJob?.cancel()
        scope.cancel()
    }

    private fun publishPrinters(active: ActivePrinterState, busy: DeviceBusyState) {
        val localId = buildLocalId(active)
        val printerId = service.generatePrinterId(localId)

        if (!active.isConnected || !active.supportsPrint) {
            val staleIds = printers.map { it.id }
            if (staleIds.isNotEmpty()) {
                removePrinters(staleIds)
            }
            return
        }

        val status = when {
            busy != DeviceBusyState.AVAILABLE -> PrinterInfo.STATUS_BUSY
            else -> PrinterInfo.STATUS_IDLE
        }

        val description = when (active.mode) {
            PrinterConnectionMode.USB_OTG -> "USB OTG Direct Printer"
            PrinterConnectionMode.WIRELESS_REMOTE -> "PrintBridge Wireless (${active.addressOrId})"
            PrinterConnectionMode.NONE -> "Disconnected"
        }

        val capsBuilder = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
            .addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A5, false)
            .addMediaSize(PrintAttributes.MediaSize.ISO_A6, false)
            .addMediaSize(PrintAttributes.MediaSize.JIS_B5, false)
            .addResolution(PrintAttributes.Resolution("dpi_300", "300 DPI (Fast)", 300, 300), true)
            .addResolution(PrintAttributes.Resolution("dpi_600", "600 DPI (Sharp)", 600, 600), false)
            .setColorModes(
                PrintAttributes.COLOR_MODE_MONOCHROME or PrintAttributes.COLOR_MODE_COLOR,
                PrintAttributes.COLOR_MODE_MONOCHROME,
            )
            .setMinMargins(PrintAttributes.Margins(167, 167, 167, 167))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            capsBuilder.setDuplexModes(
                PrintAttributes.DUPLEX_MODE_NONE or
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE or
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                PrintAttributes.DUPLEX_MODE_NONE,
            )
        }

        val printerInfo = PrinterInfo.Builder(printerId, active.displayName, status)
            .setDescription(description)
            .setCapabilities(capsBuilder.build())
            .build()

        // Remove any previously published printer with a different ID
        val stale = printers.map { it.id }.filter { it != printerId }
        if (stale.isNotEmpty()) {
            removePrinters(stale)
        }
        addPrinters(listOf(printerInfo))
    }

    private fun buildLocalId(active: ActivePrinterState): String {
        return when (active.mode) {
            PrinterConnectionMode.USB_OTG -> "otg_${active.addressOrId.ifBlank { "brother" }}"
            PrinterConnectionMode.WIRELESS_REMOTE -> "wifi_${active.addressOrId}"
            PrinterConnectionMode.NONE -> "none"
        }
    }
}
