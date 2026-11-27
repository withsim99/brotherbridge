package com.example.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.example.model.IdCardMergeLayout
import com.example.model.ScanPageSizeOption
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-Kotlin placement geometry for a 2-in-1 ID card sheet (testable without Android framework).
 */
data class TwoInOneSheetLayout(
    val canvasWidth: Int,
    val canvasHeight: Int,
    val frontLeft: Float,
    val frontTop: Float,
    val frontRight: Float,
    val frontBottom: Float,
    val backLeft: Float,
    val backTop: Float,
    val backRight: Float,
    val backBottom: Float,
) {
    val frontWidth: Float get() = frontRight - frontLeft
    val frontHeight: Float get() = frontBottom - frontTop
    val backWidth: Float get() = backRight - backLeft
    val backHeight: Float get() = backBottom - backTop
}

/**
 * Automatic ID Card Cropping & 2-in-1 Sheet Compositor.
 *
 * 1. Auto-detects each ID card boundary on the flatbed glass (even if placed at a random angle)
 *    via [ScanDocumentCropper.detectDocumentQuad] and straightens it via [ScanDocumentCropper.warpPerspective].
 * 2. Harmonizes Front/Back card orientation if one side was placed rotated 90° relative to the other.
 * 3. Places both cropped IDs cleanly in the chosen [IdCardMergeLayout] (Vertical top-and-bottom or
 *    Horizontal side-by-side) onto a crisp white sheet matching the selected [ScanPageSizeOption]
 *    (A4, A3, Letter, A5, or Card/Half).
 */
object ScanIdCardMerger {

    const val DEFAULT_MAX_CANVAS_DIMENSION = 3200

    /**
     * Computes exact pixel canvas dimensions matching [pageSize] physical aspect ratio while
     * staying within [maxCanvasDimension] to keep memory usage safe at high DPI (e.g., A3 @ 2400 DPI).
     */
    fun computeSheetPixelSize(
        pageSize: ScanPageSizeOption,
        dpi: Int,
        maxCanvasDimension: Int = DEFAULT_MAX_CANVAS_DIMENSION,
    ): Pair<Int, Int> {
        val safeDpi = dpi.coerceIn(150, 2400)
        val rawW = ((pageSize.widthMm / 25.4) * safeDpi).roundToInt().coerceAtLeast(300)
        val rawH = ((pageSize.heightMm / 25.4) * safeDpi).roundToInt().coerceAtLeast(300)
        val longest = max(rawW, rawH)
        return if (longest <= maxCanvasDimension) {
            rawW to rawH
        } else {
            val scale = maxCanvasDimension.toFloat() / longest.toFloat()
            val scaledW = (rawW * scale).roundToInt().coerceAtLeast(300)
            val scaledH = (rawH * scale).roundToInt().coerceAtLeast(300)
            scaledW to scaledH
        }
    }

