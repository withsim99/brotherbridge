package com.example.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.example.scanner.ScanExportManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Scanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.model.ActivePrinterState
import com.example.model.DiscoveredNetworkPrinter
import com.example.model.NetworkServerState
import com.example.model.PaperAvailability
import com.example.model.PrinterDiagnostics
import com.example.model.RecentScanRecord
import com.example.model.RecentScansViewMode
import com.example.model.SupplyStatus
import com.example.printer.network.NetworkAddressResolver
import com.example.scanner.BackgroundScanState
import com.example.ui.i18n.localizedBadge
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activePrinter: ActivePrinterState,
    diagnostics: PrinterDiagnostics?,
    serverState: NetworkServerState,
    discoveredPrinters: List<DiscoveredNetworkPrinter>,
    isDiscoveringNetwork: Boolean,
    isConnectingManual: Boolean,
    activeJobCount: Int,
    backgroundScanState: BackgroundScanState,
    recentScans: List<RecentScanRecord>,
    recentScansViewMode: RecentScansViewMode,
    onToggleRecentScansViewMode: () -> Unit,
    showAddIpDialog: Boolean,
    onDismissAddIpDialog: () -> Unit,
    onPickPdfForPrint: () -> Unit,
    onPickImageForPrint: () -> Unit,
    onPrintTestPage: () -> Unit,
    onOpenNewScan: () -> Unit,
    onOpenActiveScan: () -> Unit,
    onCancelBackgroundScan: () -> Unit,
    onOpenHealthPage: () -> Unit,
    onOpenRemoteServer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAllScans: () -> Unit,
    onOpenRecentScanCollage: (RecentScanRecord) -> Unit,
    onStartNetworkDiscovery: () -> Unit,
    onStopNetworkDiscovery: () -> Unit,
    onConnectRemotePrinter: (DiscoveredNetworkPrinter) -> Unit,
    onConnectManualRemoteHost: (String, Int, () -> Unit) -> Unit,
    onDisconnectPrinter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPrintSourceSheet by remember { mutableStateOf(false) }
    val isWifiOrHotspotEnabled = rememberIsWifiOrHotspotEnabled()

    // Show only the newest 8 scan items on Home; full browsing/sorting/selection lives in All Scans
    val previewRecentScans = remember(recentScans) {
        recentScans.sortedByDescending { it.createdAtMs }.take(8)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (activePrinter.isConnected) {
            ConnectedHomeContent(
                activePrinter = activePrinter,
                diagnostics = diagnostics,
                serverState = serverState,
                activeJobCount = activeJobCount,
                backgroundScanState = backgroundScanState,
                recentScans = previewRecentScans,
                recentScansViewMode = recentScansViewMode,
                onToggleRecentScansViewMode = onToggleRecentScansViewMode,
                onTapBigPrint = { showPrintSourceSheet = true },
                onTapBigScan = if (backgroundScanState.isScanning) onOpenActiveScan else onOpenNewScan,
                onOpenActiveScan = onOpenActiveScan,
                onCancelBackgroundScan = onCancelBackgroundScan,
                onOpenHealthPage = onOpenHealthPage,
                onOpenRemoteServer = onOpenRemoteServer,
                onOpenQueue = onOpenQueue,
                onOpenAllScans = onOpenAllScans,
                onTapRecentScan = onOpenRecentScanCollage,
            )
        } else {
            DisconnectedDiscoveryContent(
                isWifiOrHotspotEnabled = isWifiOrHotspotEnabled,
                isDiscoveringNetwork = isDiscoveringNetwork,
                discoveredPrinters = discoveredPrinters,
                backgroundScanState = backgroundScanState,
                recentScans = previewRecentScans,
                recentScansViewMode = recentScansViewMode,
                onToggleRecentScansViewMode = onToggleRecentScansViewMode,
                onOpenActiveScan = onOpenActiveScan,
                onCancelBackgroundScan = onCancelBackgroundScan,
                onOpenAllScans = onOpenAllScans,
                onTapRecentScan = onOpenRecentScanCollage,
                onStartNetworkDiscovery = onStartNetworkDiscovery,
                onStopNetworkDiscovery = onStopNetworkDiscovery,
                onConnectRemotePrinter = onConnectRemotePrinter,
            )
        }
    }

    if (showPrintSourceSheet) {
        PrintSourcePickerSheet(
            printerName = activePrinter.displayName,
            onPickPdf = {
                showPrintSourceSheet = false
                onPickPdfForPrint()
            },
            onPickImage = {
                showPrintSourceSheet = false
                onPickImageForPrint()
            },
            onPrintTestPage = {
                showPrintSourceSheet = false
                onPrintTestPage()
            },
            onDismiss = { showPrintSourceSheet = false },
        )
    }

    if (showAddIpDialog) {
        AddIpManuallyDialog(
            activePrinter = activePrinter,
            isConnecting = isConnectingManual,
            onConnect = { host, port ->
                onConnectManualRemoteHost(host, port) {
                    onDismissAddIpDialog()
                }
            },
            onBrowseWireless = {
                onDismissAddIpDialog()
                onDisconnectPrinter()
            },
            onDismiss = onDismissAddIpDialog,
        )
    }
}

