package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AppLanguage
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.PrintResolution
import com.example.model.PrintServerSettings
import com.example.model.PrinterLanguage
import com.example.model.RecentScansViewMode
import com.example.model.ScanParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

private val Context.printBridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "printbridge_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PRINTER_NAME = stringPreferencesKey("printer_name")
        val IPP_PORT = intPreferencesKey("ipp_port")
        val MDNS_ENABLED = booleanPreferencesKey("mdns_enabled")
        val PRINTER_LANGUAGE = stringPreferencesKey("printer_language")
        val SERVER_UUID = stringPreferencesKey("server_uuid")
        val CLIENT_DEVICE_ID = stringPreferencesKey("client_device_id")
        val AUTO_START_SERVER_ON_OTG = booleanPreferencesKey("auto_start_server_on_otg")
        val DEFAULT_PRINT_RESOLUTION = stringPreferencesKey("default_print_resolution")
        val DEFAULT_SCAN_DPI = intPreferencesKey("default_scan_dpi")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val RECENT_SCANS_VIEW_MODE = stringPreferencesKey("recent_scans_view_mode")
        val SCAN_PAGES_VIEW_MODE = stringPreferencesKey("scan_pages_view_mode")
        val CONNECTED_REMOTE_PRINTER_JSON = stringPreferencesKey("connected_remote_printer_json")
    }

    val settingsFlow: Flow<PrintServerSettings> = context.printBridgeDataStore.data.map { prefs ->
        PrintServerSettings(
            printerName = prefs[Keys.PRINTER_NAME] ?: "Brother Laser Printer",
            ippPort = (prefs[Keys.IPP_PORT] ?: 8631).coerceIn(1024, 65535),
            mdnsEnabled = prefs[Keys.MDNS_ENABLED] ?: true,
            printerLanguage = runCatching {
                PrinterLanguage.valueOf(prefs[Keys.PRINTER_LANGUAGE] ?: PrinterLanguage.BRLASER_DCP_L2520D.name)
            }.getOrDefault(PrinterLanguage.BRLASER_DCP_L2520D),
            serverUuid = prefs[Keys.SERVER_UUID] ?: "",
            autoStartServerOnOtg = prefs[Keys.AUTO_START_SERVER_ON_OTG] ?: false,
            defaultPrintResolution = runCatching {
                PrintResolution.valueOf(prefs[Keys.DEFAULT_PRINT_RESOLUTION] ?: PrintResolution.DPI_600.name)
            }.getOrDefault(PrintResolution.DPI_600),
            defaultScanDpi = ScanParameters.normalizeDpi(prefs[Keys.DEFAULT_SCAN_DPI] ?: ScanParameters.DEFAULT_DPI),
            appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
            recentScansViewMode = runCatching {
                RecentScansViewMode.valueOf(prefs[Keys.RECENT_SCANS_VIEW_MODE] ?: RecentScansViewMode.GRID.name)
            }.getOrDefault(RecentScansViewMode.GRID),
            scanPagesViewMode = runCatching {
                RecentScansViewMode.valueOf(prefs[Keys.SCAN_PAGES_VIEW_MODE] ?: RecentScansViewMode.GRID.name)
            }.getOrDefault(RecentScansViewMode.GRID),
        )
    }

    suspend fun ensureServerUuid(): String {
        val current = context.printBridgeDataStore.data.first()[Keys.SERVER_UUID]
        if (!current.isNullOrBlank()) return current
        val generated = UUID.randomUUID().toString()
        context.printBridgeDataStore.edit { it[Keys.SERVER_UUID] = generated }
        return generated
    }

    /**
     * Returns a stable, unique persistent identifier for this client device so the host server
     * can associate background scans and automatically hand over any lost/uncollected scans
     * whenever this device reconnects.
     */
    suspend fun ensureClientDeviceId(): String {
        val current = context.printBridgeDataStore.data.first()[Keys.CLIENT_DEVICE_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "dev_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        context.printBridgeDataStore.edit { it[Keys.CLIENT_DEVICE_ID] = generated }
        return generated
    }

    suspend fun updateSettings(transform: (PrintServerSettings) -> PrintServerSettings) {
        context.printBridgeDataStore.edit { prefs ->
            val current = PrintServerSettings(
                printerName = prefs[Keys.PRINTER_NAME] ?: "Brother Laser Printer",
                ippPort = (prefs[Keys.IPP_PORT] ?: 8631).coerceIn(1024, 65535),
                mdnsEnabled = prefs[Keys.MDNS_ENABLED] ?: true,
                printerLanguage = runCatching {
                    PrinterLanguage.valueOf(prefs[Keys.PRINTER_LANGUAGE] ?: PrinterLanguage.BRLASER_DCP_L2520D.name)
                }.getOrDefault(PrinterLanguage.BRLASER_DCP_L2520D),
                serverUuid = prefs[Keys.SERVER_UUID] ?: "",
                autoStartServerOnOtg = prefs[Keys.AUTO_START_SERVER_ON_OTG] ?: false,
                defaultPrintResolution = runCatching {
                    PrintResolution.valueOf(prefs[Keys.DEFAULT_PRINT_RESOLUTION] ?: PrintResolution.DPI_600.name)
                }.getOrDefault(PrintResolution.DPI_600),
                defaultScanDpi = ScanParameters.normalizeDpi(prefs[Keys.DEFAULT_SCAN_DPI] ?: ScanParameters.DEFAULT_DPI),
                appLanguage = AppLanguage.fromCode(prefs[Keys.APP_LANGUAGE]),
                recentScansViewMode = runCatching {
                    RecentScansViewMode.valueOf(prefs[Keys.RECENT_SCANS_VIEW_MODE] ?: RecentScansViewMode.GRID.name)
                }.getOrDefault(RecentScansViewMode.GRID),
                scanPagesViewMode = runCatching {
                    RecentScansViewMode.valueOf(prefs[Keys.SCAN_PAGES_VIEW_MODE] ?: RecentScansViewMode.GRID.name)
                }.getOrDefault(RecentScansViewMode.GRID),
            )
            val updated = transform(current)
            prefs[Keys.PRINTER_NAME] = updated.printerName.trim().ifBlank { "Brother Laser Printer" }
            prefs[Keys.IPP_PORT] = updated.ippPort.coerceIn(1024, 65535)
            prefs[Keys.MDNS_ENABLED] = updated.mdnsEnabled
            prefs[Keys.PRINTER_LANGUAGE] = updated.printerLanguage.name
            prefs[Keys.AUTO_START_SERVER_ON_OTG] = updated.autoStartServerOnOtg
            prefs[Keys.DEFAULT_PRINT_RESOLUTION] = updated.defaultPrintResolution.name
            prefs[Keys.DEFAULT_SCAN_DPI] = ScanParameters.normalizeDpi(updated.defaultScanDpi)
            prefs[Keys.APP_LANGUAGE] = updated.appLanguage.code
            prefs[Keys.RECENT_SCANS_VIEW_MODE] = updated.recentScansViewMode.name
            prefs[Keys.SCAN_PAGES_VIEW_MODE] = updated.scanPagesViewMode.name
        }
    }

    suspend fun saveConnectedRemotePrinter(printer: DiscoveredNetworkPrinter) {
        val json = JSONObject().apply {
            put("id", printer.id)
            put("name", printer.name)
            put("host", printer.host)
            put("port", printer.port)
            put("ippPath", printer.ippPath)
            put("supportsPrint", printer.supportsPrint)
            put("supportsScan", printer.supportsScan)
            put("scanApiPath", printer.scanApiPath)
            put("model", printer.model)
            put("isPrintBridgeServer", printer.isPrintBridgeServer)
            put("supportsAdf", printer.supportsAdf)
            put("supportsAdfDuplex", printer.supportsAdfDuplex)
        }.toString()
        context.printBridgeDataStore.edit { prefs ->
            prefs[Keys.CONNECTED_REMOTE_PRINTER_JSON] = json
        }
    }

    suspend fun loadConnectedRemotePrinter(): DiscoveredNetworkPrinter? {
        val raw = context.printBridgeDataStore.data.first()[Keys.CONNECTED_REMOTE_PRINTER_JSON]
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val host = obj.optString("host").trim()
            val port = obj.optInt("port", 8631)
            if (host.isBlank() || port !in 1..65535) return null
            DiscoveredNetworkPrinter(
                id = obj.optString("id").ifBlank { "$host:$port" },
                name = obj.optString("name").ifBlank { "Wireless Printer" },
                host = host,
                port = port,
                ippPath = obj.optString("ippPath", "/ipp/print").ifBlank { "/ipp/print" },
                supportsPrint = obj.optBoolean("supportsPrint", true),
                supportsScan = obj.optBoolean("supportsScan", false),
                scanApiPath = obj.optString("scanApiPath", "/api/scan").ifBlank { "/api/scan" },
                model = obj.optString("model", ""),
                isPrintBridgeServer = obj.optBoolean("isPrintBridgeServer", false),
                supportsAdf = obj.optBoolean("supportsAdf", false),
                supportsAdfDuplex = obj.optBoolean("supportsAdfDuplex", false),
            )
        }.getOrNull()
    }

    suspend fun clearConnectedRemotePrinter() {
        context.printBridgeDataStore.edit { prefs ->
            prefs.remove(Keys.CONNECTED_REMOTE_PRINTER_JSON)
        }
    }
}
