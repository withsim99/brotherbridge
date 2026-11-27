package com.example.printer.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.model.LogCategory
import com.example.model.PrinterDiagnostics
import com.example.model.UsbConnectionStatus
import com.example.model.UsbPrinterInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.min

class UsbPrinterManager(
    private val context: Context,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.brotherbridge.USB_PERMISSION"
        const val BROTHER_VENDOR_ID = 0x04F9
        private const val BULK_CHUNK_SIZE = 16 * 1024
        private const val BULK_TIMEOUT_MS = 30_000
    }

    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _printerInfo = MutableStateFlow(UsbPrinterInfo())
    val printerInfo: StateFlow<UsbPrinterInfo> = _printerInfo.asStateFlow()

    private var activeDevice: UsbDevice? = null
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = extractUsbDevice(intent)
                    if (granted && device != null) {
                        onLog(LogCategory.USB, "USB permission granted for ${device.productName ?: device.deviceName}", false)
                        attachDevice(device)
                    } else {
                        onLog(LogCategory.USB, "USB permission denied by user", true)
                        scanForPrinters()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    onLog(LogCategory.USB, "USB OTG device attached — scanning printer interfaces", false)
                    scanForPrinters()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = extractUsbDevice(intent)
                    if (detached == null || detached.deviceId == activeDevice?.deviceId) {
                        activeDevice = null
                        _printerInfo.value = UsbPrinterInfo(
                            status = UsbConnectionStatus.DISCONNECTED,
                            statusMessage = "USB printer disconnected — reconnect OTG cable",
                        )
                        onLog(LogCategory.USB, "USB printer detached", true)
                    }
                    scanForPrinters()
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        scanForPrinters()
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        receiverRegistered = false
    }

    fun scanForPrinters(): UsbPrinterInfo {
        val manager = usbManager
        if (manager == null) {
            val info = UsbPrinterInfo(
                status = UsbConnectionStatus.ERROR,
                statusMessage = "USB Host mode unavailable on this device",
            )
            _printerInfo.value = info
            return info
        }

        val candidates = manager.deviceList.values.filter { isPrinterDevice(it) }
        // Prioritize Brother Industries (0x04F9) laser printers
        val chosen = candidates.firstOrNull { it.vendorId == BROTHER_VENDOR_ID } ?: candidates.firstOrNull()

        if (chosen == null) {
            activeDevice = null
            val info = UsbPrinterInfo(
                status = UsbConnectionStatus.DISCONNECTED,
                statusMessage = "No USB printer detected — connect a compatible Brother printer via OTG cable",
            )
            _printerInfo.value = info
            return info
        }

        return attachDevice(chosen)
    }

    fun requestPermissionForActivePrinter() {
        val manager = usbManager ?: return
        val device = activeDevice ?: scanForDeviceOnly() ?: return
        if (manager.hasPermission(device)) {
            attachDevice(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        onLog(LogCategory.USB, "Requesting Android USB host permission for ${device.productName ?: device.deviceName}", false)
        manager.requestPermission(device, pendingIntent)
    }

    private fun scanForDeviceOnly(): UsbDevice? {
        val candidates = usbManager?.deviceList?.values?.filter { isPrinterDevice(it) }.orEmpty()
        return candidates.firstOrNull { it.vendorId == BROTHER_VENDOR_ID } ?: candidates.firstOrNull()
    }

    private fun attachDevice(device: UsbDevice): UsbPrinterInfo {
        activeDevice = device
        val manager = usbManager ?: return _printerInfo.value
        val hasPerm = manager.hasPermission(device)
        val printerIface = findPrinterInterface(device)
        val ieeeId = if (hasPerm && printerIface != null) queryIeee1284DeviceId(manager, device, printerIface) else ""

        val parsedModel = parseIeeeModel(ieeeId)
        val productLabel = when {
            parsedModel.isNotBlank() -> parsedModel
            !device.productName.isNullOrBlank() -> device.productName!!
            device.vendorId == BROTHER_VENDOR_ID -> "Brother Laser Printer"
            else -> "USB Printer"
        }

        val status = if (hasPerm) UsbConnectionStatus.CONNECTED else UsbConnectionStatus.PERMISSION_REQUIRED
        val msg = if (hasPerm) {
            "Ready over USB OTG (${String.format("%04X:%04X", device.vendorId, device.productId)})"
        } else {
            "Printer detected — tap Grant USB Access to enable printing"
        }

        val info = UsbPrinterInfo(
            deviceId = device.deviceId,
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturer = device.manufacturerName ?: if (device.vendorId == BROTHER_VENDOR_ID) "Brother" else "USB",
            productName = productLabel,
            serialNumber = if (hasPerm) runCatching { device.serialNumber ?: "" }.getOrDefault("") else "",
            ieee1284DeviceId = ieeeId,
            interfaceIndex = printerIface?.id ?: 0,
            hasPermission = hasPerm,
            status = status,
            statusMessage = msg,
        )
        _printerInfo.value = info
        onLog(
            LogCategory.USB,
            "Detected $productLabel [${info.vidPidHex}] perm=$hasPerm${if (ieeeId.isNotBlank()) " ID=$ieeeId" else ""}",
            false,
        )
        return info
    }

    /**
     * Opens a direct streaming USB Bulk OUT session so pages can be encoded and sent to the
     * printer in real time with zero intermediate .prn spool files on disk.
     */
    suspend fun <T> withUsbOutputStream(
        onBytesSent: (bytesSent: Long) -> Unit,
        block: suspend (OutputStream) -> T,
    ): Result<Pair<T, Long>> = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext Result.failure(IllegalStateException("USB Host service unavailable"))
        val device = activeDevice ?: scanForDeviceOnly()
            ?: return@withContext Result.failure(IllegalStateException("No USB OTG printer connected. Plug in a compatible Brother printer via OTG cable."))

        if (!manager.hasPermission(device)) {
            requestPermissionForActivePrinter()
            return@withContext Result.failure(IllegalStateException("USB permission required. Approve the USB prompt and retry."))
        }

        val iface = findPrinterInterface(device)
            ?: return@withContext Result.failure(IllegalStateException("No USB Printer Class (0x07) interface found on device"))
        val outEndpoint = findBulkOutEndpoint(iface)
            ?: return@withContext Result.failure(IllegalStateException("No USB Bulk OUT endpoint available on printer"))

        val connection: UsbDeviceConnection = manager.openDevice(device)
            ?: return@withContext Result.failure(IllegalStateException("Failed to open USB connection to ${device.deviceName}"))

        val prevInfo = _printerInfo.value
        _printerInfo.value = prevInfo.copy(
            status = UsbConnectionStatus.PRINTING,
            statusMessage = "Streaming raster pages over USB OTG...",
        )

        var sentTotal = 0L
        var lastReportedBytes = 0L
        try {
            if (!connection.claimInterface(iface, true)) {
                return@withContext Result.failure(IllegalStateException("Could not claim USB printer interface"))
            }

            val rawUsbOut = object : OutputStream() {
                private val singleByte = ByteArray(1)
                private val chunkBuf = ByteArray(BULK_CHUNK_SIZE)

                override fun write(b: Int) {
                    singleByte[0] = b.toByte()
                    write(singleByte, 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (len <= 0) return
                    var offset = off
                    val end = off + len
                    while (offset < end) {
                        val chunkLen = min(end - offset, BULK_CHUNK_SIZE)
                        val transferred = if (offset == 0 && chunkLen == b.size) {
                            connection.bulkTransfer(outEndpoint, b, chunkLen, BULK_TIMEOUT_MS)
                        } else {
                            System.arraycopy(b, offset, chunkBuf, 0, chunkLen)
                            connection.bulkTransfer(outEndpoint, chunkBuf, chunkLen, BULK_TIMEOUT_MS)
                        }
                        if (transferred < 0) {
                            throw IOException("USB bulk transfer failed at byte $sentTotal (code=$transferred)")
                        }
                        if (transferred == 0) {
                            throw IOException("USB bulk transfer stalled at byte $sentTotal")
                        }
                        offset += transferred
                        sentTotal += transferred
                        if (sentTotal - lastReportedBytes >= 64 * 1024) {
                            lastReportedBytes = sentTotal
                            onBytesSent(sentTotal)
                        }
                    }
                }
            }

            val bufferedOut = BufferedOutputStream(rawUsbOut, 64 * 1024)
            val result = block(bufferedOut)
            bufferedOut.flush()
            onBytesSent(sentTotal)
            onLog(LogCategory.USB, "Streamed ${sentTotal / 1024} KB directly to ${prevInfo.displayTitle}", false)
            Result.success(result to sentTotal)
        } catch (t: Throwable) {
            onLog(LogCategory.USB, "USB stream error: ${t.message}", true)
            Result.failure(t)
        } finally {
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
            _printerInfo.value = prevInfo.copy(
                status = UsbConnectionStatus.CONNECTED,
                statusMessage = "Ready over USB OTG (${prevInfo.vidPidHex})",
            )
        }
    }

    /**
     * Streams a complete Brother PJL/PCL/HBP byte stream over USB bulk OUT transfer.
     */
    suspend fun sendPayloadStream(
        inputStream: InputStream,
        totalBytes: Long,
        onProgress: (bytesSent: Long) -> Unit,
    ): Result<Long> = withUsbOutputStream(onBytesSent = onProgress) { usbOut ->
        val buffer = ByteArray(BULK_CHUNK_SIZE)
        while (true) {
            val read = inputStream.read(buffer)
            if (read <= 0) break
            usbOut.write(buffer, 0, read)
        }
    }.map { it.second }

    /**
     * Queries live hardware telemetry from the connected USB printer:
     * 1. IEEE 1284 USB Printer Class `GET_PORT_STATUS` (`0xA1 / 0x01`) for paper empty, online, and error flags
     * 2. IEEE 1284 `GET_DEVICE_ID` (`0xA1 / 0x00`) for supported command languages
     * 3. Bidirectional PJL status inquiry (`INFO ID`, `INFO STATUS`, `INFO PAGECOUNT`, `INFO NEXTCARE`, `INFO VARIABLES`)
     *    over USB Bulk OUT/IN for toner/cartridge status, drum life, paper tray size, and LCD status.
     */
    suspend fun queryPrinterDiagnostics(): Result<PrinterDiagnostics> = withContext(Dispatchers.IO) {
        val manager = usbManager ?: return@withContext Result.failure(IllegalStateException("USB Host service unavailable"))
        val device = activeDevice ?: scanForDeviceOnly()
            ?: return@withContext Result.failure(IllegalStateException("No USB OTG printer connected"))

        if (!manager.hasPermission(device)) {
            return@withContext Result.failure(IllegalStateException("USB permission not granted"))
        }

        val iface = findPrinterInterface(device)
            ?: return@withContext Result.failure(IllegalStateException("No USB Printer Class interface found"))

        val connection = manager.openDevice(device)
            ?: return@withContext Result.failure(IllegalStateException("Could not open USB connection to printer"))

        var claimed = false
        try {
            // 1. Standard USB Printer Class GET_PORT_STATUS (bmRequestType=0xA1, bRequest=0x01)
            val statusBuf = ByteArray(1)
            val statusLen = connection.controlTransfer(0xA1, 0x01, 0, iface.id, statusBuf, 1, 1500)
            val portStatusByte: Int? = if (statusLen >= 1) statusBuf[0].toInt() and 0xFF else null

            // 2. IEEE 1284 GET_DEVICE_ID (reuse cached if already retrieved, else query fresh)
            val ieeeId = _printerInfo.value.ieee1284DeviceId.ifBlank {
                val idBuf = ByteArray(1024)
                val idLen = connection.controlTransfer(0xA1, 0x00, 0, iface.id shl 8, idBuf, idBuf.size, 1500)
                if (idLen > 2) String(idBuf, 2, idLen - 2, Charsets.US_ASCII).trim() else ""
            }

            // 3. Bidirectional PJL query over Bulk OUT / Bulk IN (gracefully skipped if printer lacks Bulk IN)
            val outEndpoint = findBulkOutEndpoint(iface)
            val inEndpoint = findBulkInEndpoint(iface)
            var pjlText = ""

            if (outEndpoint != null && inEndpoint != null) {
                claimed = connection.claimInterface(iface, true)
                if (claimed) {
                    val readBuf = ByteArray(4096)
                    // Drain any stale bytes already sitting in the printer's USB IN FIFO
                    repeat(3) {
                        val drained = connection.bulkTransfer(inEndpoint, readBuf, readBuf.size, 60)
                        if (drained <= 0) return@repeat
                    }

                    val query = PrinterDiagnosticsParser.PJL_DIAGNOSTICS_QUERY
                    val sent = connection.bulkTransfer(outEndpoint, query, query.size, 2000)
                    if (sent == query.size) {
                        val responseOut = ByteArrayOutputStream()
                        val deadlineMs = System.currentTimeMillis() + 2400L
                        var emptyStreak = 0
                        while (System.currentTimeMillis() < deadlineMs) {
                            val timeout = if (responseOut.size() == 0) 650 else 280
                            val n = connection.bulkTransfer(inEndpoint, readBuf, readBuf.size, timeout)
                            if (n > 0) {
                                responseOut.write(readBuf, 0, n)
                                emptyStreak = 0
                                if (responseOut.size() >= 32 * 1024) break
                            } else {
                                emptyStreak++
                                if (responseOut.size() == 0 && emptyStreak >= 1) break
                                if (responseOut.size() > 0 && emptyStreak >= 2) break
                            }
                        }
                        if (responseOut.size() > 0) {
                            pjlText = String(responseOut.toByteArray(), Charsets.US_ASCII)
                        }
                    }
                }
            }

            val parsed = PrinterDiagnosticsParser.parse(
                portStatusByte = portStatusByte,
                ieee1284DeviceId = ieeeId,
                pjlResponseText = pjlText,
            )
            onLog(
                LogCategory.USB,
                "Printer status queried: paper=${parsed.paperAvailability.label}, toner=${parsed.tonerPercent?.let { "$it%" } ?: parsed.tonerStatus.label}${parsed.lcdDisplayMessage?.let { ", display=\"$it\"" } ?: ""}",
                false,
            )
            Result.success(parsed)
        } catch (t: Throwable) {
            onLog(LogCategory.USB, "Printer diagnostics query error: ${t.message}", true)
            Result.failure(t)
        } finally {
            if (claimed) {
                runCatching { connection.releaseInterface(iface) }
            }
            runCatching { connection.close() }
        }
    }

    private fun isPrinterDevice(device: UsbDevice): Boolean {
        if (device.vendorId == BROTHER_VENDOR_ID) return true
        return findPrinterInterface(device) != null
    }

    private fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER && findBulkOutEndpoint(iface) != null) {
                return iface
            }
        }
        // Fallback: any interface with a bulk OUT endpoint on Brother vendor devices
        if (device.vendorId == BROTHER_VENDOR_ID) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (findBulkOutEndpoint(iface) != null) return iface
            }
        }
        return null
    }

    private fun findBulkOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                return ep
            }
        }
        return null
    }

    private fun findBulkInEndpoint(iface: UsbInterface): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN) {
                return ep
            }
        }
        return null
    }

    /**
     * USB Printer Class GET_DEVICE_ID (bmRequestType=0xA1, bRequest=0x00)
     */
    private fun queryIeee1284DeviceId(
        manager: UsbManager,
        device: UsbDevice,
        iface: UsbInterface,
    ): String {
        val conn = manager.openDevice(device) ?: return ""
        return try {
            val buf = ByteArray(1024)
            val len = conn.controlTransfer(0xA1, 0x00, 0, iface.id shl 8, buf, buf.size, 2000)
            if (len > 2) {
                String(buf, 2, len - 2, Charsets.US_ASCII).trim()
            } else {
                ""
            }
        } catch (_: Throwable) {
            ""
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun parseIeeeModel(ieeeId: String): String {
        if (ieeeId.isBlank()) return ""
        val parts = ieeeId.split(';')
        val mdl = parts.firstOrNull { it.startsWith("MDL:", ignoreCase = true) || it.startsWith("MODEL:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        return mdl.orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
