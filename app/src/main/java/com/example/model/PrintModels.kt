package com.example.model

enum class PagesPerSheet(
    val count: Int,
    val columns: Int,
    val rows: Int,
    val preferLandscapeSheet: Boolean,
    val shortLabel: String,
    val description: String,
) {
    ONE(1, 1, 1, false, "1-Up", "Single page per sheet"),
    TWO(2, 2, 1, true, "2-Up", "2 pages side-by-side"),
    FOUR(4, 2, 2, false, "4-Up", "2×2 grid per sheet"),
    EIGHT(8, 4, 2, true, "8-Up", "4×2 compact grid"),
    SIXTEEN(16, 4, 4, false, "16-Up", "4×4 handout grid");

    companion object {
        fun fromCount(count: Int): PagesPerSheet = when (count) {
            1 -> ONE
            2 -> TWO
            4 -> FOUR
            8 -> EIGHT
            16 -> SIXTEEN
            else -> entries.minByOrNull { kotlin.math.abs(it.count - count) } ?: ONE
        }
    }
}

enum class DuplexMode(
    val ippKeyword: String,
    val pjlDuplex: String,
    val pjlBinding: String,
    val pclCode: Int,
    val shortLabel: String,
    val subtitle: String,
) {
    SIMPLEX(
        ippKeyword = "one-sided",
        pjlDuplex = "OFF",
        pjlBinding = "LONGEDGE",
        pclCode = 0,
        shortLabel = "1-Sided",
        subtitle = "Single-sided (Simplex)",
    ),
    LONG_EDGE(
        ippKeyword = "two-sided-long-edge",
        pjlDuplex = "ON",
        pjlBinding = "LONGEDGE",
        pclCode = 1,
        shortLabel = "2-Sided (Long)",
        subtitle = "Auto duplex, flip like a book",
    ),
    SHORT_EDGE(
        ippKeyword = "two-sided-short-edge",
        pjlDuplex = "ON",
        pjlBinding = "SHORTEDGE",
        pclCode = 2,
        shortLabel = "2-Sided (Short)",
        subtitle = "Auto duplex, flip like a notepad",
    );

    companion object {
        fun fromIppKeyword(keyword: String?): DuplexMode = when (keyword?.lowercase()) {
            "two-sided-long-edge" -> LONG_EDGE
            "two-sided-short-edge" -> SHORT_EDGE
            else -> SIMPLEX
        }
    }
}

enum class PaperSize(
    val displayName: String,
    val ippKeyword: String,
    val pjlName: String,
    val pclPaperCode: Int,
    val widthMm: Double,
    val heightMm: Double,
) {
    A4(
        displayName = "A4 (210×297 mm)",
        ippKeyword = "iso_a4_210x297mm",
        pjlName = "A4",
        pclPaperCode = 26,
        widthMm = 210.0,
        heightMm = 297.0,
    ),
    LETTER(
        displayName = "US Letter (8.5×11 in)",
        ippKeyword = "na_letter_8.5x11in",
        pjlName = "LETTER",
        pclPaperCode = 2,
        widthMm = 215.9,
        heightMm = 279.4,
    ),
    LEGAL(
        displayName = "US Legal (8.5×14 in)",
        ippKeyword = "na_legal_8.5x14in",
        pjlName = "LEGAL",
        pclPaperCode = 3,
        widthMm = 215.9,
        heightMm = 355.6,
    ),
    A5(
        displayName = "A5 (148×210 mm)",
        ippKeyword = "iso_a5_148x210mm",
        pjlName = "A5",
        pclPaperCode = 25,
        widthMm = 148.0,
        heightMm = 210.0,
    ),
    A6(
        displayName = "A6 (105×148 mm)",
        ippKeyword = "iso_a6_105x148mm",
        pjlName = "A6",
        pclPaperCode = 24,
        widthMm = 105.0,
        heightMm = 148.0,
    ),
    B5(
        displayName = "JIS B5 (182×257 mm)",
        ippKeyword = "jis_b5_182x257mm",
        pjlName = "B5",
        pclPaperCode = 45,
        widthMm = 182.0,
        heightMm = 257.0,
    ),
    EXECUTIVE(
        displayName = "Executive (7.25×10.5 in)",
        ippKeyword = "na_executive_7.25x10.5in",
        pjlName = "EXECUTIVE",
        pclPaperCode = 1,
        widthMm = 184.15,
        heightMm = 266.7,
    );

    val widthHundredthsMm: Int get() = kotlin.math.round(widthMm * 100.0).toInt()
    val heightHundredthsMm: Int get() = kotlin.math.round(heightMm * 100.0).toInt()

    fun widthPx(dpi: Int): Int = ((widthMm / 25.4) * dpi).toInt()
    fun heightPx(dpi: Int): Int = ((heightMm / 25.4) * dpi).toInt()

    companion object {
        fun fromIppKeyword(keyword: String?): PaperSize {
            val k = keyword?.lowercase() ?: return A4
            return when {
                k.contains("letter") -> LETTER
                k.contains("legal") -> LEGAL
                k.contains("a5") -> A5
                k.contains("a6") -> A6
                k.contains("b5") -> B5
                k.contains("executive") -> EXECUTIVE
                else -> A4
            }
        }

        fun fromDimensionsHundredthsMm(xDim: Int, yDim: Int): PaperSize {
            val shortSide = minOf(xDim, yDim)
            val longSide = maxOf(xDim, yDim)
            return entries.minByOrNull { size ->
                kotlin.math.abs(size.widthHundredthsMm - shortSide) +
                    kotlin.math.abs(size.heightHundredthsMm - longSide)
            } ?: A4
        }
    }
}

