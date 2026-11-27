package com.example.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Direct USB Host scanner driver for Brother `brscan4` USB multifunction devices (VID 0x04F9).
 *
 * Implements the Brother USB vendor-specific bulk protocol (Interface 1 / Class 0xFF):
 * - Endpoint HALT reset (`CLEAR_FEATURE`) for reliable back-to-back scans
 * - Vendor control transfer open/close (`BREQ_GET_OPEN` / `BREQ_GET_CLOSE`, `wIndex = 0`)
 * - Strict `brscan4` protocol verification (`BREQ_GET_OPEN` descriptor + `ESC Q` + `ESC I`)
 * - Scan geometry & capability negotiation (`ESC I \n R=... \n M=... \n 0x80`) with brscan4 DPI clamping
 * - Flatbed and multi-page ADF Simplex (`D=SIN`) / ADF Duplex (`D=DUP`) execution (`ESC X ... 0x80`)
 * - Immediate hardware abort (`ESC R`) + trailing USB FIFO drain (`ReadTrash`)
 * - Variable-wrapper brscan4 framing (`[hdr][wrapperLen LE][wrapper][dataLen LE][payload]`),
 *   PackBits (`RLENGTH`) decompression, YCbCr/RGB planar & interleaved decoding,
 *   8-bit grayscale with Brother brightness/contrast LUT, 1-bit monochrome, and JPEG fallback.
 */