    /**
     * Pure-math placement engine computing symmetric, non-overlapping, margin-safe target rectangles
     * for Front and Back cards inside the selected output sheet.
     */
    fun computeLayout(
        frontWidthPx: Int,
        frontHeightPx: Int,
        backWidthPx: Int,
        backHeightPx: Int,
        pageSize: ScanPageSizeOption,
        layout: IdCardMergeLayout,
        dpi: Int,
        maxCanvasDimension: Int = DEFAULT_MAX_CANVAS_DIMENSION,
    ): TwoInOneSheetLayout {
        val (canvasW, canvasH) = computeSheetPixelSize(pageSize, dpi, maxCanvasDimension)
        val fwSrc = frontWidthPx.coerceAtLeast(1).toFloat()
        val fhSrc = frontHeightPx.coerceAtLeast(1).toFloat()
        val bwSrc = backWidthPx.coerceAtLeast(1).toFloat()
        val bhSrc = backHeightPx.coerceAtLeast(1).toFloat()

        val marginX = canvasW * 0.065f
        val marginY = canvasH * 0.065f
        val gap = min(canvasW, canvasH) * 0.045f
        val availW = (canvasW - 2f * marginX).coerceAtLeast(64f)
        val availH = (canvasH - 2f * marginY).coerceAtLeast(64f)

        val frontAspect = fwSrc / fhSrc
        val backAspect = bwSrc / bhSrc
        val similarAspect = (max(frontAspect, backAspect) / min(frontAspect, backAspect)) <= 1.25f

        return when (layout) {
            IdCardMergeLayout.VERTICAL -> {
                val slotMaxW = availW * 0.86f
                val slotMaxH = ((availH - gap) / 2f).coerceAtLeast(32f)

                var (fw, fh) = fitInside(fwSrc, fhSrc, slotMaxW, slotMaxH)
                var (bw, bh) = fitInside(bwSrc, bhSrc, slotMaxW, slotMaxH)

                // Match both cards cleanly to the same visual scale when they have similar aspect ratios
                if (similarAspect) {
                    val unifiedW = min(fw, bw)
                    val candidateFh = unifiedW / frontAspect
                    val candidateBh = unifiedW / backAspect
                    if (candidateFh <= slotMaxH && candidateBh <= slotMaxH) {
                        fw = unifiedW
                        fh = candidateFh
                        bw = unifiedW
                        bh = candidateBh
                    }
                }

                val totalH = fh + gap + bh
                val startY = (canvasH - totalH) / 2f
                val fLeft = (canvasW - fw) / 2f
                val bLeft = (canvasW - bw) / 2f

                TwoInOneSheetLayout(
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    frontLeft = fLeft,
                    frontTop = startY,
                    frontRight = fLeft + fw,
                    frontBottom = startY + fh,
                    backLeft = bLeft,
                    backTop = startY + fh + gap,
                    backRight = bLeft + bw,
                    backBottom = startY + fh + gap + bh,
                )
            }

            IdCardMergeLayout.HORIZONTAL -> {
                val slotMaxW = ((availW - gap) / 2f).coerceAtLeast(32f)
                val slotMaxH = availH * 0.86f

                var (fw, fh) = fitInside(fwSrc, fhSrc, slotMaxW, slotMaxH)
                var (bw, bh) = fitInside(bwSrc, bhSrc, slotMaxW, slotMaxH)

                if (similarAspect) {
                    val unifiedW = min(fw, bw)
                    val candidateFh = unifiedW / frontAspect
                    val candidateBh = unifiedW / backAspect
                    if (candidateFh <= slotMaxH && candidateBh <= slotMaxH) {
                        fw = unifiedW
                        fh = candidateFh
                        bw = unifiedW
                        bh = candidateBh
                    }
                }

                val totalW = fw + gap + bw
                val startX = (canvasW - totalW) / 2f
                val fTop = (canvasH - fh) / 2f
                val bTop = (canvasH - bh) / 2f

                TwoInOneSheetLayout(
                    canvasWidth = canvasW,
                    canvasHeight = canvasH,
                    frontLeft = startX,
                    frontTop = fTop,
                    frontRight = startX + fw,
                    frontBottom = fTop + fh,
                    backLeft = startX + fw + gap,
                    backTop = bTop,
                    backRight = startX + fw + gap + bw,
                    backBottom = bTop + bh,
                )
            }
        }
    }

    /**
     * Auto-crops both raw flatbed ID scans (straightening any arbitrary tilt), harmonizes their
     * orientation, and merges them into a single crisp sheet matching [pageSize] and [layout].
     */
    fun autoCropAndMergeIdCards(
        frontRaw: Bitmap,
        backRaw: Bitmap,
        pageSize: ScanPageSizeOption,
        layout: IdCardMergeLayout,
        dpi: Int,
        maxCanvasDimension: Int = DEFAULT_MAX_CANVAS_DIMENSION,
    ): Bitmap {
        val croppedFront = autoCropSingleSide(frontRaw)
        var croppedBack = autoCropSingleSide(backRaw)

        // If one side came out landscape and the other portrait (e.g., card rotated 90° on flip),
        // align Side 2 to match Side 1's orientation so both form a clean symmetric pair.
        val frontIsLandscape = croppedFront.width > croppedFront.height * 1.12f
        val frontIsPortrait = croppedFront.height > croppedFront.width * 1.12f
        val backIsLandscape = croppedBack.width > croppedBack.height * 1.12f
        val backIsPortrait = croppedBack.height > croppedBack.width * 1.12f

        if ((frontIsLandscape && backIsPortrait) || (frontIsPortrait && backIsLandscape)) {
            val alignedBack = ScanExportManager.rotateBitmap(croppedBack, 90f)
            if (croppedBack !== backRaw && !croppedBack.isRecycled) {
                croppedBack.recycle()
            }
            croppedBack = alignedBack
        }

        var placement = computeLayout(
            frontWidthPx = croppedFront.width,
            frontHeightPx = croppedFront.height,
            backWidthPx = croppedBack.width,
            backHeightPx = croppedBack.height,
            pageSize = pageSize,
            layout = layout,
            dpi = dpi,
            maxCanvasDimension = maxCanvasDimension,
        )

        val sheet = try {
            Bitmap.createBitmap(
                placement.canvasWidth,
                placement.canvasHeight,
                Bitmap.Config.ARGB_8888,
            )
        } catch (_: OutOfMemoryError) {
            placement = computeLayout(
                frontWidthPx = croppedFront.width,
                frontHeightPx = croppedFront.height,
                backWidthPx = croppedBack.width,
                backHeightPx = croppedBack.height,
                pageSize = pageSize,
                layout = layout,
                dpi = dpi,
                maxCanvasDimension = min(maxCanvasDimension, 2000),
            )
            Bitmap.createBitmap(
                placement.canvasWidth,
                placement.canvasHeight,
                Bitmap.Config.RGB_565,
            )
        }
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            croppedFront,
            Rect(0, 0, croppedFront.width, croppedFront.height),
            RectF(placement.frontLeft, placement.frontTop, placement.frontRight, placement.frontBottom),
            paint,
        )
        canvas.drawBitmap(
            croppedBack,
            Rect(0, 0, croppedBack.width, croppedBack.height),
            RectF(placement.backLeft, placement.backTop, placement.backRight, placement.backBottom),
            paint,
        )