enum class PrintResolution(val dpi: Int, val label: String) {
    DPI_300(300, "300 DPI (Fast)"),
    DPI_600(600, "600 DPI (Sharp)");

    companion object {
        fun fromDpi(dpi: Int): PrintResolution = if (dpi >= 450) DPI_600 else DPI_300
    }
}

enum class PrintScaling(val ippKeyword: String, val label: String) {
    AUTO("auto", "Auto"),
    AUTO_FIT("auto-fit", "Auto Fit"),
    FIT("fit", "Fit to Page"),
    FILL("fill", "Fill Page"),
    NONE("none", "Actual Size (100%)");

    companion object {
        fun fromIppKeyword(keyword: String?): PrintScaling? = when (keyword?.lowercase()) {
            "auto" -> AUTO
            "auto-fit" -> AUTO_FIT
            "fit" -> FIT
            "fill" -> FILL
            "none" -> NONE
            else -> null
        }
    }
}

enum class PrintOrientation(val ippEnum: Int, val label: String) {
    PORTRAIT(3, "Portrait"),
    LANDSCAPE(4, "Landscape"),
    REVERSE_LANDSCAPE(5, "Reverse Landscape"),
    REVERSE_PORTRAIT(6, "Reverse Portrait"),
    AUTO(7, "Auto");

    val isLandscape: Boolean
        get() = this == LANDSCAPE || this == REVERSE_LANDSCAPE

    companion object {
        fun fromIppEnum(code: Int?): PrintOrientation = when (code) {
            4 -> LANDSCAPE
            5 -> REVERSE_LANDSCAPE
            6 -> REVERSE_PORTRAIT
            7 -> AUTO
            else -> PORTRAIT
        }
    }
}

enum class PrintColorScheme(
    val ditherImages: Boolean,
    val shortLabel: String,
    val subtitle: String,
) {
    GRAYSCALE(
        ditherImages = true,
        shortLabel = "Grayscale",
        subtitle = "Smooth halftone shades for photos & scans",
    ),
    MONOCHROME_2BIT(
        ditherImages = false,
        shortLabel = "2-Bit B&W",
        subtitle = "Crisp black & white for text & line art",
    ),
}

