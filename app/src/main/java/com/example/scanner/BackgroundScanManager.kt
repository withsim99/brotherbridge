package com.example.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.example.data.SettingsRepository
import com.example.model.ActivePrinterState
import com.example.model.IdCardMergeLayout
import com.example.model.LogCategory
import com.example.model.PrinterConnectionMode
import com.example.model.ScanColorOption
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
import com.example.printer.network.DeviceIdentityHelper
import com.example.service.ScanBackgroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class IdCardMergeSpec(
    val frontPageIndex: Int,
    val frontPageId: Long,
    val frontBitmap: Bitmap? = null,
    val pageSize: ScanPageSizeOption,
    val layout: IdCardMergeLayout,
    val dpi: Int,
)

data class BackgroundScanState(
    val isScanning: Boolean = false,
    val isRemote: Boolean = false,
    val remoteHost: String? = null,
    val remotePort: Int = 8631,
    val remoteJobId: String? = null,
    val targetCollectionId: String? = null,
    val sessionTitle: String = "",
    val params: ScanParameters = ScanParameters(),
    val progress: Float = 0f,
    val statusText: String = "",
    val errorMessage: String? = null,
)

data class ScanHandoverEvent(
    val collectionId: String,
    val sessionTitle: String,
    val pages: List<Bitmap>,
    val dpi: Int,
    val colorLabel: String,
    val pageSizeLabel: String,
    val pageSize: ScanPageSizeOption = ScanPageSizeOption.A4,
    val isRecovered: Boolean,
    val bannerMessage: String,
    val replacedPageIndex: Int? = null,
    val replacedPageId: Long? = null,
    val isMergedTwoInOne: Boolean = false,
    val mergedLayout: IdCardMergeLayout? = null,
)

/**
 * Application-scoped background scan coordinator for BOTH Wireless Remote (`/api/scan/...`) and
 * Direct USB OTG scans.
 *
 * - Never cancelled when the user navigates back from `ScanScreen` or backgrounds the app.
 * - For Wireless Remote scans: sends `POST /api/scan/start` with persistent `deviceId`, polls
 *   `GET /api/scan/jobs/{jobId}/progress` every 2 seconds with guaranteed 5–6s progress reports,
 *   auto-retries timeouts after 4 seconds (up to 3 full attempts), verifies SHA-256 + byte length on
 *   download, persists locally to Recent Scans FIRST, and ONLY THEN sends `POST /ack` so the
 *   host safely deletes its vault copy.
 * - Automatically calls `GET /api/scan/pending?deviceId=...` on reconnect or app restart to
 *   resume running scans and recover any lost/uncollected scans from the host.
 */
