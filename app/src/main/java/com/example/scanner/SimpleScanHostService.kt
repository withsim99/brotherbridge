package com.example.scanner

import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import com.example.model.LogCategory
import com.example.model.ScanColorOption
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully asynchronous, queued, timeout-free, fail-safe host scanning service (`/api/scan/...`).
 *
 * Lifecycle:
 * 1. Client POSTs `/api/scan/start` with `deviceId` + scan parameters -> Host ALWAYS accepts
 *    immediately (HTTP 202) and places the scan in an async FIFO queue (`QUEUED`), never rejecting
 *    with HTTP 409 even if a print or another scan is currently using the USB device.
 * 2. Background scan worker waits cleanly on the shared `hardwareMutex`, transitions the job to
 *    `RUNNING`, streams lines from USB, and writes atomic JPEG + SHA-256 into
 *    `filesDir/host_scan_vault/<deviceId>/<jobId>/`.
 * 3. Client polls `GET /api/scan/jobs/{jobId}/progress?deviceId=...` lock-free in <5ms.
 * 4. Client downloads `GET /api/scan/jobs/{jobId}/page/{idx}?deviceId=...`, verifies SHA-256 + size,
 *    saves locally, then POSTs `/ack` to delete the host vault copy.
 * 5. **Lost/Found Host Recovery**: If a completed scan fails delivery or remains un-acked past the
 *    handover grace window (or on client disconnect / host shutdown / ACK failure), the host
 *    automatically surfaces the undelivered pages inside its own `"Lost/Found"` collage!
 */