data class PageMargins(
    val topMm: Float = DEFAULT_MARGIN_MM,
    val bottomMm: Float = DEFAULT_MARGIN_MM,
    val leftMm: Float = DEFAULT_MARGIN_MM,
    val rightMm: Float = DEFAULT_MARGIN_MM,
) {
    companion object {
        /** Standard Brother laser printable margin (~1/6 in = 4.23 mm = 423 hundredths of a mm). */
        const val DEFAULT_MARGIN_HUNDREDTHS_MM = 423
        const val DEFAULT_MARGIN_MM = 4.23f
        val DEFAULT = PageMargins()
        val BORDERLESS = PageMargins(0f, 0f, 0f, 0f)

        val SUPPORTED_MARGINS_HUNDREDTHS_MM: List<Int> = listOf(
            0, 423, 635, 1000, 1270, 1500, 2000, 2540,
        )

        fun fromHundredthsMm(
            top: Int?,
            bottom: Int?,
            left: Int?,
            right: Int?,
        ): PageMargins = PageMargins(
            topMm = (top ?: DEFAULT_MARGIN_HUNDREDTHS_MM).coerceIn(0, 10000) / 100f,
            bottomMm = (bottom ?: DEFAULT_MARGIN_HUNDREDTHS_MM).coerceIn(0, 10000) / 100f,
            leftMm = (left ?: DEFAULT_MARGIN_HUNDREDTHS_MM).coerceIn(0, 10000) / 100f,
            rightMm = (right ?: DEFAULT_MARGIN_HUNDREDTHS_MM).coerceIn(0, 10000) / 100f,
        )
    }
}

enum class MediaType(
    val ippKeyword: String,
    val pjlName: String,
    val label: String,
) {
    REGULAR("stationery", "REGULAR", "Plain Paper"),
    THIN("stationery-lightweight", "THIN", "Thin Paper"),
    THICK("stationery-heavyweight", "THICK", "Thick Paper"),
    RECYCLED("stationery-recycled", "RECYCLED", "Recycled Paper"),
    BOND("stationery-bond", "BOND", "Bond Paper"),
    ENVELOPE("envelope", "ENVELOPE", "Envelope"),
    LABELS("labels", "LABEL", "Labels"),
    TRANSPARENCY("transparency", "TRANSPARENCY", "Transparency");

    companion object {
        fun fromIppKeyword(keyword: String?): MediaType {
            val k = keyword?.lowercase() ?: return REGULAR
            return when {
                k.contains("lightweight") || k.contains("thin") -> THIN
                k.contains("heavyweight") || k.contains("thick") || k.contains("cardstock") -> THICK
                k.contains("recycled") -> RECYCLED
                k.contains("bond") -> BOND
                k.contains("envelope") -> ENVELOPE
                k.contains("label") -> LABELS
                k.contains("transparen") -> TRANSPARENCY
                else -> REGULAR
            }
        }
    }
}

enum class NUpPresentationDirection(val ippKeyword: String) {
    TO_RIGHT_TO_BOTTOM("toright-tobottom"),
    TO_BOTTOM_TO_RIGHT("tobottom-toright"),
    TO_LEFT_TO_BOTTOM("toleft-tobottom"),
    TO_BOTTOM_TO_LEFT("tobottom-toleft");

    companion object {
        fun fromIppKeyword(keyword: String?): NUpPresentationDirection = when (keyword?.lowercase()) {
            "tobottom-toright" -> TO_BOTTOM_TO_RIGHT
            "toleft-tobottom" -> TO_LEFT_TO_BOTTOM
            "tobottom-toleft" -> TO_BOTTOM_TO_LEFT
            else -> TO_RIGHT_TO_BOTTOM
        }
    }
}

enum class PrinterLanguage(
    val displayName: String,
    val subtitle: String,
) {
    BRLASER_DCP_L2520D(
        displayName = "Brother Laser (brlaser)",
        subtitle = "Compatible Brother monochrome laser PJL + 1030m raster",
    ),
}

