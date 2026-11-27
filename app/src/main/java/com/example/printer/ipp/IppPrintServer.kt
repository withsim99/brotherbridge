package com.example.printer.ipp

import com.example.model.DeviceBusyState
import com.example.model.DuplexMode
import com.example.model.LogCategory
import com.example.model.MediaType
import com.example.model.NUpPresentationDirection
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintJobOptions
import com.example.model.PrintJobRecord
import com.example.model.PrintJobStatus
import com.example.model.PrintOrientation
import com.example.model.PrintResolution
import com.example.model.PrintScaling
import com.example.model.PrintServerSettings
import com.example.model.UsbConnectionStatus
import com.example.model.UsbPrinterInfo
import com.example.printer.render.DocumentRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import com.example.scanner.SimpleScanHttpResponse
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class DeviceBusyException(message: String) : IllegalStateException(message)

interface IppServerDelegate {
    fun currentSettings(): PrintServerSettings
    fun currentUsbInfo(): UsbPrinterInfo
    fun currentIpAddress(): String?
    fun currentJobs(): List<PrintJobRecord>
    fun currentDeviceBusyState(): DeviceBusyState = DeviceBusyState.AVAILABLE
    fun isScannerAvailable(): Boolean = false
    suspend fun queryStatusJson(): String? = null
    suspend fun handleSimpleScanRequest(
        method: String,
        path: String,
        body: ByteArray,
        remoteIp: String,
    ): SimpleScanHttpResponse? = null
    fun createPendingIppJob(
        title: String,
        clientName: String,
        options: PrintJobOptions,
    ): Result<PrintJobRecord>
    fun attachDocumentAndEnqueue(
        existingJobId: Int?,
        title: String,
        clientName: String,
        declaredMime: String?,
        file: File,
        options: PrintJobOptions,
    ): Result<PrintJobRecord>
    fun cancelJob(jobId: Int): Boolean
    fun onClientActivity(
        remoteIp: String,
        headers: Map<String, String>,
        activityLabel: String,
        explicitClientName: String? = null,
    ) = Unit
    fun onClientDisconnected(
        remoteIp: String,
        headers: Map<String, String>,
    ) = Unit
    fun onLog(category: LogCategory, message: String, isError: Boolean = false)
    fun onRequestMetricsChanged(activeCount: Int, totalCount: Int)
}

/**
 * Embedded HTTP/1.1 + IPP 1.1/2.0 network server allowing driverless printing from
 * macOS, Windows 10/11, Linux CUPS, iOS, and Android devices on the local Wi-Fi network.
 */
