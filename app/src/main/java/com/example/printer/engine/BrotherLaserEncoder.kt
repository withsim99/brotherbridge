package com.example.printer.engine

import android.graphics.Bitmap
import com.example.model.DuplexMode
import com.example.model.PaperSize
import com.example.model.PrintJobOptions
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Brother DCP-L2520D monochrome laser encoder.
 *
 * Strict 1:1 Kotlin port of the brlaser C++ driver (job.h, job.cc, line.h, line.cc, block.h).
 * Each print job uses an explicit [BrotherPrintSession] so coroutine thread switches across
 * suspension points never share or lose per-job encoder state.
 */
object BrotherLaserEncoder {

    private val SUPPORTED_PJL_PAPERS = setOf("A4", "LETTER", "LEGAL", "A5", "A6", "B5", "EXECUTIVE")

    fun openSession(
        output: OutputStream,
        jobName: String,
        options: PrintJobOptions,
    ): BrotherPrintSession = BrotherPrintSession(output, jobName, options)

    class BrotherPrintSession(
        private val output: OutputStream,
        jobName: String,
        private val options: PrintJobOptions,
    ) : AutoCloseable {

        private val params: BrlaserPageParams
        private val job: BrlaserJob
        private var closed: Boolean = false

        init {
            validatePaperSize(options)
            params = buildPageParams(options)
            job = BrlaserJob(output, jobName)
            job.beginJob()
            job.ensurePageHeader(params)
        }

        fun writePage(bitmap: Bitmap) {
            check(!closed) { "BrotherPrintSession is already closed" }
            require(!bitmap.isRecycled) { "Cannot encode recycled bitmap" }
            val width = bitmap.width
            val height = bitmap.height
            require(width > 0 && height > 0) { "Bitmap dimensions must be positive (got ${width}x${height})" }

            val lineSize = (width + 7) / 8
            val maxLineBytes = ((options.paperSize.widthPx(options.resolution.dpi) + 7) / 8) + 64
            require(lineSize in 1 until 10000 && lineSize <= maxLineBytes) {
                "Page width ${width}px ($lineSize bytes/row) exceeds ${options.paperSize.pjlName} @ ${options.resolution.dpi} DPI limit ($maxLineBytes bytes/row)"
            }

            // Stream 1-bit monochrome scanlines row-by-row to avoid allocating a full-page Array<ByteArray>
            val pixels = IntArray(width)
            val currErr = FloatArray(width + 2)
            val nextErr = FloatArray(width + 2)
            val dither = options.ditherImages
            var y = 0

            job.encodePage(
                params = params,
                lines = height,
                lineSize = lineSize,
            ) { rowOut ->
                if (y >= height) {
                    false
                } else {
                    rowOut.fill(0)
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    encodeMonoRow(pixels, width, currErr, nextErr, dither, rowOut)
                    y++
                    true
                }
            }
        }

        fun writeBlankBackPageIfNeeded(totalRenderedSides: Int) {
            check(!closed) { "BrotherPrintSession is already closed" }
            if (options.duplexMode != DuplexMode.SIMPLEX && totalRenderedSides % 2 == 1) {
                val lineSize = ((options.paperSize.widthPx(options.resolution.dpi) + 7) / 8).coerceIn(1, 9999)
                var emitted = false
                job.encodePage(
                    params = params,
                    lines = 1,
                    lineSize = lineSize,
                ) { buf ->
                    if (emitted) {
                        false
                    } else {
                        buf.fill(0)
                        emitted = true
                        true
                    }
                }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            job.endJob()
            output.flush()
        }
    }

    /**
     * Converts an ARGB_8888 bitmap into 1-bit monochrome scanlines (MSB-first, 1 = black toner dot).
     * Uses Floyd-Steinberg error-diffusion dithering when [dither] is true for rich grayscale tones.
     */
    internal fun convertToMonoScanlines(bitmap: Bitmap, dither: Boolean): Array<ByteArray> {
        val width = bitmap.width
        val height = bitmap.height
        val rowByteWidth = (width + 7) / 8
        val pixels = IntArray(width)
        val currErr = FloatArray(width + 2)
        val nextErr = FloatArray(width + 2)
        val result = Array(height) { ByteArray(rowByteWidth) }

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            encodeMonoRow(pixels, width, currErr, nextErr, dither, result[y])
        }
        return result
    }

