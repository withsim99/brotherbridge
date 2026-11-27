package com.example.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.model.AppLanguage
import com.example.model.DuplexMode
import com.example.model.IdCardMergeLayout
import com.example.model.MdnsStatus
import com.example.model.PagesPerSheet
import com.example.model.PaperAvailability
import com.example.model.PaperSize
import com.example.model.PrintJobSource
import com.example.model.PrintJobStatus
import com.example.model.PrintResolution
import com.example.model.PrinterConnectionMode
import com.example.model.PrinterLanguage
import com.example.model.RecentScansSortBy
import com.example.model.ScanColorOption
import com.example.model.ScanExportMode
import com.example.model.ScanPageSizeOption
import com.example.model.ScanSourceOption
import com.example.model.SupplyStatus

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

@Composable
fun isHindi(): Boolean = LocalAppLanguage.current == AppLanguage.HINDI

@Composable
fun tr(en: String, hi: String): String =
    tr(LocalAppLanguage.current, en, hi)

fun tr(lang: AppLanguage, en: String, hi: String): String = when {
    lang.isEnglish -> en
    lang == AppLanguage.HINDI -> hi
    else -> AppTranslations.translate(lang, en)
}

@Composable
fun PrinterConnectionMode.localizedBadge(): String = when (this) {
    PrinterConnectionMode.NONE -> tr("Not Connected", "कनेक्ट नहीं है")
    PrinterConnectionMode.USB_OTG -> tr("USB OTG", "USB OTG")
    PrinterConnectionMode.WIRELESS_REMOTE -> tr("Wi-Fi Server", "वाई-फ़ाई सर्वर")
}

@Composable
fun PrintResolution.localizedLabel(): String = when (this) {
    PrintResolution.DPI_300 -> tr("300 DPI (Fast)", "300 DPI (तेज़)")
    PrintResolution.DPI_600 -> tr("600 DPI (Sharp)", "600 DPI (स्पष्ट)")
}

@Composable
fun scanDpiSubtitle(dpi: Int): String = when (dpi) {
    150 -> tr("Fast", "तेज़")
    300 -> tr("Standard", "मानक")
    600 -> tr("High Detail", "उच्च विवरण")
    1200 -> tr("Ultra HD", "अल्ट्रा HD")
    2400 -> tr("Maximum Detail", "अधिकतम विवरण")
    else -> tr("Standard", "मानक")
}

@Composable
fun PagesPerSheet.localizedShortLabel(): String = when (this) {
    PagesPerSheet.ONE -> tr("1-Up", "1-पेज")
    PagesPerSheet.TWO -> tr("2-Up", "2-पेज")
    PagesPerSheet.FOUR -> tr("4-Up", "4-पेज")
    PagesPerSheet.EIGHT -> tr("8-Up", "8-पेज")
    PagesPerSheet.SIXTEEN -> tr("16-Up", "16-पेज")
}

@Composable
fun PagesPerSheet.localizedDescription(): String = when (this) {
    PagesPerSheet.ONE -> tr("Single page per sheet", "प्रति शीट 1 पेज")
    PagesPerSheet.TWO -> tr("2 pages side-by-side", "2 पेज अगल-बगल")
    PagesPerSheet.FOUR -> tr("2×2 grid per sheet", "प्रति शीट 2×2 ग्रिड")
    PagesPerSheet.EIGHT -> tr("4×2 compact grid", "4×2 कॉम्पैक्ट ग्रिड")
    PagesPerSheet.SIXTEEN -> tr("4×4 handout grid", "4×4 हैंडआउट ग्रिड")
}

@Composable
fun DuplexMode.localizedShortLabel(): String = when (this) {
    DuplexMode.SIMPLEX -> tr("1-Sided", "एक तरफ़")
    DuplexMode.LONG_EDGE -> tr("2-Sided (Long)", "दोनों तरफ़ (लंबा)")
    DuplexMode.SHORT_EDGE -> tr("2-Sided (Short)", "दोनों तरफ़ (छोटा)")
}

