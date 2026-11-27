package com.example.printer.render

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Decoded 1796-byte PWG Raster v2 (PWG 5102.4) page header fields.
 */
data class PwgPageHeader(
    val hwResolutionX: Int,
    val hwResolutionY: Int,
    val cupsWidth: Int,
    val cupsHeight: Int,
    val cupsBitsPerColor: Int,
    val cupsBitsPerPixel: Int,
    val cupsBytesPerLine: Int,
    val cupsColorOrder: Int,
    val cupsColorSpace: Int,
    val cupsNumColors: Int,
)

/**
 * Decodes `image/pwg-raster` ("RaS2", PWG 5102.4) print streams sent by Windows
 * `Microsoft IPP Class Driver`, macOS, iOS, and CUPS into per-page monochrome/grayscale bitmaps.
 *
 * Supports:
 * - 4-byte `"RaS2"` (`0x52615332`) big-endian sync word
 * - 1796-byte big-endian PWG page headers
 * - PWG PackBits scanline & line-repeat decompression (`0..127` = repeat pixel-unit `c + 1` times,
 *   `128..255` = copy `257 - c` literal pixel-units)
 * - `black_1` (1-bit K-space monochrome), `sgray_8` (8-bit grayscale), `w_1`/`w_8`, and `srgb_8` (24-bit fallback)
 */
object PwgRasterDecoder {

    const val PAGE_HEADER_SIZE = 1796

    // PWG 5102.4 / CUPS ColorSpace constants
    const val CSPACE_W = 0       // Luminance / white-based gray (0 = black, max = white)
    const val CSPACE_RGB = 1     // Device RGB
    const val CSPACE_K = 3       // Black ink channel (0 = white paper, max = black ink) — black_1
    const val CSPACE_SGRAY = 18  // sGray (0 = black, 255 = white) — sgray_8
    const val CSPACE_SRGB = 19   // sRGB — srgb_8

    // Guard rails against malformed/corrupt streams exhausting memory
    private const val MAX_PAGE_WIDTH = 16_384
    private const val MAX_PAGE_HEIGHT = 24_576
    private const val MAX_BYTES_PER_LINE = 65_536

    // Big-endian "RaS2" sync word (0x52 0x61 0x53 0x32)
    private val SYNC_RAS2 = byteArrayOf(0x52, 0x61, 0x53, 0x32)

