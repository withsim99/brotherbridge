package com.example.printer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.model.DuplexMode
import com.example.model.NUpPresentationDirection
import com.example.model.PageMargins
import com.example.model.PagesPerSheet
import com.example.model.PaperSize
import com.example.model.PrintOrientation
import com.example.model.PrintScaling
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class SheetLayoutSpec(
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val logicalWidth: Int,
    val logicalHeight: Int,
    val rotationDegrees: Int,
    val cellRects: List<RectF>,
    val innerPad: Float,
    val borderStrokeWidth: Float,
) {
    val rotate90Clockwise: Boolean get() = rotationDegrees == 90
}

object NUpLayoutEngine {

    /**
     * Resolves 1-based IPP [pageRanges] and [reverseOrder] into an ordered list of 0-based page indices.
     * Empty [pageRanges] selects all pages `0 until totalPages`.
     */
    fun resolvePageIndices(
        totalPages: Int,
        pageRanges: List<IntRange>,
        reverseOrder: Boolean,
    ): List<Int> {
        if (totalPages <= 0) return emptyList()
        val selected = if (pageRanges.isEmpty()) {
            (0 until totalPages).toList()
        } else {
            val ordered = LinkedHashSet<Int>()
            for (range in pageRanges) {
                val start = (range.first - 1).coerceIn(0, totalPages - 1)
                val end = (range.last - 1).coerceIn(0, totalPages - 1)
                if (start <= end) {
                    for (idx in start..end) {
                        ordered.add(idx)
                    }
                }
            }
            if (ordered.isEmpty()) (0 until totalPages).toList() else ordered.toList()
        }
        return if (reverseOrder) selected.asReversed() else selected
    }

    /**
     * Number of imposed page sides after grouping [sourcePageCount] pages by [pagesPerSheet].
     */
    fun calculateImposedSides(sourcePageCount: Int, pagesPerSheet: PagesPerSheet): Int {
        if (sourcePageCount <= 0) return 0
        return ceil(sourcePageCount.toDouble() / pagesPerSheet.count.toDouble()).toInt()
    }

    /**
     * Number of physical paper sheets required taking duplex mode into account.
     */
    fun calculatePhysicalSheets(imposedSides: Int, duplexMode: DuplexMode): Int {
        if (imposedSides <= 0) return 0
        return when (duplexMode) {
            DuplexMode.SIMPLEX -> imposedSides
            DuplexMode.LONG_EDGE, DuplexMode.SHORT_EDGE -> ceil(imposedSides / 2.0).toInt()
        }
    }

