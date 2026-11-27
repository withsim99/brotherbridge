package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ActivePrinterState
import com.example.model.DuplexMode
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintColorScheme
import com.example.model.PrintOrientation
import com.example.model.PrintResolution
import com.example.model.PrintScaling
import com.example.model.PrinterConnectionMode
import com.example.printer.render.NUpLayoutEngine
import com.example.ui.DirectPrintUiState
import com.example.ui.components.AdvancedSettingsCard
import com.example.ui.components.ParameterDropdownField
import com.example.ui.i18n.localizedDescription
import com.example.ui.i18n.localizedDisplayName
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.localizedShortLabel
import com.example.ui.i18n.localizedSubtitle
import com.example.ui.i18n.tr
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.ThermalRed
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DirectPrintScreen(
    activePrinter: ActivePrinterState,
    state: DirectPrintUiState,
    onSelectPaperSize: (PaperSize) -> Unit,
    onSelectNUp: (PagesPerSheet) -> Unit,
    onSelectDuplex: (DuplexMode) -> Unit,
    onSelectResolution: (PrintResolution) -> Unit,
    onSelectOrientation: (PrintOrientation) -> Unit,
    onSelectColorScheme: (PrintColorScheme) -> Unit,
    onToggleTonerSave: (Boolean) -> Unit,
    onChangeCopies: (Int) -> Unit,
    onToggleBorders: (Boolean) -> Unit,
    onChangeImageMarginMm: (Float) -> Unit,
    onSelectImageScaling: (PrintScaling) -> Unit,
    onPanImage: (Float, Float) -> Unit,
    onResetImagePan: () -> Unit,
    onPrintNow: () -> Unit,
    @Suppress("UNUSED_PARAMETER")
    onPrintTestPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = state.selectedDocument
    val sourcePages = doc?.metadata?.pageCount ?: 0
    val imposedSides = NUpLayoutEngine.calculateImposedSides(sourcePages, state.pagesPerSheet)
    val physicalSheets = NUpLayoutEngine.calculatePhysicalSheets(imposedSides, state.duplexMode)
    val selectablePaperSizes = listOf(PaperSize.A4, PaperSize.LETTER, PaperSize.LEGAL, PaperSize.A5, PaperSize.B5)
    val selectableOrientations = listOf(PrintOrientation.PORTRAIT, PrintOrientation.LANDSCAPE)

    val nUpShort = state.pagesPerSheet.localizedShortLabel()
    val bordersSuffix = tr(" • Borders", " • बॉर्डर")
    val ecoSuffix = tr(" • Eco", " • इको")
    val advancedSummary = buildString {
        append(nUpShort)
        if (state.drawBorders) append(bordersSuffix)
        if (state.tonerSave) append(ecoSuffix)
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
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
                        border = BorderStroke(1.dp, ThermalRed.copy(alpha = 0.35f)),
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

            // 1. Live Print Sheet Preview Card (no top choose-file or file-name card!)
            if (doc != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = tr("Print Preview", "प्रिंट प्रीव्यू"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = SignalEmerald.copy(alpha = 0.14f),
                                ) {
                                    Text(
                                        text = tr(
                                            "${sourcePages}p → $physicalSheets sheet${if (physicalSheets == 1) "" else "s"}",
                                            "${sourcePages}पेज → $physicalSheets शीट",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SignalEmerald,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val isImage = doc.metadata.kind.isImage
                            val canDragCrop = isImage && state.imageScaling == PrintScaling.FILL
                            val currentPanX by rememberUpdatedState(state.imagePanX)
                            val currentPanY by rememberUpdatedState(state.imagePanY)
                            val currentOnPan by rememberUpdatedState(onPanImage)

                            val effectiveLandscape = state.orientation.isLandscape || state.pagesPerSheet.preferLandscapeSheet
                            val sheetAspect = if (effectiveLandscape) {
                                (state.paperSize.heightMm / state.paperSize.widthMm).toFloat()
                            } else {
                                (state.paperSize.widthMm / state.paperSize.heightMm).toFloat()
                            }
                            val previewColorFilter = when (state.colorScheme) {
                                PrintColorScheme.GRAYSCALE -> ColorFilter.colorMatrix(
                                    ColorMatrix().apply { setToSaturation(0f) },
                                )
                                PrintColorScheme.MONOCHROME_2BIT -> {
                                    // High-contrast crisp B&W preview reflecting 2-bit/1-bit threshold printing
                                    val c = 3.2f
                                    val t = -128f * (c - 1f)
                                    val r = 0.299f * c
                                    val g = 0.587f * c
                                    val b = 0.114f * c
                                    ColorFilter.colorMatrix(
                                        ColorMatrix(
                                            floatArrayOf(
                                                r, g, b, 0f, t,
                                                r, g, b, 0f, t,
                                                r, g, b, 0f, t,
                                                0f, 0f, 0f, 1f, 0f,
                                            ),
                                        ),
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    shadowElevation = 4.dp,
                                    color = Color.White,
                                    border = BorderStroke(
                                        width = if (canDragCrop) 1.5.dp else 1.dp,
                                        color = if (canDragCrop) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color(0xFFCBD5E1),
                                    ),
                                    modifier = Modifier
                                        .widthIn(max = if (effectiveLandscape) 300.dp else 210.dp)
                                        .fillMaxWidth()
                                        .aspectRatio(sheetAspect)
                                        .then(
                                            if (canDragCrop) {
                                                Modifier.pointerInput(doc.id, canDragCrop) {
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val w = size.width.coerceAtLeast(1).toFloat()
                                                        val h = size.height.coerceAtLeast(1).toFloat()
                                                        val nextX = (currentPanX + (dragAmount.x / (w * 0.45f))).coerceIn(-1f, 1f)
                                                        val nextY = (currentPanY + (dragAmount.y / (h * 0.45f))).coerceIn(-1f, 1f)
                                                        currentOnPan(nextX, nextY)
                                                    }
                                                }
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .testTag("print_preview_sheet"),
                                ) {
                                    when {
                                        doc.isRenderingPreview && doc.previewBitmap == null -> {
                                            Box(contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                            }
                                        }
                                        doc.previewBitmap != null -> {
                                            Image(
                                                bitmap = doc.previewBitmap.asImageBitmap(),
                                                contentDescription = tr("Rendered print sheet preview", "प्रिंट शीट प्रीव्यू"),
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Fit,
                                                colorFilter = previewColorFilter,
                                            )
                                        }
                                    }
                                }
                            }

                            // Image-only Page Margin / Padding & Full-Page Crop controls inside Preview tab
                            if (isImage) {
                                Spacer(modifier = Modifier.height(14.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Spacer(modifier = Modifier.height(12.dp))

                                ImagePreviewPageControls(
                                    marginMm = state.imageMarginMm,
                                    scaling = state.imageScaling,
                                    hasPanOffset = abs(state.imagePanX) > 0.02f || abs(state.imagePanY) > 0.02f,
                                    onChangeMarginMm = onChangeImageMarginMm,
                                    onSelectScaling = onSelectImageScaling,
                                    onResetPan = onResetImagePan,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Normal Print Parameters in Two-Column Inline Grid (Paper Size, DPI, Duplex, Copies)
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
                            text = tr("Print Parameters", "प्रिंट विकल्प"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )

                        // Row 1: Paper Size | DPI (moved from Advanced into Normal parameters)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ParameterDropdownField(
                                label = stringResource(R.string.label_paper_size),
                                selectedValue = state.paperSize,
                                options = selectablePaperSizes,
                                optionLabel = { it.pjlName },
                                optionSubtitle = { it.localizedDisplayName() },
                                onSelect = onSelectPaperSize,
                                testTag = "print_paper_size_dropdown",
                                modifier = Modifier.weight(1f),
                            )

                            ParameterDropdownField(
                                label = "DPI",
                                selectedValue = state.resolution,
                                options = PrintResolution.entries,
                                optionLabel = { "${it.dpi} DPI" },
                                optionSubtitle = { it.localizedLabel() },
                                onSelect = onSelectResolution,
                                testTag = "print_resolution_dropdown",
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Row 2: Orientation (Portrait / Landscape) | Color Scheme (Grayscale / 2-Bit B&W)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            ParameterDropdownField(
                                label = tr("Orientation", "अभिविन्यास"),
                                selectedValue = if (state.orientation.isLandscape) PrintOrientation.LANDSCAPE else PrintOrientation.PORTRAIT,
                                options = selectableOrientations,
                                optionLabel = { opt ->
                                    when (opt) {
                                        PrintOrientation.LANDSCAPE -> tr("Landscape", "लैंडस्केप")
                                        else -> tr("Portrait", "पोर्ट्रेट")
                                    }
                                },
                                onSelect = onSelectOrientation,
                                testTag = "print_orientation_dropdown",
                                modifier = Modifier.weight(1f),
                            )

                            ParameterDropdownField(
                                label = tr("Color Scheme", "कलर स्कीम"),
                                selectedValue = state.colorScheme,
                                options = PrintColorScheme.entries,
                                optionLabel = { scheme ->
                                    when (scheme) {
                                        PrintColorScheme.GRAYSCALE -> tr("Grayscale", "ग्रेस्केल")
                                        PrintColorScheme.MONOCHROME_2BIT -> tr("2-Bit B&W", "2-बिट B&W")
                                    }
                                },
                                optionSubtitle = { scheme ->
                                    when (scheme) {
                                        PrintColorScheme.GRAYSCALE -> tr("Smooth halftone shades", "फोटो और शेड्स के लिए")
                                        PrintColorScheme.MONOCHROME_2BIT -> tr("Crisp black & white", "साफ़ टेक्स्ट और लाइन के लिए")
                                    }
                                },
                                onSelect = onSelectColorScheme,
                                testTag = "print_color_scheme_dropdown",
                                modifier = Modifier.weight(1f),
                            )
                        }

                        // Row 3: Double-Sided (Duplex) | Copies Inline Stepper
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ParameterDropdownField(
                                label = tr("Sides", "तरफ़"),
                                selectedValue = state.duplexMode,
                                options = DuplexMode.entries,
                                optionLabel = { it.localizedShortLabel() },
                                optionSubtitle = { it.localizedSubtitle() },
                                onSelect = onSelectDuplex,
                                testTag = "print_duplex_dropdown",
                                modifier = Modifier.weight(1f),
                            )

                            InlineCopiesControl(
                                copies = state.copies,
                                onChangeCopies = onChangeCopies,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // 3. Collapsible Advanced Settings (N-Up, Borders, Toner Save)
            item {
                AdvancedSettingsCard(
                    summaryText = advancedSummary,
                    testTag = "print_advanced_settings_toggle",
                ) {
                    ParameterDropdownField(
                        label = stringResource(R.string.label_pages_per_sheet),
                        selectedValue = state.pagesPerSheet,
                        options = PagesPerSheet.entries,
                        optionLabel = { "${it.localizedShortLabel()} — ${it.localizedDescription()}" },
                        onSelect = onSelectNUp,
                        testTag = "print_nup_dropdown",
                    )

                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            ToggleOptionRow(
                                label = stringResource(R.string.label_page_borders),
                                checked = state.drawBorders,
                                onCheckedChange = onToggleBorders,
                                testTag = "toggle_nup_borders",
                                modifier = Modifier.weight(1f),
                            )
                            ToggleOptionRow(
                                label = stringResource(R.string.label_toner_save),
                                checked = state.tonerSave,
                                onCheckedChange = onToggleTonerSave,
                                testTag = "toggle_toner_save",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        ToggleOptionRow(
                            label = stringResource(R.string.label_page_borders),
                            checked = state.drawBorders,
                            onCheckedChange = onToggleBorders,
                            testTag = "toggle_nup_borders",
                        )
                        ToggleOptionRow(
                            label = stringResource(R.string.label_toner_save),
                            checked = state.tonerSave,
                            onCheckedChange = onToggleTonerSave,
                            testTag = "toggle_toner_save",
                        )
                    }
                }
            }

            // 4. Primary Send to Printer CTA
            item {
                val printCta = when (activePrinter.mode) {
                    PrinterConnectionMode.WIRELESS_REMOTE -> tr("Print Wirelessly", "वायरलेस प्रिंट करें")
                    else -> stringResource(R.string.action_print_now)
                }
                Button(
                    onClick = onPrintNow,
                    enabled = doc != null && !state.isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("send_to_printer_button"),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Rounded.Print, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (doc != null) {
                            tr("$printCta ($physicalSheets sh)", "$printCta ($physicalSheets शीट)")
                        } else {
                            tr("No Document Selected", "कोई दस्तावेज़ नहीं चुना गया")
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun InlineCopiesControl(
    copies: Int,
    onChangeCopies: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .padding(top = 6.dp)
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { onChangeCopies(copies - 1) },
                enabled = copies > 1,
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("copies_decrease_button"),
            ) {
                Icon(
                    Icons.Outlined.Remove,
                    contentDescription = tr("Decrease copies", "प्रतियाँ कम करें"),
                    modifier = Modifier.size(18.dp),
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = tr("Copies", "प्रतियाँ"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$copies",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            IconButton(
                onClick = { onChangeCopies(copies + 1) },
                enabled = copies < 99,
                modifier = Modifier
                    .size(40.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("copies_increase_button"),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = tr("Increase copies", "प्रतियाँ बढ़ाएँ"),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleOptionRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun ImagePreviewPageControls(
    marginMm: Float,
    scaling: PrintScaling,
    hasPanOffset: Boolean,
    onChangeMarginMm: (Float) -> Unit,
    onSelectScaling: (PrintScaling) -> Unit,
    onResetPan: () -> Unit,
) {
    val isFill = scaling == PrintScaling.FILL
    val roundedMargin = marginMm.roundToInt()
    val marginPresets = listOf(0f, 4f, 10f, 20f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("image_preview_layout_controls"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Fit vs Full Page (Crop to Fill) selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScalingModeChip(
                label = tr("Fit Page", "पेज में फ़िट"),
                icon = Icons.Outlined.FitScreen,
                selected = !isFill,
                onClick = { onSelectScaling(PrintScaling.AUTO_FIT) },
                testTag = "print_image_scaling_fit",
                modifier = Modifier.weight(1f),
            )
            ScalingModeChip(
                label = tr("Full Page (Crop)", "पूरा पेज (क्रॉप)"),
                icon = Icons.Outlined.CropFree,
                selected = isFill,
                onClick = { onSelectScaling(PrintScaling.FILL) },
                testTag = "print_image_scaling_fill",
                modifier = Modifier.weight(1f),
            )
        }

        if (isFill) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenWith,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = tr("Drag preview to position crop", "क्रॉप स्थिति बदलने के लिए प्रीव्यू खींचें"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (hasPanOffset) {
                    TextButton(
                        onClick = onResetPan,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("print_image_pan_reset"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CenterFocusWeak,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tr("Center", "केंद्र"),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }

        // 2. Page Padding / Margin slider + quick presets
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tr("Page Margin / Padding", "पेज मार्जिन / पैडिंग"),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (roundedMargin == 0) {
                        SignalEmerald.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    },
                ) {
                    Text(
                        text = if (roundedMargin == 0) {
                            tr("0 mm (Full)", "0 मिमी (पूर्ण)")
                        } else {
                            tr("$roundedMargin mm", "$roundedMargin मिमी")
                        },
                        style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                        color = if (roundedMargin == 0) SignalEmerald else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }

            Slider(
                value = marginMm.coerceIn(0f, 25f),
                onValueChange = { onChangeMarginMm(it.roundToInt().toFloat()) },
                valueRange = 0f..25f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("print_image_margin_slider"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                marginPresets.forEach { preset ->
                    val selected = abs(marginMm - preset) < 0.6f
                    Surface(
                        onClick = { onChangeMarginMm(preset) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        },
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("print_margin_preset_${preset.toInt()}"),
                    ) {
                        Box(
                            modifier = Modifier.padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (preset == 0f) tr("0 mm", "0 मिमी") else "${preset.toInt()} mm",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScalingModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
