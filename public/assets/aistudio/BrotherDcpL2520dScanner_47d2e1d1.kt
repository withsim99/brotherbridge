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
import java.io.ByteArrayOutputStream
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
            if (inquiryBytes.size < 4) {
                throw UnsupportedScannerException(
                    "Scanner not supported: device did not respond to brscan4 inquiry (ESC Q)."
                )
            }
            val protocolByte = inquiryBytes[3].toInt() and 0xFF
            Log.d(
                TAG,
                "Probe ESC Q (${inquiryBytes.size}B): protocol=$protocolByte dpiCap=${inquiryBytes.getOrNull(14)}"
            )

            // Verify ESC I geometry & scan source capability response
            val queryCmd = buildScanQueryCommand(ScanConfig(dpi = 300, mode = ScanMode.COLOR_24BIT))
            sendCommand(connection, endpoints.bulkOut, queryCmd)
            val infoBytes = readNonFixedResponse(
                connection = connection,
                bulkIn = endpoints.bulkIn,
                maxLen = 356,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )

            val parsedInfo = parseDeviceScanInfoStrict(infoBytes, ScanConfig())
                ?: throw UnsupportedScannerException(
                    "Scanner not supported: device does not use the Brother brscan4 protocol."
                )

            // brscan4 ESC I token[2] scanSource bitmask/enum:
            //   bit 0 (1) = ADF present, bit 1 (2) = Flatbed present, bit 2 (4) = ADF Duplex
            //   1 = ADF only, 2 = Flatbed only, 3 = Flatbed + ADF, >=4 (or bit 2 set) = Duplex ADF
            val srcMask = parsedInfo.scanSource
            val supportsAdf = (srcMask and SCAN_SOURCE_ADF_BIT) != 0 || srcMask == 1 || srcMask >= 3
            val supportsFlatbed = (srcMask and SCAN_SOURCE_FLATBED_BIT) != 0 || srcMask == 0 || srcMask == 2 || srcMask == 3
            val supportsDuplex = (srcMask and SCAN_SOURCE_DUPLEX_BIT) != 0

            Log.d(
                TAG,
                "Probe brscan4 OK: scanSource=$srcMask flatbed=$supportsFlatbed adf=$supportsAdf duplex=$supportsDuplex"
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
            // If BREQ_GET_OPEN descriptor validation failed, treat as non-brscan4 model
            if (e.message?.contains("BREQ_GET_OPEN") == true &&
                e.message?.contains("BCOMMAND_RETURN") != true
            ) {
                throw UnsupportedScannerException(
                    "Scanner not supported: device does not implement the Brother brscan4 protocol."
                )
            }
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
                withContext(NonCancellable) {
                    delay(PROBE_COOLDOWN_MS)
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
        onProgress: (line: Int, total: Int) -> Unit = { _, _ -> }
    ): Bitmap {
        var captured: Bitmap? = null
        scanPages(
            config = config.copy(source = ScanSource.FLATBED),
            onPageProgress = { _, line, total -> onProgress(line, total) },
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
        onPageCaptured: suspend (pageNumber: Int, bitmap: Bitmap) -> Unit
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

        val connection = usbManager.openDevice(device)
            ?: throw ScannerException("Failed to open USB connection. Check OTG cable power.")

        var claimed = false
        var sessionOpened = false
        var scanStarted = false

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

            // 3. Inquiry handshake (ESC Q \n 0x80)
            sendCommand(connection, endpoints.bulkOut, CMD_QUERY_DEV_INFO)
            val inquiryBytes = readNonFixedResponse(
                connection = connection,
                bulkIn = endpoints.bulkIn,
                maxLen = 272,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )
            if (inquiryBytes.isEmpty()) {
                throw UnsupportedScannerException(
                    "Scanner not supported: no response to brscan4 inquiry (ESC Q)."
                )
            }
            Log.d(
                TAG,
                "ESC Q inquiry (${inquiryBytes.size}B): protocol=${inquiryBytes.getOrNull(3)} dpiCap=${inquiryBytes.getOrNull(14)}"
            )

            checkCancelled()

            // 4. Negotiate hardware resolution & scan limits (ESC I \n R=... \n M=... \n 0x80)
            val queryCmd = buildScanQueryCommand(config)
            sendCommand(connection, endpoints.bulkOut, queryCmd)
            val infoBytes = readNonFixedResponse(
                connection = connection,
                bulkIn = endpoints.bulkIn,
                maxLen = 356,
                timeoutMs = HANDSHAKE_TIMEOUT_MS
            )
            val devInfo = parseDeviceScanInfoStrict(infoBytes, config)
                ?: throw UnsupportedScannerException(
                    "Scanner not supported: device does not use the Brother brscan4 protocol."
                )

            if (config.source.isAdf && (devInfo.scanSource and SCAN_SOURCE_ADF_BIT) == 0 && devInfo.scanSource < 3 && devInfo.scanSource != 1) {
                throw ScannerException("ADF is not supported by this scanner model. Switch source to Flatbed.")
            }

            val area = computeAlignedDeviceScanArea(config, devInfo)

            Log.d(
                TAG,
                "Negotiated (${config.source.displayName}): devDPI=${devInfo.devResoX}x${devInfo.devResoY}, " +
                    "max=${devInfo.maxPixels}x${devInfo.maxRaster}, " +
                    "area=${area.left},${area.top},${area.right},${area.bottom} (${area.width}x${area.height})"
            )

            checkCancelled()
            onPageProgress(1, 0, area.height)

            // 5. Trigger flatbed or ADF scan (ESC X ... 0x80)
            val startCmd = buildScanStartCommand(config, devInfo, area)
            sendCommand(connection, endpoints.bulkOut, startCmd)
            scanStarted = true

            // Firmware carriage/feeder warmup pause (matches PageScanStart wait, kept cancellable)
            val warmupSteps = if (config.source.isAdf) 8 else 15
            repeat(warmupSteps) {
                checkCancelled()
                delay(200L)
            }

            // 6. Stream & decode single-page (Flatbed) or multi-page (ADF) brscan4 records
            val reader = Brscan4StreamReader(connection, endpoints.bulkIn)
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

                pageNumber++
                onPageProgress(pageNumber, 0, area.height)
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

            pagesDelivered
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
            throw e
        } catch (e: Exception) {
            if (scanStarted) abortAndDrain(connection, endpoints.bulkIn, endpoints.bulkOut)
            throw ScannerException("USB scan error: ${e.message ?: e::class.java.simpleName}", e)
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
                withContext(NonCancellable) {
                    delay(CLOSE_COOLDOWN_MS)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // USB Session Open / Close & Low-Level Packet-Aligned Transfers
    // -------------------------------------------------------------------------

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
        var transferred = -1
        for (attempt in 0 until RETRY_CNT) {
            transferred = connection.controlTransfer(
                BREQ_TYPE,
                BREQ_GET_OPEN,
                BCOMMAND_SCANNER,
                0, // wIndex MUST be 0 per brother_devaccs.c
                response,
                BREQ_GET_LENGTH,
                SHORT_TIMEOUT_MS
            )
            if (transferred >= 0) break
        }

        Log.d(TAG, "BREQ_GET_OPEN rc=$transferred data=${response.toHex()}")

        if (transferred < BREQ_GET_LENGTH) {
            throw UnsupportedScannerException(
                "Scanner not supported: BREQ_GET_OPEN failed (rc=$transferred). Model may not use brscan4."
            )
        }

        val descLen = response[0].toInt() and 0xFF
        val descType = response[1].toInt() and 0xFF
        val cmdId = response[2].toInt() and 0xFF
        val param = (response[3].toInt() and 0xFF) or ((response[4].toInt() and 0xFF) shl 8)

        if (descLen != BREQ_GET_LENGTH || descType != BDESC_TYPE || cmdId != BREQ_GET_OPEN) {
            throw UnsupportedScannerException(
                "Scanner not supported: unexpected brscan4 descriptor (${response.toHex()})."
            )
        }
        if ((param and BCOMMAND_RETURN) != 0) {
            throw ScannerException(
                "Scanner firmware stuck in BCOMMAND_RETURN (0x${param.toString(16)}). Power-cycle the scanner."
            )
        }
        if (param != BCOMMAND_SCANNER) {
            throw UnsupportedScannerException(
                "Scanner not supported: unexpected BREQ_GET_OPEN param (0x${param.toString(16)})."
            )
        }
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
        while (true) {
            val read = bulkReadAligned(connection, bulkIn, scratch, 0, scratch.size, 400)
            if (read <= 0) break
            Log.w(TAG, "Open recovery drained $read stale bytes")
            if (firstStale) {
                runCatching { sendCommand(connection, bulkOut, CMD_QUERY_DEV_INFO) }
                firstStale = false
            }
            Thread.sleep(30)
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
     * Parses the `ESC I` response strictly (`resX,resY,scanSource,maxWidthMm,maxPixels,maxHeightMm,maxRaster`)
     * and applies brscan4 (`BRSANESUFFIX == 2`) resolution clamping.
     * Returns `null` if the device response does not match `brscan4` format.
     */
    private fun parseDeviceScanInfoStrict(response: ByteArray, config: ScanConfig): DeviceScanInfo? {
        if (response.size < 3) return null

        var start = 2
        while (start < response.size && response[start] == 0.toByte()) start++
        var end = response.size
        while (end > start && (
                response[end - 1] == 0.toByte() ||
                    response[end - 1] == '\n'.code.toByte() ||
                    response[end - 1] == '\r'.code.toByte()
                )
        ) {
            end--
        }

        val csv = String(response, start, end - start, StandardCharsets.US_ASCII)
        Log.d(TAG, "ESC I CSV response: [$csv]")

        val tokens = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
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

        // brscan4 ADF direction parameter: D=SIN (Simplex) or D=DUP (Duplex)
        config.source.protocolCommand?.let { adfParam ->
            sb.append(adfParam).append('\n')
        }

        sb.append("A=")
            .append(area.left).append(',')
            .append(area.top).append(',')
            .append(area.right).append(',')
            .append(area.bottom).append('\n')

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
        private val bulkIn: UsbEndpoint
    ) {
        private val cache = ByteArray(BRSCAN4_READ_CACHE_SIZE)
        private var cacheLen = 0

        private fun readExact(dst: ByteArray, dstOffset: Int, want: Int): Int {
            if (want == 0) return 0
            if (want > BRSCAN4_MAX_REQUEST_SIZE) {
                throw ScannerException("Requested brscan4 frame size $want exceeds limit")
            }

            while (cacheLen < want) {
                if (cancelRequested.get()) throw ScanCancelledException()
                val freeSpace = BRSCAN4_READ_CACHE_SIZE - cacheLen
                val got = readChunkWithTimeout(cache, cacheLen, freeSpace)
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

        private fun readChunkWithTimeout(dst: ByteArray, offset: Int, maxLen: Int): Int {
            val deadline = System.currentTimeMillis() + DATA_TIMEOUT_MS
            var zeroReads = 0

            while (System.currentTimeMillis() < deadline) {
                if (cancelRequested.get()) throw ScanCancelledException()
                val remaining = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50)
                val n = bulkReadAligned(connection, bulkIn, dst, offset, maxLen, min(remaining, 2_000))
                if (n > 0) return n
                zeroReads++
                val sleepMs = if (zeroReads == 1) 20L else 200L
                Thread.sleep(min(sleepMs, max(1L, deadline - System.currentTimeMillis())))
            }
            return 0
        }

        fun readNextRecord(adfMode: Boolean): Brscan4Record? {
            val hdrBuf = ByteArray(1)
            if (readExact(hdrBuf, 0, 1) != 1) return null
            val header = hdrBuf[0].toInt() and 0xFF

            // Boundary status markers:
            //   0x80 = Page End / Job EOF
            //   0x81 = Next Page (ADF continuation when scanning in ADF mode)
            //   > 0xC1 = Hardware status codes (0xC2 No Doc, 0xC3 Jam, 0xC4 Cover Open, 0xE3 Timeout)
            if (header == PACKET_EOF || (adfMode && header == PACKET_NEXT_PAGE) || header > 0xC1) {
                return Brscan4Record(header = header, isBoundaryStatus = true, payload = ByteArray(0))
            }

            // 2-byte LE wrapper length
            val lenBuf = ByteArray(2)
            if (readExact(lenBuf, 0, 2) != 2) return null
            val wrapperLen = (lenBuf[0].toInt() and 0xFF) or ((lenBuf[1].toInt() and 0xFF) shl 8)

            // Skip variable-length wrapper + read 2-byte LE payload length
            val wrapperAndLen = ByteArray(wrapperLen + 2)
            if (readExact(wrapperAndLen, 0, wrapperAndLen.size) != wrapperAndLen.size) return null
            val payloadLen =
                (wrapperAndLen[wrapperLen].toInt() and 0xFF) or
                    ((wrapperAndLen[wrapperLen + 1].toInt() and 0xFF) shl 8)

            if (payloadLen > BRSCAN4_MAX_REQUEST_SIZE) {
                throw ScannerException(
                    "Invalid brscan4 payload length $payloadLen (hdr=0x${header.toString(16)}, wrapperLen=$wrapperLen)"
                )
            }

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0 && readExact(payload, 0, payloadLen) != payloadLen) {
                return null
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

        while (currentLine < maxHeight || isJpegStream) {
            checkCancelled()

            val record = reader.readNextRecord(adfMode = config.source.isAdf)
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

            if (recordCount < 6) {
                Log.d(
                    TAG,
                    "Page[$pageNumber] Record[$recordCount] hdr=0x${record.header.toString(16)} " +
                        "len=${record.payload.size} head=${record.payload.take(6).toByteArray().toHex()}"
                )
            }
            recordCount++

            if (record.payload.isEmpty()) continue

            // Detect embedded JPEG stream if first color record begins with JPEG SOI (FF D8)
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

    private fun scaleBitmapIfNeeded(
        rawBitmap: Bitmap,
        config: ScanConfig,
        devInfo: DeviceScanInfo,
        area: ScanAreaDots
    ): Bitmap {
        if (devInfo.devResoX == config.dpi && devInfo.devResoY == config.dpi) {
            return rawBitmap
        }
        val targetWidth = (rawBitmap.width.toLong() * config.dpi / devInfo.devResoX).toInt()
        val targetHeight = (rawBitmap.height.toLong() * config.dpi / devInfo.devResoY).toInt()
        if (targetWidth <= 0 || targetHeight <= 0 ||
            (rawBitmap.width == targetWidth && rawBitmap.height == targetHeight)
        ) {
            return rawBitmap
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

        // Standard USB CLEAR_FEATURE(ENDPOINT_HALT)
        private const val REQ_TYPE_CLEAR_ENDPOINT = 0x02
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val FEATURE_ENDPOINT_HALT = 0x00

        // Buffer & cache limits (brother_brscan4.h)
        private const val ANDROID_MAX_BULK_READ = 16_384 // Multiple of both 64 and 512 bytes
        private const val BRSCAN4_READ_CACHE_SIZE = 0x20400
        private const val BRSCAN4_MAX_REQUEST_SIZE = 0x20200

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

        // ESC I scanSource bit flags
        private const val SCAN_SOURCE_ADF_BIT = 0x01
        private const val SCAN_SOURCE_FLATBED_BIT = 0x02
        private const val SCAN_SOURCE_DUPLEX_BIT = 0x04

        private val CMD_QUERY_DEV_INFO = byteArrayOf(ESC, 'Q'.code.toByte(), LF, PACKET_EOF.toByte())

        private const val SHORT_TIMEOUT_MS = 2_000
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 20_000
        private const val PROBE_COOLDOWN_MS = 800L
        private const val CLOSE_COOLDOWN_MS = 2_000L
        private const val PROGRESS_STEP_LINES = 16

        val SUPPORTED_DPI_OPTIONS = listOf(100, 150, 200, 300, 600, 1200)

        /**
         * Finds a connected Brother USB scanner (`VID 0x04F9`) exposing a vendor-specific bulk interface.
         */
        fun findBrotherScanner(usbManager: UsbManager): UsbDevice? {
            return usbManager.deviceList.values.firstOrNull {
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