    /**
     * Computes exact logical sheet dimensions, per-edge margin bounds, N-Up cell rectangles in
     * the requested [nUpDirection], and physical canvas rotation for [orientation].
     */
    fun computeSheetLayout(
        pagesPerSheet: PagesPerSheet,
        paperSize: PaperSize,
        targetDpi: Int,
        drawCellBorders: Boolean,
        forPhysicalPortraitFeed: Boolean,
        margins: PageMargins = PageMargins.DEFAULT,
        orientation: PrintOrientation = PrintOrientation.PORTRAIT,
        nUpDirection: NUpPresentationDirection = NUpPresentationDirection.TO_RIGHT_TO_BOTTOM,
    ): SheetLayoutSpec {
        val portraitWidth = paperSize.widthPx(targetDpi).coerceAtLeast(120)
        val portraitHeight = paperSize.heightPx(targetDpi).coerceAtLeast(160)

        val effectiveLandscape = when (orientation) {
            PrintOrientation.LANDSCAPE, PrintOrientation.REVERSE_LANDSCAPE -> true
            PrintOrientation.PORTRAIT, PrintOrientation.REVERSE_PORTRAIT, PrintOrientation.AUTO ->
                pagesPerSheet.preferLandscapeSheet
        }

        val logicalWidth = if (effectiveLandscape) portraitHeight else portraitWidth
        val logicalHeight = if (effectiveLandscape) portraitWidth else portraitHeight

        val rotationDegrees = if (!forPhysicalPortraitFeed) {
            0
        } else {
            when (orientation) {
                PrintOrientation.LANDSCAPE -> 90
                PrintOrientation.REVERSE_LANDSCAPE -> 270
                PrintOrientation.REVERSE_PORTRAIT -> if (pagesPerSheet.preferLandscapeSheet) 270 else 180
                PrintOrientation.PORTRAIT, PrintOrientation.AUTO -> if (pagesPerSheet.preferLandscapeSheet) 90 else 0
            }
        }

        val swapOutputAxes = rotationDegrees == 90 || rotationDegrees == 270
        val bitmapWidth = if (swapOutputAxes) portraitWidth else logicalWidth
        val bitmapHeight = if (swapOutputAxes) portraitHeight else logicalHeight

        // Convert client-specified per-edge margins (mm) into pixels at targetDpi, safely clamped
        val pxPerMm = targetDpi / 25.4f
        val maxHorizontalMargin = logicalWidth * 0.35f
        val maxVerticalMargin = logicalHeight * 0.35f
        val leftMargin = (margins.leftMm * pxPerMm).coerceIn(0f, maxHorizontalMargin)
        val rightMargin = (margins.rightMm * pxPerMm).coerceIn(0f, maxHorizontalMargin)
        val topMargin = (margins.topMm * pxPerMm).coerceIn(0f, maxVerticalMargin)
        val bottomMargin = (margins.bottomMm * pxPerMm).coerceIn(0f, maxVerticalMargin)

        val cols = pagesPerSheet.columns
        val rows = pagesPerSheet.rows

        val avgMargin = (leftMargin + rightMargin + topMargin + bottomMargin) / 4f
        val gutter = if (pagesPerSheet == PagesPerSheet.ONE) {
            0f
        } else {
            (avgMargin * 0.45f).coerceAtLeast((targetDpi / 60f).coerceAtLeast(3f))
        }

        val usableWidth = (logicalWidth - leftMargin - rightMargin - gutter * (cols - 1)).coerceAtLeast(10f)
        val usableHeight = (logicalHeight - topMargin - bottomMargin - gutter * (rows - 1)).coerceAtLeast(10f)

        val cellWidth = usableWidth / cols
        val cellHeight = usableHeight / rows

        val cellRects = ArrayList<RectF>(pagesPerSheet.count)
        for (index in 0 until pagesPerSheet.count) {
            val (col, row) = when (nUpDirection) {
                NUpPresentationDirection.TO_RIGHT_TO_BOTTOM -> (index % cols) to (index / cols)
                NUpPresentationDirection.TO_BOTTOM_TO_RIGHT -> (index / rows) to (index % rows)
                NUpPresentationDirection.TO_LEFT_TO_BOTTOM -> (cols - 1 - (index % cols)) to (index / cols)
                NUpPresentationDirection.TO_BOTTOM_TO_LEFT -> (cols - 1 - (index / rows)) to (index % rows)
            }
            val cellLeft = leftMargin + col * (cellWidth + gutter)
            val cellTop = topMargin + row * (cellHeight + gutter)
            cellRects.add(RectF(cellLeft, cellTop, cellLeft + cellWidth, cellTop + cellHeight))
        }

        val innerPad = if (drawCellBorders && pagesPerSheet != PagesPerSheet.ONE) {
            (targetDpi / 75f).coerceAtLeast(2f)
        } else {
            0f
        }
        val borderStrokeWidth = (targetDpi / 150f).coerceAtLeast(1f)

        return SheetLayoutSpec(
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            rotationDegrees = rotationDegrees,
            cellRects = cellRects,
            innerPad = innerPad,
            borderStrokeWidth = borderStrokeWidth,
        )
    }

    /**
     * Applies the canvas rotation transform for [layout.rotationDegrees] into physical portrait paper coordinates.
     */
    fun applySheetRotation(canvas: Canvas, layout: SheetLayoutSpec) {
        when (layout.rotationDegrees) {
            90 -> {
                canvas.translate(layout.bitmapWidth.toFloat(), 0f)
                canvas.rotate(90f)
            }
            180 -> {
                canvas.translate(layout.bitmapWidth.toFloat(), layout.bitmapHeight.toFloat())
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(0f, layout.bitmapHeight.toFloat())
                canvas.rotate(270f)
            }
        }
    }