@Composable
fun DuplexMode.localizedSubtitle(): String = when (this) {
    DuplexMode.SIMPLEX -> tr("Single-sided (Simplex)", "केवल एक तरफ़ (सिंप्लेक्स)")
    DuplexMode.LONG_EDGE -> tr("Auto duplex, flip like a book", "ऑटो डुप्लेक्स, किताब की तरह पलटें")
    DuplexMode.SHORT_EDGE -> tr("Auto duplex, flip like a notepad", "ऑटो डुप्लेक्स, नोटपैड की तरह पलटें")
}

@Composable
fun PaperSize.localizedDisplayName(): String = when (this) {
    PaperSize.A4 -> tr("A4 (210×297 mm)", "A4 (210×297 मिमी)")
    PaperSize.LETTER -> tr("US Letter (8.5×11 in)", "US लेटर (8.5×11 इंच)")
    PaperSize.LEGAL -> tr("US Legal (8.5×14 in)", "US लीगल (8.5×14 इंच)")
    PaperSize.A5 -> tr("A5 (148×210 mm)", "A5 (148×210 मिमी)")
    PaperSize.A6 -> tr("A6 (105×148 mm)", "A6 (105×148 मिमी)")
    PaperSize.B5 -> tr("JIS B5 (182×257 mm)", "JIS B5 (182×257 मिमी)")
    PaperSize.EXECUTIVE -> tr("Executive (7.25×10.5 in)", "एग्जीक्यूटिव (7.25×10.5 इंच)")
}

@Composable
fun ScanSourceOption.localizedDisplayName(): String = when (this) {
    ScanSourceOption.FLATBED -> tr("Flatbed Glass", "फ्लैटबेड ग्लास")
    ScanSourceOption.ADF_SIMPLEX -> tr("Feeder (1-Sided)", "फ़ीडर (एक तरफ़)")
    ScanSourceOption.ADF_DUPLEX -> tr("Feeder (2-Sided)", "फ़ीडर (दोनों तरफ़)")
}

@Composable
fun ScanColorOption.localizedShortLabel(): String = when (this) {
    ScanColorOption.COLOR -> tr("Color", "रंगीन")
    ScanColorOption.GRAYSCALE -> tr("Grayscale", "ग्रेस्केल")
    ScanColorOption.MONOCHROME -> tr("B&W", "काला-सफ़ेद")
}

@Composable
fun ScanColorOption.localizedDisplayName(): String = when (this) {
    ScanColorOption.COLOR -> tr("Full Color (24-bit)", "पूरा रंगीन (24-बिट)")
    ScanColorOption.GRAYSCALE -> tr("Grayscale (8-bit)", "ग्रेस्केल (8-बिट)")
    ScanColorOption.MONOCHROME -> tr("Black & White (1-bit)", "काला और सफ़ेद (1-बिट)")
}

@Composable
fun ScanPageSizeOption.localizedDisplayName(): String = when (this) {
    ScanPageSizeOption.A4 -> "A4"
    ScanPageSizeOption.A3 -> "A3"
    ScanPageSizeOption.LETTER -> tr("Letter", "लेटर")
    ScanPageSizeOption.A5 -> "A5"
    ScanPageSizeOption.ID_CARD -> tr("Card / Half", "कार्ड / आधा")
}

@Composable
fun IdCardMergeLayout.localizedLabel(): String = when (this) {
    IdCardMergeLayout.VERTICAL -> tr("Vertical", "लंबवत")
    IdCardMergeLayout.HORIZONTAL -> tr("Horizontal", "क्षैतिज")
}

@Composable
fun IdCardMergeLayout.localizedDescription(): String = when (this) {
    IdCardMergeLayout.VERTICAL -> tr("Top & Bottom", "ऊपर और नीचे")
    IdCardMergeLayout.HORIZONTAL -> tr("Side by Side", "अगल-बगल")
}

fun IdCardMergeLayout.localizedLabel(lang: AppLanguage): String = when (this) {
    IdCardMergeLayout.VERTICAL -> tr(lang, "Vertical", "लंबवत")
    IdCardMergeLayout.HORIZONTAL -> tr(lang, "Horizontal", "क्षैतिज")
}

