package com.example.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.LogCategory
import com.example.model.PrintJobRecord
import com.example.model.PrintJobSource
import com.example.model.PrintJobStatus
import com.example.model.ProtocolLogEntry
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.localizedShortLabel
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueLogsScreen(
    jobs: List<PrintJobRecord>,
    logs: List<ProtocolLogEntry>,
    onCancelJob: (Int) -> Unit,
    onClearCompletedJobs: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSegment by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                    SegmentedButton(
                        selected = selectedSegment == 0,
                        onClick = { selectedSegment = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier.testTag("segment_print_queue"),
                    ) {
                        Text(tr("Print Queue (${jobs.size})", "प्रिंट कतार (${jobs.size})"))
                    }
                    SegmentedButton(
                        selected = selectedSegment == 1,
                        onClick = { selectedSegment = 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier.testTag("segment_protocol_logs"),
                    ) {
                        Text(tr("Telemetry (${logs.size})", "टेलीमेट्री (${logs.size})"))
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (selectedSegment == 0) {
                if (jobs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onClearCompletedJobs,
                            modifier = Modifier.testTag("clear_completed_jobs_button"),
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr("Clear Completed", "पूर्ण किए गए साफ़ करें"))
                        }
                    }
                }

                if (jobs.isEmpty()) {
                    EmptyQueueState()
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(jobs, key = { it.id }) { job ->
                            PrintJobCard(job = job, onCancel = { onCancelJob(job.id) })
                        }
                    }
                }
            } else {
                if (logs.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = onClearLogs,
                            modifier = Modifier.testTag("clear_logs_button"),
                        ) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr("Clear Console", "कंसोल साफ़ करें"))
                        }
                    }
                }

                if (logs.isEmpty()) {
                    EmptyLogsState()
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(logs, key = { it.id }) { entry ->
                            LogRowCard(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PrintJobCard(
    job: PrintJobRecord,
    onCancel: () -> Unit,
) {
    val isActive = job.status == PrintJobStatus.PENDING ||
        job.status == PrintJobStatus.RENDERING ||
        job.status == PrintJobStatus.TRANSMITTING

    val statusColor = when (job.status) {
        PrintJobStatus.COMPLETED -> SignalEmerald
        PrintJobStatus.FAILED -> ThermalRed
        PrintJobStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> LaserAmber
    }

    val timeStr = remember(job.createdAtMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(job.createdAtMs))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = when (job.source) {
                            PrintJobSource.IPP_NETWORK -> Icons.Rounded.Wifi
                            else -> Icons.Rounded.Print
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "#${job.id} • ${job.title}",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${job.source.localizedLabel()} (${job.clientName}) • $timeStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (isActive) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Cancel,
                            contentDescription = tr("Cancel job #${job.id}", "जॉब #${job.id} रद्द करें"),
                            tint = ThermalRed,
                        )
                    }
                } else {
                    Icon(
                        imageVector = when (job.status) {
                            PrintJobStatus.COMPLETED -> Icons.Rounded.CheckCircle
                            PrintJobStatus.FAILED -> Icons.Rounded.ErrorOutline
                            else -> Icons.Rounded.HourglassTop
                        },
                        contentDescription = job.status.localizedLabel(),
                        tint = statusColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallMetaChip(text = job.status.localizedLabel(), tint = statusColor)
                SmallMetaChip(text = job.pagesPerSheet.localizedShortLabel(), tint = MaterialTheme.colorScheme.primary)
                SmallMetaChip(text = job.duplexMode.localizedShortLabel(), tint = MaterialTheme.colorScheme.secondary)
                if (job.sourcePages > 0) {
                    SmallMetaChip(
                        text = tr("${job.sourcePages}p → ${job.imposedSheets}s", "${job.sourcePages}पे → ${job.imposedSheets}शी"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isActive) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }

            if (!job.errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = job.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = ThermalRed,
                )
            }
        }
    }
}

@Composable
private fun SmallMetaChip(text: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = tint.copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun LogRowCard(entry: ProtocolLogEntry) {
    val badgeColor = when {
        entry.isError -> ThermalRed
        entry.category == LogCategory.IPP -> MaterialTheme.colorScheme.primary
        entry.category == LogCategory.MDNS -> SignalEmerald
        entry.category == LogCategory.USB -> LaserAmber
        else -> MaterialTheme.colorScheme.secondary
    }
    val time = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timestampMs))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = badgeColor.copy(alpha = 0.14f),
            ) {
                Text(
                    text = entry.category.badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = badgeColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.message,
                    style = MonoCodeStyle,
                    color = if (entry.isError) ThermalRed else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyQueueState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = stringResource(R.string.empty_queue_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.empty_queue_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun EmptyLogsState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(52.dp),
            )
            Text(
                text = tr("Protocol Console Ready", "प्रोटोकॉल कंसोल तैयार है"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = tr(
                    "Start the IPP server or connect a USB OTG cable to see live Bonjour, IPP, and Brother raster events.",
                    "लाइव Bonjour, IPP और ब्रदर रास्टर ईवेंट देखने के लिए IPP सर्वर चालू करें या USB OTG केबल कनेक्ट करें।",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
