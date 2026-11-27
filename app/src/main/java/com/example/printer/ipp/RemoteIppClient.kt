package com.example.printer.ipp

import android.content.Context
import android.net.Uri
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.PaperAvailability
import com.example.model.PrintJobOptions
import com.example.model.PrinterDiagnostics
import com.example.model.SupplyStatus
import com.example.printer.network.DeviceIdentityHelper
import com.example.printer.render.DocumentSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wireless IPP 2.0 & PrintBridge Status client for Case II (when device has no OTG connection
 * and prints/checks printer health wirelessly via a remote PrintBridge server or IPP printer).
 */
object RemoteIppClient {

    private val nextRequestId = AtomicInteger(1)

    data class RemoteIppProbeInfo(
        val printerName: String,
        val makeAndModel: String,
        val printerState: Int,
        val stateMessage: String,
        val isAcceptingJobs: Boolean,
    )

    data class RemotePrintResult(
        val remoteJobId: Int?,
        val bytesUploaded: Long,
        val statusMessage: String,
    )

    fun fetchPrintBridgeStatusJson(host: String, port: Int, timeoutMs: Int = 5_000): JSONObject? {
        return fetchPrintBridgeStatusJsonResult(host, port, timeoutMs).getOrNull()
    }

    fun sendClientPing(host: String, port: Int, timeoutMs: Int = 3_000): Boolean {
        return runCatching {
            val conn = (URL("http://$host:$port/api/client/ping").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Connection", "close")
                DeviceIdentityHelper.attachClientHeaders(this)
            }
            try {
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    /**
     * Checks whether a wireless printer/server host is currently online and reachable.
     * Uses PrintBridge `/api/client/ping` first (also refreshes host heartbeat), and falls
     * back to IPP `Get-Printer-Attributes` for standard non-PrintBridge IPP/Mopria printers.
     */
    fun isHostReachable(printer: DiscoveredNetworkPrinter, timeoutMs: Int = 3_000): Boolean {
        if (sendClientPing(printer.host, printer.port, timeoutMs)) {
            return true
        }
        if (!printer.isPrintBridgeServer) {
            return probePrinter(printer.host, printer.port, printer.ippPath) != null
        }
        return false
    }

    fun sendClientDisconnect(host: String, port: Int, timeoutMs: Int = 2_000) {
        runCatching {
            val conn = (URL("http://$host:$port/api/client/disconnect").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Connection", "close")
                DeviceIdentityHelper.attachClientHeaders(this)
            }
            try {
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun fetchPrintBridgeStatusJsonResult(host: String, port: Int, timeoutMs: Int = 5_000): Result<JSONObject> {
        return runCatching {
            val conn = (URL("http://$host:$port/api/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                useCaches = false
                connectTimeout = timeoutMs
                readTimeout = timeoutMs.coerceAtLeast(8_000)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                DeviceIdentityHelper.attachClientHeaders(this)
            }
            try {
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body)
                } else {
                    val errBody = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                    throw IllegalStateException(
                        errBody?.takeIf { it.isNotBlank() } ?: "Status endpoint returned HTTP $code",
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    fun probePrinter(host: String, port: Int, ippPath: String = "/ipp/print"): RemoteIppProbeInfo? {
        return probePrinterResult(host, port, ippPath).getOrNull()
    }

    private fun probePrinterResult(host: String, port: Int, ippPath: String = "/ipp/print"): Result<RemoteIppProbeInfo> {
        return runCatching {
            val cleanPath = if (ippPath.startsWith("/")) ippPath else "/$ippPath"
            val printerUri = "ipp://$host:$port$cleanPath"
            val reqBytes = IppCodec.RequestBuilder(
                operationId = IppConstants.OP_GET_PRINTER_ATTRIBUTES,
                requestId = nextRequestId.getAndIncrement(),
            )
                .addString(IppConstants.TAG_URI, "printer-uri", printerUri)
                .addString(
                    IppConstants.TAG_NAME_WITHOUT_LANGUAGE,
                    "requesting-user-name",
                    DeviceIdentityHelper.currentDisplayName(),
                )
                .buildHeader()

            val resp = postIppBytes("http://$host:$port$cleanPath", reqBytes, timeoutMs = 6_000)
                ?: throw IllegalStateException("IPP endpoint returned empty response")
            if (!resp.isSuccess) {
                throw IllegalStateException("IPP status 0x${resp.statusCode.toString(16)}")
            }

            RemoteIppProbeInfo(
                printerName = resp.getStringAttr("printer-name") ?: "Wireless Printer",
                makeAndModel = resp.getStringAttr("printer-make-and-model")
                    ?: resp.getStringAttr("printer-info")
                    ?: "IPP Printer",
                printerState = resp.getIntAttr("printer-state") ?: 3,
                stateMessage = resp.getStringAttr("printer-state-message") ?: "Ready",
                isAcceptingJobs = resp.getBoolAttr("printer-is-accepting-jobs") ?: true,
            )
        }
    }

    /**
     * Queries live health & status from a wireless server.
     * Prefers PrintBridge `/api/status` JSON (which includes full USB toner/drum/paper telemetry),
     * falling back seamlessly to standard IPP `Get-Printer-Attributes`.
     */
    suspend fun queryDiagnostics(printer: DiscoveredNetworkPrinter): Result<PrinterDiagnostics> =
        withContext(Dispatchers.IO) {
            runCatching {
                val statusResult = fetchPrintBridgeStatusJsonResult(printer.host, printer.port, timeoutMs = 5_000)
                val json = statusResult.getOrNull()
                if (json != null) {
                    val diagObj = json.optJSONObject("diagnostics")
                    val paperStr = diagObj?.optString("paperAvailability", "UNKNOWN") ?: "UNKNOWN"
                    val tonerStr = diagObj?.optString("tonerStatus", "UNKNOWN") ?: "UNKNOWN"
                    val drumStr = diagObj?.optString("drumStatus", "UNKNOWN") ?: "UNKNOWN"
                    val connected = json.optBoolean("connected", true)
                    val busyState = json.optString("busyState", "AVAILABLE")
                    val cmdLangs = buildList {
                        val arr = diagObj?.optJSONArray("commandLanguages")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val v = arr.optString(i)
                                if (v.isNotBlank()) add(v)
                            }
                        }
                    }

                    return@runCatching PrinterDiagnostics(
                        online = diagObj?.let { if (it.has("online") && !it.isNull("online")) it.optBoolean("online") else connected } ?: connected,
                        hasHardwareError = diagObj?.let { if (it.has("hasHardwareError") && !it.isNull("hasHardwareError")) it.optBoolean("hasHardwareError") else false },
                        paperAvailability = runCatching { PaperAvailability.valueOf(paperStr) }.getOrDefault(
                            if (connected) PaperAvailability.AVAILABLE else PaperAvailability.UNKNOWN,
                        ),
                        loadedPaperSize = diagObj?.optString("loadedPaperSize")?.takeIf { it.isNotBlank() },
                        loadedMediaType = diagObj?.optString("loadedMediaType")?.takeIf { it.isNotBlank() },
                        tonerStatus = runCatching { SupplyStatus.valueOf(tonerStr) }.getOrDefault(SupplyStatus.UNKNOWN),
                        tonerPercent = diagObj?.takeIf { it.has("tonerPercent") && !it.isNull("tonerPercent") }?.optInt("tonerPercent"),
                        drumStatus = runCatching { SupplyStatus.valueOf(drumStr) }.getOrDefault(SupplyStatus.UNKNOWN),
                        drumPercent = diagObj?.takeIf { it.has("drumPercent") && !it.isNull("drumPercent") }?.optInt("drumPercent"),
                        lcdDisplayMessage = diagObj?.optString("lcdDisplayMessage")?.takeIf { it.isNotBlank() }
                            ?: when (busyState) {
                                "PRINTING" -> "Printing Remote Job"
                                "SCANNING" -> "Scanning Document"
                                else -> if (connected) "Wireless Server Ready" else "USB Printer Offline on Host"
                            },
                        pjlStatusCode = diagObj?.takeIf { it.has("pjlStatusCode") && !it.isNull("pjlStatusCode") }?.optInt("pjlStatusCode"),
                        totalPageCount = diagObj?.takeIf { it.has("totalPageCount") && !it.isNull("totalPageCount") }?.optInt("totalPageCount"),
                        economyMode = diagObj?.takeIf { it.has("economyMode") && !it.isNull("economyMode") }?.optBoolean("economyMode"),
                        duplexEnabled = diagObj?.takeIf { it.has("duplexEnabled") && !it.isNull("duplexEnabled") }?.optBoolean("duplexEnabled"),
                        commandLanguages = cmdLangs,
                        firmwareId = diagObj?.optString("firmwareId")?.takeIf { it.isNotBlank() }
                            ?: json.optString("model").takeIf { it.isNotBlank() },
                        pjlSupported = diagObj?.optBoolean("pjlSupported", false) ?: false,
                        portStatusSupported = diagObj?.optBoolean("portStatusSupported", false) ?: false,
                        telemetrySourceLabel = "Wi-Fi Server (${printer.displayAddress})",
                        queriedAtMs = diagObj?.takeIf { it.has("queriedAtMs") && !it.isNull("queriedAtMs") }?.optLong("queriedAtMs")
                            ?: System.currentTimeMillis(),
                    )
                }

                // Fallback: Standard IPP Get-Printer-Attributes
                val ippResult = probePrinterResult(printer.host, printer.port, printer.ippPath)
                val probe = ippResult.getOrNull() ?: run {
                    val cause = statusResult.exceptionOrNull()?.message
                        ?: ippResult.exceptionOrNull()?.message
                        ?: "no response"
                    throw IllegalStateException("Wireless printer at ${printer.displayAddress} failed to respond ($cause)")
                }

                val online = probe.printerState != 5
                PrinterDiagnostics(
                    online = online,
                    hasHardwareError = probe.printerState == 5,
                    paperAvailability = if (online) PaperAvailability.AVAILABLE else PaperAvailability.UNKNOWN,
                    loadedPaperSize = "A4",
                    tonerStatus = if (online) SupplyStatus.OK else SupplyStatus.UNKNOWN,
                    lcdDisplayMessage = probe.stateMessage,
                    firmwareId = probe.makeAndModel,
                    telemetrySourceLabel = "Wireless IPP (${printer.displayAddress})",
                )
            }
        }

    /**
     * Sends a PDF or Image file wirelessly via IPP `Print-Job` with all selected print parameters
     * (N-Up, duplex, copies, paper size, resolution, toner save, page borders) to the remote server.
     */
    suspend fun printDocument(
        context: Context,
        printer: DiscoveredNetworkPrinter,
        source: DocumentSource,
        title: String,
        mimeType: String,
        fileSizeBytes: Long,
        options: PrintJobOptions,
        isCanceled: () -> Boolean,
        onProgress: (progress: Float, bytesSent: Long) -> Unit,
    ): Result<RemotePrintResult> = withContext(Dispatchers.IO) {
        runCatching {
            val reqHeader = buildPrintJobHeader(
                printerUri = printer.ippUri,
                title = title,
                mimeType = mimeType,
                options = options,
            )

            val inputStream: InputStream = when (source) {
                is DocumentSource.LocalFile -> FileInputStream(source.file)
                is DocumentSource.ContentUri -> context.contentResolver.openInputStream(source.uri)
                    ?: throw IllegalStateException("Cannot read selected file")
            }

            inputStream.use { docIn ->
                val conn = (URL(printer.ippHttpUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    useCaches = false
                    doOutput = true
                    doInput = true
                    connectTimeout = 10_000
                    readTimeout = 120_000
                    setRequestProperty("Content-Type", "application/ipp")
                    setRequestProperty("Connection", "close")
                    DeviceIdentityHelper.attachClientHeaders(this)
                    if (fileSizeBytes > 0L) {
                        setFixedLengthStreamingMode(reqHeader.size.toLong() + fileSizeBytes)
                    } else {
                        setChunkedStreamingMode(64 * 1024)
                    }
                }

                var uploadedBytes = 0L
                try {
                    conn.outputStream.use { out ->
                        out.write(reqHeader)
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCanceled()) throw InterruptedException("Print canceled")
                            val read = docIn.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            uploadedBytes += read
                            val frac = if (fileSizeBytes > 0L) {
                                (uploadedBytes.toFloat() / fileSizeBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0.5f
                            }
                            onProgress(0.08f + frac * 0.84f, uploadedBytes)
                        }
                        out.flush()
                    }

                    val httpCode = conn.responseCode
                    val respBytes = if (httpCode in 200..299) {
                        conn.inputStream.readBytes()
                    } else {
                        conn.errorStream?.readBytes() ?: ByteArray(0)
                    }

                    if (httpCode !in 200..299) {
                        throw IllegalStateException("Remote server returned HTTP $httpCode")
                    }

                    val ippResp = IppCodec.decodeResponse(ByteArrayInputStream(respBytes))
                    val statusMsg = ippResp.getStringAttr("status-message") ?: "Accepted by remote server"
                    if (!ippResp.isSuccess) {
                        throw IllegalStateException("Remote printer rejected job: $statusMsg")
                    }

                    onProgress(1f, uploadedBytes)
                    RemotePrintResult(
                        remoteJobId = ippResp.getIntAttr("job-id"),
                        bytesUploaded = uploadedBytes,
                        statusMessage = statusMsg,
                    )
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    /**
     * Sends a small rendered PNG test page over wireless IPP `Print-Job`.
     */
    suspend fun printRawBytes(
        printer: DiscoveredNetworkPrinter,
        payload: ByteArray,
        title: String,
        mimeType: String,
        options: PrintJobOptions,
        onProgress: (Float, Long) -> Unit,
    ): Result<RemotePrintResult> = withContext(Dispatchers.IO) {
        runCatching {
            val reqHeader = buildPrintJobHeader(
                printerUri = printer.ippUri,
                title = title,
                mimeType = mimeType,
                options = options,
            )
            val conn = (URL(printer.ippHttpUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                useCaches = false
                doOutput = true
                doInput = true
                connectTimeout = 10_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/ipp")
                setRequestProperty("Connection", "close")
                DeviceIdentityHelper.attachClientHeaders(this)
                setFixedLengthStreamingMode(reqHeader.size + payload.size)
            }
            try {
                onProgress(0.25f, 0L)
                conn.outputStream.use { out ->
                    out.write(reqHeader)
                    out.write(payload)
                    out.flush()
                }
                onProgress(0.85f, payload.size.toLong())
                val httpCode = conn.responseCode
                val respBytes = if (httpCode in 200..299) {
                    conn.inputStream.readBytes()
                } else {
                    conn.errorStream?.readBytes() ?: ByteArray(0)
                }
                if (httpCode !in 200..299) {
                    throw IllegalStateException("Remote server returned HTTP $httpCode")
                }
                val ippResp = IppCodec.decodeResponse(ByteArrayInputStream(respBytes))
                if (!ippResp.isSuccess) {
                    val msg = ippResp.getStringAttr("status-message") ?: "Error 0x${ippResp.statusCode.toString(16)}"
                    throw IllegalStateException("Remote server rejected print job: $msg")
                }
                onProgress(1f, payload.size.toLong())
                RemotePrintResult(
                    remoteJobId = ippResp.getIntAttr("job-id"),
                    bytesUploaded = payload.size.toLong(),
                    statusMessage = ippResp.getStringAttr("status-message") ?: "Printed remotely",
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun buildPrintJobHeader(
        printerUri: String,
        title: String,
        mimeType: String,
        options: PrintJobOptions,
    ): ByteArray {
        val quality = when {
            options.tonerSave -> 3
            options.resolution.dpi >= 600 -> 5
            else -> 4
        }
        return IppCodec.RequestBuilder(
            operationId = IppConstants.OP_PRINT_JOB,
            requestId = nextRequestId.getAndIncrement(),
        )
            .addString(IppConstants.TAG_URI, "printer-uri", printerUri)
            .addString(
                IppConstants.TAG_NAME_WITHOUT_LANGUAGE,
                "requesting-user-name",
                DeviceIdentityHelper.currentDisplayName(),
            )
            .addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", title.take(120))
            .addString(IppConstants.TAG_MIME_MEDIA_TYPE, "document-format", mimeType)
            .beginGroup(IppConstants.TAG_JOB_ATTRIBUTES)
            .addInt(IppConstants.TAG_INTEGER, "copies", options.copies.coerceIn(1, 99))
            .addString(IppConstants.TAG_KEYWORD, "sides", options.duplexMode.ippKeyword)
            .addInt(IppConstants.TAG_INTEGER, "number-up", options.pagesPerSheet.count)
            .addString(IppConstants.TAG_KEYWORD, "page-border", if (options.drawNUpBorders) "single" else "none")
            .addString(IppConstants.TAG_KEYWORD, "media", options.paperSize.ippKeyword)
            .addResolution("printer-resolution", options.resolution.dpi)
            .addInt(IppConstants.TAG_ENUM, "print-quality", quality)
            .addString(IppConstants.TAG_KEYWORD, "print-scaling", options.scaling.ippKeyword)
            .addInt(IppConstants.TAG_INTEGER, "media-top-margin", kotlin.math.round(options.margins.topMm * 100f).toInt().coerceAtLeast(0))
            .addInt(IppConstants.TAG_INTEGER, "media-bottom-margin", kotlin.math.round(options.margins.bottomMm * 100f).toInt().coerceAtLeast(0))
            .addInt(IppConstants.TAG_INTEGER, "media-left-margin", kotlin.math.round(options.margins.leftMm * 100f).toInt().coerceAtLeast(0))
            .addInt(IppConstants.TAG_INTEGER, "media-right-margin", kotlin.math.round(options.margins.rightMm * 100f).toInt().coerceAtLeast(0))
            .addString(
                IppConstants.TAG_KEYWORD,
                "sheet-collate",
                if (options.collateCopies) "collated" else "uncollated",
            )
            .buildHeader()
    }

    private fun postIppBytes(url: String, body: ByteArray, timeoutMs: Int): IppCodec.IppResponse? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            useCaches = false
            doOutput = true
            doInput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs.coerceAtLeast(8_000)
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Connection", "close")
            DeviceIdentityHelper.attachClientHeaders(this)
            setFixedLengthStreamingMode(body.size)
        }
        return try {
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode in 200..299) {
                val respBytes = conn.inputStream.readBytes()
                IppCodec.decodeResponse(ByteArrayInputStream(respBytes))
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }
}
