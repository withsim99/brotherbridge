package com.example.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class CropPoint(val x: Float, val y: Float)

/**
 * 4-corner normalized quadrilateral (`0f..1f` in both X and Y) in clockwise order:
 * Top-Left -> Top-Right -> Bottom-Right -> Bottom-Left.
 */
data class NormalizedQuad(
    val topLeft: CropPoint,
    val topRight: CropPoint,
    val bottomRight: CropPoint,
    val bottomLeft: CropPoint,
) {
    fun asList(): List<CropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun clamped(): NormalizedQuad = NormalizedQuad(
        topLeft = CropPoint(topLeft.x.coerceIn(0f, 1f), topLeft.y.coerceIn(0f, 1f)),
        topRight = CropPoint(topRight.x.coerceIn(0f, 1f), topRight.y.coerceIn(0f, 1f)),
        bottomRight = CropPoint(bottomRight.x.coerceIn(0f, 1f), bottomRight.y.coerceIn(0f, 1f)),
        bottomLeft = CropPoint(bottomLeft.x.coerceIn(0f, 1f), bottomLeft.y.coerceIn(0f, 1f)),
    )

    fun isConvex(): Boolean {
        val pts = asList()
        var sign = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val c = pts[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val curSign = if (cross > 0f) 1 else -1
            if (sign == 0) sign = curSign else if (sign != curSign) return false
        }
        return true
    }

    companion object {
        val FULL = NormalizedQuad(
            topLeft = CropPoint(0f, 0f),
            topRight = CropPoint(1f, 0f),
            bottomRight = CropPoint(1f, 1f),
            bottomLeft = CropPoint(0f, 1f),
        )

        val DEFAULT_INSET = NormalizedQuad(
            topLeft = CropPoint(0.04f, 0.04f),
            topRight = CropPoint(0.96f, 0.04f),
            bottomRight = CropPoint(0.96f, 0.96f),
            bottomLeft = CropPoint(0.04f, 0.96f),
        )

        /**
         * Orders any 4 arbitrary corner points into consistent clockwise (TL, TR, BR, BL) order,
         * even when the document is rotated at an arbitrary angle (e.g. 35 degrees).
         */
        fun orderCorners(points: List<CropPoint>): NormalizedQuad {
            require(points.size == 4) { "Expected exactly 4 corner points" }
            val cx = points.sumOf { it.x.toDouble() }.toFloat() / 4f
            val cy = points.sumOf { it.y.toDouble() }.toFloat() / 4f
            // Sort clockwise in image coordinates (Y downward) around centroid
            val clockwise = points.sortedBy { p -> atan2((p.y - cy).toDouble(), (p.x - cx).toDouble()) }
            // Pick the point closest to top-left (minimum x + y) as TL while keeping clockwise winding
            val tlIdx = clockwise.indices.minByOrNull { i -> clockwise[i].x + clockwise[i].y } ?: 0
            return NormalizedQuad(
                topLeft = clockwise[tlIdx],
                topRight = clockwise[(tlIdx + 1) % 4],
                bottomRight = clockwise[(tlIdx + 2) % 4],
                bottomLeft = clockwise[(tlIdx + 3) % 4],
            )
        }
    }
}

data class AutoAdjustResult(
    val quad: NormalizedQuad,
    val detectedAngleDeg: Float,
    val wasAutoDetected: Boolean,
)

/**
 * Pure-Kotlin Document Geometry, Free-Style Perspective Warp & Auto-Adjustment Engine.
 *
 * - **Auto-Adjust**: Isolates documents/cards/receipts placed at ANY random angle on a light or
 *   dark flatbed glass via border background estimation, color+gradient segmentation, convex hull
 *   (Andrew's monotone chain), and rotating-calipers minimum-area oriented bounding box — with a
 *   projection-profile deskew fallback for full-bleed slightly tilted pages.
 * - **Free-Style Perspective Crop**: Uses exact 4-point projective homography (`Matrix.setPolyToPoly`)
 *   preserving true physical edge lengths so arbitrarily rotated/skewed documents become flat,
 *   upright, cleanly shaped rectangles.
 */
object ScanDocumentCropper {

