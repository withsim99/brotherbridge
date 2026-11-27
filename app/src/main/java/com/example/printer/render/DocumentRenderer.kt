package com.example.printer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.example.model.NUpPresentationDirection
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import com.example.model.PrintScaling
import com.example.printer.ipp.DocumentTooLargeException
import com.example.printer.ipp.InsufficientSpoolSpaceException
import com.example.printer.ipp.IppCodec
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class UnsupportedDocumentFormatException(mimeType: String) :
    IllegalArgumentException("Unsupported format '$mimeType'. Only PWG Raster, PDF, and image files are supported.")

enum class SupportedDocumentKind(val canonicalMime: String, val label: String) {
    PWG_RASTER("image/pwg-raster", "PWG Raster"),
    PDF("application/pdf", "PDF Document"),
    JPEG("image/jpeg", "JPEG Image"),
    PNG("image/png", "PNG Image"),
    WEBP("image/webp", "WebP Image"),
    BMP("image/bmp", "BMP Image"),
    GIF("image/gif", "GIF Image");

    val isImage: Boolean get() = this != PDF && this != PWG_RASTER
}

sealed interface DocumentSource {
    data class LocalFile(val file: File) : DocumentSource
    data class ContentUri(val uri: Uri) : DocumentSource
}

data class DocumentMetadata(
    val kind: SupportedDocumentKind,
    val pageCount: Int,
    val fileSizeBytes: Long,
)

data class ResolvedDocument(
    val source: DocumentSource,
    val cachedFallbackFile: File?,
    val metadata: DocumentMetadata,
)

object DocumentRenderer {

    private const val MIN_FREE_CACHE_BYTES = 64L * 1024L * 1024L
    private const val MAX_DECODED_IMAGE_PIXELS = 36_000_000L // ~600 DPI A4 (~34.8 MP)
    private const val PDF_STRIP_MAX_HEIGHT_PX = 1600

    val SUPPORTED_MIME_TYPES: List<String> = listOf(
        "image/pwg-raster",
        "application/pdf",
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/bmp",
        "image/gif",
    )

    fun detectDocumentKind(declaredMime: String?, headerBytes: ByteArray?): SupportedDocumentKind? {
        val normalized = declaredMime?.substringBefore(';')?.trim()?.lowercase()
        when (normalized) {
            "image/pwg-raster" -> return SupportedDocumentKind.PWG_RASTER
            "application/pdf" -> return SupportedDocumentKind.PDF
            "image/jpeg", "image/jpg" -> return SupportedDocumentKind.JPEG
            "image/png" -> return SupportedDocumentKind.PNG
            "image/webp" -> return SupportedDocumentKind.WEBP
            "image/bmp", "image/x-ms-bmp" -> return SupportedDocumentKind.BMP
            "image/gif" -> return SupportedDocumentKind.GIF
        }

        // Magic-byte fallback when IPP client sends application/octet-stream or unknown MIME
        if (headerBytes != null && headerBytes.size >= 4) {
            if (PwgRasterDecoder.isPwgRasterHeader(headerBytes)) {
                return SupportedDocumentKind.PWG_RASTER
            }
            if (headerBytes[0] == 0x25.toByte() && headerBytes[1] == 0x50.toByte() &&
                headerBytes[2] == 0x44.toByte() && headerBytes[3] == 0x46.toByte()
            ) {
                return SupportedDocumentKind.PDF
            }
            if (headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xD8.toByte()) {
                return SupportedDocumentKind.JPEG
            }
            if (headerBytes[0] == 0x89.toByte() && headerBytes[1] == 0x50.toByte() &&
                headerBytes[2] == 0x4E.toByte() && headerBytes[3] == 0x47.toByte()
            ) {
                return SupportedDocumentKind.PNG
            }
            if (headerBytes.size >= 12 &&
                headerBytes[0] == 'R'.code.toByte() && headerBytes[1] == 'I'.code.toByte() &&
                headerBytes[2] == 'F'.code.toByte() && headerBytes[3] == 'F'.code.toByte() &&
                headerBytes[8] == 'W'.code.toByte() && headerBytes[9] == 'E'.code.toByte() &&
                headerBytes[10] == 'B'.code.toByte() && headerBytes[11] == 'P'.code.toByte()
            ) {
                return SupportedDocumentKind.WEBP
            }
            if (headerBytes[0] == 'B'.code.toByte() && headerBytes[1] == 'M'.code.toByte()) {
                return SupportedDocumentKind.BMP
            }
            if (headerBytes[0] == 'G'.code.toByte() && headerBytes[1] == 'I'.code.toByte() &&
                headerBytes[2] == 'F'.code.toByte()
            ) {
                return SupportedDocumentKind.GIF
            }
        }
        return null
    }