enum class AppLanguage(
    val code: String,
    val englishLabel: String,
    val nativeLabel: String,
    val isEnglish: Boolean,
    val nativeLanguageWord: String,
) {
    ENGLISH("en-US", "English (US)", "English (US)", true, "Language"),
    ENGLISH_CA("en-CA", "English (Canada)", "English (Canada)", true, "Language"),
    ENGLISH_IN("en-IN", "English (India)", "English (India)", true, "Language"),
    ENGLISH_AU("en-AU", "English (Australia)", "English (Australia)", true, "Language"),
    ENGLISH_GB("en-GB", "English (UK)", "English (UK)", true, "Language"),
    HINDI("hi", "Hindi", "हिन्दी", false, "भाषा"),
    JAPANESE("ja", "Japanese", "日本語", false, "言語"),
    GERMAN("de", "German", "Deutsch", false, "Sprache"),
    FRENCH("fr", "French", "Français", false, "Langue"),
    CHINESE_SIMPLIFIED("zh-CN", "Simplified Chinese", "简体中文", false, "语言"),
    SPANISH("es", "Spanish", "Español", false, "Idioma"),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", false, "Bahasa"),
    VIETNAMESE("vi", "Vietnamese", "Tiếng Việt", false, "Ngôn ngữ"),
    THAI("th", "Thai", "ไทย", false, "ภาษา"),
    SWEDISH("sv", "Swedish", "Svenska", false, "Språk"),
    DANISH("da", "Danish", "Dansk", false, "Sprog"),
    NORWEGIAN("no", "Norwegian", "Norsk", false, "Språk"),
    FINNISH("fi", "Finnish", "Suomi", false, "Kieli");

    /**
     * Shows "Language" for any English locale, and "Language (<native word>)" for non-English locales
     * so users can always find the language picker regardless of the active script.
     */
    val languageSettingLabel: String
        get() = if (isEnglish) "Language" else "Language ($nativeLanguageWord)"

    companion object {
        fun fromCode(code: String?): AppLanguage {
            val raw = code?.trim() ?: return ENGLISH
            val normalized = raw.replace('_', '-')
            if (normalized.equals("en", ignoreCase = true)) return ENGLISH
            if (normalized.equals("in", ignoreCase = true)) return INDONESIAN
            if (normalized.equals("nb", ignoreCase = true) || normalized.equals("nn", ignoreCase = true)) return NORWEGIAN
            if (normalized.equals("zh", ignoreCase = true) || normalized.equals("zh-Hans", ignoreCase = true)) return CHINESE_SIMPLIFIED
            return entries.firstOrNull {
                it.code.equals(normalized, ignoreCase = true) ||
                    it.name.equals(raw, ignoreCase = true)
            } ?: ENGLISH
        }
    }
}

data class PrintJobOptions(
    val pagesPerSheet: PagesPerSheet = PagesPerSheet.ONE,
    val nUpDirection: NUpPresentationDirection = NUpPresentationDirection.TO_RIGHT_TO_BOTTOM,
    val duplexMode: DuplexMode = DuplexMode.SIMPLEX,
    val paperSize: PaperSize = PaperSize.A4,
    val resolution: PrintResolution = PrintResolution.DPI_600,
    val printerLanguage: PrinterLanguage = PrinterLanguage.BRLASER_DCP_L2520D,
    val copies: Int = 1,
    val collateCopies: Boolean = true,
    val tonerSave: Boolean = false,
    val drawNUpBorders: Boolean = false,
    val ditherImages: Boolean = true,
    val margins: PageMargins = PageMargins.DEFAULT,
    val scaling: PrintScaling = PrintScaling.AUTO_FIT,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val pageRanges: List<IntRange> = emptyList(),
    val reversePageOrder: Boolean = false,
    val mediaType: MediaType = MediaType.REGULAR,
    val densityAdjust: Int = 0,
    val imagePanX: Float = 0f,
    val imagePanY: Float = 0f,
)

data class PrintServerSettings(
    val printerName: String = "Brother Laser Printer",
    val ippPort: Int = 8631,
    val mdnsEnabled: Boolean = true,
    val printerLanguage: PrinterLanguage = PrinterLanguage.BRLASER_DCP_L2520D,
    val serverUuid: String = "",
    val autoStartServerOnOtg: Boolean = false,
    val defaultPrintResolution: PrintResolution = PrintResolution.DPI_600,
    val defaultScanDpi: Int = 600,
    val appLanguage: AppLanguage = AppLanguage.ENGLISH,
    val recentScansViewMode: RecentScansViewMode = RecentScansViewMode.GRID,
    val scanPagesViewMode: RecentScansViewMode = RecentScansViewMode.GRID,
)

