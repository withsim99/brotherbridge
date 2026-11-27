package com.example.scanner

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.model.RecentScanRecord
import com.example.model.ScanColorOption
import com.example.model.ScanExportMode
import com.example.model.ScanPageSizeOption
import com.example.model.ScanParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

data class ScanExportResult(
    val savedCount: Int,
    val summaryLocation: String,
    val primaryDisplayName: String,
    val recentRecord: RecentScanRecord? = null,
)

/**
 * Handles multi-page scan export (single combined PDF or separate JPEG/PNG images via zero-permission
 * Android Scoped Storage / MediaStore / SAF), persistent Recent Scans history, plus realistic
 * synthetic Demo scan & sample PDF generators.
 */
object ScanExportManager {

    const val LOST_AND_FOUND_COLLECTION_ID = "lost_and_found"
    const val LOST_AND_FOUND_TITLE = "Lost/Found"

    private val indexLock = Any()

    private fun recentScansDir(context: Context): File =
        File(context.filesDir, "recent_scans").apply { mkdirs() }

    private fun recentScansIndexFile(context: Context): File =
        File(recentScansDir(context), "index.json")

    private fun collectionsDir(context: Context): File =
        File(recentScansDir(context), "collections").apply { mkdirs() }

    /**
     * Rotates a bitmap clockwise by [degrees] (typically 90f).
     */
    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Loads persisted recent scan documents & scan collages saved in the app.
     */
    suspend fun loadRecentScans(context: Context): List<RecentScanRecord> = withContext(Dispatchers.IO) {
        loadRecentScansBlocking(context)
    }