@Composable
fun ScanExportMode.localizedSubtitle(): String = when (this) {
    ScanExportMode.SINGLE_PDF -> tr("Combine all pages into one PDF", "सभी पेजों को एक PDF में जोड़ें")
    ScanExportMode.SEPARATE_JPEG -> tr("Save each page as a JPG image", "प्रत्येक पेज को JPG फ़ोटो के रूप में सेव करें")
    ScanExportMode.SEPARATE_PNG -> tr("Save each page as a PNG image", "प्रत्येक पेज को PNG फ़ोटो के रूप में सेव करें")
    ScanExportMode.SEPARATE_PDF -> tr("Save each page as a separate PDF", "प्रत्येक पेज को अलग PDF के रूप में सेव करें")
}

@Composable
fun RecentScansSortBy.localizedLabel(): String = when (this) {
    RecentScansSortBy.NEWEST -> tr("Newest", "सबसे नए")
    RecentScansSortBy.OLDEST -> tr("Oldest", "सबसे पुराने")
    RecentScansSortBy.NAME -> tr("Name A–Z", "नाम A–Z")
    RecentScansSortBy.PAGES -> tr("Most Pages", "अधिकतम पेज")
}

@Composable
fun SupplyStatus.localizedLabel(): String = when (this) {
    SupplyStatus.OK -> tr("Good", "अच्छा")
    SupplyStatus.LOW -> tr("Low", "कम")
    SupplyStatus.EMPTY -> tr("Replace / Empty", "खाली / बदलें")
    SupplyStatus.UNKNOWN -> tr("Not Reported", "उपलब्ध नहीं")
}

@Composable
fun PaperAvailability.localizedLabel(): String = when (this) {
    PaperAvailability.AVAILABLE -> tr("Paper Loaded", "पेपर उपलब्ध")
    PaperAvailability.EMPTY -> tr("Out of Paper", "पेपर खत्म")
    PaperAvailability.JAM -> tr("Paper Jam", "पेपर जैम")
    PaperAvailability.UNKNOWN -> tr("Unknown", "अज्ञात")
}

@Composable
fun MdnsStatus.localizedLabel(): String = when (this) {
    MdnsStatus.INACTIVE -> tr("Inactive", "निष्क्रिय")
    MdnsStatus.REGISTERING -> tr("Registering...", "रजिस्टर हो रहा है...")
    MdnsStatus.ADVERTISING -> tr("Broadcasting _ipp._tcp", "_ipp._tcp प्रसारित हो रहा है")
    MdnsStatus.ERROR -> tr("mDNS Error", "mDNS त्रुटि")
}

@Composable
fun PrintJobSource.localizedLabel(): String = when (this) {
    PrintJobSource.TEST_PAGE -> tr("Test Page", "टेस्ट पेज")
    PrintJobSource.DIRECT_LOCAL -> tr("Direct Print", "डायरेक्ट प्रिंट")
    PrintJobSource.ANDROID_PRINT_SERVICE -> tr("Android Print", "एंड्रॉइड प्रिंट")
    PrintJobSource.IPP_NETWORK -> tr("Wi-Fi IPP", "वाई-फ़ाई IPP")
    PrintJobSource.WIRELESS_CLIENT -> tr("Remote Print", "रिमोट प्रिंट")
}

@Composable
fun PrintJobStatus.localizedLabel(): String = when (this) {
    PrintJobStatus.PENDING -> tr("Queued", "कतार में")
    PrintJobStatus.RENDERING -> tr("Preparing Pages", "पेज तैयार हो रहे हैं")
    PrintJobStatus.TRANSMITTING -> tr("Sending to Printer", "प्रिंटर को भेजा जा रहा है")
    PrintJobStatus.COMPLETED -> tr("Printed", "प्रिंट हो गया")
    PrintJobStatus.CANCELED -> tr("Canceled", "रद्द किया गया")
    PrintJobStatus.FAILED -> tr("Failed", "विफल")
}

@Composable
fun PrinterLanguage.localizedDisplayName(): String = when (this) {
    PrinterLanguage.BRLASER_DCP_L2520D -> tr("Brother Laser (brlaser)", "ब्रदर लेज़र (brlaser)")
}

@Composable
fun PrinterLanguage.localizedSubtitle(): String = when (this) {
    PrinterLanguage.BRLASER_DCP_L2520D -> tr(
        "Compatible Brother monochrome laser PJL + 1030m raster",
        "संगत ब्रदर मोनोक्रोम लेज़र PJL + 1030m रास्टर",
    )
}