    private fun encodeMonoRow(
        pixels: IntArray,
        width: Int,
        currErr: FloatArray,
        nextErr: FloatArray,
        dither: Boolean,
        rowOut: ByteArray,
    ) {
        nextErr.fill(0f)
        for (x in 0 until width) {
            val px = pixels[x]
            val a = (px ushr 24) and 0xFF
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            // Composite transparent pixels over white paper
            val lum = if (a < 255) {
                val alphaF = a / 255f
                val rawLum = 0.299f * r + 0.587f * g + 0.114f * b
                rawLum * alphaF + 255f * (1f - alphaF)
            } else {
                0.299f * r + 0.587f * g + 0.114f * b
            }

            val oldVal = (lum + currErr[x + 1]).coerceIn(0f, 255f)
            val isBlack = oldVal < 145f
            if (isBlack) {
                val byteIdx = x ushr 3
                val bitMask = 0x80 ushr (x and 7)
                rowOut[byteIdx] = (rowOut[byteIdx].toInt() or bitMask).toByte()
            }

            if (dither) {
                val newVal = if (isBlack) 0f else 255f
                val err = oldVal - newVal
                currErr[x + 2] += err * (7f / 16f)
                nextErr[x] += err * (3f / 16f)
                nextErr[x + 1] += err * (5f / 16f)
                nextErr[x + 2] += err * (1f / 16f)
            }
        }
        System.arraycopy(nextErr, 0, currErr, 0, currErr.size)
    }

    internal fun validatePaperSize(options: PrintJobOptions) {
        val paper: PaperSize = options.paperSize
        require(paper.pjlName in SUPPORTED_PJL_PAPERS) {
            "Unsupported Brother DCP-L2520D paper size: ${paper.pjlName}"
        }
        val widthPx = paper.widthPx(options.resolution.dpi)
        val heightPx = paper.heightPx(options.resolution.dpi)
        val lineBytes = (widthPx + 7) / 8
        require(widthPx > 0 && heightPx > 0 && lineBytes < 10000) {
            "Invalid raster geometry ${widthPx}x${heightPx} for ${paper.pjlName} @ ${options.resolution.dpi} DPI"
        }
    }

    internal fun buildPageParams(options: PrintJobOptions): BrlaserPageParams {
        return BrlaserPageParams(
            numCopies = options.copies.coerceAtLeast(1),
            resolution = options.resolution.dpi,
            pageSpeed = 0,
            ras1200 = false,
            duplex = options.duplexMode != DuplexMode.SIMPLEX,
            tumble = options.duplexMode == DuplexMode.SHORT_EDGE,
            economode = options.tonerSave,
            densityAdjust = options.densityAdjust.coerceIn(-6, 6),
            sourcetray = "AUTO",
            mediatype = options.mediaType.pjlName,
            papersize = options.paperSize.pjlName,
        )
    }

    // =========================================================================
    // Strict 1:1 Kotlin port of brlaser block.h
    // =========================================================================
    internal class BrlaserBlock {
        private val lines = ArrayList<ByteArray>()
        private var lineBytes: Int = 0

        fun empty(): Boolean = lineBytes == 0

        fun addLine(line: ByteArray) {
            check(line.isNotEmpty())
            check(lineFits(line.size))
            lineBytes += line.size
            lines.add(line)
        }

        fun lineFits(size: Int): Boolean = lineBytes + size < MAX_BLOCK_SIZE

        fun flush(out: OutputStream) {
            if (!empty()) {
                // fprintf(f, "%dw%c%c", line_bytes_ + 2, 0, static_cast<int>(lines_.size()));
                out.write("${lineBytes + 2}w".toByteArray(Charsets.US_ASCII))
                out.write(0)
                out.write(lines.size and 0xFF)
                for (line in lines) {
                    out.write(line)
                }
                lineBytes = 0
                lines.clear()
            }
        }

        companion object {
            const val MAX_BLOCK_SIZE = 16350
        }
    }

    // =========================================================================
    // Strict 1:1 Kotlin port of brlaser line.h / line.cc
    // =========================================================================
    internal object BrlaserLineEncoder {

        private fun writeOverflow(value: Int, out: ByteArrayOutputStream) {
            if (value >= 0) {
                if (value < 255) {
                    out.write(value)
                } else {
                    val full = value / 255
                    repeat(full) { out.write(255) }
                    out.write(value % 255)
                }
            }
        }

        private fun writeSubstitute(
            offset: Int,
            src: ByteArray,
            first: Int,
            last: Int,
            out: ByteArrayOutputStream,
        ) {
            check(offset >= 0)
            check(offset < 10000)
            check(first != last)

            val offsetMax = 15
            val countMax = 7
            val count = (last - first) - 1

            val offsetLow = min(offset, offsetMax)
            val countLow = min(count, countMax)
            out.write((offsetLow shl 3) or countLow)
            writeOverflow(offset - offsetMax, out)
            writeOverflow(count - countMax, out)
            out.write(src, first, last - first)
        }

