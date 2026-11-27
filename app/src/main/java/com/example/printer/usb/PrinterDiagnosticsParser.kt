package com.example.printer.usb

import com.example.model.PaperAvailability
import com.example.model.PrinterDiagnostics
import com.example.model.SupplyStatus

/**
 * Pure parser for real USB Printer Class (IEEE 1284) port status, device ID strings,
 * and bidirectional Brother/HP PJL status responses (`@PJL INFO STATUS`, `PAGECOUNT`,
 * `NEXTCARE`, `VARIABLES`, `ID`).
 */
object PrinterDiagnosticsParser {

    /**
     * Standard IEEE 1284 USB Printer Class `GET_PORT_STATUS` byte masks:
     * - Bit 5 (0x20): Paper Empty (1 = paper out, 0 = paper loaded)
     * - Bit 4 (0x10): Selected / Online (1 = selected/online, 0 = offline)
     * - Bit 3 (0x08): Not Error (1 = no error, 0 = error/fault)
     */
    const val PORT_STATUS_PAPER_EMPTY_MASK = 0x20
    const val PORT_STATUS_SELECTED_MASK = 0x10
    const val PORT_STATUS_NO_ERROR_MASK = 0x08

    /**
     * Standard PJL + Brother inquiry payload sent over USB Bulk OUT to poll real printer status.
     */
    val PJL_DIAGNOSTICS_QUERY: ByteArray = buildString {
        append("\u001B%-12345X@PJL\r\n")
        append("@PJL INFO ID\r\n")
        append("@PJL INFO STATUS\r\n")
        append("@PJL INFO PAGECOUNT\r\n")
        append("@PJL INFO NEXTCARE\r\n")
        append("@PJL INFO VARIABLES\r\n")
        append("\u001B%-12345X\r\n")
    }.toByteArray(Charsets.US_ASCII)

