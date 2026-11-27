package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ActivePrinterState
import com.example.model.PaperAvailability
import com.example.model.PrinterConnectionMode
import com.example.model.PrinterDiagnostics
import com.example.model.ScannerAvailabilityState
import com.example.model.SupplyStatus
import com.example.model.UsbPrinterInfo
import com.example.ui.PrinterInfoUiState
import com.example.ui.i18n.localizedBadge
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Printer Health & Status page (replaces the old bottom sheet popup).
 * Features pull-to-refresh and clean hardware telemetry with NO refresh button on the top model card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterHealthScreen(
    activePrinter: ActivePrinterState,
    usbInfo: UsbPrinterInfo,
    scannerState: ScannerAvailabilityState,
    state: PrinterInfoUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val diag = state.diagnostics
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(activePrinter.addressOrId, activePrinter.mode) {
        onRefresh()
    }

    PullToRefreshBox(
        isRefreshing = state.isLoading,
        onRefresh = onRefresh,
        modifier = modifier
            .fillMaxSize()
            .testTag("printer_health_screen"),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Clean Top Printer Model Card (NO refresh button)
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(50.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Print,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = activePrinter.displayName,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${activePrinter.mode.localizedBadge()}${if (activePrinter.addressOrId.isNotBlank()) " • ${activePrinter.addressOrId}" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Loading indicator while fetching initial diagnostics
                item {
                    AnimatedVisibility(visible = state.isLoading && diag == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                strokeCap = StrokeCap.Round,
                            )
                            Text(
                                text = tr(
                                    "Reading live toner, paper, and printer health...",
                                    "लाइव टोनर, पेपर और प्रिंटर स्वास्थ्य पढ़ा जा रहा है...",
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = LaserAmber.copy(alpha = 0.13f),
                            border = BorderStroke(1.dp, LaserAmber.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = null,
                                    tint = LaserAmber,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = state.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                // 2. Live Status Banner + Primary Supply & Paper Cards
                if (diag != null) {
                    item {
                        HealthLiveStatusBanner(diag = diag)
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            HealthTonerCard(
                                diag = diag,
                                modifier = Modifier.weight(1f),
                            )
                            HealthPaperCard(
                                diag = diag,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    val hasSecondary = diag.totalPageCount != null ||
                        diag.drumPercent != null ||
                        diag.drumStatus != SupplyStatus.UNKNOWN ||
                        diag.economyMode != null

                    if (hasSecondary) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                diag.totalPageCount?.let { pages ->
                                    HealthCompactMetricCard(
                                        label = tr("TOTAL PAGES", "कुल पेज"),
                                        value = "%,d".format(pages),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (diag.drumPercent != null || diag.drumStatus != SupplyStatus.UNKNOWN) {
                                    HealthCompactMetricCard(
                                        label = tr("DRUM UNIT", "ड्रम यूनिट"),
                                        value = diag.drumPercent?.let { "$it%" } ?: diag.drumStatus.localizedLabel(),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                diag.economyMode?.let { eco ->
                                    HealthCompactMetricCard(
                                        label = tr("TONER SAVE", "टोनर सेव"),
                                        value = if (eco) tr("ON", "चालू") else tr("OFF", "बंद"),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Hardware & Connection Telemetry Card
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = tr("DEVICE & TELEMETRY DETAILS", "डिवाइस और टेलीमेट्री विवरण"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            HealthDetailRow(tr("Model", "मॉडल"), activePrinter.displayName)
                            HealthDetailRow(tr("Connection", "कनेक्शन"), activePrinter.mode.localizedBadge())
                            if (activePrinter.addressOrId.isNotBlank()) {
                                HealthDetailRow(
                                    label = if (activePrinter.isWirelessClient) {
                                        tr("Remote Server", "रिमोट सर्वर")
                                    } else {
                                        "USB VID : PID"
                                    },
                                    value = activePrinter.addressOrId,
                                    mono = true,
                                )
                            }
                            if (activePrinter.mode == PrinterConnectionMode.USB_OTG && usbInfo.serialNumber.isNotBlank()) {
                                HealthDetailRow(tr("Serial Number", "सीरियल नंबर"), usbInfo.serialNumber, mono = true)
                            }
                            if (diag != null && diag.commandLanguages.isNotEmpty()) {
                                HealthDetailRow(tr("Languages", "प्रोटोकॉल भाषाएँ"), diag.commandLanguages.joinToString(", "), mono = true)
                            }
                            if (!diag?.firmwareId.isNullOrBlank()) {
                                HealthDetailRow(tr("Firmware / ID", "फर्मवेयर / ID"), diag!!.firmwareId!!, mono = true)
                            }
                            val flatbedLabel = tr("Flatbed", "फ्लैटबेड")
                            val adfDuplexLabel = tr("ADF Duplex", "ADF दोनों तरफ़")
                            val adfLabel = "ADF"
                            val scanInfo = if (activePrinter.supportsScan) {
                                buildList {
                                    add(flatbedLabel)
                                    if (activePrinter.supportsAdf || scannerState.supportsAdf) {
                                        add(if (activePrinter.supportsAdfDuplex || scannerState.supportsAdfDuplex) adfDuplexLabel else adfLabel)
                                    }
                                }.joinToString(" + ")
                            } else {
                                tr("Not supported on this model", "इस मॉडल पर समर्थित नहीं")
                            }
                            HealthDetailRow(
                                tr("Scanner", "स्कैनर"),
                                if (activePrinter.supportsScan) tr("Supported ($scanInfo)", "समर्थित ($scanInfo)") else scanInfo,
                            )
                            if (diag != null) {
                                val fallbackDesc = tr("Device Descriptor", "डिवाइस विवरण")
                                val channels = diag.telemetrySourceLabel ?: buildList {
                                    if (diag.portStatusSupported) add("IEEE 1284 Port Status")
                                    if (diag.pjlSupported) add("Bidirectional PJL")
                                }.ifEmpty { listOf(fallbackDesc) }.joinToString(" • ")
                                HealthDetailRow(tr("Telemetry Source", "टेलीमेट्री स्रोत"), channels)
                                HealthDetailRow(tr("Last Checked", "अंतिम जाँच"), timeFormatter.format(Date(diag.queriedAtMs)), mono = true)
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = tr(
                            "Pull down anywhere on this page to check live status again.",
                            "लाइव स्थिति दोबारा जाँचने के लिए इस पेज को नीचे खींचें।",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun HealthLiveStatusBanner(diag: PrinterDiagnostics) {
    val isAlert = diag.hasHardwareError == true ||
        diag.paperAvailability == PaperAvailability.EMPTY ||
        diag.paperAvailability == PaperAvailability.JAM ||
        diag.tonerStatus == SupplyStatus.EMPTY
    val isWarning = !isAlert && (
        diag.tonerStatus == SupplyStatus.LOW ||
            diag.drumStatus == SupplyStatus.LOW ||
            diag.online == false
        )

    val accent = when {
        isAlert -> ThermalRed
        isWarning -> LaserAmber
        else -> SignalEmerald
    }
    val icon = when {
        isAlert -> Icons.Rounded.ErrorOutline
        isWarning -> Icons.Rounded.WarningAmber
        else -> Icons.Rounded.CheckCircle
    }
    val headline = when {
        !diag.lcdDisplayMessage.isNullOrBlank() -> diag.lcdDisplayMessage
        diag.paperAvailability == PaperAvailability.JAM -> tr("Paper Jam Detected", "पेपर जैम पाया गया")
        diag.paperAvailability == PaperAvailability.EMPTY -> tr("Out of Paper", "पेपर खत्म")
        diag.tonerStatus == SupplyStatus.EMPTY -> tr("Replace Toner Cartridge", "टोनर कार्ट्रिज बदलें")
        diag.tonerStatus == SupplyStatus.LOW -> tr("Toner Low", "टोनर कम है")
        diag.online == false -> tr("Printer Offline", "प्रिंटर ऑफ़लाइन है")
        diag.hasHardwareError == true -> tr("Printer Attention Required", "प्रिंटर पर ध्यान दें")
        diag.online == true -> tr("Printer Ready & Online", "प्रिंटर तैयार और ऑनलाइन है")
        else -> tr("Printer Connected", "प्रिंटर कनेक्ट है")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("printer_live_status_banner"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = accent.copy(alpha = 0.2f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val statusWord = if (diag.online == false) tr("Offline", "ऑफ़लाइन") else tr("Online", "ऑनलाइन")
                val codePrefix = tr(" • Status Code ", " • स्टेटस कोड ")
                val subtitle = buildString {
                    append(statusWord)
                    diag.pjlStatusCode?.let { append("$codePrefix$it") }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthTonerCard(
    diag: PrinterDiagnostics,
    modifier: Modifier = Modifier,
) {
    val accent = when (diag.tonerStatus) {
        SupplyStatus.OK -> SignalEmerald
        SupplyStatus.LOW -> LaserAmber
        SupplyStatus.EMPTY -> ThermalRed
        SupplyStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = diag.tonerStatus.localizedLabel()
    val valueText = diag.tonerPercent?.let { "$it% ($statusLabel)" } ?: statusLabel
    val subtitle = when (diag.tonerStatus) {
        SupplyStatus.EMPTY -> tr("Install a new toner cartridge", "नया टोनर कार्ट्रिज लगाएँ")
        SupplyStatus.LOW -> tr("Prepare replacement cartridge", "नया कार्ट्रिज तैयार रखें")
        SupplyStatus.OK -> tr("Cartridge level healthy", "टोनर स्तर अच्छा है")
        SupplyStatus.UNKNOWN -> tr("Not exposed by printer", "प्रिंटर द्वारा उपलब्ध नहीं")
    }

    HealthMetricTile(
        title = tr("Toner", "टोनर"),
        value = valueText,
        subtitle = subtitle,
        icon = Icons.Outlined.InvertColors,
        accent = accent,
        progress = diag.tonerPercent?.let { (it / 100f).coerceIn(0f, 1f) },
        modifier = modifier.testTag("printer_toner_tile"),
    )
}

@Composable
private fun HealthPaperCard(
    diag: PrinterDiagnostics,
    modifier: Modifier = Modifier,
) {
    val accent = when (diag.paperAvailability) {
        PaperAvailability.AVAILABLE -> SignalEmerald
        PaperAvailability.EMPTY, PaperAvailability.JAM -> ThermalRed
        PaperAvailability.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fallbackTray = when (diag.paperAvailability) {
        PaperAvailability.AVAILABLE -> tr("Tray ready for printing", "ट्रे प्रिंटिंग के लिए तैयार है")
        PaperAvailability.EMPTY -> tr("Load paper in input tray", "इनपुट ट्रे में पेपर डालें")
        PaperAvailability.JAM -> tr("Clear paper path & retry", "पेपर पथ साफ़ करें और पुनः प्रयास करें")
        PaperAvailability.UNKNOWN -> tr("Tray status unavailable", "ट्रे की स्थिति उपलब्ध नहीं")
    }
    val trayDetail = listOfNotNull(diag.loadedPaperSize, diag.loadedMediaType)
        .joinToString(" • ")
        .ifBlank { fallbackTray }

    HealthMetricTile(
        title = tr("Paper Tray", "पेपर ट्रे"),
        value = diag.paperAvailability.localizedLabel(),
        subtitle = trayDetail,
        icon = Icons.Outlined.Description,
        accent = accent,
        progress = null,
        modifier = modifier.testTag("printer_paper_tile"),
    )
}

@Composable
private fun HealthMetricTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.14f),
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round,
                )
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HealthCompactMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HealthDetailRow(
    label: String,
    value: String,
    mono: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = value,
                style = if (mono) MonoCodeStyle else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}
