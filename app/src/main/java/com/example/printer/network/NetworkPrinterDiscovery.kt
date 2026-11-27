package com.example.printer.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.LogCategory
import com.example.printer.ipp.RemoteIppClient
import com.example.scanner.SimpleRemoteScanClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume

/**
 * Discovers wireless printers (`_ipp._tcp.`) and HTTP Scan API servers (`/api/scan`) on the local
 * Wi-Fi or Mobile Hotspot network using THREE parallel mechanisms:
 * 1. Android `NsdManager` Bonjour / mDNS discovery (`_ipp._tcp.` + HTTP `/api/scan/capabilities` probe)
 * 2. Fast UDP broadcast discovery (`PRINTBRIDGE_DISCOVER` on port 8632) — works reliably even
 *    when the host phone's screen is OFF and mDNS multicast is throttled
 * 3. Instant parallel `/24` local subnet scanning across all active Wi-Fi & Hotspot interfaces —
 *    guarantees instant discovery when Mobile Hotspot is ON and mDNS does not cross the AP bridge
 */
class NetworkPrinterDiscovery(
    private val context: Context,
    private val onLog: (LogCategory, String, Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _discoveredPrinters = MutableStateFlow<List<DiscoveredNetworkPrinter>>(emptyList())
    val discoveredPrinters: StateFlow<List<DiscoveredNetworkPrinter>> = _discoveredPrinters.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var ippDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private var resolveWorkerJob: Job? = null
    private var udpDiscoveryJob: Job? = null
    private var subnetScanJob: Job? = null
    @Volatile private var activeUdpSocket: DatagramSocket? = null

    fun startDiscovery(
        excludeLocalIp: String? = null,
        excludeLocalPort: Int? = null,
        preferredPort: Int = 8631,
    ) {
        stopDiscovery()

        acquireMulticastLock()
        _isDiscovering.value = true

        val manager = nsdManager
        if (manager != null) {
            startResolveWorker(excludeLocalIp, excludeLocalPort)
            val ippListener = createDiscoveryListener()
            ippDiscoveryListener = ippListener

            runCatching {
                manager.discoverServices(MdnsPrintAdvertiser.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, ippListener)
            }.onFailure { err ->
                ippDiscoveryListener = null
                onLog(LogCategory.MDNS, "IPP mDNS discovery unavailable (${err.message}) — relying on UDP & /24 scan", false)
            }
        } else {
            onLog(LogCategory.MDNS, "NsdManager unavailable — using UDP & /24 local network scan", false)
        }

        // Launch UDP broadcast discovery (works even when host screen is off)
        startUdpBroadcastDiscovery(excludeLocalIp, excludeLocalPort)

        // Launch instant parallel /24 local subnet scan (essential for Mobile Hotspot & isolated routers)
        val portsToProbe = linkedSetOf(preferredPort.coerceIn(1024, 65535), 8631)
        startParallelSubnetScan(
            excludeLocalIp = excludeLocalIp,
            excludeLocalPort = excludeLocalPort,
            portsToProbe = portsToProbe.toList(),
        )

        val subnets = NetworkAddressResolver.getAllLocalLanSubnets()
        val hotspotMode = NetworkAddressResolver.isHotspotActive(context)
        val subnetLabel = subnets.joinToString(", ") { "${it.prefix24}.0/24" }.ifBlank { "local LAN" }
        onLog(
            LogCategory.MDNS,
            if (hotspotMode) {
                "Hotspot active — running instant /24 scan ($subnetLabel) + UDP & mDNS discovery..."
            } else {
                "Searching $subnetLabel via mDNS, UDP beacon & instant /24 scan..."
            },
            false,
        )
    }

    fun stopDiscovery() {
        val manager = nsdManager
        ippDiscoveryListener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        ippDiscoveryListener = null
        resolveWorkerJob?.cancel()
        resolveWorkerJob = null
        udpDiscoveryJob?.cancel()
        udpDiscoveryJob = null
        runCatching { activeUdpSocket?.close() }
        activeUdpSocket = null
        subnetScanJob?.cancel()
        subnetScanJob = null
        _isDiscovering.value = false
        releaseMulticastLock()
    }

    /**
     * Broadcasts `PRINTBRIDGE_DISCOVER` on UDP port 8632 across `255.255.255.255` and every local
     * subnet broadcast address (`x.x.x.255`) so hosts reply directly over unicast even when their
     * screen is OFF or Mobile Hotspot is active.
     */
    private fun startUdpBroadcastDiscovery(
        excludeLocalIp: String?,
        excludeLocalPort: Int?,
    ) {
        udpDiscoveryJob?.cancel()
        runCatching { activeUdpSocket?.close() }
        activeUdpSocket = null
        udpDiscoveryJob = scope.launch {
            val sock = runCatching {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 700
                }
            }.getOrNull() ?: return@launch
            activeUdpSocket = sock

            try {
                val probeBytes = PrintBridgeUdpResponder.DISCOVER_MAGIC.toByteArray(Charsets.UTF_8)
                val recvBuf = ByteArray(2048)
                var lastSendMs = 0L

                while (isActive && _isDiscovering.value && !sock.isClosed) {
                    val now = System.currentTimeMillis()
                    if (now - lastSendMs >= 2_200L) {
                        lastSendMs = now
                        val targets = LinkedHashSet<String>().apply {
                            add("255.255.255.255")
                            NetworkAddressResolver.getAllLocalLanSubnets().forEach { add(it.broadcastIp) }
                        }
                        for (ip in targets) {
                            if (sock.isClosed || !isActive || !_isDiscovering.value) break
                            runCatching {
                                val pkt = DatagramPacket(
                                    probeBytes,
                                    probeBytes.size,
                                    InetAddress.getByName(ip),
                                    PrintBridgeUdpResponder.UDP_DISCOVERY_PORT,
                                )
                                sock.send(pkt)
                            }
                        }
                    }

                    if (sock.isClosed || !isActive || !_isDiscovering.value) break

                    try {
                        val resp = DatagramPacket(recvBuf, recvBuf.size)
                        sock.receive(resp)
                        val senderIp = resp.address?.hostAddress ?: continue
                        val text = String(resp.data, resp.offset, resp.length, Charsets.UTF_8).trim()
                        if (!text.startsWith(PrintBridgeUdpResponder.ANNOUNCE_PREFIX)) continue
                        val jsonStr = text.removePrefix(PrintBridgeUdpResponder.ANNOUNCE_PREFIX)
                        val obj = runCatching { JSONObject(jsonStr) }.getOrNull() ?: continue
                        val port = obj.optInt("port", 8631).takeIf { it in 1..65535 } ?: continue
                        if (excludeLocalIp != null && excludeLocalPort != null &&
                            senderIp == excludeLocalIp && port == excludeLocalPort
                        ) {
                            continue
                        }
                        val name = obj.optString("printerName").ifBlank {
                            obj.optString("name").ifBlank { "BrotherBridge ($senderIp)" }
                        }
                        val model = obj.optString("model").ifBlank { name }
                        val supportsScan = obj.optBoolean("scannerAvailable", false) || obj.optBoolean("supportsScan", false)
                        val supportsAdf = obj.optBoolean("supportsAdf", false)
                        val supportsAdfDuplex = obj.optBoolean("supportsAdfDuplex", false)

                        val printer = DiscoveredNetworkPrinter(
                            id = "$senderIp:$port",
                            name = name,
                            host = senderIp,
                            port = port,
                            ippPath = "/ipp/print",
                            supportsPrint = true,
                            supportsScan = supportsScan,
                            scanApiPath = "/api/scan",
                            model = model,
                            isPrintBridgeServer = true,
                            supportsAdf = supportsAdf,
                            supportsAdfDuplex = supportsAdfDuplex,
                        )
                        val isNew = _discoveredPrinters.value.none { it.id == printer.id }
                        upsertDiscoveredPrinter(printer)
                        if (isNew) {
                            onLog(
                                LogCategory.MDNS,
                                "Auto-discovered PrintBridge host '${printer.name}' via UDP at $senderIp:$port",
                                false,
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        // Loop around and re-broadcast if needed
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // Expected when stopDiscovery() closes the socket (EBADF / Socket closed)
                        // or when Wi-Fi/Hotspot switches mid-receive.
                        if (sock.isClosed || !isActive || !_isDiscovering.value) {
                            break
                        }
                        delay(200L)
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Never crash DefaultDispatcher on transient UDP socket close/errors
            } finally {
                runCatching { sock.close() }
                if (activeUdpSocket === sock) {
                    activeUdpSocket = null
                }
            }
        }
    }

    /**
     * Performs an instant, high-concurrency `/24` sweep across all active Wi-Fi and Mobile Hotspot
     * local IPv4 subnets (skipping own IP). Uses fast non-blocking TCP connect probes (260ms) with
     * a 48-way semaphore so a full 254-host subnet finishes in ~1.5 seconds and immediately verifies
     * any open PrintBridge server via `/api/status`.
     */
    private fun startParallelSubnetScan(
        excludeLocalIp: String?,
        excludeLocalPort: Int?,
        portsToProbe: List<Int>,
    ) {
        subnetScanJob?.cancel()
        subnetScanJob = scope.launch {
            val subnets = NetworkAddressResolver.getAllLocalLanSubnets()
            if (subnets.isEmpty()) return@launch

            val semaphore = Semaphore(48)
            for (subnet in subnets) {
                if (!isActive || !_isDiscovering.value) break
                val localIps = subnets.map { it.localIp }.toSet()
                val hosts = (1..254).map { "${subnet.prefix24}.$it" }.filter { ip ->
                    ip != subnet.localIp && (excludeLocalIp == null || ip != excludeLocalIp || excludeLocalPort == null)
                }

                coroutineScope {
                    hosts.map { candidateIp ->
                        async(Dispatchers.IO) {
                            if (!isActive || !_isDiscovering.value) return@async
                            semaphore.withPermit {
                                if (!isActive || !_isDiscovering.value) return@withPermit
                                for (port in portsToProbe) {
                                    if (candidateIp in localIps && excludeLocalPort == port) continue
                                    if (tcpPortOpen(candidateIp, port, timeoutMs = 260)) {
                                        try {
                                            verifyAndAddScannedHost(candidateIp, port)
                                        } catch (ce: kotlinx.coroutines.CancellationException) {
                                            throw ce
                                        } catch (_: Throwable) {
                                            // Ignore transient host probe error
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
            // If hotspot is active and nothing was found on pass 1, run one follow-up sweep after 3.5s
            if (isActive && _isDiscovering.value && _discoveredPrinters.value.isEmpty() &&
                NetworkAddressResolver.isHotspotActive(context)
            ) {
                delay(3_500L)
                if (!isActive || !_isDiscovering.value) return@launch
                val retrySubnets = NetworkAddressResolver.getAllLocalLanSubnets()
                val semaphoreRetry = Semaphore(48)
                for (subnet in retrySubnets) {
                    if (!isActive || !_isDiscovering.value) break
                    val hosts = (1..254).map { "${subnet.prefix24}.$it" }.filter { it != subnet.localIp }
                    coroutineScope {
                        hosts.map { candidateIp ->
                            async(Dispatchers.IO) {
                                if (!isActive || !_isDiscovering.value) return@async
                                semaphoreRetry.withPermit {
                                    for (port in portsToProbe) {
                                        if (tcpPortOpen(candidateIp, port, timeoutMs = 300)) {
                                            try {
                                                verifyAndAddScannedHost(candidateIp, port)
                                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                                throw ce
                                            } catch (_: Throwable) {
                                                // Ignore transient host probe error
                                            }
                                            break
                                        }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }
    }

    private fun tcpPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun verifyAndAddScannedHost(ip: String, port: Int) {
        val statusJson = RemoteIppClient.fetchPrintBridgeStatusJson(ip, port, timeoutMs = 2_000)
        val scanCaps = SimpleRemoteScanClient.probeCapabilities(ip, port, timeoutMs = 2_000)
        if (statusJson == null && scanCaps == null) return

        val resolvedName = statusJson?.optString("printerName")?.takeIf { it.isNotBlank() }
            ?: scanCaps?.model?.takeIf { it.isNotBlank() }
            ?: "BrotherBridge ($ip)"
        val resolvedModel = statusJson?.optString("model")?.takeIf { it.isNotBlank() }
            ?: scanCaps?.model?.takeIf { it.isNotBlank() }
            ?: resolvedName
        val supportsScan = scanCaps?.available == true || statusJson?.optBoolean("scannerAvailable", false) == true
        val supportsAdf = scanCaps?.supportsAdf ?: statusJson?.optBoolean("supportsAdf", false) ?: false
        val supportsAdfDuplex = scanCaps?.supportsAdfDuplex ?: statusJson?.optBoolean("supportsAdfDuplex", false) ?: false

        val printer = DiscoveredNetworkPrinter(
            id = "$ip:$port",
            name = resolvedName,
            host = ip,
            port = port,
            ippPath = "/ipp/print",
            supportsPrint = true,
            supportsScan = supportsScan,
            scanApiPath = "/api/scan",
            model = resolvedModel,
            isPrintBridgeServer = true,
            supportsAdf = supportsAdf,
            supportsAdfDuplex = supportsAdfDuplex,
        )
        val isNew = _discoveredPrinters.value.none { it.id == printer.id }
        upsertDiscoveredPrinter(printer)
        if (isNew) {
            onLog(
                LogCategory.MDNS,
                "Subnet scan found PrintBridge host '${printer.name}' at $ip:$port (scan=$supportsScan)",
                false,
            )
        }
    }

    /**
     * Probes a manual IP address and port over Wi-Fi or Hotspot — checks PrintBridge `/api/status`,
     * standard IPP `Get-Printer-Attributes`, and simple async `/api/scan/capabilities`.
     */
    suspend fun probeManualHost(rawHost: String, port: Int): Result<DiscoveredNetworkPrinter> =
        withContext(Dispatchers.IO) {
            val host = rawHost.trim()
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("ipp://")
                .substringBefore('/')
                .substringBefore(':')
            if (host.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Enter a valid IP address or hostname"))
            }
            val safePort = port.coerceIn(1, 65535)

            onLog(LogCategory.IPP, "Probing wireless server at $host:$safePort...", false)

            // 1. Check PrintBridge /api/status first
            val statusJson = RemoteIppClient.fetchPrintBridgeStatusJson(host, safePort, timeoutMs = 5_000)
            // 2. Check IPP Get-Printer-Attributes
            val ippPath = "/ipp/print"
            val ippInfo = RemoteIppClient.probePrinter(host, safePort, ippPath)
            // 3. Check simple async /api/scan/capabilities (skips eSCL)
            val simpleScanCaps = SimpleRemoteScanClient.probeCapabilities(host, safePort, timeoutMs = 5_000)

            if (statusJson == null && ippInfo == null && simpleScanCaps == null) {
                return@withContext Result.failure(
                    IllegalStateException("No printer or scanner responded at $host:$safePort. Ensure both devices are on the same Wi-Fi or Hotspot and the server is running."),
                )
            }

            val resolvedName = statusJson?.optString("printerName")?.takeIf { it.isNotBlank() }
                ?: ippInfo?.printerName?.takeIf { it.isNotBlank() }
                ?: simpleScanCaps?.model?.takeIf { it.isNotBlank() }
                ?: "Wireless Printer ($host)"

            val resolvedModel = statusJson?.optString("model")?.takeIf { it.isNotBlank() }
                ?: ippInfo?.makeAndModel?.takeIf { it.isNotBlank() }
                ?: simpleScanCaps?.model?.takeIf { it.isNotBlank() }
                ?: resolvedName

            val supportsScan = simpleScanCaps?.available == true || statusJson?.optBoolean("scannerAvailable", false) == true
            val supportsPrint = ippInfo != null || statusJson != null

            val printer = DiscoveredNetworkPrinter(
                id = "$host:$safePort",
                name = resolvedName,
                host = host,
                port = safePort,
                ippPath = ippPath,
                supportsPrint = supportsPrint,
                supportsScan = supportsScan,
                scanApiPath = "/api/scan",
                model = resolvedModel,
                isPrintBridgeServer = statusJson != null || simpleScanCaps != null,
                supportsAdf = simpleScanCaps?.supportsAdf ?: statusJson?.optBoolean("supportsAdf", false) ?: false,
                supportsAdfDuplex = simpleScanCaps?.supportsAdfDuplex ?: statusJson?.optBoolean("supportsAdfDuplex", false) ?: false,
            )

            upsertDiscoveredPrinter(printer)
            onLog(
                LogCategory.IPP,
                "Found wireless server '${printer.name}' at $host:$safePort (print=$supportsPrint, scan=$supportsScan)",
                false,
            )
            Result.success(printer)
        }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostName = serviceInfo.serviceName
                if (!lostName.isNullOrBlank()) {
                    _discoveredPrinters.update { list ->
                        list.filterNot { it.name.equals(lostName, ignoreCase = true) && !it.isPrintBridgeServer }
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog(LogCategory.MDNS, "mDNS discovery warning for $serviceType (code $errorCode) — UDP & /24 scan active", false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
    }

    private fun startResolveWorker(excludeLocalIp: String?, excludeLocalPort: Int?) {
        resolveWorkerJob?.cancel()
        resolveWorkerJob = scope.launch {
            for (serviceInfo in resolveQueue) {
                val resolved = resolveServiceBlocking(serviceInfo) ?: continue
                val hostAddr = resolved.host?.hostAddress ?: continue
                val port = resolved.port
                if (port <= 0) continue

                // Skip our own embedded server if running on this exact device
                if (excludeLocalIp != null && excludeLocalPort != null &&
                    hostAddr == excludeLocalIp && port == excludeLocalPort
                ) {
                    continue
                }

                val attrs = resolved.attributes
                val rp = attrs["rp"]?.toString(Charsets.UTF_8)?.trim()?.let {
                    if (it.startsWith("/")) it else "/$it"
                } ?: "/ipp/print"
                val scanPath = attrs["scanpath"]?.toString(Charsets.UTF_8)?.trim()?.let {
                    if (it.startsWith("/")) it else "/$it"
                } ?: "/api/scan"
                val ty = attrs["ty"]?.toString(Charsets.UTF_8)?.trim().orEmpty()
                val note = attrs["note"]?.toString(Charsets.UTF_8)?.trim().orEmpty()
                val isPrintBridge = note.contains("PrintBridge", ignoreCase = true)

                // Probe HTTP /api/scan/capabilities directly so scanner availability is always accurate
                val scanCaps = SimpleRemoteScanClient.probeCapabilities(hostAddr, port, timeoutMs = 2_000)
                val supportsScan = scanCaps?.available == true
                val supportsAdf = scanCaps?.supportsAdf == true
                val supportsAdfDuplex = scanCaps?.supportsAdfDuplex == true

                val key = "$hostAddr:$port"
                _discoveredPrinters.update { current ->
                    val existing = current.firstOrNull { it.id == key }
                    val merged = if (existing != null) {
                        existing.copy(
                            name = existing.name.ifBlank { resolved.serviceName },
                            model = existing.model.ifBlank { ty },
                            ippPath = rp,
                            scanApiPath = scanPath,
                            supportsPrint = true,
                            supportsScan = existing.supportsScan || supportsScan,
                            isPrintBridgeServer = existing.isPrintBridgeServer || isPrintBridge || scanCaps != null,
                            supportsAdf = existing.supportsAdf || supportsAdf,
                            supportsAdfDuplex = existing.supportsAdfDuplex || supportsAdfDuplex,
                        )
                    } else {
                        DiscoveredNetworkPrinter(
                            id = key,
                            name = resolved.serviceName.ifBlank { ty.ifBlank { "Wireless Printer" } },
                            host = hostAddr,
                            port = port,
                            ippPath = rp,
                            supportsPrint = true,
                            supportsScan = supportsScan,
                            scanApiPath = scanPath,
                            model = ty.ifBlank { resolved.serviceName },
                            isPrintBridgeServer = isPrintBridge || scanCaps != null,
                            supportsAdf = supportsAdf,
                            supportsAdfDuplex = supportsAdfDuplex,
                        )
                    }
                    (listOf(merged) + current.filterNot { it.id == key })
                }

                onLog(
                    LogCategory.MDNS,
                    "Discovered IPP host '${resolved.serviceName}' at $hostAddr:$port (HTTP scan=$supportsScan)",
                    false,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveServiceBlocking(info: NsdServiceInfo): NsdServiceInfo? {
        val manager = nsdManager ?: return null
        return withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine { cont ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                }
                try {
                    manager.resolveService(info, listener)
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun upsertDiscoveredPrinter(printer: DiscoveredNetworkPrinter) {
        _discoveredPrinters.update { current ->
            val existing = current.firstOrNull { it.id == printer.id }
            val merged = if (existing != null) {
                existing.copy(
                    name = printer.name.ifBlank { existing.name },
                    model = printer.model.ifBlank { existing.model },
                    supportsPrint = existing.supportsPrint || printer.supportsPrint,
                    supportsScan = existing.supportsScan || printer.supportsScan,
                    supportsAdf = existing.supportsAdf || printer.supportsAdf,
                    supportsAdfDuplex = existing.supportsAdfDuplex || printer.supportsAdfDuplex,
                    isPrintBridgeServer = existing.isPrintBridgeServer || printer.isPrintBridgeServer,
                )
            } else {
                printer
            }
            listOf(merged) + current.filterNot { it.id == printer.id }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("PrintBridgeDiscoveryLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
    }
}