    private fun loadRecentScansBlocking(context: Context): List<RecentScanRecord> {
        val indexFile = recentScansIndexFile(context)
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val thumb = obj.optString("thumbnailPath", "")
                    if (thumb.isNotBlank() && !File(thumb).exists()) continue
                    val pathsJson = obj.optJSONArray("pagePaths")
                    val pagePaths = buildList {
                        if (pathsJson != null) {
                            for (j in 0 until pathsJson.length()) {
                                val p = pathsJson.optString(j)
                                if (p.isNotBlank() && File(p).exists()) add(p)
                            }
                        }
                    }
                    add(
                        RecentScanRecord(
                            id = obj.optString("id"),
                            title = obj.optString("title"),
                            isPdf = obj.optBoolean("isPdf", true),
                            pageCount = obj.optInt("pageCount", pagePaths.size.coerceAtLeast(1)),
                            fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                            exportLocation = obj.optString("exportLocation", ""),
                            thumbnailPath = thumb,
                            localFilePath = obj.optString("localFilePath", "").ifBlank { null },
                            pagePaths = pagePaths,
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Decodes a JPEG/PNG image file safely downsampled so its largest edge does not exceed
     * [maxEdgePx], preventing OOM crashes on thumbnails or multi-page collage views.
     */
    fun decodeSampledBitmap(path: String, maxEdgePx: Int = 512): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var sampleSize = 1
            val targetMax = maxEdgePx.coerceAtLeast(64)
            while ((srcW / sampleSize) > targetMax * 2 || (srcH / sampleSize) > targetMax * 2) {
                sampleSize *= 2
            }
            val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            try {
                android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
            } catch (_: OutOfMemoryError) {
                decodeOpts.inSampleSize = sampleSize * 2
                try {
                    android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
                } catch (_: Throwable) {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Checks whether [file] is a readable image with valid non-zero dimensions without allocating
     * any pixel buffer in RAM.
     */
    private fun isValidImageFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Loads all page bitmaps from a saved scan collage so the user can view, append more scans,
     * rotate/delete pages, or export later. Automatically falls back to downsampled decoding if
     * memory is tight so opening large collages (such as Lost/Found) never crashes with OOM.
     */
    suspend fun loadCollectionPages(record: RecentScanRecord): List<Bitmap> = withContext(Dispatchers.IO) {
        val existingPaths = record.pagePaths.filter { File(it).exists() }
        val maxEdgePerPage = when {
            existingPaths.size >= 8 -> 1600
            existingPaths.size >= 4 -> 2200
            else -> 0 // full-res when few pages, with OOM fallback
        }
        if (existingPaths.isNotEmpty()) {
            existingPaths.mapNotNull { path ->
                if (maxEdgePerPage > 0) {
                    decodeSampledBitmap(path, maxEdgePx = maxEdgePerPage)
                } else {
                    try {
                        android.graphics.BitmapFactory.decodeFile(path)
                    } catch (_: OutOfMemoryError) {
                        decodeSampledBitmap(path, maxEdgePx = 1600)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }
        } else {
            val fallback = record.localFilePath?.takeIf {
                File(it).exists() && !it.endsWith(".pdf", ignoreCase = true)
            } ?: record.thumbnailPath
            listOfNotNull(
                try {
                    android.graphics.BitmapFactory.decodeFile(fallback)
                } catch (_: OutOfMemoryError) {
                    decodeSampledBitmap(fallback, maxEdgePx = 1600)
                } catch (_: Throwable) {
                    null
                },
            )
        }
    }

    /**
     * Automatically saves or updates a scanned multi-page collage inside the app's local library
     * when the user presses Done, so it immediately appears in Recent Scans and can be reopened
     * later to append more pages or export to PDF / images.
     */
    suspend fun saveOrUpdateScanCollection(
        context: Context,
        existingId: String?,
        title: String,
        pages: List<Bitmap>,
        isPdf: Boolean = true,
        exportLocation: String = "Saved in App",
    ): Result<RecentScanRecord> = withContext(Dispatchers.IO) {
        val validPages = pages.filterNot { it.isRecycled }
        if (validPages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No scanned pages to save"))
        }
        runCatching {
            val dir = recentScansDir(context)
            val id = existingId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            val colDir = File(collectionsDir(context), id).apply { mkdirs() }

            val savedPagePaths = mutableListOf<String>()
            val expectedNames = mutableSetOf<String>()
            var totalBytes = 0L
            validPages.forEachIndexed { idx, bmp ->
                val name = "page_%03d.jpg".format(Locale.US, idx + 1)
                expectedNames.add(name)
                val tmpPageFile = File(colDir, "$name.tmp")
                val pageFile = File(colDir, name)
                BufferedOutputStream(FileOutputStream(tmpPageFile), 64 * 1024).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                }
                if (pageFile.exists()) pageFile.delete()
                if (!tmpPageFile.renameTo(pageFile)) {
                    tmpPageFile.copyTo(pageFile, overwrite = true)
                    tmpPageFile.delete()
                }
                savedPagePaths.add(pageFile.absolutePath)
                totalBytes += pageFile.length()
            }

            // Build crisp single-page or multi-page collage preview thumbnail
            val thumbFile = File(dir, "thumb_$id.jpg")
            val thumbTmp = File(dir, "thumb_$id.jpg.tmp")
            val thumbBmp = buildCollageThumbnail(validPages)
            try {
                BufferedOutputStream(FileOutputStream(thumbTmp), 32 * 1024).use { out ->
                    thumbBmp.compress(Bitmap.CompressFormat.JPEG, 86, out)
                    out.flush()
                }
            } finally {
                thumbBmp.recycle()
            }
            if (thumbFile.exists()) thumbFile.delete()
            if (!thumbTmp.renameTo(thumbFile)) {
                thumbTmp.copyTo(thumbFile, overwrite = true)
                thumbTmp.delete()
            }

            // Maintain an up-to-date local PDF copy for instant direct printing (non-fatal on low memory so handover NEVER fails!)
            val pdfCopy = File(colDir, "document.pdf")
            val pdfTmp = File(colDir, "document.pdf.tmp")
            val pdfReady = runCatching {
                BufferedOutputStream(FileOutputStream(pdfTmp), 64 * 1024).use {
                    writePdfDocument(validPages, it, maxBitmapEdgePx = 2480)
                    it.flush()
                }
                if (pdfCopy.exists()) pdfCopy.delete()
                if (!pdfTmp.renameTo(pdfCopy)) {
                    pdfTmp.copyTo(pdfCopy, overwrite = true)
                    pdfTmp.delete()
                }
                pdfCopy.exists() && pdfCopy.length() > 0L
            }.getOrDefault(false)
            if (!pdfReady) {
                runCatching { pdfTmp.delete() }
            }

            // Remove any leftover stale pages from a larger previous version AFTER new files are safely committed
            expectedNames.add("document.pdf")
            colDir.listFiles()?.forEach { f ->
                if (f.name !in expectedNames) {
                    runCatching { f.delete() }
                }
            }

            val cleanTitle = title.ifBlank {
                "Scan_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}"
            }
            synchronized(indexLock) {
                val existingList = loadRecentScansBlocking(context)
                val previous = existingList.firstOrNull { it.id == id }
                val primaryLocalPath = if (pdfReady) pdfCopy.absolutePath else savedPagePaths.firstOrNull()
                val record = RecentScanRecord(
                    id = id,
                    title = cleanTitle,
                    isPdf = isPdf && pdfReady,
                    pageCount = validPages.size,
                    fileSizeBytes = (if (pdfReady) pdfCopy.length() else totalBytes).coerceAtLeast(totalBytes),
                    exportLocation = exportLocation.ifBlank { previous?.exportLocation ?: "Saved in App" },
                    thumbnailPath = thumbFile.absolutePath,
                    localFilePath = primaryLocalPath,
                    pagePaths = savedPagePaths,
                    createdAtMs = System.currentTimeMillis(),
                )
                val updated = (listOf(record) + existingList.filterNot { it.id == id }).take(60)
                persistRecentScansIndex(context, updated)
                record
            }
        }
    }

    /**
     * Appends undelivered remote scanned JPEG page files into the Host's persistent
     * `"Lost/Found"` collage directly on disk (zero full-resolution bitmap decoding in RAM),
     * building a compact downsampled collage thumbnail so startup or background recovery can never
     * trigger an `OutOfMemoryError` even with many high-DPI pages.
     */
    suspend fun appendPagesToLostAndFound(
        context: Context,
        pageFiles: List<File>,
        clientLabel: String,
    ): Result<RecentScanRecord> = withContext(Dispatchers.IO) {
        val validFiles = pageFiles.filter { isValidImageFile(it) }
        if (validFiles.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No valid page files to recover"))
        }
        try {
            val dir = recentScansDir(context)
            val colDir = File(collectionsDir(context), LOST_AND_FOUND_COLLECTION_ID).apply { mkdirs() }

            val existingPaths = synchronized(indexLock) {
                val existingRecord = loadRecentScansBlocking(context)
                    .firstOrNull { it.id == LOST_AND_FOUND_COLLECTION_ID }
                existingRecord?.pagePaths
                    ?.filter { p -> File(p).let { it.exists() && it.length() > 0L } }
                    .orEmpty()
            }

            // Pick a strictly increasing page file index that never collides with existing files
            var nextNum = existingPaths.mapNotNull { p ->
                Regex("""page_(\d+)\.jpg$""").find(File(p).name)?.groupValues?.get(1)?.toIntOrNull()
            }.maxOrNull()?.plus(1) ?: 1

            val copiedNewPaths = mutableListOf<String>()
            for (srcFile in validFiles) {
                var targetFile: File
                do {
                    val name = "page_%03d.jpg".format(Locale.US, nextNum++)
                    targetFile = File(colDir, name)
                } while (targetFile.exists())

                val tmpFile = File(colDir, "${targetFile.name}.tmp")
                srcFile.inputStream().buffered(64 * 1024).use { input ->
                    BufferedOutputStream(FileOutputStream(tmpFile), 64 * 1024).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                        output.flush()
                    }
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
                copiedNewPaths.add(targetFile.absolutePath)
            }

            val allPagePaths = existingPaths + copiedNewPaths
            val totalBytes = allPagePaths.sumOf { p -> File(p).length().coerceAtLeast(0L) }

            // Build 360x440 collage thumbnail using ONLY downsampled bitmaps (max 4 pages)
            val thumbFile = File(dir, "thumb_${LOST_AND_FOUND_COLLECTION_ID}.jpg")
            val thumbTmp = File(dir, "thumb_${LOST_AND_FOUND_COLLECTION_ID}.jpg.tmp")
            val previewThumbs = mutableListOf<Bitmap>()
            try {
                for (path in allPagePaths.take(4)) {
                    decodeSampledBitmap(path, maxEdgePx = 440)?.let { previewThumbs.add(it) }
                }
                if (previewThumbs.isNotEmpty()) {
                    val collageBmp = buildCollageThumbnail(previewThumbs)
                    try {
                        BufferedOutputStream(FileOutputStream(thumbTmp), 32 * 1024).use { out ->
                            collageBmp.compress(Bitmap.CompressFormat.JPEG, 84, out)
                            out.flush()
                        }
                    } finally {
                        collageBmp.recycle()
                    }
                    if (thumbFile.exists()) thumbFile.delete()
                    if (!thumbTmp.renameTo(thumbFile)) {
                        thumbTmp.copyTo(thumbFile, overwrite = true)
                        thumbTmp.delete()
                    }
                }
            } finally {
                previewThumbs.forEach { if (!it.isRecycled) it.recycle() }
                runCatching { if (thumbTmp.exists()) thumbTmp.delete() }
            }

            val locationTag = if (clientLabel.isNotBlank()) {
                "Host Recovery • $clientLabel"
            } else {
                "Host Recovery"
            }

            val savedRecord = synchronized(indexLock) {
                val existingList = loadRecentScansBlocking(context)
                val record = RecentScanRecord(
                    id = LOST_AND_FOUND_COLLECTION_ID,
                    title = LOST_AND_FOUND_TITLE,
                    isPdf = false,
                    pageCount = allPagePaths.size,
                    fileSizeBytes = totalBytes,
                    exportLocation = locationTag,
                    thumbnailPath = if (thumbFile.exists()) thumbFile.absolutePath else "",
                    localFilePath = allPagePaths.firstOrNull(),
                    pagePaths = allPagePaths,
                    createdAtMs = System.currentTimeMillis(),
                )
                val updated = (listOf(record) + existingList.filterNot { it.id == LOST_AND_FOUND_COLLECTION_ID }).take(60)
                persistRecentScansIndex(context, updated)
                record
            }
            Result.success(savedRecord)
        } catch (t: Throwable) {
            Result.failure(if (t is Exception) t else RuntimeException(t.message ?: t.javaClass.simpleName, t))
        }
    }

    suspend fun deleteRecentScan(context: Context, id: String): List<RecentScanRecord> =
        deleteRecentScans(context, setOf(id))

    suspend fun deleteRecentScans(context: Context, ids: Set<String>): List<RecentScanRecord> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext synchronized(indexLock) { loadRecentScansBlocking(context) }
        synchronized(indexLock) {
            val current = loadRecentScansBlocking(context)
            current.filter { it.id in ids }.forEach { rec ->
                runCatching { File(rec.thumbnailPath).delete() }
                rec.localFilePath?.let { path -> runCatching { File(path).delete() } }
                rec.pagePaths.forEach { p -> runCatching { File(p).delete() } }
                runCatching { File(collectionsDir(context), rec.id).deleteRecursively() }
            }
            val remaining = current.filterNot { it.id in ids }
            persistRecentScansIndex(context, remaining)
            remaining
        }
    }

    /**
     * Generates a clean thumbnail: single page if 1 page, or a crisp 2-up / 2x2 collage grid
     * when multiple pages exist in the saved scan collection.
     */
    private fun buildCollageThumbnail(pages: List<Bitmap>): Bitmap {
        val outW = 360
        val outH = 440
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.rgb(241, 245, 249))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        fun drawFitCell(bmp: Bitmap, dest: RectF) {
            val bgPaint = Paint().apply { color = Color.WHITE }
            canvas.drawRect(dest, bgPaint)
            val srcRatio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
            val dstRatio = dest.width() / dest.height().coerceAtLeast(1f)
            val srcRect = if (srcRatio > dstRatio) {
                val cropW = (bmp.height * dstRatio).roundToInt().coerceAtMost(bmp.width)
                val left = (bmp.width - cropW) / 2
                Rect(left, 0, left + cropW, bmp.height)
            } else {
                val cropH = (bmp.width / dstRatio).roundToInt().coerceAtMost(bmp.height)
                val top = (bmp.height - cropH) / 2
                Rect(0, top, bmp.width, top + cropH)
            }
            canvas.drawBitmap(bmp, srcRect, dest, paint)
            canvas.drawRect(dest, borderPaint)
        }

        val pad = 8f
        val gap = 6f
        when (pages.size) {
            1 -> {
                drawFitCell(pages[0], RectF(0f, 0f, outW.toFloat(), outH.toFloat()))
            }
            2 -> {
                val cellW = (outW - pad * 2f - gap) / 2f
                val cellH = outH - pad * 2f
                drawFitCell(pages[0], RectF(pad, pad, pad + cellW, pad + cellH))
                drawFitCell(pages[1], RectF(pad + cellW + gap, pad, pad + cellW * 2f + gap, pad + cellH))
            }
            else -> {
                val cellW = (outW - pad * 2f - gap) / 2f
                val cellH = (outH - pad * 2f - gap) / 2f
                val visible = pages.take(4)
                visible.forEachIndexed { i, bmp ->
                    val col = i % 2
                    val row = i / 2
                    val l = pad + col * (cellW + gap)
                    val t = pad + row * (cellH + gap)
                    drawFitCell(bmp, RectF(l, t, l + cellW, t + cellH))
                }
            }
        }
        return result
    }

    private fun persistRecentScansIndex(context: Context, list: List<RecentScanRecord>) {
        val arr = JSONArray()
        list.forEach { rec ->
            arr.put(
                JSONObject().apply {
                    put("id", rec.id)
                    put("title", rec.title)
                    put("isPdf", rec.isPdf)
                    put("pageCount", rec.pageCount)
                    put("fileSizeBytes", rec.fileSizeBytes)
                    put("exportLocation", rec.exportLocation)
                    put("thumbnailPath", rec.thumbnailPath)
                    put("localFilePath", rec.localFilePath ?: "")
                    put("pagePaths", JSONArray(rec.pagePaths))
                    put("createdAtMs", rec.createdAtMs)
                },
            )
        }
        val target = recentScansIndexFile(context)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(arr.toString())
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Exports [pages] either as one combined multi-page PDF or as individual JPG / PNG / PDF files
     * using Android Scoped Storage (zero runtime storage permissions required).
     * When [isSubsetExport] is true (user exported only selected pages), we don't overwrite the full
     * collage stored in Recent Scans.
     */
    suspend fun exportPages(
        context: Context,
        pages: List<Bitmap>,
        baseName: String,
        mode: ScanExportMode,
        existingCollectionId: String? = null,
        isSubsetExport: Boolean = false,
        pageSizes: List<ScanPageSizeOption>? = null,
    ): Result<ScanExportResult> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No scanned pages to export"))
        }
        val cleanBase = sanitizeFileName(baseName).ifBlank {
            "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}"
        }
        runCatching {
            when (mode) {
                ScanExportMode.SINGLE_PDF -> {
                    val fileName = "$cleanBase.pdf"
                    val location = savePdfToStorage(context, pages, fileName, pageSizes)
                    val rec = if (!isSubsetExport) {
                        saveOrUpdateScanCollection(
                            context = context,
                            existingId = existingCollectionId,
                            title = cleanBase,
                            pages = pages,
                            isPdf = true,
                            exportLocation = location,
                        ).getOrNull()
                    } else null
                    ScanExportResult(
                        savedCount = 1,
                        summaryLocation = location,
                        primaryDisplayName = fileName,
                        recentRecord = rec,
                    )
                }
                ScanExportMode.SEPARATE_JPEG -> {
                    val location = saveSeparateImages(
                        context = context,
                        pages = pages,
                        baseName = cleanBase,
                        format = Bitmap.CompressFormat.JPEG,
                        extension = "jpg",
                        mimeType = "image/jpeg",
                    )
                    val rec = if (!isSubsetExport) {
                        saveOrUpdateScanCollection(
                            context = context,
                            existingId = existingCollectionId,
                            title = cleanBase,
                            pages = pages,
                            isPdf = false,
                            exportLocation = location,
                        ).getOrNull()
                    } else null
                    ScanExportResult(
                        savedCount = pages.size,
                        summaryLocation = location,
                        primaryDisplayName = "${cleanBase}_page1.jpg",
                        recentRecord = rec,
                    )
                }
                ScanExportMode.SEPARATE_PNG -> {
                    val location = saveSeparateImages(
                        context = context,
                        pages = pages,
                        baseName = cleanBase,
                        format = Bitmap.CompressFormat.PNG,
                        extension = "png",
                        mimeType = "image/png",
                    )
                    val rec = if (!isSubsetExport) {
                        saveOrUpdateScanCollection(
                            context = context,
                            existingId = existingCollectionId,
                            title = cleanBase,
                            pages = pages,
                            isPdf = false,
                            exportLocation = location,
                        ).getOrNull()
                    } else null
                    ScanExportResult(
                        savedCount = pages.size,
                        summaryLocation = location,
                        primaryDisplayName = "${cleanBase}_page1.png",
                        recentRecord = rec,
                    )
                }
                ScanExportMode.SEPARATE_PDF -> {
                    pages.forEachIndexed { idx, bmp ->
                        val singleSize = pageSizes?.getOrNull(idx)?.let { listOf(it) }
                        savePdfToStorage(context, listOf(bmp), "${cleanBase}_page${idx + 1}.pdf", singleSize)
                    }
                    val location = "Downloads/PrintBridge/ (${pages.size} PDF file${if (pages.size == 1) "" else "s"})"
                    val rec = if (!isSubsetExport) {
                        saveOrUpdateScanCollection(
                            context = context,
                            existingId = existingCollectionId,
                            title = cleanBase,
                            pages = pages,
                            isPdf = true,
                            exportLocation = location,
                        ).getOrNull()
                    } else null
                    ScanExportResult(
                        savedCount = pages.size,
                        summaryLocation = location,
                        primaryDisplayName = "${cleanBase}_page1.pdf",
                        recentRecord = rec,
                    )
                }
            }
        }
    }

    /**
     * Creates a cached PDF containing [pages] for direct printing (e.g., printing selected scan pages).
     */
    suspend fun createTempPdfForPrint(
        context: Context,
        pages: List<Bitmap>,
        baseName: String,
        pageSizes: List<ScanPageSizeOption>? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("No pages to print"))
        }
        runCatching {
            val dir = File(context.cacheDir, "print_selection").apply { mkdirs() }
            val safe = sanitizeFileName(baseName).ifBlank { "Selected_Scan" }
            val outFile = File(dir, "${safe}_${System.currentTimeMillis()}.pdf")
            FileOutputStream(outFile).use { writePdfDocument(pages, it, pageSizes) }
            outFile
        }
    }

    /**
     * Writes [pages] as a single multi-page PDF directly to a user-chosen SAF [Uri].
     */
    suspend fun writeSinglePdfToUri(
        context: Context,
        pages: List<Bitmap>,
        targetUri: Uri,
        displayTitle: String = "Scanned_Document",
        existingCollectionId: String? = null,
        isSubsetExport: Boolean = false,
        pageSizes: List<ScanPageSizeOption>? = null,
    ): Result<RecentScanRecord?> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                writePdfDocument(pages, out, pageSizes)
            } ?: throw IllegalStateException("Could not open selected destination")
            val cleanBase = displayTitle.removeSuffix(".pdf").ifBlank { "Scanned_Document" }
            if (!isSubsetExport) {
                saveOrUpdateScanCollection(
                    context = context,
                    existingId = existingCollectionId,
                    title = cleanBase,
                    pages = pages,
                    isPdf = true,
                    exportLocation = "Saved PDF ($cleanBase.pdf)",
                ).getOrNull()
            } else null
        }
    }

    private fun savePdfToStorage(
        context: Context,
        pages: List<Bitmap>,
        fileName: String,
        pageSizes: List<ScanPageSizeOption>? = null,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PrintBridge")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create PDF in Downloads/PrintBridge")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    writePdfDocument(pages, out, pageSizes)
                } ?: throw IllegalStateException("Unable to write PDF stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Downloads/PrintBridge/$fileName"
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
                "PrintBridge",
            ).apply { mkdirs() }
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out -> writePdfDocument(pages, out, pageSizes) }
            return outFile.absolutePath
        }
    }

    private fun saveSeparateImages(
        context: Context,
        pages: List<Bitmap>,
        baseName: String,
        format: Bitmap.CompressFormat,
        extension: String,
        mimeType: String,
    ): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            pages.forEachIndexed { idx, bitmap ->
                val name = "${baseName}_page${idx + 1}.$extension"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PrintBridge")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Unable to save $name")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(format, 92, out)
                    } ?: throw IllegalStateException("Unable to open output stream for $name")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
            }
            return "Pictures/PrintBridge/ (${pages.size} file${if (pages.size == 1) "" else "s"})"
        } else {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir,
                "PrintBridge",
            ).apply { mkdirs() }
            pages.forEachIndexed { idx, bitmap ->
                val outFile = File(dir, "${baseName}_page${idx + 1}.$extension")
                FileOutputStream(outFile).use { out -> bitmap.compress(format, 92, out) }
            }
            return "${dir.absolutePath} (${pages.size} files)"
        }
    }

    private fun writePdfDocument(
        pages: List<Bitmap>,
        out: OutputStream,
        pageSizes: List<ScanPageSizeOption>? = null,
        maxBitmapEdgePx: Int = 0,
    ) {
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, rawBitmap ->
                if (rawBitmap.isRecycled) return@forEachIndexed
                var scaledCopy: Bitmap? = null
                val bitmap = if (maxBitmapEdgePx > 0 && maxOf(rawBitmap.width, rawBitmap.height) > maxBitmapEdgePx) {
                    val ratio = maxBitmapEdgePx.toFloat() / maxOf(rawBitmap.width, rawBitmap.height).toFloat()
                    val dw = (rawBitmap.width * ratio).roundToInt().coerceAtLeast(1)
                    val dh = (rawBitmap.height * ratio).roundToInt().coerceAtLeast(1)
                    runCatching { Bitmap.createScaledBitmap(rawBitmap, dw, dh, true) }
                        .getOrNull()
                        ?.also { scaledCopy = it }
                        ?: rawBitmap
                } else {
                    rawBitmap
                }
                try {
                    val targetSize = pageSizes?.getOrNull(index) ?: ScanPageSizeOption.A4
                    val pageW = targetSize.widthPdfPoints
                    val pageH = targetSize.heightPdfPoints
                    val isLandscape = bitmap.width > bitmap.height
                    val w = if (isLandscape) pageH else pageW
                    val h = if (isLandscape) pageW else pageH
                    val pageInfo = PdfDocument.PageInfo.Builder(w, h, index + 1).create()
                    val page = pdf.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    val margin = 8f
                    val availW = w - margin * 2f
                    val availH = h - margin * 2f
                    val scale = min(availW / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
                    val drawW = bitmap.width * scale
                    val drawH = bitmap.height * scale
                    val left = (w - drawW) / 2f
                    val top = (h - drawH) / 2f

                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    canvas.drawBitmap(
                        bitmap,
                        Rect(0, 0, bitmap.width, bitmap.height),
                        RectF(left, top, left + drawW, top + drawH),
                        paint,
                    )
                    pdf.finishPage(page)
                } finally {
                    scaledCopy?.let { if (!it.isRecycled && it !== rawBitmap) it.recycle() }
                }
            }
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }

    private fun sanitizeFileName(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
}
