package com.example

import com.example.model.DeviceBusyState
import com.example.model.DuplexMode
import com.example.model.LogCategory
import com.example.model.MediaType
import com.example.model.NUpPresentationDirection
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperAvailability
import com.example.model.PaperSize
import com.example.model.PrintJobOptions
import com.example.model.PrintJobRecord
import com.example.model.PrintJobSource
import com.example.model.PrintJobStatus
import com.example.model.PrintOrientation
import com.example.model.PrintResolution
import com.example.model.PrintScaling
import com.example.model.PrintServerSettings
import com.example.model.PrinterLanguage
import com.example.model.SupplyStatus
import com.example.model.UsbConnectionStatus
import com.example.model.UsbPrinterInfo
import com.example.printer.engine.BrotherLaserEncoder
import com.example.printer.ipp.DeviceBusyException
import com.example.printer.ipp.IppCodec
import com.example.printer.ipp.IppConstants
import com.example.printer.ipp.IppPrintServer
import com.example.printer.ipp.IppServerDelegate
import com.example.printer.render.DocumentRenderer
import com.example.printer.render.NUpLayoutEngine
import com.example.printer.render.PwgRasterDecoder
import com.example.printer.render.SupportedDocumentKind
import com.example.printer.usb.PrinterDiagnosticsParser
import com.example.scanner.SimpleScanHttpResponse
import com.example.scanner.SimpleScanJobState
import com.example.scanner.SimpleScanJobStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files

class PrintBridgeProtocolTest {

    @Test
    fun nUpAndDuplexSheetMath_allSupportedGrids() {
        assertEquals(16, NUpLayoutEngine.calculateImposedSides(16, PagesPerSheet.ONE))
        assertEquals(8, NUpLayoutEngine.calculateImposedSides(16, PagesPerSheet.TWO))
        assertEquals(4, NUpLayoutEngine.calculateImposedSides(16, PagesPerSheet.FOUR))
        assertEquals(2, NUpLayoutEngine.calculateImposedSides(16, PagesPerSheet.EIGHT))
        assertEquals(1, NUpLayoutEngine.calculateImposedSides(16, PagesPerSheet.SIXTEEN))

        assertEquals(8, NUpLayoutEngine.calculatePhysicalSheets(16, DuplexMode.LONG_EDGE))
        assertEquals(1, NUpLayoutEngine.calculatePhysicalSheets(2, DuplexMode.SHORT_EDGE))
        assertEquals(2, NUpLayoutEngine.calculatePhysicalSheets(3, DuplexMode.LONG_EDGE))
        assertEquals(3, NUpLayoutEngine.calculatePhysicalSheets(3, DuplexMode.SIMPLEX))
    }

    @Test
    fun documentFormatDetection_allowsPdfAndImagesOnly() {
        assertEquals(SupportedDocumentKind.PWG_RASTER, DocumentRenderer.detectDocumentKind("image/pwg-raster", null))
        assertEquals(SupportedDocumentKind.PDF, DocumentRenderer.detectDocumentKind("application/pdf", null))
        assertEquals(SupportedDocumentKind.JPEG, DocumentRenderer.detectDocumentKind("image/jpeg", null))
        assertEquals(SupportedDocumentKind.PNG, DocumentRenderer.detectDocumentKind("image/png", null))
        assertEquals(SupportedDocumentKind.WEBP, DocumentRenderer.detectDocumentKind("image/webp", null))

        val pwgMagic = byteArrayOf(0x52, 0x61, 0x53, 0x32, 0x00, 0x00)
        assertEquals(SupportedDocumentKind.PWG_RASTER, DocumentRenderer.detectDocumentKind("application/octet-stream", pwgMagic))

        val pdfMagic = "%PDF-1.7\n".toByteArray()
        assertEquals(SupportedDocumentKind.PDF, DocumentRenderer.detectDocumentKind("application/octet-stream", pdfMagic))

        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
        assertEquals(SupportedDocumentKind.PNG, DocumentRenderer.detectDocumentKind(null, pngMagic))

        assertNull(DocumentRenderer.detectDocumentKind("application/postscript", "%!PS-Adobe".toByteArray()))
        assertNull(DocumentRenderer.detectDocumentKind("text/plain", "Hello world".toByteArray()))
    }

    @Test
    fun ippCodec_decodesPrintJobWithAttributesAndPdfPayload() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeShort(IppConstants.OP_PRINT_JOB)
        dos.writeInt(42)

        dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
        writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeAttr(dos, IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", "Quarterly_Report.pdf")
        writeAttr(dos, IppConstants.TAG_MIME_MEDIA_TYPE, "document-format", "application/pdf")

        dos.writeByte(IppConstants.TAG_JOB_ATTRIBUTES)
        writeIntAttr(dos, IppConstants.TAG_INTEGER, "number-up", 4)
        writeAttr(dos, IppConstants.TAG_KEYWORD, "sides", "two-sided-long-edge")
        dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)

        val fakePdf = "%PDF-1.4\n%%EOF".toByteArray()
        dos.write(fakePdf)
        dos.flush()