enum class PrintJobSource(val label: String) {
    TEST_PAGE("Test Page"),
    DIRECT_LOCAL("Direct Print"),
    ANDROID_PRINT_SERVICE("Android Print"),
    IPP_NETWORK("Wi-Fi IPP"),
    WIRELESS_CLIENT("Remote Print"),
}

enum class PrintJobStatus(val ippStateCode: Int, val label: String) {
    PENDING(3, "Queued"),
    RENDERING(5, "Preparing Pages"),
    TRANSMITTING(5, "Sending to Printer"),
    COMPLETED(9, "Printed"),
    CANCELED(7, "Canceled"),
    FAILED(8, "Failed"),
}

data class PrintJobRecord(
    val id: Int,
    val title: String,
    val source: PrintJobSource,
    val clientName: String,
    val mimeType: String,
    val sourcePages: Int,
    val imposedSheets: Int,
    val pagesPerSheet: PagesPerSheet,
    val duplexMode: DuplexMode,
    val copies: Int = 1,
    val status: PrintJobStatus = PrintJobStatus.PENDING,
    val progress: Float = 0f,
    val bytesSent: Long = 0L,
    val errorMessage: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val completedAtMs: Long? = null,
)

enum class UsbConnectionStatus {
    DISCONNECTED,
    PERMISSION_REQUIRED,
    CONNECTED,
    PRINTING,
    ERROR,
}

data class UsbPrinterInfo(
    val deviceId: Int = -1,
    val deviceName: String = "",
    val vendorId: Int = 0,
    val productId: Int = 0,
    val manufacturer: String = "",
    val productName: String = "",
    val serialNumber: String = "",
    val ieee1284DeviceId: String = "",
    val interfaceIndex: Int = 0,
    val hasPermission: Boolean = false,
    val status: UsbConnectionStatus = UsbConnectionStatus.DISCONNECTED,
    val statusMessage: String = "Connect a compatible Brother USB printer via OTG cable",
) {
    val isConnected: Boolean
        get() = status == UsbConnectionStatus.CONNECTED || status == UsbConnectionStatus.PRINTING

    val isDetected: Boolean
        get() = status == UsbConnectionStatus.PERMISSION_REQUIRED || isConnected

    val isBrotherVendor: Boolean get() = vendorId == 0x04F9

    val vidPidHex: String
        get() = if (vendorId != 0) String.format("%04X:%04X", vendorId, productId) else ""

    val displayTitle: String
        get() = when {
            !isDetected -> "No Printer Connected"
            productName.isNotBlank() -> productName
            manufacturer.isNotBlank() -> "$manufacturer USB Printer"
            else -> "USB Printer ($vidPidHex)"
        }
}

/**
 * Unified printer connection mode across Case I (Direct OTG) and Case II (Wireless Remote Server).
 */
enum class PrinterConnectionMode(val badgeLabel: String) {
    NONE("Not Connected"),
    USB_OTG("USB OTG"),
    WIRELESS_REMOTE("Wi-Fi Server"),
}

data class DiscoveredNetworkPrinter(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val ippPath: String = "/ipp/print",
    val supportsPrint: Boolean = true,
    val supportsScan: Boolean = false,
    val scanApiPath: String = "/api/scan",
    val model: String = "",
    val isPrintBridgeServer: Boolean = false,
    val supportsAdf: Boolean = false,
    val supportsAdfDuplex: Boolean = false,
) {
    val ippHttpUrl: String get() = "http://$host:$port${if (ippPath.startsWith("/")) ippPath else "/$ippPath"}"
    val ippUri: String get() = "ipp://$host:$port${if (ippPath.startsWith("/")) ippPath else "/$ippPath"}"
    val scanApiBaseUrl: String get() = "http://$host:$port${if (scanApiPath.startsWith("/")) scanApiPath else "/$scanApiPath"}"
    val statusApiUrl: String get() = "http://$host:$port/api/status"
    val displayAddress: String get() = "$host:$port"
}

