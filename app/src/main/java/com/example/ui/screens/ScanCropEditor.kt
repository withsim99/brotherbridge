package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.scanner.CropPoint
import com.example.scanner.NormalizedQuad
import com.example.scanner.ScanDocumentCropper
import com.example.scanner.ScanExportManager
import com.example.ui.i18n.LocalAppLanguage
import com.example.ui.i18n.tr
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SignalEmerald
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DragTarget {
    NONE,
    CORNER_TL,
    CORNER_TR,
    CORNER_BR,
    CORNER_BL,
    EDGE_TOP,
    EDGE_RIGHT,
    EDGE_BOTTOM,
    EDGE_LEFT,
    INTERIOR,
}

private data class CropEditSnapshot(
    val bitmap: Bitmap,
    val quad: NormalizedQuad,
)

/**
 * Full-screen Free-Style Perspective Crop & Auto-Adjustment Studio for scanned pages.
 *
 * Matches the active Material 3 light/dark app theme, placing Close, Page N, Undo/Redo, & Save
 * on the top bar and a clean background-free icon tool row directly beneath the image with full
 * safeDrawing insets so controls never clip behind system bars or cutouts in portrait or landscape.
 */
@Composable
fun ScanCropEditorScreen(
    pageNumber: Int,
    bitmap: Bitmap,
    originalBitmap: Bitmap?,
    onApplyCropped: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val colorScheme = MaterialTheme.colorScheme
    val isDarkTheme = colorScheme.background.luminance() < 0.5f

    var workingBitmap by remember(bitmap) { mutableStateOf(bitmap) }
    var quad by remember(bitmap) { mutableStateOf(NormalizedQuad.DEFAULT_INSET) }
    var undoStack by remember(bitmap) { mutableStateOf<List<CropEditSnapshot>>(emptyList()) }
    var redoStack by remember(bitmap) { mutableStateOf<List<CropEditSnapshot>>(emptyList()) }
    var dragStartQuad by remember(bitmap) { mutableStateOf<NormalizedQuad?>(null) }
    var activeDragTarget by remember { mutableStateOf(DragTarget.NONE) }
    var isProcessing by remember { mutableStateOf(false) }

    val displayBitmap = remember(workingBitmap) {
        createCropPreviewBitmap(workingBitmap, maxEdgePx = 2048)
    }
    val imageBitmap = remember(displayBitmap) {
        displayBitmap.asImageBitmap().also { bmp ->
            runCatching { bmp.prepareToDraw() }
        }
    }
    val hasOriginalBackup = originalBitmap != null && originalBitmap !== workingBitmap

    fun pushUndoSnapshot(prevBitmap: Bitmap = workingBitmap, prevQuad: NormalizedQuad = quad) {
        undoStack = (undoStack + CropEditSnapshot(prevBitmap, prevQuad)).takeLast(20)
        redoStack = emptyList()
    }

    fun performUndo() {
        if (isProcessing || undoStack.isEmpty()) return
        val previous = undoStack.last()
        undoStack = undoStack.dropLast(1)
        redoStack = (redoStack + CropEditSnapshot(workingBitmap, quad)).takeLast(20)
        workingBitmap = previous.bitmap
        quad = previous.quad
    }

    fun performRedo() {
        if (isProcessing || redoStack.isEmpty()) return
        val next = redoStack.last()
        redoStack = redoStack.dropLast(1)
        undoStack = (undoStack + CropEditSnapshot(workingBitmap, quad)).takeLast(20)
        workingBitmap = next.bitmap
        quad = next.quad
    }

    fun runAutoAdjust() {
        if (isProcessing) return
        isProcessing = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                ScanDocumentCropper.detectDocumentQuad(workingBitmap)
            }
            if (result.quad != quad) {
                pushUndoSnapshot()
                quad = result.quad
            }
            isProcessing = false
        }
    }

    fun applyWarpAndSave() {
        if (isProcessing) return
        isProcessing = true
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                ScanDocumentCropper.warpPerspective(workingBitmap, quad)
            }
            isProcessing = false
            onApplyCropped(result)
        }
    }

    BackHandler {
        if (!isProcessing) onDismiss()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("scan_crop_editor_dialog"),
        color = colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                // Top Bar: Close (left), Page N, Undo/Redo icons, Save Check Icon (right)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { if (!isProcessing) onDismiss() },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("crop_editor_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = tr("Cancel crop", "क्रॉप रद्द करें"),
                                tint = colorScheme.onSurface,
                            )
                        }

                        Text(
                            text = tr("Page $pageNumber", "पेज $pageNumber"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        val canUndo = undoStack.isNotEmpty() && !isProcessing
                        IconButton(
                            onClick = { performUndo() },
                            enabled = canUndo,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("crop_editor_undo_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Undo,
                                contentDescription = tr("Undo", "पूर्ववत करें"),
                                tint = if (canUndo) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.32f),
                            )
                        }

                        val canRedo = redoStack.isNotEmpty() && !isProcessing
                        IconButton(
                            onClick = { performRedo() },
                            enabled = canRedo,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("crop_editor_redo_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Redo,
                                contentDescription = tr("Redo", "पुनः करें"),
                                tint = if (canRedo) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.32f),
                            )
                        }

                        CropTopSaveIconButton(
                            icon = Icons.Rounded.Check,
                            contentDescription = tr("Save cropped page", "क्रॉप किया गया पेज सेव करें"),
                            enabled = !isProcessing,
                            testTag = "crop_editor_apply_button",
                            onClick = { applyWarpAndSave() },
                        )
                    }
                    HorizontalDivider(color = colorScheme.outlineVariant)
                }

                // Main Studio Area: Interactive Quad Canvas (draws preview bitmap + crop overlay atomically)
                var studioSize by remember { mutableStateOf(IntSize.Zero) }
                val containerW = studioSize.width.toFloat().coerceAtLeast(1f)
                val containerH = studioSize.height.toFloat().coerceAtLeast(1f)
                val bmpW = displayBitmap.width.toFloat().coerceAtLeast(1f)
                val bmpH = displayBitmap.height.toFloat().coerceAtLeast(1f)

                val fitScale = min(containerW / bmpW, containerH / bmpH)
                val dispW = bmpW * fitScale
                val dispH = bmpH * fitScale
                val imgLeft = (containerW - dispW) / 2f
                val imgTop = (containerH - dispH) / 2f
                val imageRect = Rect(imgLeft, imgTop, imgLeft + dispW, imgTop + dispH)

                val touchSlopPx = with(density) { 36.dp.toPx() }
                val scrimAlpha = if (isDarkTheme) 0.56f else 0.36f
                val gridAlpha = if (isDarkTheme) 0.38f else 0.45f

                fun toScreenIn(rect: Rect, pt: CropPoint): Offset = Offset(
                    x = rect.left + pt.x * rect.width,
                    y = rect.top + pt.y * rect.height,
                )

                fun toScreen(pt: CropPoint): Offset = toScreenIn(imageRect, pt)

                fun toNormalized(pos: Offset): CropPoint = CropPoint(
                    x = ((pos.x - imageRect.left) / imageRect.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
                    y = ((pos.y - imageRect.top) / imageRect.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
                )

                fun midpoint(a: CropPoint, b: CropPoint): CropPoint =
                    CropPoint((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .onSizeChanged { studioSize = it }
                        .pointerInput(workingBitmap, imageRect) {
                            detectDragGestures(
                                onDragStart = { startPos ->
                                    dragStartQuad = quad
                                    val tl = toScreen(quad.topLeft)
                                    val tr = toScreen(quad.topRight)
                                    val br = toScreen(quad.bottomRight)
                                    val bl = toScreen(quad.bottomLeft)
                                    val topMid = toScreen(midpoint(quad.topLeft, quad.topRight))
                                    val rightMid = toScreen(midpoint(quad.topRight, quad.bottomRight))
                                    val botMid = toScreen(midpoint(quad.bottomRight, quad.bottomLeft))
                                    val leftMid = toScreen(midpoint(quad.bottomLeft, quad.topLeft))

                                    val cornerCandidates = listOf(
                                        DragTarget.CORNER_TL to hypot(startPos.x - tl.x, startPos.y - tl.y),
                                        DragTarget.CORNER_TR to hypot(startPos.x - tr.x, startPos.y - tr.y),
                                        DragTarget.CORNER_BR to hypot(startPos.x - br.x, startPos.y - br.y),
                                        DragTarget.CORNER_BL to hypot(startPos.x - bl.x, startPos.y - bl.y),
                                    )
                                    val nearestCorner = cornerCandidates.minByOrNull { it.second }
                                    if (nearestCorner != null && nearestCorner.second <= touchSlopPx * 1.45f) {
                                        activeDragTarget = nearestCorner.first
                                        return@detectDragGestures
                                    }

                                    val edgeCandidates = listOf(
                                        DragTarget.EDGE_TOP to hypot(startPos.x - topMid.x, startPos.y - topMid.y),
                                        DragTarget.EDGE_RIGHT to hypot(startPos.x - rightMid.x, startPos.y - rightMid.y),
                                        DragTarget.EDGE_BOTTOM to hypot(startPos.x - botMid.x, startPos.y - botMid.y),
                                        DragTarget.EDGE_LEFT to hypot(startPos.x - leftMid.x, startPos.y - leftMid.y),
                                    )
                                    val nearestEdge = edgeCandidates.minByOrNull { it.second }
                                    if (nearestEdge != null && nearestEdge.second <= touchSlopPx * 1.25f) {
                                        activeDragTarget = nearestEdge.first
                                        return@detectDragGestures
                                    }

                                    activeDragTarget = if (isPointInsideQuad(startPos, listOf(tl, tr, br, bl))) {
                                        DragTarget.INTERIOR
                                    } else {
                                        DragTarget.NONE
                                    }
                                },
                                onDragEnd = {
                                    val beforeDrag = dragStartQuad
                                    if (beforeDrag != null && beforeDrag != quad) {
                                        pushUndoSnapshot(prevBitmap = workingBitmap, prevQuad = beforeDrag)
                                    }
                                    dragStartQuad = null
                                    activeDragTarget = DragTarget.NONE
                                },
                                onDragCancel = {
                                    dragStartQuad = null
                                    activeDragTarget = DragTarget.NONE
                                },
                                onDrag = { change, dragAmount ->
                                    if (activeDragTarget == DragTarget.NONE) return@detectDragGestures
                                    change.consume()
                                    val dx = dragAmount.x / imageRect.width.coerceAtLeast(1f)
                                    val dy = dragAmount.y / imageRect.height.coerceAtLeast(1f)

                                    fun shift(p: CropPoint): CropPoint = CropPoint(
                                        x = (p.x + dx).coerceIn(0f, 1f),
                                        y = (p.y + dy).coerceIn(0f, 1f),
                                    )

                                    val candidate = when (activeDragTarget) {
                                        DragTarget.CORNER_TL -> quad.copy(topLeft = toNormalized(change.position))
                                        DragTarget.CORNER_TR -> quad.copy(topRight = toNormalized(change.position))
                                        DragTarget.CORNER_BR -> quad.copy(bottomRight = toNormalized(change.position))
                                        DragTarget.CORNER_BL -> quad.copy(bottomLeft = toNormalized(change.position))
                                        DragTarget.EDGE_TOP -> quad.copy(
                                            topLeft = shift(quad.topLeft),
                                            topRight = shift(quad.topRight),
                                        )
                                        DragTarget.EDGE_RIGHT -> quad.copy(
                                            topRight = shift(quad.topRight),
                                            bottomRight = shift(quad.bottomRight),
                                        )
                                        DragTarget.EDGE_BOTTOM -> quad.copy(
                                            bottomLeft = shift(quad.bottomLeft),
                                            bottomRight = shift(quad.bottomRight),
                                        )
                                        DragTarget.EDGE_LEFT -> quad.copy(
                                            topLeft = shift(quad.topLeft),
                                            bottomLeft = shift(quad.bottomLeft),
                                        )
                                        DragTarget.INTERIOR -> {
                                            val pts = quad.asList()
                                            val clampedDx = dx.coerceIn(-pts.minOf { it.x }, 1f - pts.maxOf { it.x })
                                            val clampedDy = dy.coerceIn(-pts.minOf { it.y }, 1f - pts.maxOf { it.y })
                                            fun pan(p: CropPoint) = CropPoint(p.x + clampedDx, p.y + clampedDy)
                                            NormalizedQuad(
                                                topLeft = pan(quad.topLeft),
                                                topRight = pan(quad.topRight),
                                                bottomRight = pan(quad.bottomRight),
                                                bottomLeft = pan(quad.bottomLeft),
                                            )
                                        }
                                        DragTarget.NONE -> quad
                                    }

                                    if (candidate.isConvex()) {
                                        quad = candidate
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Single atomic Canvas: draws the scanned image + outside scrim + 3x3 grid + quad + handles
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cW = size.width.coerceAtLeast(1f)
                        val cH = size.height.coerceAtLeast(1f)
                        val bW = displayBitmap.width.toFloat().coerceAtLeast(1f)
                        val bH = displayBitmap.height.toFloat().coerceAtLeast(1f)
                        val fit = min(cW / bW, cH / bH)
                        val dW = bW * fit
                        val dH = bH * fit
                        val dLeft = (cW - dW) / 2f
                        val dTop = (cH - dH) / 2f
                        val drawRect = Rect(dLeft, dTop, dLeft + dW, dTop + dH)

                        drawImage(
                            image = imageBitmap,
                            dstOffset = IntOffset(dLeft.roundToInt(), dTop.roundToInt()),
                            dstSize = IntSize(dW.roundToInt().coerceAtLeast(1), dH.roundToInt().coerceAtLeast(1)),
                        )

                        val tl = toScreenIn(drawRect, quad.topLeft)
                        val trPt = toScreenIn(drawRect, quad.topRight)
                        val br = toScreenIn(drawRect, quad.bottomRight)
                        val bl = toScreenIn(drawRect, quad.bottomLeft)

                        val scrimPath = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addRect(drawRect)
                            moveTo(tl.x, tl.y)
                            lineTo(trPt.x, trPt.y)
                            lineTo(br.x, br.y)
                            lineTo(bl.x, bl.y)
                            close()
                        }
                        drawPath(scrimPath, color = Color.Black.copy(alpha = scrimAlpha))

                        val gridColor = SignalEmerald.copy(alpha = gridAlpha)
                        for (i in 1..2) {
                            val t = i / 3f
                            val topP = lerpOffset(tl, trPt, t)
                            val botP = lerpOffset(bl, br, t)
                            val leftP = lerpOffset(tl, bl, t)
                            val rightP = lerpOffset(trPt, br, t)
                            drawLine(gridColor, topP, botP, strokeWidth = 1.dp.toPx())
                            drawLine(gridColor, leftP, rightP, strokeWidth = 1.dp.toPx())
                        }

                        val quadPath = Path().apply {
                            moveTo(tl.x, tl.y)
                            lineTo(trPt.x, trPt.y)
                            lineTo(br.x, br.y)
                            lineTo(bl.x, bl.y)
                            close()
                        }
                        drawPath(
                            path = quadPath,
                            color = SignalEmerald,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )

                        val edgePairs = listOf(
                            (tl to trPt) to DragTarget.EDGE_TOP,
                            (trPt to br) to DragTarget.EDGE_RIGHT,
                            (br to bl) to DragTarget.EDGE_BOTTOM,
                            (bl to tl) to DragTarget.EDGE_LEFT,
                        )
                        edgePairs.forEach { (pair, target) ->
                            val mid = Offset((pair.first.x + pair.second.x) * 0.5f, (pair.first.y + pair.second.y) * 0.5f)
                            val isActive = activeDragTarget == target
                            drawCircle(
                                color = if (isActive) SignalEmerald else Color.White,
                                radius = if (isActive) 8.dp.toPx() else 6.dp.toPx(),
                                center = mid,
                            )
                            drawCircle(
                                color = SignalEmerald,
                                radius = if (isActive) 8.dp.toPx() else 6.dp.toPx(),
                                center = mid,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }

                        val corners = listOf(
                            tl to DragTarget.CORNER_TL,
                            trPt to DragTarget.CORNER_TR,
                            br to DragTarget.CORNER_BR,
                            bl to DragTarget.CORNER_BL,
                        )
                        corners.forEach { (pt, target) ->
                            val isActive = activeDragTarget == target
                            val outerR = if (isActive) 15.dp.toPx() else 12.dp.toPx()
                            val innerR = if (isActive) 8.dp.toPx() else 6.dp.toPx()
                            drawCircle(color = Color.White, radius = outerR, center = pt)
                            drawCircle(
                                color = SignalEmerald,
                                radius = outerR,
                                center = pt,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                            drawCircle(color = SignalEmerald, radius = innerR, center = pt)
                        }
                    }

                    // Live Magnifier Loupe when dragging any of the 4 corners
                    val loupeNormPoint = when (activeDragTarget) {
                        DragTarget.CORNER_TL -> quad.topLeft
                        DragTarget.CORNER_TR -> quad.topRight
                        DragTarget.CORNER_BR -> quad.bottomRight
                        DragTarget.CORNER_BL -> quad.bottomLeft
                        else -> null
                    }
                    if (loupeNormPoint != null) {
                        val loupeAlignment = if (loupeNormPoint.x < 0.5f) Alignment.TopEnd else Alignment.TopStart
                        CornerMagnifierLoupe(
                            imageBitmap = imageBitmap,
                            bitmapWidth = displayBitmap.width,
                            bitmapHeight = displayBitmap.height,
                            normalizedFocus = loupeNormPoint,
                            modifier = Modifier
                                .align(loupeAlignment)
                                .padding(10.dp),
                        )
                    }

                    if (isProcessing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(colorScheme.scrim.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = SignalEmerald)
                        }
                    }
                }

                // Bottom Tool Bar directly below the image with NO background
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                        )
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CropBottomToolIconButton(
                        icon = Icons.Outlined.AutoFixHigh,
                        contentDescription = tr("Auto adjust edges", "किनारे ऑटो एडजस्ट करें"),
                        tint = SignalEmerald,
                        enabled = !isProcessing,
                        testTag = "crop_editor_auto_adjust_button",
                        onClick = { runAutoAdjust() },
                    )

                    CropBottomToolIconButton(
                        icon = Icons.Outlined.CropFree,
                        contentDescription = tr("Select full page", "पूरा पेज चुनें"),
                        tint = colorScheme.onBackground,
                        enabled = !isProcessing,
                        testTag = "crop_editor_reset_button",
                        onClick = {
                            if (!isProcessing && quad != NormalizedQuad.FULL) {
                                pushUndoSnapshot()
                                quad = NormalizedQuad.FULL
                            }
                        },
                    )

                    CropBottomToolIconButton(
                        icon = Icons.Outlined.Rotate90DegreesCw,
                        contentDescription = tr("Rotate 90 degrees", "90 डिग्री घुमाएं"),
                        tint = colorScheme.onBackground,
                        enabled = !isProcessing,
                        testTag = "crop_editor_rotate_button",
                        onClick = {
                            if (!isProcessing) {
                                isProcessing = true
                                val beforeBitmap = workingBitmap
                                val beforeQuad = quad
                                scope.launch {
                                    val rotated = withContext(Dispatchers.Default) {
                                        ScanExportManager.rotateBitmap(beforeBitmap, 90f)
                                    }
                                    pushUndoSnapshot(prevBitmap = beforeBitmap, prevQuad = beforeQuad)
                                    workingBitmap = rotated
                                    quad = NormalizedQuad.DEFAULT_INSET
                                    isProcessing = false
                                }
                            }
                        },
                    )

                    if (hasOriginalBackup) {
                        CropBottomToolIconButton(
                            icon = Icons.Outlined.Restore,
                            contentDescription = tr("Restore original scan", "मूल स्कैन वापस लाएं"),
                            tint = colorScheme.onBackground,
                            enabled = !isProcessing,
                            testTag = "crop_editor_restore_button",
                            onClick = {
                                if (!isProcessing && originalBitmap != null) {
                                    pushUndoSnapshot()
                                    workingBitmap = originalBitmap
                                    quad = NormalizedQuad.FULL
                                }
                            },
                        )
                    }
                }
            }
        }
    }

@Composable
private fun CropTopSaveIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) SignalEmerald else SignalEmerald.copy(alpha = 0.4f),
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun CropBottomToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun CornerMagnifierLoupe(
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    bitmapWidth: Int,
    bitmapHeight: Int,
    normalizedFocus: CropPoint,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(96.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(2.5.dp, SignalEmerald),
        shadowElevation = 6.dp,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
        ) {
            val cropSide = (min(bitmapWidth, bitmapHeight) * 0.16f).roundToInt().coerceIn(32, min(bitmapWidth, bitmapHeight))
            val focusX = (normalizedFocus.x * bitmapWidth).roundToInt()
            val focusY = (normalizedFocus.y * bitmapHeight).roundToInt()
            val srcLeft = (focusX - cropSide / 2).coerceIn(0, (bitmapWidth - cropSide).coerceAtLeast(0))
            val srcTop = (focusY - cropSide / 2).coerceIn(0, (bitmapHeight - cropSide).coerceAtLeast(0))

            drawImage(
                image = imageBitmap,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(cropSide, cropSide),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )

            val relX = ((focusX - srcLeft).toFloat() / cropSide.toFloat()).coerceIn(0f, 1f) * size.width
            val relY = ((focusY - srcTop).toFloat() / cropSide.toFloat()).coerceIn(0f, 1f) * size.height
            drawLine(SignalEmerald, Offset(relX - 16f, relY), Offset(relX + 16f, relY), strokeWidth = 2.dp.toPx())
            drawLine(SignalEmerald, Offset(relX, relY - 16f), Offset(relX, relY + 16f), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 3.dp.toPx(), center = Offset(relX, relY))
        }
    }
}

private fun createCropPreviewBitmap(source: Bitmap, maxEdgePx: Int = 2048): Bitmap {
    if (source.isRecycled || source.width <= 0 || source.height <= 0) return source
    val srcW = source.width
    val srcH = source.height
    val maxDim = maxOf(srcW, srcH)
    val scale = if (maxDim > maxEdgePx) maxEdgePx.toFloat() / maxDim.toFloat() else 1f
    val outW = (srcW * scale).roundToInt().coerceAtLeast(1)
    val outH = (srcH * scale).roundToInt().coerceAtLeast(1)
    return try {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(out)
        val paint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG or AndroidPaint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), paint)
        out
    } catch (_: Throwable) {
        source
    }
}

private fun lerpOffset(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private fun isPointInsideQuad(pt: Offset, quad: List<Offset>): Boolean {
    var inside = false
    var j = quad.lastIndex
    for (i in quad.indices) {
        val xi = quad[i].x
        val yi = quad[i].y
        val xj = quad[j].x
        val yj = quad[j].y
        val intersect = ((yi > pt.y) != (yj > pt.y)) &&
            (pt.x < (xj - xi) * (pt.y - yi) / ((yj - yi).takeIf { abs(it) > 1e-5f } ?: 1e-5f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}
