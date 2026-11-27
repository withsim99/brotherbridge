package com.example.ui.i18n

import com.example.model.AppLanguage

/**
 * Central translation dispatcher for all non-English/non-Hindi locales.
 * English (en-US, en-CA, en-IN, en-AU, en-GB) and Hindi (hi) bypass this object completely
 * so their existing strings remain 100% unchanged.
 */
internal object AppTranslations {

    fun translate(lang: AppLanguage, en: String): String {
        val map = dictionaryFor(lang) ?: return en
        map[en]?.let { return it }
        return translateDynamic(map, en) ?: en
    }

    private fun dictionaryFor(lang: AppLanguage): Map<String, String>? = when (lang) {
        AppLanguage.JAPANESE -> TranslationsEastAsia.JA
        AppLanguage.CHINESE_SIMPLIFIED -> TranslationsEastAsia.ZH_CN
        AppLanguage.THAI -> TranslationsEastAsia.TH
        AppLanguage.GERMAN -> TranslationsWestEurope.DE
        AppLanguage.FRENCH -> TranslationsWestEurope.FR
        AppLanguage.SPANISH -> TranslationsWestEurope.ES
        AppLanguage.INDONESIAN -> TranslationsSouthEastAsia.ID
        AppLanguage.VIETNAMESE -> TranslationsSouthEastAsia.VI
        AppLanguage.SWEDISH -> TranslationsNordic.SV
        AppLanguage.DANISH -> TranslationsNordic.DA
        AppLanguage.NORWEGIAN -> TranslationsNordic.NO
        AppLanguage.FINNISH -> TranslationsNordic.FI
        else -> null
    }

    private val rxSelected = Regex("^(\\d+) selected$")
    private val rxPageNum = Regex("^Page (\\d+)$")
    private val rxPageOf = Regex("^Page (\\d+) / (\\d+)$")
    private val rxPageOptions = Regex("^Page (\\d+) options$")
    private val rxPageCropPreview = Regex("^Page (\\d+) crop preview$")
    private val rxCropPage = Regex("^Crop • Page (\\d+)$")
    private val rxSavedCroppedPage = Regex("^Saved cropped Page (\\d+)$")
    private val rxShortPages = Regex("^(\\d+)p$")
    private val rxPagesDotRest = Regex("^(\\d+) pages? • (.+)$")
    private val rxCountPagesCap = Regex("^(\\d+) Pages?$")
    private val rxPagesToSheetsShort = Regex("^(\\d+)p → (\\d+)s$")
    private val rxPagesToSheets = Regex("^(\\d+)p → (\\d+) sheets?$")
    private val rxPrintCount = Regex("^Print \\((\\d+)\\)$")
    private val rxExportCount = Regex("^Export \\((\\d+)\\)$")
    private val rxExportSelected = Regex("^Export (\\d+) Selected Pages?$")
    private val rxExportPages = Regex("^Export \\((\\d+) Pages?\\)$")
    private val rxSaveSepFmt = Regex("^Save Separately \\((.+)\\)$")
    private val rxPrintQueueCount = Regex("^Print Queue \\((\\d+)\\)$")
    private val rxTelemetryCount = Regex("^Telemetry \\((\\d+)\\)$")
    private val rxCancelJob = Regex("^Cancel job #(\\d+)$")
    private val rxLocalIp = Regex("^Local IP: (.+)$")
    private val rxCopied = Regex("^Copied (.+)$")
    private val rxCopyLabel = Regex("^Copy (.+)$")
    private val rxSupportedInfo = Regex("^Supported \\((.+)\\)$")
    private val rxScanningPct = Regex("^Scanning • (\\d+)%$")
    private val rxDeletedScans = Regex("^Deleted (\\d+) scans$")
    private val rxActiveWifiIp = Regex("^Active Wi-Fi IP: (.+)$")
    private val rxStartingBgScan = Regex("^Starting background scan \\(Page (\\d+)\\)\\.\\.\\.$")
    private val rxBrightnessContrast = Regex("^Brightness (-?\\d+)% • Contrast (-?\\d+)%$")
    private val rxAutoAlignedTilt = Regex("^Auto-aligned • ([0-9.]+)° tilt detected$")
    private val rxIdMerged = Regex("^2-in-1 ID merged \\((.+) • (.+)\\)$")
    private val rxConnectedWireless = Regex("^Connected wirelessly to (.+)$")
    private val rxConnectedToAddr = Regex("^Connected to (.+) \\((.+)\\)$")
    private val rxCouldNotReach = Regex("^Could not reach printer at (.+)$")
    private val rxRefreshed = Regex("^Refreshed (.+)$")
    private val rxRequestUsbPerm = Regex("^Requesting USB OTG permission for (.+)\\.\\.\\.$")
    private val rxPrintedSummary = Regex("^Printed • (\\d+) side\\(s\\) • (\\d+) cop(?:y|ies)$")
    private val rxPrintCtaSheets = Regex("^(.+) \\((\\d+) sh\\)$")
    private val rxMm = Regex("^(\\d+) mm$")
    private val rxPublishesIppPort = Regex("^Publishes driverless IPP 2\\.0 printing on port (\\d+) for Android, iOS, Windows, macOS & Linux\\.$")
    private val rxOpenWithoutOtg = Regex("^Open BrotherBridge without OTG and tap Scan for Printers to find '(.+)', or connect manually using (.+)\\.$")
    private val rxTapPrintAnyApp = Regex("^Tap Print in any app — '(.+)' appears automatically on the same Wi-Fi\\.$")
    private val rxAddViaBonjour = Regex("^Add via Bonjour or manually with URI: (.+)$")
    private val rxChooseFileFor = Regex("^Choose a file from storage to preview and adjust print parameters for (.+)\\.$")
    private val rxSide1Captured = Regex("^Side 1 captured! Flip your ID card over on the scanner glass and press Scan to auto-crop and merge both sides \\((.+)\\) onto (.+)\\.$")