data class ActivePrinterState(
    val mode: PrinterConnectionMode = PrinterConnectionMode.NONE,
    val displayName: String = "No Printer Connected",
    val subtitle: String = "Connect via USB OTG or find a wireless server",
    val addressOrId: String = "",
    val supportsPrint: Boolean = false,
    val supportsScan: Boolean = false,
    val supportsAdf: Boolean = false,
    val supportsAdfDuplex: Boolean = false,
    val canHostRemoteServer: Boolean = false,
    val remotePrinter: DiscoveredNetworkPrinter? = null,
) {
    val isConnected: Boolean get() = mode != PrinterConnectionMode.NONE
    val isWirelessClient: Boolean get() = mode == PrinterConnectionMode.WIRELESS_REMOTE
}

enum class ScanColorOption(
    val displayName: String,
    val shortLabel: String,
) {
    COLOR("Full Color (24-bit)", "Color"),
    GRAYSCALE("Grayscale (8-bit)", "Grayscale"),
    MONOCHROME("Black & White (1-bit)", "B&W"),
}

enum class ScanSourceOption(
    val displayName: String,
) {
    FLATBED("Flatbed Glass"),
    ADF_SIMPLEX("Feeder (1-Sided)"),
    ADF_DUPLEX("Feeder (2-Sided)"),
}

enum class ScanPageSizeOption(
    val displayName: String,
    val dimensionsLabel: String,
    val widthMm: Double,
    val heightMm: Double,
) {
    A4("A4", "210×297 mm", 210.0, 297.0),
    A3("A3", "297×420 mm", 297.0, 420.0),
    LETTER("Letter", "8.5×11 in", 215.9, 279.4),
    A5("A5", "148×210 mm", 148.0, 210.0),
    ID_CARD("Card / Half", "105×148 mm", 105.0, 148.0);

    val width300Dpi: Int get() = kotlin.math.round((widthMm / 25.4) * 300.0).toInt()
    val height300Dpi: Int get() = kotlin.math.round((heightMm / 25.4) * 300.0).toInt()
    val widthPdfPoints: Int get() = kotlin.math.round((widthMm / 25.4) * 72.0).toInt()
    val heightPdfPoints: Int get() = kotlin.math.round((heightMm / 25.4) * 72.0).toInt()
}

enum class IdCardMergeLayout(val displayName: String) {
    VERTICAL("Vertical (Top & Bottom)"),
    HORIZONTAL("Horizontal (Side by Side)"),
}

data class ScanParameters(
    val source: ScanSourceOption = ScanSourceOption.FLATBED,
    val colorMode: ScanColorOption = ScanColorOption.COLOR,
    val dpi: Int = DEFAULT_DPI,
    val pageSize: ScanPageSizeOption = ScanPageSizeOption.A4,
    val brightness: Int = 50,
    val contrast: Int = 50,
    val twoInOneIdScan: Boolean = false,
    val idCardLayout: IdCardMergeLayout = IdCardMergeLayout.VERTICAL,
) {
    companion object {
        const val DEFAULT_DPI = 600
        val SUPPORTED_DPI_OPTIONS: List<Int> = listOf(150, 300, 600, 1200, 2400)

        fun normalizeDpi(requested: Int): Int =
            SUPPORTED_DPI_OPTIONS.minByOrNull { kotlin.math.abs(it - requested) } ?: DEFAULT_DPI
    }
}

enum class ScanExportMode(val title: String, val shortFormat: String, val subtitle: String) {
    SINGLE_PDF("Single PDF File", "PDF", "Combine all pages into one PDF"),
    SEPARATE_JPEG("Separate JPEG Images", "JPG", "Save each page as a JPG image"),
    SEPARATE_PNG("Separate PNG Images", "PNG", "Save each page as a PNG image"),
    SEPARATE_PDF("Separate PDF Files", "PDF", "Save each page as a separate PDF"),
}