    /**
     * Composes up to [pagesPerSheet.count] source page bitmaps onto a single RGB_565 output sheet.
     */
    fun composeSheet(
        pageBitmaps: List<Bitmap>,
        pagesPerSheet: PagesPerSheet,
        paperSize: PaperSize,
        targetDpi: Int,
        drawCellBorders: Boolean,
        forPhysicalPortraitFeed: Boolean = false,
        margins: PageMargins = PageMargins.DEFAULT,
        scaling: PrintScaling = PrintScaling.AUTO_FIT,
        orientation: PrintOrientation = PrintOrientation.PORTRAIT,
        nUpDirection: NUpPresentationDirection = NUpPresentationDirection.TO_RIGHT_TO_BOTTOM,
        panX: Float = 0f,
        panY: Float = 0f,
    ): Bitmap {
        val layout = computeSheetLayout(
            pagesPerSheet = pagesPerSheet,
            paperSize = paperSize,
            targetDpi = targetDpi,
            drawCellBorders = drawCellBorders,
            forPhysicalPortraitFeed = forPhysicalPortraitFeed,
            margins = margins,
            orientation = orientation,
            nUpDirection = nUpDirection,
        )

        val sheet = Bitmap.createBitmap(layout.bitmapWidth, layout.bitmapHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val rotated = layout.rotationDegrees != 0
        if (rotated) {
            canvas.save()
            applySheetRotation(canvas, layout)
        }

        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(140, 140, 140)
            style = Paint.Style.STROKE
            strokeWidth = layout.borderStrokeWidth
        }

        val maxCells = min(pageBitmaps.size, pagesPerSheet.count)
        for (index in 0 until maxCells) {
            val cellRect = layout.cellRects[index]
            val src = pageBitmaps[index]
            if (!src.isRecycled && src.width > 0 && src.height > 0) {
                val destRect = computeScaledRect(
                    cellRect = cellRect,
                    srcWidth = src.width,
                    srcHeight = src.height,
                    innerPad = layout.innerPad,
                    scaling = scaling,
                    naturalWidthPx = src.width.toFloat(),
                    naturalHeightPx = src.height.toFloat(),
                    panX = panX,
                    panY = panY,
                )
                canvas.save()
                canvas.clipRect(cellRect)
                canvas.drawBitmap(src, null, destRect, bitmapPaint)
                canvas.restore()
            }
            if (drawCellBorders && pagesPerSheet != PagesPerSheet.ONE) {
                canvas.drawRect(cellRect, borderPaint)
            }
        }

        if (rotated) {
            canvas.restore()
        }

        return sheet
    }

    fun computeFittedRect(cellRect: RectF, srcWidth: Int, srcHeight: Int, innerPad: Float): RectF =
        computeScaledRect(
            cellRect = cellRect,
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            innerPad = innerPad,
            scaling = PrintScaling.FIT,
            naturalWidthPx = srcWidth.toFloat(),
            naturalHeightPx = srcHeight.toFloat(),
        )

    /**
     * Computes destination rectangle inside [cellRect] according to IPP [PrintScaling] (PWG 5100.14):
     * - [PrintScaling.FIT] / [PrintScaling.AUTO_FIT]: scale proportionally to fit inside printable area.
     * - [PrintScaling.AUTO]: keep 1:1 physical size if it fits, otherwise scale down to fit.
     * - [PrintScaling.FILL]: scale proportionally to cover printable area completely (center-cropped).
     * - [PrintScaling.NONE]: exact 100% physical size ([naturalWidthPx] x [naturalHeightPx]), centered.
     */
    fun computeScaledRect(
        cellRect: RectF,
        srcWidth: Int,
        srcHeight: Int,
        innerPad: Float,
        scaling: PrintScaling,
        naturalWidthPx: Float,
        naturalHeightPx: Float,
        panX: Float = 0f,
        panY: Float = 0f,
    ): RectF {
        val availW = (cellRect.width() - innerPad * 2f).coerceAtLeast(1f)
        val availH = (cellRect.height() - innerPad * 2f).coerceAtLeast(1f)
        val safeW = srcWidth.coerceAtLeast(1).toFloat()
        val safeH = srcHeight.coerceAtLeast(1).toFloat()
        val natW = naturalWidthPx.coerceAtLeast(1f)
        val natH = naturalHeightPx.coerceAtLeast(1f)

        val (drawW, drawH) = when (scaling) {
            PrintScaling.FIT, PrintScaling.AUTO_FIT -> {
                val scale = min(availW / safeW, availH / safeH)
                (safeW * scale) to (safeH * scale)
            }
            PrintScaling.AUTO -> {
                if (natW <= availW && natH <= availH) {
                    natW to natH
                } else {
                    val scale = min(availW / natW, availH / natH)
                    (natW * scale) to (natH * scale)
                }
            }
            PrintScaling.FILL -> {
                val scale = max(availW / safeW, availH / safeH)
                (safeW * scale) to (safeH * scale)
            }
            PrintScaling.NONE -> {
                natW to natH
            }
        }

        val maxShiftX = ((drawW - availW) / 2f).coerceAtLeast(0f)
        val maxShiftY = ((drawH - availH) / 2f).coerceAtLeast(0f)
        val drawLeft = cellRect.left + (cellRect.width() - drawW) / 2f + panX.coerceIn(-1f, 1f) * maxShiftX
        val drawTop = cellRect.top + (cellRect.height() - drawH) / 2f + panY.coerceIn(-1f, 1f) * maxShiftY
        return RectF(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH)
    }
}
