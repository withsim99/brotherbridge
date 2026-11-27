package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.CropRotate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Scanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.scanner.ScanShareHelper
import kotlinx.coroutines.launch
import java.io.File
import com.example.R
import com.example.model.ActivePrinterState
import com.example.model.RecentScansViewMode
import com.example.model.ScanColorOption
import com.example.model.ScanExportMode
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.ScanSourceOption
import com.example.ui.ScanPhase
import com.example.ui.ScanUiState
import com.example.ui.ScannedPageItem
import com.example.ui.components.AdvancedSettingsCard
import com.example.ui.components.ParameterDropdownField
import com.example.ui.i18n.localizedDescription
import com.example.ui.i18n.localizedDisplayName
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.localizedShortLabel
import com.example.ui.i18n.localizedSubtitle
import com.example.ui.i18n.scanDpiSubtitle
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed
import kotlin.math.roundToInt

/**
 * On-Demand Multi-Page Scan Screen:
 * 1. Two-column inline scan parameter dropdowns (Source, Color, Page Size, DPI) + Advanced Settings (Brightness, Contrast)
 * 2. Scans ONLY when the user presses Scan (shows live file progress at center in Grid view or around file icon in List view)
 * 3. Saved scan workspace with page selection (delete, print, or export selected pages in desired format),
 *    zoomable page viewer, and centered scan parameters popup accessible at any time via the settings icon.
 */
@Composable
fun ScanScreen(
    activePrinter: ActivePrinterState,
    state: ScanUiState,
    selectedPageIds: Set<Long>,
    showExportSheet: Boolean,
    showSettingsDialog: Boolean,
    onDismissExportSheet: () -> Unit,
    onDismissSettingsDialog: () -> Unit,
    onToggleSelectPage: (Long) -> Unit,
    onMergeSelectedPages: () -> Unit,
    onInspectPage: (Int) -> Unit,
    onCropPage: (Int) -> Unit,
    onSelectSource: (ScanSourceOption) -> Unit,
    onSelectColor: (ScanColorOption) -> Unit,
    onSelectDpi: (Int) -> Unit,
    onSelectPageSize: (ScanPageSizeOption) -> Unit,
    onChangeBrightness: (Int) -> Unit,
    onChangeContrast: (Int) -> Unit,
    onTriggerScan: () -> Unit,
    onCancelScan: () -> Unit,
    onRotatePageAt: (Int) -> Unit,
    onDeletePageAt: (Int) -> Unit,
    onExportPages: (mode: ScanExportMode) -> Unit,
    onExportPdfToChosenFolder: () -> Unit,
    onPrintCollection: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Background scanning stays alive when leaving ScanScreen — never abort on dispose.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (state.phase) {
            ScanPhase.PARAMETERS -> {
                ScanParametersStepView(
                    activePrinter = activePrinter,
                    state = state,
                    onSelectSource = onSelectSource,
                    onSelectColor = onSelectColor,
                    onSelectDpi = onSelectDpi,
                    onSelectPageSize = onSelectPageSize,
                    onChangeBrightness = onChangeBrightness,
                    onChangeContrast = onChangeContrast,
                    onTriggerScan = onTriggerScan,
                )
            }

            ScanPhase.EXPORT_READY -> {
                ExportReadyWorkspaceView(
                    activePrinter = activePrinter,
                    canScanMore = activePrinter.isConnected && activePrinter.supportsScan,
                    canPrint = activePrinter.isConnected && activePrinter.supportsPrint,
                    state = state,
                    selectedPageIds = selectedPageIds,
                    onToggleSelectPage = onToggleSelectPage,
                    onMergeSelectedPages = onMergeSelectedPages,
                    onInspectPage = onInspectPage,
                    onCropPage = onCropPage,
                    onRotatePageAt = onRotatePageAt,
                    onDeletePageAt = onDeletePageAt,
                    onTriggerScan = onTriggerScan,
                    onCancelScan = onCancelScan,
                    onPrintCollection = onPrintCollection,
                    onBackToHome = onBackToHome,
                )
            }
        }
    }

    if (showSettingsDialog) {
        ScanParametersPopupDialog(
            activePrinter = activePrinter,
            state = state,
            onSelectSource = onSelectSource,
            onSelectColor = onSelectColor,
            onSelectDpi = onSelectDpi,
            onSelectPageSize = onSelectPageSize,
            onChangeBrightness = onChangeBrightness,
            onChangeContrast = onChangeContrast,
            onDismiss = onDismissSettingsDialog,
        )
    }

    if (showExportSheet && state.pages.isNotEmpty()) {
        val exportPageCount = if (selectedPageIds.isNotEmpty()) {
            state.pages.count { it.id in selectedPageIds }
        } else {
            state.pages.size
        }
        ScanExportOptionsDialog(
            pageCount = exportPageCount,
            isSelectionExport = selectedPageIds.isNotEmpty() && exportPageCount < state.pages.size,
            isExporting = state.isExporting,
            onExportNow = { mode ->
                onExportPages(mode)
                onDismissExportSheet()
            },
            onSavePdfAs = {
                onDismissExportSheet()
                onExportPdfToChosenFolder()
            },
            onDismiss = onDismissExportSheet,
        )
    }
}

