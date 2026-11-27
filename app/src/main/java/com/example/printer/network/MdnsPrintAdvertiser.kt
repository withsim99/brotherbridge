package com.example.printer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.example.model.LogCategory
import com.example.model.MdnsStatus
import com.example.model.PrintServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

data class LocalSubnetInfo(
    val interfaceName: String,
    val localIp: String,
    val prefix24: String,
    val broadcastIp: String,
    val isHotspotInterface: Boolean,
)

object NetworkAddressResolver {

    private fun isIgnoredInterface(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("tun") ||
            lower.startsWith("tap") ||
            lower.startsWith("ppp") ||
            lower.startsWith("dummy") ||
            lower.startsWith("p2p") ||
            lower.startsWith("lo")
    }

    private fun isHotspotInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("ap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("softap") ||
            lower.startsWith("wlan1") ||
            lower.startsWith("wlan2") ||
            lower.startsWith("rndis")
    }

    /**
     * Finds all active local LAN / Wi-Fi / Mobile Hotspot IPv4 `/24` subnets on this device.
     * Used for instant `/24` local network scanning (especially when Mobile Hotspot is ON and
     * Android mDNS multicast does not traverse the AP interface) and UDP broadcast discovery.
     */
    fun getAllLocalLanSubnets(): List<LocalSubnetInfo> {
        return try {
            val result = mutableListOf<LocalSubnetInfo>()
            val seenPrefixes = LinkedHashSet<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val ifaceName = iface.name.lowercase()
                if (isIgnoredInterface(ifaceName)) continue

                for (ifaceAddr in iface.interfaceAddresses) {
                    val addr = ifaceAddr.address
                    if (addr !is Inet4Address || addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val host = addr.hostAddress ?: continue
                    val octets = host.split('.')
                    if (octets.size != 4) continue
                    val prefix24 = "${octets[0]}.${octets[1]}.${octets[2]}"
                    if (!seenPrefixes.add(prefix24)) continue
                    val broadcastIp = ifaceAddr.broadcast?.hostAddress?.takeIf { it.isNotBlank() }
                        ?: "$prefix24.255"
                    result.add(
                        LocalSubnetInfo(
                            interfaceName = ifaceName,
                            localIp = host,
                            prefix24 = prefix24,
                            broadcastIp = broadcastIp,
                            isHotspotInterface = isHotspotInterfaceName(ifaceName),
                        ),
                    )
                }
            }
            // Prioritize hotspot & wlan interfaces first
            result.sortedByDescending { info ->
                when {
                    info.isHotspotInterface -> 3
                    info.interfaceName.startsWith("wlan") || info.interfaceName.startsWith("wifi") -> 2
                    info.interfaceName.startsWith("eth") -> 1
                    else -> 0
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Finds the best local Wi-Fi / Hotspot / LAN IPv4 address for advertising the IPP server.
     */
    fun getLocalWifiIpv4Address(): String? {
        val subnets = getAllLocalLanSubnets()
        if (subnets.isNotEmpty()) {
            return subnets.first().localIp
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<String, String>>()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(iface.name.lowercase() to host)
                    }
                }
            }
            candidates.firstOrNull { (name, _) ->
                name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("eth") ||
                    name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")
            }?.second ?: candidates.firstOrNull { (name, _) ->
                !isIgnoredInterface(name)
            }?.second ?: candidates.firstOrNull()?.second
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns true if Mobile Hotspot (Wi-Fi AP) is currently active.
     */
    fun isHotspotActive(context: Context): Boolean {
        val appCtx = context.applicationContext
        val wifiMgr = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiMgr != null) {
            val apEnabled = runCatching {
                val method = wifiMgr.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                method.invoke(wifiMgr) as? Boolean ?: false
            }.getOrDefault(false)
            if (apEnabled) return true
        }
        return getAllLocalLanSubnets().any { it.isHotspotInterface }
    }

    /**
     * Returns true if Wi-Fi or Mobile Hotspot (AP) is currently enabled on this device.
     */
    fun isWifiOrHotspotEnabled(context: Context): Boolean {
        val appCtx = context.applicationContext
        val wifiMgr = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiMgr != null) {
            if (runCatching { wifiMgr.isWifiEnabled }.getOrDefault(false)) {
                return true
            }
            val apEnabled = runCatching {
                val method = wifiMgr.javaClass.getDeclaredMethod("isWifiApEnabled")
                method.isAccessible = true
                method.invoke(wifiMgr) as? Boolean ?: false
            }.getOrDefault(false)
            if (apEnabled) return true
        }

        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            val activeCap = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            if (activeCap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return true
            }
        }

        return getAllLocalLanSubnets().any { info ->
            info.isHotspotInterface ||
                info.interfaceName.startsWith("wlan") ||
                info.interfaceName.startsWith("wifi") ||
                info.interfaceName.startsWith("eth")
        }
    }
}