class BackgroundScanManager(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val activePrinterProvider: () -> ActivePrinterState,
    private val directUsbScanRunner: suspend (
        params: ScanParameters,
        onProgress: (Float, String) -> Unit,
    ) -> Result<List<Bitmap>>,
    private val cancelDirectUsbScan: () -> Unit,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
) {
    private val trackingFile = File(context.filesDir, "active_remote_scan.json")
    private val downloadCacheDir = File(context.cacheDir, "scan_handover").apply { mkdirs() }

    private val _state = MutableStateFlow(BackgroundScanState())
    val state: StateFlow<BackgroundScanState> = _state.asStateFlow()

    private val _handoverEvents = MutableSharedFlow<ScanHandoverEvent>(extraBufferCapacity = 32)
    val handoverEvents: SharedFlow<ScanHandoverEvent> = _handoverEvents.asSharedFlow()

    private var activeScanJob: Job? = null
    private val syncInProgress = AtomicBoolean(false)
    private val activeHandoverJobIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var cachedDeviceId: String? = null

    init {
        appScope.launch {
            cachedDeviceId = settingsRepository.ensureClientDeviceId()
            attemptColdStartRecovery()
        }
    }

    suspend fun getDeviceId(): String {
        cachedDeviceId?.let { return it }
        val resolved = settingsRepository.ensureClientDeviceId()
        cachedDeviceId = resolved
        return resolved
    }

    /**
     * Starts a scan in the application-scoped background coroutine so the user can freely navigate
     * back or close the app while scanning continues.
     */
    fun startScan(
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>,
        nextPageNumber: Int,
        idCardMergeSpec: IdCardMergeSpec? = null,
    ) {
        if (_state.value.isScanning) return

        val active = activePrinterProvider()
        if (!active.isConnected) {
            _state.update { it.copy(errorMessage = "Connect a printer/scanner first") }
            return
        }
        if (!active.supportsScan) {
            _state.update { it.copy(errorMessage = "Connected printer does not expose a scanner") }
            return
        }

        val cleanTitle = sessionTitle.ifBlank {
            "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
        }

        when (active.mode) {
            PrinterConnectionMode.WIRELESS_REMOTE -> {
                val remote = active.remotePrinter ?: run {
                    _state.update { it.copy(errorMessage = "Wireless server not connected") }
                    return
                }
                startWirelessBackgroundScan(
                    host = remote.host,
                    port = remote.port,
                    params = params,
                    sessionTitle = cleanTitle,
                    targetCollectionId = targetCollectionId,
                    existingPagesSnapshot = existingPagesSnapshot,
                    nextPageNumber = nextPageNumber,
                    idCardMergeSpec = idCardMergeSpec,
                )
            }

            PrinterConnectionMode.USB_OTG -> {
                startDirectUsbBackgroundScan(
                    params = params,
                    sessionTitle = cleanTitle,
                    targetCollectionId = targetCollectionId,
                    existingPagesSnapshot = existingPagesSnapshot,
                    nextPageNumber = nextPageNumber,
                    idCardMergeSpec = idCardMergeSpec,
                )
            }

            PrinterConnectionMode.NONE -> {
                _state.update { it.copy(errorMessage = "No scanner connected") }
            }
        }
    }

    fun cancelActiveScan() {
        val current = _state.value
        val jobToCancel = activeScanJob
        activeScanJob = null
        jobToCancel?.cancel()

        if (current.isRemote && !current.remoteHost.isNullOrBlank() && !current.remoteJobId.isNullOrBlank()) {
            val host = current.remoteHost
            val port = current.remotePort
            val jobId = current.remoteJobId
            appScope.launch {
                val devId = getDeviceId()
                SimpleRemoteScanClient.cancelJob(host, port, jobId, devId)
            }
            clearTrackedRemoteScan()
        } else {
            cancelDirectUsbScan()
        }

        _state.update {
            it.copy(
                isScanning = false,
                remoteJobId = null,
                progress = 0f,
                statusText = "",
                errorMessage = null,
            )
        }
        ScanBackgroundService.stop(context)
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Checks the connected wireless host for any running or uncollected (lost/left-over) scans
     * belonging to this device's unique `deviceId`.
     * - If a job is `READY` with un-acked pages -> downloads, verifies SHA-256, saves locally, then ACKs.
     * - If a job is `RUNNING` -> seamlessly attaches our 1-second progress polling loop!
     */
    fun syncPendingRemoteScans(host: String, port: Int) {
        if (!syncInProgress.compareAndSet(false, true)) return
        appScope.launch {
            try {
                val deviceId = getDeviceId()
                val pendingRes = SimpleRemoteScanClient.getPendingJobs(host, port, deviceId)
                val jobs = pendingRes.getOrNull() ?: return@launch
                if (jobs.isEmpty()) {
                    // Clean stale tracking file if host has nothing pending for us
                    val tracked = loadTrackedRemoteScan()
                    if (tracked != null && tracked.host == host && tracked.port == port && !_state.value.isScanning) {
                        clearTrackedRemoteScan()
                    }
                    return@launch
                }

                onLog(
                    LogCategory.SCAN,
                    "Found ${jobs.size} pending scan job(s) on host $host:$port for device $deviceId",
                    false,
                )

                val tracked = loadTrackedRemoteScan()
                for (job in jobs) {
                    // Never race with an active foreground/background poll or handover for the same jobId!
                    val curState = _state.value
                    if ((curState.isScanning && curState.remoteJobId == job.jobId) ||
                        job.jobId in activeHandoverJobIds
                    ) {
                        continue
                    }

                    val isMatchingTracked = tracked != null && tracked.jobId == job.jobId
                    val collectionId = if (isMatchingTracked) tracked?.collectionId else null
                    val title = if (isMatchingTracked && !tracked?.sessionTitle.isNullOrBlank()) {
                        tracked!!.sessionTitle
                    } else {
                        "Recovered_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(job.createdAtMs))}"
                    }

                    val trackedMergeSpec = if (isMatchingTracked) tracked?.toMergeSpec() else null
                    when (job.status) {
                        SimpleScanJobStatus.READY -> {
                            handoverAndPersistRemoteJob(
                                host = host,
                                port = port,
                                deviceId = deviceId,
                                job = job,
                                params = ScanParameters(
                                    source = job.source,
                                    colorMode = job.colorMode,
                                    dpi = job.dpi,
                                    pageSize = job.pageSize,
                                    brightness = job.brightness,
                                    contrast = job.contrast,
                                    twoInOneIdScan = trackedMergeSpec != null,
                                    idCardLayout = trackedMergeSpec?.layout ?: IdCardMergeLayout.VERTICAL,
                                ),
                                sessionTitle = title,
                                targetCollectionId = collectionId,
                                existingPagesSnapshot = null,
                                idCardMergeSpec = trackedMergeSpec,
                                isRecovered = true,
                            )
                        }

                        SimpleScanJobStatus.QUEUED,
                        SimpleScanJobStatus.RUNNING -> {
                            if (!_state.value.isScanning) {
                                attachPollingToRemoteJob(
                                    host = host,
                                    port = port,
                                    deviceId = deviceId,
                                    initialJob = job,
                                    sessionTitle = title,
                                    targetCollectionId = collectionId,
                                    existingPagesSnapshot = null,
                                    idCardMergeSpec = trackedMergeSpec,
                                    isRecovered = true,
                                )
                            }
                        }

                        else -> Unit
                    }
                }
            } finally {
                syncInProgress.set(false)
            }
        }
    }

    private suspend fun attemptColdStartRecovery() {
        val tracked = loadTrackedRemoteScan() ?: return
        // Give Wi-Fi a short moment after process start before probing previous host
        delay(1_200L)
        if (_state.value.isScanning) return
        val caps = SimpleRemoteScanClient.probeCapabilities(tracked.host, tracked.port, timeoutMs = 4_000)
        if (caps != null) {
            onLog(
                LogCategory.SCAN,
                "Previous scan host ${tracked.host}:${tracked.port} reachable on startup — checking for unfinished scans",
                false,
            )
            syncPendingRemoteScans(tracked.host, tracked.port)
        }
    }

    private sealed interface PollOutcome {
        data object Completed : PollOutcome
        data object Canceled : PollOutcome
        data class RecoverableFailure(val reason: String) : PollOutcome
    }

    private fun startWirelessBackgroundScan(
        host: String,
        port: Int,
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>,
        nextPageNumber: Int,
        idCardMergeSpec: IdCardMergeSpec? = null,
    ) {
        _state.value = BackgroundScanState(
            isScanning = true,
            isRemote = true,
            remoteHost = host,
            remotePort = port,
            remoteJobId = null,
            targetCollectionId = targetCollectionId,
            sessionTitle = sessionTitle,
            params = params,
            progress = 0.03f,
            statusText = "Sending HTTP scan request to host (Page $nextPageNumber)...",
            errorMessage = null,
        )
        ScanBackgroundService.start(context)

        activeScanJob?.cancel()
        activeScanJob = appScope.launch {
            try {
                val deviceId = getDeviceId()
                val clientName = DeviceIdentityHelper.currentDisplayName(context)
                var lastFailureReason: String? = null

                for (remoteAttempt in 1..MAX_REMOTE_SCAN_ATTEMPTS) {
                    val attemptSuffix = if (remoteAttempt > 1) " (attempt $remoteAttempt/$MAX_REMOTE_SCAN_ATTEMPTS)" else ""
                    _state.update {
                        it.copy(
                            progress = 0.03f,
                            statusText = "Sending HTTP scan request to host (Page $nextPageNumber)$attemptSuffix...",
                        )
                    }
                    onLog(
                        LogCategory.SCAN,
                        "POST /api/scan/start -> $host:$port (${params.source.displayName}, ${params.dpi} DPI, ${params.colorMode.shortLabel}, dev=$deviceId, attempt=$remoteAttempt/$MAX_REMOTE_SCAN_ATTEMPTS)",
                        false,
                    )

                    val startResult = SimpleRemoteScanClient.startScan(
                        host = host,
                        port = port,
                        deviceId = deviceId,
                        clientName = clientName,
                        params = params,
                        onRetryStatus = { retryMsg ->
                            _state.update { it.copy(statusText = retryMsg) }
                        },
                    )
                    val startedJob = startResult.getOrElse { err ->
                        throw err
                    }

                    saveTrackedRemoteScan(
                        TrackedRemoteScan(
                            host = host,
                            port = port,
                            jobId = startedJob.jobId,
                            collectionId = targetCollectionId,
                            sessionTitle = sessionTitle,
                            mergeFrontPageIndex = idCardMergeSpec?.frontPageIndex,
                            mergeFrontPageId = idCardMergeSpec?.frontPageId,
                            mergePageSize = idCardMergeSpec?.pageSize?.name,
                            mergeLayout = idCardMergeSpec?.layout?.name,
                            mergeDpi = idCardMergeSpec?.dpi,
                        ),
                    )

                    _state.update {
                        it.copy(
                            remoteJobId = startedJob.jobId,
                            progress = (startedJob.progress / 100f).coerceIn(0.04f, 0.99f),
                            statusText = startedJob.statusMessage.ifBlank { "Host accepted scan request..." },
                        )
                    }

                    val outcome = pollAndHandoverLoop(
                        host = host,
                        port = port,
                        deviceId = deviceId,
                        jobId = startedJob.jobId,
                        params = params,
                        sessionTitle = sessionTitle,
                        targetCollectionId = targetCollectionId,
                        existingPagesSnapshot = existingPagesSnapshot,
                        idCardMergeSpec = idCardMergeSpec,
                        isRecovered = false,
                    )

                    when (outcome) {
                        PollOutcome.Completed,
                        PollOutcome.Canceled -> return@launch

                        is PollOutcome.RecoverableFailure -> {
                            lastFailureReason = outcome.reason
                            clearTrackedRemoteScan()
                            if (remoteAttempt < MAX_REMOTE_SCAN_ATTEMPTS) {
                                onLog(
                                    LogCategory.SCAN,
                                    "Remote scan attempt $remoteAttempt/$MAX_REMOTE_SCAN_ATTEMPTS timed out/failed (${outcome.reason}) — retrying in ${RETRY_DELAY_MS / 1000}s",
                                    true,
                                )
                                countdownStatusDelay(
                                    delayMs = RETRY_DELAY_MS,
                                    progress = 0.04f,
                                    messageForSecond = { sec ->
                                        "Scanner timed out — retrying in ${sec}s (attempt ${remoteAttempt + 1}/$MAX_REMOTE_SCAN_ATTEMPTS)..."
                                    },
                                )
                            } else {
                                throw IllegalStateException(outcome.reason)
                            }
                        }
                    }
                }

                throw IllegalStateException(lastFailureReason ?: "Wireless scan failed after $MAX_REMOTE_SCAN_ATTEMPTS attempts")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = t.message ?: "Wireless scan failed"
                onLog(LogCategory.SCAN, "Wireless scan error: $msg", true)
                _state.update {
                    it.copy(
                        isScanning = false,
                        progress = 0f,
                        statusText = "",
                        errorMessage = msg,
                    )
                }
            } finally {
                activeScanJob = null
                if (!_state.value.isScanning) {
                    ScanBackgroundService.stop(context)
                }
            }
        }
    }

    private fun attachPollingToRemoteJob(
        host: String,
        port: Int,
        deviceId: String,
        initialJob: SimpleScanJobState,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>?,
        idCardMergeSpec: IdCardMergeSpec? = null,
        isRecovered: Boolean,
    ) {
        val params = ScanParameters(
            source = initialJob.source,
            colorMode = initialJob.colorMode,
            dpi = initialJob.dpi,
            pageSize = initialJob.pageSize,
            brightness = initialJob.brightness,
            contrast = initialJob.contrast,
            twoInOneIdScan = idCardMergeSpec != null,
            idCardLayout = idCardMergeSpec?.layout ?: IdCardMergeLayout.VERTICAL,
        )
        _state.value = BackgroundScanState(
            isScanning = true,
            isRemote = true,
            remoteHost = host,
            remotePort = port,
            remoteJobId = initialJob.jobId,
            targetCollectionId = targetCollectionId,
            sessionTitle = sessionTitle,
            params = params,
            progress = (initialJob.progress / 100f).coerceIn(0.05f, 0.99f),
            statusText = initialJob.statusMessage.ifBlank { "Resumed background scan..." },
            errorMessage = null,
        )
        ScanBackgroundService.start(context)

        activeScanJob?.cancel()
        activeScanJob = appScope.launch {
            try {
                val outcome = pollAndHandoverLoop(
                    host = host,
                    port = port,
                    deviceId = deviceId,
                    jobId = initialJob.jobId,
                    params = params,
                    sessionTitle = sessionTitle,
                    targetCollectionId = targetCollectionId,
                    existingPagesSnapshot = existingPagesSnapshot,
                    idCardMergeSpec = idCardMergeSpec,
                    isRecovered = isRecovered,
                )
                if (outcome is PollOutcome.RecoverableFailure) {
                    throw IllegalStateException(outcome.reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val msg = t.message ?: "Background scan connection interrupted"
                onLog(LogCategory.SCAN, "Resumed scan poll error: $msg", true)
                _state.update {
                    it.copy(
                        isScanning = false,
                        progress = 0f,
                        statusText = "",
                        errorMessage = msg,
                    )
                }
            } finally {
                activeScanJob = null
                ScanBackgroundService.stop(context)
            }
        }
    }

    private suspend fun pollAndHandoverLoop(
        host: String,
        port: Int,
        deviceId: String,
        jobId: String,
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>?,
        idCardMergeSpec: IdCardMergeSpec? = null,
        isRecovered: Boolean,
    ): PollOutcome {
        var consecutiveErrors = 0
        val loopStartedAtMs = System.currentTimeMillis()
        var lastHostActivityAtMs = loopStartedAtMs
        var lastSeenHostTimestamp = 0L
        var lastReportAtMs = loopStartedAtMs
        var lastDisplayedText = ""

        while (appScope.isActive) {
            delay(POLL_INTERVAL_MS)

            val pollResult = SimpleRemoteScanClient.getJobProgress(host, port, jobId, deviceId)
            val jobState = pollResult.getOrNull()
            if (jobState == null) {
                consecutiveErrors++
                val errMsg = pollResult.exceptionOrNull()?.message ?: "Host unreachable"
                if (consecutiveErrors >= MAX_POLL_RETRIES) {
                    // Leave tracking file intact so reconnect auto-recovers once Wi-Fi returns!
                    throw IllegalStateException("Lost connection to host ($errMsg). Your scan is safe on the host and will auto-recover when reconnected.")
                }
                if (consecutiveErrors == 1) {
                    // Single transient Wi-Fi blip — retry quickly without alarming countdown
                    delay(1_000L)
                } else {
                    countdownStatusDelay(
                        delayMs = RETRY_DELAY_MS,
                        progress = _state.value.progress.coerceAtLeast(0.05f),
                        messageForSecond = { sec ->
                            "Connection busy — retrying in ${sec}s (retry $consecutiveErrors/$MAX_POLL_RETRIES)..."
                        },
                    )
                }
                continue
            }

            consecutiveErrors = 0
            val now = System.currentTimeMillis()
            val hostStamp = maxOf(jobState.heartbeatAtMs, jobState.updatedAtMs)
            if (hostStamp != lastSeenHostTimestamp) {
                lastSeenHostTimestamp = hostStamp
                lastHostActivityAtMs = now
            }

            when (jobState.status) {
                SimpleScanJobStatus.QUEUED -> {
                    val elapsedSec = ((now - loopStartedAtMs) / 1000L).toInt().coerceAtLeast(1)
                    val posText = if (jobState.queuePosition > 0) " (#${jobState.queuePosition})" else ""
                    val rawMsg = jobState.statusMessage.ifBlank {
                        "Queued on host$posText — waiting for scanner..."
                    }
                    val dueForReport = (now - lastReportAtMs) >= PROGRESS_REPORT_INTERVAL_MS
                    val displayMsg = formatProgressReport(rawMsg, elapsedSec, forceTimestamp = dueForReport)
                    if (displayMsg != lastDisplayedText || dueForReport) {
                        lastDisplayedText = displayMsg
                        lastReportAtMs = now
                        _state.update {
                            it.copy(
                                progress = 0.04f,
                                statusText = displayMsg,
                            )
                        }
                    }
                }

                SimpleScanJobStatus.RUNNING -> {
                    if (now - lastHostActivityAtMs >= HOST_STALL_TIMEOUT_MS) {
                        runCatching { SimpleRemoteScanClient.cancelJob(host, port, jobId, deviceId) }
                        return PollOutcome.RecoverableFailure(
                            "Scanner stalled with no progress update for ${HOST_STALL_TIMEOUT_MS / 1000}s"
                        )
                    }
                    val elapsedSec = ((now - loopStartedAtMs) / 1000L).toInt().coerceAtLeast(1)
                    val rawMsg = jobState.statusMessage.ifBlank { "Scanning (${jobState.progress}%)..." }
                    val dueForReport = (now - lastReportAtMs) >= PROGRESS_REPORT_INTERVAL_MS
                    val displayMsg = formatProgressReport(rawMsg, elapsedSec, forceTimestamp = dueForReport)
                    if (displayMsg != lastDisplayedText || dueForReport) {
                        lastDisplayedText = displayMsg
                        lastReportAtMs = now
                        _state.update {
                            it.copy(
                                progress = (jobState.progress / 100f).coerceIn(0.05f, 0.95f),
                                statusText = displayMsg,
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(progress = (jobState.progress / 100f).coerceIn(0.05f, 0.95f))
                        }
                    }
                }

                SimpleScanJobStatus.READY -> {
                    _state.update {
                        it.copy(
                            progress = 0.96f,
                            statusText = "Receiving scanned file from host...",
                        )
                    }
                    val ok = handoverAndPersistRemoteJob(
                        host = host,
                        port = port,
                        deviceId = deviceId,
                        job = jobState,
                        params = params,
                        sessionTitle = sessionTitle,
                        targetCollectionId = targetCollectionId,
                        existingPagesSnapshot = existingPagesSnapshot,
                        idCardMergeSpec = idCardMergeSpec,
                        isRecovered = isRecovered,
                    )
                    if (!ok) {
                        throw IllegalStateException("Handover verification failed — host kept your scan safely for retry on reconnect")
                    }
                    _state.update {
                        it.copy(
                            isScanning = false,
                            remoteJobId = null,
                            progress = 1f,
                            statusText = "",
                            errorMessage = null,
                        )
                    }
                    return PollOutcome.Completed
                }

                SimpleScanJobStatus.CANCELED -> {
                    clearTrackedRemoteScan()
                    _state.update {
                        it.copy(
                            isScanning = false,
                            remoteJobId = null,
                            progress = 0f,
                            statusText = "",
                            errorMessage = null,
                        )
                    }
                    return PollOutcome.Canceled
                }

                SimpleScanJobStatus.FAILED -> {
                    clearTrackedRemoteScan()
                    val errText = jobState.errorMessage ?: jobState.statusMessage.ifBlank { "Host scan failed" }
                    if (isRecoverableRemoteScanError(errText)) {
                        return PollOutcome.RecoverableFailure(errText)
                    }
                    throw IllegalStateException(errText)
                }
            }
        }
        return PollOutcome.Canceled
    }

    private suspend fun countdownStatusDelay(
        delayMs: Long,
        progress: Float,
        messageForSecond: (Int) -> String,
    ) {
        val totalSec = ((delayMs + 999L) / 1000L).toInt().coerceAtLeast(1)
        for (sec in totalSec downTo 1) {
            _state.update {
                it.copy(
                    progress = progress,
                    statusText = messageForSecond(sec),
                )
            }
            delay(1_000L)
        }
    }

    private fun formatProgressReport(
        rawMessage: String,
        elapsedSec: Int,
        forceTimestamp: Boolean,
    ): String {
        val alreadyHasTime = rawMessage.contains(Regex("\\(\\d+s\\)|•\\s*\\d+s"))
        return if (forceTimestamp && !alreadyHasTime) {
            "$rawMessage • ${elapsedSec}s"
        } else {
            rawMessage
        }
    }

    private fun isRecoverableRemoteScanError(message: String): Boolean {
        if (message.contains("0xC2") || message.contains("0xC3") || message.contains("0xC4")) return false
        if (message.contains("No document", ignoreCase = true)) return false
        if (message.contains("Document jam", ignoreCase = true)) return false
        if (message.contains("cover is open", ignoreCase = true)) return false
        if (message.contains("ADF is not supported", ignoreCase = true)) return false
        if (message.contains("USB permission", ignoreCase = true)) return false
        if (message.contains("Scanner disconnected", ignoreCase = true)) return false
        if (message.contains("not connected", ignoreCase = true)) return false
        return true
    }

    /**
     * Fail-safe handover pipeline:
     * 1. Download all un-acked pages + verify exact byte length + verify SHA-256 + decode valid Bitmap.
     * 2. Persist ALL newly downloaded pages safely into local Recent Scans storage FIRST.
     * 3. ONLY AFTER local storage save succeeds, send `POST /ack` per page with verified SHA-256 so
     *    the host deletes its vault copy.
     * If ANY step before step 3 fails, ACK is NOT sent and host preserves the files!
     */
    private suspend fun handoverAndPersistRemoteJob(
        host: String,
        port: Int,
        deviceId: String,
        job: SimpleScanJobState,
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>?,
        idCardMergeSpec: IdCardMergeSpec? = null,
        isRecovered: Boolean,
    ): Boolean {
        if (!activeHandoverJobIds.add(job.jobId)) {
            // Another coroutine (e.g. active poll or reconnect sync) is already handing over this exact job
            return true
        }
        try {
            val unacked = job.unackedPages.sortedBy { it.index }
            if (unacked.isEmpty()) {
                clearTrackedRemoteScan()
                return true
            }

            val downloaded = mutableListOf<DownloadedScanPage>()
            for ((idx, pageMeta) in unacked.withIndex()) {
                _state.update {
                    if (it.isScanning) {
                        it.copy(
                            progress = 0.96f + 0.03f * (idx.toFloat() / unacked.size.coerceAtLeast(1)),
                            statusText = "Downloading & verifying page ${pageMeta.index}/${unacked.size}...",
                        )
                    } else {
                        it
                    }
                }

                var pageResult: Result<DownloadedScanPage>? = null
                val retryBackoffs = longArrayOf(1_500L, 2_500L, 3_500L)
                for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
                    val res = SimpleRemoteScanClient.downloadAndVerifyPage(
                        host = host,
                        port = port,
                        jobId = job.jobId,
                        pageMeta = pageMeta,
                        deviceId = deviceId,
                        cacheDir = downloadCacheDir,
                    )
                    if (res.isSuccess) {
                        pageResult = res
                        break
                    }
                    val errText = res.exceptionOrNull()?.message.orEmpty()
                    if (errText.contains("HTTP 410") || errText.contains("HTTP 404")) {
                        pageResult = res
                        break
                    }
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        val waitMs = retryBackoffs[(attempt - 1).coerceAtMost(retryBackoffs.lastIndex)]
                        countdownStatusDelay(
                            delayMs = waitMs,
                            progress = 0.96f,
                            messageForSecond = { sec ->
                                "Download interrupted — retrying in ${sec}s (attempt ${attempt + 1}/$MAX_DOWNLOAD_ATTEMPTS)..."
                            },
                        )
                    } else {
                        pageResult = res
                    }
                }

                val okPage = pageResult?.getOrNull()
                if (okPage == null) {
                    val reason = pageResult?.exceptionOrNull()?.message ?: "Download failed"
                    onLog(
                        LogCategory.SCAN,
                        "Fail-safe handover aborted for ${job.jobId} p${pageMeta.index} ($reason) — NOT sending ACK so host keeps file",
                        true,
                    )
                    downloaded.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    return false
                }
                downloaded.add(okPage)
            }

            // Step 2: Persist locally BEFORE acknowledging to host!
            val existingPages: List<Bitmap> = when {
                existingPagesSnapshot != null -> existingPagesSnapshot.filterNot { it.isRecycled }
                !targetCollectionId.isNullOrBlank() -> {
                    val existingRecord = ScanExportManager.loadRecentScans(context)
                        .firstOrNull { it.id == targetCollectionId }
                    if (existingRecord != null) {
                        ScanExportManager.loadCollectionPages(existingRecord)
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }

            val mergeOutcome = applyTwoInOneMergeIfRequested(
                existingPages = existingPages,
                newPages = downloaded.map { it.bitmap },
                params = params,
                idCardMergeSpec = idCardMergeSpec,
            )

            val saveResult = ScanExportManager.saveOrUpdateScanCollection(
                context = context,
                existingId = targetCollectionId,
                title = sessionTitle,
                pages = mergeOutcome.combinedPages,
            )
            val savedRecord = saveResult.getOrNull()
            if (savedRecord == null) {
                onLog(
                    LogCategory.SCAN,
                    "Local save failed for ${job.jobId} (${saveResult.exceptionOrNull()?.message}) — NOT sending ACK so host keeps file",
                    true,
                )
                downloaded.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                return false
            }

            // Step 3: Local save succeeded! Now send verified ACK for each page so host deletes it.
            var allAcked = true
            for (dl in downloaded) {
                val ackRes = SimpleRemoteScanClient.ackPage(
                    host = host,
                    port = port,
                    jobId = job.jobId,
                    deviceId = deviceId,
                    pageIndex = dl.pageIndex,
                    sha256 = dl.sha256,
                    receivedBytes = dl.sizeBytes,
                )
                if (ackRes.isFailure) {
                    allAcked = false
                    onLog(
                        LogCategory.SCAN,
                        "ACK warning for ${job.jobId} p${dl.pageIndex}: ${ackRes.exceptionOrNull()?.message}",
                        true,
                    )
                }
            }

            if (allAcked) {
                clearTrackedRemoteScan()
            }

            val bannerMsg = when {
                mergeOutcome.isMerged -> "2-in-1 ID merged (${mergeOutcome.outputPageSize.displayName})"
                isRecovered -> "Recovered ${downloaded.size} pending scan page${if (downloaded.size == 1) "" else "s"} from host"
                else -> "Scan complete (${downloaded.size} page${if (downloaded.size == 1) "" else "s"})"
            }
            onLog(LogCategory.SCAN, "$bannerMsg [${job.jobId}]", false)

            _handoverEvents.emit(
                ScanHandoverEvent(
                    collectionId = savedRecord.id,
                    sessionTitle = savedRecord.title,
                    pages = mergeOutcome.deliveredPages,
                    dpi = idCardMergeSpec?.dpi ?: job.dpi,
                    colorLabel = job.colorMode.shortLabel,
                    pageSizeLabel = if (mergeOutcome.isMerged) {
                        "${mergeOutcome.outputPageSize.displayName} • 2-in-1 ID"
                    } else {
                        job.pageSize.displayName
                    },
                    pageSize = mergeOutcome.outputPageSize,
                    isRecovered = isRecovered,
                    bannerMessage = bannerMsg,
                    replacedPageIndex = mergeOutcome.replacedPageIndex,
                    replacedPageId = mergeOutcome.replacedPageId,
                    isMergedTwoInOne = mergeOutcome.isMerged,
                    mergedLayout = mergeOutcome.layout,
                ),
            )
            return true
        } finally {
            activeHandoverJobIds.remove(job.jobId)
        }
    }

    private fun startDirectUsbBackgroundScan(
        params: ScanParameters,
        sessionTitle: String,
        targetCollectionId: String?,
        existingPagesSnapshot: List<Bitmap>,
        nextPageNumber: Int,
        idCardMergeSpec: IdCardMergeSpec? = null,
    ) {
        _state.value = BackgroundScanState(
            isScanning = true,
            isRemote = false,
            targetCollectionId = targetCollectionId,
            sessionTitle = sessionTitle,
            params = params,
            progress = 0.04f,
            statusText = "Scanning Page $nextPageNumber via USB...",
            errorMessage = null,
        )
        ScanBackgroundService.start(context)

        activeScanJob?.cancel()
        activeScanJob = appScope.launch {
            val scanStartedAtMs = System.currentTimeMillis()
            val lastProgressAtMs = AtomicLong(scanStartedAtMs)
            val usbHeartbeatJob = launch {
                while (isActive) {
                    delay(PROGRESS_REPORT_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAtMs.get() >= PROGRESS_REPORT_INTERVAL_MS - 400L) {
                        val elapsedSec = ((now - scanStartedAtMs) / 1000L).toInt().coerceAtLeast(1)
                        _state.update { current ->
                            if (!current.isScanning) return@update current
                            val base = current.statusText
                                .replace(Regex("\\s*\\(\\d+s\\)$"), "")
                                .replace(Regex("\\s*•\\s*\\d+s$"), "")
                                .ifBlank { "Scanning via USB..." }
                            current.copy(statusText = "$base • ${elapsedSec}s")
                        }
                    }
                }
            }

            try {
                val result = directUsbScanRunner(params) { progress, statusText ->
                    lastProgressAtMs.set(System.currentTimeMillis())
                    _state.update {
                        it.copy(
                            progress = progress.coerceIn(0f, 1f),
                            statusText = statusText,
                        )
                    }
                }

                result.fold(
                    onSuccess = { bitmaps ->
                        if (bitmaps.isNotEmpty()) {
                            val mergeOutcome = applyTwoInOneMergeIfRequested(
                                existingPages = existingPagesSnapshot,
                                newPages = bitmaps,
                                params = params,
                                idCardMergeSpec = idCardMergeSpec,
                            )
                            val saved = ScanExportManager.saveOrUpdateScanCollection(
                                context = context,
                                existingId = targetCollectionId,
                                title = sessionTitle,
                                pages = mergeOutcome.combinedPages,
                            ).getOrNull()

                            _handoverEvents.emit(
                                ScanHandoverEvent(
                                    collectionId = saved?.id ?: targetCollectionId.orEmpty(),
                                    sessionTitle = saved?.title ?: sessionTitle,
                                    pages = mergeOutcome.deliveredPages,
                                    dpi = idCardMergeSpec?.dpi ?: params.dpi,
                                    colorLabel = params.colorMode.shortLabel,
                                    pageSizeLabel = if (mergeOutcome.isMerged) {
                                        "${mergeOutcome.outputPageSize.displayName} • 2-in-1 ID"
                                    } else {
                                        params.pageSize.displayName
                                    },
                                    pageSize = mergeOutcome.outputPageSize,
                                    isRecovered = false,
                                    bannerMessage = if (mergeOutcome.isMerged) {
                                        "2-in-1 ID merged (${mergeOutcome.outputPageSize.displayName})"
                                    } else {
                                        "Captured ${bitmaps.size} page${if (bitmaps.size == 1) "" else "s"}"
                                    },
                                    replacedPageIndex = mergeOutcome.replacedPageIndex,
                                    replacedPageId = mergeOutcome.replacedPageId,
                                    isMergedTwoInOne = mergeOutcome.isMerged,
                                    mergedLayout = mergeOutcome.layout,
                                ),
                            )
                        }
                        _state.update {
                            it.copy(
                                isScanning = false,
                                progress = 0f,
                                statusText = "",
                                errorMessage = null,
                            )
                        }
                    },
                    onFailure = { err ->
                        val isCanceled = err is CancellationException ||
                            err is InterruptedException ||
                            err is BrotherDcpL2520dScanner.ScanCancelledException
                        _state.update {
                            it.copy(
                                isScanning = false,
                                progress = 0f,
                                statusText = "",
                                errorMessage = if (isCanceled) null else (err.message ?: "USB scan failed"),
                            )
                        }
                    },
                )
            } finally {
                usbHeartbeatJob.cancel()
                activeScanJob = null
                ScanBackgroundService.stop(context)
            }
        }
    }

    private data class TwoInOneMergeOutcome(
        val combinedPages: List<Bitmap>,
        val deliveredPages: List<Bitmap>,
        val replacedPageIndex: Int?,
        val replacedPageId: Long?,
        val isMerged: Boolean,
        val layout: IdCardMergeLayout?,
        val outputPageSize: ScanPageSizeOption,
    )

    private suspend fun applyTwoInOneMergeIfRequested(
        existingPages: List<Bitmap>,
        newPages: List<Bitmap>,
        params: ScanParameters,
        idCardMergeSpec: IdCardMergeSpec?,
    ): TwoInOneMergeOutcome {
        // Case 1: Side 2 arrived for a pending Side 1 -> auto-crop & merge into ONE page replacing Side 1
        if (idCardMergeSpec != null && newPages.isNotEmpty()) {
            val targetIdx = when {
                idCardMergeSpec.frontPageIndex in existingPages.indices -> idCardMergeSpec.frontPageIndex
                existingPages.isNotEmpty() -> existingPages.lastIndex
                else -> -1
            }
            val frontBitmap = idCardMergeSpec.frontBitmap?.takeUnless { it.isRecycled }
                ?: existingPages.getOrNull(targetIdx)?.takeUnless { it.isRecycled }

            if (frontBitmap != null) {
                _state.update {
                    if (it.isScanning) {
                        it.copy(
                            progress = 0.98f,
                            statusText = "Auto-cropping & merging 2-in-1 ID...",
                        )
                    } else {
                        it
                    }
                }
                val mergedBitmap = withContext(Dispatchers.Default) {
                    ScanIdCardMerger.autoCropAndMergeIdCards(
                        frontRaw = frontBitmap,
                        backRaw = newPages.first(),
                        pageSize = idCardMergeSpec.pageSize,
                        layout = idCardMergeSpec.layout,
                        dpi = idCardMergeSpec.dpi,
                    )
                }
                val extraPages = if (newPages.size > 1) newPages.drop(1) else emptyList()
                val combined = if (targetIdx >= 0) {
                    existingPages.toMutableList().apply { set(targetIdx, mergedBitmap) } + extraPages
                } else {
                    listOf(mergedBitmap) + extraPages
                }
                return TwoInOneMergeOutcome(
                    combinedPages = combined,
                    deliveredPages = listOf(mergedBitmap) + extraPages,
                    replacedPageIndex = targetIdx.takeIf { it >= 0 },
                    replacedPageId = idCardMergeSpec.frontPageId,
                    isMerged = true,
                    layout = idCardMergeSpec.layout,
                    outputPageSize = idCardMergeSpec.pageSize,
                )
            }
        }

        // Case 2: Single pass (e.g., ADF Duplex) captured 2+ pages while 2-in-1 ID Scanning is enabled
        if (idCardMergeSpec == null && params.twoInOneIdScan && newPages.size >= 2) {
            _state.update {
                if (it.isScanning) {
                    it.copy(
                        progress = 0.98f,
                        statusText = "Auto-cropping & merging 2-in-1 ID...",
                    )
                } else {
                    it
                }
            }
            val mergedPages = withContext(Dispatchers.Default) {
                buildList {
                    var i = 0
                    while (i + 1 < newPages.size) {
                        add(
                            ScanIdCardMerger.autoCropAndMergeIdCards(
                                frontRaw = newPages[i],
                                backRaw = newPages[i + 1],
                                pageSize = params.pageSize,
                                layout = params.idCardLayout,
                                dpi = params.dpi,
                            ),
                        )
                        i += 2
                    }
                    if (i < newPages.size) {
                        add(newPages[i])
                    }
                }
            }
            return TwoInOneMergeOutcome(
                combinedPages = existingPages + mergedPages,
                deliveredPages = mergedPages,
                replacedPageIndex = null,
                replacedPageId = null,
                isMerged = true,
                layout = params.idCardLayout,
                outputPageSize = params.pageSize,
            )
        }

        return TwoInOneMergeOutcome(
            combinedPages = existingPages + newPages,
            deliveredPages = newPages,
            replacedPageIndex = null,
            replacedPageId = null,
            isMerged = false,
            layout = null,
            outputPageSize = params.pageSize,
        )
    }

    private data class TrackedRemoteScan(
        val host: String,
        val port: Int,
        val jobId: String,
        val collectionId: String?,
        val sessionTitle: String,
        val mergeFrontPageIndex: Int? = null,
        val mergeFrontPageId: Long? = null,
        val mergePageSize: String? = null,
        val mergeLayout: String? = null,
        val mergeDpi: Int? = null,
    ) {
        fun toMergeSpec(): IdCardMergeSpec? {
            val idx = mergeFrontPageIndex ?: return null
            val pageSize = runCatching { ScanPageSizeOption.valueOf(mergePageSize.orEmpty()) }
                .getOrDefault(ScanPageSizeOption.A4)
            val layout = runCatching { IdCardMergeLayout.valueOf(mergeLayout.orEmpty()) }
                .getOrDefault(IdCardMergeLayout.VERTICAL)
            return IdCardMergeSpec(
                frontPageIndex = idx,
                frontPageId = mergeFrontPageId ?: -1L,
                frontBitmap = null,
                pageSize = pageSize,
                layout = layout,
                dpi = mergeDpi ?: ScanParameters.DEFAULT_DPI,
            )
        }
    }

    private fun saveTrackedRemoteScan(tracked: TrackedRemoteScan) {
        runCatching {
            val obj = JSONObject().apply {
                put("host", tracked.host)
                put("port", tracked.port)
                put("jobId", tracked.jobId)
                put("collectionId", tracked.collectionId ?: "")
                put("sessionTitle", tracked.sessionTitle)
                if (tracked.mergeFrontPageIndex != null) {
                    put("mergeFrontPageIndex", tracked.mergeFrontPageIndex)
                    put("mergeFrontPageId", tracked.mergeFrontPageId ?: -1L)
                    put("mergePageSize", tracked.mergePageSize ?: ScanPageSizeOption.A4.name)
                    put("mergeLayout", tracked.mergeLayout ?: IdCardMergeLayout.VERTICAL.name)
                    put("mergeDpi", tracked.mergeDpi ?: ScanParameters.DEFAULT_DPI)
                }
            }
            trackingFile.writeText(obj.toString())
        }
    }

    private fun loadTrackedRemoteScan(): TrackedRemoteScan? {
        if (!trackingFile.exists()) return null
        return runCatching {
            val obj = JSONObject(trackingFile.readText())
            val host = obj.optString("host", "")
            val port = obj.optInt("port", 8631)
            val jobId = obj.optString("jobId", "")
            if (host.isBlank() || jobId.isBlank()) return@runCatching null
            val hasMerge = obj.has("mergeFrontPageIndex")
            TrackedRemoteScan(
                host = host,
                port = port,
                jobId = jobId,
                collectionId = obj.optString("collectionId", "").ifBlank { null },
                sessionTitle = obj.optString("sessionTitle", ""),
                mergeFrontPageIndex = if (hasMerge) obj.optInt("mergeFrontPageIndex") else null,
                mergeFrontPageId = if (hasMerge) obj.optLong("mergeFrontPageId", -1L) else null,
                mergePageSize = if (hasMerge) obj.optString("mergePageSize", ScanPageSizeOption.A4.name) else null,
                mergeLayout = if (hasMerge) obj.optString("mergeLayout", IdCardMergeLayout.VERTICAL.name) else null,
                mergeDpi = if (hasMerge) obj.optInt("mergeDpi", ScanParameters.DEFAULT_DPI) else null,
            )
        }.getOrNull()
    }

    private fun clearTrackedRemoteScan() {
        runCatching { if (trackingFile.exists()) trackingFile.delete() }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_000L
        private const val PROGRESS_REPORT_INTERVAL_MS = 6_000L
        private const val RETRY_DELAY_MS = 3_000L
        private const val HOST_STALL_TIMEOUT_MS = 75_000L
        private const val MAX_REMOTE_SCAN_ATTEMPTS = 3
        private const val MAX_POLL_RETRIES = 12
        private const val MAX_DOWNLOAD_ATTEMPTS = 4
    }
}
