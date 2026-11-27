package com.example.scanner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface UsbConnectionState {
    data object Disconnected : UsbConnectionState

    data class Unsupported(
        val info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo,
        val reason: String
    ) : UsbConnectionState

    data class PermissionRequired(
        val device: UsbDevice,
        val info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo
    ) : UsbConnectionState

    data class Checking(
        val device: UsbDevice,
        val info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo
    ) : UsbConnectionState

    data class Ready(
        val device: UsbDevice,
        val info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo,
        val capabilities: BrotherDcpL2520dScanner.ScannerCapabilities
    ) : UsbConnectionState
}

/**
 * Monitors Android USB Host events for Brother `brscan4` scanner attachment, detachment,
 * unsupported peripheral detection, and runtime USB permission grants.
 */
class UsbScannerMonitor(
    private val context: Context,
    private val onDeviceAuthorized: (UsbDevice, BrotherDcpL2520dScanner.UsbScannerHardwareInfo) -> Unit = { _, _ -> },
    private val onPermissionDenied: () -> Unit = {}
) {
    val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _connectionState = MutableStateFlow<UsbConnectionState>(UsbConnectionState.Disconnected)
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val actionUsbPermission = "${context.packageName}.SCANNER_USB_PERMISSION"
    private var registered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = extractUsbDevice(intent)
                    if (granted && device != null) {
                        val info = BrotherDcpL2520dScanner.inspectDevice(device)
                        _connectionState.value = UsbConnectionState.Checking(device, info)
                        onDeviceAuthorized(device, info)
                    } else {
                        refreshDevices()
                        onPermissionDenied()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(actionUsbPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
        refreshDevices()
    }

    fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        registered = false
    }

    fun handleAttachIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            refreshDevices()
        }
    }

    fun refreshDevices(): UsbConnectionState {
        val mgr = usbManager ?: run {
            _connectionState.value = UsbConnectionState.Disconnected
            return UsbConnectionState.Disconnected
        }
        val current = _connectionState.value
        val brotherDevice = BrotherDcpL2520dScanner.findBrotherScanner(mgr)

        if (brotherDevice != null) {
            val info = BrotherDcpL2520dScanner.inspectDevice(brotherDevice)
            if (mgr.hasPermission(brotherDevice)) {
                // Keep existing verified Ready or Unsupported state for the same connected device
                if (current is UsbConnectionState.Ready && current.device.deviceId == brotherDevice.deviceId) {
                    return current
                }
                if (current is UsbConnectionState.Checking && current.device.deviceId == brotherDevice.deviceId) {
                    return current
                }
                if (current is UsbConnectionState.Unsupported && current.info.deviceName == brotherDevice.deviceName) {
                    return current
                }
                val checking = UsbConnectionState.Checking(brotherDevice, info)
                _connectionState.value = checking
                onDeviceAuthorized(brotherDevice, info)
                return checking
            } else {
                val perm = UsbConnectionState.PermissionRequired(brotherDevice, info)
                _connectionState.value = perm
                return perm
            }
        }

        val unsupported = BrotherDcpL2520dScanner.findUnsupportedUsbPeripheral(mgr)
        val nextState = if (unsupported != null) {
            val info = BrotherDcpL2520dScanner.inspectDevice(unsupported)
            UsbConnectionState.Unsupported(
                info = info,
                reason = "This model does not support the Brother brscan4 protocol."
            )
        } else {
            UsbConnectionState.Disconnected
        }

        _connectionState.value = nextState
        return nextState
    }

    fun markReady(
        device: UsbDevice,
        info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo,
        capabilities: BrotherDcpL2520dScanner.ScannerCapabilities
    ) {
        _connectionState.value = UsbConnectionState.Ready(device, info, capabilities)
    }

    fun markUnsupported(
        info: BrotherDcpL2520dScanner.UsbScannerHardwareInfo,
        reason: String
    ) {
        _connectionState.value = UsbConnectionState.Unsupported(info, reason)
    }

    fun markProbeFailed() {
        if (_connectionState.value is UsbConnectionState.Checking) {
            _connectionState.value = UsbConnectionState.Disconnected
        }
    }

    fun requestPermission(device: UsbDevice) {
        val mgr = usbManager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(actionUsbPermission).setPackage(context.packageName),
            flags
        )
        runCatching { mgr.requestPermission(device, permissionIntent) }
    }

    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
}