        private fun writeRepeat(
            offset: Int,
            rawCount: Int,
            value: Int,
            out: ByteArrayOutputStream,
        ) {
            check(offset >= 0)
            check(offset < 10000)
            check(rawCount >= 2)
            check(rawCount < 10000)

            val offsetMax = 3
            val countMax = 31
            val count = rawCount - 2

            val offsetLow = min(offset, offsetMax)
            val countLow = min(count, countMax)
            out.write(128 or (offsetLow shl 5) or countLow)
            writeOverflow(offset - offsetMax, out)
            writeOverflow(count - countMax, out)
            out.write(value and 0xFF)
        }

        private fun allZeros(buf: ByteArray): Boolean {
            for (b in buf) {
                if (b != 0.toByte()) return false
            }
            return true
        }

        private fun skipToNextMismatch(
            line: ByteArray,
            reference: ByteArray,
            start: Int,
            end: Int,
        ): Int {
            var idx = start
            while (idx < end && line[idx] == reference[idx]) {
                idx++
            }
            return idx - start
        }

        private fun repeatLength(buf: ByteArray, first: Int, last: Int): Int {
            if (first != last) {
                val k = buf[first]
                var idx = first + 1
                while (idx < last && buf[idx] == k) {
                    idx++
                }
                return idx - first
            }
            return 0
        }

        private fun substituteLength(
            line: ByteArray,
            first: Int,
            last: Int,
            reference: ByteArray,
        ): Int {
            if (first != last) {
                var it1 = first
                var next1 = first + 1
                var prev1 = first
                while (next1 != last) {
                    if (line[it1] == reference[it1] && line[next1] == reference[next1]) {
                        return it1 - first
                    }
                    if (line[it1] == line[next1] && line[it1] == line[prev1]) {
                        return prev1 - first
                    }
                    prev1 = it1
                    it1 = next1
                    next1++
                }
            }
            return last - first
        }

        private fun reserveSize(line: ByteArray): Int = line.size + 16

        fun encodeLine(line: ByteArray, reference: ByteArray): ByteArray {
            check(line.size == reference.size)
            if (allZeros(line)) {
                return byteArrayOf(0xFF.toByte())
            }

            val output = ByteArrayOutputStream(reserveSize(line))
            output.write(0) // first byte is the edit count

            val maxEdits = 254
            var numEdits = 0

            var lineIdx = 0
            // std::mismatch(line.rbegin(), line.rend(), reference.rbegin()).first.base()
            var lineEnd = line.size
            while (lineEnd > 0 && line[lineEnd - 1] == reference[lineEnd - 1]) {
                lineEnd--
            }

            while (true) {
                val offset = skipToNextMismatch(line, reference, lineIdx, lineEnd)
                lineIdx += offset
                if (lineIdx == lineEnd) {
                    // No more differences, we're done.
                    break
                }

                if (++numEdits == maxEdits) {
                    // We've run out of edits. Just output the rest of the line in a big
                    // substitute command.
                    writeSubstitute(offset, line, lineIdx, lineEnd, output)
                    break
                }

                val s = substituteLength(line, lineIdx, lineEnd, reference)
                if (s > 0) {
                    writeSubstitute(offset, line, lineIdx, lineIdx + s, output)
                    lineIdx += s
                } else {
                    val r = repeatLength(line, lineIdx, lineEnd)
                    check(r >= 2)
                    writeRepeat(offset, r, line[lineIdx].toInt() and 0xFF, output)
                    lineIdx += r
                }
            }

            check(numEdits <= maxEdits)
            val result = output.toByteArray()
            result[0] = (numEdits and 0xFF).toByte()
            return result
        }

        fun encodeLine(line: ByteArray): ByteArray {
            if (allZeros(line)) {
                return byteArrayOf(0xFF.toByte())
            }
            val buf = ByteArrayOutputStream(reserveSize(line))
            buf.write(1)
            writeSubstitute(0, line, 0, line.size, buf)
            return buf.toByteArray()
        }
    }

    // =========================================================================
    // Strict 1:1 Kotlin port of brlaser job.h / job.cc
    // =========================================================================
    internal data class BrlaserPageParams(
        val numCopies: Int = 0,
        val resolution: Int = 0,
        val pageSpeed: Int = 0,
        val ras1200: Boolean = false,
        val duplex: Boolean = false,
        val tumble: Boolean = false,
        val economode: Boolean = false,
        val densityAdjust: Int = 0,
        val sourcetray: String = "",
        val mediatype: String = "",
        val papersize: String = "",
    )