@Composable
private fun ScanParametersStepView(
    activePrinter: ActivePrinterState,
    state: ScanUiState,
    onSelectSource: (ScanSourceOption) -> Unit,
    onSelectColor: (ScanColorOption) -> Unit,
    onSelectDpi: (Int) -> Unit,
    onSelectPageSize: (ScanPageSizeOption) -> Unit,
    onChangeBrightness: (Int) -> Unit,
    onChangeContrast: (Int) -> Unit,
    onTriggerScan: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val isWide = maxWidth >= 600.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.errorMessage.isNullOrBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ThermalRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, ThermalRed.copy(alpha = 0.38f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = ThermalRed,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = tr("Scan Parameters", "स्कैन पैरामीटर"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        ScanParameterDropdownGrid(
                            activePrinter = activePrinter,
                            state = state,
                            onSelectSource = onSelectSource,
                            onSelectColor = onSelectColor,
                            onSelectDpi = onSelectDpi,
                            onSelectPageSize = onSelectPageSize,
                        )
                    }
                }
            }

            item {
                ScanAdvancedToneCard(
                    brightness = state.params.brightness,
                    contrast = state.params.contrast,
                    isWide = isWide,
                    onChangeBrightness = onChangeBrightness,
                    onChangeContrast = onChangeContrast,
                )
            }

            item {
                Button(
                    onClick = onTriggerScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_scan_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SignalEmerald,
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(Icons.Rounded.Scanner, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = tr("Scan", "स्कैन करें"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ScanParameterDropdownGrid(
    activePrinter: ActivePrinterState,
    state: ScanUiState,
    onSelectSource: (ScanSourceOption) -> Unit,
    onSelectColor: (ScanColorOption) -> Unit,
    onSelectDpi: (Int) -> Unit,
    onSelectPageSize: (ScanPageSizeOption) -> Unit,
) {
    val availableSources = buildList {
        add(ScanSourceOption.FLATBED)
        if (activePrinter.supportsAdf) add(ScanSourceOption.ADF_SIMPLEX)
        if (activePrinter.supportsAdfDuplex) add(ScanSourceOption.ADF_DUPLEX)
    }
    val dpiOptions = ScanParameters.SUPPORTED_DPI_OPTIONS

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Row 1: Source | Color
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ParameterDropdownField(
                label = tr("Source", "स्रोत"),
                selectedValue = state.params.source,
                options = availableSources,
                optionLabel = { it.localizedDisplayName() },
                onSelect = onSelectSource,
                testTag = "scan_source_dropdown",
                modifier = Modifier.weight(1f),
            )

            ParameterDropdownField(
                label = tr("Color", "रंग"),
                selectedValue = state.params.colorMode,
                options = ScanColorOption.entries,
                optionLabel = { it.localizedShortLabel() },
                optionSubtitle = { it.localizedDisplayName() },
                onSelect = onSelectColor,
                testTag = "scan_color_dropdown",
                modifier = Modifier.weight(1f),
            )
        }

        // Row 2: Page Size (A4 / A3 / Letter / A5 / Card) | DPI (up to 2400 DPI)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ParameterDropdownField(
                label = tr("Page Size", "पेज आकार"),
                selectedValue = state.params.pageSize,
                options = ScanPageSizeOption.entries,
                optionLabel = { it.localizedDisplayName() },
                optionSubtitle = { it.dimensionsLabel },
                onSelect = onSelectPageSize,
                testTag = "scan_pagesize_dropdown",
                modifier = Modifier.weight(1f),
            )

            ParameterDropdownField(
                label = "DPI",
                selectedValue = state.params.dpi,
                options = dpiOptions,
                optionLabel = { "$it DPI" },
                optionSubtitle = { dpi -> scanDpiSubtitle(dpi) },
                onSelect = onSelectDpi,
                testTag = "scan_dpi_dropdown",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScanAdvancedToneCard(
    brightness: Int,
    contrast: Int,
    isWide: Boolean,
    onChangeBrightness: (Int) -> Unit,
    onChangeContrast: (Int) -> Unit,
) {
    val advancedSummary = tr(
        "Brightness $brightness% • Contrast $contrast%",
        "चमक $brightness% • कंट्रास्ट $contrast%",
    )
    AdvancedSettingsCard(
        summaryText = advancedSummary,
        testTag = "scan_advanced_settings_toggle",
    ) {
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ToneSliderControl(
                    label = tr("Brightness", "चमक"),
                    value = brightness,
                    onValueChange = onChangeBrightness,
                    modifier = Modifier.weight(1f),
                )
                ToneSliderControl(
                    label = tr("Contrast", "कंट्रास्ट"),
                    value = contrast,
                    onValueChange = onChangeContrast,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            ToneSliderControl(
                label = tr("Brightness", "चमक"),
                value = brightness,
                onValueChange = onChangeBrightness,
            )
            ToneSliderControl(
                label = tr("Contrast", "कंट्रास्ट"),
                value = contrast,
                onValueChange = onChangeContrast,
            )
        }
    }
}

/**
 * Centered Scan Parameters Popup opened from the Settings icon on the Scan page so the user can
 * view or adjust scan settings at any time.
 */
@Composable
private fun ScanParametersPopupDialog(
    activePrinter: ActivePrinterState,
    state: ScanUiState,
    onSelectSource: (ScanSourceOption) -> Unit,
    onSelectColor: (ScanColorOption) -> Unit,
    onSelectDpi: (Int) -> Unit,
    onSelectPageSize: (ScanPageSizeOption) -> Unit,
    onChangeBrightness: (Int) -> Unit,
    onChangeContrast: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
                .testTag("scan_settings_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = tr("Scan Parameters", "स्कैन पैरामीटर"),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scan_settings_dialog_close"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = tr("Close scan settings", "स्कैन सेटिंग्स बंद करें"),
                        )
                    }
                }

                ScanParameterDropdownGrid(
                    activePrinter = activePrinter,
                    state = state,
                    onSelectSource = onSelectSource,
                    onSelectColor = onSelectColor,
                    onSelectDpi = onSelectDpi,
                    onSelectPageSize = onSelectPageSize,
                )

                ScanAdvancedToneCard(
                    brightness = state.params.brightness,
                    contrast = state.params.contrast,
                    isWide = false,
                    onChangeBrightness = onChangeBrightness,
                    onChangeContrast = onChangeContrast,
                )

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("scan_settings_dialog_done"),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = tr("Done", "पूर्ण"),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToneSliderControl(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text("$value%", style = MonoCodeStyle)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = 0f..100f,
        )
    }
}

@Composable
private fun ActiveScanningGridCard(
    pageNumber: Int,
    sessionTitle: String,
    progress: Float,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, SignalEmerald.copy(alpha = 0.6f)),
        modifier = modifier.testTag("active_scanning_grid_card"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
                    .background(SignalEmerald.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.size(72.dp),
                        color = SignalEmerald,
                        trackColor = SignalEmerald.copy(alpha = 0.2f),
                        strokeWidth = 5.dp,
                        strokeCap = StrokeCap.Round,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                            color = SignalEmerald,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = tr("Page $pageNumber", "पेज $pageNumber"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText.ifBlank { tr("Scanning...", "स्कैन हो रहा है...") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveScanningListRow(
    fileTitle: String,
    pageNumber: Int,
    progress: Float,
    statusText: String,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.5.dp, SignalEmerald.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_scanning_list_row"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier.size(54.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier.size(54.dp),
                    color = SignalEmerald,
                    trackColor = SignalEmerald.copy(alpha = 0.2f),
                    strokeWidth = 4.dp,
                    strokeCap = StrokeCap.Round,
                )
                Surface(
                    shape = CircleShape,
                    color = SignalEmerald.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Description,
                            contentDescription = "Scanning page $pageNumber",
                            tint = SignalEmerald,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileTitle,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = "${(progress * 100).toInt()}%",
                style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                color = SignalEmerald,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExportReadyWorkspaceView(
    activePrinter: ActivePrinterState,
    canScanMore: Boolean,
    canPrint: Boolean,
    state: ScanUiState,
    selectedPageIds: Set<Long>,
    onToggleSelectPage: (Long) -> Unit,
    onMergeSelectedPages: () -> Unit,
    onInspectPage: (Int) -> Unit,
    onCropPage: (Int) -> Unit,
    onRotatePageAt: (Int) -> Unit,
    onDeletePageAt: (Int) -> Unit,
    onTriggerScan: () -> Unit,
    onCancelScan: () -> Unit,
    onPrintCollection: () -> Unit,
    onBackToHome: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareChooserTitle = tr("Share Scanned Page (JPG)", "स्कैन किया पेज साझा करें (JPG)")
    val isSelectionMode = selectedPageIds.isNotEmpty()
    val effectiveCount = if (isSelectionMode) {
        state.pages.count { it.id in selectedPageIds }
    } else {
        state.pages.size
    }
    val animatedProgress by animateFloatAsState(
        targetValue = state.currentPageProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "scan_file_progress",
    )

    // Pre-cache crisp JPG files on disk so long-press Dynamic Island drag starts with 0ms latency
    var cachedJpgFiles by remember { mutableStateOf<Map<Long, Pair<Int, File>>>(emptyMap()) }
    LaunchedEffect(state.pages, state.sessionTitle) {
        val updated = cachedJpgFiles.toMutableMap()
        val baseTitle = state.sessionTitle.ifBlank { "Scan" }
        state.pages.forEachIndexed { idx, item ->
            val genId = item.bitmap.generationId
            val existing = updated[item.id]
            if (existing == null || existing.first != genId || !existing.second.exists()) {
                val file = ScanShareHelper.prepareJpgFile(
                    context = context,
                    pageId = item.id,
                    sessionTitle = baseTitle,
                    pageNumber = idx + 1,
                    bitmap = item.bitmap,
                )
                updated[item.id] = genId to file
            }
        }
        val activeIds = state.pages.map { it.id }.toSet()
        updated.keys.retainAll(activeIds)
        cachedJpgFiles = updated
    }

    var activeDraggedJpg by remember { mutableStateOf<File?>(null) }
    val latestShareTitle by rememberUpdatedState(shareChooserTitle)
    val dragSessionObserver = remember(context) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = false

            override fun onEnded(event: DragAndDropEvent) {
                val acceptedExternally = event.toAndroidDragEvent().result
                val file = activeDraggedJpg
                activeDraggedJpg = null
                if (!acceptedExternally && file != null && file.exists()) {
                    ScanShareHelper.shareSingleJpg(context, file, latestShareTitle)
                }
            }
        }
    }

    fun resolvePageJpgSync(pageIdx: Int, item: ScannedPageItem): File {
        val genId = item.bitmap.generationId
        val cached = cachedJpgFiles[item.id]
        if (cached != null && cached.first == genId && cached.second.exists()) {
            return cached.second
        }
        return ScanShareHelper.prepareJpgFileSync(
            context = context,
            pageId = item.id,
            sessionTitle = state.sessionTitle.ifBlank { "Scan" },
            pageNumber = pageIdx + 1,
            bitmap = item.bitmap,
            generationTag = genId,
        )
    }

    fun triggerDirectJpgShare(pageIdx: Int, item: ScannedPageItem) {
        scope.launch {
            val file = ScanShareHelper.prepareJpgFile(
                context = context,
                pageId = item.id,
                sessionTitle = state.sessionTitle.ifBlank { "Scan" },
                pageNumber = pageIdx + 1,
                bitmap = item.bitmap,
            )
            ScanShareHelper.shareSingleJpg(context, file, shareChooserTitle)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.toAndroidDragEvent().localState is File },
                target = dragSessionObserver,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        val gridColumns = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }
        val smallTileColumns = when {
            maxWidth >= 840.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 3
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 900.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isMergingIdCard) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SignalEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("id_card_merging_banner"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = SignalEmerald,
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = tr(
                                "Identifying objects & merging selected pages...",
                                "ऑब्जेक्ट पहचानकर चयनित पेज मर्ज हो रहे हैं...",
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            if (!state.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ThermalRed.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ThermalRed.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = ThermalRed,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            AnimatedVisibility(visible = !state.exportSuccessMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SignalEmerald.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, SignalEmerald.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = SignalEmerald, modifier = Modifier.size(18.dp))
                        Text(
                            text = state.exportSuccessMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(
                            onClick = onBackToHome,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(tr("Home", "होम"))
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.viewMode) {
                    RecentScansViewMode.GRID -> {
                        val totalSlots = state.pages.size + if (state.isActivelyScanningPage) 1 else 0
                        val rowCount = (totalSlots + gridColumns - 1) / gridColumns
                        items(
                            count = rowCount,
                            key = { rowIdx ->
                                buildString {
                                    append("grid_row_").append(rowIdx)
                                    for (c in 0 until gridColumns) {
                                        val slotIdx = rowIdx * gridColumns + c
                                        if (slotIdx < state.pages.size) {
                                            val p = state.pages[slotIdx]
                                            append('_')
                                                .append(p.id)
                                                .append('_')
                                                .append(System.identityHashCode(p.bitmap))
                                                .append('_')
                                                .append(p.bitmap.generationId)
                                        }
                                    }
                                }
                            },
                        ) { rowIdx ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (c in 0 until gridColumns) {
                                    val slotIdx = rowIdx * gridColumns + c
                                    when {
                                        slotIdx < state.pages.size -> {
                                            val item = state.pages[slotIdx]
                                            CapturedPageGridCard(
                                                pageNumber = slotIdx + 1,
                                                item = item,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = item.id in selectedPageIds,
                                                canDragShare = !isSelectionMode && !state.isActivelyScanningPage,
                                                resolveJpgForDrag = { resolvePageJpgSync(slotIdx, item) },
                                                onDragShareStarted = { file -> activeDraggedJpg = file },
                                                onTap = {
                                                    if (isSelectionMode) {
                                                        onToggleSelectPage(item.id)
                                                    } else {
                                                        onInspectPage(slotIdx)
                                                    }
                                                },
                                                onCrop = { onCropPage(slotIdx) },
                                                onRotate = { onRotatePageAt(slotIdx) },
                                                onDelete = { onDeletePageAt(slotIdx) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        slotIdx == state.pages.size && state.isActivelyScanningPage -> {
                                            ActiveScanningGridCard(
                                                pageNumber = state.pages.size + 1,
                                                sessionTitle = state.sessionTitle.ifBlank { "Scan" },
                                                progress = animatedProgress,
                                                statusText = state.currentPageStatus,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        else -> {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    RecentScansViewMode.SMALL_TILES -> {
                        val totalSlots = state.pages.size + if (state.isActivelyScanningPage) 1 else 0
                        val rowCount = (totalSlots + smallTileColumns - 1) / smallTileColumns
                        items(
                            count = rowCount,
                            key = { rowIdx ->
                                buildString {
                                    append("tile_row_").append(rowIdx)
                                    for (c in 0 until smallTileColumns) {
                                        val slotIdx = rowIdx * smallTileColumns + c
                                        if (slotIdx < state.pages.size) {
                                            val p = state.pages[slotIdx]
                                            append('_')
                                                .append(p.id)
                                                .append('_')
                                                .append(System.identityHashCode(p.bitmap))
                                                .append('_')
                                                .append(p.bitmap.generationId)
                                        }
                                    }
                                }
                            },
                        ) { rowIdx ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (c in 0 until smallTileColumns) {
                                    val slotIdx = rowIdx * smallTileColumns + c
                                    when {
                                        slotIdx < state.pages.size -> {
                                            val item = state.pages[slotIdx]
                                            CapturedPageSmallTileCard(
                                                pageNumber = slotIdx + 1,
                                                item = item,
                                                isSelectionMode = isSelectionMode,
                                                isSelected = item.id in selectedPageIds,
                                                canDragShare = !isSelectionMode && !state.isActivelyScanningPage,
                                                resolveJpgForDrag = { resolvePageJpgSync(slotIdx, item) },
                                                onDragShareStarted = { file -> activeDraggedJpg = file },
                                                onTap = {
                                                    if (isSelectionMode) {
                                                        onToggleSelectPage(item.id)
                                                    } else {
                                                        onInspectPage(slotIdx)
                                                    }
                                                },
                                                onShareJpg = { triggerDirectJpgShare(slotIdx, item) },
                                                onCrop = { onCropPage(slotIdx) },
                                                onRotate = { onRotatePageAt(slotIdx) },
                                                onDelete = { onDeletePageAt(slotIdx) },
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        slotIdx == state.pages.size && state.isActivelyScanningPage -> {
                                            ActiveScanningGridCard(
                                                pageNumber = state.pages.size + 1,
                                                sessionTitle = state.sessionTitle.ifBlank { "Scan" },
                                                progress = animatedProgress,
                                                statusText = state.currentPageStatus,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        else -> {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    RecentScansViewMode.LIST -> {
                        if (state.isActivelyScanningPage) {
                            item(key = "active_scanning_list_file") {
                                ActiveScanningListRow(
                                    fileTitle = tr("Page ${state.pages.size + 1}", "पेज ${state.pages.size + 1}"),
                                    pageNumber = state.pages.size + 1,
                                    progress = animatedProgress,
                                    statusText = state.currentPageStatus.ifBlank {
                                        tr("Scanning in background — safe to go back", "बैकग्राउंड में स्कैन हो रहा है — वापस जाना सुरक्षित है")
                                    },
                                )
                            }
                        }

                        itemsIndexed(
                            items = state.pages,
                            key = { _, page ->
                                "${page.id}_${System.identityHashCode(page.bitmap)}_${page.bitmap.generationId}"
                            },
                        ) { idx, item ->
                            CapturedPageListRow(
                                pageNumber = idx + 1,
                                title = tr("Page ${idx + 1}", "पेज ${idx + 1}"),
                                item = item,
                                isSelectionMode = isSelectionMode,
                                isSelected = item.id in selectedPageIds,
                                canDragShare = !isSelectionMode && !state.isActivelyScanningPage,
                                resolveJpgForDrag = { resolvePageJpgSync(idx, item) },
                                onDragShareStarted = { file -> activeDraggedJpg = file },
                                onTap = {
                                    if (isSelectionMode) {
                                        onToggleSelectPage(item.id)
                                    } else {
                                        onInspectPage(idx)
                                    }
                                },
                                onShareJpg = { triggerDirectJpgShare(idx, item) },
                                onCrop = { onCropPage(idx) },
                                onRotate = { onRotatePageAt(idx) },
                                onDelete = { onDeletePageAt(idx) },
                            )
                        }
                    }
                }
            }

            // Bottom Controls: Scan / Cancel (normal mode) OR Merge / Print (selection mode).
            // Save / Export lives in the top header bar.
            val showSelectionBottomBar = isSelectionMode && (effectiveCount >= 2 || canPrint)
            val showNormalBottomBar = !isSelectionMode && (state.isActivelyScanningPage || canScanMore)
            if (showSelectionBottomBar || showNormalBottomBar) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.isActivelyScanningPage) {
                        OutlinedButton(
                            onClick = onCancelScan,
                            border = BorderStroke(1.dp, ThermalRed.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ThermalRed),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("scan_cancel_button"),
                            shape = RoundedCornerShape(15.dp),
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr("Cancel Scan", "स्कैन रद्द करें"), maxLines = 1)
                        }
                    } else if (isSelectionMode) {
                        if (effectiveCount >= 2) {
                            Button(
                                onClick = onMergeSelectedPages,
                                enabled = !state.isMergingIdCard,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SignalEmerald,
                                    contentColor = Color.White,
                                ),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(54.dp)
                                    .testTag("scan_merge_selected_button"),
                                shape = RoundedCornerShape(15.dp),
                            ) {
                                Icon(Icons.Outlined.AutoAwesomeMosaic, contentDescription = null, modifier = Modifier.size(19.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = tr("Merge ($effectiveCount)", "मर्ज करें ($effectiveCount)"),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                )
                            }
                        }
                        if (canPrint) {
                            OutlinedButton(
                                onClick = onPrintCollection,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("scan_print_selected_button"),
                                shape = RoundedCornerShape(15.dp),
                            ) {
                                Icon(Icons.Outlined.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Print ($effectiveCount)", "प्रिंट ($effectiveCount)"), maxLines = 1)
                            }
                        }
                    } else if (canScanMore) {
                        Button(
                            onClick = onTriggerScan,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SignalEmerald,
                                contentColor = Color.White,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .testTag("scan_more_pages_button"),
                            shape = RoundedCornerShape(15.dp),
                        ) {
                            Icon(Icons.Rounded.Scanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Scan", "स्कैन करें"),
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CapturedPageGridCard(
    pageNumber: Int,
    item: ScannedPageItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    canDragShare: Boolean,
    resolveJpgForDrag: () -> File,
    onDragShareStarted: (File) -> Unit,
    onTap: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val latestOnTap by rememberUpdatedState(onTap)
    val latestResolveJpg by rememberUpdatedState(resolveJpgForDrag)
    val latestOnDragStarted by rememberUpdatedState(onDragShareStarted)
    val interactionModifier = if (canDragShare) {
        Modifier.dragAndDropSource {
            detectTapGestures(
                onTap = { latestOnTap() },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val jpgFile = latestResolveJpg()
                    latestOnDragStarted(jpgFile)
                    startTransfer(ScanShareHelper.createJpgDragTransferData(context, jpgFile))
                },
            )
        }
    } else {
        Modifier.clickable(onClick = onTap)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .then(interactionModifier)
            .testTag("scan_page_grid_${item.id}"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.82f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                key(item.bitmap, item.bitmap.generationId) {
                    val gridImageBitmap = remember(item.bitmap, item.bitmap.generationId) {
                        item.bitmap.asImageBitmap().also { bmp ->
                            runCatching { bmp.prepareToDraw() }
                        }
                    }
                    Image(
                        bitmap = gridImageBitmap,
                        contentDescription = "Scanned page $pageNumber",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Text(
                        text = tr("Page $pageNumber", "पेज $pageNumber"),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
                if (isSelectionMode || isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected page $pageNumber" else "Unselected page $pageNumber",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${item.dpi} DPI • ${item.colorLabel}",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Row {
                    IconButton(
                        onClick = onCrop,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("scan_page_crop_${item.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CropRotate,
                            contentDescription = "Crop and auto-adjust page $pageNumber",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onRotate,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Rotate90DegreesCw,
                            contentDescription = "Rotate page $pageNumber",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete page $pageNumber",
                            tint = ThermalRed,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CapturedPageSmallTileCard(
    pageNumber: Int,
    item: ScannedPageItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    canDragShare: Boolean,
    resolveJpgForDrag: () -> File,
    onDragShareStarted: (File) -> Unit,
    onTap: () -> Unit,
    onShareJpg: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val latestOnTap by rememberUpdatedState(onTap)
    val latestResolveJpg by rememberUpdatedState(resolveJpgForDrag)
    val latestOnDragStarted by rememberUpdatedState(onDragShareStarted)
    val interactionModifier = if (canDragShare) {
        Modifier.dragAndDropSource {
            detectTapGestures(
                onTap = { latestOnTap() },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val jpgFile = latestResolveJpg()
                    latestOnDragStarted(jpgFile)
                    startTransfer(ScanShareHelper.createJpgDragTransferData(context, jpgFile))
                },
            )
        }
    } else {
        Modifier.clickable(onClick = onTap)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(interactionModifier)
            .testTag("scan_page_tile_${item.id}"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                contentAlignment = Alignment.Center,
            ) {
                key(item.bitmap, item.bitmap.generationId) {
                    val tileImageBitmap = remember(item.bitmap, item.bitmap.generationId) {
                        item.bitmap.asImageBitmap().also { bmp ->
                            runCatching { bmp.prepareToDraw() }
                        }
                    }
                    Image(
                        bitmap = tileImageBitmap,
                        contentDescription = "Scanned page $pageNumber",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isSelectionMode || isSelected) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected page $pageNumber" else "Unselected page $pageNumber",
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 7.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("P$pageNumber", "पेज $pageNumber"),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                var showTileMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showTileMenu = true },
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("scan_page_tile_menu_${item.id}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = tr("Page $pageNumber options", "पेज $pageNumber विकल्प"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showTileMenu,
                        onDismissRequest = { showTileMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("Share as JPG", "JPG के रूप में साझा करें")) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Share,
                                    contentDescription = null,
                                    tint = SignalEmerald,
                                )
                            },
                            onClick = {
                                showTileMenu = false
                                onShareJpg()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Crop & Auto-Adjust", "क्रॉप और ऑटो-एडजस्ट")) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.CropRotate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = {
                                showTileMenu = false
                                onCrop()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(tr("Rotate 90°", "90° घुमाएँ")) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Rotate90DegreesCw,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                showTileMenu = false
                                onRotate()
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = tr("Delete Page", "पेज हटाएँ"),
                                    color = ThermalRed,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = ThermalRed,
                                )
                            },
                            onClick = {
                                showTileMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CapturedPageListRow(
    pageNumber: Int,
    title: String,
    item: ScannedPageItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    canDragShare: Boolean,
    resolveJpgForDrag: () -> File,
    onDragShareStarted: (File) -> Unit,
    onTap: () -> Unit,
    onShareJpg: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val latestOnTap by rememberUpdatedState(onTap)
    val latestResolveJpg by rememberUpdatedState(resolveJpgForDrag)
    val latestOnDragStarted by rememberUpdatedState(onDragShareStarted)
    val interactionModifier = if (canDragShare) {
        Modifier.dragAndDropSource {
            detectTapGestures(
                onTap = { latestOnTap() },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    val jpgFile = latestResolveJpg()
                    latestOnDragStarted(jpgFile)
                    startTransfer(ScanShareHelper.createJpgDragTransferData(context, jpgFile))
                },
            )
        }
    } else {
        Modifier.clickable(onClick = onTap)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(interactionModifier)
            .testTag("scan_page_list_${item.id}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (isSelectionMode || isSelected) {
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.size(width = 46.dp, height = 58.dp),
            ) {
                key(item.bitmap, item.bitmap.generationId) {
                    val listImageBitmap = remember(item.bitmap, item.bitmap.generationId) {
                        item.bitmap.asImageBitmap().also { bmp ->
                            runCatching { bmp.prepareToDraw() }
                        }
                    }
                    Image(
                        bitmap = listImageBitmap,
                        contentDescription = "Page $pageNumber",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${item.pageSizeLabel} • ${item.dpi} DPI • ${item.colorLabel}",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            var showActionsMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(
                    onClick = { showActionsMenu = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("scan_page_list_menu_${item.id}"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = tr("Page $pageNumber options", "पेज $pageNumber विकल्प"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showActionsMenu,
                    onDismissRequest = { showActionsMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(tr("Share as JPG", "JPG के रूप में साझा करें")) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = null,
                                tint = SignalEmerald,
                            )
                        },
                        onClick = {
                            showActionsMenu = false
                            onShareJpg()
                        },
                        modifier = Modifier.testTag("scan_page_list_share_${item.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Crop & Auto-Adjust", "क्रॉप और ऑटो-एडजस्ट")) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.CropRotate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            showActionsMenu = false
                            onCrop()
                        },
                        modifier = Modifier.testTag("scan_page_list_crop_${item.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(tr("Rotate 90°", "90° घुमाएँ")) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Rotate90DegreesCw,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showActionsMenu = false
                            onRotate()
                        },
                        modifier = Modifier.testTag("scan_page_list_rotate_${item.id}"),
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = tr("Delete Page", "पेज हटाएँ"),
                                color = ThermalRed,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = ThermalRed,
                            )
                        },
                        onClick = {
                            showActionsMenu = false
                            onDelete()
                        },
                        modifier = Modifier.testTag("scan_page_list_delete_${item.id}"),
                    )
                }
            }
        }
    }
}

/**
 * Centered Export Popup Dialog:
 * - NO output filename input or filename shown
 * - Two clean actions: "Save as PDF" and in-lined "Save separately as <JPG / PNG / PDF>" dropdown
 */
@Composable
private fun ScanExportOptionsDialog(
    pageCount: Int,
    isSelectionExport: Boolean,
    isExporting: Boolean,
    onExportNow: (mode: ScanExportMode) -> Unit,
    onSavePdfAs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val separateFormatOptions = listOf(
        ScanExportMode.SEPARATE_JPEG,
        ScanExportMode.SEPARATE_PNG,
        ScanExportMode.SEPARATE_PDF,
    )
    var separateFormat by remember { mutableStateOf(ScanExportMode.SEPARATE_JPEG) }
    var formatDropdownExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .testTag("scan_export_dialog"),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (isSelectionExport) {
                            tr(
                                "Export $pageCount Selected Page${if (pageCount == 1) "" else "s"}",
                                "$pageCount चयनित पेज एक्सपोर्ट करें",
                            )
                        } else {
                            tr(
                                "Export ($pageCount Page${if (pageCount == 1) "" else "s"})",
                                "एक्सपोर्ट ($pageCount पेज)",
                            )
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("scan_export_dialog_close"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = tr("Close export popup", "एक्सपोर्ट बंद करें"),
                        )
                    }
                }

                // Option 1: Save as PDF (+ optional folder picker icon)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = { onExportNow(ScanExportMode.SINGLE_PDF) },
                        enabled = !isExporting,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("export_single_pdf_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tr("Save as PDF", "PDF के रूप में सेव करें"),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }

                    OutlinedButton(
                        onClick = onSavePdfAs,
                        enabled = !isExporting,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("export_pdf_choose_folder_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderOpen,
                            contentDescription = tr("Choose PDF folder", "PDF फ़ोल्डर चुनें"),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Option 2: In-lined "Save separately as <Dropdown: JPG / PNG / PDF>"
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Collections,
                            contentDescription = null,
                            tint = SignalEmerald,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = tr("Save separately as", "अलग-अलग सेव करें"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )

                        Box {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { formatDropdownExpanded = true }
                                    .minimumInteractiveComponentSize()
                                    .testTag("export_separate_format_dropdown"),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = separateFormat.shortFormat,
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDropDown,
                                        contentDescription = tr("Choose separate file format", "फ़ाइल फ़ॉर्मेट चुनें"),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = formatDropdownExpanded,
                                onDismissRequest = { formatDropdownExpanded = false },
                            ) {
                                separateFormatOptions.forEach { option ->
                                    val isSelected = option == separateFormat
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = option.shortFormat,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                )
                                                Text(
                                                    text = option.localizedSubtitle(),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        trailingIcon = {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            separateFormat = option
                                            formatDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = { onExportNow(separateFormat) },
                        enabled = !isExporting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("export_separate_files_button"),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = tr(
                                "Save Separately (${separateFormat.shortFormat})",
                                "अलग-अलग सेव करें (${separateFormat.shortFormat})",
                            ),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Theme-Matched Swipeable & Zoomable Page Viewer:
 * - Background, top bar, and bottom bar strictly follow the active Material 3 theme
 * - Left/right sliding pagination via HorizontalPager (when at 1x zoom)
 * - Touch-screen pinch-to-zoom (1x to 5x), drag-to-pan (when zoomed), and double-tap 1x <-> 2.5x
 * - Top bar: Close, Page indicator, Share, Crop, and Delete (Rotate lives inside Crop editor)
 * - Bottom bar: centered image resolution (width × height px)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ScanPageZoomViewerScreen(
    pages: List<ScannedPageItem>,
    sessionTitle: String,
    initialIndex: Int,
    onCropPageAt: (Int) -> Unit,
    onDeletePageAt: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler {
        onDismiss()
    }

    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val safeInitial = initialIndex.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeInitial,
        pageCount = { pages.size },
    )
    LaunchedEffect(pages.size) {
        if (pages.isEmpty()) {
            onDismiss()
        } else if (pagerState.currentPage > pages.lastIndex) {
            pagerState.scrollToPage(pages.lastIndex)
        }
    }

    val currentIndex = pagerState.currentPage.coerceIn(0, pages.lastIndex)
    val currentPage = pages[currentIndex]

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareChooserTitle = tr("Share Scanned Page (JPG)", "स्कैन किया पेज साझा करें (JPG)")

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scan_page_zoom_viewer"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                // Themed Top Overlay Bar (Close, Page N / Total, Share, Crop, Delete)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("zoom_viewer_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = tr("Close page viewer", "पेज व्यूअर बंद करें"),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("Page ${currentIndex + 1} / ${pages.size}", "पेज ${currentIndex + 1} / ${pages.size}"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${currentPage.pageSizeLabel} • ${currentPage.dpi} DPI • ${currentPage.colorLabel}",
                                style = MonoCodeStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    val file = ScanShareHelper.prepareJpgFile(
                                        context = context,
                                        pageId = currentPage.id,
                                        sessionTitle = sessionTitle,
                                        pageNumber = currentIndex + 1,
                                        bitmap = currentPage.bitmap,
                                    )
                                    ScanShareHelper.shareSingleJpg(context, file, shareChooserTitle)
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("zoom_viewer_share_jpg_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = tr("Share page as JPG", "पेज JPG के रूप में साझा करें"),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        IconButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                                onCropPageAt(currentIndex)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("zoom_viewer_crop_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CropRotate,
                                contentDescription = "Crop and auto-adjust page",
                                tint = SignalEmerald,
                            )
                        }

                        IconButton(
                            onClick = {
                                onDeletePageAt(currentIndex)
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("zoom_viewer_delete_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete page",
                                tint = ThermalRed,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Reset zoom cleanly whenever the user swipes to a different page
                LaunchedEffect(pagerState.currentPage, currentPage.id) {
                    scale = 1f
                    offset = Offset.Zero
                }

                // Swipeable HorizontalPager + Touch-Screen Pinch-to-Zoom, Drag-to-Pan & Double-Tap Zoom
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = scale <= 1.02f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .testTag("zoom_viewer_pager"),
                ) { pageIdx ->
                    val pageItem = pages[pageIdx]
                    val isCurrent = pageIdx == pagerState.currentPage
                    val pageScale = if (isCurrent) scale else 1f
                    val pageOffset = if (isCurrent) offset else Offset.Zero
                    val pageImageBitmap = remember(pageItem.bitmap) {
                        pageItem.bitmap.asImageBitmap().also { bmp ->
                            runCatching { bmp.prepareToDraw() }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(pageItem.id) {
                                detectTapGestures(
                                    onDoubleTap = { tapPos ->
                                        if (scale > 1.25f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            val targetScale = 2.5f
                                            val center = Offset(size.width / 2f, size.height / 2f)
                                            val maxX = (size.width * (targetScale - 1f)) / 2f
                                            val maxY = (size.height * (targetScale - 1f)) / 2f
                                            val rawOffset = (center - tapPos) * (targetScale - 1f)
                                            scale = targetScale
                                            offset = Offset(
                                                x = rawOffset.x.coerceIn(-maxX, maxX),
                                                y = rawOffset.y.coerceIn(-maxY, maxY),
                                            )
                                        }
                                    },
                                )
                            }
                            .pointerInput(pageItem.id) {
                                val touchSlop = viewConfiguration.touchSlop
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    var totalPanDistance = 0f
                                    var isPanningZoomed = false
                                    do {
                                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                        val pressedChanges = event.changes.filter { it.pressed }
                                        val pressedCount = pressedChanges.size

                                        if (pressedCount >= 2) {
                                            // Multi-touch pinch-to-zoom: intercept BEFORE HorizontalPager can scroll
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val centroid = event.calculateCentroid(useCurrent = true)
                                            val prevScale = scale
                                            val nextScale = (prevScale * zoomChange).coerceIn(1f, 5f)
                                            val maxX = (size.width * (nextScale - 1f)) / 2f
                                            val maxY = (size.height * (nextScale - 1f)) / 2f
                                            val nextOffset = if (nextScale <= 1.02f) {
                                                Offset.Zero
                                            } else {
                                                val center = Offset(size.width / 2f, size.height / 2f)
                                                val zoomFactor = if (prevScale > 0f) nextScale / prevScale else 1f
                                                val focusFromCenter = if (centroid != Offset.Unspecified) {
                                                    centroid - center
                                                } else {
                                                    Offset.Zero
                                                }
                                                val raw = (offset - focusFromCenter) * zoomFactor + focusFromCenter + panChange
                                                Offset(
                                                    x = raw.x.coerceIn(-maxX, maxX),
                                                    y = raw.y.coerceIn(-maxY, maxY),
                                                )
                                            }
                                            scale = nextScale
                                            offset = nextOffset
                                            // Consume all multi-touch pointer events so HorizontalPager never steals pinch
                                            event.changes.forEach { change ->
                                                if (change.positionChanged() || change.pressed) {
                                                    change.consume()
                                                }
                                            }
                                        } else if (pressedCount == 1 && scale > 1.02f) {
                                            // Single-finger drag-to-pan while zoomed (leave taps unconsumed for double-tap)
                                            val panChange = event.calculatePan()
                                            totalPanDistance += panChange.getDistance()
                                            if (!isPanningZoomed && totalPanDistance > touchSlop) {
                                                isPanningZoomed = true
                                            }
                                            if (isPanningZoomed && panChange != Offset.Zero) {
                                                val maxX = (size.width * (scale - 1f)) / 2f
                                                val maxY = (size.height * (scale - 1f)) / 2f
                                                offset = Offset(
                                                    x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                                    y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
                                                )
                                                event.changes.forEach { change ->
                                                    if (change.positionChanged()) {
                                                        change.consume()
                                                    }
                                                }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            bitmap = pageImageBitmap,
                            contentDescription = "Zoomable preview of page ${pageIdx + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .graphicsLayer {
                                    scaleX = pageScale
                                    scaleY = pageScale
                                    translationX = pageOffset.x
                                    translationY = pageOffset.y
                                },
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Themed Bottom Bar: Centered Image Resolution (width × height px)
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.testTag("zoom_viewer_resolution_badge"),
                        ) {
                            Text(
                                text = "${currentPage.bitmap.width} × ${currentPage.bitmap.height} px",
                                style = MonoCodeStyle.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
