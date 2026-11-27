package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Queue
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.R
import com.example.model.ActivePrinterState
import com.example.model.ConnectedClientDevice
import com.example.model.MdnsStatus
import com.example.model.NetworkServerState
import com.example.model.PrintServerSettings
import com.example.model.ScannerAvailabilityState
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed

/**
 * Redesigned professional Remote Server control console:
 * - No top printer model hero card
 * - Clean master toggle switch to start/stop the Wi-Fi server
 * - Reveals network URLs (IPP, HTTP Scan API, Web Status) ONLY when the server is ON
 */
@Composable
fun BridgeDashboardScreen(
    activePrinter: ActivePrinterState,
    settings: PrintServerSettings,
    serverState: NetworkServerState,
    scannerState: ScannerAvailabilityState,
    connectedClients: List<ConnectedClientDevice> = emptyList(),
    onToggleServer: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onOpenQueue: () -> Unit,
    onCopiedNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showConnectGuide by remember { mutableStateOf(false) }

    val pulseTransition = rememberInfiniteTransition(label = "server_live_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse_alpha",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Professional Master Toggle Control Card
            item {
                val isOnline = serverState.isRunning
                val accent = if (isOnline) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(
                        width = if (isOnline) 1.5.dp else 1.dp,
                        color = if (isOnline) SignalEmerald.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isOnline) 2.dp else 0.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isOnline) {
                                    SignalEmerald.copy(alpha = 0.14f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier.size(54.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Router,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("Remote Wi-Fi Server", "रिमोट वाई-फ़ाई सर्वर"),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .alpha(if (isOnline) pulseAlpha else 1f)
                                            .background(if (isOnline) SignalEmerald else MaterialTheme.colorScheme.outline),
                                    )
                                    Text(
                                        text = if (isOnline) tr("Online", "ऑनलाइन") else tr("Offline", "ऑफ़लाइन"),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = accent,
                                    )
                                }
                            }

                            Switch(
                                checked = isOnline,
                                onCheckedChange = { onToggleServer() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SignalEmerald,
                                ),
                                modifier = Modifier.testTag("toggle_server_switch"),
                            )
                        }

                        // Compact live telemetry strip when server is ON
                        AnimatedVisibility(
                            visible = isOnline,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                ServerStatPill(
                                    label = tr("PORT", "पोर्ट"),
                                    value = "${serverState.port}",
                                    modifier = Modifier.weight(1f),
                                )
                                ServerStatPill(
                                    label = tr("BONJOUR", "बोनजूर"),
                                    value = if (serverState.mdnsStatus == MdnsStatus.ADVERTISING) {
                                        tr("Active", "सक्रिय")
                                    } else {
                                        serverState.mdnsStatus.localizedLabel()
                                    },
                                    accent = if (serverState.mdnsStatus == MdnsStatus.ADVERTISING) SignalEmerald else null,
                                    modifier = Modifier.weight(1.2f),
                                )
                                ServerStatPill(
                                    label = tr("REQUESTS", "अनुरोध"),
                                    value = "${serverState.totalIppRequests}",
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        if (!serverState.lastError.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = ThermalRed, modifier = Modifier.size(16.dp))
                                Text(
                                    text = serverState.lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ThermalRed,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Network URLs & Endpoints — SHOWN ONLY WHEN SERVER IS ON
            item {
                AnimatedVisibility(
                    visible = serverState.isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("server_urls_card"),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Wifi,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Column {
                                            Text(
                                                text = tr("Active Server URLs", "सक्रिय सर्वर URL"),
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            )
                                            Text(
                                                text = serverState.ipAddress?.let {
                                                    tr("Local IP: $it", "लोकल IP: $it")
                                                } ?: tr("Connect Wi-Fi or Hotspot", "वाई-फ़ाई या हॉटस्पॉट कनेक्ट करें"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = onRefreshNetwork,
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("refresh_ip_button"),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Refresh,
                                            contentDescription = tr("Refresh local Wi-Fi IP", "लोकल वाई-फ़ाई IP रिफ़्रेश करें"),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }

                                val copiedPrefix = tr("Copied", "कॉपी किया गया")
                                ServerUrlTile(
                                    label = tr("IPP PRINT URL", "IPP प्रिंट URL"),
                                    url = serverState.ippUri,
                                    icon = Icons.Outlined.Print,
                                    enabled = serverState.ipAddress != null,
                                    onCopy = {
                                        copyToClipboard(context, "IPP Printer URI", serverState.ippUri)
                                        onCopiedNotice("$copiedPrefix ${serverState.ippUri}")
                                    },
                                    testTag = "copy_ipp_uri_box",
                                )

                                if (activePrinter.supportsScan || scannerState.isAvailable) {
                                    ServerUrlTile(
                                        label = tr("HTTP SCANNER API URL", "HTTP स्कैनर API URL"),
                                        url = serverState.scanApiUri,
                                        icon = Icons.Outlined.DocumentScanner,
                                        enabled = serverState.ipAddress != null,
                                        onCopy = {
                                            copyToClipboard(context, "Scanner Server URI", serverState.scanApiUri)
                                            onCopiedNotice("$copiedPrefix ${serverState.scanApiUri}")
                                        },
                                        testTag = "copy_scan_api_uri_box",
                                    )
                                }

                                if (serverState.httpConsoleUrl.isNotBlank()) {
                                    ServerUrlTile(
                                        label = tr("WEB STATUS CONSOLE", "वेब स्टेटस कंसोल"),
                                        url = serverState.httpConsoleUrl,
                                        icon = Icons.Outlined.Public,
                                        enabled = true,
                                        onCopy = {
                                            copyToClipboard(context, "Web Status URL", serverState.httpConsoleUrl)
                                            onCopiedNotice("$copiedPrefix ${serverState.httpConsoleUrl}")
                                        },
                                        testTag = "copy_web_url_box",
                                    )
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                FilledTonalButton(
                                    onClick = onOpenQueue,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("server_open_queue_button"),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Icon(Icons.Outlined.Queue, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(tr("View Print Queue & Protocol Logs", "प्रिंट कतार और प्रोटोकॉल लॉग देखें"))
                                }
                            }
                        }

                        ConnectedDevicesCard(clients = connectedClients)

                        BackgroundKeepAliveBanner(serverRunning = true)

                        DriverlessSetupGuideCard(
                            expanded = showConnectGuide,
                            onToggleExpand = { showConnectGuide = !showConnectGuide },
                            serverState = serverState,
                            settings = settings,
                        )
                    }
                }
            }

            // 3. Clean Offline Info Summary when Server is OFF (no URLs shown)
            item {
                AnimatedVisibility(
                    visible = !serverState.isRunning,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = tr("WHAT HAPPENS WHEN ENABLED", "चालू करने पर क्या होता है"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            OfflineFeatureRow(
                                icon = Icons.Outlined.Print,
                                text = tr(
                                    "Publishes driverless IPP 2.0 printing on port ${settings.ippPort} for Android, iOS, Windows, macOS & Linux.",
                                    "Android, iOS, Windows, macOS और Linux के लिए पोर्ट ${settings.ippPort} पर ड्राइवर-मुक्त IPP 2.0 प्रिंटिंग चालू करता है।",
                                ),
                            )
                            if (activePrinter.supportsScan) {
                                OfflineFeatureRow(
                                    icon = Icons.Outlined.DocumentScanner,
                                    text = tr(
                                        "Publishes fail-safe wireless background scanning so other devices can scan documents remotely.",
                                        "सुरक्षित वायरलेस बैकग्राउंड स्कैनिंग चालू करता है ताकि अन्य डिवाइस दूर से दस्तावेज़ स्कैन कर सकें।",
                                    ),
                                )
                            }
                            OfflineFeatureRow(
                                icon = Icons.Outlined.Wifi,
                                text = tr(
                                    "Broadcasts Bonjour / mDNS discovery and exposes shareable network URLs once active.",
                                    "सक्रिय होने पर Bonjour / mDNS खोज प्रसारित करता है और शेयर करने योग्य नेटवर्क URL दिखाता है।",
                                ),
                            )
                        }
                    }
                }
            }

            // 4. Android System Print Service Card (Direct Print from Chrome, Drive, File Manager)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("android_print_service_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(38.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Print,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("Android System Print Service", "एंड्रॉइड सिस्टम प्रिंट सेवा"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = tr(
                                        "Print directly from File Manager, Drive & Chrome with parameter selection",
                                        "फ़ाइल मैनेजर, ड्राइव और क्रोम से सीधे प्रिंट विकल्पों के साथ प्रिंट करें",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        val printSettingsHint = tr(
                            "Open Settings → Connected devices → Printing → BrotherBridge USB OTG",
                            "सेटिंग्स → कनेक्टेड डिवाइस → प्रिंटिंग → ब्रदरब्रिज USB OTG खोलें",
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                                    .onFailure { onCopiedNotice(printSettingsHint) }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("open_android_print_settings_button"),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tr("Open Android Print Settings", "एंड्रॉइड प्रिंट सेटिंग्स खोलें"))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ServerStatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = accent ?: MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ServerUrlTile(
    label: String,
    url: String,
    icon: ImageVector,
    enabled: Boolean,
    onCopy: () -> Unit,
    testTag: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onCopy)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = url,
                    style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = tr("Copy $label", "$label कॉपी करें"),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ConnectedDevicesCard(
    clients: List<ConnectedClientDevice>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_devices_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Devices,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        text = tr("Connected Devices", "कनेक्टेड डिवाइस"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (clients.isNotEmpty()) {
                        SignalEmerald.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = "${clients.size}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (clients.isNotEmpty()) SignalEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            if (clients.isEmpty()) {
                Text(
                    text = tr(
                        "No devices connected yet. Phones or computers connected over Wi-Fi / Hotspot will appear here automatically.",
                        "अभी कोई डिवाइस कनेक्ट नहीं है। वाई-फ़ाई / हॉटस्पॉट से जुड़े फ़ोन या कंप्यूटर यहाँ अपने आप दिखाई देंगे।",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    clients.forEach { client ->
                        ConnectedClientRow(client = client)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedClientRow(
    client: ConnectedClientDevice,
) {
    val primaryName = client.displayName.ifBlank {
        client.modelName.ifBlank { client.ipAddress }
    }
    val secondaryLine = buildString {
        if (client.modelName.isNotBlank() && !client.modelName.equals(primaryName, ignoreCase = true)) {
            append(client.modelName)
            if (client.ipAddress.isNotBlank()) {
                append(" • ")
                append(client.ipAddress)
            }
        } else if (client.ipAddress.isNotBlank() && !client.ipAddress.equals(primaryName, ignoreCase = true)) {
            append(client.ipAddress)
        }
    }
    val localizedActivity = when (client.lastActivityLabel) {
        "Printing" -> tr("Printing", "प्रिंटिंग")
        "Scanning" -> tr("Scanning", "स्कैनिंग")
        "Receiving Scan" -> tr("Receiving Scan", "स्कैन प्राप्त हो रहा है")
        "Web Console" -> tr("Web Console", "वेब कंसोल")
        "IPP Client" -> tr("IPP Client", "IPP क्लाइंट")
        else -> tr("Connected", "कनेक्टेड")
    }
    val isActiveTask = client.lastActivityLabel in setOf("Printing", "Scanning", "Receiving Scan")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connected_device_row_${client.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SignalEmerald.copy(alpha = 0.14f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PhoneAndroid,
                        contentDescription = null,
                        tint = SignalEmerald,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primaryName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondaryLine.isNotBlank()) {
                    Text(
                        text = secondaryLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (isActiveTask) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    SignalEmerald.copy(alpha = 0.14f)
                },
            ) {
                Text(
                    text = localizedActivity,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActiveTask) MaterialTheme.colorScheme.primary else SignalEmerald,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun OfflineFeatureRow(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackgroundKeepAliveBanner(serverRunning: Boolean) {
    val context = LocalContext.current
    var isUnrestricted by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isUnrestricted = isBatteryOptimizationIgnored(context)
    }

    val accentColor = if (isUnrestricted) SignalEmerald else LaserAmber
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = accentColor.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isUnrestricted) Icons.Rounded.BatteryChargingFull else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.background_keepalive_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isUnrestricted) {
                    stringResource(R.string.background_unrestricted_status)
                } else {
                    stringResource(R.string.background_restricted_status)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isUnrestricted) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openAppBatterySettings(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("battery_settings_button"),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        text = if (serverRunning) {
                            tr("Set Battery to Unrestricted (Recommended)", "बैटरी को अनप्रतिबंधित पर सेट करें (अनुशंसित)")
                        } else {
                            stringResource(R.string.action_allow_background)
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

private fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun openAppBatterySettings(context: Context) {
    val appDetailsIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    val batteryListIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching { context.startActivity(appDetailsIntent) }
        .onFailure { runCatching { context.startActivity(batteryListIntent) } }
}

@Composable
private fun DriverlessSetupGuideCard(
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    serverState: NetworkServerState,
    settings: PrintServerSettings,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lan,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = tr("How Other Devices Connect", "अन्य डिवाइस कैसे कनेक्ट करें"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = tr(
                                "BrotherBridge app, Android, iOS, macOS, Windows & Linux",
                                "ब्रदरब्रिज ऐप, Android, iOS, macOS, Windows और Linux",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) tr("Collapse guide", "गाइड बंद करें") else tr("Expand guide", "गाइड खोलें"),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideStep(
                        platform = tr("Another BrotherBridge Phone (Wi-Fi / Hotspot)", "दूसरा ब्रदरब्रिज फ़ोन (वाई-फ़ाई / हॉटस्पॉट)"),
                        detail = tr(
                            "Open BrotherBridge without OTG and tap Scan for Printers to find '${settings.printerName}', or connect manually using ${serverState.ipAddress ?: "<IP>"}:${serverState.port}.",
                            "OTG के बिना ब्रदरब्रिज खोलें और '${settings.printerName}' खोजने के लिए प्रिंटर स्कैन करें दबाएँ, या ${serverState.ipAddress ?: "<IP>"}:${serverState.port} से मैन्युअल रूप से कनेक्ट करें।",
                        ),
                    )
                    GuideStep(
                        platform = tr("Android / iPhone / iPad (Mopria & AirPrint)", "Android / iPhone / iPad (Mopria और AirPrint)"),
                        detail = tr(
                            "Tap Print in any app — '${settings.printerName}' appears automatically on the same Wi-Fi.",
                            "किसी भी ऐप में Print दबाएँ — समान वाई-फ़ाई पर '${settings.printerName}' अपने आप दिखाई देगा।",
                        ),
                    )
                    GuideStep(
                        platform = tr("Windows 10/11, macOS & Linux (CUPS)", "Windows 10/11, macOS और Linux (CUPS)"),
                        detail = tr(
                            "Add via Bonjour or manually with URI: ${serverState.ippUri}",
                            "Bonjour के ज़रिए या इस URI से मैन्युअल जोड़ें: ${serverState.ippUri}",
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun GuideStep(platform: String, detail: String) {
    Column {
        Text(
            text = platform,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}