@Composable
private fun ConnectedHomeContent(
    activePrinter: ActivePrinterState,
    diagnostics: PrinterDiagnostics?,
    serverState: NetworkServerState,
    activeJobCount: Int,
    backgroundScanState: BackgroundScanState,
    recentScans: List<RecentScanRecord>,
    recentScansViewMode: RecentScansViewMode,
    onToggleRecentScansViewMode: () -> Unit,
    onTapBigPrint: () -> Unit,
    onTapBigScan: () -> Unit,
    onOpenActiveScan: () -> Unit,
    onCancelBackgroundScan: () -> Unit,
    onOpenHealthPage: () -> Unit,
    onOpenRemoteServer: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAllScans: () -> Unit,
    onTapRecentScan: (RecentScanRecord) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val gridColumns = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1. Clean Printer Model Card: Status below model + Health ICON ONLY at top-right
            item {
                PrinterModelStatusCard(
                    activePrinter = activePrinter,
                    diagnostics = diagnostics,
                    onOpenHealthPage = onOpenHealthPage,
                )
            }

            // 2. Big Primary Action Tiles: Print & Flatbed Scanner (no circle around icons, bigger icons, no subtitle!)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    BigPrimaryActionTile(
                        title = stringResource(R.string.nav_print),
                        icon = Icons.Rounded.Print,
                        gradientColors = listOf(
                            MaterialTheme.colorScheme.primary,
                            Color(0xFF0A3D91),
                        ),
                        enabled = activePrinter.supportsPrint,
                        onClick = onTapBigPrint,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("big_print_button"),
                    )

                    if (activePrinter.supportsScan) {
                        BigPrimaryActionTile(
                            title = stringResource(R.string.nav_scan),
                            icon = Icons.Rounded.Scanner,
                            gradientColors = listOf(
                                SignalEmerald,
                                Color(0xFF046C4E),
                            ),
                            enabled = true,
                            onClick = onTapBigScan,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("big_scan_button"),
                        )
                    }
                }
            }

            // 3. Compact Secondary Buttons BELOW Print & Scan (OTG only: Server, and Queue when Server is ON)
            if (activePrinter.canHostRemoteServer) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SmallSecondaryToolButton(
                            label = stringResource(R.string.nav_server),
                            icon = Icons.Outlined.Router,
                            isHighlighted = serverState.isRunning,
                            statusDotColor = if (serverState.isRunning) SignalEmerald else null,
                            onClick = onOpenRemoteServer,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("small_server_button"),
                        )

                        if (serverState.isRunning) {
                            SmallSecondaryToolButton(
                                label = stringResource(R.string.nav_queue),
                                icon = Icons.Outlined.Queue,
                                badgeCount = activeJobCount,
                                onClick = onOpenQueue,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("small_queue_button"),
                            )
                        }
                    }
                }
            }

            // 4. Recent Scans Preview (active scanning item sits here as a regular item until delivery is confirmed)
            val activeTargetId = backgroundScanState.targetCollectionId
            val visibleRecentScans = if (backgroundScanState.isScanning && !activeTargetId.isNullOrBlank()) {
                recentScans.filterNot { it.id == activeTargetId }
            } else {
                recentScans
            }
            if (backgroundScanState.isScanning || visibleRecentScans.isNotEmpty()) {
                item {
                    RecentScansPreviewHeader(
                        viewMode = recentScansViewMode,
                        showViewAll = recentScans.isNotEmpty(),
                        onToggleViewMode = onToggleRecentScansViewMode,
                        onViewAll = onOpenAllScans,
                    )
                }

                if (recentScansViewMode == RecentScansViewMode.GRID) {
                    val slots = buildList {
                        if (backgroundScanState.isScanning) {
                            add(HomeRecentScanSlot.ActiveScan(backgroundScanState))
                        }
                        visibleRecentScans.forEach { add(HomeRecentScanSlot.Saved(it)) }
                    }
                    val rows = slots.chunked(gridColumns)
                    items(
                        count = rows.size,
                        key = { idx ->
                            rows[idx].joinToString("_") { slot ->
                                when (slot) {
                                    is HomeRecentScanSlot.ActiveScan -> "active_${slot.state.remoteJobId.orEmpty()}"
                                    is HomeRecentScanSlot.Saved -> slot.record.id
                                }
                            }
                        },
                    ) { rowIdx ->
                        val rowItems = rows[rowIdx]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            rowItems.forEach { slot ->
                                when (slot) {
                                    is HomeRecentScanSlot.ActiveScan -> HomeActiveScanGridCard(
                                        state = slot.state,
                                        onOpenScan = onOpenActiveScan,
                                        onCancel = onCancelBackgroundScan,
                                        modifier = Modifier.weight(1f),
                                    )
                                    is HomeRecentScanSlot.Saved -> HomeRecentScanCard(
                                        record = slot.record,
                                        onClick = { onTapRecentScan(slot.record) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            repeat(gridColumns - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    if (backgroundScanState.isScanning) {
                        item(key = "list_active_${backgroundScanState.remoteJobId.orEmpty()}") {
                            HomeActiveScanListRow(
                                state = backgroundScanState,
                                onOpenScan = onOpenActiveScan,
                                onCancel = onCancelBackgroundScan,
                            )
                        }
                    }
                    items(visibleRecentScans.size, key = { idx -> "list_${visibleRecentScans[idx].id}" }) { idx ->
                        val record = visibleRecentScans[idx]
                        HomeRecentScanListRow(
                            record = record,
                            onClick = { onTapRecentScan(record) },
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun PrinterModelStatusCard(
    activePrinter: ActivePrinterState,
    diagnostics: PrinterDiagnostics?,
    onOpenHealthPage: () -> Unit,
) {
    val isWireless = activePrinter.isWirelessClient
    val isAlert = diagnostics?.hasHardwareError == true ||
        diagnostics?.paperAvailability == PaperAvailability.EMPTY ||
        diagnostics?.paperAvailability == PaperAvailability.JAM ||
        diagnostics?.tonerStatus == SupplyStatus.EMPTY
    val isWarning = !isAlert && (
        diagnostics?.tonerStatus == SupplyStatus.LOW ||
            diagnostics?.online == false
        )

    val statusDotColor = when {
        isAlert -> ThermalRed
        isWarning -> LaserAmber
        else -> SignalEmerald
    }
    val statusHeadline = when {
        !diagnostics?.lcdDisplayMessage.isNullOrBlank() -> diagnostics!!.lcdDisplayMessage!!
        diagnostics?.paperAvailability == PaperAvailability.JAM -> tr("Paper Jam", "पेपर जैम")
        diagnostics?.paperAvailability == PaperAvailability.EMPTY -> tr("Out of Paper", "पेपर खत्म")
        diagnostics?.tonerStatus == SupplyStatus.EMPTY -> tr("Replace Toner", "टोनर बदलें")
        diagnostics?.tonerStatus == SupplyStatus.LOW -> tr("Toner Low", "टोनर कम")
        diagnostics?.online == false -> tr("Offline", "ऑफ़लाइन")
        else -> tr("Ready", "तैयार")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_printer_status_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isWireless) Icons.Rounded.Wifi else Icons.Rounded.Usb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activePrinter.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(5.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor),
                    )
                    Text(
                        text = "$statusHeadline • ${activePrinter.mode.localizedBadge()}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = statusDotColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Health ICON ONLY at top-right of the printer model card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            ) {
                IconButton(
                    onClick = onOpenHealthPage,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("printer_card_health_button"),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MonitorHeart,
                        contentDescription = stringResource(R.string.nav_health),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BigPrimaryActionTile(
    title: String,
    icon: ImageVector,
    gradientColors: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgBrush = if (enabled) {
        Brush.linearGradient(colors = gradientColors)
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
    val contentTint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(26.dp),
        shadowElevation = if (enabled) 4.dp else 0.dp,
        modifier = modifier.height(178.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentTint,
                    modifier = Modifier.size(62.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = contentTint,
                )
            }
        }
    }
}

@Composable
private fun SmallSecondaryToolButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    statusDotColor: Color? = null,
    badgeCount: Int = 0,
) {
    val containerColor = if (isHighlighted) {
        SignalEmerald.copy(alpha = 0.13f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isHighlighted) {
        SignalEmerald.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val iconTint = if (isHighlighted) SignalEmerald else MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .height(62.dp)
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (statusDotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (badgeCount > 0) {
                    BadgedBox(badge = { Badge { Text("$badgeCount") } }) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentScansPreviewHeader(
    viewMode: RecentScansViewMode,
    showViewAll: Boolean = true,
    onToggleViewMode: () -> Unit,
    onViewAll: () -> Unit,
) {
    val isGrid = viewMode == RecentScansViewMode.GRID
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .testTag("recent_scans_header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = tr("Recent Scans", "हाल के स्कैन"),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = onToggleViewMode,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("home_recent_scans_view_toggle"),
            ) {
                Icon(
                    imageVector = if (isGrid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                    contentDescription = if (isGrid) {
                        tr("Switch to list view", "सूची दृश्य में बदलें")
                    } else {
                        tr("Switch to grid view", "ग्रिड दृश्य में बदलें")
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showViewAll) {
            TextButton(
                onClick = onViewAll,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("recent_scans_view_all_button"),
            ) {
                Text(
                    text = tr("View All", "सभी देखें"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = tr("View all scans", "सभी स्कैन देखें"),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun rememberThumbnailBitmap(path: String, keyTag: Long): Bitmap? {
    return remember(path, keyTag) {
        ScanExportManager.decodeSampledBitmap(path, maxEdgePx = 480)
    }
}

@Composable
private fun HomeRecentScanCard(
    record: RecentScanRecord,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumb = rememberThumbnailBitmap(record.thumbnailPath, record.createdAtMs)
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("recent_scan_grid_${record.id}"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.86f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = record.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = if (record.isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = "${record.pageCount}p",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = dateFmt.format(Date(record.createdAtMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomeRecentScanListRow(
    record: RecentScanRecord,
    onClick: () -> Unit,
) {
    val thumb = rememberThumbnailBitmap(record.thumbnailPath, record.createdAtMs)
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("recent_scan_list_${record.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(width = 48.dp, height = 58.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = record.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = if (record.isPdf) Icons.Outlined.PictureAsPdf else Icons.Outlined.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr(
                        "${record.pageCount} page${if (record.pageCount == 1) "" else "s"} • ${dateFmt.format(Date(record.createdAtMs))}",
                        "${record.pageCount} पेज • ${dateFmt.format(Date(record.createdAtMs))}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun rememberIsWifiOrHotspotEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(NetworkAddressResolver.isWifiOrHotspotEnabled(context))
    }

    DisposableEffect(context, lifecycleOwner) {
        val mainHandler = Handler(Looper.getMainLooper())
        val refresh = {
            runCatching {
                val isEnabled = NetworkAddressResolver.isWifiOrHotspotEnabled(context)
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    enabled = isEnabled
                } else {
                    mainHandler.post { enabled = isEnabled }
                }
            }
            Unit
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refresh()
        }
        runCatching { cm?.registerDefaultNetworkCallback(networkCallback) }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        refresh()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { cm?.unregisterNetworkCallback(networkCallback) }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    return enabled
}

@Composable
private fun DisconnectedDiscoveryContent(
    isWifiOrHotspotEnabled: Boolean,
    isDiscoveringNetwork: Boolean,
    discoveredPrinters: List<DiscoveredNetworkPrinter>,
    backgroundScanState: BackgroundScanState,
    recentScans: List<RecentScanRecord>,
    recentScansViewMode: RecentScansViewMode,
    onToggleRecentScansViewMode: () -> Unit,
    onOpenActiveScan: () -> Unit,
    onCancelBackgroundScan: () -> Unit,
    onOpenAllScans: () -> Unit,
    onTapRecentScan: (RecentScanRecord) -> Unit,
    onStartNetworkDiscovery: () -> Unit,
    onStopNetworkDiscovery: () -> Unit,
    onConnectRemotePrinter: (DiscoveredNetworkPrinter) -> Unit,
) {
    val context = LocalContext.current
    val activeTargetId = backgroundScanState.targetCollectionId
    val visibleRecentScans = if (backgroundScanState.isScanning && !activeTargetId.isNullOrBlank()) {
        recentScans.filterNot { it.id == activeTargetId }
    } else {
        recentScans
    }
    val hasPastScans = backgroundScanState.isScanning || visibleRecentScans.isNotEmpty()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val gridColumns = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 1. Discovery Hero (Wi-Fi Off | Idle Ready to Scan | Active Radar Scan)
            item(key = "disconnected_discovery_hero") {
                val heroVerticalPadding = if (hasPastScans || discoveredPrinters.isNotEmpty()) 10.dp else 44.dp
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = heroVerticalPadding)
                        .testTag(
                            when {
                                !isWifiOrHotspotEnabled -> "wifi_disabled_state"
                                else -> "wifi_discovery_animation_container"
                            },
                        ),
                ) {
                    when {
                        !isWifiOrHotspotEnabled -> {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(84.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiOff,
                                        contentDescription = tr("Wi-Fi or Hotspot is off", "वाई-फ़ाई या हॉटस्पॉट बंद है"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            }
                            Text(
                                text = tr("Wi-Fi or Hotspot is Off", "वाई-फ़ाई या हॉटस्पॉट बंद है"),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = tr(
                                    "Turn on Wi-Fi or Mobile Hotspot to scan for wireless printers, or plug in via USB OTG.",
                                    "वायरलेस प्रिंटर खोजने के लिए वाई-फ़ाई या हॉटस्पॉट चालू करें, या USB OTG से जोड़ें।",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 380.dp),
                            )
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("open_wifi_settings_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr("Open Wi-Fi Settings", "वाई-फ़ाई सेटिंग्स खोलें"))
                            }
                        }

                        isDiscoveringNetwork -> {
                            val radarSize = if (hasPastScans || discoveredPrinters.isNotEmpty()) 146 else 220
                            WifiRadarPulseAnimation(sizeDp = radarSize)
                            Text(
                                text = if (discoveredPrinters.isEmpty()) {
                                    tr("Searching for Printers & Scanners...", "प्रिंटर और स्कैनर खोजे जा रहे हैं...")
                                } else {
                                    tr("Wireless Printers Found", "वायरलेस प्रिंटर मिले")
                                },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = tr(
                                    "Scanning Wi-Fi / Hotspot for wireless printers and flatbed scanners",
                                    "वाई-फ़ाई / हॉटस्पॉट पर वायरलेस प्रिंटर और स्कैनर खोजे जा रहे हैं",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 380.dp),
                            )
                            OutlinedButton(
                                onClick = onStopNetworkDiscovery,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("home_stop_scan_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Stop", "रोकें"))
                            }
                        }

                        else -> {
                            val medallionSize = if (hasPastScans || discoveredPrinters.isNotEmpty()) 124 else 168
                            IdleDiscoveryMedallion(sizeDp = medallionSize)
                            Text(
                                text = if (discoveredPrinters.isEmpty()) {
                                    tr("No Printer Connected", "कोई प्रिंटर कनेक्ट नहीं है")
                                } else {
                                    tr("Wireless Printers Found", "वायरलेस प्रिंटर मिले")
                                },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = tr(
                                    "Plug in a printer via USB OTG or press Scan to search Wi-Fi / Hotspot.",
                                    "USB OTG से प्रिंटर जोड़ें या वाई-फ़ाई / हॉटस्पॉट पर खोजने के लिए स्कैन दबाएं।",
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.widthIn(max = 380.dp),
                            )
                            Button(
                                onClick = onStartNetworkDiscovery,
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("home_manual_scan_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tr("Scan for Printers", "प्रिंटर खोजें"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }

            // 2. Discovered wireless printers (if any)
            if (discoveredPrinters.isNotEmpty()) {
                items(discoveredPrinters.size, key = { idx -> discoveredPrinters[idx].id }) { idx ->
                    val printer = discoveredPrinters[idx]
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("discovered_printer_${printer.id}"),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Print,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = printer.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val printLabel = tr("Print", "प्रिंट")
                                val scanLabel = tr("Scan", "स्कैन")
                                val caps = buildList {
                                    if (printer.supportsPrint) add(printLabel)
                                    if (printer.supportsScan) add(scanLabel)
                                }.joinToString(" + ")
                                Text(
                                    text = "${printer.displayAddress} • $caps",
                                    style = MonoCodeStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = { onConnectRemotePrinter(printer) },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Text(tr("Connect", "कनेक्ट"))
                            }
                        }
                    }
                }
            }

            // 3. Past / Recent Scans (always visible even when no device is connected, including any active scan!)
            if (hasPastScans) {
                item(key = "disconnected_recent_scans_header") {
                    RecentScansPreviewHeader(
                        viewMode = recentScansViewMode,
                        showViewAll = recentScans.isNotEmpty(),
                        onToggleViewMode = onToggleRecentScansViewMode,
                        onViewAll = onOpenAllScans,
                    )
                }

                if (recentScansViewMode == RecentScansViewMode.GRID) {
                    val slots = buildList {
                        if (backgroundScanState.isScanning) {
                            add(HomeRecentScanSlot.ActiveScan(backgroundScanState))
                        }
                        visibleRecentScans.forEach { add(HomeRecentScanSlot.Saved(it)) }
                    }
                    val rows = slots.chunked(gridColumns)
                    items(
                        count = rows.size,
                        key = { idx ->
                            "disc_row_" + rows[idx].joinToString("_") { slot ->
                                when (slot) {
                                    is HomeRecentScanSlot.ActiveScan -> "active_${slot.state.remoteJobId.orEmpty()}"
                                    is HomeRecentScanSlot.Saved -> slot.record.id
                                }
                            }
                        },
                    ) { rowIdx ->
                        val rowItems = rows[rowIdx]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            rowItems.forEach { slot ->
                                when (slot) {
                                    is HomeRecentScanSlot.ActiveScan -> HomeActiveScanGridCard(
                                        state = slot.state,
                                        onOpenScan = onOpenActiveScan,
                                        onCancel = onCancelBackgroundScan,
                                        modifier = Modifier.weight(1f),
                                    )
                                    is HomeRecentScanSlot.Saved -> HomeRecentScanCard(
                                        record = slot.record,
                                        onClick = { onTapRecentScan(slot.record) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            repeat(gridColumns - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    if (backgroundScanState.isScanning) {
                        item(key = "disc_list_active_${backgroundScanState.remoteJobId.orEmpty()}") {
                            HomeActiveScanListRow(
                                state = backgroundScanState,
                                onOpenScan = onOpenActiveScan,
                                onCancel = onCancelBackgroundScan,
                            )
                        }
                    }
                    items(visibleRecentScans.size, key = { idx -> "disc_list_${visibleRecentScans[idx].id}" }) { idx ->
                        val record = visibleRecentScans[idx]
                        HomeRecentScanListRow(
                            record = record,
                            onClick = { onTapRecentScan(record) },
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun IdleDiscoveryMedallion(sizeDp: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val hubSize = (sizeDp * 0.48f).dp
    val iconSize = (sizeDp * 0.24f).dp

    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to primary.copy(alpha = 0.22f),
                            0.52f to secondary.copy(alpha = 0.10f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        Surface(
            shape = CircleShape,
            color = primary,
            shadowElevation = 6.dp,
            modifier = Modifier.size(hubSize),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(colors = listOf(primary, secondary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Print,
                    contentDescription = tr("Ready to scan for printers", "प्रिंटर खोजने के लिए तैयार"),
                    tint = Color.White,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun WifiRadarPulseAnimation(
    sizeDp: Int,
) {
    val printerPrimary = MaterialTheme.colorScheme.primary
    val printerSecondary = MaterialTheme.colorScheme.tertiary
    val scannerPrimary = SignalEmerald
    val scannerSecondary = MaterialTheme.colorScheme.secondary

    var showFlatbedScanner by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2600L)
            showFlatbedScanner = !showFlatbedScanner
        }
    }

    val accentColor by animateColorAsState(
        targetValue = if (showFlatbedScanner) scannerPrimary else printerPrimary,
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "radar_accent_color",
    )
    val accentSecondary by animateColorAsState(
        targetValue = if (showFlatbedScanner) scannerSecondary else printerSecondary,
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "radar_accent_secondary",
    )

    val transition = rememberInfiniteTransition(label = "wifi_wave_scan")

    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "radar_sweep_angle",
    )
    val waveProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wifi_wave_progress",
    )
    val ripplePhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wifi_wave_ripple_phase",
    )
    val coreScale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "radar_core_pulse",
    )

    val signalNodes = remember {
        listOf(
            Triple(36f, 0.64f, 1.0f),
            Triple(142f, 0.74f, 0.85f),
            Triple(228f, 0.56f, 0.95f),
            Triple(312f, 0.69f, 0.90f),
        )
    }

    val wavePath = remember { Path() }
    val centerHubSize = (sizeDp * 0.30f).dp
    val centerAuraSize = (sizeDp * 0.44f).dp
    val iconSize = (sizeDp * 0.145f).dp

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .testTag("wifi_radar_animation"),
        contentAlignment = Alignment.Center,
    ) {
        // Layer 1: Fluid radiating wave bands + undulating organic wave crests (zero solid circular lines)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension / 2f

            // Soft ambient glow behind the waves
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to accentColor.copy(alpha = 0.22f),
                        0.45f to accentSecondary.copy(alpha = 0.09f),
                        0.82f to accentColor.copy(alpha = 0.025f),
                        1.0f to Color.Transparent,
                    ),
                    center = center,
                    radius = outerRadius,
                ),
                radius = outerRadius,
            )

            // 4 staggered continuous feathered radial wave swells flowing outward
            for (i in 0 until 4) {
                val rawT = (waveProgress + i * 0.25f) % 1f
                val easedT = 1f - (1f - rawT) * (1f - rawT)
                val centerFrac = 0.27f + 0.69f * easedT
                val halfBand = 0.085f + 0.045f * easedT
                val fadeIn = (rawT / 0.16f).coerceIn(0f, 1f)
                val fadeOut = (1f - rawT) * (1f - rawT)
                val waveAlpha = fadeIn * fadeOut * 0.30f

                if (waveAlpha > 0.005f) {
                    val tint = if (i % 2 == 0) accentColor else accentSecondary
                    val stop0 = (centerFrac - halfBand).coerceIn(0f, 0.996f)
                    val stop1 = (centerFrac - halfBand * 0.28f).coerceIn(stop0 + 0.001f, 0.997f)
                    val stop2 = centerFrac.coerceIn(stop1 + 0.001f, 0.998f)
                    val stop3 = (centerFrac + halfBand * 0.45f).coerceIn(stop2 + 0.001f, 0.999f)
                    val stop4 = (centerFrac + halfBand).coerceIn(stop3 + 0.001f, 1f)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                stop0 to Color.Transparent,
                                stop1 to tint.copy(alpha = waveAlpha * 0.58f),
                                stop2 to tint.copy(alpha = waveAlpha),
                                stop3 to tint.copy(alpha = waveAlpha * 0.32f),
                                stop4 to Color.Transparent,
                                1.0f to Color.Transparent,
                            ),
                            center = center,
                            radius = outerRadius,
                        ),
                        radius = outerRadius,
                    )
                }
            }

            // 2 gently undulating fluid wave crests (soft filled organic waves, no crisp strokes)
            val segments = 72
            for (w in 0 until 2) {
                val rawT = (waveProgress + w * 0.50f) % 1f
                val easedT = 1f - (1f - rawT) * (1f - rawT)
                val baseR = outerRadius * (0.30f + 0.62f * easedT)
                val fadeIn = (rawT / 0.18f).coerceIn(0f, 1f)
                val fadeOut = (1f - rawT) * (1f - rawT)
                val crestAlpha = fadeIn * fadeOut * 0.18f

                if (crestAlpha > 0.004f) {
                    val amp = outerRadius * (0.022f + 0.020f * easedT)
                    val dir = if (w == 0) 1f else -1f
                    val phase = ripplePhase * dir + w * 1.4f

                    wavePath.reset()
                    for (s in 0..segments) {
                        val theta = (s.toFloat() / segments.toFloat()) * (2.0 * PI)
                        val waveOffset = (
                            sin(theta * 5.0 + phase) * 0.65 +
                                cos(theta * 3.0 - phase * 0.75) * 0.35
                            ).toFloat()
                        val r = (baseR + amp * waveOffset).coerceIn(outerRadius * 0.24f, outerRadius * 0.98f)
                        val px = center.x + cos(theta).toFloat() * r
                        val py = center.y + sin(theta).toFloat() * r
                        if (s == 0) wavePath.moveTo(px, py) else wavePath.lineTo(px, py)
                    }
                    wavePath.close()

                    val tint = if (w == 0) accentColor else accentSecondary
                    drawPath(
                        path = wavePath,
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.58f to Color.Transparent,
                                0.88f to tint.copy(alpha = crestAlpha * 0.55f),
                                1.0f to tint.copy(alpha = crestAlpha),
                            ),
                            center = center,
                            radius = (baseR + amp).coerceAtLeast(1f),
                        ),
                    )
                }
            }
        }

        // Layer 2: Edge-feathered luminous scanning sweep (offscreen radial mask removes any circular rim)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            val outerRadius = size.minDimension / 2f
            val hubRadius = outerRadius * 0.28f

            rotate(degrees = sweepAngle, pivot = center) {
                // Translucent trailing light fan
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.58f to Color.Transparent,
                            0.78f to accentColor.copy(alpha = 0.08f),
                            0.93f to accentColor.copy(alpha = 0.26f),
                            1.0f to accentColor.copy(alpha = 0.44f),
                        ),
                        center = center,
                    ),
                    radius = outerRadius,
                )

                // Soft wide luminous scanning ray
                drawLine(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.22f to accentColor.copy(alpha = 0.28f),
                            0.72f to accentColor.copy(alpha = 0.42f),
                            1.0f to Color.Transparent,
                        ),
                        start = Offset(center.x + hubRadius * 0.7f, center.y),
                        end = Offset(center.x + outerRadius * 0.98f, center.y),
                    ),
                    start = Offset(center.x + hubRadius * 0.7f, center.y),
                    end = Offset(center.x + outerRadius * 0.98f, center.y),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Crisp tapered scanning beam core
                drawLine(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.18f to accentColor.copy(alpha = 0.55f),
                            0.68f to accentColor.copy(alpha = 0.92f),
                            1.0f to Color.Transparent,
                        ),
                        start = Offset(center.x + hubRadius * 0.75f, center.y),
                        end = Offset(center.x + outerRadius * 0.96f, center.y),
                    ),
                    start = Offset(center.x + hubRadius * 0.75f, center.y),
                    end = Offset(center.x + outerRadius * 0.96f, center.y),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Feather the outer edge of the scanning sweep to pure transparency so no hard circle exists
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.White,
                        0.56f to Color.White,
                        0.82f to Color.White.copy(alpha = 0.34f),
                        1.0f to Color.Transparent,
                    ),
                    center = center,
                    radius = outerRadius,
                ),
                radius = outerRadius,
                blendMode = BlendMode.DstIn,
            )
        }

        // Layer 3: Sweep-reactive soft glowing signal pings (feathered radial blooms, no stroked rings)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerRadius = size.minDimension / 2f
            signalNodes.forEach { (nodeAngleDeg, radiusFraction, weight) ->
                val angularLag = ((sweepAngle - nodeAngleDeg) % 360f + 360f) % 360f
                val intensity = (1f - (angularLag / 170f)).coerceIn(0f, 1f)
                if (intensity > 0.02f) {
                    val rad = nodeAngleDeg * PI / 180.0
                    val r = outerRadius * radiusFraction
                    val nodeCenter = Offset(
                        x = center.x + cos(rad).toFloat() * r,
                        y = center.y + sin(rad).toFloat() * r,
                    )
                    val baseDot = 3.2.dp.toPx() * weight
                    val bloomRadius = baseDot * (2.6f + (1f - intensity) * 2.8f)

                    // Soft expanding glow bloom
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.0f to accentColor.copy(alpha = intensity * 0.55f),
                                0.45f to accentColor.copy(alpha = intensity * 0.22f),
                                1.0f to Color.Transparent,
                            ),
                            center = nodeCenter,
                            radius = bloomRadius,
                        ),
                        radius = bloomRadius,
                        center = nodeCenter,
                    )
                    // Luminous core dot
                    drawCircle(
                        color = accentColor.copy(alpha = 0.35f + intensity * 0.65f),
                        radius = baseDot * (0.72f + intensity * 0.32f),
                        center = nodeCenter,
                    )
                    // Specular white highlight
                    drawCircle(
                        color = Color.White.copy(alpha = intensity * 0.85f),
                        radius = baseDot * 0.36f,
                        center = nodeCenter,
                    )
                }
            }
        }

        // Soft diffused breathing aura behind the central medallion (no solid border line)
        Box(
            modifier = Modifier
                .size(centerAuraSize * coreScale)
                .background(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to accentColor.copy(alpha = 0.30f),
                            0.55f to accentColor.copy(alpha = 0.14f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )

        // Dynamic center medallion alternating Printer <-> Flatbed Scanner
        Surface(
            shape = CircleShape,
            color = accentColor,
            shadowElevation = 8.dp,
            modifier = Modifier.size(centerHubSize * coreScale),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(accentColor, accentSecondary),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = showFlatbedScanner,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(420, easing = FastOutSlowInEasing)) +
                            scaleIn(
                                initialScale = 0.68f,
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                            )) togetherWith
                            (fadeOut(animationSpec = tween(260, easing = FastOutSlowInEasing)) +
                                scaleOut(
                                    targetScale = 0.72f,
                                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                                ))
                    },
                    label = "radar_center_device_icon",
                ) { isScanner ->
                    Icon(
                        imageVector = if (isScanner) Icons.Rounded.Scanner else Icons.Rounded.Print,
                        contentDescription = if (isScanner) {
                            "Searching for wireless flatbed scanners"
                        } else {
                            "Searching for wireless printers"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddIpManuallyDialog(
    activePrinter: ActivePrinterState,
    isConnecting: Boolean,
    onConnect: (String, Int) -> Unit,
    onBrowseWireless: () -> Unit,
    onDismiss: () -> Unit,
) {
    var ipAddress by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("8631") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = tr("Add IP Manually", "मैनुअल IP जोड़ें"),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = tr(
                        "Enter the IP address and port of a BrotherBridge Remote Server or IPP network printer on your Wi-Fi or Hotspot.",
                        "अपने वाई-फ़ाई या हॉटस्पॉट पर ब्रदरब्रिज रिमोट सर्वर या IPP नेटवर्क प्रिंटर का IP पता और पोर्ट दर्ज करें।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text(tr("Server IP", "सर्वर IP")) },
                        placeholder = { Text("192.168.43.1") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1.7f)
                            .testTag("dialog_manual_ip_input"),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text(tr("Port", "पोर्ट")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(0.95f)
                            .testTag("dialog_manual_port_input"),
                    )
                }

                if (activePrinter.isConnected) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedButton(
                        onClick = onBrowseWireless,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(tr("Disconnect Active Printer", "सक्रिय प्रिंटर डिस्कनेक्ट करें"))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val port = portText.toIntOrNull() ?: 8631
                    onConnect(ipAddress.trim(), port)
                },
                enabled = ipAddress.isNotBlank() && !isConnecting,
                modifier = Modifier.testTag("dialog_manual_connect_button"),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(tr("Connect", "कनेक्ट"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(tr("Cancel", "रद्द करें"))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintSourcePickerSheet(
    printerName: String,
    onPickPdf: () -> Unit,
    onPickImage: () -> Unit,
    onPrintTestPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        modifier = Modifier.testTag("print_source_picker_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = tr("What would you like to print?", "आप क्या प्रिंट करना चाहते हैं?"),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = tr(
                    "Choose a file from storage to preview and adjust print parameters for $printerName.",
                    "$printerName के लिए प्रिंट प्रीव्यू और सेटिंग्स देखने के लिए स्टोरेज से फ़ाइल चुनें।",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PrintSourceOptionRow(
                title = stringResource(R.string.action_pick_pdf),
                subtitle = tr("Open any PDF document from device storage", "डिवाइस स्टोरेज से कोई भी PDF दस्तावेज़ खोलें"),
                icon = Icons.Outlined.PictureAsPdf,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onPickPdf,
                testTag = "sheet_pick_pdf_button",
            )

            PrintSourceOptionRow(
                title = stringResource(R.string.action_pick_image),
                subtitle = tr("Select a photo or image (JPG, PNG, WebP)", "फ़ोटो या छवि चुनें (JPG, PNG, WebP)"),
                icon = Icons.Outlined.Image,
                accent = SignalEmerald,
                onClick = onPickImage,
                testTag = "sheet_pick_image_button",
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedButton(
                onClick = onPrintTestPage,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sheet_test_page_button"),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_test_page))
            }
        }
    }
}

@Composable
private fun PrintSourceOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    testTag: String,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accent.copy(alpha = 0.14f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private sealed interface HomeRecentScanSlot {
    data class ActiveScan(val state: BackgroundScanState) : HomeRecentScanSlot
    data class Saved(val record: RecentScanRecord) : HomeRecentScanSlot
}

@Composable
private fun HomeActiveScanGridCard(
    state: BackgroundScanState,
    onOpenScan: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pct = (state.progress.coerceIn(0f, 1f) * 100f).toInt()
    val titleText = state.sessionTitle.ifBlank {
        stringResource(R.string.bg_scan_banner_title)
    }
    val subtitleText = state.statusText.ifBlank {
        tr("Scanning • $pct%", "स्कैन हो रहा है • $pct%")
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, SignalEmerald.copy(alpha = 0.55f)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenScan)
            .testTag("home_recent_active_scan_grid"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.86f)
                    .background(SignalEmerald.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { state.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(64.dp),
                        color = SignalEmerald,
                        trackColor = SignalEmerald.copy(alpha = 0.18f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Scanner,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "$pct%",
                            style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                            color = SignalEmerald,
                        )
                    }
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .minimumInteractiveComponentSize()
                        .testTag("home_background_scan_cancel"),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        border = BorderStroke(1.dp, ThermalRed.copy(alpha = 0.35f)),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = tr("Cancel scan", "स्कैन रद्द करें"),
                                tint = ThermalRed,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = SignalEmerald,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HomeActiveScanListRow(
    state: BackgroundScanState,
    onOpenScan: () -> Unit,
    onCancel: () -> Unit,
) {
    val pct = (state.progress.coerceIn(0f, 1f) * 100f).toInt()
    val titleText = state.sessionTitle.ifBlank {
        stringResource(R.string.bg_scan_banner_title)
    }
    val subtitleText = state.statusText.ifBlank {
        tr("Scanning in background...", "बैकग्राउंड में स्कैन हो रहा है...")
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.2.dp, SignalEmerald.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpenScan)
            .testTag("home_recent_active_scan_list"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.4f)),
                color = SignalEmerald.copy(alpha = 0.10f),
                modifier = Modifier.size(width = 48.dp, height = 58.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { state.progress.coerceIn(0.04f, 1f) },
                        modifier = Modifier.size(34.dp),
                        color = SignalEmerald,
                        trackColor = SignalEmerald.copy(alpha = 0.18f),
                        strokeWidth = 3.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Icon(
                        imageVector = Icons.Rounded.Scanner,
                        contentDescription = null,
                        tint = SignalEmerald,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$pct%",
                        style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                        color = SignalEmerald,
                    )
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = SignalEmerald,
                    trackColor = SignalEmerald.copy(alpha = 0.18f),
                    strokeCap = StrokeCap.Round,
                )
            }

            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("home_background_scan_cancel"),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = tr("Cancel background scan", "बैकग्राउंड स्कैन रद्द करें"),
                    tint = ThermalRed,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}