        if (croppedFront !== frontRaw && !croppedFront.isRecycled) {
            croppedFront.recycle()
        }
        if (croppedBack !== backRaw && !croppedBack.isRecycled) {
            croppedBack.recycle()
        }

        return sheet
    }

    /**
     * Identifies and auto-crops the primary object on each selected page (removing excess flatbed
     * background and straightening tilt via [ScanDocumentCropper]), then composites all detected
     * objects cleanly onto a single new white sheet matching [pageSize].
     *
     * - For 2 pages: harmonizes orientation and uses Vertical (top & bottom) for landscape/card
     *   objects or Horizontal (side-by-side) when both objects are tall portrait strips.
     * - For 3+ pages: lays out all detected objects in a symmetric multi-cell grid.
     */
    fun autoCropAndMergePages(
        rawPages: List<Bitmap>,
        pageSize: ScanPageSizeOption,
        dpi: Int,
        maxCanvasDimension: Int = DEFAULT_MAX_CANVAS_DIMENSION,
    ): Bitmap {
        require(rawPages.size >= 2) { "Select at least 2 pages to merge" }

        if (rawPages.size == 2) {
            val front = autoCropSingleSide(rawPages[0])
            val back = autoCropSingleSide(rawPages[1])
            val bothTallPortrait = front.height > front.width * 1.15f && back.height > back.width * 1.15f
            if (front !== rawPages[0] && !front.isRecycled) front.recycle()
            if (back !== rawPages[1] && !back.isRecycled) back.recycle()
            val bestLayout = if (bothTallPortrait) {
                IdCardMergeLayout.HORIZONTAL
            } else {
                IdCardMergeLayout.VERTICAL
            }
            return autoCropAndMergeIdCards(
                frontRaw = rawPages[0],
                backRaw = rawPages[1],
                pageSize = pageSize,
                layout = bestLayout,
                dpi = dpi,
                maxCanvasDimension = maxCanvasDimension,
            )
        }

        val croppedObjects = rawPages.map { autoCropSingleSide(it) }
        try {
            val count = croppedObjects.size
            val cols = kotlin.math.ceil(kotlin.math.sqrt(count.toDouble())).toInt().coerceAtLeast(2)
            val rows = kotlin.math.ceil(count.toDouble() / cols.toDouble()).toInt().coerceAtLeast(1)

            var (canvasW, canvasH) = computeSheetPixelSize(pageSize, dpi, maxCanvasDimension)
            val sheet = try {
                Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                val fallback = computeSheetPixelSize(pageSize, dpi, min(maxCanvasDimension, 2000))
                canvasW = fallback.first
                canvasH = fallback.second
                Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.RGB_565)
            }

            val canvas = Canvas(sheet)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            val marginX = canvasW * 0.055f
            val marginY = canvasH * 0.055f
            val gap = min(canvasW, canvasH) * 0.032f
            val cellW = ((canvasW - 2f * marginX - gap * (cols - 1)) / cols).coerceAtLeast(32f)
            val cellH = ((canvasH - 2f * marginY - gap * (rows - 1)) / rows).coerceAtLeast(32f)

            croppedObjects.forEachIndexed { idx, bmp ->
                val row = idx / cols
                val col = idx % cols
                val cellLeft = marginX + col * (cellW + gap)
                val cellTop = marginY + row * (cellH + gap)
                val (drawW, drawH) = fitInside(bmp.width.toFloat(), bmp.height.toFloat(), cellW, cellH)
                val dstLeft = cellLeft + (cellW - drawW) / 2f
                val dstTop = cellTop + (cellH - drawH) / 2f
                canvas.drawBitmap(
                    bmp,
                    Rect(0, 0, bmp.width, bmp.height),
                    RectF(dstLeft, dstTop, dstLeft + drawW, dstTop + drawH),
                    paint,
                )
            }
            return sheet
        } finally {
            croppedObjects.forEachIndexed { idx, bmp ->
                if (bmp !== rawPages[idx] && !bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    private fun autoCropSingleSide(raw: Bitmap): Bitmap {
        return try {
            val detection = ScanDocumentCropper.detectDocumentQuad(raw)
            if (detection.wasAutoDetected) {
                ScanDocumentCropper.warpPerspective(raw, detection.quad, maxOutputDimension = 2400)
            } else {
                raw
            }
        } catch (_: Throwable) {
            raw
        }
    }

    private fun fitInside(srcW: Float, srcH: Float, maxW: Float, maxH: Float): Pair<Float, Float> {
        val scale = min(maxW / srcW.coerceAtLeast(1f), maxH / srcH.coerceAtLeast(1f))
        return (srcW * scale) to (srcH * scale)
    }
}