enum class RecentScansViewMode {
    GRID,
    SMALL_TILES,
    LIST,
}

enum class RecentScansSortBy(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    NAME("Name A–Z"),
    PAGES("Most Pages"),
}

data class RecentScanRecord(
    val id: String,
    val title: String,
    val isPdf: Boolean,
    val pageCount: Int,
    val fileSizeBytes: Long,
    val exportLocation: String,
    val thumbnailPath: String,
    val localFilePath: String? = null,
    val pagePaths: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
)

enum class SupplyStatus(val label: String) {
    OK("Good"),
    LOW("Low"),
    EMPTY("Replace / Empty"),
    UNKNOWN("Not Reported"),
}

enum class PaperAvailability(val label: String) {
    AVAILABLE("Paper Loaded"),
    EMPTY("Out of Paper"),
    JAM("Paper Jam"),
    UNKNOWN("Unknown"),
}

data class PrinterDiagnostics(
    val online: Boolean? = null,
    val hasHardwareError: Boolean? = null,
    val paperAvailability: PaperAvailability = PaperAvailability.UNKNOWN,
    val loadedPaperSize: String? = null,
    val loadedMediaType: String? = null,
    val tonerStatus: SupplyStatus = SupplyStatus.UNKNOWN,
    val tonerPercent: Int? = null,
    val drumStatus: SupplyStatus = SupplyStatus.UNKNOWN,
    val drumPercent: Int? = null,
    val lcdDisplayMessage: String? = null,
    val pjlStatusCode: Int? = null,
    val totalPageCount: Int? = null,
    val economyMode: Boolean? = null,
    val duplexEnabled: Boolean? = null,
    val commandLanguages: List<String> = emptyList(),
    val firmwareId: String? = null,
    val portStatusSupported: Boolean = false,
    val pjlSupported: Boolean = false,
    val telemetrySourceLabel: String? = null,
    val queriedAtMs: Long = System.currentTimeMillis(),
)

enum class MdnsStatus(val label: String) {
    INACTIVE("Inactive"),
    REGISTERING("Registering..."),
    ADVERTISING("Broadcasting _ipp._tcp"),
    ERROR("mDNS Error"),
}

enum class DeviceBusyState {
    AVAILABLE,
    PRINTING,
    SCANNING,
}

data class ScannerAvailabilityState(
    val isAvailable: Boolean = false,
    val supportsAdf: Boolean = false,
    val supportsAdfDuplex: Boolean = false,
    val isScanning: Boolean = false,
    val mdnsStatus: MdnsStatus = MdnsStatus.INACTIVE,
)

data class NetworkServerState(
    val isRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 8631,
    val mdnsStatus: MdnsStatus = MdnsStatus.INACTIVE,
    val mdnsServiceName: String = "",
    val activeRequests: Int = 0,
    val totalIppRequests: Int = 0,
    val wakeLockActive: Boolean = false,
    val wifiLockActive: Boolean = false,
    val lastError: String? = null,
) {
    val ippUri: String
        get() = if (ipAddress != null) "ipp://$ipAddress:$port/ipp/print" else "Wi-Fi not connected"
    val scanApiUri: String
        get() = if (ipAddress != null) "http://$ipAddress:$port/api/scan" else "Wi-Fi not connected"
    val httpConsoleUrl: String
        get() = if (ipAddress != null) "http://$ipAddress:$port/" else ""
}

data class ConnectedClientDevice(
    val id: String,
    val displayName: String,
    val modelName: String = "",
    val ipAddress: String,
    val lastActivityLabel: String = "Connected",
    val lastSeenMs: Long = System.currentTimeMillis(),
)

enum class LogCategory(val badge: String) {
    IPP("IPP"),
    MDNS("mDNS"),
    USB("USB"),
    SPOOLER("JOB"),
    SCAN("SCAN"),
}

data class ProtocolLogEntry(
    val id: Long,
    val timestampMs: Long,
    val category: LogCategory,
    val message: String,
    val isError: Boolean = false,
)
