package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.PrintJobRecord
import com.example.model.PrintJobStatus
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.localizedShortLabel
import com.example.ui.i18n.tr
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed

/**
 * Persistent bottom printing progress bar displayed whenever a print job is active
 * (Queued / Preparing / Sending) or has just completed/failed (until dismissed).
 */
@Composable
fun BottomPrintProgressBar(
    job: PrintJobRecord?,
    onCancel: (Int) -> Unit,
    onDismiss: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = job != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        if (job != null) {
            val isActive = job.status == PrintJobStatus.PENDING ||
                job.status == PrintJobStatus.RENDERING ||
                job.status == PrintJobStatus.TRANSMITTING
            val isSuccess = job.status == PrintJobStatus.COMPLETED
            val isError = job.status == PrintJobStatus.FAILED || job.status == PrintJobStatus.CANCELED

            val accent = when {
                isSuccess -> SignalEmerald
                isError -> ThermalRed
                else -> MaterialTheme.colorScheme.primary
            }

            val animatedProgress by animateFloatAsState(
                targetValue = when {
                    isSuccess -> 1f
                    isActive -> job.progress.coerceIn(0.05f, 1f)
                    else -> 0f
                },
                animationSpec = tween(durationMillis = 240),
                label = "bottom_print_progress",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .testTag("bottom_print_progress_bar"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = accent.copy(alpha = 0.14f),
                                modifier = Modifier.size(36.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = when {
                                            isSuccess -> Icons.Rounded.CheckCircle
                                            isError -> Icons.Rounded.ErrorOutline
                                            else -> Icons.Rounded.Print
                                        },
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = job.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = when {
                                    isError && !job.errorMessage.isNullOrBlank() -> job.errorMessage
                                    isSuccess -> tr(
                                        "Printed • ${job.imposedSheets} side(s) • ${job.copies} cop${if (job.copies == 1) "y" else "ies"}",
                                        "प्रिंट हुआ • ${job.imposedSheets} साइड • ${job.copies} कॉपी",
                                    )
                                    else -> "${job.status.localizedLabel()} • ${job.pagesPerSheet.localizedShortLabel()} • ${job.duplexMode.localizedShortLabel()}"
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isError) ThermalRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            Text(
                                text = "${(animatedProgress * 100).toInt()}%",
                                style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                                color = accent,
                            )

                            if (isActive) {
                                IconButton(
                                    onClick = { onCancel(job.id) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("bottom_progress_cancel_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.StopCircle,
                                        contentDescription = tr("Cancel print job", "प्रिंट जॉब रद्द करें"),
                                        tint = ThermalRed,
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { onDismiss(job.id) },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("bottom_progress_dismiss_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = tr("Dismiss print progress", "प्रिंट प्रगति हटाएं"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = accent,
                            trackColor = accent.copy(alpha = 0.16f),
                            strokeCap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}
