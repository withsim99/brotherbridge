package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Scanner
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.i18n.tr
import com.example.ui.theme.LaserAmber
import com.example.ui.theme.SignalEmerald

/**
 * Help & About page with collapsible dropdown-style cards for supported printer/scanner models
 * and setup guides, plus localized author credit at the bottom.
 */
@Composable
fun AboutHelpScreen(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("about_help_screen"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // App Overview Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
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
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("BrotherBridge", "ब्रदरब्रिज"),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(
                                text = tr(
                                    "All-in-one USB OTG & Wi-Fi Printing, Background Scanning, Health Telemetry, and Remote Print/Scan Server.",
                                    "ऑल-इन-वन USB OTG और वाई-फ़ाई प्रिंटिंग, बैकग्राउंड स्कैनिंग, प्रिंटर स्वास्थ्य और रिमोट प्रिंट/स्कैन सर्वर।",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // Supported Printer Models (Dropdown Accordion Card)
            item {
                ExpandableInfoGuideCard(
                    icon = Icons.Outlined.Print,
                    accent = MaterialTheme.colorScheme.primary,
                    title = tr("Supported Printer Models", "समर्थित प्रिंटर मॉडल"),
                    testTag = "help_card_supported_printers",
                ) {
                    GuideBullet(
                        heading = tr(
                            "Direct USB OTG (Brother Laser / brlaser)",
                            "डायरेक्ट USB OTG (ब्रदर लेज़र / brlaser)",
                        ),
                        body = tr(
                            "Brother DCP-L2520D, DCP-L2500D, DCP-L2540DW, HL-L2300D, HL-L2320D, HL-L2340DW, HL-L2360DN, MFC-L2700DW, and compatible Brother monochrome laser printers using PJL + 1030m raster compression.",
                            "Brother DCP-L2520D, DCP-L2500D, DCP-L2540DW, HL-L2300D, HL-L2320D, HL-L2340DW, HL-L2360DN, MFC-L2700DW तथा PJL + 1030m रास्टर का समर्थन करने वाले संगत ब्रदर मोनोक्रोम लेज़र प्रिंटर।",
                        ),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideBullet(
                        heading = tr(
                            "Wireless Network Printers (Wi-Fi / Hotspot)",
                            "वायरलेस नेटवर्क प्रिंटर (वाई-फ़ाई / हॉटस्पॉट)",
                        ),
                        body = tr(
                            "Any BrotherBridge Remote Server, Mopria, AirPrint, or IPP 2.0 compatible network printer discovered over mDNS (_ipp._tcp) or added manually via IP address.",
                            "कोई भी ब्रदरब्रिज रिमोट सर्वर, Mopria, AirPrint या IPP 2.0 संगत नेटवर्क प्रिंटर जिसे mDNS (_ipp._tcp) द्वारा खोजा गया हो या IP पते से मैन्युअल जोड़ा गया हो।",
                        ),
                    )
                }
            }

            // Supported Scanner Models (Dropdown Accordion Card)
            item {
                ExpandableInfoGuideCard(
                    icon = Icons.Outlined.Scanner,
                    accent = SignalEmerald,
                    title = tr("Supported Scanner Models", "समर्थित स्कैनर मॉडल"),
                    testTag = "help_card_supported_scanners",
                ) {
                    GuideBullet(
                        heading = tr(
                            "Direct USB OTG Flatbed & ADF Scanners",
                            "डायरेक्ट USB OTG फ्लैटबेड और ADF स्कैनर",
                        ),
                        body = tr(
                            "Brother DCP-L2520D, DCP-L2540DW, and compatible Brother USB multifunction scanners supporting up to 2400 DPI (Flatbed glass & ADF where equipped).",
                            "Brother DCP-L2520D, DCP-L2540DW तथा 2400 DPI तक का समर्थन करने वाले संगत ब्रदर USB मल्टीफ़ंक्शन स्कैनर (फ्लैटबेड ग्लास और ADF जहाँ उपलब्ध हो)।",
                        ),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideBullet(
                        heading = tr(
                            "Fail-Safe Wireless Host Scanning",
                            "सुरक्षित वायरलेस होस्ट स्कैनिंग",
                        ),
                        body = tr(
                            "BrotherBridge Remote Servers run scans in the background with live progress polling and verified handover — even if you switch apps or reconnect later.",
                            "ब्रदरब्रिज रिमोट सर्वर लाइव प्रगति और सत्यापित हैंडओवर के साथ बैकग्राउंड में स्कैन करते हैं — भले ही आप ऐप बंद करें या बाद में फिर से कनेक्ट हों।",
                        ),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    GuideBullet(
                        heading = tr("Print-Only Models", "केवल-प्रिंट मॉडल"),
                        body = tr(
                            "If your connected printer does not include scanner hardware (e.g., HL-L2320D), the Scan button is automatically hidden on the Home screen.",
                            "यदि आपके कनेक्टेड प्रिंटर में स्कैनर हार्डवेयर नहीं है (जैसे HL-L2320D), तो होम स्क्रीन पर स्कैन बटन अपने आप छिप जाता है।",
                        ),
                    )
                }
            }

            // Guide 1 (Dropdown Accordion Card)
            item {
                ExpandableInfoGuideCard(
                    icon = Icons.Outlined.Usb,
                    accent = MaterialTheme.colorScheme.primary,
                    title = tr("Guide 1: Direct USB OTG & Remote Server", "गाइड 1: डायरेक्ट USB OTG और रिमोट सर्वर"),
                    testTag = "help_card_guide_usb",
                ) {
                    GuideStep(
                        "1",
                        tr(
                            "Connect your printer to this Android device using a USB-B to USB-C / OTG adapter cable.",
                            "USB-B से USB-C / OTG एडॉप्टर केबल द्वारा अपने प्रिंटर को इस एंड्रॉइड डिवाइस से कनेक्ट करें।",
                        ),
                    )
                    GuideStep(
                        "2",
                        tr(
                            "Tap Allow when Android prompts for USB device permission. Your printer status appears on the Home screen.",
                            "जब एंड्रॉइड USB अनुमति माँगे तो Allow दबाएँ। आपके प्रिंटर की स्थिति होम स्क्रीन पर दिखाई देगी।",
                        ),
                    )
                    GuideStep(
                        "3",
                        tr(
                            "Use Print, Scan, or tap the Health icon to check toner, drum, paper tray, and device counters.",
                            "प्रिंट, स्कैन का उपयोग करें या टोनर, ड्रम, पेपर ट्रे और डिवाइस काउंटर देखने के लिए स्वास्थ्य आइकन दबाएँ।",
                        ),
                    )
                    GuideStep(
                        "4",
                        tr(
                            "Optional: Tap Server at the bottom of Home (or enable OTG auto-start in Settings) to share this USB printer & scanner wirelessly over Wi-Fi or Hotspot.",
                            "वैकल्पिक: वाई-फ़ाई या हॉटस्पॉट पर इस USB प्रिंटर और स्कैनर को शेयर करने के लिए होम पर सर्वर चालू करें (या सेटिंग्स में OTG ऑटो-स्टार्ट सक्षम करें)।",
                        ),
                    )
                    GuideStep(
                        "5",
                        tr(
                            "Android System Print Service: Enable \"BrotherBridge USB OTG\" in Android Settings → Printing to print directly from File Manager, Google Drive, Chrome, or Photos.",
                            "एंड्रॉइड सिस्टम प्रिंट सेवा: फ़ाइल मैनेजर, गूगल ड्राइव, क्रोम या फ़ोटो से सीधे प्रिंट करने के लिए एंड्रॉइड सेटिंग्स → प्रिंटिंग में \"BrotherBridge USB OTG\" चालू करें।",
                        ),
                    )
                }
            }

            // Guide 2 (Dropdown Accordion Card)
            item {
                ExpandableInfoGuideCard(
                    icon = Icons.Outlined.Wifi,
                    accent = LaserAmber,
                    title = tr("Guide 2: Wireless Wi-Fi / Hotspot Client", "गाइड 2: वायरलेस वाई-फ़ाई / हॉटस्पॉट क्लाइंट"),
                    testTag = "help_card_guide_wifi",
                ) {
                    GuideStep(
                        "1",
                        tr(
                            "Connect this device to the same Wi-Fi router or mobile hotspot as your BrotherBridge Remote Server or network printer.",
                            "इस डिवाइस को उसी वाई-फ़ाई राउटर या मोबाइल हॉटस्पॉट से कनेक्ट करें जिस पर आपका ब्रदरब्रिज सर्वर या नेटवर्क प्रिंटर है।",
                        ),
                    )
                    GuideStep(
                        "2",
                        tr(
                            "When no USB cable is plugged in, tap \"Scan for Printers\" on the Home screen to search for nearby servers.",
                            "जब कोई USB केबल नहीं लगा हो, तो नज़दीकी सर्वर खोजने के लिए होम स्क्रीन पर \"प्रिंटर स्कैन करें\" दबाएँ।",
                        ),
                    )
                    GuideStep(
                        "3",
                        tr(
                            "If automatic discovery is blocked by a hotspot or router, open the top-right 3-dot menu and choose \"Add IP Manually!\" (default port 8631).",
                            "यदि हॉटस्पॉट या राउटर खोज को रोकता है, तो ऊपर-दाएँ 3-डॉट मेनू से \"मैन्युअल IP जोड़ें!\" चुनें (डिफ़ॉल्ट पोर्ट 8631)।",
                        ),
                    )
                    GuideStep(
                        "4",
                        tr(
                            "Once connected wirelessly, print settings and scan parameters (up to 2400 DPI) are sent directly to the remote server.",
                            "वायरलेस कनेक्ट होने के बाद प्रिंट सेटिंग्स और स्कैन विकल्प (2400 DPI तक) सीधे रिमोट सर्वर पर भेजे जाते हैं।",
                        ),
                    )
                }
            }

            // Guide 3 (Dropdown Accordion Card)
            item {
                ExpandableInfoGuideCard(
                    icon = Icons.Outlined.Router,
                    accent = SignalEmerald,
                    title = tr("Guide 3: Scanning, Cropping & Collages", "गाइड 3: स्कैनिंग, क्रॉपिंग और कोलाज"),
                    testTag = "help_card_guide_scan",
                ) {
                    GuideStep(
                        "1",
                        tr(
                            "Tap Scan on Home to start a session, choose DPI (up to 2400 DPI), and tap Scan Page.",
                            "नया सत्र शुरू करने के लिए होम पर स्कैन दबाएँ, DPI चुनें (2400 DPI तक) और पेज स्कैन करें दबाएँ।",
                        ),
                    )
                    GuideStep(
                        "2",
                        tr(
                            "Wireless scans run safely in the background — you can switch apps and recover uncollected scans automatically upon reconnecting.",
                            "वायरलेस स्कैन सुरक्षित रूप से बैकग्राउंड में चलते हैं — आप ऐप बदल सकते हैं और दोबारा कनेक्ट होने पर छूटे हुए स्कैन स्वतः प्राप्त कर सकते हैं।",
                        ),
                    )
                    GuideStep(
                        "3",
                        tr(
                            "Use the Crop tool on any scanned page for free-style 4-corner perspective cropping or one-tap Auto Adjust for tilted pages.",
                            "किसी भी स्कैन किए गए पेज पर 4-कोने वाले फ्री-स्टाइल क्रॉप या तिरछे पेजों को सीधा करने के लिए ऑटो एडजस्ट का उपयोग करें।",
                        ),
                    )
                    GuideStep(
                        "4",
                        tr(
                            "Tap any saved scan on Home (even when no printer is connected) to edit, rotate, crop, or export as PDF/images.",
                            "संपादित, रोटेट, क्रॉप या PDF/फ़ोटो निर्यात करने के लिए होम पर किसी भी सेव किए गए स्कैन पर टैप करें (भले ही कोई प्रिंटर कनेक्ट न हो)।",
                        ),
                    )
                }
            }

            // Version & Author Credit Footer (Localized)
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .testTag("about_author_quote_footer"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "v1.9.0",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.testTag("about_app_version_text"),
                    )
                    Text(
                        text = tr("❤️ utkarsh 🔥", "❤️ उत्कर्ष 🔥"),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandableInfoGuideCard(
    icon: ImageVector,
    accent: Color,
    title: String,
    testTag: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "help_card_chevron",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (expanded) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag(testTag),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.14f),
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
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) {
                        tr("Collapse section", "अनुभाग बंद करें")
                    } else {
                        tr("Expand section", "अनुभाग खोलें")
                    },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(chevronRotation),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    content()
                }
            }
        }
    }
}

@Composable
private fun GuideBullet(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideStep(step: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