class IppPrintServer(
    private val spoolDir: File,
    private val delegate: IppServerDelegate,
) {
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private var acceptJob: Job? = null

    private val activeConnections = AtomicInteger(0)
    private val totalRequests = AtomicInteger(0)
    private val createdJobOptions = ConcurrentHashMap<Int, Pair<String, PrintJobOptions>>()
    private val serverStartTimeSec = (System.currentTimeMillis() / 1000L).toInt()

    private companion object {
        /** Buffer HTTP bodies up to 256 KB fully into a ByteArray before IPP parsing; larger print jobs stream directly to disk. */
        const val IPP_MEMORY_BUFFER_LIMIT = 256 * 1024
    }

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    fun start(port: Int): Result<Int> {
        stop()
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 64)
            }
            serverSocket = socket
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            serverScope = scope
            acceptJob = scope.launch {
                delegate.onLog(LogCategory.IPP, "IPP HTTP/1.1 listener bound on 0.0.0.0:$port")
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        scope.launch { handleClientSocket(client) }
                    } catch (e: Exception) {
                        if (isActive && !socket.isClosed) {
                            delegate.onLog(LogCategory.IPP, "Socket accept warning: ${e.message}", true)
                        }
                    }
                }
            }
            Result.success(socket.localPort)
        } catch (e: Exception) {
            delegate.onLog(LogCategory.IPP, "Failed to bind IPP port $port: ${e.message}", true)
            Result.failure(e)
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        serverScope?.cancel()
        serverScope = null
        activeConnections.set(0)
        delegate.onRequestMetricsChanged(0, totalRequests.get())
    }

    private suspend fun handleClientSocket(socket: Socket) {
        val active = activeConnections.incrementAndGet()
        delegate.onRequestMetricsChanged(active, totalRequests.get())
        try {
            socket.soTimeout = 120_000
            runCatching {
                socket.tcpNoDelay = true
                socket.keepAlive = true
            }
            val rawIn = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val rawOut = socket.getOutputStream()

            // Support HTTP/1.1 keep-alive (multiple IPP requests per TCP socket)
            var keepAlive = true
            var requestsOnSocket = 0
            while (keepAlive && requestsOnSocket < 20 && !socket.isClosed) {
                val requestLine = readHttpLine(rawIn) ?: break
                if (requestLine.isBlank()) continue
                val parts = requestLine.split(' ')
                if (parts.size < 2) break
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readHttpLine(rawIn) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }

                // Mandatory for CUPS / macOS / Windows IPP clients sending large PDFs
                if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                    rawOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    rawOut.flush()
                }

                val isChunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
                val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
                val connHeader = headers["connection"]?.lowercase()
                keepAlive = connHeader != "close"
                requestsOnSocket++

                val rawRequestPath = if (path.startsWith("http://", ignoreCase = true) ||
                    path.startsWith("https://", ignoreCase = true)
                ) {
                    runCatching { java.net.URI(path).rawPath }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "/"
                } else {
                    path
                }
                val normalizedPath = rawRequestPath.substringBefore('?')
                val remoteIp = socket.inetAddress?.hostAddress ?: "LAN Client"

                if (normalizedPath.equals("/api/client/disconnect", ignoreCase = true)) {
                    delegate.onClientDisconnected(remoteIp, headers)
                    writeHttpResponse(
                        rawOut,
                        200,
                        "OK",
                        "application/json; charset=utf-8",
                        """{"disconnected":true}""".toByteArray(Charsets.UTF_8),
                        keepAlive = false,
                    )
                    keepAlive = false
                    continue
                }

                if (normalizedPath.equals("/api/client/ping", ignoreCase = true)) {
                    delegate.onClientActivity(remoteIp, headers, "Connected")
                    writeHttpResponse(
                        rawOut,
                        200,
                        "OK",
                        "application/json; charset=utf-8",
                        """{"ok":true}""".toByteArray(Charsets.UTF_8),
                        keepAlive = false,
                    )
                    keepAlive = false
                    continue
                }

                if (normalizedPath.equals("/api/scan", ignoreCase = true) ||
                    normalizedPath.startsWith("/api/scan/", ignoreCase = true)
                ) {
                    runCatching {
                        socket.soTimeout = 120_000
                        socket.sendBufferSize = 128 * 1024
                    }
                    val scanActivity = when {
                        normalizedPath.endsWith("/start", ignoreCase = true) -> "Scanning"
                        normalizedPath.contains("/page/", ignoreCase = true) -> "Receiving Scan"
                        normalizedPath.endsWith("/progress", ignoreCase = true) -> "Scanning"
                        else -> "Connected"
                    }
                    delegate.onClientActivity(remoteIp, headers, scanActivity)
                    val bodyStream: InputStream = when {
                        isChunked -> ChunkedInputStream(rawIn)
                        contentLength > 0 -> BoundedInputStream(rawIn, contentLength.coerceAtMost(256L * 1024L))
                        else -> ByteArray(0).inputStream()
                    }
                    val reqBody = bodyStream.readBytes()
                    val scanResp = try {
                        delegate.handleSimpleScanRequest(
                            method = method,
                            path = rawRequestPath,
                            body = reqBody,
                            remoteIp = remoteIp,
                        )
                    } catch (t: Throwable) {
                        delegate.onLog(LogCategory.SCAN, "Scan API error ($method $normalizedPath): ${t.message}", true)
                        SimpleScanHttpResponse.Bytes(
                            statusCode = 500,
                            reason = "Internal Server Error",
                            body = """{"error":"${(t.message ?: "Scan server error").replace("\"", "'")}"}""".toByteArray(Charsets.UTF_8),
                        )
                    }
                    if (scanResp != null) {
                        writeSimpleScanResponse(rawOut, scanResp, headOnly = method == "HEAD")
                    } else {
                        writeHttpResponse(
                            rawOut,
                            404,
                            "Not Found",
                            "application/json; charset=utf-8",
                            """{"error":"Scan API unavailable"}""".toByteArray(Charsets.UTF_8),
                            keepAlive = false,
                        )
                    }
                    keepAlive = false
                    continue
                }

                when (method) {
                    "GET", "HEAD" -> {
                        if (normalizedPath.equals("/api/status", ignoreCase = true)) {
                            delegate.onClientActivity(remoteIp, headers, "Connected")
                            val json = try {
                                delegate.queryStatusJson() ?: buildFallbackStatusJson()
                            } catch (t: Throwable) {
                                delegate.onLog(LogCategory.IPP, "Status query fallback: ${t.message}", true)
                                buildFallbackStatusJson()
                            }
                            val body = if (method == "HEAD") ByteArray(0) else json.toByteArray(Charsets.UTF_8)
                            writeHttpResponse(rawOut, 200, "OK", "application/json; charset=utf-8", body, keepAlive = false)
                        } else if (normalizedPath.equals("/eSCL", ignoreCase = true) ||
                            normalizedPath.startsWith("/eSCL/", ignoreCase = true)
                        ) {
                            val body = if (method == "HEAD") ByteArray(0) else """{"error":"Use HTTP /api/scan"}""".toByteArray(Charsets.UTF_8)
                            writeHttpResponse(rawOut, 404, "Not Found", "application/json; charset=utf-8", body, keepAlive = false)
                        } else {
                            delegate.onClientActivity(remoteIp, headers, "Web Console")
                            val html = buildWebConsoleHtml()
                            val body = if (method == "HEAD") ByteArray(0) else html.toByteArray(Charsets.UTF_8)
                            writeHttpResponse(rawOut, 200, "OK", "text/html; charset=utf-8", body, keepAlive = false)
                        }
                        keepAlive = false
                    }
                    "POST" -> {
                        val total = totalRequests.incrementAndGet()
                        delegate.onRequestMetricsChanged(activeConnections.get(), total)

                        // Fast-fail oversized Content-Length (> 1 GB + 64 KB IPP attribute overhead) before reading payload
                        if (!isChunked && contentLength > IppCodec.MAX_DOCUMENT_BYTES + 64L * 1024L) {
                            delegate.onLog(
                                LogCategory.IPP,
                                "Rejected oversized IPP upload (${contentLength / (1024 * 1024)} MB > 1 GB limit) from $remoteIp",
                                true,
                            )
                            val ippErr = IppCodec.ResponseBuilder(1, 1, IppConstants.STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE, 1)
                                .addString(
                                    IppConstants.TAG_TEXT_WITHOUT_LANGUAGE,
                                    "status-message",
                                    "Document exceeds 1 GB maximum printer limit",
                                )
                                .build()
                            writeHttpResponse(
                                out = rawOut,
                                statusCode = 200,
                                reason = "OK",
                                contentType = "application/ipp",
                                body = ippErr,
                                keepAlive = false,
                            )
                            keepAlive = false
                            continue
                        }

                        val decodedBody = prepareIppRequestBody(
                            rawIn = rawIn,
                            isChunked = isChunked,
                            contentLength = contentLength,
                        )
                        val (ippResponse, closeAfterResponse) = handleIppPost(
                            decodedBody = decodedBody,
                            remoteIp = remoteIp,
                            headers = headers,
                            requestPath = normalizedPath,
                        )
                        if (closeAfterResponse) keepAlive = false
                        writeHttpResponse(
                            out = rawOut,
                            statusCode = 200,
                            reason = "OK",
                            contentType = "application/ipp",
                            body = ippResponse,
                            keepAlive = keepAlive,
                        )
                        // Only drain trailing unread bytes when keeping the connection alive
                        if (keepAlive && !decodedBody.fullyBuffered) {
                            decodedBody.stream.drainQuietly()
                        }
                    }
                    "OPTIONS" -> {
                        writeHttpResponse(
                            out = rawOut,
                            statusCode = 200,
                            reason = "OK",
                            contentType = "text/plain",
                            body = ByteArray(0),
                            keepAlive = keepAlive,
                            extraHeaders = mapOf("Allow" to "GET, HEAD, POST, OPTIONS"),
                        )
                    }
                    else -> {
                        writeHttpResponse(
                            rawOut,
                            405,
                            "Method Not Allowed",
                            "text/plain",
                            "Method not supported".toByteArray(),
                            keepAlive = false,
                        )
                        keepAlive = false
                    }
                }
            }
        } catch (e: Exception) {
            if (e.message?.contains("Socket closed", ignoreCase = true) != true) {
                delegate.onLog(LogCategory.IPP, "Client session ended: ${e.message ?: "EOF"}", false)
            }
        } finally {
            runCatching { socket.close() }
            val remaining = activeConnections.decrementAndGet().coerceAtLeast(0)
            delegate.onRequestMetricsChanged(remaining, totalRequests.get())
        }
    }

    private data class PreparedHttpBody(
        val stream: CountingInputStream,
        val preBufferedSize: Long?,
        val fullyBuffered: Boolean,
    ) {
        val decodedByteCount: Long get() = preBufferedSize ?: stream.bytesRead
    }

    /**
     * Reads either a `Transfer-Encoding: chunked` body or a `Content-Length` body.
     * Standard IPP control/discovery requests (and small print payloads <= 256 KB) are completely
     * decoded into a `ByteArray` before invoking the IPP parser so chunk trailers and exact byte
     * sizes are resolved up front. Larger print payloads stream seamlessly to disk without OOM.
     */
    private fun prepareIppRequestBody(
        rawIn: InputStream,
        isChunked: Boolean,
        contentLength: Long,
    ): PreparedHttpBody {
        if (isChunked) {
            val chunked = ChunkedInputStream(rawIn)
            val buffer = ByteArrayOutputStream(4096)
            val scratch = ByteArray(16 * 1024)
            while (buffer.size() <= IPP_MEMORY_BUFFER_LIMIT) {
                val maxToRead = minOf(scratch.size, IPP_MEMORY_BUFFER_LIMIT - buffer.size() + 1)
                val n = chunked.read(scratch, 0, maxToRead)
                if (n < 0) {
                    val bytes = buffer.toByteArray()
                    return PreparedHttpBody(
                        stream = CountingInputStream(ByteArrayInputStream(bytes)),
                        preBufferedSize = bytes.size.toLong(),
                        fullyBuffered = true,
                    )
                }
                buffer.write(scratch, 0, n)
            }
            val prefix = buffer.toByteArray()
            val combined = SequenceInputStream(ByteArrayInputStream(prefix), chunked)
            return PreparedHttpBody(
                stream = CountingInputStream(combined),
                preBufferedSize = null,
                fullyBuffered = false,
            )
        }

        if (contentLength <= 0L) {
            return PreparedHttpBody(
                stream = CountingInputStream(ByteArrayInputStream(ByteArray(0))),
                preBufferedSize = 0L,
                fullyBuffered = true,
            )
        }

        if (contentLength <= IPP_MEMORY_BUFFER_LIMIT) {
            val expected = contentLength.toInt()
            val bytes = ByteArray(expected)
            var offset = 0
            while (offset < expected) {
                val n = rawIn.read(bytes, offset, expected - offset)
                if (n < 0) break
                offset += n
            }
            val actual = if (offset == expected) bytes else bytes.copyOf(offset)
            return PreparedHttpBody(
                stream = CountingInputStream(ByteArrayInputStream(actual)),
                preBufferedSize = actual.size.toLong(),
                fullyBuffered = true,
            )
        }

        return PreparedHttpBody(
            stream = CountingInputStream(BoundedInputStream(rawIn, contentLength)),
            preBufferedSize = null,
            fullyBuffered = false,
        )
    }

    private data class EffectiveEndpoint(
        val scheme: String,
        val authority: String,
    ) {
        val printerUri: String get() = "$scheme://$authority/ipp/print"
        val webBaseUrl: String get() = "http://$authority/"
        fun jobUri(jobId: Int): String = "$scheme://$authority/jobs/$jobId"
    }

    /**
     * Dynamically matches the incoming HTTP `Host` header (falling back to `<local-wifi-ip>:<port>`)
     * and preserves the scheme (`ipp://` vs `http://`) from the incoming IPP `printer-uri` attribute.
     */
    private fun resolveEffectiveEndpoint(req: IppRequest, headers: Map<String, String>): EffectiveEndpoint {
        val settings = delegate.currentSettings()
        val fallbackIp = delegate.currentIpAddress()?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1"
        val fallbackAuthority = "$fallbackIp:${settings.ippPort}"
        val hostHeader = headers["host"]?.trim()?.takeIf { it.isNotEmpty() }
        val reqUri = req.getStringAttr("printer-uri")?.trim().orEmpty()
        val scheme = when {
            reqUri.startsWith("ipp://", ignoreCase = true) ||
                reqUri.startsWith("ipps://", ignoreCase = true) -> "ipp"
            else -> "http"
        }
        return EffectiveEndpoint(scheme = scheme, authority = hostHeader ?: fallbackAuthority)
    }

    private fun extractJobId(req: IppRequest): Int? {
        req.getIntAttr("job-id")?.let { return it }
        val jobUri = req.getStringAttr("job-uri") ?: return null
        return jobUri.trimEnd('/').substringAfterLast('/').toIntOrNull()
    }

    private fun extractResponseStatusCode(ippResponse: ByteArray): Int {
        if (ippResponse.size < 4) return -1
        return ((ippResponse[2].toInt() and 0xFF) shl 8) or (ippResponse[3].toInt() and 0xFF)
    }

    private fun logIppDiagnostic(
        requestPath: String,
        headers: Map<String, String>,
        decodedBodySize: Long,
        reqVersion: String?,
        respVersion: String?,
        operationId: Int?,
        requestId: Int?,
        documentFormat: String?,
        requestedAttrs: List<String>?,
        statusCode: Int,
        detail: String? = null,
    ) {
        val hostVal = headers["host"] ?: "<none>"
        val uaVal = headers["user-agent"] ?: "<none>"
        val teVal = headers["transfer-encoding"] ?: "none"
        val clVal = headers["content-length"] ?: "none"
        val opStr = if (operationId != null) {
            String.format("0x%04X (%s)", operationId, IppConstants.operationName(operationId))
        } else {
            "unknown"
        }
        val reqIdStr = requestId?.toString() ?: "n/a"
        val fmtStr = documentFormat?.takeIf { it.isNotBlank() } ?: "none"
        val attrsStr = requestedAttrs?.takeIf { it.isNotEmpty() }?.joinToString(",") ?: "none"
        val statusStr = String.format("0x%04X", statusCode and 0xFFFF)
        val extra = detail?.let { " | $it" } ?: ""
        delegate.onLog(
            LogCategory.IPP,
            "IPP $requestPath | Host=$hostVal | UA=$uaVal | reqVer=${reqVersion ?: "n/a"} | respVer=${respVersion ?: "n/a"} | TE=$teVal | CL=$clVal | body=${decodedBodySize}B | op=$opStr | reqId=$reqIdStr | reqAttrs=$attrsStr | format=$fmtStr | status=$statusStr$extra",
            isError = statusCode !in 0x0000..0x00FF,
        )
    }

    private fun handleIppPost(
        decodedBody: PreparedHttpBody,
        remoteIp: String,
        headers: Map<String, String>,
        requestPath: String,
    ): Pair<ByteArray, Boolean> {
        val req = try {
            IppCodec.decodeRequest(decodedBody.stream, spoolDir)
        } catch (e: DocumentTooLargeException) {
            val resp = IppCodec.ResponseBuilder(1, 1, IppConstants.STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE, 1)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", e.message ?: "Document exceeds 1 GB limit")
                .build()
            logIppDiagnostic(
                requestPath = requestPath,
                headers = headers,
                decodedBodySize = decodedBody.decodedByteCount,
                reqVersion = null,
                respVersion = "1.1",
                operationId = null,
                requestId = null,
                documentFormat = null,
                requestedAttrs = null,
                statusCode = IppConstants.STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE,
                detail = "Oversized document from $remoteIp: ${e.message}",
            )
            return resp to true
        } catch (e: InsufficientSpoolSpaceException) {
            val resp = IppCodec.ResponseBuilder(1, 1, IppConstants.STATUS_SERVER_BUSY, 1)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", e.message ?: "Insufficient storage space")
                .build()
            logIppDiagnostic(
                requestPath = requestPath,
                headers = headers,
                decodedBodySize = decodedBody.decodedByteCount,
                reqVersion = null,
                respVersion = "1.1",
                operationId = null,
                requestId = null,
                documentFormat = null,
                requestedAttrs = null,
                statusCode = IppConstants.STATUS_SERVER_BUSY,
                detail = "Spool storage full from $remoteIp: ${e.message}",
            )
            return resp to true
        } catch (e: Exception) {
            val resp = IppCodec.ResponseBuilder(1, 1, IppConstants.STATUS_CLIENT_BAD_REQUEST, 1)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", e.message ?: "Bad request")
                .build()
            logIppDiagnostic(
                requestPath = requestPath,
                headers = headers,
                decodedBodySize = decodedBody.decodedByteCount,
                reqVersion = null,
                respVersion = "1.1",
                operationId = null,
                requestId = null,
                documentFormat = null,
                requestedAttrs = null,
                statusCode = IppConstants.STATUS_CLIENT_BAD_REQUEST,
                detail = "Malformed IPP frame from $remoteIp: ${e.message}",
            )
            return resp to true
        }

        val opName = IppConstants.operationName(req.operationId)
        val reqDocFormat = req.getStringAttr("document-format")
        val reqVersionStr = "${req.majorVersion}.${req.minorVersion}"
        val reqAttrsList = req.getStringListAttr("requested-attributes")

        // Echo the client's IPP version (2.0 -> 2.0, 1.1 -> 1.1, 1.0 -> 1.0); reject unsupported major versions.
        val isSupportedVersion = req.majorVersion in 1..2
        if (!isSupportedVersion) {
            req.documentFile?.delete()
            val errResp = IppCodec.ResponseBuilder(
                1,
                1,
                IppConstants.STATUS_SERVER_VERSION_NOT_SUPPORTED,
                req.requestId,
                naturalLanguage = "en",
            )
                .addString(
                    IppConstants.TAG_TEXT_WITHOUT_LANGUAGE,
                    "status-message",
                    "IPP version $reqVersionStr not supported",
                )
                .build()
            logIppDiagnostic(
                requestPath = requestPath,
                headers = headers,
                decodedBodySize = decodedBody.decodedByteCount,
                reqVersion = reqVersionStr,
                respVersion = "1.1",
                operationId = req.operationId,
                requestId = req.requestId,
                documentFormat = reqDocFormat,
                requestedAttrs = reqAttrsList,
                statusCode = IppConstants.STATUS_SERVER_VERSION_NOT_SUPPORTED,
                detail = "Unsupported client IPP version $reqVersionStr",
            )
            return errResp to false
        }

        val respMajor = req.majorVersion
        val respMinor = req.minorVersion
        val respVersionStr = "$respMajor.$respMinor"
        val respLang = "en"

        val endpoint = resolveEffectiveEndpoint(req, headers)
        val reqUser = req.getStringAttr("requesting-user-name")?.takeIf { it.isNotBlank() }
        val activityLabel = when (req.operationId) {
            IppConstants.OP_PRINT_JOB, IppConstants.OP_CREATE_JOB, IppConstants.OP_SEND_DOCUMENT -> "Printing"
            else -> "IPP Client"
        }
        delegate.onClientActivity(remoteIp, headers, activityLabel, explicitClientName = reqUser)

        val response = when (req.operationId) {
            IppConstants.OP_GET_PRINTER_ATTRIBUTES -> buildGetPrinterAttributesResponse(respMajor, respMinor, respLang, req, endpoint)
            IppConstants.OP_VALIDATE_JOB -> handleValidateJob(respMajor, respMinor, respLang, req)
            IppConstants.OP_CREATE_JOB -> handleCreateJob(respMajor, respMinor, respLang, req, remoteIp, endpoint)
            IppConstants.OP_SEND_DOCUMENT -> handleSendDocument(respMajor, respMinor, respLang, req, remoteIp, endpoint)
            IppConstants.OP_PRINT_JOB -> handlePrintJob(respMajor, respMinor, respLang, req, remoteIp, endpoint)
            IppConstants.OP_GET_JOB_ATTRIBUTES -> handleGetJobAttributes(respMajor, respMinor, respLang, req, endpoint)
            IppConstants.OP_GET_JOBS -> handleGetJobs(respMajor, respMinor, respLang, req, endpoint)
            IppConstants.OP_CANCEL_JOB -> handleCancelJob(respMajor, respMinor, respLang, req)
            else -> {
                req.documentFile?.delete()
                IppCodec.ResponseBuilder(
                    respMajor,
                    respMinor,
                    IppConstants.STATUS_SERVER_OPERATION_NOT_SUPPORTED,
                    req.requestId,
                    naturalLanguage = respLang,
                )
                    .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Operation $opName not supported")
                    .build()
            }
        }

        val returnedStatus = extractResponseStatusCode(response)
        logIppDiagnostic(
            requestPath = requestPath,
            headers = headers,
            decodedBodySize = decodedBody.decodedByteCount,
            reqVersion = reqVersionStr,
            respVersion = respVersionStr,
            operationId = req.operationId,
            requestId = req.requestId,
            documentFormat = reqDocFormat,
            requestedAttrs = reqAttrsList,
            statusCode = returnedStatus,
            detail = if (req.documentSizeBytes > 0) {
                "client=${reqUser ?: remoteIp}, doc=${formatSizeForLog(req.documentSizeBytes)}"
            } else {
                "client=${reqUser ?: remoteIp}"
            },
        )
        return response to false
    }

    private fun formatSizeForLog(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "${(bytes / 1024L).coerceAtLeast(1)} KB"
    }

    private fun buildGetPrinterAttributesResponse(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val settings = delegate.currentSettings()
        val usb = delegate.currentUsbInfo()
        val busyState = delegate.currentDeviceBusyState()
        val jobs = delegate.currentJobs()
        val queuedCount = jobs.count {
            it.status == PrintJobStatus.PENDING ||
                it.status == PrintJobStatus.RENDERING ||
                it.status == PrintJobStatus.TRANSMITTING
        }

        val isBusyPrinting = busyState == DeviceBusyState.PRINTING ||
            usb.status == UsbConnectionStatus.PRINTING ||
            queuedCount > 0
        val isBusyScanning = busyState == DeviceBusyState.SCANNING

        // IPP printer-state (RFC 8011): 3 = idle (available), 4 = processing (busy), 5 = stopped (offline)
        val printerState = when {
            !usb.isConnected -> 5
            isBusyPrinting || isBusyScanning -> 4
            else -> 3
        }
        val stateReason = when {
            usb.status == UsbConnectionStatus.PERMISSION_REQUIRED -> "connecting-to-device"
            !usb.isConnected -> "offline-report"
            isBusyScanning -> "other-warning"
            else -> "none"
        }
        val stateMessage = when {
            !usb.isConnected -> "Offline: USB printer disconnected"
            isBusyScanning -> "Busy: Scanner is in use"
            isBusyPrinting -> "Busy: Printing another job"
            else -> "Available"
        }

        val uptimeSec = ((System.currentTimeMillis() / 1000L).toInt() - serverStartTimeSec).coerceAtLeast(1)
        val printerDisplayName = settings.printerName.trim().ifBlank { "BrotherBridge" }
        val makeAndModel = if (usb.isConnected) {
            usb.displayTitle.trim().ifBlank { "Brother DCP-L2520D" }.let { model ->
                if (model.contains("(IPP)", ignoreCase = true)) model else "$model (IPP)"
            }
        } else {
            "Brother DCP-L2520D (IPP)"
        }
        val safeModel = makeAndModel.replace(';', ' ').replace(':', ' ').trim().ifBlank { "Brother DCP-L2520D (IPP)" }
        val safeMfg = when {
            safeModel.contains("brother", ignoreCase = true) ||
                safeModel.startsWith("DCP", ignoreCase = true) ||
                safeModel.startsWith("HL", ignoreCase = true) ||
                safeModel.startsWith("MFC", ignoreCase = true) -> "Brother"
            else -> safeModel.substringBefore(' ').ifBlank { "Brother" }
        }
        val cleanDeviceModel = (if (usb.isConnected) usb.displayTitle.trim() else "")
            .replace("(IPP)", "", ignoreCase = true)
            .replace("series", "", ignoreCase = true)
            .replace(';', ' ')
            .replace(':', ' ')
            .trim()
            .let { cleaned ->
                if (cleaned.startsWith("$safeMfg ", ignoreCase = true)) {
                    cleaned.substring(safeMfg.length).trim()
                } else {
                    cleaned
                }
            }
            .ifBlank { "DCP-L2520D" }
        val ieeeDeviceId = "MFG:$safeMfg;MDL:$cleanDeviceModel;CMD:PWG,PDF;CLS:PRINTER;"
        val effectiveUuid = settings.serverUuid.trim().ifBlank {
            java.util.UUID.nameUUIDFromBytes("printbridge:$printerDisplayName".toByteArray(Charsets.UTF_8)).toString()
        }

        // RFC 8011 section 4.2.5: respect requested-attributes and guard IPP 1.0 clients (Windows inetpp.dll)
        // from RFC 3382 collection tags (0x34/0x4A/0x37) that IPP 1.0 parsers reject.
        val requestedAttrs = req.getStringListAttr("requested-attributes")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        val isIpp10 = req.majorVersion == 1 && req.minorVersion == 0
        val wantsAll = requestedAttrs.isEmpty() || "all" in requestedAttrs
        val wantsPrinterDesc = wantsAll || "printer-description" in requestedAttrs
        val wantsJobTemplate = wantsAll || "job-template" in requestedAttrs

        fun includeDesc(attrName: String): Boolean = wantsPrinterDesc || attrName in requestedAttrs
        fun includeJob(attrName: String): Boolean = wantsJobTemplate || attrName in requestedAttrs
        fun includeCollection(attrName: String): Boolean =
            if (isIpp10) attrName in requestedAttrs else (wantsJobTemplate || attrName in requestedAttrs)

        val builder = IppCodec.ResponseBuilder(
            majorVersion = major,
            minorVersion = minor,
            statusCode = IppConstants.STATUS_SUCCESSFUL_OK,
            requestId = req.requestId,
            naturalLanguage = reqLang,
        )
        builder.beginGroup(IppConstants.TAG_PRINTER_ATTRIBUTES)

        if (includeDesc("printer-uri-supported")) {
            builder.addString(IppConstants.TAG_URI, "printer-uri-supported", endpoint.printerUri)
        }
        if (includeDesc("uri-security-supported")) {
            builder.addString(IppConstants.TAG_KEYWORD, "uri-security-supported", "none")
        }
        if (includeDesc("uri-authentication-supported")) {
            builder.addString(IppConstants.TAG_KEYWORD, "uri-authentication-supported", "none")
        }
        if (includeDesc("printer-name")) {
            builder.addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "printer-name", printerDisplayName)
        }
        if (includeDesc("printer-dns-sd-name")) {
            builder.addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "printer-dns-sd-name", printerDisplayName)
        }
        if (includeDesc("printer-info")) {
            builder.addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "printer-info", "$printerDisplayName Wireless Printer")
        }
        if (includeDesc("printer-location")) {
            builder.addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "printer-location", "Local Wi-Fi USB Bridge")
        }
        if (includeDesc("printer-make-and-model")) {
            builder.addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "printer-make-and-model", makeAndModel)
        }
        if (includeDesc("printer-device-id")) {
            builder.addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "printer-device-id", ieeeDeviceId)
        }
        if (includeDesc("printer-more-info")) {
            builder.addString(IppConstants.TAG_URI, "printer-more-info", endpoint.webBaseUrl)
        }
        if (includeDesc("printer-uuid")) {
            builder.addString(IppConstants.TAG_URI, "printer-uuid", "urn:uuid:$effectiveUuid")
        }
        if (includeDesc("printer-state")) {
            builder.addInt(IppConstants.TAG_ENUM, "printer-state", printerState)
        }
        if (includeDesc("printer-state-reasons")) {
            builder.addString(IppConstants.TAG_KEYWORD, "printer-state-reasons", stateReason)
        }
        if (includeDesc("printer-state-message")) {
            builder.addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "printer-state-message", stateMessage)
        }
        if (includeDesc("printer-state-change-time")) {
            builder.addInt(IppConstants.TAG_INTEGER, "printer-state-change-time", 1)
        }
        if (includeDesc("printer-config-change-time")) {
            builder.addInt(IppConstants.TAG_INTEGER, "printer-config-change-time", 1)
        }
        if (includeDesc("ipp-versions-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "ipp-versions-supported", listOf("1.1", "2.0"))
        }
        if (includeDesc("ipp-features-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "ipp-features-supported", listOf("ipp-everywhere"))
        }
        if (includeDesc("operations-supported")) {
            builder.addIntList(
                IppConstants.TAG_ENUM,
                "operations-supported",
                listOf(
                    IppConstants.OP_PRINT_JOB,
                    IppConstants.OP_VALIDATE_JOB,
                    IppConstants.OP_CREATE_JOB,
                    IppConstants.OP_SEND_DOCUMENT,
                    IppConstants.OP_CANCEL_JOB,
                    IppConstants.OP_GET_JOB_ATTRIBUTES,
                    IppConstants.OP_GET_JOBS,
                    IppConstants.OP_GET_PRINTER_ATTRIBUTES,
                ),
            )
        }
        if (includeDesc("charset-configured")) {
            builder.addString(IppConstants.TAG_CHARSET, "charset-configured", "utf-8")
        }
        if (includeDesc("charset-supported")) {
            builder.addStringList(IppConstants.TAG_CHARSET, "charset-supported", listOf("utf-8"))
        }
        if (includeDesc("natural-language-configured")) {
            builder.addString(IppConstants.TAG_NATURAL_LANGUAGE, "natural-language-configured", "en")
        }
        if (includeDesc("generated-natural-language-supported")) {
            builder.addStringList(IppConstants.TAG_NATURAL_LANGUAGE, "generated-natural-language-supported", listOf("en"))
        }
        if (includeDesc("document-format-default")) {
            builder.addString(IppConstants.TAG_MIME_MEDIA_TYPE, "document-format-default", "application/octet-stream")
        }
        if (includeDesc("document-format-supported")) {
            builder.addStringList(
                IppConstants.TAG_MIME_MEDIA_TYPE,
                "document-format-supported",
                DocumentRenderer.SUPPORTED_MIME_TYPES + "application/octet-stream",
            )
        }
        if (includeDesc("pwg-raster-document-resolution-supported")) {
            builder.addResolutionList("pwg-raster-document-resolution-supported", listOf(300, 600))
        }
        if (includeDesc("pwg-raster-document-type-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "pwg-raster-document-type-supported", listOf("black_1", "sgray_8"))
        }
        if (includeDesc("pwg-raster-document-sheet-back")) {
            builder.addString(IppConstants.TAG_KEYWORD, "pwg-raster-document-sheet-back", "normal")
        }
        if (includeDesc("pdf-versions-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "pdf-versions-supported",
                listOf(
                    "adobe-1.3",
                    "adobe-1.4",
                    "adobe-1.5",
                    "adobe-1.6",
                    "adobe-1.7",
                    "iso-19005-1_2005",
                    "iso-32000-1_2008",
                    "pwg-5102.3",
                ),
            )
        }
        if (includeDesc("pdf-k-octets-supported")) {
            builder.addRangeOfInt("pdf-k-octets-supported", 1, IppCodec.MAX_DOCUMENT_K_OCTETS)
        }
        if (includeDesc("printer-is-accepting-jobs")) {
            // Spooler queue remains open for printer setup & status even if USB printer wakes up moments later
            builder.addBoolean("printer-is-accepting-jobs", true)
        }
        if (includeDesc("queued-job-count")) {
            builder.addInt(IppConstants.TAG_INTEGER, "queued-job-count", queuedCount)
        }
        if (includeDesc("pdl-override-supported")) {
            builder.addString(IppConstants.TAG_KEYWORD, "pdl-override-supported", "attempted")
        }
        if (includeDesc("printer-up-time")) {
            builder.addInt(IppConstants.TAG_INTEGER, "printer-up-time", uptimeSec)
        }
        if (includeDesc("compression-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "compression-supported", listOf("none"))
        }
        if (includeDesc("color-supported")) {
            builder.addBoolean("color-supported", false)
        }
        if (includeDesc("pages-per-minute")) {
            builder.addInt(IppConstants.TAG_INTEGER, "pages-per-minute", 26)
        }
        if (includeDesc("which-jobs-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "which-jobs-supported", listOf("completed", "not-completed", "all"))
        }
        if (includeDesc("job-ids-supported")) {
            builder.addBoolean("job-ids-supported", true)
        }
        if (includeDesc("printer-settable-attributes-supported")) {
            builder.addString(IppConstants.TAG_KEYWORD, "printer-settable-attributes-supported", "none")
        }
        if (includeDesc("multiple-document-jobs-supported")) {
            builder.addBoolean("multiple-document-jobs-supported", false)
        }
        if (includeDesc("job-k-octets-supported")) {
            builder.addRangeOfInt("job-k-octets-supported", 1, IppCodec.MAX_DOCUMENT_K_OCTETS)
        }
        if (includeDesc("job-impressions-supported")) {
            builder.addRangeOfInt("job-impressions-supported", 1, IppCodec.MAX_JOB_IMPRESSIONS)
        }

        // Job Template & Default/Supported attributes
        if (includeJob("finishings-default")) {
            builder.addInt(IppConstants.TAG_ENUM, "finishings-default", 3)
        }
        if (includeJob("finishings-supported")) {
            builder.addIntList(IppConstants.TAG_ENUM, "finishings-supported", listOf(3))
        }
        if (includeJob("multiple-document-handling-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "multiple-document-handling-default", "separate-documents-collated-copies")
        }
        if (includeJob("multiple-document-handling-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "multiple-document-handling-supported",
                listOf("separate-documents-collated-copies", "separate-documents-uncollated-copies"),
            )
        }
        if (includeJob("output-bin-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "output-bin-default", "face-down")
        }
        if (includeJob("output-bin-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "output-bin-supported", listOf("face-down"))
        }
        if (includeJob("sides-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "sides-default", DuplexMode.SIMPLEX.ippKeyword)
        }
        if (includeJob("sides-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "sides-supported",
                listOf(DuplexMode.SIMPLEX.ippKeyword),
            )
        }
        if (includeJob("number-up-default")) {
            builder.addInt(IppConstants.TAG_INTEGER, "number-up-default", 1)
        }
        if (includeJob("number-up-supported")) {
            builder.addIntList(IppConstants.TAG_INTEGER, "number-up-supported", listOf(1, 2, 4, 8, 16))
        }
        if (includeJob("presentation-direction-number-up-default")) {
            builder.addString(
                IppConstants.TAG_KEYWORD,
                "presentation-direction-number-up-default",
                NUpPresentationDirection.TO_RIGHT_TO_BOTTOM.ippKeyword,
            )
        }
        if (includeJob("presentation-direction-number-up-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "presentation-direction-number-up-supported",
                NUpPresentationDirection.entries.map { it.ippKeyword },
            )
        }
        if (includeJob("page-border-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "page-border-default", "none")
        }
        if (includeJob("page-border-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "page-border-supported",
                listOf("none", "single", "single-thick", "double", "double-thick"),
            )
        }
        if (includeJob("media-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "media-default", PaperSize.A4.ippKeyword)
        }
        if (includeJob("media-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "media-supported",
                PaperSize.entries.map { it.ippKeyword },
            )
        }
        if (includeJob("media-ready")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "media-ready",
                listOf(PaperSize.A4.ippKeyword, PaperSize.LETTER.ippKeyword),
            )
        }
        if (includeJob("media-type-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "media-type-default", "stationery")
        }
        if (includeJob("media-type-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "media-type-supported", listOf("stationery"))
        }
        if (includeJob("media-source-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "media-source-default", "auto")
        }
        if (includeJob("media-source-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "media-source-supported", listOf("auto", "main"))
        }
        if (includeJob("media-col-supported") && (!isIpp10 || "media-col-supported" in requestedAttrs)) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "media-col-supported",
                listOf(
                    "media-size",
                    "media-top-margin",
                    "media-bottom-margin",
                    "media-left-margin",
                    "media-right-margin",
                    "media-source",
                    "media-type",
                ),
            )
        }
        if (includeJob("media-top-margin-supported")) {
            builder.addIntList(IppConstants.TAG_INTEGER, "media-top-margin-supported", PageMargins.SUPPORTED_MARGINS_HUNDREDTHS_MM)
        }
        if (includeJob("media-bottom-margin-supported")) {
            builder.addIntList(IppConstants.TAG_INTEGER, "media-bottom-margin-supported", PageMargins.SUPPORTED_MARGINS_HUNDREDTHS_MM)
        }
        if (includeJob("media-left-margin-supported")) {
            builder.addIntList(IppConstants.TAG_INTEGER, "media-left-margin-supported", PageMargins.SUPPORTED_MARGINS_HUNDREDTHS_MM)
        }
        if (includeJob("media-right-margin-supported")) {
            builder.addIntList(IppConstants.TAG_INTEGER, "media-right-margin-supported", PageMargins.SUPPORTED_MARGINS_HUNDREDTHS_MM)
        }
        if (includeCollection("media-size-supported")) {
            PaperSize.entries.forEachIndexed { idx, paper ->
                builder.beginCollection(if (idx == 0) "media-size-supported" else "")
                    .addMemberInt(IppConstants.TAG_INTEGER, "x-dimension", paper.widthHundredthsMm)
                    .addMemberInt(IppConstants.TAG_INTEGER, "y-dimension", paper.heightHundredthsMm)
                    .endCollection()
            }
        }
        if (includeCollection("media-col-default")) {
            addMediaCol(builder, "media-col-default", PaperSize.A4, PageMargins.DEFAULT_MARGIN_HUNDREDTHS_MM)
        }
        if (includeCollection("media-col-ready")) {
            val readyEntries = listOf(
                PaperSize.A4 to PageMargins.DEFAULT_MARGIN_HUNDREDTHS_MM,
                PaperSize.A4 to 0,
                PaperSize.LETTER to PageMargins.DEFAULT_MARGIN_HUNDREDTHS_MM,
                PaperSize.LETTER to 0,
            )
            readyEntries.forEachIndexed { idx, (paper, margin) ->
                addMediaCol(builder, if (idx == 0) "media-col-ready" else "", paper, margin)
            }
        }
        if (includeCollection("media-col-database")) {
            var dbIndex = 0
            for (paper in PaperSize.entries) {
                addMediaCol(builder, if (dbIndex++ == 0) "media-col-database" else "", paper, PageMargins.DEFAULT_MARGIN_HUNDREDTHS_MM)
                addMediaCol(builder, "", paper, 0)
            }
        }
        if (includeJob("print-scaling-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "print-scaling-default", PrintScaling.AUTO.ippKeyword)
        }
        if (includeJob("print-scaling-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "print-scaling-supported",
                PrintScaling.entries.map { it.ippKeyword },
            )
        }
        if (includeJob("orientation-requested-default")) {
            builder.addInt(IppConstants.TAG_ENUM, "orientation-requested-default", PrintOrientation.PORTRAIT.ippEnum)
        }
        if (includeJob("orientation-requested-supported")) {
            builder.addIntList(IppConstants.TAG_ENUM, "orientation-requested-supported", listOf(3, 4))
        }
        if (includeJob("page-ranges-supported")) {
            builder.addBoolean("page-ranges-supported", true)
        }
        if (includeJob("copies-default")) {
            builder.addInt(IppConstants.TAG_INTEGER, "copies-default", 1)
        }
        if (includeJob("copies-supported")) {
            builder.addRangeOfInt("copies-supported", 1, 99)
        }
        if (includeJob("sheet-collate-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "sheet-collate-default", "collated")
        }
        if (includeJob("sheet-collate-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "sheet-collate-supported", listOf("collated", "uncollated"))
        }
        if (includeJob("page-delivery-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "page-delivery-default", "same-order")
        }
        if (includeJob("page-delivery-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "page-delivery-supported", listOf("same-order", "reverse-order"))
        }
        if (includeJob("print-content-optimize-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "print-content-optimize-default", "auto")
        }
        if (includeJob("print-content-optimize-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "print-content-optimize-supported",
                listOf("auto", "photo", "graphics", "text", "text-and-graphics"),
            )
        }
        if (includeJob("printer-resolution-default")) {
            builder.addResolution("printer-resolution-default", 600, 600)
        }
        if (includeJob("printer-resolution-supported")) {
            builder.addResolutionList("printer-resolution-supported", listOf(300, 600))
        }
        if (includeJob("print-quality-default")) {
            builder.addInt(IppConstants.TAG_ENUM, "print-quality-default", 4)
        }
        if (includeJob("print-quality-supported")) {
            builder.addIntList(IppConstants.TAG_ENUM, "print-quality-supported", listOf(3, 4, 5))
        }
        if (includeJob("print-color-mode-supported")) {
            builder.addStringList(IppConstants.TAG_KEYWORD, "print-color-mode-supported", listOf("monochrome", "auto-monochrome"))
        }
        if (includeJob("print-color-mode-default")) {
            builder.addString(IppConstants.TAG_KEYWORD, "print-color-mode-default", "monochrome")
        }
        if (includeDesc("job-creation-attributes-supported")) {
            builder.addStringList(
                IppConstants.TAG_KEYWORD,
                "job-creation-attributes-supported",
                listOf(
                    "copies",
                    "finishings",
                    "media",
                    "media-col",
                    "media-source",
                    "media-type",
                    "multiple-document-handling",
                    "output-bin",
                    "sides",
                    "number-up",
                    "presentation-direction-number-up",
                    "page-border",
                    "orientation-requested",
                    "page-ranges",
                    "print-scaling",
                    "printer-resolution",
                    "print-quality",
                    "print-color-mode",
                    "print-content-optimize",
                    "sheet-collate",
                    "page-delivery",
                ),
            )
        }

        return builder.build()
    }

    private fun addMediaCol(
        builder: IppCodec.ResponseBuilder,
        attrName: String,
        paper: PaperSize,
        marginHundredthsMm: Int,
    ) {
        builder.beginCollection(attrName)
            .beginMemberCollection("media-size")
            .addMemberInt(IppConstants.TAG_INTEGER, "x-dimension", paper.widthHundredthsMm)
            .addMemberInt(IppConstants.TAG_INTEGER, "y-dimension", paper.heightHundredthsMm)
            .endCollection()
            .addMemberInt(IppConstants.TAG_INTEGER, "media-top-margin", marginHundredthsMm)
            .addMemberInt(IppConstants.TAG_INTEGER, "media-bottom-margin", marginHundredthsMm)
            .addMemberInt(IppConstants.TAG_INTEGER, "media-left-margin", marginHundredthsMm)
            .addMemberInt(IppConstants.TAG_INTEGER, "media-right-margin", marginHundredthsMm)
            .addMemberString(IppConstants.TAG_KEYWORD, "media-source", "auto")
            .addMemberString(IppConstants.TAG_KEYWORD, "media-type", MediaType.REGULAR.ippKeyword)
            .endCollection()
    }

    private fun handleValidateJob(major: Int, minor: Int, reqLang: String, req: IppRequest): ByteArray {
        if (!delegate.currentUsbInfo().isConnected) {
            req.documentFile?.delete()
            return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_SERVER_BUSY, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "USB printer is offline on host")
                .build()
        }

        val requestedKOctets = req.getIntAttr("job-k-octets")
        if (requestedKOctets != null && requestedKOctets > IppCodec.MAX_DOCUMENT_K_OCTETS) {
            req.documentFile?.delete()
            delegate.onLog(LogCategory.IPP, "Rejected oversized job-k-octets=$requestedKOctets in Validate-Job", true)
            return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Document exceeds 1 GB (1,048,576 KB) limit")
                .build()
        }

        val format = req.getStringAttr("document-format")
        val supported = format.isNullOrBlank() ||
            format.equals("application/octet-stream", ignoreCase = true) ||
            DocumentRenderer.detectDocumentKind(format, null) != null

        req.documentFile?.delete()

        return if (supported) {
            IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_SUCCESSFUL_OK, req.requestId, reqLang).build()
        } else {
            delegate.onLog(LogCategory.IPP, "Rejected unsupported format in Validate-Job: $format", true)
            IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_DOCUMENT_FORMAT_NOT_SUPPORTED, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Only PWG Raster, PDF, and image files are supported (got $format)")
                .build()
        }
    }

    private fun handleCreateJob(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        remoteIp: String,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val requestedKOctets = req.getIntAttr("job-k-octets")
        if (requestedKOctets != null && requestedKOctets > IppCodec.MAX_DOCUMENT_K_OCTETS) {
            delegate.onLog(LogCategory.IPP, "Rejected oversized job-k-octets=$requestedKOctets in Create-Job", true)
            return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_REQUEST_ENTITY_TOO_LARGE, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Document exceeds 1 GB (1,048,576 KB) limit")
                .build()
        }
        val title = req.getStringAttr("job-name")?.takeIf { it.isNotBlank() } ?: "Wi-Fi Print Job"
        val user = req.getStringAttr("requesting-user-name")?.takeIf { it.isNotBlank() } ?: remoteIp
        val options = resolveJobOptions(req)
        val result = delegate.createPendingIppJob(title = title, clientName = user, options = options)
        return result.fold(
            onSuccess = { record ->
                createdJobOptions[record.id] = user to options
                buildJobCreatedResponse(major, minor, reqLang, req.requestId, record, endpoint)
            },
            onFailure = { err ->
                val statusCode = if (err is DeviceBusyException) {
                    IppConstants.STATUS_SERVER_BUSY
                } else {
                    IppConstants.STATUS_SERVER_INTERNAL_ERROR
                }
                IppCodec.ResponseBuilder(major, minor, statusCode, req.requestId, reqLang)
                    .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", err.message ?: "Printer busy")
                    .build()
            },
        )
    }

    private fun handleSendDocument(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        remoteIp: String,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val jobId = extractJobId(req)
        val file = req.documentFile
        if (file == null) {
            return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_BAD_REQUEST, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "No document payload attached")
                .build()
        }
        val saved = jobId?.let { createdJobOptions.remove(it) }
        val title = req.getStringAttr("document-name") ?: req.getStringAttr("job-name") ?: "Wi-Fi Document"
        val client = saved?.first ?: req.getStringAttr("requesting-user-name") ?: remoteIp
        val options = saved?.second ?: resolveJobOptions(req)
        val mime = req.getStringAttr("document-format")

        val result = delegate.attachDocumentAndEnqueue(
            existingJobId = jobId,
            title = title,
            clientName = client,
            declaredMime = mime,
            file = file,
            options = options,
        )
        return result.fold(
            onSuccess = { record -> buildJobCreatedResponse(major, minor, reqLang, req.requestId, record, endpoint) },
            onFailure = { err ->
                file.delete()
                val statusCode = when (err) {
                    is DeviceBusyException -> IppConstants.STATUS_SERVER_BUSY
                    is com.example.printer.render.UnsupportedDocumentFormatException ->
                        IppConstants.STATUS_CLIENT_DOCUMENT_FORMAT_NOT_SUPPORTED
                    else -> IppConstants.STATUS_SERVER_INTERNAL_ERROR
                }
                IppCodec.ResponseBuilder(major, minor, statusCode, req.requestId, reqLang)
                    .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", err.message ?: "Print failed")
                    .build()
            },
        )
    }

    private fun handlePrintJob(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        remoteIp: String,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val file = req.documentFile
        if (file == null) {
            return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_BAD_REQUEST, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Missing PDF or image document body")
                .build()
        }
        val title = req.getStringAttr("job-name")?.takeIf { it.isNotBlank() } ?: "Wi-Fi Print Job"
        val client = req.getStringAttr("requesting-user-name")?.takeIf { it.isNotBlank() } ?: remoteIp
        val mime = req.getStringAttr("document-format")
        val options = resolveJobOptions(req)

        val result = delegate.attachDocumentAndEnqueue(
            existingJobId = null,
            title = title,
            clientName = client,
            declaredMime = mime,
            file = file,
            options = options,
        )
        return result.fold(
            onSuccess = { record -> buildJobCreatedResponse(major, minor, reqLang, req.requestId, record, endpoint) },
            onFailure = { err ->
                file.delete()
                val statusCode = when (err) {
                    is DeviceBusyException -> IppConstants.STATUS_SERVER_BUSY
                    is com.example.printer.render.UnsupportedDocumentFormatException ->
                        IppConstants.STATUS_CLIENT_DOCUMENT_FORMAT_NOT_SUPPORTED
                    else -> IppConstants.STATUS_SERVER_INTERNAL_ERROR
                }
                IppCodec.ResponseBuilder(major, minor, statusCode, req.requestId, reqLang)
                    .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", err.message ?: "Print job error")
                    .build()
            },
        )
    }

    private fun busyMessageIfUnavailable(): String? = when (delegate.currentDeviceBusyState()) {
        DeviceBusyState.SCANNING -> "Busy: Scanner is in use — try again shortly"
        DeviceBusyState.PRINTING -> "Busy: Printer is printing another job — try again shortly"
        DeviceBusyState.AVAILABLE -> null
    }

    private fun handleGetJobAttributes(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val jobId = extractJobId(req)
        val job = delegate.currentJobs().firstOrNull { it.id == jobId }
            ?: return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_CLIENT_NOT_FOUND, req.requestId, reqLang)
                .addString(IppConstants.TAG_TEXT_WITHOUT_LANGUAGE, "status-message", "Job #$jobId not found")
                .build()
        return buildJobCreatedResponse(major, minor, reqLang, req.requestId, job, endpoint)
    }

    private fun handleGetJobs(
        major: Int,
        minor: Int,
        reqLang: String,
        req: IppRequest,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        val which = req.getStringAttr("which-jobs") ?: "not-completed"
        val jobs = delegate.currentJobs().filter { job ->
            when (which) {
                "completed" -> job.status == PrintJobStatus.COMPLETED || job.status == PrintJobStatus.CANCELED || job.status == PrintJobStatus.FAILED
                "all" -> true
                else -> job.status == PrintJobStatus.PENDING || job.status == PrintJobStatus.RENDERING || job.status == PrintJobStatus.TRANSMITTING
            }
        }
        val builder = IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_SUCCESSFUL_OK, req.requestId, reqLang)
        jobs.forEach { job ->
            builder.beginGroup(IppConstants.TAG_JOB_ATTRIBUTES)
                .addInt(IppConstants.TAG_INTEGER, "job-id", job.id)
                .addString(IppConstants.TAG_URI, "job-uri", endpoint.jobUri(job.id))
                .addString(IppConstants.TAG_URI, "job-printer-uri", endpoint.printerUri)
                .addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", job.title)
                .addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-originating-user-name", job.clientName)
                .addInt(IppConstants.TAG_ENUM, "job-state", job.status.ippStateCode)
                .addString(
                    IppConstants.TAG_KEYWORD,
                    "job-state-reasons",
                    when (job.status) {
                        PrintJobStatus.COMPLETED -> "job-completed-successfully"
                        PrintJobStatus.CANCELED -> "job-canceled-by-user"
                        PrintJobStatus.FAILED -> "aborted-by-system"
                        else -> "none"
                    },
                )
                .addInt(IppConstants.TAG_INTEGER, "job-impressions", job.imposedSheets)
                .addInt(IppConstants.TAG_INTEGER, "job-impressions-completed", if (job.status == PrintJobStatus.COMPLETED) job.imposedSheets else 0)
        }
        return builder.build()
    }

    private fun handleCancelJob(major: Int, minor: Int, reqLang: String, req: IppRequest): ByteArray {
        val jobId = extractJobId(req) ?: -1
        val canceled = delegate.cancelJob(jobId)
        val status = if (canceled) IppConstants.STATUS_SUCCESSFUL_OK else IppConstants.STATUS_CLIENT_NOT_FOUND
        return IppCodec.ResponseBuilder(major, minor, status, req.requestId, reqLang).build()
    }

    private fun buildJobCreatedResponse(
        major: Int,
        minor: Int,
        reqLang: String,
        requestId: Int,
        job: PrintJobRecord,
        endpoint: EffectiveEndpoint,
    ): ByteArray {
        return IppCodec.ResponseBuilder(major, minor, IppConstants.STATUS_SUCCESSFUL_OK, requestId, reqLang)
            .beginGroup(IppConstants.TAG_JOB_ATTRIBUTES)
            .addInt(IppConstants.TAG_INTEGER, "job-id", job.id)
            .addString(IppConstants.TAG_URI, "job-uri", endpoint.jobUri(job.id))
            .addString(IppConstants.TAG_URI, "job-printer-uri", endpoint.printerUri)
            .addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", job.title)
            .addString(IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-originating-user-name", job.clientName)
            .addInt(IppConstants.TAG_ENUM, "job-state", job.status.ippStateCode)
            .addString(
                IppConstants.TAG_KEYWORD,
                "job-state-reasons",
                when (job.status) {
                    PrintJobStatus.COMPLETED -> "job-completed-successfully"
                    PrintJobStatus.CANCELED -> "job-canceled-by-user"
                    PrintJobStatus.FAILED -> "aborted-by-system"
                    else -> "none"
                },
            )
            .addInt(IppConstants.TAG_INTEGER, "job-impressions", job.imposedSheets)
            .addInt(IppConstants.TAG_INTEGER, "job-impressions-completed", if (job.status == PrintJobStatus.COMPLETED) job.imposedSheets else 0)
            .build()
    }

    private fun resolveJobOptions(req: IppRequest): PrintJobOptions {
        val settings = delegate.currentSettings()
        val mediaCol = req.getCollectionAttr("media-col")
        val mediaSizeCol = mediaCol?.get("media-size")?.firstOrNull()?.collectionValue

        // 1. Paper size: prefer media-col.media-size dimensions, then `media` keyword, defaulting to A4
        val xDim = mediaSizeCol?.get("x-dimension")?.firstOrNull()?.intValue
        val yDim = mediaSizeCol?.get("y-dimension")?.firstOrNull()?.intValue
        val resolvedPaper = when {
            xDim != null && yDim != null && xDim > 0 && yDim > 0 ->
                PaperSize.fromDimensionsHundredthsMm(xDim, yDim)
            else -> PaperSize.fromIppKeyword(req.getStringAttr("media"))
        }

        // 2. Page margins: from media-col (1/100 mm), top-level media-*-margin (1/100 mm), or CUPS page-*-margin (points)
        fun readMarginHundredths(colKey: String, cupsPointsKey: String): Int? {
            val fromCol = mediaCol?.get(colKey)?.firstOrNull()?.intValue
            if (fromCol != null) return fromCol
            val fromTop = req.getIntAttr(colKey)
            if (fromTop != null) return fromTop
            val fromPts = req.getIntAttr(cupsPointsKey)
            if (fromPts != null) return kotlin.math.round(fromPts * 2540.0 / 72.0).toInt()
            return null
        }
        val resolvedMargins = PageMargins.fromHundredthsMm(
            top = readMarginHundredths("media-top-margin", "page-top"),
            bottom = readMarginHundredths("media-bottom-margin", "page-bottom"),
            left = readMarginHundredths("media-left-margin", "page-left"),
            right = readMarginHundredths("media-right-margin", "page-right"),
        )

        // 3. Scaling: standard IPP `print-scaling` or legacy CUPS `fit-to-page` boolean
        val resolvedScaling = PrintScaling.fromIppKeyword(req.getStringAttr("print-scaling"))
            ?: when (req.getBoolAttr("fit-to-page")) {
                true -> PrintScaling.FIT
                false -> PrintScaling.NONE
                null -> PrintScaling.AUTO_FIT
            }

        // 4. Orientation, N-Up, N-Up direction, N-Up borders, Duplex
        val resolvedOrientation = PrintOrientation.fromIppEnum(req.getIntAttr("orientation-requested"))
        val resolvedNUp = req.getIntAttr("number-up")?.let { PagesPerSheet.fromCount(it) } ?: PagesPerSheet.ONE
        val resolvedNUpDir = NUpPresentationDirection.fromIppKeyword(req.getStringAttr("presentation-direction-number-up"))
        val pageBorder = req.getStringAttr("page-border")?.lowercase()
        val drawBorders = pageBorder != null && pageBorder != "none"
        val resolvedDuplex = DuplexMode.fromIppKeyword(req.getStringAttr("sides"))

        // 5. Copies, collation, page ranges, reverse page order
        val resolvedCopies = (req.getIntAttr("copies") ?: 1).coerceIn(1, 99)
        val collateCopies = !req.getStringAttr("sheet-collate").equals("uncollated", ignoreCase = true)
        val pageRanges = req.getRangeListAttr("page-ranges")
        val delivery = (req.getStringAttr("page-delivery") ?: req.getStringAttr("outputorder"))?.lowercase()
        val reversePageOrder = delivery == "reverse-order" || delivery == "reverse"

        // 6. DPI, print quality / draft toner-save, content optimization, media type, density
        val reqResolutionDpi = req.getResolutionDpi("printer-resolution")
        val reqQuality = req.getIntAttr("print-quality")
        val resolvedResolution = when {
            reqResolutionDpi != null -> PrintResolution.fromDpi(reqResolutionDpi)
            reqQuality == 5 -> PrintResolution.DPI_600
            else -> PrintResolution.DPI_300
        }
        val tonerSave = reqQuality == 3 || req.getBoolAttr("brother-toner-save") == true
        val contentOptimize = req.getStringAttr("print-content-optimize")?.lowercase()
        val ditherImages = contentOptimize != "text"
        val mediaTypeKeyword = mediaCol?.get("media-type")?.firstOrNull()?.stringValue
            ?: req.getStringAttr("media-type")
        val resolvedMediaType = MediaType.fromIppKeyword(mediaTypeKeyword)
        val densityAdjust = (req.getIntAttr("print-density") ?: req.getIntAttr("density") ?: 0).coerceIn(-6, 6)

        return PrintJobOptions(
            pagesPerSheet = resolvedNUp,
            nUpDirection = resolvedNUpDir,
            duplexMode = resolvedDuplex,
            paperSize = resolvedPaper,
            resolution = resolvedResolution,
            printerLanguage = settings.printerLanguage,
            copies = resolvedCopies,
            collateCopies = collateCopies,
            tonerSave = tonerSave,
            drawNUpBorders = drawBorders,
            ditherImages = ditherImages,
            margins = resolvedMargins,
            scaling = resolvedScaling,
            orientation = resolvedOrientation,
            pageRanges = pageRanges,
            reversePageOrder = reversePageOrder,
            mediaType = resolvedMediaType,
            densityAdjust = densityAdjust,
        )
    }

    private fun buildWebConsoleHtml(): String {
        val settings = delegate.currentSettings()
        val usb = delegate.currentUsbInfo()
        val ip = delegate.currentIpAddress() ?: "localhost"
        val busyLabel = when (delegate.currentDeviceBusyState()) {
            DeviceBusyState.AVAILABLE -> "Available"
            DeviceBusyState.PRINTING -> "Busy (Printing)"
            DeviceBusyState.SCANNING -> "Busy (Scanning)"
        }
        val jobs = delegate.currentJobs().take(10)
        val rowsHtml = if (jobs.isEmpty()) {
            "<tr><td colspan='5' style='color:#64748b;text-align:center'>No print jobs yet</td></tr>"
        } else {
            jobs.joinToString("") { j ->
                "<tr><td>#${j.id}</td><td>${escapeHtml(j.title)}</td><td>${j.pagesPerSheet.shortLabel} / ${j.duplexMode.shortLabel}</td><td>${j.source.label}</td><td><b>${j.status.label}</b></td></tr>"
            }
        }
        return """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escapeHtml(settings.printerName)} — PrintBridge IPP</title>
            <style>body{font-family:system-ui,-apple-system,sans-serif;background:#f4f5f7;color:#0f172a;margin:0;padding:24px}
            .card{max-width:720px;margin:0 auto;background:#fff;border-radius:14px;padding:24px;box-shadow:0 4px 20px rgba(15,23,42,.08)}
            .badge{display:inline-block;padding:4px 10px;border-radius:999px;font-size:12px;font-weight:700;background:#dce8ff;color:#0d52bd}
            code{background:#f1f5f9;padding:3px 7px;border-radius:6px;font-family:monospace}
            table{width:100%;border-collapse:collapse;margin-top:14px;font-size:14px}th,td{padding:8px;border-bottom:1px solid #e2e8f0;text-align:left}</style>
            </head><body><div class="card">
            <span class="badge">PRINTBRIDGE IPP SERVER</span>
            <h1 style="margin:10px 0 4px">${escapeHtml(settings.printerName)}</h1>
            <p style="color:#475569;margin-top:0">USB OTG Status: <b>${escapeHtml(usb.statusMessage)}</b> &middot; Device: <b>$busyLabel</b></p>
            <p>IPP URI: <code>http://$ip:${settings.ippPort}/ipp/print</code></p>
            ${if (delegate.isScannerAvailable()) "<p>HTTP Scan API URI: <code>http://$ip:${settings.ippPort}/api/scan</code> (<b>Scanner Connected</b>)</p>" else ""}
            <p>Supported Formats: <code>image/pwg-raster</code>, <code>application/pdf</code>, <code>image/jpeg</code>, <code>image/png</code>, <code>image/webp</code></p>
            <p>Limits: <b>Max 1 GB (1,048,576 KB)</b> &middot; Up to <b>10,000</b> impressions</p>
            <p>Print Parameters: <b>Client-controlled</b> (DPI, paper size, margins, scaling, orientation, duplex, N-Up, page ranges, copies, collation, quality)</p>
            <h3>Recent Spooler Jobs</h3>
            <table><tr><th>ID</th><th>Document</th><th>Layout</th><th>Source</th><th>Status</th></tr>$rowsHtml</table>
            </div></body></html>
        """.trimIndent()
    }

    private fun buildFallbackStatusJson(): String {
        val settings = delegate.currentSettings()
        val usb = delegate.currentUsbInfo()
        val busy = delegate.currentDeviceBusyState()
        val scannerAvail = delegate.isScannerAvailable()
        return org.json.JSONObject().apply {
            put("server", "PrintBridge")
            put("printerName", settings.printerName)
            put("model", if (usb.isConnected) usb.displayTitle else settings.printerName)
            put("connected", usb.isConnected)
            put("busyState", busy.name)
            put("scannerAvailable", scannerAvail)
        }.toString()
    }

    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun writeSimpleScanResponse(
        out: OutputStream,
        response: SimpleScanHttpResponse,
        headOnly: Boolean,
    ) {
        when (response) {
            is SimpleScanHttpResponse.Bytes -> {
                val payload = if (headOnly) ByteArray(0) else response.body
                val header = buildString {
                    append("HTTP/1.1 ${response.statusCode} ${response.reason}\r\n")
                    append("Content-Type: ${response.contentType}\r\n")
                    append("Content-Length: ${response.body.size}\r\n")
                    response.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Server: PrintBridge-ScanAPI/2.0\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (payload.isNotEmpty()) out.write(payload)
                out.flush()
            }

            is SimpleScanHttpResponse.FileStream -> {
                val fileLen = response.file.length()
                val header = buildString {
                    append("HTTP/1.1 ${response.statusCode} ${response.reason}\r\n")
                    append("Content-Type: ${response.contentType}\r\n")
                    append("Content-Length: $fileLen\r\n")
                    response.extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Server: PrintBridge-ScanAPI/2.0\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val bufferedOut = BufferedOutputStream(out, 64 * 1024)
                bufferedOut.write(header.toByteArray(Charsets.US_ASCII))
                if (!headOnly && response.file.exists()) {
                    FileInputStream(response.file).use { fis ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = fis.read(buf)
                            if (n <= 0) break
                            bufferedOut.write(buf, 0, n)
                        }
                    }
                }
                bufferedOut.flush()
            }
        }
    }

    private fun writeHttpResponse(
        out: OutputStream,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        keepAlive: Boolean,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val dateStr = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("GMT")
        }.format(java.util.Date())
        val header = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Date: $dateStr\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Server: PrintBridge-IPP/1.0\r\n")
            append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readHttpLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var prev = -1
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (sawAny) baos.toString("ISO-8859-1").trimEnd('\r') else null
            sawAny = true
            if (b == '\n'.code) {
                return baos.toString("ISO-8859-1").trimEnd('\r')
            }
            if (baos.size() < 8192) baos.write(b)
            prev = b
        }
    }

    private fun InputStream.drainQuietly() {
        runCatching {
            val sink = ByteArray(4096)
            while (read(sink) > 0) {
                // drain remaining chunk/bounded bytes
            }
        }
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead: Long = 0L
            private set

        override fun read(): Int {
            val b = delegate.read()
            if (b != -1) bytesRead++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = delegate.read(b, off, toRead)
            if (n > 0) remaining -= n
            return n
        }
    }

    private class ChunkedInputStream(private val delegate: InputStream) : InputStream() {
        private var chunkRemaining = 0
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n == 1) (one[0].toInt() and 0xFF) else -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (finished) return -1
            while (chunkRemaining == 0) {
                val sizeLine = readLineAscii() ?: run {
                    finished = true
                    return -1
                }
                val cleanHex = sizeLine.substringBefore(';').trim()
                if (cleanHex.isEmpty()) {
                    throw IOException("Invalid empty HTTP chunk size line")
                }
                val nextSize = cleanHex.toIntOrNull(16)
                    ?: throw IOException("Invalid HTTP chunk size '$cleanHex'")
                if (nextSize < 0) {
                    throw IOException("Negative HTTP chunk size '$cleanHex'")
                }
                if (nextSize == 0) {
                    // Consume optional trailer headers and terminating CRLF
                    while (true) {
                        val trailer = readLineAscii()
                        if (trailer.isNullOrEmpty()) break
                    }
                    finished = true
                    return -1
                }
                chunkRemaining = nextSize
            }
            val toRead = minOf(len, chunkRemaining)
            val n = delegate.read(b, off, toRead)
            if (n <= 0) {
                finished = true
                return -1
            }
            chunkRemaining -= n
            if (chunkRemaining == 0) {
                // Consume trailing CRLF ending this chunk
                readLineAscii()
            }
            return n
        }

        private fun readLineAscii(): String? {
            val sb = StringBuilder(32)
            while (true) {
                val c = delegate.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString().trimEnd('\r')
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
            }
        }
    }
}
