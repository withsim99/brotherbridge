package com.example

import android.Manifest
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.PrintJobStatus
import com.example.model.PrinterConnectionMode
import com.example.model.RecentScansSortBy
import com.example.model.RecentScansViewMode
import com.example.scanner.ScanShareHelper
import com.example.service.PrintJobForegroundService
import com.example.service.ScanBackgroundService
import kotlinx.coroutines.launch
import com.example.ui.PrintBridgeViewModel
import com.example.ui.ScanPhase
import com.example.ui.components.BottomPrintProgressBar
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.i18n.localizedLabel
import com.example.ui.i18n.tr
import com.example.ui.screens.AboutHelpScreen
import com.example.ui.screens.AllScansScreen
import com.example.ui.screens.BridgeDashboardScreen
import com.example.ui.screens.DirectPrintScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.PrinterHealthScreen
import com.example.ui.screens.QueueLogsScreen
import com.example.ui.screens.ScanCropEditorScreen
import com.example.ui.screens.ScanPageZoomViewerScreen
import com.example.ui.screens.ScanScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.PrintBridgeTheme
import com.example.ui.theme.ThermalRed

private enum class AppDestination {
    HOME,
    ALL_SCANS,
    PRINT,
    SCAN,
    HEALTH,
    REMOTE_SERVER,
    QUEUE,
    SETTINGS,
    ABOUT_HELP,
}

class MainActivity : ComponentActivity() {

