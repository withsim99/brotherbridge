package com.example.ui.printservice

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintJobInfo
import android.printservice.PrintService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DuplexMode
import com.example.model.PagesPerSheet
import com.example.model.PrintResolution
import com.example.service.PrintBridgePrintService
import com.example.ui.components.ParameterDropdownField
import com.example.ui.theme.PrintBridgeTheme

/**
 * Advanced print options activity launched directly from Android's system Print Dialog
 * ("More options") when printing from File Manager, Google Drive, Chrome, Photos, etc.
 */
class PrintServiceOptionsActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialInfo: PrintJobInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(PrintService.EXTRA_PRINT_JOB_INFO, PrintJobInfo::class.java)
        } else {
            intent.getParcelableExtra(PrintService.EXTRA_PRINT_JOB_INFO)
        }

        if (initialInfo == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        setContent {
            PrintBridgeTheme {
                PrintServiceOptionsScreen(
                    initialInfo = initialInfo,
                    onCancel = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    },
                    onApply = { resolution, duplex, nUp, tonerSave, drawBorders ->
                        val updated = buildUpdatedPrintJobInfo(
                            original = initialInfo,
                            resolution = resolution,
                            duplex = duplex,
                            nUp = nUp,
                            tonerSave = tonerSave,
                            drawBorders = drawBorders,
                        )
                        val resultIntent = Intent().putExtra(PrintService.EXTRA_PRINT_JOB_INFO, updated)
                        setResult(Activity.RESULT_OK, resultIntent)
                        finish()
                    },
                )
            }
        }
    }

    private fun buildUpdatedPrintJobInfo(
        original: PrintJobInfo,
        resolution: PrintResolution,
        duplex: DuplexMode,
        nUp: PagesPerSheet,
        tonerSave: Boolean,
        drawBorders: Boolean,
    ): PrintJobInfo {
        val builder = PrintJobInfo.Builder(original)
        builder.putAdvancedOption(PrintBridgePrintService.OPTION_DPI, resolution.dpi)
        builder.putAdvancedOption(PrintBridgePrintService.OPTION_DUPLEX_MODE, duplex.ippKeyword)
        builder.putAdvancedOption(PrintBridgePrintService.OPTION_NUP, nUp.count)
        builder.putAdvancedOption(PrintBridgePrintService.OPTION_TONER_SAVE, if (tonerSave) 1 else 0)
        builder.putAdvancedOption(PrintBridgePrintService.OPTION_DRAW_BORDERS, if (drawBorders) 1 else 0)

        val currentAttrs = original.attributes
        val attrsBuilder = PrintAttributes.Builder()
        currentAttrs.mediaSize?.let { attrsBuilder.setMediaSize(it) }
        currentAttrs.minMargins?.let { attrsBuilder.setMinMargins(it) }
        if (currentAttrs.colorMode != 0) {
            attrsBuilder.setColorMode(currentAttrs.colorMode)
        }
        attrsBuilder.setResolution(
            PrintAttributes.Resolution(
                "dpi_${resolution.dpi}",
                resolution.label,
                resolution.dpi,
                resolution.dpi,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val sysDuplex = when (duplex) {
                DuplexMode.LONG_EDGE -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                DuplexMode.SHORT_EDGE -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                DuplexMode.SIMPLEX -> PrintAttributes.DUPLEX_MODE_NONE
            }
            attrsBuilder.setDuplexMode(sysDuplex)
        }
        builder.setAttributes(attrsBuilder.build())
        return builder.build()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintServiceOptionsScreen(
    initialInfo: PrintJobInfo,
    onCancel: () -> Unit,
    onApply: (
        resolution: PrintResolution,
        duplex: DuplexMode,
        nUp: PagesPerSheet,
        tonerSave: Boolean,
        drawBorders: Boolean,
    ) -> Unit,
) {
    val startDpi = if (initialInfo.hasAdvancedOption(PrintBridgePrintService.OPTION_DPI)) {
        initialInfo.getAdvancedIntOption(PrintBridgePrintService.OPTION_DPI)
    } else {
        initialInfo.attributes.resolution?.horizontalDpi ?: 300
    }
    val startDuplex = when {
        initialInfo.hasAdvancedOption(PrintBridgePrintService.OPTION_DUPLEX_MODE) -> {
            DuplexMode.fromIppKeyword(initialInfo.getAdvancedStringOption(PrintBridgePrintService.OPTION_DUPLEX_MODE))
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            when (initialInfo.attributes.duplexMode) {
                PrintAttributes.DUPLEX_MODE_LONG_EDGE -> DuplexMode.LONG_EDGE
                PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> DuplexMode.SHORT_EDGE
                else -> DuplexMode.SIMPLEX
            }
        }
        else -> DuplexMode.SIMPLEX
    }
    val startNUp = if (initialInfo.hasAdvancedOption(PrintBridgePrintService.OPTION_NUP)) {
        PagesPerSheet.fromCount(initialInfo.getAdvancedIntOption(PrintBridgePrintService.OPTION_NUP))
    } else {
        PagesPerSheet.ONE
    }
    val startTonerSave = initialInfo.hasAdvancedOption(PrintBridgePrintService.OPTION_TONER_SAVE) &&
        initialInfo.getAdvancedIntOption(PrintBridgePrintService.OPTION_TONER_SAVE) == 1
    val startDrawBorders = if (initialInfo.hasAdvancedOption(PrintBridgePrintService.OPTION_DRAW_BORDERS)) {
        initialInfo.getAdvancedIntOption(PrintBridgePrintService.OPTION_DRAW_BORDERS) == 1
    } else {
        true
    }

    var resolution by remember { mutableStateOf(PrintResolution.fromDpi(startDpi)) }
    var duplex by remember { mutableStateOf(startDuplex) }
    var nUp by remember { mutableStateOf(startNUp) }
    var tonerSave by remember { mutableStateOf(startTonerSave) }
    var drawBorders by remember { mutableStateOf(startDrawBorders) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.print_service_options_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("print_service_options_close"),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Print Parameters",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ParameterDropdownField(
                                label = "DPI",
                                selectedValue = resolution,
                                options = PrintResolution.entries,
                                optionLabel = { "${it.dpi} DPI" },
                                optionSubtitle = { it.label },
                                onSelect = { resolution = it },
                                testTag = "sysprint_dpi_dropdown",
                                modifier = Modifier.weight(1f),
                            )

                            ParameterDropdownField(
                                label = "Sides",
                                selectedValue = duplex,
                                options = DuplexMode.entries,
                                optionLabel = { it.shortLabel },
                                optionSubtitle = { it.subtitle },
                                onSelect = { duplex = it },
                                testTag = "sysprint_duplex_dropdown",
                                modifier = Modifier.weight(1f),
                            )
                        }

                        ParameterDropdownField(
                            label = "Pages per Sheet (N-Up)",
                            selectedValue = nUp,
                            options = PagesPerSheet.entries,
                            optionLabel = { it.shortLabel },
                            optionSubtitle = { it.description },
                            onSelect = { nUp = it },
                            testTag = "sysprint_nup_dropdown",
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Toner Save (Eco Mode)",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = tonerSave,
                                onCheckedChange = { tonerSave = it },
                                modifier = Modifier.testTag("sysprint_toner_save_switch"),
                            )
                        }

                        if (nUp.count > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Draw N-Up Cell Borders",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = drawBorders,
                                    onCheckedChange = { drawBorders = it },
                                    modifier = Modifier.testTag("sysprint_borders_switch"),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { onApply(resolution, duplex, nUp, tonerSave, drawBorders) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("sysprint_apply_options_button"),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text(
                        text = "Apply Print Parameters",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