    /**
     * Warps any 4-corner quadrilateral [quad] inside [source] into a straightened rectangular [Bitmap]
     * preserving the true physical edge proportions of the selected document.
     */
    fun warpPerspective(
        source: Bitmap,
        quad: NormalizedQuad,
        maxOutputDimension: Int = 3600,
    ): Bitmap {
        val safeQuad = quad.clamped()
        val ordered = if (safeQuad.isConvex()) safeQuad else NormalizedQuad.orderCorners(safeQuad.asList())
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()

        val tlX = ordered.topLeft.x * srcW
        val tlY = ordered.topLeft.y * srcH
        val trX = ordered.topRight.x * srcW
        val trY = ordered.topRight.y * srcH
        val brX = ordered.bottomRight.x * srcW
        val brY = ordered.bottomRight.y * srcH
        val blX = ordered.bottomLeft.x * srcW
        val blY = ordered.bottomLeft.y * srcH

        val topWidth = hypot(trX - tlX, trY - tlY)
        val bottomWidth = hypot(brX - blX, brY - blY)
        val leftHeight = hypot(blX - tlX, blY - tlY)
        val rightHeight = hypot(brX - trX, brY - trY)

        var outW = max(topWidth, bottomWidth).roundToInt().coerceAtLeast(32)
        var outH = max(leftHeight, rightHeight).roundToInt().coerceAtLeast(32)

        val largest = max(outW, outH)
        if (largest > maxOutputDimension) {
            val scale = maxOutputDimension.toFloat() / largest.toFloat()
            outW = (outW * scale).roundToInt().coerceAtLeast(32)
            outH = (outH * scale).roundToInt().coerceAtLeast(32)
        }

        val srcPts = floatArrayOf(tlX, tlY, trX, trY, brX, brY, blX, blY)
        val dstPts = floatArrayOf(0f, 0f, outW.toFloat(), 0f, outW.toFloat(), outH.toFloat(), 0f, outH.toFloat())

        val matrix = Matrix()
        val solved = matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (solved) {
            canvas.drawBitmap(source, matrix, paint)
        } else {
            // Safe axis-aligned bounding box fallback if corners are degenerate
            val minX = minOf(tlX, trX, brX, blX).roundToInt().coerceIn(0, source.width - 1)
            val minY = minOf(tlY, trY, brY, blY).roundToInt().coerceIn(0, source.height - 1)
            val maxX = maxOf(tlX, trX, brX, blX).roundToInt().coerceIn(minX + 1, source.width)
            val maxY = maxOf(tlY, trY, brY, blY).roundToInt().coerceIn(minY + 1, source.height)
            canvas.drawBitmap(
                source,
                android.graphics.Rect(minX, minY, maxX, maxY),
                android.graphics.RectF(0f, 0f, outW.toFloat(), outH.toFloat()),
                paint,
            )
        }
        return output
    }

    /**
     * Automatically detects the tilted document/card boundary inside [source] and returns the
     * optimal 4-corner [NormalizedQuad] plus the estimated rotation angle in degrees.
     */
    fun detectDocumentQuad(source: Bitmap): AutoAdjustResult {
        val maxSide = 300
        val scale = min(1f, maxSide.toFloat() / max(source.width, source.height).coerceAtLeast(1).toFloat())
        val w = (source.width * scale).roundToInt().coerceAtLeast(24)
        val h = (source.height * scale).roundToInt().coerceAtLeast(24)
        val work = if (w == source.width && h == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, w, h, true)
        }