    internal class BrlaserJob(
        private val out: OutputStream,
        rawJobName: String,
    ) {
        private val jobName: String = sanitizeJobName(rawJobName)
        private var pageParams: BrlaserPageParams = BrlaserPageParams()
        private var pages: Int = 0
        private var jobStarted: Boolean = false
        private var jobEnded: Boolean = false

        fun pages(): Int = pages

        fun beginJob() {
            if (jobStarted) return
            jobStarted = true
            out.write(ByteArray(128)) // 128 NUL bytes
            writeAscii("\u001B%-12345X@PJL\n")
            writeAscii("@PJL JOB NAME=\"$jobName\"\n")
        }

        fun endJob() {
            if (jobEnded) return
            jobEnded = true
            writeAscii("\u001B%-12345X@PJL\n")
            writeAscii("@PJL EOJ NAME=\"$jobName\"\n")
            writeAscii("\u001B%-12345X\n")
        }

        fun ensurePageHeader(params: BrlaserPageParams) {
            if (pageParams != params) {
                pageParams = params
                writePageHeader()
            }
        }

        private fun writePageHeader() {
            writeAscii("\u001B%-12345X@PJL\n")
            if (pageParams.ras1200) {
                writeAscii("@PJL SET RAS1200MODE = TRUE\n")
                writeAscii("@PJL SET RESOLUTION = 600\n")
            } else {
                writeAscii("@PJL SET RAS1200MODE = FALSE\n")
                writeAscii("@PJL SET RESOLUTION = ${pageParams.resolution}\n")
                if (pageParams.resolution == 1200) {
                    writeAscii("@PJL SET PAPERFEEDSPEED = ${if (pageParams.pageSpeed != 0) "FULL" else "HALF"}\n")
                }
            }
            writeAscii("@PJL SET ECONOMODE = ${if (pageParams.economode) "ON" else "OFF"}\n")
            writeAscii("@PJL SET SOURCETRAY = ${pageParams.sourcetray}\n")
            writeAscii("@PJL SET MEDIATYPE = ${pageParams.mediatype}\n")
            writeAscii("@PJL SET DENSITY=${pageParams.densityAdjust}\n")
            writeAscii("@PJL SET DEVBIASADJUST=${pageParams.densityAdjust}\n")
            writeAscii("@PJL SET PAPER = ${pageParams.papersize}\n")
            writeAscii("@PJL SET PAGEPROTECT = AUTO\n")
            writeAscii("@PJL SET ORIENTATION = PORTRAIT\n")
            writeAscii("@PJL ENTER LANGUAGE = PCL\n")

            writeAscii("\u001BE")
            writeAscii("\u001B&l${max(1, pageParams.numCopies)}X")
            writeAscii("\u001B&u${pageParams.resolution}D")
            writeAscii("\u001B*t${if (pageParams.ras1200) 600 else pageParams.resolution}R")

            when {
                !pageParams.duplex -> writeAscii("\u001B&l0S") // Simplex
                pageParams.tumble -> writeAscii("\u001B&l2S") // Duplex short-edge
                else -> writeAscii("\u001B&l1S") // Duplex long-edge
            }
        }

        fun encodePage(
            params: BrlaserPageParams,
            lines: Int,
            lineSize: Int,
            nextLine: (ByteArray) -> Boolean,
        ) {
            if (pages == 0) {
                beginJob()
            }
            pages++

            if (pageParams != params) {
                pageParams = params
                writePageHeader()
            }

            var line = ByteArray(lineSize)
            var reference = ByteArray(lineSize)
            val block = BrlaserBlock()

            if (!nextLine(line)) {
                return
            }
            block.addLine(BrlaserLineEncoder.encodeLine(line))
            run {
                val tmp = line
                line = reference
                reference = tmp
            }

            writeAscii("\u001B*b1030m")

            val linesPerBand = 64

            var i = 1
            while (i < lines && nextLine(line)) {
                var encoded: ByteArray
                if (i % linesPerBand == 0) {
                    block.flush(out)
                    encoded = BrlaserLineEncoder.encodeLine(line)
                } else {
                    encoded = BrlaserLineEncoder.encodeLine(line, reference)
                    if (!block.lineFits(encoded.size)) {
                        block.flush(out)
                        encoded = BrlaserLineEncoder.encodeLine(line)
                    }
                }
                block.addLine(encoded)
                val tmp = line
                line = reference
                reference = tmp
                i++
            }

            block.flush(out)
            writeAscii("1030M\u000C")
            out.flush()
        }

        private fun writeAscii(text: String) {
            out.write(text.toByteArray(Charsets.US_ASCII))
        }

        companion object {
            fun sanitizeJobName(raw: String): String {
                val sb = StringBuilder(raw.length)
                for (ch in raw) {
                    val code = ch.code
                    if (code < 32 || code >= 127 || ch == '"' || ch == '\\') {
                        sb.append(' ')
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }
        }
    }
}