/**
 * Lightweight UDP discovery responder (`0.0.0.0:8632`) that complements Bonjour/mDNS.
 *
 * When a host device's screen turns OFF or when devices communicate over Mobile Hotspot, Android
 * Wi-Fi drivers frequently drop or delay multicast mDNS packets. This responder answers unicast
 * and broadcast `PRINTBRIDGE_DISCOVER` probes immediately over UDP so clients auto-discover the
 * host within ~50ms even with screen off or Hotspot active.
 */
class PrintBridgeUdpResponder(
    private val payloadProvider: () -> JSONObject?,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
) {
    companion object {
        const val UDP_DISCOVERY_PORT = 8632
        const val DISCOVER_MAGIC = "PRINTBRIDGE_DISCOVER"
        const val ANNOUNCE_PREFIX = "PRINTBRIDGE_HERE:"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: DatagramSocket? = null
    private var listenJob: Job? = null

    val isRunning: Boolean get() = socket?.isClosed == false

    fun start() {
        stop()
        val boundSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_DISCOVERY_PORT))
            }
        } catch (t: Throwable) {
            onLog(LogCategory.MDNS, "UDP discovery beacon bind warning (port $UDP_DISCOVERY_PORT): ${t.message}", false)
            return
        }
        socket = boundSocket
        listenJob = scope.launch {
            val buf = ByteArray(1024)
            onLog(LogCategory.MDNS, "UDP screen-off & hotspot discovery responder active on port $UDP_DISCOVERY_PORT", false)
            while (isActive && !boundSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    boundSocket.receive(packet)
                    val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    if (msg.startsWith(DISCOVER_MAGIC)) {
                        val payload = payloadProvider() ?: continue
                        val replyBytes = (ANNOUNCE_PREFIX + payload.toString()).toByteArray(Charsets.UTF_8)
                        val replyPacket = DatagramPacket(
                            replyBytes,
                            replyBytes.size,
                            packet.address,
                            packet.port,
                        )
                        runCatching { boundSocket.send(replyPacket) }
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    if (!isActive || boundSocket.isClosed) break
                }
            }
        }
    }

    /**
     * Proactively sends a lightweight UDP broadcast keep-alive packet on all local LAN/Hotspot
     * subnets. This keeps the Wi-Fi radio's multicast/broadcast receive path awake during screen-off
     * and notifies any actively searching clients immediately.
     */
    fun sendKeepAliveBeacon() {
        val activeSocket = socket?.takeIf { !it.isClosed } ?: return
        val payload = payloadProvider() ?: return
        scope.launch {
            val bytes = (ANNOUNCE_PREFIX + payload.toString()).toByteArray(Charsets.UTF_8)
            val targets = LinkedHashSet<String>().apply {
                add("255.255.255.255")
                NetworkAddressResolver.getAllLocalLanSubnets().forEach { add(it.broadcastIp) }
            }
            for (targetIp in targets) {
                runCatching {
                    val addr = InetAddress.getByName(targetIp)
                    val pkt = DatagramPacket(bytes, bytes.size, addr, UDP_DISCOVERY_PORT)
                    activeSocket.send(pkt)
                }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        listenJob?.cancel()
        listenJob = null
    }
}

/**
 * Registers the Brother laser USB bridge over Bonjour / mDNS (_ipp._tcp.) using Android NsdManager
 * with RFC 7472 TXT records so macOS, Windows, Linux CUPS, iOS, and Android auto-discover it.
 */
class MdnsPrintAdvertiser(
    private val context: Context,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_ipp._tcp."
    }

    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(MdnsStatus.INACTIVE)
    val status: StateFlow<MdnsStatus> = _status.asStateFlow()

    private val _registeredName = MutableStateFlow("")
    val registeredName: StateFlow<String> = _registeredName.asStateFlow()

    private var activeListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun ensureMulticastLockHeld() {
        if (activeListener != null) {
            acquireMulticastLock()
        }
    }

    fun startAdvertising(settings: PrintServerSettings, localIp: String?) {
        stopPrinterAdvertisingOnly()
        if (!settings.mdnsEnabled) {
            _status.value = MdnsStatus.INACTIVE
            onLog(LogCategory.MDNS, "Bonjour / mDNS disabled in settings", false)
            return
        }
        val manager = nsdManager
        if (manager == null) {
            _status.value = MdnsStatus.ERROR
            onLog(LogCategory.MDNS, "Android NsdManager unavailable on this device", true)
            return
        }

        acquireMulticastLock()

        val cleanName = settings.printerName.trim().ifBlank { "Brother Laser Printer" }.take(60)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = cleanName
            serviceType = SERVICE_TYPE
            port = settings.ippPort
            // RFC 7472 / Bonjour Printing Specification TXT records
            setAttribute("txtvers", "1")
            setAttribute("qtotal", "1")
            setAttribute("rp", "ipp/print")
            setAttribute("ty", cleanName)
            setAttribute("product", "($cleanName)")
            setAttribute("pdl", "application/pdf,image/jpeg,image/png,image/webp")
            setAttribute("Duplex", "T")
            setAttribute("Color", "F")
            setAttribute("Copies", "T")
            setAttribute("PaperMax", "legal-A4")
            setAttribute("priority", "50")
            setAttribute("note", "PrintBridge USB OTG")
            setAttribute("scanpath", "/api/scan")
            if (settings.serverUuid.isNotBlank()) {
                setAttribute("UUID", settings.serverUuid)
            }
            if (!localIp.isNullOrBlank()) {
                setAttribute("adminurl", "http://$localIp:${settings.ippPort}/")
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _registeredName.value = info.serviceName
                _status.value = MdnsStatus.ADVERTISING
                onLog(LogCategory.MDNS, "Bonjour registered '${info.serviceName}.$SERVICE_TYPE' on port ${settings.ippPort}", false)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _status.value = MdnsStatus.ERROR
                onLog(LogCategory.MDNS, "Bonjour registration failed (NSD error $errorCode)", true)
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                _status.value = MdnsStatus.INACTIVE
                onLog(LogCategory.MDNS, "Bonjour service unregistered", false)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _status.value = MdnsStatus.INACTIVE
                onLog(LogCategory.MDNS, "Bonjour unregistration warning (code $errorCode)", true)
            }
        }

        _status.value = MdnsStatus.REGISTERING
        activeListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            activeListener = null
            _status.value = MdnsStatus.ERROR
            releaseMulticastLockIfIdle()
            onLog(LogCategory.MDNS, "Failed to start mDNS advertisement: ${t.message}", true)
        }
    }

    fun stopAdvertising() {
        stopPrinterAdvertisingOnly()
    }

    private fun stopPrinterAdvertisingOnly() {
        val listener = activeListener
        activeListener = null
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
        }
        _status.value = MdnsStatus.INACTIVE
        releaseMulticastLockIfIdle()
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("PrintBridgeMdnsLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        }
    }

    private fun releaseMulticastLockIfIdle() {
        if (activeListener == null) {
            runCatching {
                multicastLock?.takeIf { it.isHeld }?.release()
            }
        }
    }
}