    private fun fmt(map: Map<String, String>, key: String, vararg args: String): String? {
        var template = map[key] ?: return null
        args.forEachIndexed { idx, value ->
            template = template.replace("{$idx}", value)
        }
        return template
    }

    private fun translateDynamic(map: Map<String, String>, en: String): String? {
        rxSelected.matchEntire(en)?.let { return fmt(map, "_tpl_selected", it.groupValues[1]) }
        rxPageNum.matchEntire(en)?.let { return fmt(map, "_tpl_page_num", it.groupValues[1]) }
        rxPageOf.matchEntire(en)?.let { return fmt(map, "_tpl_page_of", it.groupValues[1], it.groupValues[2]) }
        rxPageOptions.matchEntire(en)?.let { return fmt(map, "_tpl_page_options", it.groupValues[1]) }
        rxPageCropPreview.matchEntire(en)?.let { return fmt(map, "_tpl_page_crop_preview", it.groupValues[1]) }
        rxCropPage.matchEntire(en)?.let { return fmt(map, "_tpl_crop_page", it.groupValues[1]) }
        rxSavedCroppedPage.matchEntire(en)?.let { return fmt(map, "_tpl_saved_cropped_page", it.groupValues[1]) }
        rxShortPages.matchEntire(en)?.let { return fmt(map, "_tpl_short_pages", it.groupValues[1]) }
        rxPagesDotRest.matchEntire(en)?.let { return fmt(map, "_tpl_pages_dot_rest", it.groupValues[1], it.groupValues[2]) }
        rxCountPagesCap.matchEntire(en)?.let { return fmt(map, "_tpl_count_pages", it.groupValues[1]) }
        rxPagesToSheetsShort.matchEntire(en)?.let { return fmt(map, "_tpl_p_to_s", it.groupValues[1], it.groupValues[2]) }
        rxPagesToSheets.matchEntire(en)?.let { return fmt(map, "_tpl_p_to_sheets", it.groupValues[1], it.groupValues[2]) }
        rxPrintCount.matchEntire(en)?.let { return fmt(map, "_tpl_print_count", it.groupValues[1]) }
        rxExportCount.matchEntire(en)?.let { return fmt(map, "_tpl_export_count", it.groupValues[1]) }
        rxExportSelected.matchEntire(en)?.let { return fmt(map, "_tpl_export_selected", it.groupValues[1]) }
        rxExportPages.matchEntire(en)?.let { return fmt(map, "_tpl_export_pages", it.groupValues[1]) }
        rxSaveSepFmt.matchEntire(en)?.let { return fmt(map, "_tpl_save_sep_fmt", it.groupValues[1]) }
        rxPrintQueueCount.matchEntire(en)?.let { return fmt(map, "_tpl_print_queue_count", it.groupValues[1]) }
        rxTelemetryCount.matchEntire(en)?.let { return fmt(map, "_tpl_telemetry_count", it.groupValues[1]) }
        rxCancelJob.matchEntire(en)?.let { return fmt(map, "_tpl_cancel_job", it.groupValues[1]) }
        rxLocalIp.matchEntire(en)?.let { return fmt(map, "_tpl_local_ip", it.groupValues[1]) }
        rxCopied.matchEntire(en)?.let { return fmt(map, "_tpl_copied", it.groupValues[1]) }
        rxCopyLabel.matchEntire(en)?.let { return fmt(map, "_tpl_copy_label", it.groupValues[1]) }
        rxSupportedInfo.matchEntire(en)?.let { return fmt(map, "_tpl_supported_info", it.groupValues[1]) }
        rxScanningPct.matchEntire(en)?.let { return fmt(map, "_tpl_scanning_pct", it.groupValues[1]) }
        rxDeletedScans.matchEntire(en)?.let { return fmt(map, "_tpl_deleted_scans", it.groupValues[1]) }
        rxActiveWifiIp.matchEntire(en)?.let { return fmt(map, "_tpl_active_wifi_ip", it.groupValues[1]) }
        rxStartingBgScan.matchEntire(en)?.let { return fmt(map, "_tpl_starting_bg_scan", it.groupValues[1]) }
        rxBrightnessContrast.matchEntire(en)?.let { return fmt(map, "_tpl_brightness_contrast", it.groupValues[1], it.groupValues[2]) }
        rxAutoAlignedTilt.matchEntire(en)?.let { return fmt(map, "_tpl_auto_aligned_tilt", it.groupValues[1]) }
        rxIdMerged.matchEntire(en)?.let { return fmt(map, "_tpl_id_merged", it.groupValues[1], it.groupValues[2]) }
        rxConnectedWireless.matchEntire(en)?.let { return fmt(map, "_tpl_connected_wireless", it.groupValues[1]) }
        rxConnectedToAddr.matchEntire(en)?.let { return fmt(map, "_tpl_connected_to_addr", it.groupValues[1], it.groupValues[2]) }
        rxCouldNotReach.matchEntire(en)?.let { return fmt(map, "_tpl_could_not_reach", it.groupValues[1]) }
        rxRefreshed.matchEntire(en)?.let { return fmt(map, "_tpl_refreshed", it.groupValues[1]) }
        rxRequestUsbPerm.matchEntire(en)?.let { return fmt(map, "_tpl_request_usb_perm", it.groupValues[1]) }
        rxPrintedSummary.matchEntire(en)?.let { return fmt(map, "_tpl_printed_summary", it.groupValues[1], it.groupValues[2]) }
        rxPrintCtaSheets.matchEntire(en)?.let { return fmt(map, "_tpl_print_cta_sheets", it.groupValues[1], it.groupValues[2]) }
        rxMm.matchEntire(en)?.let { return fmt(map, "_tpl_mm", it.groupValues[1]) }
        rxPublishesIppPort.matchEntire(en)?.let { return fmt(map, "_tpl_publishes_ipp_port", it.groupValues[1]) }
        rxOpenWithoutOtg.matchEntire(en)?.let { return fmt(map, "_tpl_open_without_otg", it.groupValues[1], it.groupValues[2]) }
        rxTapPrintAnyApp.matchEntire(en)?.let { return fmt(map, "_tpl_tap_print_any_app", it.groupValues[1]) }
        rxAddViaBonjour.matchEntire(en)?.let { return fmt(map, "_tpl_add_via_bonjour", it.groupValues[1]) }
        rxChooseFileFor.matchEntire(en)?.let { return fmt(map, "_tpl_choose_file_for", it.groupValues[1]) }
        rxSide1Captured.matchEntire(en)?.let { return fmt(map, "_tpl_side1_captured", it.groupValues[1], it.groupValues[2]) }
        return null
    }
}