class BrotherDcpL2520dScanner(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {

    enum class ScanSource(
        val displayName: String,
        val protocolCommand: String?,
        val isAdf: Boolean
    ) {
        FLATBED(
            displayName = "Flatbed",
            protocolCommand = null,
            isAdf = false
        ),
        ADF_SIMPLEX(
            displayName = "ADF (1-Sided)",
            protocolCommand = "D=SIN",
            isAdf = true
        ),
        ADF_DUPLEX(
            displayName = "ADF (Duplex)",
            protocolCommand = "D=DUP",
            isAdf = true
        )
    }

    enum class ScanMode(
        val protocolCode: String,
        val displayName: String,
        val bitDepthLabel: String,
        val description: String
    ) {
        MONOCHROME_1BIT(
            protocolCode = "TEXT",
            displayName = "1-bit B&W",
            bitDepthLabel = "1-bit Mono",
            description = "Sharp black & white lineart"
        ),
        GRAYSCALE_8BIT(
            protocolCode = "GRAY64",
            displayName = "8-bit Gray",
            bitDepthLabel = "8-bit Grayscale",
            description = "256 shades of gray"
        ),
        COLOR_24BIT(
            protocolCode = "CGRAY",
            displayName = "24-bit Color",
            bitDepthLabel = "24-bit True Color",
            description = "Full RGB color"
        )
    }

    enum class Compression(
        val protocolCode: String,
        val displayName: String,
        val subtitle: String
    ) {
        PACKBITS(
            protocolCode = "RLENGTH",
            displayName = "PackBits RLE",
            subtitle = "Faster USB transfer"
        ),
        NONE(
            protocolCode = "NONE",
            displayName = "Uncompressed",
            subtitle = "Raw scanline stream"
        )
    }

    enum class PageSize(
        val displayName: String,
        val dimensionsLabel: String,
        val widthMm: Double,
        val heightMm: Double
    ) {
        A4("A4", "210 × 297 mm", 210.0, 297.0),
        LETTER("Letter", "216 × 279 mm", 215.9, 279.4),
        A5("A5", "148 × 210 mm", 148.0, 210.0),
        ID_CARD("Half / Card", "105 × 148 mm", 105.0, 148.0);

        fun pixelWidth(dpi: Int): Int = ((widthMm / MM_PER_INCH) * dpi).roundToInt()
        fun pixelHeight(dpi: Int): Int = ((heightMm / MM_PER_INCH) * dpi).roundToInt()
        internal val width01Mm: Int get() = (widthMm * 10.0).roundToInt()
        internal val height01Mm: Int get() = (heightMm * 10.0).roundToInt()
    }

    data class ScanConfig(
        val dpi: Int = 300,
        val mode: ScanMode = ScanMode.COLOR_24BIT,
        val compression: Compression = Compression.PACKBITS,
        val pageSize: PageSize = PageSize.A4,
        val source: ScanSource = ScanSource.FLATBED,
        val brightness: Int = 50,        // 0..100 (50 = neutral)
        val contrast: Int = 50,          // 0..100 (50 = neutral)
        val colorIsYCbCr: Boolean = true // brscan4 sends planar Cr/Y/Cb by default
    ) {
        val requestedWidthPx: Int get() = pageSize.pixelWidth(dpi)
        val requestedHeightPx: Int get() = pageSize.pixelHeight(dpi)
    }

    data class ScannerCapabilities(
        val isBrscan4Supported: Boolean,
        val supportsFlatbed: Boolean,
        val supportsAdf: Boolean,
        val supportsAdfDuplex: Boolean,
        val protocolByte: Int? = null
    ) {
        val availableSources: List<ScanSource>
            get() = buildList {
                if (supportsFlatbed) add(ScanSource.FLATBED)
                if (supportsAdf) add(ScanSource.ADF_SIMPLEX)
                if (supportsAdfDuplex) add(ScanSource.ADF_DUPLEX)
            }.ifEmpty { listOf(ScanSource.FLATBED) }
    }

    data class UsbScannerHardwareInfo(
        val deviceName: String,
        val productName: String,
        val manufacturerName: String,
        val vendorId: Int,
        val productId: Int,
        val isBrotherVendor: Boolean,
        val scannerInterfaceId: Int?,
        val bulkInAddress: Int?,
        val bulkOutAddress: Int?,
        val maxPacketSize: Int?
    ) {
        val vidPidHex: String
            get() = String.format("%04X:%04X", vendorId, productId)
    }

    data class RawScannedPage(
        val file: File,
        val format: String,
        val widthPx: Int,
        val heightPx: Int,
        val bytesPerLine: Int,
        val deviceDpiX: Int,
        val deviceDpiY: Int,
        val requestedDpi: Int,
        val mode: ScanMode
    )

    data class RawBrscan4PageHeader(
        val pageNumber: Int,
        val widthPx: Int,
        val heightPx: Int,
        val deviceDpiX: Int,
        val deviceDpiY: Int,
        val requestedDpi: Int,
        val mode: ScanMode,
        val colorIsYCbCr: Boolean,
        val brightness: Int,
        val contrast: Int,
        val isAdf: Boolean
    )

    class ScanCancelledException(message: String = "Scan cancelled by user") : Exception(message)

    class UnsupportedScannerException(message: String) : Exception(message)

    class ScannerException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val cancelRequested = AtomicBoolean(false)

    fun requestCancel() {
        cancelRequested.set(true)
    }

    /**
     * Probes the connected USB device to verify Brother `brscan4` compatibility and
     * query Flatbed / ADF capabilities (`ESC Q` + `ESC I`).
     * Throws [UnsupportedScannerException] if the device does not speak `brscan4`.
     */
    suspend fun probeCapabilities(): ScannerCapabilities = withContext(Dispatchers.IO) {
        cancelRequested.set(false)
        if (device.vendorId != BROTHER_VENDOR_ID) {
            throw UnsupportedScannerException("Only Brother brscan4 USB scanners are supported.")
        }
        if (!usbManager.hasPermission(device)) {
            throw ScannerException("USB permission not granted.")
        }

        val endpoints = locateScannerEndpoints(device)
            ?: throw UnsupportedScannerException(
                "Scanner not supported: no Brother brscan4 vendor bulk interface found."
            )

        ensureInterScanCooldown()

        val connection = usbManager.openDevice(device)
            ?: throw ScannerException("Failed to open USB connection. Check OTG cable power.")

        var claimed = false
        var sessionOpened = false

        try {
            claimed = connection.claimInterface(endpoints.usbInterface, true)
            if (!claimed) {
                throw ScannerException("Could not claim USB scanner interface #${endpoints.usbInterface.id}")
            }

            clearEndpointHalt(connection, endpoints.bulkIn.address)
            clearEndpointHalt(connection, endpoints.bulkOut.address)

            openVendorSession(connection)
            sessionOpened = true
            drainStaleInputOnOpen(connection, endpoints.bulkIn, endpoints.bulkOut)

            // Verify ESC Q inquiry response
            sendCommand(connection, endpoints.bulkOut, CMD_QUERY_DEV_INFO)
            val inquiryBytes = readNonFixedResponse(
                connection = connection,
                bulkIn = endpoints.bulkIn,
                maxLen = 272,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )
            if (inquiryBytes.isEmpty()) {
                throw ScannerException(
                    "Timed out waiting for brscan4 inquiry response (ESC Q)."
                )
            }
            val protocolByte = inquiryBytes.getOrNull(3)?.toInt()?.and(0xFF)
            Log.d(
                TAG,
                "Probe ESC Q (${inquiryBytes.size}B): protocol=$protocolByte dpiCap=${inquiryBytes.getOrNull(14)}"
            )

            // Query ESC I geometry & active scan source enum
            val queryCmd = buildScanQueryCommand(ScanConfig(dpi = 300, mode = ScanMode.COLOR_24BIT))
            sendCommand(connection, endpoints.bulkOut, queryCmd)
            val infoBytes = readNonFixedResponse(
                connection = connection,
                bulkIn = endpoints.bulkIn,
                maxLen = 356,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )

            val parsedInfo = parseDeviceScanInfoOrNull(infoBytes, ScanConfig())
            if (parsedInfo == null) {
                Log.w(
                    TAG,
                    "Probe ESC I returned unparseable response (${infoBytes.size}B); falling back to Flatbed-supported capabilities"
                )
                return@withContext ScannerCapabilities(
                    isBrscan4Supported = true,
                    supportsFlatbed = true,
                    supportsAdf = false,
                    supportsAdfDuplex = false,
                    protocolByte = protocolByte
                )
            }

            // brscan4 ESC I token[2] is an active-source enum:
            //   1 = ADF, 2 = Flatbed, 3 = ADF Duplex (NOT a hardware capability bitmask).
            // Always enable Flatbed for detected Brother brscan4 scanners.
            val src = parsedInfo.scanSource
            val supportsFlatbed = true
            val supportsAdf = (src == 1 || src == 3)
            val supportsDuplex = (src == 3)

            Log.d(
                TAG,
                "Probe brscan4 OK: scanSource=$src flatbed=$supportsFlatbed adf=$supportsAdf duplex=$supportsDuplex"
            )

            ScannerCapabilities(
                isBrscan4Supported = true,
                supportsFlatbed = supportsFlatbed,
                supportsAdf = supportsAdf,
                supportsAdfDuplex = supportsDuplex,
                protocolByte = protocolByte
            )
        } catch (e: UnsupportedScannerException) {
            throw e
        } catch (e: ScannerException) {
            throw e
        } finally {
            if (sessionOpened) {
                runCatching { closeVendorSession(connection) }
            }
            if (claimed) {
                runCatching { connection.releaseInterface(endpoints.usbInterface) }
            }
            runCatching { connection.close() }

            if (sessionOpened) {
                lastUsbSessionClosedAtMs = System.currentTimeMillis()
                withContext(NonCancellable) {
                    delay(CLOSE_COOLDOWN_MS)
                }
            }
        }
    }

    /**
     * Executes a single flatbed scan over USB OTG and returns the assembled [Bitmap].
     * Calls [onProgress] with `(currentLine, totalLines)` as scanlines stream in.
     */
    suspend fun scanFlatbed(
        config: ScanConfig,
        onProgress: (line: Int, total: Int) -> Unit = { _, _ -> },
        onStatusUpdate: (stageProgress: Float, statusMessage: String) -> Unit = { _, _ -> }
    ): Bitmap {
        var captured: Bitmap? = null
        scanPages(
            config = config.copy(source = ScanSource.FLATBED),
            onPageProgress = { _, line, total -> onProgress(line, total) },
            onStatusUpdate = onStatusUpdate,
            onPageCaptured = { _, bitmap -> captured = bitmap }
        )
        return captured ?: throw ScannerException("Scan finished without image data.")
    }

    /**
     * Executes a flatbed (1 page) or brscan4 ADF (1..N pages, simplex or duplex) scan over USB OTG.
     * Invokes [onPageCaptured] as each page completes and returns total pages captured.
     */
    suspend fun scanPages(
        config: ScanConfig,
        onPageProgress: (pageNumber: Int, line: Int, total: Int) -> Unit = { _, _, _ -> },
        onStatusUpdate: (stageProgress: Float, statusMessage: String) -> Unit = { _, _ -> },
        onPageCaptured: suspend (pageNumber: Int, bitmap: Bitmap) -> Unit
    ): Int = executeUsbScanSession(
        config = config,
        onPageProgress = onPageProgress,
        onStatusUpdate = onStatusUpdate,
    ) { reader, devInfo, area, sendNextPageCommand ->
        var pageNumber = 1
        var pagesDelivered = 0

        while (true) {
            checkCancelled()
            val pageResult = receiveSinglePage(
                reader = reader,
                config = config,
                area = area,
                pageNumber = pageNumber,
                alreadyScannedPages = pagesDelivered,
                onProgress = { line, total -> onPageProgress(pageNumber, line, total) }
            )

            val pageBitmap = pageResult.bitmap
            if (pageBitmap != null) {
                val finalBitmap = scaleBitmapIfNeeded(pageBitmap, config, devInfo, area)
                pagesDelivered++
                onPageCaptured(pagesDelivered, finalBitmap)
            }

            if (!config.source.isAdf || !pageResult.hasMorePages) {
                break
            }

            checkCancelled()
            sendNextPageCommand()
            pageNumber++
            onPageProgress(pageNumber, 0, area.height)
        }

        pagesDelivered
    }

    /**
     * Zero-encoding raw streaming scan over USB OTG.
     *
     * Uses the exact same Brother `brscan4` USB handshake and teardown as [scanPages], but streams
     * decoded raw raster bytes (`raw-rgb24`, `raw-gray8`, `raw-mono1`, or native hardware `raw-jpeg`)
     * directly row-by-row into temporary files in [outputDir] with zero full-page Bitmap allocation,
     * zero scaling/cropping, and zero JPEG/PNG/PDF encoding on the host.
     */
    suspend fun scanRawPages(
        config: ScanConfig,
        outputDir: File,
        filePrefix: String,
        onPageProgress: (pageNumber: Int, line: Int, total: Int) -> Unit = { _, _, _ -> },
        onStatusUpdate: (stageProgress: Float, statusMessage: String) -> Unit = { _, _ -> },
        onPageCaptured: suspend (pageNumber: Int, rawPage: RawScannedPage) -> Unit
    ): Int = executeUsbScanSession(
        config = config,
        onPageProgress = onPageProgress,
        onStatusUpdate = onStatusUpdate,
    ) { reader, devInfo, area, sendNextPageCommand ->
        if (!outputDir.exists()) outputDir.mkdirs()
        var pageNumber = 1
        var pagesDelivered = 0

        while (true) {
            checkCancelled()
            val outFile = File(outputDir, "${filePrefix}_p${pageNumber}_${System.nanoTime()}.raw")
            val pageResult = receiveSinglePageRawToFile(
                reader = reader,
                config = config,
                devInfo = devInfo,
                area = area,
                outFile = outFile,
                pageNumber = pageNumber,
                alreadyScannedPages = pagesDelivered,
                onProgress = { line, total -> onPageProgress(pageNumber, line, total) }
            )

            val captured = pageResult.page
            if (captured != null) {
                pagesDelivered++
                onPageCaptured(pagesDelivered, captured)
            }

            if (!config.source.isAdf || !pageResult.hasMorePages) {
                break
            }

            checkCancelled()
            sendNextPageCommand()
            pageNumber++
            onPageProgress(pageNumber, 0, area.height)
        }

        pagesDelivered
    }

    /**
     * Zero-decode pass-through streaming over USB OTG for wireless sharing.
     *
     * Performs ZERO PackBits decompression, ZERO YCbCr->RGB conversion, ZERO LUT mapping,
     * ZERO Bitmap allocation, and ZERO temp file I/O on the host device. Untouched raw
     * `brscan4` records from USB Bulk IN are forwarded directly to [onRawRecord] as they arrive
     * so the remote client can decode them incrementally and show live scanning progress.
     */
    suspend fun streamRawBrscan4Pages(
        config: ScanConfig,
        onPageReady: suspend (RawBrscan4PageHeader) -> Unit,
        onRawRecord: suspend (header: Int, payload: ByteArray, isBoundary: Boolean) -> Unit
    ): Int = executeUsbScanSession(
        config = config,
        onPageProgress = { _, _, _ -> },
        onStatusUpdate = { _, _ -> },
    ) { reader, devInfo, area, sendNextPageCommand ->
        var pageNumber = 1
        var pagesDelivered = 0

        while (true) {
            checkCancelled()
            val pageHeader = RawBrscan4PageHeader(
                pageNumber = pageNumber,
                widthPx = area.width,
                heightPx = area.height,
                deviceDpiX = devInfo.devResoX,
                deviceDpiY = devInfo.devResoY,
                requestedDpi = config.dpi,
                mode = config.mode,
                colorIsYCbCr = config.colorIsYCbCr,
                brightness = config.brightness.coerceIn(0, 100),
                contrast = config.contrast.coerceIn(0, 100),
                isAdf = config.source.isAdf
            )
            onPageReady(pageHeader)

            var hasMorePages = false
            var receivedDataRecords = 0

            while (true) {
                checkCancelled()
                val record = reader.readNextRecord(adfMode = config.source.isAdf)
                if (record == null) {
                    if (receivedDataRecords > 0) {
                        Log.w(TAG, "Raw stream page $pageNumber ended early after $receivedDataRecords records")
                        onRawRecord(PACKET_EOF, ByteArray(0), true)
                        hasMorePages = false
                        break
                    }
                    if (config.source.isAdf && pagesDelivered > 0) {
                        onRawRecord(PACKET_EOF, ByteArray(0), true)
                        hasMorePages = false
                        break
                    }
                    throw ScannerException("Timed out waiting for scan data from scanner.")
                }

                if (record.isBoundaryStatus) {
                    when (record.header) {
                        PACKET_EOF -> {
                            Log.d(TAG, "Raw stream page $pageNumber End (0x80) after $receivedDataRecords records")
                            onRawRecord(PACKET_EOF, ByteArray(0), true)
                            hasMorePages = false
                            break
                        }
                        PACKET_NEXT_PAGE -> {
                            Log.d(TAG, "Raw stream page $pageNumber Next Page (0x81) after $receivedDataRecords records")
                            onRawRecord(PACKET_NEXT_PAGE, ByteArray(0), true)
                            hasMorePages = true
                            break
                        }
                        STATUS_CANCEL_TIMEOUT -> {
                            if (cancelRequested.get()) throw ScanCancelledException()
                            throw ScannerException("Scanner cancelled scan due to timeout (0xE3).")
                        }
                        STATUS_NO_DOC -> {
                            if (config.source.isAdf && (pagesDelivered > 0 || receivedDataRecords > 0)) {
                                Log.d(TAG, "ADF feeder empty (0xC2) after $pagesDelivered pages")
                                onRawRecord(PACKET_EOF, ByteArray(0), true)
                                hasMorePages = false
                                break
                            }
                            throw ScannerException("No document detected in feeder (0xC2).")
                        }
                        STATUS_DOC_JAM -> throw ScannerException("Document jam reported by scanner (0xC3).")
                        STATUS_COVER_OPEN -> throw ScannerException("Scanner cover is open (0xC4).")
                        else -> {
                            if (receivedDataRecords > 0) {
                                Log.w(
                                    TAG,
                                    "Raw stream status 0x${record.header.toString(16)} after $receivedDataRecords records — finalizing"
                                )
                                onRawRecord(PACKET_EOF, ByteArray(0), true)
                                hasMorePages = false
                                break
                            }
                            throw ScannerException(
                                "Scanner hardware error (status=0x${record.header.toString(16).uppercase()})."
                            )
                        }
                    }
                }

                if (record.payload.isEmpty()) continue
                receivedDataRecords++
                onRawRecord(record.header, record.payload, false)
            }

            if (receivedDataRecords > 0) {
                pagesDelivered++
            }

            if (!config.source.isAdf || !hasMorePages) {
                break
            }

            checkCancelled()
            sendNextPageCommand()
            pageNumber++
        }

        pagesDelivered
    }

    private suspend fun executeUsbScanSession(
        config: ScanConfig,
        onPageProgress: (pageNumber: Int, line: Int, total: Int) -> Unit,
        onStatusUpdate: (stageProgress: Float, statusMessage: String) -> Unit,
        streamPages: suspend (
            reader: Brscan4StreamReader,
            devInfo: DeviceScanInfo,
            area: ScanAreaDots,
            sendNextPageCommand: () -> Unit,
        ) -> Int,
    ): Int = withContext(Dispatchers.IO) {
        cancelRequested.set(false)

        if (device.vendorId != BROTHER_VENDOR_ID) {
            throw UnsupportedScannerException("Scanner not supported: only Brother brscan4 scanners are supported.")
        }

        if (!usbManager.hasPermission(device)) {
            throw ScannerException("USB permission not granted for ${device.deviceName}")
        }

        val endpoints = locateScannerEndpoints(device)
            ?: throw UnsupportedScannerException(
                "Scanner not supported: could not locate Brother brscan4 vendor bulk interface."
            )

        var lastRecoverableError: ScannerException? = null

        for (attempt in 1..MAX_SCAN_ATTEMPTS) {
            checkCancelled()
            ensureInterScanCooldown(onStatusUpdate)

            val attemptTag = if (attempt > 1) " (attempt $attempt/$MAX_SCAN_ATTEMPTS)" else ""
            onStatusUpdate(0.03f, "Opening USB scanner session$attemptTag...")

            val connection = usbManager.openDevice(device)
                ?: throw ScannerException("Failed to open USB connection. Check OTG cable power.")

            var claimed = false
            var sessionOpened = false
            var scanStarted = false
            var reader: Brscan4StreamReader? = null

            try {
                claimed = connection.claimInterface(endpoints.usbInterface, true)
                if (!claimed) {
                    throw ScannerException("Could not claim USB scanner interface #${endpoints.usbInterface.id}")
                }

                // 1. Reset bulk endpoint data toggles so back-to-back scans do not stall at 0 bytes
                clearEndpointHalt(connection, endpoints.bulkIn.address)
                clearEndpointHalt(connection, endpoints.bulkOut.address)

                // 2. Open Brother vendor session (BREQ_GET_OPEN, wIndex = 0) & validate 5-byte descriptor
                openVendorSession(connection)
                sessionOpened = true
                drainStaleInputOnOpen(connection, endpoints.bulkIn, endpoints.bulkOut)

                checkCancelled()

                // 3. Inquiry handshake (ESC Q \n 0x80) — accept any non-empty reply; fail fast on empty timeout
                onStatusUpdate(0.04f, "Connecting to Brother scanner (ESC Q)$attemptTag...")
                sendCommand(connection, endpoints.bulkOut, CMD_QUERY_DEV_INFO)
                val inquiryBytes = readNonFixedResponse(
                    connection = connection,
                    bulkIn = endpoints.bulkIn,
                    maxLen = 272,
                    timeoutMs = HANDSHAKE_TIMEOUT_MS
                )
                if (inquiryBytes.isEmpty()) {
                    throw ScannerException(
                        "Timed out waiting for brscan4 inquiry response (ESC Q)."
                    )
                }
                Log.d(
                    TAG,
                    "ESC Q inquiry (${inquiryBytes.size}B, attempt=$attempt): protocol=${inquiryBytes.getOrNull(3)} dpiCap=${inquiryBytes.getOrNull(14)}"
                )

                checkCancelled()

                // 4. Negotiate hardware resolution & scan limits (ESC I \n R=... \n M=... \n 0x80)
                onStatusUpdate(0.05f, "Negotiating ${config.dpi} DPI scan area$attemptTag...")
                val queryCmd = buildScanQueryCommand(config)
                sendCommand(connection, endpoints.bulkOut, queryCmd)
                val infoBytes = readNonFixedResponse(
                    connection = connection,
                    bulkIn = endpoints.bulkIn,
                    maxLen = 356,
                    timeoutMs = HANDSHAKE_TIMEOUT_MS
                )
                if (infoBytes.isEmpty()) {
                    if (attempt < MAX_SCAN_ATTEMPTS || config.source.isAdf) {
                        throw ScannerException("Timed out waiting for scanner capability response (ESC I).")
                    }
                    Log.w(TAG, "ESC I timed out on final flatbed attempt; falling back to clamped flatbed defaults")
                }
                var devInfo = if (infoBytes.isNotEmpty()) parseDeviceScanInfoOrNull(infoBytes, config) else null
                if (devInfo == null && infoBytes.isNotEmpty()) {
                    // A stray leftover packet may have preceded the CSV payload — read 1 quick follow-up chunk
                    Log.w(
                        TAG,
                        "ESC I first chunk unparseable (${infoBytes.size}B); reading follow-up chunk before fallback"
                    )
                    val followUp = readNonFixedResponse(
                        connection = connection,
                        bulkIn = endpoints.bulkIn,
                        maxLen = 356,
                        timeoutMs = 1_000
                    )
                    if (followUp.isNotEmpty()) {
                        devInfo = parseDeviceScanInfoOrNull(followUp, config)
                            ?: parseDeviceScanInfoOrNull(infoBytes + followUp, config)
                    }
                }
                val resolvedDevInfo = devInfo ?: run {
                    Log.w(
                        TAG,
                        "ESC I response (${infoBytes.size}B, head=${infoBytes.take(16).toByteArray().toHex()}) " +
                            "could not be parsed; falling back to default scan geometry for ${config.dpi} DPI"
                    )
                    fallbackDeviceScanInfo(config)
                }

                if (config.source.isAdf && resolvedDevInfo.scanSource != 1 && resolvedDevInfo.scanSource != 3) {
                    throw ScannerException("ADF is not supported by this scanner model. Switch source to Flatbed.")
                }

                val area = computeAlignedDeviceScanArea(config, resolvedDevInfo)

                Log.d(
                    TAG,
                    "Negotiated (${config.source.displayName}, attempt=$attempt): " +
                        "devDPI=${resolvedDevInfo.devResoX}x${resolvedDevInfo.devResoY}, " +
                        "max=${resolvedDevInfo.maxPixels}x${resolvedDevInfo.maxRaster}, " +
                        "area=${area.left},${area.top},${area.right},${area.bottom} (${area.width}x${area.height})"
                )

                checkCancelled()
                onStatusUpdate(0.06f, "Starting scanner carriage (${config.source.displayName})$attemptTag...")
                onPageProgress(1, 0, area.height)

                // 5. Trigger flatbed or ADF scan (ESC X ... 0x80)
                val startCmd = buildScanStartCommand(config, resolvedDevInfo, area)
                sendCommand(connection, endpoints.bulkOut, startCmd)
                scanStarted = true

                // Firmware carriage/feeder warmup pause (matches PageScanStart wait, kept cancellable)
                onStatusUpdate(0.07f, "Warming up scanner lamp & carriage$attemptTag...")
                val warmupSteps = if (config.source.isAdf) 8 else 15
                repeat(warmupSteps) {
                    checkCancelled()
                    delay(200L)
                }

                // 6. Stream single-page (Flatbed) or multi-page (ADF) brscan4 records with 5s live heartbeats
                onStatusUpdate(0.08f, "Waiting for first scanlines from scanner$attemptTag...")
                lateinit var streamReader: Brscan4StreamReader
                streamReader = Brscan4StreamReader(
                    connection = connection,
                    bulkIn = endpoints.bulkIn,
                    onHeartbeat = { elapsedSec ->
                        if (streamReader.hasReceivedDataRecord) {
                            onStatusUpdate(0.12f, "Reading scanlines from scanner (${elapsedSec}s)$attemptTag...")
                        } else {
                            onStatusUpdate(0.08f, "Warming up scanner carriage (${elapsedSec}s)$attemptTag...")
                        }
                    }
                )
                reader = streamReader
                val pagesDelivered = streamPages(streamReader, resolvedDevInfo, area) {
                    sendCommand(connection, endpoints.bulkOut, CMD_NEXT_PAGE)
                }

                if (pagesDelivered == 0) {
                    throw ScannerException(
                        if (config.source.isAdf) {
                            "No document found in the ADF feeder."
                        } else {
                            "Scan finished with 0 decoded lines."
                        }
                    )
                }

                return@withContext pagesDelivered
            } catch (e: CancellationException) {
                if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
                throw ScanCancelledException("Scan coroutine cancelled")
            } catch (e: ScanCancelledException) {
                if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
                throw e
            } catch (e: UnsupportedScannerException) {
                if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
                throw e
            } catch (e: ScannerException) {
                if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
                val canRetry = attempt < MAX_SCAN_ATTEMPTS &&
                    reader?.hasReceivedDataRecord != true &&
                    isRecoverableUsbSessionError(e)
                if (canRetry) {
                    Log.w(
                        TAG,
                        "USB scan attempt $attempt/$MAX_SCAN_ATTEMPTS hit recoverable error (${e.message}); will reset & retry in ${RETRY_DELAY_MS / 1000}s"
                    )
                    lastRecoverableError = e
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
                val wrapped = ScannerException("USB scan error: ${e.message ?: e::class.java.simpleName}", e)
                val canRetry = attempt < MAX_SCAN_ATTEMPTS && reader?.hasReceivedDataRecord != true
                if (canRetry) {
                    Log.w(TAG, "USB scan attempt $attempt/$MAX_SCAN_ATTEMPTS threw ${e::class.java.simpleName}; will reset & retry in ${RETRY_DELAY_MS / 1000}s")
                    lastRecoverableError = wrapped
                } else {
                    throw wrapped
                }
            } finally {
                // Strictly match Brother brscan4 teardown:
                //   BREQ_GET_CLOSE (wIndex = 0) -> releaseInterface -> close -> ~2s firmware cooldown.
                // Never call setInterface(0) between close and release — it locks BCOMMAND_RETURN (0x8000).
                if (sessionOpened) {
                    runCatching { closeVendorSession(connection) }
                }
                if (claimed) {
                    runCatching { connection.releaseInterface(endpoints.usbInterface) }
                }
                runCatching { connection.close() }

                if (sessionOpened) {
                    lastUsbSessionClosedAtMs = System.currentTimeMillis()
                    withContext(NonCancellable) {
                        delay(CLOSE_COOLDOWN_MS)
                    }
                }
            }

            // Visible 4-second recovery countdown before next attempt so Brother carriage & USB state machine fully reset
            val nextAttempt = attempt + 1
            var waitLeft = RETRY_DELAY_MS
            var lastReportedSec = -1
            while (waitLeft > 0L) {
                checkCancelled()
                val secLeft = ((waitLeft + 999L) / 1000L).toInt().coerceAtLeast(1)
                if (secLeft != lastReportedSec) {
                    lastReportedSec = secLeft
                    onStatusUpdate(
                        0.03f,
                        "Scanner timed out — retrying in ${secLeft}s (attempt $nextAttempt/$MAX_SCAN_ATTEMPTS)..."
                    )
                }
                val step = min(waitLeft, 250L)
                delay(step)
                waitLeft -= step
            }
        }

        throw (lastRecoverableError ?: ScannerException("USB scan failed after $MAX_SCAN_ATTEMPTS attempts."))
    }

    // -------------------------------------------------------------------------
    // USB Session Open / Close & Low-Level Packet-Aligned Transfers
    // -------------------------------------------------------------------------

    /**
     * Guards against opening a new USB session before the previous session's 2s firmware
     * close cooldown has elapsed. Normally a no-op (0ms) because teardown already awaits
     * [CLOSE_COOLDOWN_MS] before releasing the hardware lock.
     */
    private suspend fun ensureInterScanCooldown(
        onStatusUpdate: ((Float, String) -> Unit)? = null
    ) {
        val lastClosed = lastUsbSessionClosedAtMs
        if (lastClosed <= 0L) return
        val elapsed = System.currentTimeMillis() - lastClosed
        val remaining = CLOSE_COOLDOWN_MS - elapsed
        if (remaining > 0L) {
            Log.d(TAG, "Waiting ${remaining}ms remaining USB close cooldown")
            var left = remaining
            var lastSec = -1
            while (left > 0L) {
                checkCancelled()
                val sec = ((left + 999L) / 1000L).toInt().coerceAtLeast(1)
                if (sec != lastSec) {
                    lastSec = sec
                    onStatusUpdate?.invoke(0.02f, "Resetting scanner carriage (${sec}s)...")
                }
                val step = min(left, 250L)
                delay(step)
                left -= step
            }
        }
    }

    private fun isRecoverableUsbSessionError(e: ScannerException): Boolean {
        val msg = e.message.orEmpty()
        if (msg.contains("0xC2") || msg.contains("0xC3") || msg.contains("0xC4")) return false
        if (msg.contains("ADF is not supported", ignoreCase = true)) return false
        if (msg.contains("USB permission not granted", ignoreCase = true)) return false
        if (msg.contains("No document found in the ADF", ignoreCase = true)) return false
        return true
    }

    private suspend fun checkCancelled() {
        currentCoroutineContext().ensureActive()
        if (cancelRequested.get()) {
            throw ScanCancelledException()
        }
    }

    private fun clearEndpointHalt(connection: UsbDeviceConnection, endpointAddress: Int) {
        val rc = connection.controlTransfer(
            REQ_TYPE_CLEAR_ENDPOINT,
            REQ_CLEAR_FEATURE,
            FEATURE_ENDPOINT_HALT,
            endpointAddress,
            null,
            0,
            SHORT_TIMEOUT_MS
        )
        Log.d(TAG, "CLEAR_FEATURE(ENDPOINT_HALT) ep=0x${endpointAddress.toString(16)} rc=$rc")
    }

    private fun openVendorSession(connection: UsbDeviceConnection) {
        val response = ByteArray(BREQ_GET_LENGTH)
        val backoffMs = longArrayOf(300L, 450L, 600L, 800L, 1_000L, 1_200L, 1_200L, 1_200L)
        var lastRc = -1
        var lastParam = 0

        for (attempt in 0 until OPEN_RETRY_CNT) {
            if (cancelRequested.get()) throw ScanCancelledException()
            response.fill(0)
            val transferred = connection.controlTransfer(
                BREQ_TYPE,
                BREQ_GET_OPEN,
                BCOMMAND_SCANNER,
                0, // wIndex MUST be 0 per brother_devaccs.c
                response,
                BREQ_GET_LENGTH,
                SHORT_TIMEOUT_MS
            )
            lastRc = transferred
            Log.d(TAG, "BREQ_GET_OPEN attempt=${attempt + 1}/$OPEN_RETRY_CNT rc=$transferred data=${response.toHex()}")

            if (transferred < BREQ_GET_LENGTH) {
                if (attempt + 1 < OPEN_RETRY_CNT) {
                    Thread.sleep(backoffMs[attempt.coerceAtMost(backoffMs.lastIndex)])
                }
                continue
            }

            val descLen = response[0].toInt() and 0xFF
            val descType = response[1].toInt() and 0xFF
            val cmdId = response[2].toInt() and 0xFF
            val param = (response[3].toInt() and 0xFF) or ((response[4].toInt() and 0xFF) shl 8)
            lastParam = param

            if (descLen != BREQ_GET_LENGTH || descType != BDESC_TYPE || cmdId != BREQ_GET_OPEN) {
                throw UnsupportedScannerException(
                    "Scanner not supported: unexpected brscan4 descriptor (${response.toHex()})."
                )
            }
            if ((param and BCOMMAND_RETURN) != 0) {
                Log.w(
                    TAG,
                    "BREQ_GET_OPEN returned BCOMMAND_RETURN (0x${param.toString(16)}) on attempt ${attempt + 1}; sending BREQ_GET_CLOSE and retrying"
                )
                runCatching { closeVendorSession(connection) }
                if (attempt + 1 < OPEN_RETRY_CNT) {
                    Thread.sleep(1_000L)
                    continue
                }
                throw ScannerException(
                    "Scanner firmware busy/stuck in BCOMMAND_RETURN (0x${param.toString(16)}). Wait a few seconds or power-cycle the scanner."
                )
            }
            if (param != BCOMMAND_SCANNER) {
                throw UnsupportedScannerException(
                    "Scanner not supported: unexpected BREQ_GET_OPEN param (0x${param.toString(16)})."
                )
            }
            return
        }

        if ((lastParam and BCOMMAND_RETURN) != 0) {
            throw ScannerException(
                "Scanner firmware busy in BCOMMAND_RETURN (0x${lastParam.toString(16)}). Please retry."
            )
        }
        throw ScannerException(
            "BREQ_GET_OPEN failed or timed out (rc=$lastRc). Scanner may be busy or resetting."
        )
    }

    private fun closeVendorSession(connection: UsbDeviceConnection) {
        val response = ByteArray(BREQ_GET_LENGTH)
        for (attempt in 0 until RETRY_CNT) {
            val rc = connection.controlTransfer(
                BREQ_TYPE,
                BREQ_GET_CLOSE,
                BCOMMAND_SCANNER,
                0, // wIndex MUST be 0 per brother_devaccs.c
                response,
                BREQ_GET_LENGTH,
                SHORT_TIMEOUT_MS
            )
            if (rc >= 0) {
                Log.d(TAG, "BREQ_GET_CLOSE rc=$rc data=${response.toHex()}")
                return
            }
            Thread.sleep(100L)
        }
        Log.w(TAG, "BREQ_GET_CLOSE failed after $RETRY_CNT retries")
    }

    /**
     * Recovery drain from OpenDevice(): clears any unread bytes left in the scanner FIFO.
     * On the first stale chunk, sends `ESC Q` to kick the firmware back to idle.
     */
    private fun drainStaleInputOnOpen(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint
    ) {
        val scratch = ByteArray(ANDROID_MAX_BULK_READ)
        var firstStale = true
        val hardDeadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < hardDeadline) {
            if (cancelRequested.get()) throw ScanCancelledException()
            val read = bulkReadAligned(connection, bulkIn, scratch, 0, scratch.size, 400)
            if (read <= 0) break
            Log.w(TAG, "Open recovery drained $read stale bytes")
            if (firstStale) {
                runCatching { sendCommand(connection, bulkOut, CMD_QUERY_DEV_INFO) }
                firstStale = false
            }
            Thread.sleep(30L)
        }
    }

    /**
     * Sends `ESC R` (no trailing LF) and drains remaining bulk-IN bytes until 1 second of silence
     * (`ReadTrash` in brother_scanner.c) so aborted scan data does not poison the next scan.
     */
    private fun abortAndDrain(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        bulkOut: UsbEndpoint
    ) {
        runCatching {
            val cancelCmd = byteArrayOf(ESC, 'R'.code.toByte())
            connection.bulkTransfer(bulkOut, cancelCmd, cancelCmd.size, SHORT_TIMEOUT_MS)
            Log.d(TAG, "Sent ESC R cancel command")
        }

        val trash = ByteArray(ANDROID_MAX_BULK_READ)
        var lastDataAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - lastDataAt < 1_000L) {
            val n = bulkReadAligned(connection, bulkIn, trash, 0, trash.size, 300)
            if (n > 0) {
                lastDataAt = System.currentTimeMillis()
            } else {
                Thread.sleep(30)
            }
        }
    }

    private fun sendCommand(connection: UsbDeviceConnection, bulkOut: UsbEndpoint, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val chunkSize = min(data.size - offset, bulkOut.maxPacketSize.coerceAtLeast(64))
            var sent = -1
            for (attempt in 0 until RETRY_CNT) {
                sent = connection.bulkTransfer(bulkOut, data, offset, chunkSize, COMMAND_TIMEOUT_MS)
                if (sent >= 0) break
            }
            if (sent < 0) {
                throw ScannerException("USB Bulk OUT transfer failed while sending scanner command (offset=$offset)")
            }
            offset += sent
        }
    }

    /**
     * Rounds reads >= wMaxPacketSize down to whole USB packets (matching `brscan_usb_bulk_read`)
     * to prevent host-controller packet overrun and brscan4 record desynchronization.
     */
    private fun bulkReadAligned(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int
    ): Int {
        val mps = bulkIn.maxPacketSize.coerceAtLeast(1)
        var reqSize = min(length, ANDROID_MAX_BULK_READ)
        if (reqSize >= mps) {
            reqSize -= (reqSize % mps)
        }
        if (reqSize <= 0) return 0
        return connection.bulkTransfer(bulkIn, buffer, offset, reqSize, timeoutMs)
    }

    private fun readNonFixedResponse(
        connection: UsbDeviceConnection,
        bulkIn: UsbEndpoint,
        maxLen: Int,
        timeoutMs: Int
    ): ByteArray {
        val buf = ByteArray(maxLen)
        val deadline = System.currentTimeMillis() + timeoutMs
        var zeroReads = 0

        while (System.currentTimeMillis() < deadline) {
            if (cancelRequested.get()) throw ScanCancelledException()
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50)
            val n = bulkReadAligned(connection, bulkIn, buf, 0, maxLen, min(remaining, 1_500))
            if (n > 0) {
                return buf.copyOf(n)
            }
            zeroReads++
            val backoff = if (zeroReads == 1) 20L else 200L
            Thread.sleep(min(backoff, max(1L, deadline - System.currentTimeMillis())))
        }
        return ByteArray(0)
    }

    // -------------------------------------------------------------------------
    // ESC I Query, Geometry Calculation & ESC X Start Command
    // -------------------------------------------------------------------------

    private fun buildScanQueryCommand(config: ScanConfig): ByteArray {
        val cmd = "\u001BI\nR=${config.dpi},${config.dpi}\nM=${config.mode.protocolCode}\n"
        return cmd.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(PACKET_EOF.toByte())
    }

    private data class DeviceScanInfo(
        val devResoX: Int,
        val devResoY: Int,
        val scanSource: Int,
        val maxWidth01Mm: Int,
        val maxPixels: Int,
        val maxHeight01Mm: Int,
        val maxRaster: Int
    )

    private data class ScanAreaDots(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Leniently parses the `ESC I` response (`resX,resY,scanSource,maxWidthMm,maxPixels,maxHeightMm,maxRaster`)
     * and applies brscan4 (`BRSANESUFFIX == 2`) resolution clamping.
     * Stops scanning the CSV at the first `0x00`, `\n`, `\r`, or `0x80` terminator byte.
     * Returns `null` if 7 valid integers cannot be parsed.
     */
    private fun parseDeviceScanInfoOrNull(response: ByteArray, config: ScanConfig): DeviceScanInfo? {
        if (response.size < 3) return null

        var start = 2
        while (start < response.size && response[start] == 0.toByte()) start++
        var end = start
        while (end < response.size) {
            val b = response[end].toInt() and 0xFF
            if (b == 0x00 || b == 0x0A || b == 0x0D || b == PACKET_EOF) break
            end++
        }
        if (end <= start) return null

        val csv = String(response, start, end - start, StandardCharsets.US_ASCII)
        Log.d(TAG, "ESC I CSV response: [$csv]")

        val tokens = csv.split(',').mapNotNull { token ->
            token.trim().takeWhile { it.isDigit() }.toIntOrNull()
        }
        if (tokens.size < 7) return null

        var resoX = tokens[0]
        var resoY = tokens[1]
        val scanSource = tokens[2]
        val maxWidth01Mm = tokens[3] * 10
        var maxPixels = tokens[4]
        val maxHeight01Mm = tokens[5] * 10
        var maxRaster = tokens[6]

        var timesX = 1
        var timesY = 1
        if (resoX > 600) {
            timesX = resoX / 600
            resoX = 600
        }
        if (resoY > 600) {
            timesY = resoY / 600
            resoY = 600
        }
        if (resoX > 400 && config.mode == ScanMode.GRAYSCALE_8BIT) {
            timesX = resoX / 300
            resoX = 300
            timesY = resoY / 300
            resoY = 300
        }

        maxPixels /= timesX
        maxRaster /= timesY

        if (resoX <= 0 || resoY <= 0 || maxWidth01Mm <= 0 || maxPixels <= 0 || maxRaster <= 0) {
            return null
        }

        return DeviceScanInfo(
            devResoX = resoX,
            devResoY = resoY,
            scanSource = scanSource,
            maxWidth01Mm = maxWidth01Mm,
            maxPixels = maxPixels,
            maxHeight01Mm = maxHeight01Mm,
            maxRaster = maxRaster
        )
    }

    /**
     * Safe clamped flatbed defaults used if `ESC I` fails to parse 7 valid integers.
     */
    private fun fallbackDeviceScanInfo(config: ScanConfig): DeviceScanInfo {
        var resoX = config.dpi.coerceAtLeast(100)
        var resoY = config.dpi.coerceAtLeast(100)
        if (resoX > 600) resoX = 600
        if (resoY > 600) resoY = 600
        if (resoX > 400 && config.mode == ScanMode.GRAYSCALE_8BIT) {
            resoX = 300
            resoY = 300
        }
        val maxWidth01Mm = 2159
        val maxHeight01Mm = 2970
        val maxPixels = (maxWidth01Mm.toLong() * resoX / 254L).toInt()
        val maxRaster = (maxHeight01Mm.toLong() * resoY / 254L).toInt()

        return DeviceScanInfo(
            devResoX = resoX,
            devResoY = resoY,
            scanSource = 2,
            maxWidth01Mm = maxWidth01Mm,
            maxPixels = maxPixels,
            maxHeight01Mm = maxHeight01Mm,
            maxRaster = maxRaster
        )
    }

    /**
     * Converts requested [PageSize] into device dots, clamps to scanner limits,
     * and aligns horizontal coordinates to 16-dot boundaries (`GetDeviceScanArea`).
     */
    private fun computeAlignedDeviceScanArea(
        config: ScanConfig,
        devInfo: DeviceScanInfo
    ): ScanAreaDots {
        var left = 0
        var right = (config.pageSize.width01Mm.toLong() * devInfo.devResoX / 254L).toInt()
        val top = 0
        var bottom = (config.pageSize.height01Mm.toLong() * devInfo.devResoY / 254L).toInt()

        if (right > devInfo.maxPixels) right = devInfo.maxPixels
        if (right - left < 16) {
            right = left + 16
            if (right > devInfo.maxPixels) {
                right = devInfo.maxPixels
                left = max(0, devInfo.maxPixels - 16)
            }
        }
        if (bottom > devInfo.maxRaster) bottom = devInfo.maxRaster

        // 16-dot boundary alignment (0-7 -> 0, 8-15 -> 16)
        left = (left + 8) and 0xFFF0
        right = (right + 8) and 0xFFF0
        if (right > devInfo.maxPixels) {
            right = devInfo.maxPixels and 0xFFF0
        }
        if (right <= left) {
            right = (left + 16).coerceAtMost(devInfo.maxPixels)
        }

        return ScanAreaDots(left = left, top = top, right = right, bottom = bottom)
    }

    private fun buildScanStartCommand(
        config: ScanConfig,
        devInfo: DeviceScanInfo,
        area: ScanAreaDots
    ): ByteArray {
        val brightnessPct = config.brightness.coerceIn(0, 100)
        val contrastPct = config.contrast.coerceIn(0, 100)

        val sb = StringBuilder()
            .append("\u001BX\n")
            .append("R=").append(devInfo.devResoX).append(',').append(devInfo.devResoY).append('\n')
            .append("M=").append(config.mode.protocolCode).append('\n')
            .append("C=").append(config.compression.protocolCode).append('\n')
            .append("B=").append(brightnessPct).append('\n')
            .append("N=").append(contrastPct).append('\n')
            .append("U=OFF\n")
            .append("P=OFF\n")

        sb.append("A=")
            .append(area.left).append(',')
            .append(area.top).append(',')
            .append(area.right).append(',')
            .append(area.bottom).append('\n')

        // brscan4 ADF direction parameter AFTER A=...: D=SIN (Simplex) or D=DUP (Duplex); never emitted for Flatbed
        if (config.source != ScanSource.FLATBED) {
            config.source.protocolCommand?.let { adfParam ->
                sb.append(adfParam).append('\n')
            }
        }

        Log.d(TAG, "ESC X command:\n${sb.toString().replace("\u001B", "<ESC>")}")
        return sb.toString().toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(PACKET_EOF.toByte())
    }

    // -------------------------------------------------------------------------
    // brscan4 Record Framing & Image Assembly (Flatbed + Multi-page ADF)
    // -------------------------------------------------------------------------

    private data class Brscan4Record(
        val header: Int,
        val isBoundaryStatus: Boolean,
        val payload: ByteArray
    )

    private data class PageDecodeResult(
        val bitmap: Bitmap?,
        val hasMorePages: Boolean
    )

    /**
     * Buffered reader implementing `Brscan4ReadCache` and `brscan4_read_next_record`
     * from `brother_brscan4.c`.
     */
    private inner class Brscan4StreamReader(
        private val connection: UsbDeviceConnection,
        private val bulkIn: UsbEndpoint,
        private val onHeartbeat: ((elapsedSec: Int) -> Unit)? = null
    ) {
        private val cache = ByteArray(BRSCAN4_READ_CACHE_SIZE)
        private var cacheLen = 0
        @Volatile
        var hasReceivedDataRecord: Boolean = false
            private set

        private fun readExact(dst: ByteArray, dstOffset: Int, want: Int, timeoutMs: Int): Int {
            if (want == 0) return 0
            if (want > BRSCAN4_MAX_REQUEST_SIZE) {
                throw ScannerException("Requested brscan4 frame size $want exceeds limit")
            }

            while (cacheLen < want) {
                if (cancelRequested.get()) throw ScanCancelledException()
                val freeSpace = BRSCAN4_READ_CACHE_SIZE - cacheLen
                val got = readChunkWithTimeout(cache, cacheLen, freeSpace, timeoutMs)
                if (got > 0) {
                    cacheLen += got
                } else {
                    break
                }
            }

            if (cacheLen >= want) {
                System.arraycopy(cache, 0, dst, dstOffset, want)
                val rem = cacheLen - want
                if (rem > 0) {
                    System.arraycopy(cache, want, cache, 0, rem)
                }
                cacheLen = rem
                return want
            }

            if (cacheLen > 0) {
                val partial = cacheLen
                System.arraycopy(cache, 0, dst, dstOffset, partial)
                cacheLen = 0
                return partial
            }

            return 0
        }

        private fun readChunkWithTimeout(dst: ByteArray, offset: Int, maxLen: Int, timeoutMs: Int): Int {
            val startedAt = System.currentTimeMillis()
            val deadline = startedAt + timeoutMs
            var lastHeartbeatAt = startedAt
            var zeroReads = 0

            while (System.currentTimeMillis() < deadline) {
                if (cancelRequested.get()) throw ScanCancelledException()
                val now = System.currentTimeMillis()
                if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
                    lastHeartbeatAt = now
                    val elapsedSec = ((now - startedAt) / 1000L).toInt().coerceAtLeast(1)
                    onHeartbeat?.invoke(elapsedSec)
                }
                val remaining = (deadline - now).toInt().coerceAtLeast(50)
                val n = bulkReadAligned(connection, bulkIn, dst, offset, maxLen, min(remaining, 2_000))
                if (n > 0) return n
                zeroReads++
                val sleepMs = if (zeroReads == 1) 20L else 200L
                Thread.sleep(min(sleepMs, max(1L, deadline - System.currentTimeMillis())))
            }
            return 0
        }

        fun readNextRecord(adfMode: Boolean, timeoutMs: Int = DATA_TIMEOUT_MS): Brscan4Record? {
            val hdrBuf = ByteArray(1)
            if (readExact(hdrBuf, 0, 1, timeoutMs) != 1) return null
            val header = hdrBuf[0].toInt() and 0xFF

            // Boundary status markers:
            //   0x80 = Page End / Job EOF
            //   0x81 = Next Page / page boundary (ADF mode only; 0x81..0xC1 can be data headers on flatbed)
            //   > 0xC1 = Hardware status codes (0xC2 No Doc, 0xC3 Jam, 0xC4 Cover Open, 0xE3 Timeout)
            if (header == PACKET_EOF || (adfMode && header == PACKET_NEXT_PAGE) || header > 0xC1) {
                return Brscan4Record(header = header, isBoundaryStatus = true, payload = ByteArray(0))
            }

            // 2-byte LE wrapper length
            val lenBuf = ByteArray(2)
            if (readExact(lenBuf, 0, 2, timeoutMs) != 2) return null
            val wrapperLen = (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8)
            if (wrapperLen > MAX_BRSCAN4_WRAPPER_LEN) {
                throw ScannerException(
                    "Invalid brscan4 wrapper length $wrapperLen (hdr=0x${header.toString(16)}) — stream out of sync"
                )
            }

            // Skip variable-length wrapper + read 2-byte LE payload length
            val wrapperAndLen = ByteArray(wrapperLen + 2)
            if (readExact(wrapperAndLen, 0, wrapperAndLen.size, timeoutMs) != wrapperAndLen.size) return null
            val payloadLen =
                (wrapperAndLen[wrapperLen].toInt() and 0xFF) or
                    ((wrapperAndLen[wrapperLen + 1].toInt() and 0xFF) shl 8)

            if (payloadLen > BRSCAN4_MAX_REQUEST_SIZE) {
                throw ScannerException(
                    "Invalid brscan4 payload length $payloadLen (hdr=0x${header.toString(16)}, wrapperLen=$wrapperLen)"
                )
            }

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0 && readExact(payload, 0, payloadLen, timeoutMs) != payloadLen) {
                return null
            }

            if (payloadLen > 0) {
                hasReceivedDataRecord = true
            }
            return Brscan4Record(header = header, isBoundaryStatus = false, payload = payload)
        }
    }

    private suspend fun receiveSinglePage(
        reader: Brscan4StreamReader,
        config: ScanConfig,
        area: ScanAreaDots,
        pageNumber: Int,
        alreadyScannedPages: Int,
        onProgress: (line: Int, total: Int) -> Unit
    ): PageDecodeResult {
        val width = area.width
        val maxHeight = area.height
        val monoBytesPerLine = (width + 7) / 8

        val pixels = IntArray(width * maxHeight) { Color.WHITE }
        val grayLut = if (config.mode == ScanMode.GRAYSCALE_8BIT) {
            buildGrayLut(
                brightnessDelta = config.brightness.coerceIn(0, 100) - 50,
                contrastDelta = config.contrast.coerceIn(0, 100) - 50
            )
        } else {
            null
        }

        // Plane accumulators: plane0 = Cr/R (0x04), plane1 = Y/G (0x08), plane2 = Cb/B (0x0C)
        val plane0Buf = ByteArrayOutputStream()
        val plane1Buf = ByteArrayOutputStream()
        val plane2Buf = ByteArrayOutputStream()
        val interleavedBuf = ByteArrayOutputStream()
        val monoBuf = ByteArrayOutputStream()
        val jpegBuf = ByteArrayOutputStream()

        var isJpegStream = false
        var currentLine = 0
        var lastReportedLine = -1
        var recordCount = 0
        var hasMorePages = false

        while (config.source.isAdf || currentLine < maxHeight || isJpegStream) {
            checkCancelled()

            val readTimeout = if (!isJpegStream && currentLine >= maxHeight) EOF_DRAIN_TIMEOUT_MS else DATA_TIMEOUT_MS
            val record = reader.readNextRecord(adfMode = config.source.isAdf, timeoutMs = readTimeout)
            if (record == null) {
                if (currentLine > 0 || jpegBuf.size() > 0) {
                    Log.w(TAG, "Page $pageNumber stream ended early at line $currentLine/$maxHeight — finalizing")
                    break
                }
                if (config.source.isAdf && alreadyScannedPages > 0) {
                    return PageDecodeResult(bitmap = null, hasMorePages = false)
                }
                throw ScannerException("Timed out waiting for scan data from scanner.")
            }

            if (record.isBoundaryStatus) {
                when (record.header) {
                    PACKET_EOF -> {
                        Log.d(TAG, "Page $pageNumber End (0x80) at line $currentLine/$maxHeight")
                        hasMorePages = false
                        break
                    }
                    PACKET_NEXT_PAGE -> {
                        Log.d(TAG, "Page $pageNumber Next Page (0x81) at line $currentLine/$maxHeight")
                        hasMorePages = true
                        break
                    }
                    STATUS_CANCEL_TIMEOUT -> {
                        if (cancelRequested.get()) throw ScanCancelledException()
                        throw ScannerException("Scanner cancelled scan due to timeout (0xE3).")
                    }
                    STATUS_NO_DOC -> {
                        if (config.source.isAdf && (alreadyScannedPages > 0 || currentLine > 0 || jpegBuf.size() > 0)) {
                            Log.d(TAG, "ADF feeder empty (0xC2) after $alreadyScannedPages pages")
                            hasMorePages = false
                            break
                        }
                        throw ScannerException("No document detected in feeder (0xC2).")
                    }
                    STATUS_DOC_JAM -> throw ScannerException("Document jam reported by scanner (0xC3).")
                    STATUS_COVER_OPEN -> throw ScannerException("Scanner cover is open (0xC4).")
                    else -> {
                        if (currentLine > 0) {
                            Log.w(
                                TAG,
                                "Status 0x${record.header.toString(16)} after $currentLine lines — finalizing"
                            )
                            hasMorePages = false
                            break
                        }
                        throw ScannerException(
                            "Scanner hardware error (status=0x${record.header.toString(16).uppercase()})."
                        )
                    }
                }
            }

            if (record.payload.isEmpty()) continue

            if (recordCount < 6) {
                Log.d(
                    TAG,
                    "Page[$pageNumber] Record[$recordCount] hdr=0x${record.header.toString(16)} " +
                        "len=${record.payload.size} head=${record.payload.take(6).toByteArray().toHex()}"
                )
            }
            recordCount++

            // Detect embedded JPEG stream if first non-empty color record begins with JPEG SOI (FF D8)
            if (config.mode == ScanMode.COLOR_24BIT && recordCount == 1 &&
                record.payload.size >= 2 &&
                (record.payload[0].toInt() and 0xFF) == 0xFF &&
                (record.payload[1].toInt() and 0xFF) == 0xD8
            ) {
                isJpegStream = true
                Log.d(TAG, "Detected embedded JPEG color stream")
            }

            if (isJpegStream) {
                jpegBuf.write(record.payload)
                continue
            }

            // Keep consuming records until 0x80/0x81 is drained, without decoding extra rows
            if (currentLine >= maxHeight) continue

            // Bitfields from SetupImgLineProc (brother_scanner.c):
            //   bits 0..1 == 2 -> PackBits compressed
            //   bits 2..4 -> channel type (0x00 Mono, 0x04 Cr/R, 0x08 Y/G, 0x0C Cb/B, 0x10 RGB, 0x14 BGR)
            val isPackBits = (record.header and 0x03) == 2
            val channelTag = record.header and 0x1C
            val decoded = if (isPackBits) {
                PackBitsDecoder.decode(record.payload)
            } else {
                record.payload
            }

            when (config.mode) {
                ScanMode.MONOCHROME_1BIT -> {
                    monoBuf.write(decoded)
                    currentLine = flushMonochromeScanlines(
                        buffer = monoBuf,
                        pixels = pixels,
                        width = width,
                        bytesPerLine = monoBytesPerLine,
                        maxHeight = maxHeight,
                        startLine = currentLine
                    )
                }

                ScanMode.GRAYSCALE_8BIT -> {
                    monoBuf.write(decoded)
                    currentLine = flushGrayscaleScanlines(
                        buffer = monoBuf,
                        lut = grayLut!!,
                        pixels = pixels,
                        width = width,
                        maxHeight = maxHeight,
                        startLine = currentLine
                    )
                }

                ScanMode.COLOR_24BIT -> {
                    when (channelTag) {
                        CHANNEL_CR_OR_R -> plane0Buf.write(decoded)
                        CHANNEL_Y_OR_G -> plane1Buf.write(decoded)
                        CHANNEL_CB_OR_B -> plane2Buf.write(decoded)
                        CHANNEL_INTERLEAVED_RGB -> {
                            interleavedBuf.write(decoded)
                            currentLine = flushInterleavedColorScanlines(
                                buffer = interleavedBuf,
                                pixels = pixels,
                                width = width,
                                maxHeight = maxHeight,
                                startLine = currentLine,
                                bgr = false
                            )
                        }
                        CHANNEL_INTERLEAVED_BGR -> {
                            interleavedBuf.write(decoded)
                            currentLine = flushInterleavedColorScanlines(
                                buffer = interleavedBuf,
                                pixels = pixels,
                                width = width,
                                maxHeight = maxHeight,
                                startLine = currentLine,
                                bgr = true
                            )
                        }
                        else -> {
                            Log.v(TAG, "Ignoring unrecognized color channel tag 0x${channelTag.toString(16)}")
                        }
                    }

                    if (plane0Buf.size() >= width && plane1Buf.size() >= width && plane2Buf.size() >= width) {
                        currentLine = flushPlanarColorScanlines(
                            plane0 = plane0Buf,
                            plane1 = plane1Buf,
                            plane2 = plane2Buf,
                            isYCbCr = config.colorIsYCbCr,
                            pixels = pixels,
                            width = width,
                            maxHeight = maxHeight,
                            startLine = currentLine
                        )
                    }
                }
            }

            if (currentLine - lastReportedLine >= PROGRESS_STEP_LINES || currentLine >= maxHeight) {
                lastReportedLine = currentLine
                onProgress(currentLine.coerceAtMost(maxHeight), maxHeight)
            }
        }

        if (isJpegStream && jpegBuf.size() > 0) {
            val jpegBytes = jpegBuf.toByteArray()
            val decodedBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: throw ScannerException("Failed to decode JPEG image stream (${jpegBytes.size} bytes)")
            onProgress(decodedBitmap.height, decodedBitmap.height)
            return PageDecodeResult(bitmap = decodedBitmap, hasMorePages = hasMorePages)
        }

        if (currentLine == 0) {
            return PageDecodeResult(bitmap = null, hasMorePages = false)
        }

        val finalHeight = if (config.source.isAdf) currentLine.coerceIn(1, maxHeight) else maxHeight
        onProgress(finalHeight, finalHeight)
        val bitmap = Bitmap.createBitmap(pixels, width, finalHeight, Bitmap.Config.ARGB_8888)
        return PageDecodeResult(bitmap = bitmap, hasMorePages = hasMorePages)
    }

    private data class RawPageResult(
        val page: RawScannedPage?,
        val hasMorePages: Boolean,
    )

    private suspend fun receiveSinglePageRawToFile(
        reader: Brscan4StreamReader,
        config: ScanConfig,
        devInfo: DeviceScanInfo,
        area: ScanAreaDots,
        outFile: File,
        pageNumber: Int,
        alreadyScannedPages: Int,
        onProgress: (line: Int, total: Int) -> Unit,
    ): RawPageResult {
        val width = area.width
        val maxHeight = area.height
        val monoBytesPerLine = (width + 7) / 8
        val grayLut = if (config.mode == ScanMode.GRAYSCALE_8BIT && (config.brightness != 50 || config.contrast != 50)) {
            buildGrayLut(
                brightnessDelta = config.brightness.coerceIn(0, 100) - 50,
                contrastDelta = config.contrast.coerceIn(0, 100) - 50
            )
        } else {
            null
        }

        val plane0Buf = ByteArrayOutputStream()
        val plane1Buf = ByteArrayOutputStream()
        val plane2Buf = ByteArrayOutputStream()
        val interleavedBuf = ByteArrayOutputStream()
        val monoBuf = ByteArrayOutputStream()
        val rgbRowBuf = if (config.mode == ScanMode.COLOR_24BIT) ByteArray(width * 3) else ByteArray(0)
        val grayRowBuf = if (grayLut != null) ByteArray(width) else ByteArray(0)

        var isJpegStream = false
        var jpegBytesWritten = 0L
        var currentLine = 0
        var lastReportedLine = -1
        var recordCount = 0
        var hasMorePages = false

        try {
            BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { rawOut ->
                while (config.source.isAdf || currentLine < maxHeight || isJpegStream) {
                    checkCancelled()

                    val readTimeout = if (!isJpegStream && currentLine >= maxHeight) EOF_DRAIN_TIMEOUT_MS else DATA_TIMEOUT_MS
                    val record = reader.readNextRecord(adfMode = config.source.isAdf, timeoutMs = readTimeout)
                    if (record == null) {
                        if (currentLine > 0 || jpegBytesWritten > 0L) {
                            Log.w(TAG, "Raw page $pageNumber stream ended early at line $currentLine/$maxHeight — finalizing")
                            break
                        }
                        if (config.source.isAdf && alreadyScannedPages > 0) {
                            runCatching { outFile.delete() }
                            return RawPageResult(page = null, hasMorePages = false)
                        }
                        throw ScannerException("Timed out waiting for scan data from scanner.")
                    }

                    if (record.isBoundaryStatus) {
                        when (record.header) {
                            PACKET_EOF -> {
                                Log.d(TAG, "Raw page $pageNumber End (0x80) at line $currentLine/$maxHeight")
                                hasMorePages = false
                                break
                            }
                            PACKET_NEXT_PAGE -> {
                                Log.d(TAG, "Raw page $pageNumber Next Page (0x81) at line $currentLine/$maxHeight")
                                hasMorePages = true
                                break
                            }
                            STATUS_CANCEL_TIMEOUT -> {
                                if (cancelRequested.get()) throw ScanCancelledException()
                                throw ScannerException("Scanner cancelled scan due to timeout (0xE3).")
                            }
                            STATUS_NO_DOC -> {
                                if (config.source.isAdf && (alreadyScannedPages > 0 || currentLine > 0 || jpegBytesWritten > 0L)) {
                                    Log.d(TAG, "ADF feeder empty (0xC2) after $alreadyScannedPages pages")
                                    hasMorePages = false
                                    break
                                }
                                throw ScannerException("No document detected in feeder (0xC2).")
                            }
                            STATUS_DOC_JAM -> throw ScannerException("Document jam reported by scanner (0xC3).")
                            STATUS_COVER_OPEN -> throw ScannerException("Scanner cover is open (0xC4).")
                            else -> {
                                if (currentLine > 0 || jpegBytesWritten > 0L) {
                                    Log.w(
                                        TAG,
                                        "Status 0x${record.header.toString(16)} after $currentLine lines — finalizing raw page"
                                    )
                                    hasMorePages = false
                                    break
                                }
                                throw ScannerException(
                                    "Scanner hardware error (status=0x${record.header.toString(16).uppercase()})."
                                )
                            }
                        }
                    }

                    if (record.payload.isEmpty()) continue
                    recordCount++

                    // Pass through native hardware JPEG stream untouched (no decode/re-encode on host)
                    if (config.mode == ScanMode.COLOR_24BIT && recordCount == 1 &&
                        record.payload.size >= 2 &&
                        (record.payload[0].toInt() and 0xFF) == 0xFF &&
                        (record.payload[1].toInt() and 0xFF) == 0xD8
                    ) {
                        isJpegStream = true
                        Log.d(TAG, "Detected native hardware JPEG stream — forwarding raw bytes")
                    }

                    if (isJpegStream) {
                        rawOut.write(record.payload)
                        jpegBytesWritten += record.payload.size
                        continue
                    }

                    // Keep consuming records until 0x80/0x81 is drained, without decoding/writing extra rows
                    if (currentLine >= maxHeight) continue

                    val isPackBits = (record.header and 0x03) == 2
                    val channelTag = record.header and 0x1C
                    val decoded = if (isPackBits) {
                        PackBitsDecoder.decode(record.payload)
                    } else {
                        record.payload
                    }

                    when (config.mode) {
                        ScanMode.MONOCHROME_1BIT -> {
                            monoBuf.write(decoded)
                            currentLine = flushRawContiguousScanlines(
                                buffer = monoBuf,
                                out = rawOut,
                                bytesPerLine = monoBytesPerLine,
                                maxHeight = maxHeight,
                                startLine = currentLine,
                                lut = null,
                                lutRowBuf = grayRowBuf,
                            )
                        }

                        ScanMode.GRAYSCALE_8BIT -> {
                            monoBuf.write(decoded)
                            currentLine = flushRawContiguousScanlines(
                                buffer = monoBuf,
                                out = rawOut,
                                bytesPerLine = width,
                                maxHeight = maxHeight,
                                startLine = currentLine,
                                lut = grayLut,
                                lutRowBuf = grayRowBuf,
                            )
                        }

                        ScanMode.COLOR_24BIT -> {
                            when (channelTag) {
                                CHANNEL_CR_OR_R -> plane0Buf.write(decoded)
                                CHANNEL_Y_OR_G -> plane1Buf.write(decoded)
                                CHANNEL_CB_OR_B -> plane2Buf.write(decoded)
                                CHANNEL_INTERLEAVED_RGB -> {
                                    interleavedBuf.write(decoded)
                                    currentLine = flushInterleavedColorScanlinesRaw(
                                        buffer = interleavedBuf,
                                        out = rawOut,
                                        rowBuf = rgbRowBuf,
                                        width = width,
                                        maxHeight = maxHeight,
                                        startLine = currentLine,
                                        bgr = false,
                                    )
                                }
                                CHANNEL_INTERLEAVED_BGR -> {
                                    interleavedBuf.write(decoded)
                                    currentLine = flushInterleavedColorScanlinesRaw(
                                        buffer = interleavedBuf,
                                        out = rawOut,
                                        rowBuf = rgbRowBuf,
                                        width = width,
                                        maxHeight = maxHeight,
                                        startLine = currentLine,
                                        bgr = true,
                                    )
                                }
                                else -> {
                                    Log.v(TAG, "Ignoring unrecognized color channel tag 0x${channelTag.toString(16)}")
                                }
                            }

                            if (plane0Buf.size() >= width && plane1Buf.size() >= width && plane2Buf.size() >= width) {
                                currentLine = flushPlanarColorScanlinesRaw(
                                    plane0 = plane0Buf,
                                    plane1 = plane1Buf,
                                    plane2 = plane2Buf,
                                    isYCbCr = config.colorIsYCbCr,
                                    out = rawOut,
                                    rowBuf = rgbRowBuf,
                                    width = width,
                                    maxHeight = maxHeight,
                                    startLine = currentLine,
                                )
                            }
                        }
                    }

                    if (currentLine - lastReportedLine >= PROGRESS_STEP_LINES || currentLine >= maxHeight) {
                        lastReportedLine = currentLine
                        onProgress(currentLine.coerceAtMost(maxHeight), maxHeight)
                    }
                }
                rawOut.flush()
            }
        } catch (t: Throwable) {
            runCatching { outFile.delete() }
            throw t
        }

        if (isJpegStream && jpegBytesWritten > 0L) {
            onProgress(maxHeight, maxHeight)
            return RawPageResult(
                page = RawScannedPage(
                    file = outFile,
                    format = RAW_FORMAT_JPEG,
                    widthPx = width,
                    heightPx = maxHeight,
                    bytesPerLine = width * 3,
                    deviceDpiX = devInfo.devResoX,
                    deviceDpiY = devInfo.devResoY,
                    requestedDpi = config.dpi,
                    mode = config.mode,
                ),
                hasMorePages = hasMorePages,
            )
        }

        if (currentLine == 0) {
            runCatching { outFile.delete() }
            return RawPageResult(page = null, hasMorePages = false)
        }

        val bytesPerLine = when (config.mode) {
            ScanMode.COLOR_24BIT -> width * 3
            ScanMode.GRAYSCALE_8BIT -> width
            ScanMode.MONOCHROME_1BIT -> monoBytesPerLine
        }
        val rawFormat = when (config.mode) {
            ScanMode.COLOR_24BIT -> RAW_FORMAT_RGB24
            ScanMode.GRAYSCALE_8BIT -> RAW_FORMAT_GRAY8
            ScanMode.MONOCHROME_1BIT -> RAW_FORMAT_MONO1
        }
        onProgress(currentLine, currentLine)
        return RawPageResult(
            page = RawScannedPage(
                file = outFile,
                format = rawFormat,
                widthPx = width,
                heightPx = currentLine,
                bytesPerLine = bytesPerLine,
                deviceDpiX = devInfo.devResoX,
                deviceDpiY = devInfo.devResoY,
                requestedDpi = config.dpi,
                mode = config.mode,
            ),
            hasMorePages = hasMorePages,
        )
    }

    private fun scaleBitmapIfNeeded(
        rawBitmap: Bitmap,
        config: ScanConfig,
        devInfo: DeviceScanInfo,
        area: ScanAreaDots
    ): Bitmap {
        if (devInfo.devResoX == config.dpi && devInfo.devResoY == config.dpi) {
            return rawBitmap
        }
        var targetWidth = (rawBitmap.width.toLong() * config.dpi / devInfo.devResoX).toInt()
        var targetHeight = (rawBitmap.height.toLong() * config.dpi / devInfo.devResoY).toInt()
        if (targetWidth <= 0 || targetHeight <= 0 ||
            (rawBitmap.width == targetWidth && rawBitmap.height == targetHeight)
        ) {
            return rawBitmap
        }

        // Prevent OutOfMemoryError on ultra-high DPI (1200 / 2400 DPI) full-page scans by
        // capping scaled output dimensions to a safe fraction of the JVM heap budget.
        val neededBytes = targetWidth.toLong() * targetHeight.toLong() * 4L
        val maxSafeBytes = (Runtime.getRuntime().maxMemory() / 3L)
            .coerceAtMost(96L * 1024L * 1024L)
        if (neededBytes > maxSafeBytes && neededBytes > 0L) {
            val scaleFactor = kotlin.math.sqrt(maxSafeBytes.toDouble() / neededBytes.toDouble())
            targetWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(rawBitmap.width)
            targetHeight = (targetHeight * scaleFactor).toInt().coerceAtLeast(rawBitmap.height)
            if (targetWidth == rawBitmap.width && targetHeight == rawBitmap.height) {
                return rawBitmap
            }
        }

        return try {
            val scaled = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
            if (scaled !== rawBitmap) rawBitmap.recycle()
            scaled
        } catch (oom: OutOfMemoryError) {
            Log.w(
                TAG,
                "Insufficient memory to upscale ${rawBitmap.width}x${rawBitmap.height} to " +
                    "${targetWidth}x$targetHeight; returning native optical scan",
                oom
            )
            rawBitmap
        }
    }

    // -------------------------------------------------------------------------
    // Scanline Decoders
    // -------------------------------------------------------------------------

    private fun flushPlanarColorScanlines(
        plane0: ByteArrayOutputStream,
        plane1: ByteArrayOutputStream,
        plane2: ByteArrayOutputStream,
        isYCbCr: Boolean,
        pixels: IntArray,
        width: Int,
        maxHeight: Int,
        startLine: Int
    ): Int {
        val availableLines = minOf(
            plane0.size() / width,
            plane1.size() / width,
            plane2.size() / width,
            maxHeight - startLine
        )
        if (availableLines <= 0) return startLine

        val p0 = plane0.toByteArray()
        val p1 = plane1.toByteArray()
        val p2 = plane2.toByteArray()
        val consumed = availableLines * width

        var line = startLine
        var srcIdx = 0
        repeat(availableLines) {
            var dstIdx = line * width
            if (isYCbCr) {
                // brscan4_process_color_direct: 0x04 = Cr, 0x08 = Y, 0x0C = Cb
                repeat(width) {
                    val cr = (p0[srcIdx].toInt() and 0xFF) - 128
                    val y = p1[srcIdx].toInt() and 0xFF
                    val cb = (p2[srcIdx].toInt() and 0xFF) - 128

                    val r = (y + 1.402 * cr).toInt().coerceIn(0, 255)
                    val g = (y - 0.34414 * cb - 0.71414 * cr).toInt().coerceIn(0, 255)
                    val b = (y + 1.772 * cb).toInt().coerceIn(0, 255)

                    pixels[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    srcIdx++
                    dstIdx++
                }
            } else {
                repeat(width) {
                    val r = p0[srcIdx].toInt() and 0xFF
                    val g = p1[srcIdx].toInt() and 0xFF
                    val b = p2[srcIdx].toInt() and 0xFF
                    pixels[dstIdx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    srcIdx++
                    dstIdx++
                }
            }
            line++
        }

        retainRemainder(plane0, p0, consumed)
        retainRemainder(plane1, p1, consumed)
        retainRemainder(plane2, p2, consumed)
        return line
    }

    private fun flushInterleavedColorScanlines(
        buffer: ByteArrayOutputStream,
        pixels: IntArray,
        width: Int,
        maxHeight: Int,
        startLine: Int,
        bgr: Boolean
    ): Int {
        val bytesPerLine = width * 3
        val availableLines = min(buffer.size() / bytesPerLine, maxHeight - startLine)
        if (availableLines <= 0) return startLine

        val bytes = buffer.toByteArray()
        val consumed = availableLines * bytesPerLine
        var line = startLine
        var srcIdx = 0
        repeat(availableLines) {
            var dstIdx = line * width
            repeat(width) {
                val c0 = bytes[srcIdx].toInt() and 0xFF
                val c1 = bytes[srcIdx + 1].toInt() and 0xFF
                val c2 = bytes[srcIdx + 2].toInt() and 0xFF
                val r = if (bgr) c2 else c0
                val b = if (bgr) c0 else c2
                pixels[dstIdx] = (0xFF shl 24) or (r shl 16) or (c1 shl 8) or b
                srcIdx += 3
                dstIdx++
            }
            line++
        }

        retainRemainder(buffer, bytes, consumed)
        return line
    }

    private fun flushGrayscaleScanlines(
        buffer: ByteArrayOutputStream,
        lut: IntArray,
        pixels: IntArray,
        width: Int,
        maxHeight: Int,
        startLine: Int
    ): Int {
        val availableLines = min(buffer.size() / width, maxHeight - startLine)
        if (availableLines <= 0) return startLine

        val bytes = buffer.toByteArray()
        val consumed = availableLines * width
        var line = startLine
        var srcIdx = 0
        repeat(availableLines) {
            var dstIdx = line * width
            repeat(width) {
                val gray = lut[bytes[srcIdx].toInt() and 0xFF]
                pixels[dstIdx] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                srcIdx++
                dstIdx++
            }
            line++
        }

        retainRemainder(buffer, bytes, consumed)
        return line
    }

    private fun flushMonochromeScanlines(
        buffer: ByteArrayOutputStream,
        pixels: IntArray,
        width: Int,
        bytesPerLine: Int,
        maxHeight: Int,
        startLine: Int
    ): Int {
        val availableLines = min(buffer.size() / bytesPerLine, maxHeight - startLine)
        if (availableLines <= 0) return startLine

        val bytes = buffer.toByteArray()
        val consumed = availableLines * bytesPerLine
        var line = startLine
        var srcLineOffset = 0
        repeat(availableLines) {
            val dstRowOffset = line * width
            for (x in 0 until width) {
                val byteVal = bytes[srcLineOffset + (x ushr 3)].toInt() and 0xFF
                val bit = (byteVal ushr (7 - (x and 7))) and 1
                // Brother 1-bit TEXT raster: 1 = black, 0 = white
                pixels[dstRowOffset + x] = if (bit == 1) Color.BLACK else Color.WHITE
            }
            srcLineOffset += bytesPerLine
            line++
        }

        retainRemainder(buffer, bytes, consumed)
        return line
    }

    private fun flushPlanarColorScanlinesRaw(
        plane0: ByteArrayOutputStream,
        plane1: ByteArrayOutputStream,
        plane2: ByteArrayOutputStream,
        isYCbCr: Boolean,
        out: BufferedOutputStream,
        rowBuf: ByteArray,
        width: Int,
        maxHeight: Int,
        startLine: Int,
    ): Int {
        val availableLines = minOf(
            plane0.size() / width,
            plane1.size() / width,
            plane2.size() / width,
            maxHeight - startLine,
        )
        if (availableLines <= 0) return startLine

        val p0 = plane0.toByteArray()
        val p1 = plane1.toByteArray()
        val p2 = plane2.toByteArray()
        val consumed = availableLines * width

        var line = startLine
        var srcIdx = 0
        repeat(availableLines) {
            var dstIdx = 0
            if (isYCbCr) {
                repeat(width) {
                    val cr = (p0[srcIdx].toInt() and 0xFF) - 128
                    val y = p1[srcIdx].toInt() and 0xFF
                    val cb = (p2[srcIdx].toInt() and 0xFF) - 128

                    rowBuf[dstIdx] = (y + 1.402 * cr).toInt().coerceIn(0, 255).toByte()
                    rowBuf[dstIdx + 1] = (y - 0.34414 * cb - 0.71414 * cr).toInt().coerceIn(0, 255).toByte()
                    rowBuf[dstIdx + 2] = (y + 1.772 * cb).toInt().coerceIn(0, 255).toByte()
                    srcIdx++
                    dstIdx += 3
                }
            } else {
                repeat(width) {
                    rowBuf[dstIdx] = p0[srcIdx]
                    rowBuf[dstIdx + 1] = p1[srcIdx]
                    rowBuf[dstIdx + 2] = p2[srcIdx]
                    srcIdx++
                    dstIdx += 3
                }
            }
            out.write(rowBuf, 0, width * 3)
            line++
        }

        retainRemainder(plane0, p0, consumed)
        retainRemainder(plane1, p1, consumed)
        retainRemainder(plane2, p2, consumed)
        return line
    }

    private fun flushInterleavedColorScanlinesRaw(
        buffer: ByteArrayOutputStream,
        out: BufferedOutputStream,
        rowBuf: ByteArray,
        width: Int,
        maxHeight: Int,
        startLine: Int,
        bgr: Boolean,
    ): Int {
        val bytesPerLine = width * 3
        val availableLines = min(buffer.size() / bytesPerLine, maxHeight - startLine)
        if (availableLines <= 0) return startLine

        val bytes = buffer.toByteArray()
        val consumed = availableLines * bytesPerLine
        if (!bgr) {
            out.write(bytes, 0, consumed)
        } else {
            var srcIdx = 0
            repeat(availableLines) {
                var dstIdx = 0
                repeat(width) {
                    rowBuf[dstIdx] = bytes[srcIdx + 2]
                    rowBuf[dstIdx + 1] = bytes[srcIdx + 1]
                    rowBuf[dstIdx + 2] = bytes[srcIdx]
                    srcIdx += 3
                    dstIdx += 3
                }
                out.write(rowBuf, 0, bytesPerLine)
            }
        }

        retainRemainder(buffer, bytes, consumed)
        return startLine + availableLines
    }

    private fun flushRawContiguousScanlines(
        buffer: ByteArrayOutputStream,
        out: BufferedOutputStream,
        bytesPerLine: Int,
        maxHeight: Int,
        startLine: Int,
        lut: IntArray?,
        lutRowBuf: ByteArray,
    ): Int {
        val availableLines = min(buffer.size() / bytesPerLine, maxHeight - startLine)
        if (availableLines <= 0) return startLine

        val bytes = buffer.toByteArray()
        val consumed = availableLines * bytesPerLine
        if (lut == null) {
            out.write(bytes, 0, consumed)
        } else {
            var srcIdx = 0
            repeat(availableLines) {
                for (x in 0 until bytesPerLine) {
                    lutRowBuf[x] = lut[bytes[srcIdx++].toInt() and 0xFF].toByte()
                }
                out.write(lutRowBuf, 0, bytesPerLine)
            }
        }

        retainRemainder(buffer, bytes, consumed)
        return startLine + availableLines
    }

    private fun retainRemainder(out: ByteArrayOutputStream, full: ByteArray, consumed: Int) {
        out.reset()
        if (consumed < full.size) {
            out.write(full, consumed, full.size - consumed)
        }
    }

    /** Exact brightness/contrast LUT ported from `SetupGrayAdjust` (brother_cmatch.c). */
    private fun buildGrayLut(brightnessDelta: Int, contrastDelta: Int): IntArray {
        val b = brightnessDelta.coerceIn(-50, 50)
        val c = contrastDelta.coerceIn(-50, 50)
        return IntArray(256) { idx ->
            var v = idx
            if (c != 0) {
                val gamma = c shl 2
                v = if (gamma >= 0) {
                    ((255 * (v - 128)) / (255 - gamma)) + 128
                } else {
                    (((255 + gamma) * (v - 128)) / 255) + 128
                }
            }
            if (b != 0) {
                v += (b shl 1)
            }
            v.coerceIn(0, 255)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    private data class ScannerEndpoints(
        val usbInterface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint
    )

    /**
     * Standard Apple/TIFF PackBits (`C=RLENGTH`) decoder used by Brother brscan4 scanners.
     */
    object PackBitsDecoder {
        fun decode(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(input.size * 2)
            var i = 0
            while (i < input.size) {
                val n = input[i++].toInt() // Signed byte: -128..127
                when {
                    n >= 0 -> {
                        val count = min(n + 1, input.size - i)
                        if (count > 0) {
                            out.write(input, i, count)
                            i += count
                        }
                    }
                    n != -128 -> {
                        if (i < input.size) {
                            val value = input[i++].toInt() and 0xFF
                            val repeatCount = 1 - n
                            repeat(repeatCount) { out.write(value) }
                        }
                    }
                    // n == -128 (0x80): no-op
                }
            }
            return out.toByteArray()
        }
    }

    companion object {
        private const val TAG = "BrotherBrscan4"

        const val BROTHER_VENDOR_ID = 0x04F9
        const val DCP_L2520D_PRODUCT_ID = 0x0324

        const val RAW_FORMAT_RGB24 = "raw-rgb24"
        const val RAW_FORMAT_GRAY8 = "raw-gray8"
        const val RAW_FORMAT_MONO1 = "raw-mono1"
        const val RAW_FORMAT_JPEG = "raw-jpeg"

        private const val MM_PER_INCH = 25.4
        private const val ESC: Byte = 0x1B
        private const val LF: Byte = 0x0A

        // Brother vendor control transfer constants (brother_devaccs.h)
        private const val BREQ_TYPE = 0xC0
        private const val BREQ_GET_OPEN = 0x01
        private const val BREQ_GET_CLOSE = 0x02
        private const val BREQ_GET_LENGTH = 5
        private const val BDESC_TYPE = 0x10
        private const val BCOMMAND_SCANNER = 0x02
        private const val BCOMMAND_RETURN = 0x8000
        private const val RETRY_CNT = 5
        private const val OPEN_RETRY_CNT = 8

        // Standard USB CLEAR_FEATURE(ENDPOINT_HALT)
        private const val REQ_TYPE_CLEAR_ENDPOINT = 0x02
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val FEATURE_ENDPOINT_HALT = 0x00

        // Buffer & cache limits (brother_brscan4.h)
        private const val ANDROID_MAX_BULK_READ = 16_384 // Multiple of both 64 and 512 bytes
        private const val BRSCAN4_READ_CACHE_SIZE = 0x20400
        private const val BRSCAN4_MAX_REQUEST_SIZE = 0x20200
        private const val MAX_BRSCAN4_WRAPPER_LEN = 512

        // brscan4 channel bitmasks (header & 0x1C)
        private const val CHANNEL_CR_OR_R = 0x04
        private const val CHANNEL_Y_OR_G = 0x08
        private const val CHANNEL_CB_OR_B = 0x0C
        private const val CHANNEL_INTERLEAVED_RGB = 0x10
        private const val CHANNEL_INTERLEAVED_BGR = 0x14

        // brscan4 boundary status codes
        private const val PACKET_EOF = 0x80
        private const val PACKET_NEXT_PAGE = 0x81
        private const val STATUS_NO_DOC = 0xC2
        private const val STATUS_DOC_JAM = 0xC3
        private const val STATUS_COVER_OPEN = 0xC4
        private const val STATUS_CANCEL_TIMEOUT = 0xE3

        private val CMD_QUERY_DEV_INFO = byteArrayOf(ESC, 'Q'.code.toByte(), LF, PACKET_EOF.toByte())
        private val CMD_NEXT_PAGE = byteArrayOf(ESC, 'X'.code.toByte(), LF, PACKET_EOF.toByte())

        private const val SHORT_TIMEOUT_MS = 2_000
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 20_000
        private const val EOF_DRAIN_TIMEOUT_MS = 1_500
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val MAX_SCAN_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 4_000L
        private const val CLOSE_COOLDOWN_MS = 2_000L
        private const val PROGRESS_STEP_LINES = 16

        @Volatile
        private var lastUsbSessionClosedAtMs: Long = 0L

        val SUPPORTED_DPI_OPTIONS = listOf(100, 150, 200, 300, 600, 1200, 2400)

        /**
         * Finds a connected Brother USB scanner (`VID 0x04F9`) exposing a vendor-specific bulk interface,
         * prioritizing exact DCP-L2520D (`VID 0x04F9`, `PID 0x0324`) first.
         */
        fun findBrotherScanner(usbManager: UsbManager): UsbDevice? {
            val devices = usbManager.deviceList.values
            return devices.firstOrNull {
                it.vendorId == BROTHER_VENDOR_ID &&
                    it.productId == DCP_L2520D_PRODUCT_ID &&
                    locateScannerEndpoints(it) != null
            } ?: devices.firstOrNull {
                it.vendorId == BROTHER_VENDOR_ID && locateScannerEndpoints(it) != null
            }
        }

        /** Alias retained for backward compatibility with existing callers. */
        fun findDcpL2520d(usbManager: UsbManager): UsbDevice? = findBrotherScanner(usbManager)

        /**
         * Finds any attached USB printer/imaging/scanner peripheral that is NOT a compatible
         * Brother brscan4 USB scanner (e.g. non-Brother brands or eSCL-only models without 0xFF bulk interface).
         */
        fun findUnsupportedUsbPeripheral(usbManager: UsbManager): UsbDevice? {
            return usbManager.deviceList.values.firstOrNull { device ->
                val isSupportedBrother = device.vendorId == BROTHER_VENDOR_ID &&
                    locateScannerEndpoints(device) != null
                if (isSupportedBrother) return@firstOrNull false

                // Treat Brother devices without a 0xFF bulk interface, or any USB Printer (Class 7)
                // / Still Imaging / Scanner (Class 6) device from another vendor as an unsupported scanner.
                if (device.vendorId == BROTHER_VENDOR_ID) return@firstOrNull true
                hasPrinterOrImagingClass(device)
            }
        }

        private fun hasPrinterOrImagingClass(device: UsbDevice): Boolean {
            if (device.deviceClass == UsbConstants.USB_CLASS_PRINTER ||
                device.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE
            ) {
                return true
            }
            for (i in 0 until device.interfaceCount) {
                val cls = device.getInterface(i).interfaceClass
                if (cls == UsbConstants.USB_CLASS_PRINTER || cls == UsbConstants.USB_CLASS_STILL_IMAGE) {
                    return true
                }
            }
            return false
        }

        fun inspectDevice(device: UsbDevice): UsbScannerHardwareInfo {
            val endpoints = locateScannerEndpoints(device)
            val product = runCatching { device.productName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: if (device.vendorId == BROTHER_VENDOR_ID) "Brother USB Scanner" else "USB Device"
            val manufacturer = runCatching { device.manufacturerName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: if (device.vendorId == BROTHER_VENDOR_ID) "Brother Industries, Ltd." else "Unknown Manufacturer"

            return UsbScannerHardwareInfo(
                deviceName = device.deviceName,
                productName = product,
                manufacturerName = manufacturer,
                vendorId = device.vendorId,
                productId = device.productId,
                isBrotherVendor = device.vendorId == BROTHER_VENDOR_ID,
                scannerInterfaceId = endpoints?.usbInterface?.id,
                bulkInAddress = endpoints?.bulkIn?.address,
                bulkOutAddress = endpoints?.bulkOut?.address,
                maxPacketSize = endpoints?.bulkIn?.maxPacketSize
            )
        }

        private fun locateScannerEndpoints(device: UsbDevice): ScannerEndpoints? {
            var fallback: ScannerEndpoints? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var bulkIn: UsbEndpoint? = null
                var bulkOut: UsbEndpoint? = null

                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            bulkIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            bulkOut = ep
                        }
                    }
                }

                if (bulkIn != null && bulkOut != null) {
                    val candidate = ScannerEndpoints(iface, bulkIn, bulkOut)
                    if (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                        return candidate
                    }
                    if (iface.id == 1 && fallback == null) {
                        fallback = candidate
                    }
                }
            }
            return fallback
        }
    }
}