    fun parse(
        portStatusByte: Int?,
        ieee1284DeviceId: String,
        pjlResponseText: String,
        nowMs: Long = System.currentTimeMillis(),
    ): PrinterDiagnostics {
        val portSupported = portStatusByte != null && portStatusByte >= 0
        var online: Boolean? = if (portSupported) (portStatusByte!! and PORT_STATUS_SELECTED_MASK) != 0 else null
        var hasError: Boolean? = if (portSupported) (portStatusByte!! and PORT_STATUS_NO_ERROR_MASK) == 0 else null
        var paperAvailability: PaperAvailability = when {
            !portSupported -> PaperAvailability.UNKNOWN
            (portStatusByte!! and PORT_STATUS_PAPER_EMPTY_MASK) != 0 -> PaperAvailability.EMPTY
            else -> PaperAvailability.AVAILABLE
        }

        val cmdLanguages = parseCommandLanguages(ieee1284DeviceId)
        val cleanPjl = pjlResponseText.replace('\u000C', '\n').trim()
        val pjlSupported = cleanPjl.contains("@PJL", ignoreCase = true)

        var lcdMessage: String? = null
        var pjlCode: Int? = null
        var totalPages: Int? = null
        var tonerPercent: Int? = null
        var tonerStatus = SupplyStatus.UNKNOWN
        var drumPercent: Int? = null
        var drumStatus = SupplyStatus.UNKNOWN
        var loadedPaperSize: String? = null
        var loadedMediaType: String? = null
        var economyMode: Boolean? = null
        var duplexEnabled: Boolean? = null
        var firmwareId: String? = null

        if (pjlSupported) {
            val sections = splitPjlSections(cleanPjl)

            // 1. @PJL INFO ID
            sections["INFO ID"]?.let { lines ->
                firmwareId = lines.firstOrNull { it.isNotBlank() }?.trim()?.trim('"')
            }

            // 2. @PJL INFO STATUS
            sections["INFO STATUS"]?.let { lines ->
                val map = parseKeyValues(lines)
                map["CODE"]?.toIntOrNull()?.let { pjlCode = it }
                map["DISPLAY"]?.trim('"')?.takeIf { it.isNotBlank() }?.let { lcdMessage = it }
                map["ONLINE"]?.let { value ->
                    online = value.equals("TRUE", ignoreCase = true)
                }
            }

            // 3. @PJL INFO PAGECOUNT
            sections["INFO PAGECOUNT"]?.let { lines ->
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val direct = trimmed.toIntOrNull()
                    if (direct != null) {
                        totalPages = direct
                        break
                    }
                    if (trimmed.contains("=")) {
                        val v = trimmed.substringAfter('=').trim().toIntOrNull()
                        if (v != null) {
                            totalPages = v
                            break
                        }
                    }
                }
            }

            // 4. @PJL INFO NEXTCARE (Brother maintenance & consumable telemetry)
            sections["INFO NEXTCARE"]?.let { lines ->
                val map = parseKeyValues(lines)
                // Total page counter fallback
                if (totalPages == null) {
                    totalPages = listOf("PAGE", "PAGES", "TOTAL", "PAGECOUNT", "TOTALPAGE")
                        .firstNotNullOfOrNull { key -> map[key]?.extractFirstInt() }
                }
                // Toner level or status
                val tonerRaw = listOf("TONER", "BLACKTONER", "BLACK", "TNR", "TN", "TONERLIFE", "TONER_LEVEL")
                    .firstNotNullOfOrNull { key -> map[key] }
                if (tonerRaw != null) {
                    val pct = tonerRaw.extractPercentage()
                    if (pct != null) {
                        tonerPercent = pct
                        tonerStatus = statusFromPercent(pct)
                    } else {
                        tonerStatus = statusFromText(tonerRaw)
                    }
                }
                // Drum life or status
                val drumRaw = listOf("DRUM", "DRM", "DRUMLIFE", "DRUM_LEVEL", "DRUMREMAIN")
                    .firstNotNullOfOrNull { key -> map[key] }
                if (drumRaw != null) {
                    val pct = drumRaw.extractPercentage()
                    if (pct != null) {
                        drumPercent = pct
                        drumStatus = statusFromPercent(pct)
                    } else {
                        drumStatus = statusFromText(drumRaw)
                    }
                }
            }

            // 5. @PJL INFO VARIABLES (active paper, media type, eco mode, duplex)
            sections["INFO VARIABLES"]?.let { lines ->
                val map = parseKeyValues(lines)
                map["PAPER"]?.takeIf { it.isNotBlank() }?.let { loadedPaperSize = normalizePaperLabel(it) }
                map["MEDIATYPE"]?.takeIf { it.isNotBlank() }?.let { loadedMediaType = normalizeMediaLabel(it) }
                map["ECONOMODE"]?.let { v ->
                    economyMode = v.equals("ON", ignoreCase = true) || v.equals("TRUE", ignoreCase = true)
                }
                map["DUPLEX"]?.let { v ->
                    duplexEnabled = v.equals("ON", ignoreCase = true) || v.equals("TRUE", ignoreCase = true)
                }
                if (tonerPercent == null && tonerStatus == SupplyStatus.UNKNOWN) {
                    val varToner = map["TONER"] ?: map["TONERLEVEL"]
                    if (varToner != null) {
                        val pct = varToner.extractPercentage()
                        if (pct != null) {
                            tonerPercent = pct
                            tonerStatus = statusFromPercent(pct)
                        }
                    }
                }
            }

            // Refine paper, toner, drum, and error status from PJL CODE and LCD DISPLAY
            val displayLower = lcdMessage?.lowercase().orEmpty()
            when {
                displayLower.contains("jam") || (pjlCode != null && pjlCode!! in 42000..42999) || pjlCode == 40022 -> {
                    paperAvailability = PaperAvailability.JAM
                    hasError = true
                }
                displayLower.contains("no paper") || displayLower.contains("out of paper") ||
                    displayLower.contains("load paper") || displayLower.contains("paper empty") ||
                    pjlCode == 30016 || (pjlCode != null && pjlCode!! in 41000..41999) -> {
                    paperAvailability = PaperAvailability.EMPTY
                    hasError = true
                }
                paperAvailability == PaperAvailability.UNKNOWN &&
                    (displayLower.contains("ready") || displayLower.contains("sleep") || pjlCode == 10001) -> {
                    paperAvailability = PaperAvailability.AVAILABLE
                }
            }

            when {
                displayLower.contains("replace toner") || displayLower.contains("toner ended") ||
                    displayLower.contains("no toner") || pjlCode == 40039 || pjlCode == 40010 -> {
                    tonerStatus = SupplyStatus.EMPTY
                    if (tonerPercent == null) tonerPercent = 0
                    hasError = true
                }
                displayLower.contains("toner low") || displayLower.contains("low toner") ||
                    pjlCode == 10006 || pjlCode == 40038 -> {
                    if (tonerStatus == SupplyStatus.UNKNOWN || tonerStatus == SupplyStatus.OK) {
                        tonerStatus = SupplyStatus.LOW
                    }
                }
                tonerStatus == SupplyStatus.UNKNOWN &&
                    (displayLower.contains("ready") || displayLower.contains("sleep") || pjlCode == 10001) -> {
                    tonerStatus = SupplyStatus.OK
                }
            }

            when {
                displayLower.contains("replace drum") || displayLower.contains("drum end") -> {
                    drumStatus = SupplyStatus.EMPTY
                    if (drumPercent == null) drumPercent = 0
                    hasError = true
                }
                displayLower.contains("drum") && (displayLower.contains("soon") || displayLower.contains("low")) -> {
                    if (drumStatus == SupplyStatus.UNKNOWN || drumStatus == SupplyStatus.OK) {
                        drumStatus = SupplyStatus.LOW
                    }
                }
            }

            if (displayLower.contains("cover open") || pjlCode == 40021) {
                hasError = true
            }
        }

