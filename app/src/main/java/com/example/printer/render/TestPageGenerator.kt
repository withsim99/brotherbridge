package com.example.printer.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.model.NetworkServerState
import com.example.model.PrintJobOptions
import com.example.model.UsbPrinterInfo
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TestPageGenerator {

    fun generateTestPage(
        options: PrintJobOptions,
        serverState: NetworkServerState,
        usbInfo: UsbPrinterInfo,
        dpi: Int = options.resolution.dpi,
    ): Bitmap {
        val width = options.paperSize.widthPx(dpi).coerceAtLeast(300)
        val height = options.paperSize.heightPx(dpi).coerceAtLeast(420)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val unit = width / 100f
        val margin = unit * 6.5f

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (unit * 0.25f).coerceAtLeast(1.5f)
        }
        val fillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = unit * 3.8f
        }
        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = unit * 2.1f
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = unit * 1.65f
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = unit * 1.3f
        }

        // 1. Outer precision alignment frame + corner crosshairs
        canvas.drawRect(margin, margin, width - margin, height - margin, strokePaint)
        drawCrosshair(canvas, margin, margin, unit * 1.8f, strokePaint)
        drawCrosshair(canvas, width - margin, margin, unit * 1.8f, strokePaint)
        drawCrosshair(canvas, margin, height - margin, unit * 1.8f, strokePaint)
        drawCrosshair(canvas, width - margin, height - margin, unit * 1.8f, strokePaint)

        // 2. Solid black header bar (laser density check)
        val headerTop = margin + unit * 2.2f
        val headerBottom = headerTop + unit * 10.5f
        canvas.drawRect(margin + unit * 2f, headerTop, width - margin - unit * 2f, headerBottom, fillBlack)
        canvas.drawText(
            "BROTHERBRIDGE // ${usbInfo.displayTitle.uppercase(Locale.US)}",
            margin + unit * 4.5f,
            headerTop + unit * 5.2f,
            titlePaint,
        )
        val tagPaint = Paint(titlePaint).apply {
            textSize = unit * 1.75f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText(
            "USB OTG -> WI-FI IPP BRLASER DIAGNOSTIC TEST PAGE",
            margin + unit * 4.5f,
            headerTop + unit * 8.4f,
            tagPaint,
        )

        // 3. Hardware & Network Telemetry Table
        var cursorY = headerBottom + unit * 5.0f
        val leftX = margin + unit * 2.5f
        val valueX = margin + unit * 34f
        val lineStep = unit * 3.1f

        canvas.drawText("SYSTEM & NETWORK TELEMETRY", leftX, cursorY, subHeaderPaint)
        cursorY += unit * 1.2f
        canvas.drawLine(leftX, cursorY, width - margin - unit * 2.5f, cursorY, strokePaint)
        cursorY += unit * 3.2f

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val rows = listOf(
            "Printer Target" to usbInfo.displayTitle,
            "USB VID:PID" to "${usbInfo.vidPidHex} (${usbInfo.status.name})",
            "IPP Endpoint" to serverState.ippUri,
            "Bonjour / mDNS" to "${serverState.mdnsServiceName}._ipp._tcp (${serverState.mdnsStatus.label})",
            "Driver Protocol" to options.printerLanguage.displayName,
            "Paper / DPI" to "${options.paperSize.displayName} @ ${options.resolution.dpi} DPI",
            "Duplex Mode" to "${options.duplexMode.shortLabel} (${options.duplexMode.ippKeyword})",
            "N-Up Imposition" to "${options.pagesPerSheet.shortLabel} (${options.pagesPerSheet.description})",
            "Toner Eco Mode" to if (options.tonerSave) "ENABLED" else "OFF (Standard Density)",
            "Timestamp" to timestamp,
        )

        rows.forEach { (label, value) ->
            canvas.drawText(label, leftX, cursorY, bodyPaint)
            canvas.drawText(": $value", valueX, cursorY, bodyPaint)
            cursorY += lineStep
        }

        // 4. Grayscale Halftone / Floyd-Steinberg Dithering Ramp (10 steps)
        cursorY += unit * 3.0f
        canvas.drawText("GRAYSCALE DITHERING CALIBRATION (10% - 100%)", leftX, cursorY, subHeaderPaint)
        cursorY += unit * 2.2f

        val barWidth = (width - margin * 2f - unit * 5f) / 10f
        val barHeight = unit * 7.5f
        for (i in 0 until 10) {
            val pct = (i + 1) * 10
            val gray = (255 - (255 * pct / 100)).coerceIn(0, 255)
            val rect = RectF(leftX + i * barWidth, cursorY, leftX + (i + 1) * barWidth - unit * 0.4f, cursorY + barHeight)
            val shadePaint = Paint().apply {
                color = Color.rgb(gray, gray, gray)
                style = Paint.Style.FILL
            }
            canvas.drawRect(rect, shadePaint)
            canvas.drawRect(rect, strokePaint)
            canvas.drawText("$pct%", rect.left + unit * 1.1f, rect.bottom + unit * 2.1f, smallPaint)
        }

        // 5. Resolution & Line-Weight Precision Bars
        cursorY += barHeight + unit * 5.8f
        canvas.drawText("LASER LINE-WEIGHT & REGISTRATION CHECK", leftX, cursorY, subHeaderPaint)
        cursorY += unit * 2.5f

        val weights = listOf(0.5f, 1.0f, 2.0f, 4.0f, 8.0f)
        weights.forEach { pt ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                strokeWidth = (pt * (dpi / 72f)).coerceAtLeast(1f)
            }
            canvas.drawText("${pt}pt", leftX, cursorY + unit * 0.6f, smallPaint)
            canvas.drawLine(leftX + unit * 9f, cursorY, width - margin - unit * 2.5f, cursorY, p)
            cursorY += unit * 2.8f
        }

        // 6. Supported Format & N-Up Summary Box + Author Quote at Bottom
        val footerTop = height - margin - unit * 16.5f
        val footerBoxBottom = height - margin - unit * 5.8f
        canvas.drawRect(leftX, footerTop, width - margin - unit * 2.5f, footerBoxBottom, strokePaint)
        canvas.drawText(
            "ACCEPTED FORMATS : application/pdf, image/jpeg, image/png, image/webp (Max 1 GB)",
            leftX + unit * 2f,
            footerTop + unit * 3.2f,
            smallPaint,
        )
        canvas.drawText(
            "N-UP CAPABILITY  : 1-Up | 2-Up | 4-Up | 8-Up | 16-Up Sheet Imposition",
            leftX + unit * 2f,
            footerTop + unit * 5.8f,
            smallPaint,
        )
        canvas.drawText(
            "DUPLEX HARDWARE  : Brother Automatic Duplexer (Long-Edge & Short-Edge)",
            leftX + unit * 2f,
            footerTop + unit * 8.4f,
            smallPaint,
        )

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = unit * 2.1f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            "❤️ utkarsh 🔥",
            width / 2f,
            height - margin - unit * 2.1f,
            quotePaint,
        )

        return bitmap
    }

    /**
     * Renders the diagnostic test page into a single-page PDF file in app cache so it can be
     * loaded directly into Print Preview with live paper-size, N-Up, DPI, and duplex controls.
     */
    fun generateTestPagePdf(
        context: Context,
        options: PrintJobOptions,
        serverState: NetworkServerState,
        usbInfo: UsbPrinterInfo,
        dpi: Int = 150,
    ): File {
        val dir = File(context.cacheDir, "test_page_preview").apply { mkdirs() }
        val pdfFile = File(dir, "PrintBridge_Test_Page.pdf")
        val bmp = generateTestPage(
            options = options,
            serverState = serverState,
            usbInfo = usbInfo,
            dpi = dpi,
        )
        val doc = PdfDocument()
        try {
            val pageW = kotlin.math.round((options.paperSize.widthMm / 25.4) * 72.0).toInt().coerceAtLeast(300)
            val pageH = kotlin.math.round((options.paperSize.heightMm / 25.4) * 72.0).toInt().coerceAtLeast(420)
            val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()), null)
            doc.finishPage(page)
            FileOutputStream(pdfFile).use { out ->
                doc.writeTo(out)
                out.flush()
            }
        } finally {
            doc.close()
            bmp.recycle()
        }
        return pdfFile
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
        canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
    }
}