class SimpleScanHostService(
    vaultRootDir: File,
    private val usbManager: UsbManager?,
    private val readyProvider: () -> UsbConnectionState.Ready?,
    private val modelNameProvider: () -> String,
    private val externalBusyProvider: () -> Boolean,
    private val onScanHardwareStateChanged: (Boolean) -> Unit = {},
    private val hardwareMutex: Mutex,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
    private val onRecoverToLostAndFound: (suspend (pageFiles: List<File>, clientLabel: String) -> Boolean)? = null,
    private val lostAndFoundTimeoutMs: Long = DEFAULT_LOST_AND_FOUND_TIMEOUT_MS,
    private val customPageProducer: (suspend (
        params: ScanParameters,
        onProgress: (pageNumber: Int, line: Int, total: Int) -> Unit,
        onPageCaptured: suspend (pageNumber: Int, bitmap: Bitmap) -> Unit,
    ) -> Int)? = null,
) {
    private data class QueuedScanTask(
        val jobId: String,
        val deviceId: String,
        val params: ScanParameters,
    )

    private val hostExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SimpleScanHostService", "Uncaught scan host coroutine error", throwable)
        runCatching {
            onLog(
                LogCategory.SCAN,
                "Scan host background error recovered: ${throwable.message ?: throwable.javaClass.simpleName}",
                true,
            )
        }
    }

    private val hostScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + hostExceptionHandler)
    private val vaultDir = vaultRootDir.apply { mkdirs() }
    private val stateLock = Any()

    // jobId -> persisted state
    private val jobsById = ConcurrentHashMap<String, SimpleScanJobState>()
    // jobId -> active USB driver (for cancel while running)
    private val activeDrivers = ConcurrentHashMap<String, BrotherDcpL2520dScanner>()
    // jobId -> canceled before/during execution
    private val canceledJobIds = ConcurrentHashMap.newKeySet<String>()
    // jobId -> pending Lost/Found watchdog job
    private val recoveryWatchdogs = ConcurrentHashMap<String, Job>()
    // Ordered list of queued/running jobIds for queuePosition reporting
    private val pendingQueueOrder = mutableListOf<String>()

    private val scanQueue = Channel<QueuedScanTask>(capacity = Channel.UNLIMITED)

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()

    private val _pendingVaultJobCount = MutableStateFlow(0)
    val pendingVaultJobCount: StateFlow<Int> = _pendingVaultJobCount.asStateFlow()

    init {
        hostScope.launch {
            try {
                restoreVaultFromDisk()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("SimpleScanHostService", "Vault restore failed cleanly", t)
            }
        }
        startQueueWorker()
    }

    private fun startQueueWorker() {
        hostScope.launch {
            for (task in scanQueue) {
                if (canceledJobIds.remove(task.jobId)) {
                    synchronized(stateLock) {
                        pendingQueueOrder.remove(task.jobId)
                        refreshQueuePositionsLocked()
                    }
                    continue
                }
                executeQueuedScanTask(task)
            }
        }
    }

    /**
     * Reloads any un-acked scan jobs stored on disk so client scans survive even if the host app restarts,
     * and surfaces any leftover undelivered pages into Host's `"Lost/Found"` collage.
     */
    private fun restoreVaultFromDisk() {
        val deviceDirs = vaultDir.listFiles { f -> f.isDirectory } ?: return
        var restoredReady = 0
        val toRecoverJobs = mutableListOf<String>()
        for (devDir in deviceDirs) {
            val jobDirs = devDir.listFiles { f -> f.isDirectory } ?: continue
            for (jobDir in jobDirs) {
                val metaFile = File(jobDir, META_FILE_NAME)
                if (!metaFile.exists()) {
                    runCatching { jobDir.deleteRecursively() }
                    continue
                }
                val parsed = runCatching { SimpleScanJobState.fromJson(JSONObject(metaFile.readText())) }.getOrNull()
                if (parsed == null || parsed.jobId.isBlank() || parsed.deviceId.isBlank()) {
                    runCatching { jobDir.deleteRecursively() }
                    continue
                }
                val validPages = parsed.pages.filter { page ->
                    !page.acked && File(jobDir, page.fileName).exists()
                }
                val reconciled = when {
                    validPages.isEmpty() -> {
                        runCatching { jobDir.deleteRecursively() }
                        null
                    }
                    parsed.status == SimpleScanJobStatus.RUNNING || parsed.status == SimpleScanJobStatus.QUEUED -> {
                        parsed.copy(
                            status = SimpleScanJobStatus.READY,
                            progress = 100,
                            queuePosition = 0,
                            statusMessage = "Recovered ${validPages.size} page(s) ready for handover",
                            pages = validPages,
                            updatedAtMs = System.currentTimeMillis(),
                            completedAtMs = parsed.completedAtMs ?: System.currentTimeMillis(),
                        )
                    }
                    parsed.status == SimpleScanJobStatus.READY -> {
                        parsed.copy(pages = validPages, queuePosition = 0)
                    }
                    else -> {
                        runCatching { jobDir.deleteRecursively() }
                        null
                    }
                }
                if (reconciled != null) {
                    jobsById[reconciled.jobId] = reconciled
                    persistMetaLocked(reconciled)
                    restoredReady++
                    if (reconciled.pages.any { !it.acked && !it.movedToLostAndFound }) {
                        toRecoverJobs.add(reconciled.jobId)
                    }
                }
            }
            if (devDir.listFiles().isNullOrEmpty()) {
                runCatching { devDir.delete() }
            }
        }
        recomputePendingCount()
        if (restoredReady > 0) {
            runCatching {
                onLog(LogCategory.SCAN, "Restored $restoredReady uncollected scan job(s) in fail-safe vault", false)
            }
        }
        if (toRecoverJobs.isNotEmpty()) {
            hostScope.launch {
                toRecoverJobs.forEach { jid ->
                    try {
                        moveUnackedPagesToLostAndFound(jid, reason = "recovered after host restart")
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e("SimpleScanHostService", "Lost/Found startup recovery failed for $jid", t)
                    }
                }
            }
        }
    }

    suspend fun handleHttpRequest(
        method: String,
        rawPath: String,
        body: ByteArray,
        remoteIp: String,
    ): SimpleScanHttpResponse {
        val pathOnly = rawPath.substringBefore('?').trimEnd('/')
        val query = SimpleScanProtocol.parseQueryParams(rawPath)
        val suffix = pathOnly.removePrefix(SimpleScanProtocol.BASE_PATH).trimStart('/')

        return when {
            method == "GET" && (suffix == "capabilities" || suffix.isEmpty()) -> {
                handleGetCapabilities()
            }

            method == "POST" && suffix == "start" -> {
                handleStartScan(body, remoteIp)
            }

            method == "GET" && suffix == "pending" -> {
                handleGetPending(query["deviceId"])
            }

            suffix.startsWith("jobs/") -> {
                handleJobRoute(method, suffix.removePrefix("jobs/"), query, body, remoteIp)
            }

            else -> SimpleScanProtocol.errorResponse(404, "Not Found", "Unknown scan endpoint: $pathOnly")
        }
    }

    private fun handleGetCapabilities(): SimpleScanHttpResponse {
        val ready = readyProvider()
        val available = ready != null || customPageProducer != null
        val busy = externalBusyProvider() || _isScanning.value
        val busyReason = when {
            _isScanning.value -> "Scanner active — new requests will be queued automatically"
            externalBusyProvider() -> "Printer active — new requests will be queued automatically"
            else -> null
        }
        val caps = SimpleScanCapabilities(
            available = available,
            busy = busy,
            busyReason = busyReason,
            supportsFlatbed = ready?.capabilities?.supportsFlatbed ?: true,
            supportsAdf = ready?.capabilities?.supportsAdf ?: false,
            supportsAdfDuplex = ready?.capabilities?.supportsAdfDuplex ?: false,
            model = modelNameProvider(),
        )
        return SimpleScanProtocol.jsonResponse(200, "OK", caps.toJson())
    }

    private fun handleStartScan(body: ByteArray, remoteIp: String): SimpleScanHttpResponse {
        val ready = readyProvider()
        if (ready == null && customPageProducer == null) {
            return SimpleScanProtocol.errorResponse(503, "Service Unavailable", "USB scanner is not connected on host")
        }

        val json = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Invalid JSON payload")

        val deviceId = SimpleScanProtocol.sanitizeId(json.optString("deviceId"))
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Valid deviceId is required")
        val clientName = json.optString("clientName", remoteIp).ifBlank { remoteIp }.take(64)

        val caps = ready?.capabilities
        val requestedSource = runCatching { ScanSourceOption.valueOf(json.optString("source")) }
            .getOrDefault(ScanSourceOption.FLATBED)
        val effectiveSource = when {
            requestedSource == ScanSourceOption.ADF_DUPLEX && caps?.supportsAdfDuplex == false ->
                if (caps.supportsAdf) ScanSourceOption.ADF_SIMPLEX else ScanSourceOption.FLATBED
            requestedSource == ScanSourceOption.ADF_SIMPLEX && caps?.supportsAdf == false ->
                ScanSourceOption.FLATBED
            else -> requestedSource
        }
        val colorMode = runCatching { ScanColorOption.valueOf(json.optString("colorMode")) }
            .getOrDefault(ScanColorOption.COLOR)
        val pageSize = runCatching { ScanPageSizeOption.valueOf(json.optString("pageSize")) }
            .getOrDefault(ScanPageSizeOption.A4)
        val dpi = ScanParameters.normalizeDpi(json.optInt("dpi", ScanParameters.DEFAULT_DPI))
        val brightness = json.optInt("brightness", 50).coerceIn(0, 100)
        val contrast = json.optInt("contrast", 50).coerceIn(0, 100)

        val params = ScanParameters(
            source = effectiveSource,
            colorMode = colorMode,
            dpi = dpi,
            pageSize = pageSize,
            brightness = brightness,
            contrast = contrast,
        )

        val jobId = "scan_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val now = System.currentTimeMillis()
        val isHardwareBusy = externalBusyProvider() || _isScanning.value

        val initialState = synchronized(stateLock) {
            pendingQueueOrder.add(jobId)
            val queuePos = pendingQueueOrder.size
            val statusMsg = if (isHardwareBusy || queuePos > 1) {
                "Queued on host (#$queuePos — waiting for device)..."
            } else {
                "Starting scanner on host..."
            }
            val created = SimpleScanJobState(
                jobId = jobId,
                deviceId = deviceId,
                clientName = clientName,
                status = SimpleScanJobStatus.QUEUED,
                progress = 2,
                statusMessage = statusMsg,
                queuePosition = queuePos,
                dpi = dpi,
                colorMode = colorMode,
                pageSize = pageSize,
                source = effectiveSource,
                brightness = brightness,
                contrast = contrast,
                createdAtMs = now,
                updatedAtMs = now,
                pages = emptyList(),
            )
            jobsById[jobId] = created
            persistMetaLocked(created)
            _queuedCount.value = pendingQueueOrder.size
            created
        }
        recomputePendingCount()

        onLog(
            LogCategory.SCAN,
            "Host queued async scan $jobId for $clientName (pos #${initialState.queuePosition}, ${effectiveSource.displayName}, $dpi DPI, ${colorMode.shortLabel})",
            false,
        )

        scanQueue.trySend(QueuedScanTask(jobId = jobId, deviceId = deviceId, params = params))
        return SimpleScanProtocol.jsonResponse(202, "Accepted", initialState.toJson())
    }

    private suspend fun executeQueuedScanTask(task: QueuedScanTask) {
        val jobId = task.jobId
        val deviceId = task.deviceId
        val params = task.params
        val jobDir = jobDirectory(deviceId, jobId).apply { mkdirs() }
        val capturedPages = mutableListOf<SimpleScanPageMeta>()
        val hardwareHeld = AtomicBoolean(false)
        val taskStartedAtMs = System.currentTimeMillis()

        fun releaseScanHardwareFlag() {
            if (hardwareHeld.compareAndSet(true, false)) {
                activeDrivers.remove(jobId)
                _isScanning.value = false
                onScanHardwareStateChanged(false)
            }
        }

        // 5-second host heartbeat guarantees clients always see a fresh progress update every 5–6 seconds
        // even while waiting on hardwareMutex, cooldown, USB handshake, or scanner lamp warmup.
        val heartbeatJob = hostScope.launch {
            while (true) {
                delay(HOST_HEARTBEAT_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val elapsedSec = ((now - taskStartedAtMs) / 1000L).toInt().coerceAtLeast(1)
                updateJobState(jobId, persist = false) { current ->
                    if (current.status != SimpleScanJobStatus.QUEUED && current.status != SimpleScanJobStatus.RUNNING) {
                        return@updateJobState current
                    }
                    if (now - current.heartbeatAtMs < HOST_HEARTBEAT_INTERVAL_MS - 500L) {
                        return@updateJobState current
                    }
                    val baseMsg = current.statusMessage
                        .replace(Regex("\\s*\\(\\d+s\\)$"), "")
                        .replace(Regex("\\s*•\\s*\\d+s$"), "")
                        .ifBlank {
                            if (current.status == SimpleScanJobStatus.QUEUED) "Waiting for scanner on host..."
                            else "Scanning on host..."
                        }
                    current.copy(
                        statusMessage = "$baseMsg • ${elapsedSec}s",
                        updatedAtMs = now,
                        heartbeatAtMs = now,
                    )
                }
            }
        }

        try {
            val onStageStatus: (Float, String) -> Unit = { stageFrac, stageMsg ->
                val now = System.currentTimeMillis()
                val pct = (stageFrac * 100f).toInt().coerceIn(3, 95)
                val parsedAttempt = Regex("attempt (\\d+)/(\\d+)").find(stageMsg)?.groupValues?.getOrNull(1)?.toIntOrNull()
                updateJobState(jobId, persist = false) { current ->
                    current.copy(
                        status = SimpleScanJobStatus.RUNNING,
                        progress = if (stageMsg.contains("retrying", ignoreCase = true)) pct else maxOf(current.progress, pct),
                        queuePosition = 0,
                        attempt = parsedAttempt ?: current.attempt,
                        statusMessage = stageMsg,
                        updatedAtMs = now,
                        heartbeatAtMs = now,
                    )
                }
            }

            val onLineProgress: (Int, Int, Int) -> Unit = { pageNum, line, total ->
                val now = System.currentTimeMillis()
                val frac = if (total > 0) (line.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0.1f
                val pct = (8 + (frac * 84f).toInt()).coerceIn(8, 92)
                updateJobState(jobId, persist = false) {
                    it.copy(
                        status = SimpleScanJobStatus.RUNNING,
                        progress = pct,
                        queuePosition = 0,
                        statusMessage = "Scanning page $pageNum (${(frac * 100).toInt()}%)...",
                        updatedAtMs = now,
                        heartbeatAtMs = now,
                    )
                }
            }

            val onPageCaptured: suspend (Int, Bitmap) -> Unit = { pageNum, bitmap ->
                try {
                    val savingNow = System.currentTimeMillis()
                    updateJobState(jobId, persist = false) {
                        it.copy(
                            progress = 94,
                            statusMessage = "Saving page $pageNum to host vault...",
                            updatedAtMs = savingNow,
                            heartbeatAtMs = savingNow,
                        )
                    }
                    val fileName = String.format(Locale.US, "page_%03d.jpg", pageNum)
                    val tmpFile = File(jobDir, "$fileName.tmp")
                    val finalFile = File(jobDir, fileName)
                    val w = bitmap.width
                    val h = bitmap.height

                    val digest = MessageDigest.getInstance("SHA-256")
                    DigestOutputStream(BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024), digest).use { out ->
                        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, HOST_VAULT_JPEG_QUALITY, out)
                        if (!ok) throw IllegalStateException("Failed to encode scanned page $pageNum")
                        out.flush()
                    }
                    if (finalFile.exists()) finalFile.delete()
                    if (!tmpFile.renameTo(finalFile)) {
                        tmpFile.copyTo(finalFile, overwrite = true)
                        tmpFile.delete()
                    }

                    val sizeBytes = finalFile.length()
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    val pageMeta = SimpleScanPageMeta(
                        index = pageNum,
                        fileName = fileName,
                        sizeBytes = sizeBytes,
                        sha256 = sha256,
                        width = w,
                        height = h,
                        acked = false,
                        movedToLostAndFound = false,
                    )
                    capturedPages.add(pageMeta)
                    val savedNow = System.currentTimeMillis()
                    updateJobState(jobId, persist = true) {
                        it.copy(
                            progress = 96,
                            statusMessage = "Page $pageNum saved (${sizeBytes / 1024} KB)",
                            pages = capturedPages.toList(),
                            updatedAtMs = savedNow,
                            heartbeatAtMs = savedNow,
                        )
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }

            // Wait cleanly in FIFO order for the shared USB print/scan hardware mutex!
            hardwareMutex.withLock {
                if (canceledJobIds.remove(jobId)) {
                    throw CancellationException("Scan canceled while queued")
                }

                hardwareHeld.set(true)
                _isScanning.value = true
                onScanHardwareStateChanged(true)

                val runNow = System.currentTimeMillis()
                updateJobState(jobId) {
                    it.copy(
                        status = SimpleScanJobStatus.RUNNING,
                        progress = 5,
                        queuePosition = 0,
                        statusMessage = "Initializing Brother USB scanner...",
                        updatedAtMs = runNow,
                        heartbeatAtMs = runNow,
                    )
                }

                if (customPageProducer != null) {
                    customPageProducer.invoke(params, onLineProgress, onPageCaptured)
                } else {
                    val usb = usbManager ?: throw IllegalStateException("USB Manager unavailable")
                    val readyState = readyProvider() ?: throw IllegalStateException("Scanner disconnected")
                    val usbDriver = BrotherDcpL2520dScanner(usb, readyState.device)
                    activeDrivers[jobId] = usbDriver

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

                    usbDriver.scanPages(
                        config = driverConfig,
                        onPageProgress = onLineProgress,
                        onStatusUpdate = onStageStatus,
                        onPageCaptured = onPageCaptured,
                    )
                }
            }

            releaseScanHardwareFlag()

            if (capturedPages.isEmpty()) {
                throw IllegalStateException("Scanner finished without producing any page image")
            }

            val completedNow = System.currentTimeMillis()
            updateJobState(jobId) {
                it.copy(
                    status = SimpleScanJobStatus.READY,
                    progress = 100,
                    queuePosition = 0,
                    statusMessage = "Ready for handover (${capturedPages.size} page${if (capturedPages.size == 1) "" else "s"})",
                    errorMessage = null,
                    pages = capturedPages.toList(),
                    updatedAtMs = completedNow,
                    heartbeatAtMs = completedNow,
                    completedAtMs = completedNow,
                )
            }
            onLog(
                LogCategory.SCAN,
                "Host scan $jobId READY (${capturedPages.size} page(s)) — waiting for verified client handover",
                false,
            )

            // Start Lost/Found watchdog: if client doesn't ACK within timeout, surface in Host's Lost/Found collage
            scheduleLostAndFoundWatchdog(jobId)
        } catch (e: CancellationException) {
            markFailedOrCanceled(jobId, deviceId, SimpleScanJobStatus.CANCELED, "Scan canceled by user")
        } catch (e: BrotherDcpL2520dScanner.ScanCancelledException) {
            markFailedOrCanceled(jobId, deviceId, SimpleScanJobStatus.CANCELED, "Scan canceled by user")
        } catch (t: Throwable) {
            val msg = t.message ?: "Host scanner error"
            onLog(LogCategory.SCAN, "Host scan $jobId failed: $msg", true)
            markFailedOrCanceled(jobId, deviceId, SimpleScanJobStatus.FAILED, msg)
        } finally {
            heartbeatJob.cancel()
            releaseScanHardwareFlag()
            synchronized(stateLock) {
                pendingQueueOrder.remove(jobId)
                refreshQueuePositionsLocked()
            }
            recomputePendingCount()
        }
    }

    private fun scheduleLostAndFoundWatchdog(jobId: String) {
        if (onRecoverToLostAndFound == null) return
        recoveryWatchdogs.remove(jobId)?.cancel()
        recoveryWatchdogs[jobId] = hostScope.launch {
            delay(lostAndFoundTimeoutMs)
            moveUnackedPagesToLostAndFound(jobId, reason = "client delivery timed out")
        }
    }

    /**
     * Copies any un-acked, not-yet-recovered pages of [jobId] into the Host's `"Lost/Found"` collage.
     * Idempotent: marks `movedToLostAndFound = true` on each recovered page so it is never duplicated.
     */
    suspend fun moveUnackedPagesToLostAndFound(jobId: String, reason: String) {
        val recoverCallback = onRecoverToLostAndFound ?: return
        val snapshot = synchronized(stateLock) { jobsById[jobId] } ?: return
        if (snapshot.status != SimpleScanJobStatus.READY) return

        val pendingPages = snapshot.pages.filter { !it.acked && !it.movedToLostAndFound }
        if (pendingPages.isEmpty()) return

        val jobDir = jobDirectory(snapshot.deviceId, snapshot.jobId)
        val pageFiles = pendingPages
            .sortedBy { it.index }
            .map { File(jobDir, it.fileName) }
            .filter { it.exists() && it.length() > 0L }
        if (pageFiles.isEmpty()) return

        val savedOk = try {
            recoverCallback.invoke(pageFiles, snapshot.clientName)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e("SimpleScanHostService", "Lost/Found callback failed for $jobId", t)
            false
        }

        if (savedOk) {
            val movedIndices = pendingPages.map { it.index }.toSet()
            synchronized(stateLock) {
                val latest = jobsById[jobId] ?: return@synchronized
                val updated = latest.copy(
                    pages = latest.pages.map { p ->
                        if (p.index in movedIndices) p.copy(movedToLostAndFound = true) else p
                    },
                    updatedAtMs = System.currentTimeMillis(),
                )
                jobsById[jobId] = updated
                persistMetaLocked(updated)
            }
            runCatching {
                onLog(
                    LogCategory.SCAN,
                    "Saved ${pageFiles.size} undelivered page(s) from ${snapshot.clientName} into Host 'Lost/Found' ($reason)",
                    false,
                )
            }
        }
    }

    /**
     * Immediately flushes any un-acked READY pages (optionally filtered to a specific [deviceId])
     * into the Host's `"Lost/Found"` collage — e.g. when a client disconnects or the host server stops.
     */
    fun flushUndeliveredToLostAndFoundAsync(deviceId: String? = null, reason: String = "delivery interrupted") {
        val targetJobIds = synchronized(stateLock) {
            jobsById.values
                .filter { it.status == SimpleScanJobStatus.READY }
                .filter { deviceId.isNullOrBlank() || it.deviceId == deviceId }
                .filter { it.pages.any { p -> !p.acked && !p.movedToLostAndFound } }
                .map { it.jobId }
        }
        if (targetJobIds.isEmpty()) return
        hostScope.launch {
            targetJobIds.forEach { jid ->
                moveUnackedPagesToLostAndFound(jid, reason = reason)
            }
        }
    }

    private fun refreshQueuePositionsLocked() {
        _queuedCount.value = pendingQueueOrder.size
        pendingQueueOrder.forEachIndexed { idx, jid ->
            val current = jobsById[jid] ?: return@forEachIndexed
            if (current.status == SimpleScanJobStatus.QUEUED) {
                val pos = idx + 1
                val updated = current.copy(
                    queuePosition = pos,
                    statusMessage = if (pos <= 1 && !externalBusyProvider()) {
                        "Starting scanner on host..."
                    } else {
                        "Queued on host (#$pos — waiting for device)..."
                    },
                )
                jobsById[jid] = updated
            }
        }
    }

    private fun markFailedOrCanceled(
        jobId: String,
        deviceId: String,
        status: SimpleScanJobStatus,
        message: String,
    ) {
        val now = System.currentTimeMillis()
        synchronized(stateLock) {
            val current = jobsById[jobId] ?: return
            val updated = current.copy(
                status = status,
                queuePosition = 0,
                statusMessage = message,
                errorMessage = if (status == SimpleScanJobStatus.FAILED) message else null,
                updatedAtMs = now,
                completedAtMs = now,
            )
            jobsById[jobId] = updated
            if (updated.pages.isEmpty()) {
                runCatching { jobDirectory(deviceId, jobId).deleteRecursively() }
                cleanupDeviceDirIfEmpty(deviceId)
            } else {
                persistMetaLocked(updated)
            }
        }
    }

    private fun handleGetPending(rawDeviceId: String?): SimpleScanHttpResponse {
        val deviceId = SimpleScanProtocol.sanitizeId(rawDeviceId)
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Valid deviceId query parameter required")

        val pending = synchronized(stateLock) {
            jobsById.values
                .filter { it.deviceId == deviceId }
                .filter {
                    it.status == SimpleScanJobStatus.QUEUED ||
                        it.status == SimpleScanJobStatus.RUNNING ||
                        (it.status == SimpleScanJobStatus.READY && it.unackedPages.isNotEmpty())
                }
                .sortedBy { it.createdAtMs }
        }
        val arr = JSONArray()
        pending.forEach { arr.put(it.toJson()) }
        return SimpleScanProtocol.jsonResponse(
            200,
            "OK",
            JSONObject().apply {
                put("deviceId", deviceId)
                put("jobs", arr)
            },
        )
    }

    private fun handleJobRoute(
        method: String,
        jobSubPath: String,
        query: Map<String, String>,
        body: ByteArray,
        remoteIp: String,
    ): SimpleScanHttpResponse {
        val segments = jobSubPath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return SimpleScanProtocol.errorResponse(400, "Bad Request", "Missing jobId")
        }
        val jobId = SimpleScanProtocol.sanitizeId(segments[0])
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Invalid jobId")
        val action = segments.getOrNull(1)?.lowercase()

        return when {
            method == "GET" && (action == null || action == "progress") -> {
                val deviceId = SimpleScanProtocol.sanitizeId(query["deviceId"])
                val job = jobsById[jobId]
                    ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Scan job not found")
                if (deviceId != null && job.deviceId != deviceId) {
                    return SimpleScanProtocol.errorResponse(403, "Forbidden", "Job belongs to another device")
                }
                SimpleScanProtocol.jsonResponse(200, "OK", job.toJson())
            }

            method == "GET" && action == "page" -> {
                val pageIdx = segments.getOrNull(2)?.toIntOrNull()
                    ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Invalid page index")
                val deviceId = SimpleScanProtocol.sanitizeId(query["deviceId"])
                    ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "deviceId is required")
                handleDownloadPage(jobId, deviceId, pageIdx)
            }

            method == "POST" && action == "ack" -> {
                handleAckPage(jobId, body, remoteIp)
            }

            method == "POST" && action == "cancel" -> {
                handleCancelJob(jobId, body, query)
            }

            else -> SimpleScanProtocol.errorResponse(404, "Not Found", "Unknown scan job endpoint")
        }
    }

    private fun handleDownloadPage(
        jobId: String,
        deviceId: String,
        pageIndex: Int,
    ): SimpleScanHttpResponse {
        val job = jobsById[jobId]
            ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Scan job not found")
        if (job.deviceId != deviceId) {
            return SimpleScanProtocol.errorResponse(403, "Forbidden", "Job belongs to another device")
        }
        val pageMeta = job.pages.firstOrNull { it.index == pageIndex }
            ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Page $pageIndex not found")
        val file = File(jobDirectory(deviceId, jobId), pageMeta.fileName)
        if (!file.exists()) {
            return SimpleScanProtocol.errorResponse(410, "Gone", "Page file already handed over and deleted")
        }

        // Client is actively downloading — reset Lost/Found grace window so handover is never interrupted.
        if (job.status == SimpleScanJobStatus.READY) {
            scheduleLostAndFoundWatchdog(jobId)
        }

        // CRITICAL FAIL-SAFE: Streaming the file NEVER deletes it. Deletion happens ONLY on verified /ack.
        return SimpleScanHttpResponse.FileStream(
            statusCode = 200,
            reason = "OK",
            contentType = "image/jpeg",
            file = file,
            extraHeaders = mapOf(
                "X-Scan-Job-Id" to jobId,
                "X-Scan-Page-Index" to pageIndex.toString(),
                "X-Scan-Sha256" to pageMeta.sha256,
                "X-Scan-Size-Bytes" to pageMeta.sizeBytes.toString(),
                "X-Scan-Width" to pageMeta.width.toString(),
                "X-Scan-Height" to pageMeta.height.toString(),
            ),
        )
    }

    private fun handleAckPage(
        jobId: String,
        body: ByteArray,
        remoteIp: String,
    ): SimpleScanHttpResponse {
        val json = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Invalid ACK JSON")

        val deviceId = SimpleScanProtocol.sanitizeId(json.optString("deviceId"))
            ?: return SimpleScanProtocol.errorResponse(400, "Bad Request", "Valid deviceId required in ACK")
        val pageIndex = json.optInt("pageIndex", -1)
        val clientSha256 = json.optString("sha256", "").trim().lowercase(Locale.US)
        val receivedBytes = json.optLong("receivedBytes", -1L)

        if (pageIndex <= 0 || clientSha256.length != 64 || receivedBytes <= 0L) {
            onLog(
                LogCategory.SCAN,
                "Fail-safe kept host file for $jobId: incomplete ACK from $remoteIp",
                true,
            )
            hostScope.launch { moveUnackedPagesToLostAndFound(jobId, reason = "incomplete client ACK") }
            return SimpleScanProtocol.errorResponse(
                400,
                "Bad Request",
                "ACK must include valid pageIndex, sha256, and receivedBytes; host file preserved",
            )
        }

        synchronized(stateLock) {
            val job = jobsById[jobId]
                ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Scan job not found")
            if (job.deviceId != deviceId) {
                return SimpleScanProtocol.errorResponse(403, "Forbidden", "Job belongs to another device")
            }
            val pageMeta = job.pages.firstOrNull { it.index == pageIndex }
                ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Page $pageIndex not found")

            // Verify client received exact intact payload before deleting anything!
            if (!pageMeta.sha256.equals(clientSha256, ignoreCase = true) || pageMeta.sizeBytes != receivedBytes) {
                onLog(
                    LogCategory.SCAN,
                    "Fail-safe checksum mismatch on ACK for $jobId p$pageIndex from $remoteIp — keeping host file & saving to Lost/Found!",
                    true,
                )
                hostScope.launch { moveUnackedPagesToLostAndFound(jobId, reason = "checksum verification failed") }
                return SimpleScanProtocol.errorResponse(
                    409,
                    "Conflict",
                    "Checksum or byte-size mismatch; host file NOT deleted",
                )
            }

            // Verified! Now delete the handed-over page file safely.
            val jobDir = jobDirectory(deviceId, jobId)
            val pageFile = File(jobDir, pageMeta.fileName)
            if (pageFile.exists()) {
                runCatching { pageFile.delete() }
            }

            val updatedPages = job.pages.map { p ->
                if (p.index == pageIndex) p.copy(acked = true) else p
            }
            val updatedJob = job.copy(
                pages = updatedPages,
                updatedAtMs = System.currentTimeMillis(),
            )
            val allAcked = updatedJob.status == SimpleScanJobStatus.READY && updatedPages.all { it.acked }

            if (allAcked) {
                recoveryWatchdogs.remove(jobId)?.cancel()
                jobsById.remove(jobId)
                runCatching { jobDir.deleteRecursively() }
                cleanupDeviceDirIfEmpty(deviceId)
                onLog(
                    LogCategory.SCAN,
                    "Verified handover complete for $jobId ($deviceId) — deleted host temp files",
                    false,
                )
            } else {
                jobsById[jobId] = updatedJob
                persistMetaLocked(updatedJob)
                onLog(
                    LogCategory.SCAN,
                    "Verified handover of $jobId page $pageIndex — deleted page from host storage",
                    false,
                )
            }
            recomputePendingCount()

            return SimpleScanProtocol.jsonResponse(
                200,
                "OK",
                JSONObject().apply {
                    put("jobId", jobId)
                    put("pageIndex", pageIndex)
                    put("deleted", true)
                    put("jobCompletedAndCleaned", allAcked)
                },
            )
        }
    }

    private fun handleCancelJob(
        jobId: String,
        body: ByteArray,
        query: Map<String, String>,
    ): SimpleScanHttpResponse {
        val bodyJson = runCatching { JSONObject(body.toString(Charsets.UTF_8)) }.getOrNull()
        val deviceId = SimpleScanProtocol.sanitizeId(bodyJson?.optString("deviceId") ?: query["deviceId"])
        val job = jobsById[jobId]
            ?: return SimpleScanProtocol.errorResponse(404, "Not Found", "Scan job not found")
        if (deviceId != null && job.deviceId != deviceId) {
            return SimpleScanProtocol.errorResponse(403, "Forbidden", "Job belongs to another device")
        }

        canceledJobIds.add(jobId)
        activeDrivers[jobId]?.requestCancel()
        if (job.status == SimpleScanJobStatus.QUEUED) {
            synchronized(stateLock) {
                pendingQueueOrder.remove(jobId)
                refreshQueuePositionsLocked()
            }
            markFailedOrCanceled(jobId, job.deviceId, SimpleScanJobStatus.CANCELED, "Scan canceled by user")
        }
        onLog(LogCategory.SCAN, "Canceled remote scan $jobId by client request", false)
        return SimpleScanProtocol.jsonResponse(
            200,
            "OK",
            JSONObject().apply {
                put("jobId", jobId)
                put("canceled", true)
            },
        )
    }

    fun cancelAllRunningJobs() {
        flushUndeliveredToLostAndFoundAsync(reason = "host server stopped")
        synchronized(stateLock) {
            pendingQueueOrder.forEach { jid ->
                canceledJobIds.add(jid)
                val job = jobsById[jid]
                if (job != null && job.status == SimpleScanJobStatus.QUEUED) {
                    jobsById[jid] = job.copy(
                        status = SimpleScanJobStatus.CANCELED,
                        queuePosition = 0,
                        statusMessage = "Scan canceled (server stopped)",
                        updatedAtMs = System.currentTimeMillis(),
                        completedAtMs = System.currentTimeMillis(),
                    )
                }
            }
            pendingQueueOrder.clear()
            _queuedCount.value = 0
        }
        activeDrivers.values.forEach { it.requestCancel() }
        activeDrivers.clear()
        _isScanning.value = false
    }

    fun pendingVaultCountForDevice(deviceId: String): Int {
        val clean = SimpleScanProtocol.sanitizeId(deviceId) ?: return 0
        return jobsById.values.count {
            it.deviceId == clean && it.status == SimpleScanJobStatus.READY && it.unackedPages.isNotEmpty()
        }
    }

    private fun updateJobState(
        jobId: String,
        persist: Boolean = true,
        transform: (SimpleScanJobState) -> SimpleScanJobState,
    ) {
        synchronized(stateLock) {
            val current = jobsById[jobId] ?: return
            val next = transform(current)
            jobsById[jobId] = next
            if (persist) {
                persistMetaLocked(next)
            }
        }
    }

    private fun persistMetaLocked(state: SimpleScanJobState) {
        runCatching {
            val dir = jobDirectory(state.deviceId, state.jobId).apply { mkdirs() }
            val tmp = File(dir, "$META_FILE_NAME.tmp")
            val target = File(dir, META_FILE_NAME)
            tmp.writeText(state.toJson().toString())
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun jobDirectory(deviceId: String, jobId: String): File =
        File(File(vaultDir, deviceId), jobId)

    private fun cleanupDeviceDirIfEmpty(deviceId: String) {
        val devDir = File(vaultDir, deviceId)
        if (devDir.exists() && devDir.listFiles().isNullOrEmpty()) {
            runCatching { devDir.delete() }
        }
    }

    private fun recomputePendingCount() {
        _pendingVaultJobCount.value = jobsById.values.count {
            it.status == SimpleScanJobStatus.READY && it.unackedPages.isNotEmpty()
        }
    }

    companion object {
        private const val META_FILE_NAME = "meta.json"
        private const val HOST_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HOST_VAULT_JPEG_QUALITY = 88
        const val DEFAULT_LOST_AND_FOUND_TIMEOUT_MS = 120_000L
    }
}