        return try {
            val pixels = IntArray(w * h)
            work.getPixels(pixels, 0, w, 0, 0, w, h)
            detectFromPixels(pixels, w, h)
        } finally {
            if (work !== source && !work.isRecycled) {
                work.recycle()
            }
        }
    }

    /**
     * Pure-JVM testable core detection operating directly on ARGB pixel array.
     */
    internal fun detectFromPixels(pixels: IntArray, w: Int, h: Int): AutoAdjustResult {
        if (w < 16 || h < 16 || pixels.size < w * h) {
            return AutoAdjustResult(NormalizedQuad.FULL, 0f, false)
        }

        // 1. Estimate flatbed background color from outer border samples (robust median per channel)
        val borderR = ArrayList<Int>(w * 4 + h * 4)
        val borderG = ArrayList<Int>(w * 4 + h * 4)
        val borderB = ArrayList<Int>(w * 4 + h * 4)
        val margin = max(2, min(w, h) / 40)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x < margin || x >= w - margin || y < margin || y >= h - margin) {
                    val p = pixels[y * w + x]
                    borderR.add((p shr 16) and 0xFF)
                    borderG.add((p shr 8) and 0xFF)
                    borderB.add(p and 0xFF)
                }
            }
        }
        val bgR = medianOf(borderR)
        val bgG = medianOf(borderG)
        val bgB = medianOf(borderB)

        // Compute background deviation (MAD) so threshold adapts automatically to scanner noise
        val borderDiffs = ArrayList<Float>(borderR.size)
        for (i in borderR.indices) {
            val dr = (borderR[i] - bgR).toFloat()
            val dg = (borderG[i] - bgG).toFloat()
            val db = (borderB[i] - bgB).toFloat()
            borderDiffs.add(sqrt(dr * dr + dg * dg + db * db))
        }
        borderDiffs.sort()
        val p85BorderNoise = borderDiffs.getOrElse((borderDiffs.size * 0.85f).toInt()) { 10f }
        val colorThreshold = max(22f, min(65f, p85BorderNoise * 2.4f + 14f))

        // 2. Build grayscale array + foreground mask (color difference OR strong Sobel edge)
        val lum = FloatArray(w * h)
        for (i in 0 until w * h) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val mask = BooleanArray(w * h)
        var fgCount = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val p = pixels[idx]
                val dr = ((p shr 16) and 0xFF) - bgR
                val dg = ((p shr 8) and 0xFF) - bgG
                val db = (p and 0xFF) - bgB
                val colorDist = sqrt((dr * dr + dg * dg + db * db).toFloat())

                val gx = -lum[idx - w - 1] + lum[idx - w + 1] -
                    2f * lum[idx - 1] + 2f * lum[idx + 1] -
                    lum[idx + w - 1] + lum[idx + w + 1]
                val gy = -lum[idx - w - 1] - 2f * lum[idx - w] - lum[idx - w + 1] +
                    lum[idx + w - 1] + 2f * lum[idx + w] + lum[idx + w + 1]
                val grad = abs(gx) + abs(gy)

                if (colorDist > colorThreshold || grad > 110f) {
                    mask[idx] = true
                    fgCount++
                }
            }
        }

        val totalPixels = (w * h).toFloat()
        val fgRatio = fgCount / totalPixels
        if (fgCount < 40 || fgRatio < 0.008f) {
            return AutoAdjustResult(NormalizedQuad.DEFAULT_INSET, 0f, false)
        }

        // 3. Morphological closing (dilate then erode) to bridge text gaps & remove isolated scanner dust
        val closed = morphErode(morphDilate(mask, w, h, radius = 2), w, h, radius = 1)

        // 4. Filter out tiny dust speckles via connected components; keep significant components
        val filteredMask = filterNoiseComponents(closed, w, h, minAreaPx = max(30, (totalPixels * 0.004f).toInt()))

        // Collect boundary points of the remaining foreground mask
        val fgPoints = ArrayList<CropPoint>(4096)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                if (filteredMask[idx]) {
                    // Only boundary pixels are needed for convex hull
                    if (!filteredMask[idx - 1] || !filteredMask[idx + 1] ||
                        !filteredMask[idx - w] || !filteredMask[idx + w]
                    ) {
                        fgPoints.add(CropPoint(x.toFloat(), y.toFloat()))
                    }
                }
            }
        }

        if (fgPoints.size < 8) {
            return AutoAdjustResult(NormalizedQuad.DEFAULT_INSET, 0f, false)
        }

        // 5. Compute Convex Hull (Andrew's Monotone Chain)
        val hull = computeConvexHull(fgPoints)
        if (hull.size < 3) {
            return AutoAdjustResult(NormalizedQuad.DEFAULT_INSET, 0f, false)
        }

        // 6. Minimum-Area Oriented Bounding Box (Rotating Calipers over convex hull edges)
        val obb = computeMinAreaOrientedBox(hull)
        val obbAreaRatio = obb.area / totalPixels

        // If the detected box covers nearly the entire full-bleed page (>94%), check fine text deskew
        if (obbAreaRatio > 0.94f && abs(obb.angleDeg) < 1.0f) {
            val skewDeg = estimateTextSkewAngleDeg(lum, w, h)
            if (abs(skewDeg) >= 0.8f) {
                val deskewQuad = buildRotatedInsetQuad(w, h, skewDeg)
                return AutoAdjustResult(
                    quad = deskewQuad,
                    detectedAngleDeg = skewDeg,
                    wasAutoDetected = true,
                )
            }
            return AutoAdjustResult(NormalizedQuad.FULL, 0f, false)
        }

        // Add subtle padding (~1.2% of edge length) so edges of documents/cards aren't clipped
        val padded = expandQuadSlightly(obb.corners, w.toFloat(), h.toFloat(), padFraction = 0.012f)
        val normalized = padded.map { pt ->
            CropPoint(
                x = (pt.x / w.toFloat()).coerceIn(0f, 1f),
                y = (pt.y / h.toFloat()).coerceIn(0f, 1f),
            )
        }
        val orderedQuad = NormalizedQuad.orderCorners(normalized)

        // Normalize angle to [-45, +45] for readable UI display (e.g., "14.2° tilt corrected")
        val topEdgeAngleRad = atan2(
            (orderedQuad.topRight.y * h - orderedQuad.topLeft.y * h).toDouble(),
            (orderedQuad.topRight.x * w - orderedQuad.topLeft.x * w).toDouble(),
        )
        var displayDeg = (topEdgeAngleRad * 180.0 / PI).toFloat()
        while (displayDeg > 45f) displayDeg -= 90f
        while (displayDeg < -45f) displayDeg += 90f

        return AutoAdjustResult(
            quad = orderedQuad,
            detectedAngleDeg = displayDeg,
            wasAutoDetected = true,
        )
    }

    private data class OrientedBox(
        val corners: List<CropPoint>,
        val area: Float,
        val angleDeg: Float,
    )

    private fun computeMinAreaOrientedBox(hull: List<CropPoint>): OrientedBox {
        var bestArea = Float.MAX_VALUE
        var bestCorners: List<CropPoint> = emptyList()
        var bestAngleDeg = 0f

        for (i in hull.indices) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % hull.size]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val len = hypot(dx, dy)
            if (len < 1e-4f) continue

            val ux = dx / len
            val uy = dy / len
            val vx = -uy
            val vy = ux

            var minU = Float.MAX_VALUE
            var maxU = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxV = -Float.MAX_VALUE

            for (p in hull) {
                val u = p.x * ux + p.y * uy
                val v = p.x * vx + p.y * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }

            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                bestCorners = listOf(
                    CropPoint(minU * ux + minV * vx, minU * uy + minV * vy),
                    CropPoint(maxU * ux + minV * vx, maxU * uy + minV * vy),
                    CropPoint(maxU * ux + maxV * vx, maxU * uy + maxV * vy),
                    CropPoint(minU * ux + maxV * vx, minU * uy + maxV * vy),
                )
                bestAngleDeg = (atan2(uy.toDouble(), ux.toDouble()) * 180.0 / PI).toFloat()
            }
        }

        return OrientedBox(bestCorners, bestArea, bestAngleDeg)
    }

    private fun expandQuadSlightly(
        corners: List<CropPoint>,
        w: Float,
        h: Float,
        padFraction: Float,
    ): List<CropPoint> {
        val cx = corners.sumOf { it.x.toDouble() }.toFloat() / 4f
        val cy = corners.sumOf { it.y.toDouble() }.toFloat() / 4f
        val factor = 1f + padFraction * 2f
        return corners.map { pt ->
            CropPoint(
                x = (cx + (pt.x - cx) * factor).coerceIn(0f, w),
                y = (cy + (pt.y - cy) * factor).coerceIn(0f, h),
            )
        }
    }

    private fun computeConvexHull(points: List<CropPoint>): List<CropPoint> {
        val sorted = points.sortedWith(compareBy<CropPoint> { it.x }.thenBy { it.y })
        if (sorted.size <= 3) return sorted

        fun cross(o: CropPoint, a: CropPoint, b: CropPoint): Float =
            (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = ArrayList<CropPoint>(sorted.size)
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(p)
        }

        val upper = ArrayList<CropPoint>(sorted.size)
        for (i in sorted.lastIndex downTo 0) {
            val p = sorted[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(p)
        }

        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        lower.addAll(upper)
        return lower
    }

    private fun morphDilate(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val dst = BooleanArray(w * h)
        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                if (src[y * w + x]) {
                    for (dy in -radius..radius) {
                        val row = (y + dy) * w
                        for (dx in -radius..radius) {
                            dst[row + x + dx] = true
                        }
                    }
                }
            }
        }
        return dst
    }

    private fun morphErode(src: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val dst = BooleanArray(w * h)
        for (y in radius until h - radius) {
            for (x in radius until w - radius) {
                var allSet = true
                outer@ for (dy in -radius..radius) {
                    val row = (y + dy) * w
                    for (dx in -radius..radius) {
                        if (!src[row + x + dx]) {
                            allSet = false
                            break@outer
                        }
                    }
                }
                dst[y * w + x] = allSet
            }
        }
        return dst
    }

    private fun filterNoiseComponents(mask: BooleanArray, w: Int, h: Int, minAreaPx: Int): BooleanArray {
        val visited = BooleanArray(w * h)
        val out = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val compPixels = ArrayList<Int>(1024)

        for (i in 0 until w * h) {
            if (!mask[i] || visited[i]) continue
            compPixels.clear()
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            while (head < tail) {
                val cur = queue[head++]
                compPixels.add(cur)
                val cx = cur % w
                val cy = cur / w
                if (cx > 0 && mask[cur - 1] && !visited[cur - 1]) {
                    visited[cur - 1] = true
                    queue[tail++] = cur - 1
                }
                if (cx < w - 1 && mask[cur + 1] && !visited[cur + 1]) {
                    visited[cur + 1] = true
                    queue[tail++] = cur + 1
                }
                if (cy > 0 && mask[cur - w] && !visited[cur - w]) {
                    visited[cur - w] = true
                    queue[tail++] = cur - w
                }
                if (cy < h - 1 && mask[cur + w] && !visited[cur + w]) {
                    visited[cur + w] = true
                    queue[tail++] = cur + w
                }
            }

            if (compPixels.size >= minAreaPx) {
                for (px in compPixels) out[px] = true
            }
        }
        return out
    }

    /**
     * Projection-profile deskew angle estimator for full-page text scans slightly tilted (-15..+15 deg).
     */
    private fun estimateTextSkewAngleDeg(lum: FloatArray, w: Int, h: Int): Float {
        val edgePtsX = ArrayList<Float>(2048)
        val edgePtsY = ArrayList<Float>(2048)
        val cx = w / 2f
        val cy = h / 2f

        for (y in 2 until h - 2 step 2) {
            val row = y * w
            for (x in 2 until w - 2 step 2) {
                val dy = abs(lum[row + x + w] - lum[row + x - w])
                if (dy > 38f) {
                    edgePtsX.add(x - cx)
                    edgePtsY.add(y - cy)
                }
            }
        }
        if (edgePtsX.size < 80) return 0f

        var bestAngle = 0f
        var bestScore = -1.0
        val binCount = h
        val bins = IntArray(binCount)

        var angle = -15.0f
        while (angle <= 15.0f) {
            bins.fill(0)
            val rad = angle * PI / 180.0
            val sinA = sin(rad).toFloat()
            val cosA = cos(rad).toFloat()

            for (i in edgePtsX.indices) {
                val rotY = (edgePtsX[i] * (-sinA) + edgePtsY[i] * cosA + cy).roundToInt()
                if (rotY in 0 until binCount) {
                    bins[rotY]++
                }
            }
            var varianceScore = 0.0
            for (count in bins) {
                varianceScore += count.toDouble() * count.toDouble()
            }
            if (varianceScore > bestScore) {
                bestScore = varianceScore
                bestAngle = angle
            }
            angle += 0.5f
        }
        return bestAngle
    }

    private fun buildRotatedInsetQuad(w: Int, h: Int, angleDeg: Float): NormalizedQuad {
        val rad = angleDeg * PI / 180.0
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val halfW = w * 0.44f
        val halfH = h * 0.44f

        fun rot(lx: Float, ly: Float): CropPoint {
            val rx = cx + lx * cosA - ly * sinA
            val ry = cy + lx * sinA + ly * cosA
            return CropPoint((rx / w).coerceIn(0f, 1f), (ry / h).coerceIn(0f, 1f))
        }

        return NormalizedQuad(
            topLeft = rot(-halfW, -halfH),
            topRight = rot(halfW, -halfH),
            bottomRight = rot(halfW, halfH),
            bottomLeft = rot(-halfW, halfH),
        )
    }

    private fun medianOf(values: MutableList<Int>): Int {
        if (values.isEmpty()) return 255
        values.sort()
        return values[values.size / 2]
    }
}