    fun isPwgRasterHeader(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 4) return false
        return bytes[0] == SYNC_RAS2[0] &&
            bytes[1] == SYNC_RAS2[1] &&
            bytes[2] == SYNC_RAS2[2] &&
            bytes[3] == SYNC_RAS2[3]
    }

    /**
     * Counts the number of pages in a PWG Raster file without allocating page bitmaps.
     */
    fun countPages(file: File): Int = file.inputStream().buffered(64 * 1024).use { countPages(it) }

    fun countPages(input: InputStream): Int {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        verifySyncWord(buffered)
        val headerBuf = ByteArray(PAGE_HEADER_SIZE)
        var pages = 0
        while (true) {
            val header = readNextPageHeader(buffered, headerBuf) ?: break
            skipPageRasterData(buffered, header)
            pages++
        }
        return pages
    }

    /**
     * Streams pages from a PWG Raster stream into [onPage] as `RGB_565` Android [Bitmap] objects.
     * Callers should recycle the provided bitmap after consuming it.
     */
    fun decodePages(
        input: InputStream,
        pageFilter: Set<Int>? = null,
        onPage: (pageIndex: Int, header: PwgPageHeader, bitmap: Bitmap) -> Unit,
    ) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        verifySyncWord(buffered)
        val headerBuf = ByteArray(PAGE_HEADER_SIZE)
        var pageIndex = 0
        val maxNeeded = pageFilter?.maxOrNull() ?: Int.MAX_VALUE

        while (true) {
            val header = readNextPageHeader(buffered, headerBuf) ?: break
            if (pageIndex > maxNeeded) break

            if (pageFilter != null && pageIndex !in pageFilter) {
                skipPageRasterData(buffered, header)
                pageIndex++
                continue
            }

            val bitmap = Bitmap.createBitmap(header.cupsWidth, header.cupsHeight, Bitmap.Config.RGB_565)
            try {
                decodePageRasterRows(buffered, header) { yStart, repeatCount, rowArgb ->
                    val endRow = (yStart + repeatCount).coerceAtMost(header.cupsHeight)
                    for (row in yStart until endRow) {
                        bitmap.setPixels(rowArgb, 0, header.cupsWidth, 0, row, header.cupsWidth, 1)
                    }
                }
                onPage(pageIndex, header, bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            pageIndex++
        }
    }

    /**
     * Pure-JVM decoder returning all pages as `(PwgPageHeader, IntArray ARGB pixels)` without
     * requiring Android `Bitmap` framework classes (ideal for JVM unit tests and inspection).
     */
    fun decodeAllPagesPixels(input: InputStream): List<Pair<PwgPageHeader, IntArray>> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
        verifySyncWord(buffered)
        val headerBuf = ByteArray(PAGE_HEADER_SIZE)
        val result = ArrayList<Pair<PwgPageHeader, IntArray>>()
        while (true) {
            val header = readNextPageHeader(buffered, headerBuf) ?: break
            val pixels = IntArray(header.cupsWidth * header.cupsHeight) { 0xFFFFFFFF.toInt() }
            decodePageRasterRows(buffered, header) { yStart, repeatCount, rowArgb ->
                val endRow = (yStart + repeatCount).coerceAtMost(header.cupsHeight)
                for (row in yStart until endRow) {
                    System.arraycopy(rowArgb, 0, pixels, row * header.cupsWidth, header.cupsWidth)
                }
            }
            result.add(header to pixels)
        }
        return result
    }

    private fun verifySyncWord(input: InputStream) {
        val sync = ByteArray(4)
        readFully(input, sync, 0, 4)
        if (!isPwgRasterHeader(sync)) {
            val hex = sync.joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
            throw IOException("Invalid PWG Raster sync word 0x$hex (expected RaS2 / 0x52615332)")
        }
    }

    private fun readNextPageHeader(input: InputStream, buf: ByteArray): PwgPageHeader? {
        val first = input.read()
        if (first == -1) return null
        buf[0] = first.toByte()
        readFully(input, buf, 1, PAGE_HEADER_SIZE - 1)

        val hwResX = readIntBE(buf, 276).takeIf { it > 0 } ?: 300
        val hwResY = readIntBE(buf, 280).takeIf { it > 0 } ?: hwResX
        val width = readIntBE(buf, 372)
        val height = readIntBE(buf, 376)
        val bitsPerColor = readIntBE(buf, 384).takeIf { it > 0 } ?: 1
        val bitsPerPixel = readIntBE(buf, 388).takeIf { it > 0 } ?: bitsPerColor
        val rawBytesPerLine = readIntBE(buf, 392)
        val bytesPerLine = if (rawBytesPerLine > 0) {
            rawBytesPerLine
        } else {
            (width * bitsPerPixel + 7) / 8
        }
        val colorOrder = readIntBE(buf, 396)
        val colorSpace = readIntBE(buf, 400)
        val numColors = readIntBE(buf, 420).takeIf { it > 0 } ?: 1

        if (width !in 1..MAX_PAGE_WIDTH || height !in 1..MAX_PAGE_HEIGHT) {
            throw IOException("Invalid PWG Raster dimensions: ${width}x$height")
        }
        if (bytesPerLine !in 1..MAX_BYTES_PER_LINE) {
            throw IOException("Invalid PWG Raster bytesPerLine: $bytesPerLine")
        }

        return PwgPageHeader(
            hwResolutionX = hwResX,
            hwResolutionY = hwResY,
            cupsWidth = width,
            cupsHeight = height,
            cupsBitsPerColor = bitsPerColor,
            cupsBitsPerPixel = bitsPerPixel,
            cupsBytesPerLine = bytesPerLine,
            cupsColorOrder = colorOrder,
            cupsColorSpace = colorSpace,
            cupsNumColors = numColors,
        )
    }

    private fun decodePageRasterRows(
        input: InputStream,
        header: PwgPageHeader,
        onRowDecoded: (yStart: Int, repeatCount: Int, rowArgb: IntArray) -> Unit,
    ) {
        val bytesPerLine = header.cupsBytesPerLine
        val unitBytes = ((header.cupsBitsPerPixel + 7) / 8).coerceAtLeast(1)
        val lineBuf = ByteArray(bytesPerLine)
        val unitBuf = ByteArray(unitBytes)
        val rowArgb = IntArray(header.cupsWidth)

        var y = 0
        while (y < header.cupsHeight) {
            val repeatByte = input.read()
            if (repeatByte < 0) break
            val repeatLines = (repeatByte and 0xFF) + 1

            unpackPackBitsScanline(input, lineBuf, bytesPerLine, unitBytes, unitBuf)
            convertScanlineToArgb(lineBuf, header, rowArgb)
            onRowDecoded(y, repeatLines, rowArgb)
            y += repeatLines
        }
    }

    private fun skipPageRasterData(input: InputStream, header: PwgPageHeader) {
        val bytesPerLine = header.cupsBytesPerLine
        val unitBytes = ((header.cupsBitsPerPixel + 7) / 8).coerceAtLeast(1)
        val scratch = ByteArray(maxOf(unitBytes, 129 * unitBytes))

        var y = 0
        while (y < header.cupsHeight) {
            val repeatByte = input.read()
            if (repeatByte < 0) break
            val repeatLines = (repeatByte and 0xFF) + 1

            var outPos = 0
            while (outPos < bytesPerLine) {
                val ctrl = input.read()
                if (ctrl < 0) return
                val b = ctrl and 0xFF
                if (b <= 127) {
                    val count = b + 1
                    readFully(input, scratch, 0, unitBytes)
                    outPos += count * unitBytes
                } else {
                    val count = 257 - b
                    val literalBytes = count * unitBytes
                    readFully(input, scratch, 0, literalBytes)
                    outPos += literalBytes
                }
            }
            y += repeatLines
        }
    }

    /**
     * Unpacks a single PWG Raster PackBits-compressed scanline (PWG 5102.4 §4.3.1):
     * - Control byte `0..127`: repeat next `unitBytes`-byte pixel/octet `(ctrl + 1)` times.
     * - Control byte `128..255`: copy `(257 - ctrl)` literal `unitBytes`-byte pixels/octets.
     */
    private fun unpackPackBitsScanline(
        input: InputStream,
        outLine: ByteArray,
        bytesPerLine: Int,
        unitBytes: Int,
        unitBuf: ByteArray,
    ) {
        var pos = 0
        while (pos < bytesPerLine) {
            val ctrl = input.read()
            if (ctrl < 0) {
                // Pad any remaining bytes with white/zero on truncated stream
                outLine.fill(0, pos, bytesPerLine)
                return
            }
            val b = ctrl and 0xFF
            if (b <= 127) {
                val count = b + 1
                readFully(input, unitBuf, 0, unitBytes)
                repeat(count) {
                    val toCopy = minOf(unitBytes, bytesPerLine - pos)
                    if (toCopy > 0) {
                        System.arraycopy(unitBuf, 0, outLine, pos, toCopy)
                        pos += toCopy
                    }
                }
            } else {
                val count = 257 - b
                val literalBytes = count * unitBytes
                val toStore = minOf(literalBytes, bytesPerLine - pos)
                if (toStore > 0) {
                    readFully(input, outLine, pos, toStore)
                    pos += toStore
                }
                val extra = literalBytes - toStore
                if (extra > 0) {
                    skipFully(input, extra)
                }
            }
        }
    }

    private fun convertScanlineToArgb(
        lineBuf: ByteArray,
        header: PwgPageHeader,
        outArgb: IntArray,
    ) {
        val width = header.cupsWidth
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()

        when (header.cupsBitsPerPixel) {
            1 -> {
                // black_1 (CSPACE_K == 3): bit 1 = black ink, bit 0 = white paper.
                // w_1 / sgray_1 (CSPACE_W == 0 or CSPACE_SGRAY == 18): bit 0 = black, bit 1 = white.
                val bitOneIsBlack = header.cupsColorSpace == CSPACE_K
                for (x in 0 until width) {
                    val byteIdx = x ushr 3
                    val bit = if (byteIdx < lineBuf.size) {
                        (lineBuf[byteIdx].toInt() ushr (7 - (x and 7))) and 1
                    } else {
                        0
                    }
                    val isBlack = if (bitOneIsBlack) (bit == 1) else (bit == 0)
                    outArgb[x] = if (isBlack) black else white
                }
            }

            8 -> {
                // sgray_8 (CSPACE_SGRAY == 18 or CSPACE_W == 0): 0 = black, 255 = white.
                // black_8 (CSPACE_K == 3): 0 = white, 255 = black.
                val invertK = header.cupsColorSpace == CSPACE_K
                for (x in 0 until width) {
                    val raw = if (x < lineBuf.size) (lineBuf[x].toInt() and 0xFF) else if (invertK) 0 else 255
                    val gray = if (invertK) (255 - raw) else raw
                    outArgb[x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }

            24 -> {
                // srgb_8 / rgb_8 fallback
                for (x in 0 until width) {
                    val idx = x * 3
                    if (idx + 2 < lineBuf.size) {
                        val r = lineBuf[idx].toInt() and 0xFF
                        val g = lineBuf[idx + 1].toInt() and 0xFF
                        val b = lineBuf[idx + 2].toInt() and 0xFF
                        outArgb[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        outArgb[x] = white
                    }
                }
            }

            32 -> {
                // CMYK_8 or RGBA_8 defensive fallback
                for (x in 0 until width) {
                    val idx = x * 4
                    if (idx + 3 < lineBuf.size) {
                        val c0 = lineBuf[idx].toInt() and 0xFF
                        val c1 = lineBuf[idx + 1].toInt() and 0xFF
                        val c2 = lineBuf[idx + 2].toInt() and 0xFF
                        val c3 = lineBuf[idx + 3].toInt() and 0xFF
                        val gray = (255 - ((c0 * 30 + c1 * 59 + c2 * 11) / 100 + c3).coerceAtMost(255)).coerceIn(0, 255)
                        outArgb[x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                    } else {
                        outArgb[x] = white
                    }
                }
            }

            else -> {
                // Generic single-byte grayscale fallback
                for (x in 0 until width) {
                    val gray = if (x < lineBuf.size) (lineBuf[x].toInt() and 0xFF) else 255
                    outArgb[x] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
        }
    }

    private fun readIntBE(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int) {
        var remaining = len
        var pos = off
        while (remaining > 0) {
            val n = input.read(buf, pos, remaining)
            if (n < 0) throw EOFException("Unexpected EOF while reading PWG Raster stream ($remaining bytes short)")
            pos += n
            remaining -= n
        }
    }

    private fun skipFully(input: InputStream, len: Int) {
        var remaining = len
        val sink = ByteArray(minOf(len, 1024))
        while (remaining > 0) {
            val n = input.read(sink, 0, minOf(remaining, sink.size))
            if (n < 0) throw EOFException("Unexpected EOF while skipping PWG Raster bytes")
            remaining -= n
        }
    }
}