        return PrinterDiagnostics(
            online = online,
            hasHardwareError = hasError,
            paperAvailability = paperAvailability,
            loadedPaperSize = loadedPaperSize,
            loadedMediaType = loadedMediaType,
            tonerStatus = tonerStatus,
            tonerPercent = tonerPercent,
            drumStatus = drumStatus,
            drumPercent = drumPercent,
            lcdDisplayMessage = lcdMessage,
            pjlStatusCode = pjlCode,
            totalPageCount = totalPages,
            economyMode = economyMode,
            duplexEnabled = duplexEnabled,
            commandLanguages = cmdLanguages,
            firmwareId = firmwareId,
            portStatusSupported = portSupported,
            pjlSupported = pjlSupported,
            queriedAtMs = nowMs,
        )
    }

    fun parseCommandLanguages(ieee1284DeviceId: String): List<String> {
        if (ieee1284DeviceId.isBlank()) return emptyList()
        val cmdPart = ieee1284DeviceId.split(';')
            .firstOrNull {
                it.trim().startsWith("CMD:", ignoreCase = true) ||
                    it.trim().startsWith("COMMAND SET:", ignoreCase = true)
            }
            ?.substringAfter(':')
            ?.trim()
            .orEmpty()
        if (cmdPart.isBlank()) return emptyList()
        return cmdPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun splitPjlSections(raw: String): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        var currentHeader: String? = null
        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("@PJL ", ignoreCase = true)) {
                currentHeader = line.substring(5).trim().uppercase()
                result.getOrPut(currentHeader) { mutableListOf() }
            } else if (currentHeader != null) {
                result.getOrPut(currentHeader) { mutableListOf() }.add(line)
            }
        }
        return result
    }

    private fun parseKeyValues(lines: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val idx = line.indexOf('=')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().uppercase()
                // PJL INFO VARIABLES lines look like "PAPER=A4 [15 ENUMERATED]" — strip trailing metadata brackets
                val rawVal = line.substring(idx + 1).trim()
                val cleanedVal = if (rawVal.startsWith("\"")) {
                    rawVal
                } else {
                    rawVal.substringBefore('[').trim()
                }
                if (key.isNotEmpty() && cleanedVal.isNotEmpty()) {
                    map[key] = cleanedVal
                }
            }
        }
        return map
    }

    private fun String.extractFirstInt(): Int? = Regex("\\d+").find(this)?.value?.toIntOrNull()

    private fun String.extractPercentage(): Int? {
        val n = extractFirstInt() ?: return null
        return if (n in 0..100) n else null
    }

    private fun statusFromPercent(pct: Int): SupplyStatus = when {
        pct <= 5 -> SupplyStatus.EMPTY
        pct <= 20 -> SupplyStatus.LOW
        else -> SupplyStatus.OK
    }

    private fun statusFromText(raw: String): SupplyStatus {
        val lower = raw.lowercase()
        return when {
            lower.contains("end") || lower.contains("empty") || lower.contains("replace") || lower == "0" ->
                SupplyStatus.EMPTY
            lower.contains("low") || lower.contains("near") || lower.contains("soon") ->
                SupplyStatus.LOW
            lower.contains("ok") || lower.contains("full") || lower.contains("good") || lower.contains("normal") ->
                SupplyStatus.OK
            else -> SupplyStatus.UNKNOWN
        }
    }

    private fun normalizePaperLabel(raw: String): String = when (raw.uppercase()) {
        "A4" -> "A4"
        "LETTER" -> "US Letter"
        "LEGAL" -> "US Legal"
        "A5" -> "A5"
        "A6" -> "A6"
        "B5", "JISB5" -> "JIS B5"
        "EXECUTIVE" -> "Executive"
        else -> raw
    }

    private fun normalizeMediaLabel(raw: String): String = when (raw.uppercase()) {
        "REGULAR", "PLAIN" -> "Plain Paper"
        "THIN" -> "Thin Paper"
        "THICK" -> "Thick Paper"
        "THICKER" -> "Thicker Paper"
        "RECYCLED" -> "Recycled Paper"
        "BOND" -> "Bond Paper"
        "ENVELOPE" -> "Envelope"
        "LABEL", "LABELS" -> "Labels"
        else -> raw.lowercase().replaceFirstChar { it.uppercase() }
    }
}