        val tempDir = Files.createTempDirectory("ipp_test").toFile()
        try {
            val req = IppCodec.decodeRequest(ByteArrayInputStream(baos.toByteArray()), tempDir)
            assertEquals(2, req.majorVersion)
            assertEquals(0, req.minorVersion)
            assertEquals(IppConstants.OP_PRINT_JOB, req.operationId)
            assertEquals(42, req.requestId)
            assertEquals("Quarterly_Report.pdf", req.getStringAttr("job-name"))
            assertEquals("application/pdf", req.getStringAttr("document-format"))
            assertEquals(4, req.getIntAttr("number-up"))
            assertEquals("two-sided-long-edge", req.getStringAttr("sides"))
            assertNotNull(req.documentFile)
            assertEquals(fakePdf.size.toLong(), req.documentSizeBytes)
            req.documentFile?.delete()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ippCodec_enforces1GbUpperLimitAndEncodesPrinterLimitAttributes() {
        assertEquals(1L * 1024L * 1024L * 1024L, IppCodec.MAX_DOCUMENT_BYTES)
        assertEquals(1_048_576, IppCodec.MAX_DOCUMENT_K_OCTETS)
        assertEquals(10_000, IppCodec.MAX_JOB_IMPRESSIONS)

        // Verify oversized payload throws DocumentTooLargeException and deletes partial spool file
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeShort(IppConstants.OP_PRINT_JOB)
        dos.writeInt(77)
        dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
        writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
        dos.write(ByteArray(2048) { 0x25 })
        dos.flush()

        val tempDir = Files.createTempDirectory("ipp_limit_test").toFile()
        try {
            var threwTooLarge = false
            try {
                IppCodec.decodeRequest(
                    input = ByteArrayInputStream(baos.toByteArray()),
                    spoolDir = tempDir,
                    maxDocumentBytes = 1024L,
                )
            } catch (_: com.example.printer.ipp.DocumentTooLargeException) {
                threwTooLarge = true
            }
            assertTrue("Expected DocumentTooLargeException", threwTooLarge)
            assertEquals("Partial temp file must be deleted", 0, tempDir.listFiles()?.size ?: 0)
        } finally {
            tempDir.deleteRecursively()
        }

        // Verify ResponseBuilder encodes job-k-octets-supported, job-impressions-supported, and printer-resolution-supported
        val responseBytes = IppCodec.ResponseBuilder(2, 0, IppConstants.STATUS_SUCCESSFUL_OK, 99)
            .beginGroup(IppConstants.TAG_PRINTER_ATTRIBUTES)
            .addRangeOfInt("job-k-octets-supported", 1, IppCodec.MAX_DOCUMENT_K_OCTETS)
            .addRangeOfInt("job-impressions-supported", 1, IppCodec.MAX_JOB_IMPRESSIONS)
            .addResolution("printer-resolution-default", 600, 600)
            .addResolutionList("printer-resolution-supported", listOf(300, 600))
            .build()
        val wireText = String(responseBytes, Charsets.ISO_8859_1)
        assertTrue(wireText.contains("job-k-octets-supported"))
        assertTrue(wireText.contains("job-impressions-supported"))
        assertTrue(wireText.contains("printer-resolution-default"))
        assertTrue(wireText.contains("printer-resolution-supported"))
    }

    @Test
    fun brlaserPort_sessionHeaderDuplexBindingAndFooterMatchSpec() {
        val out = ByteArrayOutputStream()
        val opts = PrintJobOptions(
            pagesPerSheet = PagesPerSheet.FOUR,
            duplexMode = DuplexMode.LONG_EDGE,
            paperSize = PaperSize.A4,
            resolution = PrintResolution.DPI_300,
            printerLanguage = PrinterLanguage.BRLASER_DCP_L2520D,
            copies = 2,
            tonerSave = true,
        )
        BrotherLaserEncoder.openSession(out, "Test\"Doc\\1", opts).use { session ->
            session.writeBlankBackPageIfNeeded(totalRenderedSides = 1)
        }

        val bytes = out.toByteArray()
        // First 128 bytes must be NULs from job::begin_job()
        for (i in 0 until 128) {
            assertEquals("Expected NUL at byte $i", 0.toByte(), bytes[i])
        }
        val streamText = String(bytes, 128, bytes.size - 128, Charsets.ISO_8859_1)
        assertTrue(streamText.startsWith("\u001B%-12345X@PJL\n@PJL JOB NAME=\"Test Doc 1\"\n"))
        assertTrue(streamText.contains("@PJL SET RAS1200MODE = FALSE\n"))
        assertTrue(streamText.contains("@PJL SET RESOLUTION = 300\n"))
        assertTrue(streamText.contains("@PJL SET ECONOMODE = ON\n"))
        assertTrue(streamText.contains("@PJL SET SOURCETRAY = AUTO\n"))
        assertTrue(streamText.contains("@PJL SET MEDIATYPE = REGULAR\n"))
        assertTrue(streamText.contains("@PJL SET DENSITY=0\n"))
        assertTrue(streamText.contains("@PJL SET DEVBIASADJUST=0\n"))
        assertTrue(streamText.contains("@PJL SET PAPER = A4\n"))
        assertTrue(streamText.contains("@PJL SET PAGEPROTECT = AUTO\n"))
        assertTrue(streamText.contains("@PJL SET ORIENTATION = PORTRAIT\n"))
        assertTrue(streamText.contains("@PJL ENTER LANGUAGE = PCL\n"))
        // Long-edge duplex emits &l1S
        assertTrue(streamText.contains("\u001BE\u001B&l2X\u001B&u300D\u001B*t300R\u001B&l1S"))
        // Blank duplex back page encoded as \033*b1030m + 3w\0\1\xFF + 1030M\f
        assertTrue(streamText.contains("\u001B*b1030m3w\u0000\u0001\u00FF1030M\u000C"))
        assertTrue(streamText.endsWith("\u001B%-12345X@PJL\n@PJL EOJ NAME=\"Test Doc 1\"\n\u001B%-12345X\n"))
    }

    @Test
    fun brlaserPort_duplexBindingCodesAndCrossThreadSessionSafety() {
        // Verify Simplex (&l0S), Long-Edge (&l1S), and Short-Edge (&l2S)
        val cases = listOf(
            DuplexMode.SIMPLEX to "\u001B&l0S",
            DuplexMode.LONG_EDGE to "\u001B&l1S",
            DuplexMode.SHORT_EDGE to "\u001B&l2S",
        )
        for ((mode, expectedPcl) in cases) {
            val out = ByteArrayOutputStream()
            val opts = PrintJobOptions(duplexMode = mode)
            val params = BrotherLaserEncoder.buildPageParams(opts)
            assertEquals(mode != DuplexMode.SIMPLEX, params.duplex)
            assertEquals(mode == DuplexMode.SHORT_EDGE, params.tumble)

            // Open on current thread, write blank back on a worker thread, close on current thread
            val session = BrotherLaserEncoder.openSession(out, "Mode_${mode.name}", opts)
            val worker = Thread {
                session.writeBlankBackPageIfNeeded(totalRenderedSides = 1)
            }
            worker.start()
            worker.join()
            session.close()

            val payload = String(out.toByteArray(), 128, out.size() - 128, Charsets.ISO_8859_1)
            assertTrue("Expected $expectedPcl for $mode", payload.contains(expectedPcl))
            // Must contain exactly ONE "@PJL JOB NAME=" even across threads (no duplicate beginJob)
            assertEquals(1, payload.split("@PJL JOB NAME=").size - 1)
            assertEquals(1, payload.split("@PJL EOJ NAME=").size - 1)
        }
    }

    @Test
    fun brlaserLineEncoder_exactDeltaRepeatAndZeroLine() {
        // 1. All-zero line encodes to single byte 0xFF
        assertArrayEquals(
            byteArrayOf(0xFF.toByte()),
            BrotherLaserEncoder.BrlaserLineEncoder.encodeLine(ByteArray(8)),
        )

        // 2. Identical non-zero line vs reference encodes to [0x00] (0 edits)
        val ref = byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x02)
        assertArrayEquals(
            byteArrayOf(0x00),
            BrotherLaserEncoder.BrlaserLineEncoder.encodeLine(ref.copyOf(), ref),
        )

        // 3. Repeat run of 5 bytes of 0xAA at offset 2:
        // num_edits=1, cmd = 128 | (offset=2 << 5) | (count=5-2=3) = 0xC3, value = 0xAA
        val lineWithRepeat = byteArrayOf(0x00, 0x00, 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0x00)
        val zeroRef = ByteArray(8)
        assertArrayEquals(
            byteArrayOf(0x01, 0xC3.toByte(), 0xAA.toByte()),
            BrotherLaserEncoder.BrlaserLineEncoder.encodeLine(lineWithRepeat, zeroRef),
        )

        // 4. First-line encodeLine(line) encodes as 1 substitute command from offset 0
        val sample = byteArrayOf(0x11, 0x22, 0x33)
        // num_edits=1, cmd=(0<<3)|(3-1=2)=0x02, payload=0x11,0x22,0x33
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x11, 0x22, 0x33),
            BrotherLaserEncoder.BrlaserLineEncoder.encodeLine(sample),
        )

        // 5. Block flush header: "%dw\0<lineCount>"
        val block = BrotherLaserEncoder.BrlaserBlock()
        block.addLine(byteArrayOf(0xFF.toByte()))
        block.addLine(byteArrayOf(0x00))
        val blockOut = ByteArrayOutputStream()
        block.flush(blockOut)
        assertArrayEquals(
            byteArrayOf('4'.code.toByte(), 'w'.code.toByte(), 0x00, 0x02, 0xFF.toByte(), 0x00),
            blockOut.toByteArray(),
        )
    }

    @Test
    fun simpleScanProtocol_tracksAttemptAndHeartbeatAndRoutesViaHttpOnIppServer() {
        val tempDir = Files.createTempDirectory("http_scan_test").toFile()
        val sampleState = SimpleScanJobState(
            jobId = "scan_test_001",
            deviceId = "dev_abc123",
            clientName = "Pixel Client",
            status = SimpleScanJobStatus.RUNNING,
            progress = 42,
            statusMessage = "Warming up scanner carriage (6s)...",
            attempt = 2,
            maxAttempts = 3,
            createdAtMs = 1_700_000_000_000L,
            updatedAtMs = 1_700_000_006_000L,
            heartbeatAtMs = 1_700_000_006_500L,
        )
        assertEquals("scan_test_001", sampleState.jobId)
        assertEquals(2, sampleState.attempt)
        assertEquals(3, sampleState.maxAttempts)
        assertEquals(1_700_000_006_500L, sampleState.heartbeatAtMs)
        assertEquals(42, sampleState.progress)
        assertEquals("Warming up scanner carriage (6s)...", sampleState.statusMessage)

        val capsJsonPayload = """{"available":true,"busy":false,"supportsFlatbed":true,"supportsAdf":true,"model":"Brother DCP-L2520D"}"""
        val progressJsonPayload = """{"jobId":"scan_test_001","status":"RUNNING","progress":42,"attempt":2,"maxAttempts":3,"heartbeatAtMs":1700000006500,"statusMessage":"Warming up scanner carriage (6s)..."}"""

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
            ): SimpleScanHttpResponse = when {
                method == "GET" && path == "/api/scan/capabilities" -> SimpleScanHttpResponse.Bytes(
                    statusCode = 200,
                    reason = "OK",
                    body = capsJsonPayload.toByteArray(Charsets.UTF_8),
                )
                method == "GET" && path.startsWith("/api/scan/jobs/scan_test_001/progress") -> SimpleScanHttpResponse.Bytes(
                    statusCode = 200,
                    reason = "OK",
                    body = progressJsonPayload.toByteArray(Charsets.UTF_8),
                )
                else -> SimpleScanHttpResponse.Bytes(
                    statusCode = 404,
                    reason = "Not Found",
                    body = """{"error":"Unknown endpoint"}""".toByteArray(Charsets.UTF_8),
                )
            }
            override fun createPendingIppJob(
                title: String,
                clientName: String,
                options: PrintJobOptions,
            ): Result<PrintJobRecord> = Result.failure(UnsupportedOperationException("Unused"))
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

        val server = IppPrintServer(tempDir, delegate)
        try {
            val port = server.start(0).getOrThrow()

            // 1. Verify /api/scan/capabilities over HTTP on same port
            val capConn = URL("http://127.0.0.1:$port/api/scan/capabilities").openConnection() as HttpURLConnection
            assertEquals(200, capConn.responseCode)
            val capBody = capConn.inputStream.readBytes().toString(Charsets.UTF_8)
            capConn.disconnect()
            assertTrue(capBody.contains("\"available\":true"))
            assertTrue(capBody.contains("\"supportsFlatbed\":true"))
            assertTrue(capBody.contains("\"supportsAdf\":true"))

            // 2. Verify /api/scan/jobs/{id}/progress returns live progress, attempt, and heartbeatAtMs
            val progConn = URL("http://127.0.0.1:$port/api/scan/jobs/scan_test_001/progress?deviceId=dev_abc123")
                .openConnection() as HttpURLConnection
            assertEquals(200, progConn.responseCode)
            val progBody = progConn.inputStream.readBytes().toString(Charsets.UTF_8)
            progConn.disconnect()
            assertTrue(progBody.contains("\"progress\":42"))
            assertTrue(progBody.contains("\"attempt\":2"))
            assertTrue(progBody.contains("\"heartbeatAtMs\":1700000006500"))

            // 3. Verify legacy /eSCL paths are removed and return HTTP 404
            val esclConn = URL("http://127.0.0.1:$port/eSCL/ScannerCapabilities").openConnection() as HttpURLConnection
            assertEquals(404, esclConn.responseCode)
            esclConn.disconnect()
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ippCodec_parsesClientPrinterResolutionDpiAndDpcm() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeShort(IppConstants.OP_PRINT_JOB)
        dos.writeInt(101)
        dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
        writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        dos.writeByte(IppConstants.TAG_JOB_ATTRIBUTES)
        writeResolutionAttr(dos, "printer-resolution", 600, 600, IppConstants.RESOLUTION_UNIT_DPI)
        writeIntAttr(dos, IppConstants.TAG_ENUM, "print-quality", 5)
        dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
        dos.flush()

        val tempDir = Files.createTempDirectory("ipp_res_test").toFile()
        try {
            val req = IppCodec.decodeRequest(ByteArrayInputStream(baos.toByteArray()), tempDir)
            assertEquals(600, req.getResolutionDpi("printer-resolution"))
            assertEquals(5, req.getIntAttr("print-quality"))

            // Also verify DPCM (dots per cm): 236 dpcm ~= 600 DPI
            val dpcmBaos = ByteArrayOutputStream()
            val dpcmDos = DataOutputStream(dpcmBaos)
            dpcmDos.writeByte(2)
            dpcmDos.writeByte(0)
            dpcmDos.writeShort(IppConstants.OP_CREATE_JOB)
            dpcmDos.writeInt(102)
            dpcmDos.writeByte(IppConstants.TAG_JOB_ATTRIBUTES)
            writeResolutionAttr(dpcmDos, "printer-resolution", 236, 236, IppConstants.RESOLUTION_UNIT_DPCM)
            dpcmDos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
            dpcmDos.flush()

            val dpcmReq = IppCodec.decodeRequest(ByteArrayInputStream(dpcmBaos.toByteArray()), tempDir)
            val parsedDpcmDpi = dpcmReq.getResolutionDpi("printer-resolution")
            assertNotNull(parsedDpcmDpi)
            assertEquals(PrintResolution.DPI_600, PrintResolution.fromDpi(parsedDpcmDpi!!))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ippPrintServer_reportsBusyOrAvailableAttributes_enforcesMutualExclusion_andHonorsClientDpi() {
        val tempDir = Files.createTempDirectory("ipp_busy_dpi_test").toFile()
        var currentBusy = DeviceBusyState.AVAILABLE
        var lastCapturedOptions: PrintJobOptions? = null

        val delegate = object : IppServerDelegate {
            override fun currentSettings() = PrintServerSettings(ippPort = 0)
            override fun currentUsbInfo() = UsbPrinterInfo(
                status = UsbConnectionStatus.CONNECTED,
                productName = "Brother Laser Printer",
            )
            override fun currentIpAddress() = "127.0.0.1"
            override fun currentJobs(): List<PrintJobRecord> = emptyList()
            override fun currentDeviceBusyState(): DeviceBusyState = currentBusy
            override fun createPendingIppJob(
                title: String,
                clientName: String,
                options: PrintJobOptions,
            ): Result<PrintJobRecord> {
                if (currentBusy != DeviceBusyState.AVAILABLE) {
                    return Result.failure(DeviceBusyException("Busy: ${currentBusy.name}"))
                }
                lastCapturedOptions = options
                return Result.success(
                    PrintJobRecord(
                        id = 7,
                        title = title,
                        source = PrintJobSource.IPP_NETWORK,
                        clientName = clientName,
                        mimeType = "application/pdf",
                        sourcePages = 1,
                        imposedSheets = 1,
                        pagesPerSheet = options.pagesPerSheet,
                        duplexMode = options.duplexMode,
                        status = PrintJobStatus.PENDING,
                    ),
                )
            }
            override fun attachDocumentAndEnqueue(
                existingJobId: Int?,
                title: String,
                clientName: String,
                declaredMime: String?,
                file: File,
                options: PrintJobOptions,
            ): Result<PrintJobRecord> {
                if (currentBusy != DeviceBusyState.AVAILABLE) {
                    return Result.failure(DeviceBusyException("Busy: ${currentBusy.name}"))
                }
                lastCapturedOptions = options
                file.delete()
                return Result.success(
                    PrintJobRecord(
                        id = existingJobId ?: 8,
                        title = title,
                        source = PrintJobSource.IPP_NETWORK,
                        clientName = clientName,
                        mimeType = declaredMime ?: "application/pdf",
                        sourcePages = 1,
                        imposedSheets = 1,
                        pagesPerSheet = options.pagesPerSheet,
                        duplexMode = options.duplexMode,
                        status = PrintJobStatus.PENDING,
                    ),
                )
            }
            override fun cancelJob(jobId: Int) = false
            override fun onLog(category: LogCategory, message: String, isError: Boolean) = Unit
            override fun onRequestMetricsChanged(activeCount: Int, totalCount: Int) = Unit
        }

        val server = IppPrintServer(tempDir, delegate)
        try {
            val port = server.start(0).getOrThrow()

            // 1. AVAILABLE -> printer-state=3 (idle), printer-is-accepting-jobs=true, "Available"
            val availResp = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_GET_PRINTER_ATTRIBUTES, 1))
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(availResp))
            val availText = String(availResp, Charsets.ISO_8859_1)
            assertTrue(availText.contains("Available"))
            assertTrue(availText.contains("printer-is-accepting-jobs"))
            assertTrue(availText.contains("printer-resolution-supported"))
            assertTrue(availText.contains("print-quality-supported"))

            // 2. Client-selected 600 DPI via printer-resolution on Print-Job
            val print600 = buildPrintJobRequest(requestId = 2, resolutionDpi = 600, printQuality = null)
            val print600Resp = sendIppRequest(port, print600)
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(print600Resp))
            assertEquals(PrintResolution.DPI_600, lastCapturedOptions?.resolution)

            // 3. Client-selected 600 DPI via print-quality=5 (High) when printer-resolution is omitted
            val printHighQuality = buildPrintJobRequest(requestId = 3, resolutionDpi = null, printQuality = 5)
            val printHighResp = sendIppRequest(port, printHighQuality)
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(printHighResp))
            assertEquals(PrintResolution.DPI_600, lastCapturedOptions?.resolution)

            // 4. Default falls back to 300 DPI when client specifies neither
            val printDefault = buildPrintJobRequest(requestId = 4, resolutionDpi = null, printQuality = null)
            val printDefaultResp = sendIppRequest(port, printDefault)
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(printDefaultResp))
            assertEquals(PrintResolution.DPI_300, lastCapturedOptions?.resolution)

            // 5. SCANNING -> Get-Printer-Attributes reports "Busy: Scanner is in use"; Validate-Job accepts for async queueing; Create-Job propagates delegate busy if delegate rejects
            currentBusy = DeviceBusyState.SCANNING
            val scanBusyAttrs = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_GET_PRINTER_ATTRIBUTES, 5))
            assertTrue(String(scanBusyAttrs, Charsets.ISO_8859_1).contains("Busy: Scanner is in use"))

            val validateWhileScanning = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_VALIDATE_JOB, 6))
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(validateWhileScanning))

            val createWhileScanning = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_CREATE_JOB, 7))
            assertEquals(IppConstants.STATUS_SERVER_BUSY, readIppStatusCode(createWhileScanning))

            // 6. PRINTING -> Get-Printer-Attributes reports "Busy: Printing another job"; Print-Job returns STATUS_SERVER_BUSY
            currentBusy = DeviceBusyState.PRINTING
            val printBusyAttrs = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_GET_PRINTER_ATTRIBUTES, 8))
            assertTrue(String(printBusyAttrs, Charsets.ISO_8859_1).contains("Busy: Printing another job"))

            val printWhileBusy = sendIppRequest(port, buildPrintJobRequest(requestId = 9, resolutionDpi = 300, printQuality = null))
            assertEquals(IppConstants.STATUS_SERVER_BUSY, readIppStatusCode(printWhileBusy))
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ippPrintServer_advertisesAndHonorsClientMarginsScalingOrientationRangesAndMedia() {
        val tempDir = Files.createTempDirectory("ipp_client_params_test").toFile()
        var captured: PrintJobOptions? = null

        val delegate = object : IppServerDelegate {
            override fun currentSettings() = PrintServerSettings(ippPort = 0)
            override fun currentUsbInfo() = UsbPrinterInfo(
                status = UsbConnectionStatus.CONNECTED,
                productName = "Brother Laser Printer",
            )
            override fun currentIpAddress() = "127.0.0.1"
            override fun currentJobs(): List<PrintJobRecord> = emptyList()
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
            ): Result<PrintJobRecord> {
                captured = options
                file.delete()
                return Result.success(
                    PrintJobRecord(
                        id = 21,
                        title = title,
                        source = PrintJobSource.IPP_NETWORK,
                        clientName = clientName,
                        mimeType = "application/pdf",
                        sourcePages = 4,
                        imposedSheets = 2,
                        pagesPerSheet = options.pagesPerSheet,
                        duplexMode = options.duplexMode,
                        copies = options.copies,
                        status = PrintJobStatus.PENDING,
                    ),
                )
            }
            override fun cancelJob(jobId: Int) = false
            override fun onLog(category: LogCategory, message: String, isError: Boolean) = Unit
            override fun onRequestMetricsChanged(activeCount: Int, totalCount: Int) = Unit
        }

        val server = IppPrintServer(tempDir, delegate)
        try {
            val port = server.start(0).getOrThrow()

            // 1. Verify Get-Printer-Attributes advertises full client-controllable parameters
            val attrsResp = sendIppRequest(port, buildSimpleIppOp(IppConstants.OP_GET_PRINTER_ATTRIBUTES, 10))
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(attrsResp))
            val attrsWire = String(attrsResp, Charsets.ISO_8859_1)
            assertTrue(attrsWire.contains("media-col-supported"))
            assertTrue(attrsWire.contains("media-col-default"))
            assertTrue(attrsWire.contains("media-top-margin-supported"))
            assertTrue(attrsWire.contains("print-scaling-supported"))
            assertTrue(attrsWire.contains("orientation-requested-supported"))
            assertTrue(attrsWire.contains("page-ranges-supported"))
            assertTrue(attrsWire.contains("sheet-collate-supported"))
            assertTrue(attrsWire.contains("page-delivery-supported"))
            assertTrue(attrsWire.contains("media-type-supported"))
            assertTrue(attrsWire.contains("job-creation-attributes-supported"))
            assertTrue(attrsWire.contains("na_legal_8.5x14in"))
            assertTrue(attrsWire.contains("iso_a5_148x210mm"))

            // 2. Send Print-Job with rich client parameters:
            //    media-col (A5 dimensions, custom asymmetric margins 12.7/6.35/10/15 mm, heavyweight media),
            //    print-scaling=fill, orientation-requested=4 (landscape), page-ranges=[1..2, 5..6],
            //    page-delivery=reverse-order, sheet-collate=uncollated, print-quality=3 (Draft/Eco),
            //    print-content-optimize=text, page-border=single, presentation-direction-number-up=tobottom-toright
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeByte(2)
            dos.writeByte(0)
            dos.writeShort(IppConstants.OP_PRINT_JOB)
            dos.writeInt(11)
            dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
            writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
            writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
            writeAttr(dos, IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", "ClientFullParams.pdf")
            writeAttr(dos, IppConstants.TAG_MIME_MEDIA_TYPE, "document-format", "application/pdf")

            dos.writeByte(IppConstants.TAG_JOB_ATTRIBUTES)
            // media-col collection
            writeBeginCollection(dos, "media-col")
            writeBeginMemberCollection(dos, "media-size")
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "x-dimension", 14800)
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "y-dimension", 21000)
            writeEndCollection(dos)
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "media-top-margin", 1270)
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "media-bottom-margin", 635)
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "media-left-margin", 1000)
            writeMemberInt(dos, IppConstants.TAG_INTEGER, "media-right-margin", 1500)
            writeMemberString(dos, IppConstants.TAG_KEYWORD, "media-type", "stationery-heavyweight")
            writeEndCollection(dos)

            writeAttr(dos, IppConstants.TAG_KEYWORD, "print-scaling", "fill")
            writeIntAttr(dos, IppConstants.TAG_ENUM, "orientation-requested", 4)
            writeRangeAttr(dos, "page-ranges", 1, 2)
            writeRangeAttr(dos, "", 5, 6)
            writeAttr(dos, IppConstants.TAG_KEYWORD, "page-delivery", "reverse-order")
            writeAttr(dos, IppConstants.TAG_KEYWORD, "sheet-collate", "uncollated")
            writeIntAttr(dos, IppConstants.TAG_ENUM, "print-quality", 3)
            writeAttr(dos, IppConstants.TAG_KEYWORD, "print-content-optimize", "text")
            writeAttr(dos, IppConstants.TAG_KEYWORD, "page-border", "single")
            writeIntAttr(dos, IppConstants.TAG_INTEGER, "number-up", 4)
            writeAttr(dos, IppConstants.TAG_KEYWORD, "presentation-direction-number-up", "tobottom-toright")
            writeAttr(dos, IppConstants.TAG_KEYWORD, "sides", "two-sided-short-edge")
            writeIntAttr(dos, IppConstants.TAG_INTEGER, "copies", 3)
            dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
            dos.write("%PDF-1.7\n%%EOF".toByteArray())
            dos.flush()

            val resp = sendIppRequest(port, baos.toByteArray())
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, readIppStatusCode(resp))

            val opts = captured
            assertNotNull(opts)
            opts!!
            assertEquals(PaperSize.A5, opts.paperSize)
            assertEquals(PageMargins(topMm = 12.7f, bottomMm = 6.35f, leftMm = 10.0f, rightMm = 15.0f), opts.margins)
            assertEquals(MediaType.THICK, opts.mediaType)
            assertEquals(PrintScaling.FILL, opts.scaling)
            assertEquals(PrintOrientation.LANDSCAPE, opts.orientation)
            assertEquals(listOf(1..2, 5..6), opts.pageRanges)
            assertTrue(opts.reversePageOrder)
            assertFalse(opts.collateCopies)
            assertTrue(opts.tonerSave)
            assertFalse(opts.ditherImages)
            assertTrue(opts.drawNUpBorders)
            assertEquals(PagesPerSheet.FOUR, opts.pagesPerSheet)
            assertEquals(NUpPresentationDirection.TO_BOTTOM_TO_RIGHT, opts.nUpDirection)
            assertEquals(DuplexMode.SHORT_EDGE, opts.duplexMode)
            assertEquals(3, opts.copies)

            // 3. Verify NUpLayoutEngine.resolvePageIndices handles ranges + reverse order
            assertEquals(
                listOf(5, 4, 1, 0),
                NUpLayoutEngine.resolvePageIndices(totalPages = 8, pageRanges = opts.pageRanges, reverseOrder = true),
            )
            assertEquals(
                listOf(0, 1, 4, 5),
                NUpLayoutEngine.resolvePageIndices(totalPages = 8, pageRanges = opts.pageRanges, reverseOrder = false),
            )
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun windows10And11_ippSharedPrinterHandshake_chunkedExpectAndPwgRasterAttributes() {
        val tempDir = Files.createTempDirectory("ipp_windows_compat_test").toFile()
        val capturedLogs = java.util.Collections.synchronizedList(mutableListOf<String>())
        var capturedPwgBytes: ByteArray? = null
        var capturedMime: String? = null

        val delegate = object : IppServerDelegate {
            override fun currentSettings() = PrintServerSettings(
                printerName = "BrotherBridge",
                ippPort = 8631,
            )
            override fun currentUsbInfo() = UsbPrinterInfo(
                status = UsbConnectionStatus.CONNECTED,
                productName = "Brother DCP-L2520D",
            )
            override fun currentIpAddress() = "192.168.1.50"
            override fun currentJobs(): List<PrintJobRecord> = emptyList()
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
            ): Result<PrintJobRecord> {
                capturedMime = declaredMime
                capturedPwgBytes = file.readBytes()
                file.delete()
                return Result.success(
                    PrintJobRecord(
                        id = existingJobId ?: 31,
                        title = title,
                        source = PrintJobSource.IPP_NETWORK,
                        clientName = clientName,
                        mimeType = declaredMime ?: "image/pwg-raster",
                        sourcePages = 2,
                        imposedSheets = 2,
                        pagesPerSheet = options.pagesPerSheet,
                        duplexMode = options.duplexMode,
                        status = PrintJobStatus.PENDING,
                    ),
                )
            }
            override fun cancelJob(jobId: Int) = false
            override fun onLog(category: LogCategory, message: String, isError: Boolean) {
                if (category == LogCategory.IPP) capturedLogs.add(message)
            }
            override fun onRequestMetricsChanged(activeCount: Int, totalCount: Int) = Unit
        }

        val server = IppPrintServer(tempDir, delegate)
        try {
            val port = server.start(0).getOrThrow()
            val customHost = "192.168.43.1:8631"
            val expectedUri = "http://$customHost/ipp/print"

            // 1. Simulate Windows 10/11 inetpp.dll sending Transfer-Encoding: chunked + Expect: 100-continue
            //    with a custom Host header, chunk extensions, and trailer headers.
            val reqId = 0x12345678
            val getPrinterAttrsReq = ByteArrayOutputStream().apply {
                val dos = DataOutputStream(this)
                dos.writeByte(2)
                dos.writeByte(0)
                dos.writeShort(IppConstants.OP_GET_PRINTER_ATTRIBUTES)
                dos.writeInt(reqId)
                dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
                writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
                writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
                writeAttr(dos, IppConstants.TAG_URI, "printer-uri", expectedUri)
                dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
                dos.flush()
            }.toByteArray()

            val (saw100Continue, httpHeaders, respBytes) = sendRawChunkedIppRequest(
                port = port,
                hostHeader = customHost,
                ippPayload = getPrinterAttrsReq,
                expectContinue = true,
                userAgent = "Internet Print Provider",
            )
            assertTrue("Server must send HTTP/1.1 100 Continue before reading body", saw100Continue)
            assertEquals("application/ipp", httpHeaders["content-type"])
            assertEquals(respBytes.size.toString(), httpHeaders["content-length"])

            val resp = IppCodec.decodeResponse(respBytes.inputStream())
            // Must echo client IPP 2.0 (0x02 0x00)
            assertEquals(2, resp.majorVersion)
            assertEquals(0, resp.minorVersion)
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, resp.statusCode)
            assertEquals(reqId, resp.requestId)

            // Verify attributes-charset and attributes-natural-language are the FIRST two attributes
            val opKeys = resp.operationAttributes.keys.toList()
            assertTrue(opKeys.size >= 2)
            assertEquals("attributes-charset", opKeys[0])
            assertEquals("attributes-natural-language", opKeys[1])
            assertEquals("utf-8", resp.operationAttributes["attributes-charset"]?.firstOrNull()?.stringValue)
            assertEquals("en", resp.operationAttributes["attributes-natural-language"]?.firstOrNull()?.stringValue)

            // Verify dynamic Host-matched http:// printer-uri-supported, IEEE 1284 Device ID, and Mopria core attributes
            assertEquals(expectedUri, resp.getStringAttr("printer-uri-supported"))
            assertEquals("MFG:Brother;MDL:DCP-L2520D;CMD:PWG,PDF;CLS:PRINTER;", resp.getStringAttr("printer-device-id"))
            assertEquals("BrotherBridge", resp.getStringAttr("printer-dns-sd-name"))
            assertEquals(listOf("ipp-everywhere"), resp.getStringListAttr("ipp-features-supported"))
            assertEquals(listOf("none"), resp.getStringListAttr("compression-supported"))
            assertEquals(1, resp.getIntAttr("copies-default"))
            assertEquals(1, resp.printerAttributes["copies-supported"]?.firstOrNull()?.rangeLower)
            assertEquals(99, resp.printerAttributes["copies-supported"]?.firstOrNull()?.rangeUpper)
            assertEquals("stationery", resp.getStringAttr("media-type-default"))
            assertEquals(listOf("stationery"), resp.getStringListAttr("media-type-supported"))
            assertEquals(3, resp.getIntAttr("orientation-requested-default"))
            assertEquals(
                listOf(3, 4),
                resp.printerAttributes["orientation-requested-supported"]?.mapNotNull { it.intValue },
            )
            assertTrue((resp.getIntAttr("printer-up-time") ?: 0) >= 1)
            assertEquals("http://$customHost/", resp.getStringAttr("printer-more-info"))
            assertEquals("none", resp.getStringAttr("uri-security-supported"))
            assertEquals("none", resp.getStringAttr("uri-authentication-supported"))
            assertEquals("BrotherBridge", resp.getStringAttr("printer-name"))
            assertEquals("BrotherBridge Wireless Printer", resp.getStringAttr("printer-info"))
            assertEquals("Brother DCP-L2520D (IPP)", resp.getStringAttr("printer-make-and-model"))
            assertEquals(3, resp.getIntAttr("printer-state"))
            assertEquals("none", resp.getStringAttr("printer-state-reasons"))
            assertEquals(true, resp.getBoolAttr("printer-is-accepting-jobs"))
            assertEquals(0, resp.getIntAttr("queued-job-count"))
            assertEquals(listOf("1.1", "2.0"), resp.getStringListAttr("ipp-versions-supported"))
            assertEquals("utf-8", resp.getStringAttr("charset-configured"))
            assertEquals(listOf("utf-8"), resp.getStringListAttr("charset-supported"))
            assertEquals("en", resp.getStringAttr("natural-language-configured"))
            assertEquals(listOf("en"), resp.getStringListAttr("generated-natural-language-supported"))
            assertEquals("application/octet-stream", resp.getStringAttr("document-format-default"))
            val formats = resp.getStringListAttr("document-format-supported")
            assertTrue(formats.containsAll(listOf("image/pwg-raster", "application/pdf", "image/jpeg", "image/png", "image/webp", "application/octet-stream")))
            assertEquals("attempted", resp.getStringAttr("pdl-override-supported"))
            assertEquals(false, resp.getBoolAttr("color-supported"))
            assertEquals(26, resp.getIntAttr("pages-per-minute"))
            assertEquals("iso_a4_210x297mm", resp.getStringAttr("media-default"))
            assertTrue(resp.getStringListAttr("media-supported").containsAll(listOf("iso_a4_210x297mm", "na_letter_8.5x11in", "iso_a5_148x210mm")))
            assertEquals(listOf("iso_a4_210x297mm", "na_letter_8.5x11in"), resp.getStringListAttr("media-ready"))
            assertEquals("one-sided", resp.getStringAttr("sides-default"))
            assertEquals(listOf("one-sided"), resp.getStringListAttr("sides-supported"))
            assertEquals("600x600dpi", resp.getStringAttr("printer-resolution-default"))
            assertEquals(listOf("300x300dpi", "600x600dpi"), resp.getStringListAttr("printer-resolution-supported"))
            assertEquals(4, resp.getIntAttr("print-quality-default"))
            assertEquals(listOf("300x300dpi", "600x600dpi"), resp.getStringListAttr("pwg-raster-document-resolution-supported"))
            assertEquals(listOf("black_1", "sgray_8"), resp.getStringListAttr("pwg-raster-document-type-supported"))
            assertEquals("normal", resp.getStringAttr("pwg-raster-document-sheet-back"))

            // 2. Verify IPP 1.1 with ipp:// printer-uri + filtered requested-attributes echoes 1.1, returns ipp:// scheme, and filters cleanly
            val ippSchemeReq = ByteArrayOutputStream().apply {
                val dos = DataOutputStream(this)
                dos.writeByte(1)
                dos.writeByte(1)
                dos.writeShort(IppConstants.OP_GET_PRINTER_ATTRIBUTES)
                dos.writeInt(101)
                dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
                writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
                writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
                writeAttr(dos, IppConstants.TAG_URI, "printer-uri", "ipp://$customHost/ipp/print")
                writeAttr(dos, IppConstants.TAG_KEYWORD, "requested-attributes", "printer-uri-supported")
                writeAttr(dos, IppConstants.TAG_KEYWORD, "", "printer-device-id")
                writeAttr(dos, IppConstants.TAG_KEYWORD, "", "printer-more-info")
                dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
                dos.flush()
            }.toByteArray()
            val (_, _, ippSchemeRespBytes) = sendRawChunkedIppRequest(
                port = port,
                hostHeader = customHost,
                ippPayload = ippSchemeReq,
                expectContinue = false,
                userAgent = "CUPS/2.4",
            )
            val ippSchemeResp = IppCodec.decodeResponse(ippSchemeRespBytes.inputStream())
            assertEquals(1, ippSchemeResp.majorVersion)
            assertEquals(1, ippSchemeResp.minorVersion)
            assertEquals("ipp://$customHost/ipp/print", ippSchemeResp.getStringAttr("printer-uri-supported"))
            assertEquals("http://$customHost/", ippSchemeResp.getStringAttr("printer-more-info"))
            assertEquals("MFG:Brother;MDL:DCP-L2520D;CMD:PWG,PDF;CLS:PRINTER;", ippSchemeResp.getStringAttr("printer-device-id"))
            assertEquals(
                setOf("printer-uri-supported", "printer-device-id", "printer-more-info"),
                ippSchemeResp.printerAttributes.keys,
            )

            // 3. Verify IPP 1.0 echoes 1.0 and omits RFC 3382 collections; unsupported major version 3.0 returns 0x0503
            val win10AllReq = ByteArrayOutputStream().apply {
                val dos = DataOutputStream(this)
                dos.writeByte(1)
                dos.writeByte(0)
                dos.writeShort(IppConstants.OP_GET_PRINTER_ATTRIBUTES)
                dos.writeInt(102)
                dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
                writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
                writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
                writeAttr(dos, IppConstants.TAG_URI, "printer-uri", "http://127.0.0.1:$port/ipp/print")
                dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
                dos.flush()
            }.toByteArray()
            val win10All = IppCodec.decodeResponse(sendIppRequest(port, win10AllReq).inputStream())
            assertEquals(1, win10All.majorVersion)
            assertEquals(0, win10All.minorVersion)
            assertFalse("IPP 1.0 response must not emit media-col-default collections", win10All.printerAttributes.containsKey("media-col-default"))
            assertFalse("IPP 1.0 response must not emit media-col-database collections", win10All.printerAttributes.containsKey("media-col-database"))
            assertTrue(win10All.printerAttributes.containsKey("pwg-raster-document-type-supported"))

            val unsupportedVerReq = ByteArrayOutputStream().apply {
                val dos = DataOutputStream(this)
                dos.writeByte(3)
                dos.writeByte(0)
                dos.writeShort(IppConstants.OP_GET_PRINTER_ATTRIBUTES)
                dos.writeInt(103)
                dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
                writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
                writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
                dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
                dos.flush()
            }.toByteArray()
            val unsupportedVerResp = IppCodec.decodeResponse(sendIppRequest(port, unsupportedVerReq).inputStream())
            assertEquals(IppConstants.STATUS_SERVER_VERSION_NOT_SUPPORTED, unsupportedVerResp.statusCode)

            // 4. Simulate Windows chunked Print-Job delivering an image/pwg-raster ("RaS2") payload
            val pwgPayload = buildSyntheticTwoPagePwgRaster()
            val pwgPrintReq = ByteArrayOutputStream().apply {
                val dos = DataOutputStream(this)
                dos.writeByte(2)
                dos.writeByte(0)
                dos.writeShort(IppConstants.OP_PRINT_JOB)
                dos.writeInt(205)
                dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
                writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
                writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
                writeAttr(dos, IppConstants.TAG_URI, "printer-uri", expectedUri)
                writeAttr(dos, IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", "Windows Test Page")
                writeAttr(dos, IppConstants.TAG_MIME_MEDIA_TYPE, "document-format", "image/pwg-raster")
                dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
                dos.write(pwgPayload)
                dos.flush()
            }.toByteArray()

            val (_, _, printRespBytes) = sendRawChunkedIppRequest(
                port = port,
                hostHeader = customHost,
                ippPayload = pwgPrintReq,
                expectContinue = true,
                userAgent = "Internet Print Provider",
            )
            val printResp = IppCodec.decodeResponse(printRespBytes.inputStream())
            assertEquals(2, printResp.majorVersion)
            assertEquals(0, printResp.minorVersion)
            assertEquals(IppConstants.STATUS_SUCCESSFUL_OK, printResp.statusCode)
            assertEquals(205, printResp.requestId)
            assertEquals("image/pwg-raster", capturedMime)
            assertArrayEquals(pwgPayload, capturedPwgBytes)

            // 5. Verify diagnostic log captured Host, User-Agent, reqVer/respVer, reqAttrs, TE, op, reqId, format, status
            assertTrue(
                "Expected diagnostic log entry for chunked Get-Printer-Attributes with UA and IPP versions",
                capturedLogs.any {
                    it.contains("Host=$customHost") &&
                        it.contains("UA=Internet Print Provider") &&
                        it.contains("reqVer=2.0") &&
                        it.contains("respVer=2.0") &&
                        it.contains("TE=chunked") &&
                        it.contains("op=0x000B") &&
                        it.contains("reqId=$reqId") &&
                        it.contains("status=0x0000")
                },
            )
            assertTrue(
                "Expected diagnostic log entry capturing requested-attributes",
                capturedLogs.any {
                    it.contains("UA=CUPS/2.4") &&
                        it.contains("reqVer=1.1") &&
                        it.contains("respVer=1.1") &&
                        it.contains("reqAttrs=printer-uri-supported,printer-device-id,printer-more-info")
                },
            )
            assertTrue(
                "Expected diagnostic log entry for chunked image/pwg-raster Print-Job",
                capturedLogs.any {
                    it.contains("op=0x0002") &&
                        it.contains("reqId=205") &&
                        it.contains("format=image/pwg-raster") &&
                        it.contains("status=0x0000")
                },
            )
        } finally {
            server.stop()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pwgRasterDecoder_decodesBlack1AndSGray8MultiPagePackBitsStreams() {
        val streamBytes = buildSyntheticTwoPagePwgRaster()
        assertTrue(PwgRasterDecoder.isPwgRasterHeader(streamBytes))
        assertEquals(2, PwgRasterDecoder.countPages(ByteArrayInputStream(streamBytes)))

        val pages = PwgRasterDecoder.decodeAllPagesPixels(ByteArrayInputStream(streamBytes))
        assertEquals(2, pages.size)

        // Page 1: black_1 (8x4, K-space: bit 1 = black, bit 0 = white)
        val (hdr1, px1) = pages[0]
        assertEquals(8, hdr1.cupsWidth)
        assertEquals(4, hdr1.cupsHeight)
        assertEquals(1, hdr1.cupsBitsPerPixel)
        assertEquals(1, hdr1.cupsBytesPerLine)
        assertEquals(600, hdr1.hwResolutionX)
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Rows 0 & 1 repeated from 0xAA (10101010b -> B,W,B,W,B,W,B,W)
        for (row in 0..1) {
            for (x in 0 until 8) {
                val expected = if (x % 2 == 0) black else white
                assertEquals("Page 1 pixel ($x,$row)", expected, px1[row * 8 + x])
            }
        }
        // Row 2 from 0xF0 (11110000b -> 4 black, 4 white)
        for (x in 0 until 8) {
            val expected = if (x < 4) black else white
            assertEquals("Page 1 pixel ($x,2)", expected, px1[2 * 8 + x])
        }
        // Row 3 from 0x00 (all white)
        for (x in 0 until 8) {
            assertEquals("Page 1 pixel ($x,3)", white, px1[3 * 8 + x])
        }

        // Page 2: sgray_8 (4x3, sGray: 0 = black, 255 = white)
        val (hdr2, px2) = pages[1]
        assertEquals(4, hdr2.cupsWidth)
        assertEquals(3, hdr2.cupsHeight)
        assertEquals(8, hdr2.cupsBitsPerPixel)
        assertEquals(4, hdr2.cupsBytesPerLine)
        assertEquals(300, hdr2.hwResolutionX)
        // Row 0: literal [0x00, 0x40, 0x80, 0xFF]
        assertEquals(0xFF000000.toInt(), px2[0])
        assertEquals(0xFF404040.toInt(), px2[1])
        assertEquals(0xFF808080.toInt(), px2[2])
        assertEquals(0xFFFFFFFF.toInt(), px2[3])
        // Rows 1 & 2: repeated 4x 0xC0 across 2 repeated scanlines
        for (row in 1..2) {
            for (x in 0 until 4) {
                assertEquals("Page 2 pixel ($x,$row)", 0xFFC0C0C0.toInt(), px2[row * 4 + x])
            }
        }
    }

    @Test
    fun printerDiagnosticsParser_extractsTonerPaperDrumAndPortStatus() {
        val ieeeId = "MFG:Brother;CMD:PJL,HBP;MDL:DCP-L2520D series;CLS:PRINTER;CID:Brother Laser Type1;"
        val healthyPjl = """
            @PJL INFO ID
            "Brother DCP-L2520D series"
            @PJL INFO STATUS
            CODE=10001
            DISPLAY="Ready"
            ONLINE=TRUE
            @PJL INFO PAGECOUNT
            1482
            @PJL INFO NEXTCARE
            TONER=75
            DRUM=88
            @PJL INFO VARIABLES
            PAPER=A4 [15 ENUMERATED]
            MEDIATYPE=REGULAR [6 ENUMERATED]
            ECONOMODE=OFF [2 ENUMERATED]
            DUPLEX=ON [2 ENUMERATED]
        """.trimIndent()

        // 0x18 = Selected (0x10) + No Error (0x08), Paper Empty (0x20) = 0
        val healthy = PrinterDiagnosticsParser.parse(
            portStatusByte = 0x18,
            ieee1284DeviceId = ieeeId,
            pjlResponseText = healthyPjl,
        )
        assertEquals(true, healthy.online)
        assertEquals(false, healthy.hasHardwareError)
        assertEquals(PaperAvailability.AVAILABLE, healthy.paperAvailability)
        assertEquals("A4", healthy.loadedPaperSize)
        assertEquals("Plain Paper", healthy.loadedMediaType)
        assertEquals(75, healthy.tonerPercent)
        assertEquals(SupplyStatus.OK, healthy.tonerStatus)
        assertEquals(88, healthy.drumPercent)
        assertEquals(SupplyStatus.OK, healthy.drumStatus)
        assertEquals("Ready", healthy.lcdDisplayMessage)
        assertEquals(10001, healthy.pjlStatusCode)
        assertEquals(1482, healthy.totalPageCount)
        assertEquals(false, healthy.economyMode)
        assertEquals(true, healthy.duplexEnabled)
        assertEquals(listOf("PJL", "HBP"), healthy.commandLanguages)
        assertTrue(healthy.pjlSupported)
        assertTrue(healthy.portStatusSupported)

        // Low toner + Paper Empty via USB GET_PORT_STATUS (0x20 = Paper Empty, 0x00 = Error/Offline)
        val alertPjl = """
            @PJL INFO STATUS
            CODE=10006
            DISPLAY="Toner Low"
            ONLINE=TRUE
        """.trimIndent()
        val alert = PrinterDiagnosticsParser.parse(
            portStatusByte = 0x30, // Paper Empty (0x20) + Selected (0x10) + Error (bit 3 = 0)
            ieee1284DeviceId = ieeeId,
            pjlResponseText = alertPjl,
        )
        assertEquals(PaperAvailability.EMPTY, alert.paperAvailability)
        assertEquals(SupplyStatus.LOW, alert.tonerStatus)
        assertEquals(true, alert.hasHardwareError)

        // Non-PJL printer fallback relies cleanly on IEEE 1284 port status
        val fallback = PrinterDiagnosticsParser.parse(
            portStatusByte = 0x18,
            ieee1284DeviceId = ieeeId,
            pjlResponseText = "",
        )
        assertFalse(fallback.pjlSupported)
        assertTrue(fallback.portStatusSupported)
        assertEquals(PaperAvailability.AVAILABLE, fallback.paperAvailability)
        assertEquals(SupplyStatus.UNKNOWN, fallback.tonerStatus)
        assertEquals(true, fallback.online)
    }

    private fun buildSimpleIppOp(opId: Int, requestId: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeShort(opId)
        dos.writeInt(requestId)
        dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
        writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
        dos.flush()
        return baos.toByteArray()
    }

    private fun buildPrintJobRequest(requestId: Int, resolutionDpi: Int?, printQuality: Int?): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeByte(2)
        dos.writeByte(0)
        dos.writeShort(IppConstants.OP_PRINT_JOB)
        dos.writeInt(requestId)
        dos.writeByte(IppConstants.TAG_OPERATION_ATTRIBUTES)
        writeAttr(dos, IppConstants.TAG_CHARSET, "attributes-charset", "utf-8")
        writeAttr(dos, IppConstants.TAG_NATURAL_LANGUAGE, "attributes-natural-language", "en")
        writeAttr(dos, IppConstants.TAG_NAME_WITHOUT_LANGUAGE, "job-name", "ClientDpiJob.pdf")
        writeAttr(dos, IppConstants.TAG_MIME_MEDIA_TYPE, "document-format", "application/pdf")
        if (resolutionDpi != null || printQuality != null) {
            dos.writeByte(IppConstants.TAG_JOB_ATTRIBUTES)
            if (resolutionDpi != null) {
                writeResolutionAttr(dos, "printer-resolution", resolutionDpi, resolutionDpi, IppConstants.RESOLUTION_UNIT_DPI)
            }
            if (printQuality != null) {
                writeIntAttr(dos, IppConstants.TAG_ENUM, "print-quality", printQuality)
            }
        }
        dos.writeByte(IppConstants.TAG_END_OF_ATTRIBUTES)
        dos.write("%PDF-1.7\n%%EOF".toByteArray())
        dos.flush()
        return baos.toByteArray()
    }

    private fun sendIppRequest(port: Int, payload: ByteArray): ByteArray {
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.outputStream.use { it.write(payload) }
        assertEquals(200, conn.responseCode)
        val resp = conn.inputStream.readBytes()
        conn.disconnect()
        return resp
    }

    private fun readIppStatusCode(ippResponse: ByteArray): Int {
        assertTrue("IPP response too short", ippResponse.size >= 8)
        return ((ippResponse[2].toInt() and 0xFF) shl 8) or (ippResponse[3].toInt() and 0xFF)
    }

    private fun writeAttr(out: DataOutputStream, tag: Int, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(v.size)
        out.write(v)
    }

    private fun writeIntAttr(out: DataOutputStream, tag: Int, name: String, value: Int) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(4)
        out.writeInt(value)
    }

    private fun writeResolutionAttr(out: DataOutputStream, name: String, crossFeed: Int, feed: Int, units: Int) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_RESOLUTION)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(9)
        out.writeInt(crossFeed)
        out.writeInt(feed)
        out.writeByte(units)
    }

    private fun writeRangeAttr(out: DataOutputStream, name: String, lower: Int, upper: Int) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_RANGE_OF_INTEGER)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(8)
        out.writeInt(lower)
        out.writeInt(upper)
    }

    private fun writeBeginCollection(out: DataOutputStream, name: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_BEGIN_COLLECTION)
        out.writeShort(n.size)
        out.write(n)
        out.writeShort(0)
    }

    private fun writeBeginMemberCollection(out: DataOutputStream, memberName: String) {
        val m = memberName.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_MEMBER_ATTR_NAME)
        out.writeShort(0)
        out.writeShort(m.size)
        out.write(m)
        out.writeByte(IppConstants.TAG_BEGIN_COLLECTION)
        out.writeShort(0)
        out.writeShort(0)
    }

    private fun writeMemberInt(out: DataOutputStream, tag: Int, memberName: String, value: Int) {
        val m = memberName.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_MEMBER_ATTR_NAME)
        out.writeShort(0)
        out.writeShort(m.size)
        out.write(m)
        out.writeByte(tag)
        out.writeShort(0)
        out.writeShort(4)
        out.writeInt(value)
    }

    private fun writeMemberString(out: DataOutputStream, tag: Int, memberName: String, value: String) {
        val m = memberName.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        out.writeByte(IppConstants.TAG_MEMBER_ATTR_NAME)
        out.writeShort(0)
        out.writeShort(m.size)
        out.write(m)
        out.writeByte(tag)
        out.writeShort(0)
        out.writeShort(v.size)
        out.write(v)
    }

    private fun writeEndCollection(out: DataOutputStream) {
        out.writeByte(IppConstants.TAG_END_COLLECTION)
        out.writeShort(0)
        out.writeShort(0)
    }

    private fun sendRawChunkedIppRequest(
        port: Int,
        hostHeader: String,
        ippPayload: ByteArray,
        expectContinue: Boolean,
        userAgent: String? = null,
    ): Triple<Boolean, Map<String, String>, ByteArray> {
        Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 5000
            val out = sock.getOutputStream()
            val inp = BufferedInputStream(sock.getInputStream())

            val reqHeaders = buildString {
                append("POST /ipp/print HTTP/1.1\r\n")
                append("Host: $hostHeader\r\n")
                if (!userAgent.isNullOrBlank()) append("User-Agent: $userAgent\r\n")
                append("Content-Type: application/ipp\r\n")
                append("Transfer-Encoding: chunked\r\n")
                if (expectContinue) append("Expect: 100-continue\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            out.write(reqHeaders.toByteArray(Charsets.US_ASCII))
            out.flush()

            var saw100Continue = false
            if (expectContinue) {
                val statusLine = readAsciiLine(inp)
                assertTrue("Expected HTTP/1.1 100 Continue, got: $statusLine", statusLine.startsWith("HTTP/1.1 100"))
                saw100Continue = true
                // Consume blank line after 100 Continue
                while (readAsciiLine(inp).isNotEmpty()) {
                    // skip any headers
                }
            }

            // Write payload in multiple chunks with chunk extension and trailer header
            var offset = 0
            var chunkNum = 0
            while (offset < ippPayload.size) {
                val chunkSize = minOf(37, ippPayload.size - offset)
                val ext = if (chunkNum++ == 0) ";ext=win" else ""
                out.write("${chunkSize.toString(16)}$ext\r\n".toByteArray(Charsets.US_ASCII))
                out.write(ippPayload, offset, chunkSize)
                out.write("\r\n".toByteArray(Charsets.US_ASCII))
                offset += chunkSize
            }
            out.write("0\r\nX-Test-Trailer: ok\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()

            val respStatus = readAsciiLine(inp)
            assertTrue("Expected HTTP/1.1 200 OK, got: $respStatus", respStatus.startsWith("HTTP/1.1 200"))
            val respHeaders = LinkedHashMap<String, String>()
            while (true) {
                val line = readAsciiLine(inp)
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    respHeaders[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }
            val contentLength = respHeaders["content-length"]?.toInt() ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = inp.read(body, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            assertEquals(contentLength, read)
            return Triple(saw100Continue, respHeaders, body)
        }
    }

    private fun readAsciiLine(inp: BufferedInputStream): String {
        val sb = StringBuilder(64)
        while (true) {
            val b = inp.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun buildSyntheticTwoPagePwgRaster(): ByteArray {
        val baos = ByteArrayOutputStream()
        // 4-byte "RaS2" sync word
        baos.write(byteArrayOf(0x52, 0x61, 0x53, 0x32))

        // Page 1: black_1 (8x4, 1 bpp, 1 byte/line, CSPACE_K=3, 600 DPI)
        val hdr1 = ByteArray(PwgRasterDecoder.PAGE_HEADER_SIZE)
        putIntBE(hdr1, 276, 600)
        putIntBE(hdr1, 280, 600)
        putIntBE(hdr1, 372, 8)
        putIntBE(hdr1, 376, 4)
        putIntBE(hdr1, 384, 1)
        putIntBE(hdr1, 388, 1)
        putIntBE(hdr1, 392, 1)
        putIntBE(hdr1, 400, PwgRasterDecoder.CSPACE_K)
        putIntBE(hdr1, 420, 1)
        baos.write(hdr1)
        // Rows 0..1 (repeatLine=1 -> 2 rows): repeat 1 byte 0xAA (ctrl=0x00)
        baos.write(byteArrayOf(0x01, 0x00, 0xAA.toByte()))
        // Row 2 (repeatLine=0 -> 1 row): repeat 1 byte 0xF0 (ctrl=0x00)
        baos.write(byteArrayOf(0x00, 0x00, 0xF0.toByte()))
        // Row 3 (repeatLine=0 -> 1 row): repeat 1 byte 0x00 (ctrl=0x00)
        baos.write(byteArrayOf(0x00, 0x00, 0x00))

        // Page 2: sgray_8 (4x3, 8 bpp, 4 bytes/line, CSPACE_SGRAY=18, 300 DPI)
        val hdr2 = ByteArray(PwgRasterDecoder.PAGE_HEADER_SIZE)
        putIntBE(hdr2, 276, 300)
        putIntBE(hdr2, 280, 300)
        putIntBE(hdr2, 372, 4)
        putIntBE(hdr2, 376, 3)
        putIntBE(hdr2, 384, 8)
        putIntBE(hdr2, 388, 8)
        putIntBE(hdr2, 392, 4)
        putIntBE(hdr2, 400, PwgRasterDecoder.CSPACE_SGRAY)
        putIntBE(hdr2, 420, 1)
        baos.write(hdr2)
        // Row 0 (repeatLine=0 -> 1 row): literal 4 bytes -> ctrl = 257 - 4 = 253 (0xFD)
        baos.write(byteArrayOf(0x00, 0xFD.toByte(), 0x00, 0x40, 0x80.toByte(), 0xFF.toByte()))
        // Rows 1..2 (repeatLine=1 -> 2 rows): repeat 4 bytes of 0xC0 -> ctrl = 4 - 1 = 3 (0x03)
        baos.write(byteArrayOf(0x01, 0x03, 0xC0.toByte()))

        return baos.toByteArray()
    }

    private fun putIntBE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
