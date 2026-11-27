package com.example

import com.example.model.AppLanguage
import com.example.model.IdCardMergeLayout
import com.example.model.LogCategory
import com.example.model.PrintJobOptions
import com.example.model.PrintJobRecord
import com.example.model.PrintResolution
import com.example.model.PrintServerSettings
import com.example.model.RecentScansViewMode
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import com.example.model.UsbPrinterInfo
import com.example.printer.ipp.IppPrintServer
import com.example.printer.ipp.IppServerDelegate
import com.example.scanner.CropPoint
import com.example.scanner.NormalizedQuad
import com.example.scanner.ScanDocumentCropper
import com.example.scanner.ScanIdCardMerger
import com.example.scanner.SimpleScanHttpResponse
import com.example.scanner.SimpleScanJobStatus
import com.example.scanner.SimpleScanProtocol
import com.example.ui.i18n.tr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class SimpleScanAndCropTest {

    @Test
    fun settingsDefaults_highDpiSupport_andHindiLocalization() {
        val defaults = PrintServerSettings()
        assertFalse(defaults.autoStartServerOnOtg)
        assertEquals(PrintResolution.DPI_600, defaults.defaultPrintResolution)
        assertEquals(600, defaults.defaultScanDpi)
        assertEquals(AppLanguage.ENGLISH, defaults.appLanguage)
        assertEquals(RecentScansViewMode.GRID, defaults.recentScansViewMode)
        assertEquals(RecentScansViewMode.GRID, defaults.scanPagesViewMode)
        assertEquals(SimpleScanJobStatus.QUEUED, SimpleScanJobStatus.fromString("QUEUED"))

        assertEquals(600, ScanParameters.DEFAULT_DPI)
        assertEquals(listOf(150, 300, 600, 1200, 2400), ScanParameters.SUPPORTED_DPI_OPTIONS)
        assertEquals(150, ScanParameters.normalizeDpi(150))
        assertEquals(300, ScanParameters.normalizeDpi(300))
        assertEquals(600, ScanParameters.normalizeDpi(600))
        assertEquals(1200, ScanParameters.normalizeDpi(1200))
        assertEquals(2400, ScanParameters.normalizeDpi(2400))
        assertEquals(600, ScanParameters.normalizeDpi(550))
        assertEquals(2400, ScanParameters.normalizeDpi(4800))

        assertEquals(AppLanguage.HINDI, AppLanguage.fromCode("hi"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("unknown"))
        assertEquals("सेव", tr(AppLanguage.HINDI, "Save", "सेव"))
        assertEquals("Save", tr(AppLanguage.ENGLISH, "Save", "सेव"))
    }

    @Test
    fun multiLanguageSupport_allLocalesLabelsAndTranslations() {
        val expectedCodes = listOf(
            "en-US", "en-CA", "en-IN", "en-AU", "en-GB",
            "hi", "ja", "de", "fr", "zh-CN", "es",
            "id", "vi", "th", "sv", "da", "no", "fi",
        )
        assertEquals(expectedCodes, AppLanguage.entries.map { it.code })

        // Verify fromCode round-trips and aliases
        AppLanguage.entries.forEach { lang ->
            assertEquals(lang, AppLanguage.fromCode(lang.code))
            assertEquals(lang, AppLanguage.fromCode(lang.name))
        }
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromCode("en"))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromCode("in"))
        assertEquals(AppLanguage.NORWEGIAN, AppLanguage.fromCode("nb"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromCode("zh"))

        // English locales MUST display "Language"; non-English MUST display "Language (<native>)"
        val englishLocales = listOf(
            AppLanguage.ENGLISH,
            AppLanguage.ENGLISH_CA,
            AppLanguage.ENGLISH_IN,
            AppLanguage.ENGLISH_AU,
            AppLanguage.ENGLISH_GB,
        )
        englishLocales.forEach { lang ->
            assertTrue(lang.isEnglish)
            assertEquals("Language", lang.languageSettingLabel)
            assertEquals("BrotherBridge", tr(lang, "BrotherBridge", "ब्रदरब्रिज"))
            assertEquals("❤️ utkarsh 🔥", tr(lang, "❤️ utkarsh 🔥", "❤️ उत्कर्ष 🔥"))
        }

        // Existing Hindi strings remain untouched
        assertEquals("Language (भाषा)", AppLanguage.HINDI.languageSettingLabel)
        assertEquals("ब्रदरब्रिज", tr(AppLanguage.HINDI, "BrotherBridge", "ब्रदरब्रिज"))
        assertEquals("❤️ उत्कर्ष 🔥", tr(AppLanguage.HINDI, "❤️ utkarsh 🔥", "❤️ उत्कर्ष 🔥"))

        // Verify all 12 new non-English languages translate UI labels, app name, author name, and templates
        val newLanguages = AppLanguage.entries.filter { !it.isEnglish && it != AppLanguage.HINDI }
        assertEquals(12, newLanguages.size)
        newLanguages.forEach { lang ->
            assertTrue(
                "${lang.code} setting label should follow 'Language (<native>)'",
                lang.languageSettingLabel.startsWith("Language (") && lang.languageSettingLabel.endsWith(")"),
            )
            val appName = tr(lang, "BrotherBridge", "ब्रदरब्रिज")
            assertTrue("${lang.code} should translate app name", appName != "BrotherBridge" && appName.isNotBlank())

            val authorName = tr(lang, "❤️ utkarsh 🔥", "❤️ उत्कर्ष 🔥")
            assertTrue("${lang.code} should localize author name", authorName != "❤️ utkarsh 🔥" && authorName.contains("❤️"))

            val settingsLabel = tr(lang, "Settings", "सेटिंग्स")
            assertTrue("${lang.code} should translate Settings", settingsLabel != "Settings" && settingsLabel.isNotBlank())

            val idScanLabel = tr(lang, "2-in-1 ID Scanning", "2-इन-1 आईडी स्कैनिंग")
            assertTrue("${lang.code} should translate 2-in-1 ID Scanning", idScanLabel != "2-in-1 ID Scanning")

            val pageOf = tr(lang, "Page 3 / 5", "पेज 3 / 5")
            assertTrue("${lang.code} should preserve numbers in Page X / Y", pageOf.contains("3") && pageOf.contains("5"))
            if (lang != AppLanguage.FRENCH) {
                assertTrue("${lang.code} should translate dynamic Page X / Y", pageOf != "Page 3 / 5")
            }

            val printCount = tr(lang, "Print (4)", "प्रिंट (4)")
            assertTrue("${lang.code} should translate dynamic Print (N)", printCount != "Print (4)" && printCount.contains("4"))

            val selectedCount = tr(lang, "2 selected", "2 चुने गए")
            assertTrue("${lang.code} should translate dynamic N selected", selectedCount != "2 selected" && selectedCount.contains("2"))
        }
    }

    @Test
    fun scanDocumentCropper_detectFromPixels_detectsRotatedDocumentQuad() {
        val w = 240
        val h = 300
        val pixels = IntArray(w * h)
        val darkBg = (0xFF shl 24) or (24 shl 16) or (28 shl 8) or 34
        val brightPage = (0xFF shl 24) or (248 shl 16) or (248 shl 8) or 246
        val cx = w / 2.0
        val cy = h / 2.0
        val rad = Math.toRadians(-22.0)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val halfW = 58.0
        val halfH = 88.0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                val rx = dx * cosA - dy * sinA
                val ry = dx * sinA + dy * cosA
                pixels[y * w + x] = if (abs(rx) <= halfW && abs(ry) <= halfH) brightPage else darkBg
            }
        }

        val detected = ScanDocumentCropper.detectFromPixels(pixels, w, h)
        assertTrue("Auto-detect should find the rotated document quad", detected.wasAutoDetected)
        assertTrue(
            "Detected angle (${detected.detectedAngleDeg}°) should reflect rotation (> 5°)",
            abs(detected.detectedAngleDeg) > 5f,
        )
        assertTrue("Detected quad should be convex", detected.quad.isConvex())

        val manualQuad = NormalizedQuad(
            topLeft = CropPoint(0.15f, 0.20f),
            topRight = CropPoint(0.82f, 0.14f),
            bottomRight = CropPoint(0.88f, 0.85f),
            bottomLeft = CropPoint(0.10f, 0.78f),
        )
        assertTrue(manualQuad.isConvex())
    }

    @Test
    fun simpleScanProtocol_httpRoutingAndQuerySanitization() = runBlocking {
        val tempRoot = Files.createTempDirectory("simple_scan_http").toFile()
        val spoolDir = File(tempRoot, "spool").apply { mkdirs() }
        val capturedPaths = mutableListOf<String>()
        val clientEvents = mutableListOf<String>()

        val delegate = object : IppServerDelegate {
            override fun currentSettings() = PrintServerSettings(ippPort = 0)
            override fun currentUsbInfo() = UsbPrinterInfo()
            override fun currentIpAddress() = "127.0.0.1"
            override fun currentJobs(): List<PrintJobRecord> = emptyList()
            override fun isScannerAvailable() = true
            override suspend fun handleSimpleScanRequest(
                method: String,
                path: String,
                body: ByteArray,
                remoteIp: String,
            ): SimpleScanHttpResponse {
                capturedPaths.add("$method $path")
                return SimpleScanHttpResponse.Bytes(
                    statusCode = 200,
                    reason = "OK",
                    body = """{"available":true,"model":"Brother DCP-L2520D","maxDpi":2400}""".toByteArray(Charsets.UTF_8),
                )
            }

            override fun onClientActivity(
                remoteIp: String,
                headers: Map<String, String>,
                activityLabel: String,
                explicitClientName: String?,
            ) {
                val name = headers["x-client-name"] ?: explicitClientName ?: remoteIp
                clientEvents.add("ACTIVE:$name:$activityLabel")
            }

            override fun onClientDisconnected(
                remoteIp: String,
                headers: Map<String, String>,
            ) {
                val name = headers["x-client-name"] ?: remoteIp
                clientEvents.add("DISCONNECTED:$name")
            }

            override fun createPendingIppJob(
                title: String,
                clientName: String,
                options: PrintJobOptions,
            ): Result<PrintJobRecord> = Result.failure(UnsupportedOperationException())

            override fun attachDocumentAndEnqueue(
                existingJobId: Int?,
                title: String,
                clientName: String,
                declaredMime: String?,
                file: File,
                options: PrintJobOptions,
            ): Result<PrintJobRecord> = Result.failure(UnsupportedOperationException())

            override fun cancelJob(jobId: Int) = false
            override fun onLog(category: LogCategory, message: String, isError: Boolean) = Unit
            override fun onRequestMetricsChanged(activeCount: Int, totalCount: Int) = Unit
        }

        val server = IppPrintServer(spoolDir, delegate)
        try {
            val port = server.start(0).getOrThrow()

            val capConn = URL("http://127.0.0.1:$port/api/scan/capabilities")
                .openConnection() as HttpURLConnection
            try {
                assertEquals(200, capConn.responseCode)
                val capBody = capConn.inputStream.readBytes().toString(Charsets.UTF_8)
                assertTrue(capBody.contains("\"maxDpi\":2400"))
            } finally {
                capConn.disconnect()
            }

            val pendConn = URL("http://127.0.0.1:$port/api/scan/pending?deviceId=dev_alpha_123456")
                .openConnection() as HttpURLConnection
            try {
                assertEquals(200, pendConn.responseCode)
            } finally {
                pendConn.disconnect()
            }

            val pingConn = URL("http://127.0.0.1:$port/api/client/ping")
                .openConnection() as HttpURLConnection
            try {
                pingConn.setRequestProperty("X-Client-Id", "client-1")
                pingConn.setRequestProperty("X-Client-Name", "Rahul's Pixel")
                pingConn.setRequestProperty("X-Client-Model", "Google Pixel 9")
                assertEquals(200, pingConn.responseCode)
            } finally {
                pingConn.disconnect()
            }

            val disconnectConn = URL("http://127.0.0.1:$port/api/client/disconnect")
                .openConnection() as HttpURLConnection
            try {
                disconnectConn.requestMethod = "POST"
                disconnectConn.setRequestProperty("X-Client-Id", "client-1")
                disconnectConn.setRequestProperty("X-Client-Name", "Rahul's Pixel")
                assertEquals(200, disconnectConn.responseCode)
            } finally {
                disconnectConn.disconnect()
            }

            assertTrue(capturedPaths.contains("GET /api/scan/capabilities"))
            assertTrue(capturedPaths.contains("GET /api/scan/pending?deviceId=dev_alpha_123456"))
            assertTrue(clientEvents.any { it == "ACTIVE:Rahul's Pixel:Connected" })
            assertTrue(clientEvents.any { it == "DISCONNECTED:Rahul's Pixel" })
            assertEquals("dev_alpha_123456", SimpleScanProtocol.sanitizeId("dev_alpha_123456"))
            assertEquals(null, SimpleScanProtocol.sanitizeId("../escape"))
            assertEquals("dev_alpha", SimpleScanProtocol.parseQueryParams("/api/scan/pending?deviceId=dev_alpha")["deviceId"])
        } finally {
            server.stop()
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun scanIdCardMerger_sheetPixelSize_matchesA4A3LetterAndCapsHighDpi() {
        val (a4W, a4H) = ScanIdCardMerger.computeSheetPixelSize(ScanPageSizeOption.A4, dpi = 150)
        assertEquals(210.0 / 297.0, a4W.toDouble() / a4H.toDouble(), 0.01)

        val (a3W, a3H) = ScanIdCardMerger.computeSheetPixelSize(ScanPageSizeOption.A3, dpi = 150)
        assertEquals(297.0 / 420.0, a3W.toDouble() / a3H.toDouble(), 0.01)
        assertTrue("A3 should be larger than A4 at same DPI", a3W > a4W && a3H > a4H)

        val (letterW, letterH) = ScanIdCardMerger.computeSheetPixelSize(ScanPageSizeOption.LETTER, dpi = 150)
        assertEquals(215.9 / 279.4, letterW.toDouble() / letterH.toDouble(), 0.01)

        val (cappedW, cappedH) = ScanIdCardMerger.computeSheetPixelSize(ScanPageSizeOption.A3, dpi = 2400)
        assertEquals(ScanIdCardMerger.DEFAULT_MAX_CANVAS_DIMENSION, maxOf(cappedW, cappedH))
        assertEquals(297.0 / 420.0, cappedW.toDouble() / cappedH.toDouble(), 0.01)

        // Verify PDF export point dimensions for A4, A3, and Letter
        assertEquals(595, ScanPageSizeOption.A4.widthPdfPoints)
        assertEquals(842, ScanPageSizeOption.A4.heightPdfPoints)
        assertEquals(842, ScanPageSizeOption.A3.widthPdfPoints)
        assertEquals(1191, ScanPageSizeOption.A3.heightPdfPoints)
        assertEquals(612, ScanPageSizeOption.LETTER.widthPdfPoints)
        assertEquals(792, ScanPageSizeOption.LETTER.heightPdfPoints)
    }

    @Test
    fun scanIdCardMerger_computeLayout_verticalAndHorizontalSymmetricNonOverlapping() {
        val vertical = ScanIdCardMerger.computeLayout(
            frontWidthPx = 1020,
            frontHeightPx = 640,
            backWidthPx = 1000,
            backHeightPx = 635,
            pageSize = ScanPageSizeOption.A4,
            layout = IdCardMergeLayout.VERTICAL,
            dpi = 300,
        )
        assertTrue("Front card must sit above back card with positive gap", vertical.frontBottom < vertical.backTop)
        assertTrue("Cards must stay within vertical margins", vertical.frontTop > 0f && vertical.backBottom < vertical.canvasHeight)
        assertTrue("Cards must stay within horizontal margins", vertical.frontLeft > 0f && vertical.frontRight < vertical.canvasWidth)
        assertEquals("Similar cards should have unified width", vertical.frontWidth, vertical.backWidth, 1f)
        assertEquals(
            "Front card should be horizontally centered",
            vertical.canvasWidth / 2f,
            (vertical.frontLeft + vertical.frontRight) / 2f,
            1f,
        )

        val horizontal = ScanIdCardMerger.computeLayout(
            frontWidthPx = 1020,
            frontHeightPx = 640,
            backWidthPx = 1010,
            backHeightPx = 645,
            pageSize = ScanPageSizeOption.A3,
            layout = IdCardMergeLayout.HORIZONTAL,
            dpi = 300,
        )
        assertTrue("Front card must sit left of back card with positive gap", horizontal.frontRight < horizontal.backLeft)
        assertTrue("Cards must stay within horizontal margins", horizontal.frontLeft > 0f && horizontal.backRight < horizontal.canvasWidth)
        assertTrue("Cards must stay within vertical margins", horizontal.frontTop > 0f && horizontal.backBottom < horizontal.canvasHeight)
        assertEquals("Similar cards should have unified width", horizontal.frontWidth, horizontal.backWidth, 1f)
        assertEquals(
            "Front card should be vertically centered",
            horizontal.canvasHeight / 2f,
            (horizontal.frontTop + horizontal.frontBottom) / 2f,
            1f,
        )
    }
}