    private val viewModel: PrintBridgeViewModel by viewModels()
    private val pendingSharedUri = mutableStateOf<Uri?>(null)
    private val pendingOpenScan = mutableStateOf(false)
    private val pendingOpenQueue = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)

        setContent {
            val isReady by viewModel.isInitialLoadComplete.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val baseContext = LocalContext.current
            val baseConfig = LocalConfiguration.current
            val localizedConfig = remember(baseConfig, settings.appLanguage) {
                val targetLocale = Locale.forLanguageTag(settings.appLanguage.code)
                Configuration(baseConfig).apply {
                    setLocale(targetLocale)
                    setLocales(LocaleList(targetLocale))
                }
            }
            val localizedContext = remember(baseContext, localizedConfig) {
                val configContext = baseContext.createConfigurationContext(localizedConfig)
                object : ContextWrapper(baseContext) {
                    override fun getResources(): Resources = configContext.resources
                    override fun getAssets(): AssetManager = configContext.assets
                }
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                LocalActivityResultRegistryOwner provides this@MainActivity,
                LocalOnBackPressedDispatcherOwner provides this@MainActivity,
                LocalAppLanguage provides settings.appLanguage,
            ) {
                PrintBridgeTheme {
                    Crossfade(
                        targetState = isReady,
                        animationSpec = tween(durationMillis = 220),
                        label = "launch_icon_crossfade",
                    ) { ready ->
                        if (!ready) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF111A29))
                                    .testTag("launch_icon_splash"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_printbridge_logo),
                                    contentDescription = tr("BrotherBridge", "ब्रदरब्रिज"),
                                    modifier = Modifier.size(112.dp),
                                )
                            }
                        } else {
                            PrintBridgeRoot(
                                viewModel = viewModel,
                                sharedUri = pendingSharedUri.value,
                                onConsumeSharedUri = { pendingSharedUri.value = null },
                                openScanFromNotif = pendingOpenScan.value,
                                onConsumeOpenScan = { pendingOpenScan.value = false },
                                openQueueFromNotif = pendingOpenQueue.value,
                                onConsumeOpenQueue = { pendingOpenQueue.value = false },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(ScanBackgroundService.EXTRA_OPEN_SCAN_SCREEN, false)) {
            pendingOpenScan.value = true
        }
        if (intent.getBooleanExtra(PrintJobForegroundService.EXTRA_OPEN_QUEUE_SCREEN, false)) {
            pendingOpenQueue.value = true
        }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) {
                    pendingSharedUri.value = uri
                }
            }
            android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                viewModel.scanUsbPrinters()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrintBridgeRoot(
    viewModel: PrintBridgeViewModel,
    sharedUri: Uri?,
    onConsumeSharedUri: () -> Unit,
    openScanFromNotif: Boolean,
    onConsumeOpenScan: () -> Unit,
    openQueueFromNotif: Boolean,
    onConsumeOpenQueue: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usbInfo by viewModel.usbInfo.collectAsStateWithLifecycle()
    val activePrinter by viewModel.activePrinter.collectAsStateWithLifecycle()
    val discoveredPrinters by viewModel.discoveredPrinters.collectAsStateWithLifecycle()
    val isDiscoveringNetwork by viewModel.isDiscoveringNetwork.collectAsStateWithLifecycle()
    val isConnectingManual by viewModel.isConnectingManual.collectAsStateWithLifecycle()
    val scannerState by viewModel.scannerState.collectAsStateWithLifecycle()
    val backgroundScanState by viewModel.backgroundScanState.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val connectedClients by viewModel.connectedClients.collectAsStateWithLifecycle()
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val directPrintState by viewModel.directPrintState.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val recentScans by viewModel.recentScans.collectAsStateWithLifecycle()
    val recentScansViewMode by viewModel.recentScansViewMode.collectAsStateWithLifecycle()
    val recentScansSortBy by viewModel.recentScansSortBy.collectAsStateWithLifecycle()
    val printerInfoState by viewModel.printerInfoState.collectAsStateWithLifecycle()
    val dismissedProgressJobId by viewModel.dismissedProgressJobId.collectAsStateWithLifecycle()
    val snackbarMsg by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var topMenuExpanded by remember { mutableStateOf(false) }
    var allScansSortMenuExpanded by remember { mutableStateOf(false) }
    var showAddIpDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var selectedAllScanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedScanPageIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showScanExportSheet by remember { mutableStateOf(false) }
    var showScanSettingsDialog by remember { mutableStateOf(false) }
    var scanHeaderMenuExpanded by remember { mutableStateOf(false) }
    var inspectedScanPageIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var cropScanPageIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Keep All Scans selection clean if list changes or user leaves the All Scans screen
    LaunchedEffect(recentScans, currentDestination) {
        if (currentDestination != AppDestination.ALL_SCANS) {
            selectedAllScanIds = emptySet()
            allScansSortMenuExpanded = false
        } else {
            val validIds = recentScans.map { it.id }.toSet()
            if (selectedAllScanIds.any { it !in validIds }) {
                selectedAllScanIds = selectedAllScanIds.intersect(validIds)
            }
        }
    }

    // Keep Scan Page selection & full-screen overlays clean if pages change, phase leaves EXPORT_READY, or user leaves SCAN
    LaunchedEffect(scanState.pages, scanState.phase, currentDestination) {
        if (currentDestination != AppDestination.SCAN || scanState.phase != ScanPhase.EXPORT_READY) {
            selectedScanPageIds = emptySet()
            inspectedScanPageIndex = null
            cropScanPageIndex = null
            if (currentDestination != AppDestination.SCAN) {
                showScanExportSheet = false
                showScanSettingsDialog = false
                scanHeaderMenuExpanded = false
            }
        } else {
            val validPageIds = scanState.pages.map { it.id }.toSet()
            if (selectedScanPageIds.any { it !in validPageIds }) {
                selectedScanPageIds = selectedScanPageIds.intersect(validPageIds)
            }
            val currentIdx = inspectedScanPageIndex
            if (currentIdx != null) {
                inspectedScanPageIndex = if (scanState.pages.isEmpty()) {
                    null
                } else {
                    currentIdx.coerceIn(0, scanState.pages.lastIndex)
                }
            }
            val currentCropIdx = cropScanPageIndex
            if (currentCropIdx != null && (scanState.pages.isEmpty() || currentCropIdx !in scanState.pages.indices)) {
                cropScanPageIndex = null
            }
        }
    }

    LaunchedEffect(activePrinter.isConnected, activePrinter.canHostRemoteServer, serverState.isRunning) {
        if (!activePrinter.isConnected && currentDestination in listOf(
                AppDestination.PRINT,
                AppDestination.HEALTH,
                AppDestination.REMOTE_SERVER,
                AppDestination.QUEUE,
            )
        ) {
            currentDestination = AppDestination.HOME
        } else if (!activePrinter.canHostRemoteServer && currentDestination == AppDestination.REMOTE_SERVER) {
            currentDestination = AppDestination.HOME
        } else if (!serverState.isRunning && currentDestination == AppDestination.QUEUE) {
            currentDestination = AppDestination.HOME
        }
    }

    val navigateBackToHome: () -> Unit = {
        if (currentDestination == AppDestination.SCAN) {
            selectedScanPageIds = emptySet()
            showScanExportSheet = false
            showScanSettingsDialog = false
            inspectedScanPageIndex = null
            cropScanPageIndex = null
            // Do NOT abort background scans — allow the user to go back freely while scanning!
        }
        if (currentDestination == AppDestination.ALL_SCANS) {
            selectedAllScanIds = emptySet()
        }
        currentDestination = AppDestination.HOME
    }

    LaunchedEffect(openScanFromNotif) {
        if (openScanFromNotif) {
            currentDestination = AppDestination.SCAN
            onConsumeOpenScan()
        }
    }

    LaunchedEffect(openQueueFromNotif) {
        if (openQueueFromNotif) {
            if (serverState.isRunning) {
                currentDestination = AppDestination.QUEUE
            }
            onConsumeOpenQueue()
        }
    }

    BackHandler(enabled = currentDestination != AppDestination.HOME) {
        when {
            currentDestination == AppDestination.ALL_SCANS && selectedAllScanIds.isNotEmpty() -> {
                selectedAllScanIds = emptySet()
            }
            currentDestination == AppDestination.SCAN && selectedScanPageIds.isNotEmpty() -> {
                selectedScanPageIds = emptySet()
            }
            else -> navigateBackToHome()
        }
    }

    LaunchedEffect(sharedUri, activePrinter.isConnected) {
        if (sharedUri != null) {
            if (activePrinter.isConnected) {
                viewModel.selectDocumentUri(sharedUri)
                currentDestination = AppDestination.PRINT
                onConsumeSharedUri()
            } else {
                viewModel.showMessage(
                    tr(
                        settings.appLanguage,
                        "Connect a printer first to print shared files",
                        "साझा की गई फ़ाइलें प्रिंट करने के लिए पहले प्रिंटर कनेक्ट करें",
                    ),
                )
                onConsumeSharedUri()
            }
        }
    }

    LaunchedEffect(snackbarMsg) {
        if (!snackbarMsg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(snackbarMsg!!)
            viewModel.consumeSnackbar()
        }
    }

    var pendingNotifAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        val action = pendingNotifAction
        pendingNotifAction = null
        action?.invoke()
    }

    val runWithNotificationPermission: (() -> Unit) -> Unit = { action ->
        val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPerm) {
            pendingNotifAction = action
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.selectDocumentUri(uri)
            currentDestination = AppDestination.PRINT
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.selectDocumentUri(uri)
            currentDestination = AppDestination.PRINT
        }
    }

    val saveScanPdfAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            val subsetIds = selectedScanPageIds
            viewModel.exportScannedPdfToUri(
                targetUri = uri,
                selectedPageIds = subsetIds,
                onDone = {
                    if (subsetIds.isNotEmpty()) {
                        selectedScanPageIds = emptySet()
                    }
                },
            )
        }
    }

    val activeJobCount = jobs.count {
        it.status == PrintJobStatus.PENDING ||
            it.status == PrintJobStatus.RENDERING ||
            it.status == PrintJobStatus.TRANSMITTING
    }

    val progressBarJob = remember(jobs, dismissedProgressJobId) {
        val activeJob = jobs.firstOrNull {
            it.status == PrintJobStatus.PENDING ||
                it.status == PrintJobStatus.RENDERING ||
                it.status == PrintJobStatus.TRANSMITTING
        }
        activeJob ?: jobs.firstOrNull()?.takeIf { it.id != dismissedProgressJobId }
    }

    val hasActivePrintProcess = activeJobCount > 0 || directPrintState.isSubmitting
    val hasActiveScanProcess = backgroundScanState.isScanning || scanState.isActivelyScanningPage
    val requestDisconnectDevice: () -> Unit = {
        if (hasActivePrintProcess || hasActiveScanProcess) {
            showDisconnectConfirmDialog = true
        } else {
            viewModel.disconnectActivePrinter(cancelRunningProcesses = false)
            currentDestination = AppDestination.HOME
        }
    }

    val startOrStopServer: () -> Unit = {
        if (!serverState.isRunning && activePrinter.mode == PrinterConnectionMode.USB_OTG) {
            runWithNotificationPermission { viewModel.toggleServer() }
        } else {
            viewModel.toggleServer()
        }
    }

    val isAllScansSelectionMode = currentDestination == AppDestination.ALL_SCANS && selectedAllScanIds.isNotEmpty()
    val isScanPageSelectionMode = currentDestination == AppDestination.SCAN &&
        scanState.phase == ScanPhase.EXPORT_READY &&
        selectedScanPageIds.isNotEmpty()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                val topBarContainerColor = if (isAllScansSelectionMode || isScanPageSelectionMode) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.background
                }

                if (currentDestination == AppDestination.SCAN) {
                    TopAppBar(
                        title = {
                            val scanTitle = if (isScanPageSelectionMode) {
                                tr(
                                    "${selectedScanPageIds.size} selected",
                                    "${selectedScanPageIds.size} चयनित",
                                )
                            } else {
                                scanState.sessionTitle.ifBlank { tr("Scan", "स्कैन") }
                            }
                            Text(
                                text = scanTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (isScanPageSelectionMode) {
                                IconButton(
                                    onClick = { selectedScanPageIds = emptySet() },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("scan_page_selection_close_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = tr("Cancel page selection", "पेज चयन रद्द करें"),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = navigateBackToHome,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("top_back_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                        contentDescription = tr("Back to Home", "होम पर वापस जाएं"),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (scanState.phase == ScanPhase.EXPORT_READY) {
                                val (viewIcon, viewDesc) = when (scanState.viewMode) {
                                    RecentScansViewMode.GRID -> Icons.Outlined.GridView to tr(
                                        "Grid view, tap to switch to small tiles",
                                        "ग्रिड दृश्य, छोटे टाइल में बदलने के लिए टैप करें",
                                    )
                                    RecentScansViewMode.SMALL_TILES -> Icons.Outlined.Apps to tr(
                                        "Small tiles view, tap to switch to list",
                                        "छोटे टाइल दृश्य, सूची में बदलने के लिए टैप करें",
                                    )
                                    RecentScansViewMode.LIST -> Icons.Outlined.ViewList to tr(
                                        "List view, tap to switch to grid",
                                        "सूची दृश्य, ग्रिड में बदलने के लिए टैप करें",
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val nextMode = when (scanState.viewMode) {
                                            RecentScansViewMode.GRID -> RecentScansViewMode.SMALL_TILES
                                            RecentScansViewMode.SMALL_TILES -> RecentScansViewMode.LIST
                                            RecentScansViewMode.LIST -> RecentScansViewMode.GRID
                                        }
                                        viewModel.setScanViewMode(nextMode)
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("scan_view_mode_toggle_button"),
                                ) {
                                    Icon(
                                        imageVector = viewIcon,
                                        contentDescription = viewDesc,
                                    )
                                }
                                Box {
                                    IconButton(
                                    onClick = { scanHeaderMenuExpanded = true },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("scan_header_overflow_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = tr("Scan options", "स्कैन विकल्प"),
                                    )
                                }
                                DropdownMenu(
                                    expanded = scanHeaderMenuExpanded,
                                    onDismissRequest = { scanHeaderMenuExpanded = false },
                                ) {
                                    if (isScanPageSelectionMode) {
                                        val allPagesSelected = scanState.pages.isNotEmpty() && selectedScanPageIds.size == scanState.pages.size
                                        if (selectedScanPageIds.size >= 2 && !scanState.isMergingIdCard) {
                                            DropdownMenuItem(
                                                text = { Text(tr("Merge Selected (${selectedScanPageIds.size})", "चयनित मर्ज करें (${selectedScanPageIds.size})")) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.AutoAwesomeMosaic,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                },
                                                onClick = {
                                                    scanHeaderMenuExpanded = false
                                                    val idsToMerge = selectedScanPageIds
                                                    viewModel.mergeSelectedScanPages(idsToMerge) {
                                                        selectedScanPageIds = emptySet()
                                                    }
                                                },
                                                modifier = Modifier.testTag("scan_pages_merge_header_button"),
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (allPagesSelected) {
                                                        tr("Deselect All", "सभी चयन हटाएं")
                                                    } else {
                                                        tr("Select All", "सभी चुनें")
                                                    },
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.SelectAll,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            },
                                            onClick = {
                                                scanHeaderMenuExpanded = false
                                                selectedScanPageIds = if (allPagesSelected) {
                                                    emptySet()
                                                } else {
                                                    scanState.pages.map { it.id }.toSet()
                                                }
                                            },
                                            modifier = Modifier.testTag("scan_pages_select_all_button"),
                                        )
                                        if (activePrinter.isConnected && activePrinter.supportsPrint) {
                                            DropdownMenuItem(
                                                text = { Text(tr("Print Selected", "चयनित प्रिंट करें")) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Print,
                                                        contentDescription = null,
                                                    )
                                                },
                                                onClick = {
                                                    scanHeaderMenuExpanded = false
                                                    val idsToPrint = selectedScanPageIds
                                                    viewModel.printActiveScanCollection(selectedPageIds = idsToPrint) {
                                                        selectedScanPageIds = emptySet()
                                                        currentDestination = AppDestination.PRINT
                                                    }
                                                },
                                                modifier = Modifier.testTag("scan_pages_print_selected_button"),
                                            )
                                        }
                                        val shareMultiTitle = tr("Share Selected Pages (JPG)", "चयनित पेज साझा करें (JPG)")
                                        DropdownMenuItem(
                                            text = { Text(tr("Share as JPG", "JPG के रूप में साझा करें")) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Share,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                scanHeaderMenuExpanded = false
                                                val idsToShare = selectedScanPageIds
                                                val baseTitle = scanState.sessionTitle.ifBlank { "Scan" }
                                                val matching = scanState.pages.mapIndexedNotNull { idx, page ->
                                                    if (page.id in idsToShare) (idx + 1) to page else null
                                                }
                                                if (matching.isNotEmpty()) {
                                                    coroutineScope.launch {
                                                        val jpgFiles = matching.map { (pageNum, page) ->
                                                            ScanShareHelper.prepareJpgFile(
                                                                context = context,
                                                                pageId = page.id,
                                                                sessionTitle = baseTitle,
                                                                pageNumber = pageNum,
                                                                bitmap = page.bitmap,
                                                            )
                                                        }
                                                        ScanShareHelper.shareMultipleJpgs(context, jpgFiles, shareMultiTitle)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.testTag("scan_pages_share_selected_button"),
                                        )
                                        DropdownMenuItem(
                                            text = { Text(tr("Export Selected", "चयनित एक्सपोर्ट करें")) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.SaveAlt,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                scanHeaderMenuExpanded = false
                                                showScanExportSheet = true
                                            },
                                            modifier = Modifier.testTag("scan_pages_export_selected_button"),
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = tr("Delete Selected", "चयनित हटाएं"),
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
                                                scanHeaderMenuExpanded = false
                                                val idsToDelete = selectedScanPageIds
                                                selectedScanPageIds = emptySet()
                                                viewModel.deleteSelectedScanPages(idsToDelete)
                                            },
                                            modifier = Modifier.testTag("scan_pages_delete_selected_button"),
                                        )
                                    } else {
                                        val inWorkspace = scanState.phase == ScanPhase.EXPORT_READY
                                        val hasIdlePages = inWorkspace &&
                                            scanState.pages.isNotEmpty() &&
                                            !scanState.isActivelyScanningPage
                                        if (hasIdlePages) {
                                            DropdownMenuItem(
                                                text = { Text(tr("Select Pages", "पेज चुनें")) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Outlined.SelectAll,
                                                        contentDescription = null,
                                                    )
                                                },
                                                onClick = {
                                                    scanHeaderMenuExpanded = false
                                                    scanState.pages.firstOrNull()?.let { first ->
                                                        selectedScanPageIds = setOf(first.id)
                                                    }
                                                },
                                                modifier = Modifier.testTag("scan_enter_selection_mode_button"),
                                            )
                                            if (scanState.pages.size >= 2 && !scanState.isMergingIdCard) {
                                                DropdownMenuItem(
                                                    text = { Text(tr("Merge Pages", "पेज मर्ज करें")) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Outlined.AutoAwesomeMosaic,
                                                            contentDescription = null,
                                                        )
                                                    },
                                                    onClick = {
                                                        scanHeaderMenuExpanded = false
                                                        selectedScanPageIds = scanState.pages.take(2).map { it.id }.toSet()
                                                    },
                                                    modifier = Modifier.testTag("scan_header_merge_menu_item"),
                                                )
                                            }
                                            DropdownMenuItem(
                                                text = { Text(tr("Export / Save", "सेव / एक्सपोर्ट")) },
                                                leadingIcon = {
                                                    Icon(
                                                        imageVector = Icons.Rounded.SaveAlt,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                },
                                                onClick = {
                                                    scanHeaderMenuExpanded = false
                                                    showScanExportSheet = true
                                                },
                                                modifier = Modifier.testTag("scan_top_export_button"),
                                            )
                                        }
                                        if (hasIdlePages) {
                                            HorizontalDivider()
                                        }
                                        DropdownMenuItem(
                                            text = { Text(tr("Scan Settings", "स्कैन सेटिंग्स")) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Settings,
                                                    contentDescription = null,
                                                )
                                            },
                                            onClick = {
                                                scanHeaderMenuExpanded = false
                                                showScanSettingsDialog = true
                                            },
                                            modifier = Modifier.testTag("scan_settings_button"),
                                        )
                                    }
                                }
                            }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = topBarContainerColor,
                        ),
                    )
                } else {
                    CenterAlignedTopAppBar(
                        title = {
                            val titleText = when {
                                isAllScansSelectionMode -> tr(
                                    "${selectedAllScanIds.size} selected",
                                    "${selectedAllScanIds.size} चयनित",
                                )
                                else -> when (currentDestination) {
                                    AppDestination.HOME -> tr("BrotherBridge", "ब्रदरब्रिज")
                                    AppDestination.ALL_SCANS -> tr("All Scans", "सभी स्कैन")
                                    AppDestination.PRINT -> tr("Print", "प्रिंट")
                                    AppDestination.SCAN -> scanState.sessionTitle.ifBlank { tr("Scan", "स्कैन") }
                                    AppDestination.HEALTH -> tr("Printer Health & Status", "प्रिंटर हेल्थ और स्थिति")
                                    AppDestination.REMOTE_SERVER -> tr("Remote Server", "रिमोट सर्वर")
                                    AppDestination.QUEUE -> tr("Queue", "क्यू")
                                    AppDestination.SETTINGS -> tr("Settings", "सेटिंग्स")
                                    AppDestination.ABOUT_HELP -> tr("Help & About", "मदद और परिचय")
                                }
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        navigationIcon = {
                            when {
                                isAllScansSelectionMode -> {
                            IconButton(
                                onClick = { selectedAllScanIds = emptySet() },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("all_scans_selection_close_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = tr("Cancel selection", "चयन रद्द करें"),
                                )
                            }
                        }
                        currentDestination != AppDestination.HOME -> {
                            IconButton(
                                onClick = navigateBackToHome,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("top_back_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = tr("Back to Home", "होम पर वापस जाएं"),
                                )
                            }
                        }
                    }
                },
                actions = {
                    when {
                        // Home: 3-dot overflow menu (Refresh, Add IP Manually!, Settings, Help)
                        currentDestination == AppDestination.HOME -> {
                            Box {
                                IconButton(
                                    onClick = { topMenuExpanded = true },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("top_overflow_menu_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreVert,
                                        contentDescription = tr("More options", "अधिक विकल्प"),
                                    )
                                }

                                DropdownMenu(
                                    expanded = topMenuExpanded,
                                    onDismissRequest = { topMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(tr("Refresh", "रीफ़्रेश")) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            viewModel.refreshAllConnections()
                                        },
                                        modifier = Modifier.testTag("menu_refresh"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Add IP Manually!", "मैनुअल IP जोड़ें!")) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Lan, contentDescription = null)
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            showAddIpDialog = true
                                        },
                                        modifier = Modifier.testTag("menu_add_ip_manually"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Settings", "सेटिंग्स")) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Settings, contentDescription = null)
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            currentDestination = AppDestination.SETTINGS
                                        },
                                        modifier = Modifier.testTag("menu_settings"),
                                    )
                                    DropdownMenuItem(
                                        text = { Text(tr("Help", "सहायता")) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.HelpOutline, contentDescription = null)
                                        },
                                        onClick = {
                                            topMenuExpanded = false
                                            currentDestination = AppDestination.ABOUT_HELP
                                        },
                                        modifier = Modifier.testTag("menu_help"),
                                    )
                                    if (activePrinter.isConnected) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = tr("Disconnect Device", "डिवाइस डिस्कनेक्ट करें"),
                                                    color = ThermalRed,
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.LinkOff,
                                                    contentDescription = null,
                                                    tint = ThermalRed,
                                                )
                                            },
                                            onClick = {
                                                topMenuExpanded = false
                                                requestDisconnectDevice()
                                            },
                                            modifier = Modifier.testTag("menu_disconnect_device"),
                                        )
                                    }
                                }
                            }
                        }

                        // All Scans — Selection Mode header (Select All + Delete)
                        isAllScansSelectionMode -> {
                            val allSelected = recentScans.isNotEmpty() && selectedAllScanIds.size == recentScans.size
                            IconButton(
                                onClick = {
                                    selectedAllScanIds = if (allSelected) {
                                        emptySet()
                                    } else {
                                        recentScans.map { it.id }.toSet()
                                    }
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("all_scans_select_all_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SelectAll,
                                    contentDescription = if (allSelected) {
                                        tr("Deselect all", "सभी चयन हटाएं")
                                    } else {
                                        tr("Select all", "सभी चुनें")
                                    },
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    val toDelete = selectedAllScanIds
                                    selectedAllScanIds = emptySet()
                                    viewModel.deleteRecentScans(toDelete)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("all_scans_delete_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = tr("Delete selected scans", "चयनित स्कैन हटाएं"),
                                    tint = ThermalRed,
                                )
                            }
                        }

                        // All Scans — Normal Mode header (Sort icon-only + Grid/List toggle)
                        currentDestination == AppDestination.ALL_SCANS -> {
                            val isGrid = recentScansViewMode == RecentScansViewMode.GRID
                            Box {
                                IconButton(
                                    onClick = { allScansSortMenuExpanded = true },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("all_scans_sort_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Sort,
                                        contentDescription = tr("Sort scans", "स्कैन क्रमबद्ध करें"),
                                    )
                                }
                                DropdownMenu(
                                    expanded = allScansSortMenuExpanded,
                                    onDismissRequest = { allScansSortMenuExpanded = false },
                                ) {
                                    RecentScansSortBy.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = option.localizedLabel(),
                                                    fontWeight = if (option == recentScansSortBy) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                            trailingIcon = {
                                                if (option == recentScansSortBy) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                allScansSortMenuExpanded = false
                                                viewModel.setRecentScansSortBy(option)
                                            },
                                        )
                                    }
                                }
                            }

                            IconButton(
                                onClick = {
                                    val next = if (isGrid) RecentScansViewMode.LIST else RecentScansViewMode.GRID
                                    viewModel.setRecentScansViewMode(next)
                                },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("all_scans_view_toggle"),
                            ) {
                                Icon(
                                    imageVector = if (isGrid) Icons.Outlined.ViewList else Icons.Outlined.GridView,
                                    contentDescription = if (isGrid) {
                                        tr("Switch to list view", "सूची दृश्य में बदलें")
                                    } else {
                                        tr("Switch to grid view", "ग्रिड दृश्य में बदलें")
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = topBarContainerColor,
                ),
            )
        }
    },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // Only show printing progress bar (never show scanning progress as a bottom popup)
            if (currentDestination != AppDestination.SCAN) {
                BottomPrintProgressBar(
                    job = progressBarJob,
                    onCancel = viewModel::cancelJob,
                    onDismiss = viewModel::dismissPrintProgressBar,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (currentDestination) {
                AppDestination.HOME -> HomeScreen(
                    activePrinter = activePrinter,
                    diagnostics = printerInfoState.diagnostics,
                    serverState = serverState,
                    discoveredPrinters = discoveredPrinters,
                    isDiscoveringNetwork = isDiscoveringNetwork,
                    isConnectingManual = isConnectingManual,
                    activeJobCount = activeJobCount,
                    backgroundScanState = backgroundScanState,
                    recentScans = recentScans,
                    recentScansViewMode = recentScansViewMode,
                    onToggleRecentScansViewMode = {
                        val next = if (recentScansViewMode == RecentScansViewMode.GRID) {
                            RecentScansViewMode.LIST
                        } else {
                            RecentScansViewMode.GRID
                        }
                        viewModel.setRecentScansViewMode(next)
                    },
                    showAddIpDialog = showAddIpDialog,
                    onDismissAddIpDialog = { showAddIpDialog = false },
                    onPickPdfForPrint = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                    onPickImageForPrint = {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onPrintTestPage = {
                        viewModel.selectTestPageForPrint(
                            onReady = { currentDestination = AppDestination.PRINT },
                        )
                    },
                    onOpenNewScan = {
                        selectedScanPageIds = emptySet()
                        showScanExportSheet = false
                        viewModel.startNewScanSession()
                        currentDestination = AppDestination.SCAN
                    },
                    onOpenActiveScan = {
                        selectedScanPageIds = emptySet()
                        showScanExportSheet = false
                        currentDestination = AppDestination.SCAN
                    },
                    onCancelBackgroundScan = viewModel::cancelActiveScan,
                    onOpenHealthPage = { currentDestination = AppDestination.HEALTH },
                    onOpenRemoteServer = { currentDestination = AppDestination.REMOTE_SERVER },
                    onOpenQueue = { currentDestination = AppDestination.QUEUE },
                    onOpenAllScans = {
                        selectedAllScanIds = emptySet()
                        currentDestination = AppDestination.ALL_SCANS
                    },
                    onOpenRecentScanCollage = { rec ->
                        selectedScanPageIds = emptySet()
                        showScanExportSheet = false
                        viewModel.openRecentScanCollection(rec) {
                            currentDestination = AppDestination.SCAN
                        }
                    },
                    onStartNetworkDiscovery = viewModel::startNetworkDiscovery,
                    onStopNetworkDiscovery = viewModel::stopNetworkDiscovery,
                    onConnectRemotePrinter = viewModel::connectRemotePrinter,
                    onConnectManualRemoteHost = { host, port, onConnected ->
                        viewModel.connectManualRemoteHost(host, port, onConnected)
                    },
                    onDisconnectPrinter = requestDisconnectDevice,
                )

                AppDestination.ALL_SCANS -> AllScansScreen(
                    scans = recentScans,
                    viewMode = recentScansViewMode,
                    sortBy = recentScansSortBy,
                    selectedIds = selectedAllScanIds,
                    onTapScan = { rec ->
                        if (selectedAllScanIds.isNotEmpty()) {
                            selectedAllScanIds = if (rec.id in selectedAllScanIds) {
                                selectedAllScanIds - rec.id
                            } else {
                                selectedAllScanIds + rec.id
                            }
                        } else {
                            selectedScanPageIds = emptySet()
                            showScanExportSheet = false
                            viewModel.openRecentScanCollection(rec) {
                                currentDestination = AppDestination.SCAN
                            }
                        }
                    },
                    onLongPressScan = { rec ->
                        selectedAllScanIds = if (rec.id in selectedAllScanIds) {
                            selectedAllScanIds - rec.id
                        } else {
                            selectedAllScanIds + rec.id
                        }
                    },
                )

                AppDestination.PRINT -> DirectPrintScreen(
                    activePrinter = activePrinter,
                    state = directPrintState,
                    onSelectPaperSize = viewModel::setDirectPaperSize,
                    onSelectNUp = viewModel::setDirectPagesPerSheet,
                    onSelectDuplex = viewModel::setDirectDuplexMode,
                    onSelectResolution = viewModel::setDirectResolution,
                    onSelectOrientation = viewModel::setDirectOrientation,
                    onSelectColorScheme = viewModel::setDirectColorScheme,
                    onToggleTonerSave = viewModel::setDirectTonerSave,
                    onChangeCopies = viewModel::setDirectCopies,
                    onToggleBorders = viewModel::setDirectDrawBorders,
                    onChangeImageMarginMm = viewModel::setDirectImageMarginMm,
                    onSelectImageScaling = viewModel::setDirectImageScaling,
                    onPanImage = viewModel::setDirectImagePan,
                    onResetImagePan = viewModel::resetDirectImagePan,
                    onPrintNow = { runWithNotificationPermission { viewModel.printSelectedDocument() } },
                    onPrintTestPage = { viewModel.selectTestPageForPrint() },
                )

                AppDestination.SCAN -> ScanScreen(
                    activePrinter = activePrinter,
                    state = scanState,
                    selectedPageIds = selectedScanPageIds,
                    showExportSheet = showScanExportSheet,
                    showSettingsDialog = showScanSettingsDialog,
                    onDismissExportSheet = { showScanExportSheet = false },
                    onDismissSettingsDialog = { showScanSettingsDialog = false },
                    onToggleSelectPage = { pageId ->
                        selectedScanPageIds = if (pageId in selectedScanPageIds) {
                            selectedScanPageIds - pageId
                        } else {
                            selectedScanPageIds + pageId
                        }
                    },
                    onMergeSelectedPages = {
                        val idsToMerge = selectedScanPageIds
                        viewModel.mergeSelectedScanPages(idsToMerge) {
                            selectedScanPageIds = emptySet()
                        }
                    },
                    onInspectPage = { idx -> inspectedScanPageIndex = idx },
                    onCropPage = { idx -> cropScanPageIndex = idx },
                    onSelectSource = viewModel::updateScanSource,
                    onSelectColor = viewModel::updateScanColor,
                    onSelectDpi = viewModel::updateScanDpi,
                    onSelectPageSize = viewModel::updateScanPageSize,
                    onChangeBrightness = viewModel::updateScanBrightness,
                    onChangeContrast = viewModel::updateScanContrast,
                    onTriggerScan = { runWithNotificationPermission { viewModel.scanSinglePass() } },
                    onCancelScan = viewModel::cancelActiveScan,
                    onRotatePageAt = viewModel::rotateScanPageAt,
                    onDeletePageAt = viewModel::deleteScanPageAt,
                    onExportPages = { mode ->
                        val subsetIds = selectedScanPageIds
                        viewModel.exportScannedPages(
                            mode = mode,
                            selectedPageIds = subsetIds,
                            onDone = {
                                if (subsetIds.isNotEmpty()) {
                                    selectedScanPageIds = emptySet()
                                }
                            },
                        )
                    },
                    onExportPdfToChosenFolder = {
                        val base = scanState.sessionTitle.ifBlank { "Scan" }
                        saveScanPdfAsLauncher.launch("$base.pdf")
                    },
                    onPrintCollection = {
                        val subsetIds = selectedScanPageIds
                        viewModel.printActiveScanCollection(selectedPageIds = subsetIds) {
                            selectedScanPageIds = emptySet()
                            currentDestination = AppDestination.PRINT
                        }
                    },
                    onBackToHome = navigateBackToHome,
                )

                AppDestination.HEALTH -> PrinterHealthScreen(
                    activePrinter = activePrinter,
                    usbInfo = usbInfo,
                    scannerState = scannerState,
                    state = printerInfoState,
                    onRefresh = { viewModel.refreshPrinterInfo(silent = false) },
                )

                AppDestination.REMOTE_SERVER -> BridgeDashboardScreen(
                    activePrinter = activePrinter,
                    settings = settings,
                    serverState = serverState,
                    scannerState = scannerState,
                    connectedClients = connectedClients,
                    onToggleServer = startOrStopServer,
                    onRefreshNetwork = viewModel::refreshNetwork,
                    onOpenQueue = { currentDestination = AppDestination.QUEUE },
                    onCopiedNotice = viewModel::showMessage,
                )

                AppDestination.QUEUE -> QueueLogsScreen(
                    jobs = jobs,
                    logs = logs,
                    onCancelJob = viewModel::cancelJob,
                    onClearCompletedJobs = viewModel::clearCompletedJobs,
                    onClearLogs = viewModel::clearLogs,
                )

                AppDestination.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onToggleAutoStartServerOnOtg = viewModel::updateAutoStartServerOnOtg,
                    onSelectDefaultPrintResolution = viewModel::updateDefaultPrintResolution,
                    onSelectDefaultScanDpi = viewModel::updateDefaultScanDpi,
                    onSelectAppLanguage = viewModel::updateAppLanguage,
                )

                AppDestination.ABOUT_HELP -> AboutHelpScreen()
            }
        }
    }

        // True full-screen edge-to-edge Crop Editor & Page Viewer (mutually exclusive so opening
        // Crop from the Page Viewer unmounts the pager and renders identically to the 3-dot tile menu)
        if (currentDestination == AppDestination.SCAN) {
            val editingCropIdx = cropScanPageIndex
            val openIdx = inspectedScanPageIndex
            when {
                editingCropIdx != null && editingCropIdx in scanState.pages.indices -> {
                    val editingPage = scanState.pages[editingCropIdx]
                    androidx.compose.runtime.key(editingPage.id, editingCropIdx) {
                        ScanCropEditorScreen(
                            pageNumber = editingCropIdx + 1,
                            bitmap = editingPage.bitmap,
                            originalBitmap = editingPage.originalBitmap,
                            onApplyCropped = { cropped ->
                                viewModel.applyCroppedPageAt(editingCropIdx, cropped)
                                cropScanPageIndex = null
                            },
                            onDismiss = { cropScanPageIndex = null },
                        )
                    }
                }

                openIdx != null && openIdx in scanState.pages.indices -> {
                    ScanPageZoomViewerScreen(
                        pages = scanState.pages,
                        sessionTitle = scanState.sessionTitle.ifBlank { "Scan" },
                        initialIndex = openIdx,
                        onCropPageAt = { idx ->
                            inspectedScanPageIndex = idx
                            cropScanPageIndex = idx
                        },
                        onDeletePageAt = viewModel::deleteScanPageAt,
                        onDismiss = { inspectedScanPageIndex = null },
                    )
                }
            }
        }

        if (showDisconnectConfirmDialog) {
            val deviceName = activePrinter.displayName
            val runningDescription = when {
                hasActivePrintProcess && hasActiveScanProcess -> tr(
                    "A print job and a scan are currently running on $deviceName. Disconnecting now will cancel them and return to device search.",
                    "$deviceName पर एक प्रिंट जॉब और स्कैन अभी चल रहे हैं। अभी डिस्कनेक्ट करने से वे रद्द हो जाएंगे और आप डिवाइस खोज स्क्रीन पर लौट जाएंगे।",
                )
                hasActivePrintProcess -> tr(
                    "A print job is currently in progress on $deviceName. Disconnecting now will cancel it and return to device search.",
                    "$deviceName पर एक प्रिंट जॉब अभी चल रहा है। अभी डिस्कनेक्ट करने से यह रद्द हो जाएगा और आप डिवाइस खोज स्क्रीन पर लौट जाएंगे।",
                )
                else -> tr(
                    "A scan is currently in progress on $deviceName. Disconnecting now will cancel it and return to device search.",
                    "$deviceName पर एक स्कैन अभी चल रहा है। अभी डिस्कनेक्ट करने से यह रद्द हो जाएगा और आप डिवाइस खोज स्क्रीन पर लौट जाएंगे।",
                )
            }
            AlertDialog(
                onDismissRequest = { showDisconnectConfirmDialog = false },
                shape = RoundedCornerShape(22.dp),
                containerColor = MaterialTheme.colorScheme.surface,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = ThermalRed.copy(alpha = 0.14f),
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = ThermalRed,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                        Text(
                            text = tr("Disconnect Device?", "डिवाइस डिस्कनेक्ट करें?"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = runningDescription,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDisconnectConfirmDialog = false
                            viewModel.disconnectActivePrinter(cancelRunningProcesses = true)
                            currentDestination = AppDestination.HOME
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ThermalRed,
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_disconnect_device_button"),
                    ) {
                        Text(tr("Disconnect", "डिस्कनेक्ट करें"))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDisconnectConfirmDialog = false },
                        modifier = Modifier.testTag("cancel_disconnect_device_button"),
                    ) {
                        Text(tr("Cancel", "रद्द करें"))
                    }
                },
                modifier = Modifier.testTag("disconnect_confirm_dialog"),
            )
        }
    }
}
