package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.model.PrintResolution
import com.example.model.PrintServerSettings
import com.example.model.ScanParameters
import com.example.ui.i18n.tr

@Composable
fun SettingsScreen(
    settings: PrintServerSettings,
    onToggleAutoStartServerOnOtg: (Boolean) -> Unit,
    onSelectDefaultPrintResolution: (PrintResolution) -> Unit,
    onSelectDefaultScanDpi: (Int) -> Unit,
    onSelectAppLanguage: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        // i) Auto-start server on USB OTG connect
                        SettingsToggleRow(
                            title = tr(
                                "Automatically start server when connected via OTG",
                                "OTG से कनेक्ट होने पर सर्वर अपने आप शुरू करें",
                            ),
                            checked = settings.autoStartServerOnOtg,
                            onCheckedChange = onToggleAutoStartServerOnOtg,
                            tag = "toggle_auto_start_otg_server",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // ii) Default Printing Resolution (Inline Dropdown, default 600 DPI)
                        InlineSettingsDropdownRow(
                            title = tr("Default Printing Resolution", "डिफ़ॉल्ट प्रिंटिंग रिज़ॉल्यूशन"),
                            selectedDisplay = "${settings.defaultPrintResolution.dpi} DPI",
                            options = PrintResolution.entries,
                            selectedOption = settings.defaultPrintResolution,
                            optionLabel = { "${it.dpi} DPI" },
                            onSelect = onSelectDefaultPrintResolution,
                            tag = "dropdown_default_print_resolution",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // iii) Default Scanning Resolution (Inline Dropdown, default 600 DPI, up to 2400 DPI)
                        InlineSettingsDropdownRow(
                            title = tr("Default Scanning Resolution", "डिफ़ॉल्ट स्कैनिंग रिज़ॉल्यूशन"),
                            selectedDisplay = "${settings.defaultScanDpi} DPI",
                            options = ScanParameters.SUPPORTED_DPI_OPTIONS,
                            selectedOption = settings.defaultScanDpi,
                            optionLabel = { "$it DPI" },
                            onSelect = onSelectDefaultScanDpi,
                            tag = "dropdown_default_scan_resolution",
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // iv) Language Selector (shows "Language" for English, "Language (<native>)" otherwise)
                        InlineSettingsDropdownRow(
                            title = settings.appLanguage.languageSettingLabel,
                            selectedDisplay = settings.appLanguage.nativeLabel,
                            options = AppLanguage.entries,
                            selectedOption = settings.appLanguage,
                            optionLabel = { lang ->
                                if (lang.isEnglish || lang.nativeLabel == lang.englishLabel) {
                                    lang.nativeLabel
                                } else {
                                    "${lang.nativeLabel} • ${lang.englishLabel}"
                                }
                            },
                            onSelect = onSelectAppLanguage,
                            tag = "dropdown_app_language",
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun <T> InlineSettingsDropdownRow(
    title: String,
    selectedDisplay: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    tag: String,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable { expanded = true }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        Box {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .minimumInteractiveComponentSize()
                    .clickable { expanded = true }
                    .testTag(tag),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = selectedDisplay,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 380.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option == selectedOption
                    val label = optionLabel(option)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
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
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}