    /**
     * Resolves a local [Uri] with zero-copy direct file-descriptor access whenever possible so
     * selecting a 1 GB PDF is instant and uses 0 bytes of extra cache storage. Falls back to
     * copying to cache only if the provider does not support seekable descriptors.
     */
    fun resolveUriZeroCopy(
        context: Context,
        uri: Uri,
        declaredMime: String?,
        fallbackPrefix: String = "job_",
    ): ResolvedDocument {
        val reportedSize = queryUriSize(context, uri)
        if (reportedSize != null && reportedSize > IppCodec.MAX_DOCUMENT_BYTES) {
            throw DocumentTooLargeException(
                "Document is ${formatBytes(reportedSize)} — maximum supported size is 1 GB",
            )
        }

        val header = ByteArray(16)
        val headerRead = context.contentResolver.openInputStream(uri)?.use { it.read(header) }?.coerceAtLeast(0) ?: 0
        val kind = detectDocumentKind(declaredMime, header.copyOf(headerRead))
            ?: throw UnsupportedDocumentFormatException(declaredMime ?: "unknown")

        // Try direct zero-copy ParcelFileDescriptor inspection for PDFs and images
        val directMetadata = runCatching {
            when (kind) {
                SupportedDocumentKind.PDF -> {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("No file descriptor for URI")
                    pfd.use { fd ->
                        val statSize = fd.statSize.takeIf { it > 0L } ?: reportedSize ?: 0L
                        if (statSize > IppCodec.MAX_DOCUMENT_BYTES) {
                            throw DocumentTooLargeException(
                                "Document is ${formatBytes(statSize)} — maximum supported size is 1 GB",
                            )
                        }
                        PdfRenderer(fd).use { renderer ->
                            DocumentMetadata(
                                kind = kind,
                                pageCount = renderer.pageCount.coerceAtLeast(1),
                                fileSizeBytes = statSize,
                            )
                        }
                    }
                }
                SupportedDocumentKind.PWG_RASTER -> {
                    val count = context.contentResolver.openInputStream(uri)?.use { input ->
                        PwgRasterDecoder.countPages(input)
                    } ?: error("Cannot open PWG Raster stream")
                    DocumentMetadata(
                        kind = kind,
                        pageCount = count.coerceAtLeast(1),
                        fileSizeBytes = reportedSize ?: 0L,
                    )
                }
                else -> {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input, null, opts)
                    } ?: error("Cannot open image stream")
                    if (opts.outWidth <= 0 || opts.outHeight <= 0) error("Invalid image bounds")
                    DocumentMetadata(
                        kind = kind,
                        pageCount = 1,
                        fileSizeBytes = reportedSize ?: 0L,
                    )
                }
            }
        }

        directMetadata.onSuccess { meta ->
            return ResolvedDocument(
                source = DocumentSource.ContentUri(uri),
                cachedFallbackFile = null,
                metadata = meta,
            )
        }.onFailure { err ->
            if (err is DocumentTooLargeException) throw err
        }

        // Non-seekable provider fallback: copy safely into cache with 1 GB and free-space guards
        val copied = copyUriToTempFile(context, uri, prefix = fallbackPrefix)
        return try {
            val meta = inspectFile(copied, declaredMime)
            ResolvedDocument(
                source = DocumentSource.LocalFile(copied),
                cachedFallbackFile = copied,
                metadata = meta,
            )
        } catch (t: Throwable) {
            copied.delete()
            throw t
        }
    }

    fun copyUriToTempFile(context: Context, uri: Uri, prefix: String = "job_"): File {
        val spoolDir = File(context.cacheDir, "print_spool").apply { mkdirs() }
        if (spoolDir.usableSpace > 0L && spoolDir.usableSpace < MIN_FREE_CACHE_BYTES) {
            throw InsufficientSpoolSpaceException("Not enough free storage space to cache document")
        }
        val tempFile = File.createTempFile(prefix, ".bin", spoolDir)
        var written = 0L
        val buf = ByteArray(64 * 1024)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        written += read
                        if (written > IppCodec.MAX_DOCUMENT_BYTES) {
                            throw DocumentTooLargeException("Document exceeds 1 GB maximum supported size")
                        }
                        output.write(buf, 0, read)
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected file from URI")
            return tempFile
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    fun inspectFile(file: File, declaredMime: String?): DocumentMetadata {
        val fileLen = file.length()
        if (fileLen > IppCodec.MAX_DOCUMENT_BYTES) {
            throw DocumentTooLargeException(
                "Document is ${formatBytes(fileLen)} — maximum supported size is 1 GB",
            )
        }
        val header = ByteArray(16)
        val readCount = file.inputStream().use { it.read(header) }.coerceAtLeast(0)
        val kind = detectDocumentKind(declaredMime, header.copyOf(readCount))
            ?: throw UnsupportedDocumentFormatException(declaredMime ?: "unknown")

        val pageCount = when (kind) {
            SupportedDocumentKind.PWG_RASTER -> PwgRasterDecoder.countPages(file)
            SupportedDocumentKind.PDF -> {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer -> renderer.pageCount }
                }
            }
            else -> 1
        }
        return DocumentMetadata(
            kind = kind,
            pageCount = pageCount.coerceAtLeast(1),
            fileSizeBytes = fileLen,
        )
    }

    /**
     * Streams imposed sheets one-by-one into [onSheetReady] using a single RGB_565 output sheet
     * bitmap and horizontal strip / single-cell rendering so even 1 GB multi-hundred-page PDFs
     * at 600 DPI stay strictly bounded in RAM and never allocate duplicate full-page bitmaps.
     */
    fun renderImposedSheets(
        context: Context,
        source: DocumentSource,
        kind: SupportedDocumentKind,
        pagesPerSheet: PagesPerSheet,
        paperSize: PaperSize,
        targetDpi: Int,
        drawCellBorders: Boolean,
        forPhysicalPortraitFeed: Boolean,
        margins: PageMargins = PageMargins.DEFAULT,
        scaling: PrintScaling = PrintScaling.AUTO_FIT,
        orientation: PrintOrientation = PrintOrientation.PORTRAIT,
        nUpDirection: NUpPresentationDirection = NUpPresentationDirection.TO_RIGHT_TO_BOTTOM,
        pageRanges: List<IntRange> = emptyList(),
        reversePageOrder: Boolean = false,
        maxSheets: Int = Int.MAX_VALUE,
        panX: Float = 0f,
        panY: Float = 0f,
        onSheetReady: (sheetIndex: Int, totalSheets: Int, sheetBitmap: Bitmap) -> Unit,
    ) {
        val layout = NUpLayoutEngine.computeSheetLayout(
            pagesPerSheet = pagesPerSheet,
            paperSize = paperSize,
            targetDpi = targetDpi,
            drawCellBorders = drawCellBorders,
            forPhysicalPortraitFeed = forPhysicalPortraitFeed,
            margins = margins,
            orientation = orientation,
            nUpDirection = nUpDirection,
        )
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(140, 140, 140)
            style = Paint.Style.STROKE
            strokeWidth = layout.borderStrokeWidth
        }

        when (kind) {
            SupportedDocumentKind.PDF -> {
                openPdfDescriptor(context, source).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val totalPdfPages = renderer.pageCount
                        if (totalPdfPages <= 0) return
                        val pageIndices = NUpLayoutEngine.resolvePageIndices(
                            totalPages = totalPdfPages,
                            pageRanges = pageRanges,
                            reverseOrder = reversePageOrder,
                        )
                        if (pageIndices.isEmpty()) return

                        val totalSheets = NUpLayoutEngine.calculateImposedSides(pageIndices.size, pagesPerSheet)
                        val limit = min(totalSheets, maxSheets)
                        val cellDpi = computeCellRenderDpi(targetDpi, pagesPerSheet)
                        val pdfScaleToTargetDpi = targetDpi / 72f

                        for (sheetIdx in 0 until limit) {
                            val startSlot = sheetIdx * pagesPerSheet.count
                            val endSlot = min(startSlot + pagesPerSheet.count, pageIndices.size)

                            val sheet = Bitmap.createBitmap(
                                layout.bitmapWidth,
                                layout.bitmapHeight,
                                Bitmap.Config.RGB_565,
                            )
                            try {
                                val canvas = Canvas(sheet)
                                canvas.drawColor(Color.WHITE)

                                val rotated = layout.rotationDegrees != 0
                                if (rotated) {
                                    canvas.save()
                                    NUpLayoutEngine.applySheetRotation(canvas, layout)
                                }

                                for (slotIdx in startSlot until endSlot) {
                                    val cellIndex = slotIdx - startSlot
                                    val cellRect = layout.cellRects[cellIndex]
                                    val pdfPageIdx = pageIndices[slotIdx]
                                    renderer.openPage(pdfPageIdx).use { page ->
                                        val natW = page.width.coerceAtLeast(1) * pdfScaleToTargetDpi
                                        val natH = page.height.coerceAtLeast(1) * pdfScaleToTargetDpi
                                        val destRect = NUpLayoutEngine.computeScaledRect(
                                            cellRect = cellRect,
                                            srcWidth = page.width.coerceAtLeast(1),
                                            srcHeight = page.height.coerceAtLeast(1),
                                            innerPad = layout.innerPad,
                                            scaling = scaling,
                                            naturalWidthPx = natW,
                                            naturalHeightPx = natH,
                                        )
                                        if (pagesPerSheet == PagesPerSheet.ONE && targetDpi >= 600) {
                                            // Render 600 DPI 1-Up PDF in horizontal strips, clipped to cell margins
                                            renderPdfPageStrippedOntoCanvas(
                                                page = page,
                                                targetCanvas = canvas,
                                                destRect = destRect,
                                                clipRect = cellRect,
                                                paint = bitmapPaint,
                                            )
                                        } else {
                                            val targetW = ceil(destRect.width()).toInt()
                                                .coerceIn(120, max(layout.logicalWidth * 2, 2400))
                                            val targetH = ceil(destRect.height()).toInt()
                                                .coerceIn(120, max(layout.logicalHeight * 2, 3400))
                                            val rendered = renderPdfPage(
                                                page = page,
                                                maxW = targetW,
                                                maxH = targetH,
                                                dpi = cellDpi,
                                            )
                                            try {
                                                canvas.save()
                                                canvas.clipRect(cellRect)
                                                canvas.drawBitmap(rendered, null, destRect, bitmapPaint)
                                                canvas.restore()
                                            } finally {
                                                if (!rendered.isRecycled) rendered.recycle()
                                            }
                                        }
                                    }
                                    if (drawCellBorders && pagesPerSheet != PagesPerSheet.ONE) {
                                        canvas.drawRect(cellRect, borderPaint)
                                    }
                                }

                                if (rotated) {
                                    canvas.restore()
                                }

                                onSheetReady(sheetIdx, totalSheets, sheet)
                            } finally {
                                if (!sheet.isRecycled) sheet.recycle()
                            }
                        }
                    }
                }
            }

            SupportedDocumentKind.PWG_RASTER -> {
                val totalPwgPages = openDocumentStream(context, source).use { PwgRasterDecoder.countPages(it) }
                if (totalPwgPages <= 0) return
                val pageIndices = NUpLayoutEngine.resolvePageIndices(
                    totalPages = totalPwgPages,
                    pageRanges = pageRanges,
                    reverseOrder = reversePageOrder,
                )
                if (pageIndices.isEmpty()) return

                val totalSheets = NUpLayoutEngine.calculateImposedSides(pageIndices.size, pagesPerSheet)
                val limit = min(totalSheets, maxSheets)
                val fullPageRect = RectF(0f, 0f, layout.logicalWidth.toFloat(), layout.logicalHeight.toFloat())

                if (pagesPerSheet == PagesPerSheet.ONE && !reversePageOrder) {
                    val neededPages = pageIndices.take(limit)
                    val neededSet = neededPages.toSet()
                    var emittedSheets = 0
                    openDocumentStream(context, source).use { stream ->
                        PwgRasterDecoder.decodePages(stream, pageFilter = neededSet) { _, header, pageBitmap ->
                            if (emittedSheets >= limit) return@decodePages
                            val sheet = Bitmap.createBitmap(
                                layout.bitmapWidth,
                                layout.bitmapHeight,
                                Bitmap.Config.RGB_565,
                            )
                            try {
                                val canvas = Canvas(sheet)
                                canvas.drawColor(Color.WHITE)
                                val rotated = layout.rotationDegrees != 0
                                if (rotated) {
                                    canvas.save()
                                    NUpLayoutEngine.applySheetRotation(canvas, layout)
                                }
                                // PWG Raster pages from Windows already include page margins in the full-bleed raster
                                val natW = pageBitmap.width.coerceAtLeast(1) *
                                    (targetDpi.toFloat() / header.hwResolutionX.coerceAtLeast(72))
                                val natH = pageBitmap.height.coerceAtLeast(1) *
                                    (targetDpi.toFloat() / header.hwResolutionY.coerceAtLeast(72))
                                val destRect = NUpLayoutEngine.computeScaledRect(
                                    cellRect = fullPageRect,
                                    srcWidth = pageBitmap.width.coerceAtLeast(1),
                                    srcHeight = pageBitmap.height.coerceAtLeast(1),
                                    innerPad = 0f,
                                    scaling = PrintScaling.FIT,
                                    naturalWidthPx = natW,
                                    naturalHeightPx = natH,
                                )
                                canvas.drawBitmap(pageBitmap, null, destRect, bitmapPaint)
                                if (rotated) canvas.restore()
                                onSheetReady(emittedSheets, totalSheets, sheet)
                                emittedSheets++
                            } finally {
                                if (!sheet.isRecycled) sheet.recycle()
                            }
                        }
                    }
                } else {
                    for (sheetIdx in 0 until limit) {
                        val startSlot = sheetIdx * pagesPerSheet.count
                        val endSlot = min(startSlot + pagesPerSheet.count, pageIndices.size)
                        val slotIndices = (startSlot until endSlot).map { pageIndices[it] }
                        val neededSet = slotIndices.toSet()

                        val sheet = Bitmap.createBitmap(
                            layout.bitmapWidth,
                            layout.bitmapHeight,
                            Bitmap.Config.RGB_565,
                        )
                        try {
                            val canvas = Canvas(sheet)
                            canvas.drawColor(Color.WHITE)
                            val rotated = layout.rotationDegrees != 0
                            if (rotated) {
                                canvas.save()
                                NUpLayoutEngine.applySheetRotation(canvas, layout)
                            }
                            openDocumentStream(context, source).use { stream ->
                                PwgRasterDecoder.decodePages(stream, pageFilter = neededSet) { decodedIdx, header, pageBitmap ->
                                    slotIndices.forEachIndexed { cellIndex, targetPageIdx ->
                                        if (targetPageIdx == decodedIdx) {
                                            val cellRect = if (pagesPerSheet == PagesPerSheet.ONE) {
                                                fullPageRect
                                            } else {
                                                layout.cellRects[cellIndex]
                                            }
                                            val natW = pageBitmap.width.coerceAtLeast(1) *
                                                (targetDpi.toFloat() / header.hwResolutionX.coerceAtLeast(72))
                                            val natH = pageBitmap.height.coerceAtLeast(1) *
                                                (targetDpi.toFloat() / header.hwResolutionY.coerceAtLeast(72))
                                            val destRect = NUpLayoutEngine.computeScaledRect(
                                                cellRect = cellRect,
                                                srcWidth = pageBitmap.width.coerceAtLeast(1),
                                                srcHeight = pageBitmap.height.coerceAtLeast(1),
                                                innerPad = if (pagesPerSheet == PagesPerSheet.ONE) 0f else layout.innerPad,
                                                scaling = if (pagesPerSheet == PagesPerSheet.ONE) PrintScaling.FIT else scaling,
                                                naturalWidthPx = natW,
                                                naturalHeightPx = natH,
                                            )
                                            canvas.save()
                                            canvas.clipRect(cellRect)
                                            canvas.drawBitmap(pageBitmap, null, destRect, bitmapPaint)
                                            canvas.restore()
                                            if (drawCellBorders && pagesPerSheet != PagesPerSheet.ONE) {
                                                canvas.drawRect(cellRect, borderPaint)
                                            }
                                        }
                                    }
                                }
                            }
                            if (rotated) canvas.restore()
                            onSheetReady(sheetIdx, totalSheets, sheet)
                        } finally {
                            if (!sheet.isRecycled) sheet.recycle()
                        }
                    }
                }
            }

            else -> {
                val maxDim = max(layout.logicalWidth, layout.logicalHeight)
                val imageBitmap = decodeSafeImageBitmap(context, source, maxDim)
                    ?: throw IllegalStateException("Failed to decode image file")
                try {
                    val sheet = NUpLayoutEngine.composeSheet(
                        pageBitmaps = listOf(imageBitmap),
                        pagesPerSheet = pagesPerSheet,
                        paperSize = paperSize,
                        targetDpi = targetDpi,
                        drawCellBorders = drawCellBorders,
                        forPhysicalPortraitFeed = forPhysicalPortraitFeed,
                        margins = margins,
                        scaling = scaling,
                        orientation = orientation,
                        nUpDirection = nUpDirection,
                        panX = panX,
                        panY = panY,
                    )
                    try {
                        onSheetReady(0, 1, sheet)
                    } finally {
                        if (!sheet.isRecycled) sheet.recycle()
                    }
                } finally {
                    if (!imageBitmap.isRecycled) imageBitmap.recycle()
                }
            }
        }
    }

    /**
     * Generates a lightweight on-screen preview bitmap of the first imposed sheet (sheet 0).
     */
    fun renderFirstSheetPreview(
        context: Context,
        source: DocumentSource,
        kind: SupportedDocumentKind,
        pagesPerSheet: PagesPerSheet,
        paperSize: PaperSize,
        drawCellBorders: Boolean,
        margins: PageMargins = PageMargins.DEFAULT,
        scaling: PrintScaling = PrintScaling.AUTO_FIT,
        orientation: PrintOrientation = PrintOrientation.PORTRAIT,
        panX: Float = 0f,
        panY: Float = 0f,
    ): Bitmap? {
        var previewCopy: Bitmap? = null
        renderImposedSheets(
            context = context,
            source = source,
            kind = kind,
            pagesPerSheet = pagesPerSheet,
            paperSize = paperSize,
            targetDpi = 96,
            drawCellBorders = drawCellBorders,
            forPhysicalPortraitFeed = false,
            margins = margins,
            scaling = scaling,
            orientation = orientation,
            maxSheets = 1,
            panX = panX,
            panY = panY,
        ) { _, _, sheetBitmap ->
            previewCopy = sheetBitmap.copy(Bitmap.Config.RGB_565, false)
        }
        return previewCopy
    }

    private fun openPdfDescriptor(context: Context, source: DocumentSource): ParcelFileDescriptor =
        when (source) {
            is DocumentSource.LocalFile -> ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY)
            is DocumentSource.ContentUri -> context.contentResolver.openFileDescriptor(source.uri, "r")
                ?: throw IllegalStateException("Unable to open PDF file descriptor")
        }

    private fun openDocumentStream(context: Context, source: DocumentSource): java.io.InputStream =
        when (source) {
            is DocumentSource.LocalFile -> source.file.inputStream()
            is DocumentSource.ContentUri -> context.contentResolver.openInputStream(source.uri)
                ?: throw IllegalStateException("Unable to open document input stream")
        }

    private fun queryUriSize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun computeCellRenderDpi(targetDpi: Int, pagesPerSheet: PagesPerSheet): Int {
        val divisor = when (pagesPerSheet) {
            PagesPerSheet.ONE -> 1.0f
            PagesPerSheet.TWO -> 1.4f
            PagesPerSheet.FOUR -> 1.9f
            PagesPerSheet.EIGHT -> 2.6f
            PagesPerSheet.SIXTEEN -> 3.6f
        }
        return (targetDpi / divisor).toInt().coerceAtLeast(72)
    }

    /**
     * Renders a high-resolution 1-Up PDF page in horizontal strips (<= 1600 px tall each) using a
     * single reusable ARGB_8888 strip bitmap so 600 DPI rendering never doubles peak heap usage.
     */
    private fun renderPdfPageStrippedOntoCanvas(
        page: PdfRenderer.Page,
        targetCanvas: Canvas,
        destRect: RectF,
        clipRect: RectF,
        paint: Paint,
    ) {
        val visibleRect = RectF()
        if (!visibleRect.setIntersect(destRect, clipRect)) return

        val destW = ceil(destRect.width()).toInt().coerceAtLeast(1)
        val destH = ceil(destRect.height()).toInt().coerceAtLeast(1)
        val scaleX = destW.toFloat() / page.width.coerceAtLeast(1).toFloat()
        val scaleY = destH.toFloat() / page.height.coerceAtLeast(1).toFloat()

        val visLeftInDest = (visibleRect.left - destRect.left).coerceAtLeast(0f)
        val visTopInDest = (visibleRect.top - destRect.top).coerceAtLeast(0f)
        val visW = ceil(visibleRect.width()).toInt().coerceAtLeast(1)
        val visH = ceil(visibleRect.height()).toInt().coerceAtLeast(1)

        val stripHeight = min(PDF_STRIP_MAX_HEIGHT_PX, visH)
        val stripBitmap = Bitmap.createBitmap(visW, stripHeight, Bitmap.Config.ARGB_8888)
        val stripCanvas = Canvas(stripBitmap)
        val matrix = Matrix()
        val srcRect = Rect()
        val dstStripRect = RectF()

        try {
            targetCanvas.save()
            targetCanvas.clipRect(clipRect)
            var yOffset = 0
            while (yOffset < visH) {
                val h = min(stripHeight, visH - yOffset)
                stripCanvas.drawColor(Color.WHITE)
                matrix.reset()
                matrix.setScale(scaleX, scaleY)
                matrix.postTranslate(-visLeftInDest, -(visTopInDest + yOffset))

                val clip = Rect(0, 0, visW, h)
                page.render(stripBitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                srcRect.set(0, 0, visW, h)
                dstStripRect.set(
                    visibleRect.left,
                    visibleRect.top + yOffset,
                    visibleRect.left + visW,
                    visibleRect.top + yOffset + h,
                )
                targetCanvas.drawBitmap(stripBitmap, srcRect, dstStripRect, paint)
                yOffset += h
            }
            targetCanvas.restore()
        } finally {
            if (!stripBitmap.isRecycled) stripBitmap.recycle()
        }
    }

    private fun renderPdfPage(
        page: PdfRenderer.Page,
        maxW: Int,
        maxH: Int,
        dpi: Int,
    ): Bitmap {
        val scale = dpi / 72f
        val rawW = (page.width * scale).toInt().coerceAtLeast(1)
        val rawH = (page.height * scale).toInt().coerceAtLeast(1)
        val clampScale = min(1f, min(maxW.toFloat() / rawW, maxH.toFloat() / rawH))
        val outW = (rawW * clampScale).toInt().coerceAtLeast(1)
        val outH = (rawH * clampScale).toInt().coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        return bitmap
    }

    private fun decodeSafeImageBitmap(
        context: Context,
        source: DocumentSource,
        maxDimensionPx: Int,
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeWithSource(context, source, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        var inSampleSize = 1
        while (true) {
            val sampledW = boundsOpts.outWidth / inSampleSize
            val sampledH = boundsOpts.outHeight / inSampleSize
            val tooWide = sampledW > maxDimensionPx || sampledH > maxDimensionPx
            val tooManyPixels = sampledW.toLong() * sampledH.toLong() > MAX_DECODED_IMAGE_PIXELS
            if (!tooWide && !tooManyPixels) break
            inSampleSize *= 2
        }

        val finalPixels = (boundsOpts.outWidth / inSampleSize).toLong() * (boundsOpts.outHeight / inSampleSize).toLong()
        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            // Use RGB_565 on large (>12 MP) images to cut heap usage in half without hurting 1-bit monochrome output
            inPreferredConfig = if (finalPixels > 12_000_000L) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return decodeWithSource(context, source, decodeOpts)
    }

    private fun decodeWithSource(
        context: Context,
        source: DocumentSource,
        options: BitmapFactory.Options,
    ): Bitmap? = when (source) {
        is DocumentSource.LocalFile -> BitmapFactory.decodeFile(source.file.absolutePath, options)
        is DocumentSource.ContentUri -> context.contentResolver.openInputStream(source.uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> "${(bytes / 1024L).coerceAtLeast(1)} KB"
    }
}
